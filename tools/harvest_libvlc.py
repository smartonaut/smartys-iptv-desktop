#!/usr/bin/env python3
"""Harvest a complete, self-contained libVLC bundle from the system VLC 3.0.23.

Produces the exact layout VlcPlayer.kt expects when extracted to
~/.smartys-iptv/libvlc/:
    lib/       -> libvlc.so.5, libvlccore.so.9, helper libs, ALL transitive deps
    plugins/   -> every plugin .so + plugins.dat

Every bundled object gets RUNPATH rewritten so the closure resolves internally:
    lib/*       RUNPATH = $ORIGIN
    plugins/*   RUNPATH = $ORIGIN/../lib
This removes the dependency on a system VLC install (the "self-contained" rule).

Excluded (GUI-only / huge-dep, useless for vlcj embedding):
    gui/*, visualization/*, video_splitter/*, notify/* (Qt5/GTK3 stack)
    access libsmb/libdsm/libvnc (samba etc.)
    disc access (dvdnav/dvdread/bluray) — IPTV doesn't need optical discs
Excluded from dep closure (glibc base, always present on any Linux):
    libc, libm, libpthread, librt, libdl, libresolv, libmvec, libgcc_s,
    ld-linux, linux-vdso, libnsl, libutil
"""
import os, subprocess, shutil, sys, zipfile, glob

SRC_VLC = "/usr/lib/x86_64-linux-gnu/vlc"
SRC_LIB = "/usr/lib/x86_64-linux-gnu"
STAGE = "/tmp/vlc_bundle_stage"

# glibc base set — never bundled (present on every Linux target)
BASE = {
    "linux-vdso.so.1", "ld-linux-x86-64.so.2",
    "libc.so.6", "libm.so.6", "libpthread.so.0", "librt.so.1",
    "libdl.so.2", "libresolv.so.2", "libmvec.so.1", "libgcc_s.so.1",
    "libnsl.so.1", "libnsl.so.2", "libutil.so.1", "libcrypt.so.1",
    "libanl.so.1", "libBrokenLocale.so.1", "libnss_files.so.2",
}

def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)

def plugin_blacklist():
    """Plugin dirs/files to skip (GUI-only or huge unnecessary deps)."""
    skip_dirs = {"gui", "visualization", "video_splitter", "notify"}
    skip_files = {
        "access/libsmb_plugin.so", "access/libdsm_plugin.so", "access/libvnc_plugin.so",
        "access/libdvdnav_plugin.so", "access/libdvdread_plugin.so", "access/libbluray_plugin.so",
        "access/liblibbluray_plugin.so", "access/libcdda_plugin.so", "access/libvcd_plugin.so",
    }
    return skip_dirs, skip_files

def ldd_deps(path):
    """Return list of (soname, resolved_path) for a given .so."""
    r = run(f"ldd '{path}'")
    deps = []
    for line in r.stdout.splitlines():
        if "=> not found" in line:
            lib = line.split("=>")[0].strip()
            sys.stderr.write(f"  MISSING DEP: {lib} (from {os.path.basename(path)})\n")
            continue
        if "=>" not in line:
            continue
        # left side = soname, right side = resolved path (may itself be a symlink)
        soname = line.split("=>")[0].strip()
        right = line.split("=>", 1)[1].strip()
        resolved = right.split(" (")[0].strip()
        if resolved.startswith("/"):
            deps.append((soname, resolved))
    return deps

def main():
    if os.path.exists(STAGE):
        shutil.rmtree(STAGE)
    libdir = os.path.join(STAGE, "lib")
    plugdir = os.path.join(STAGE, "plugins")
    os.makedirs(libdir)
    os.makedirs(plugdir)

    # 1) core libs
    for name in ["libvlc.so.5", "libvlc.so.5.6.1", "libvlccore.so.9", "libvlccore.so.9.0.1"]:
        src = os.path.join(SRC_LIB, name)
        if os.path.exists(src):
            shutil.copy2(src, libdir)
    # Unversioned names: JNA Native.load("vlc") maps to "libvlc.so" on Linux,
    # and System.load may want "libvlccore.so". Copy the versioned real files
    # under the unversioned names too (same bytes, regular files — zip-safe).
    for unver, ver in [("libvlc.so", "libvlc.so.5"), ("libvlccore.so", "libvlccore.so.9")]:
        src = os.path.join(libdir, ver)
        if os.path.exists(src) and not os.path.exists(os.path.join(libdir, unver)):
            shutil.copy2(src, os.path.join(libdir, unver))
    # helper modules
    for name in ["libvlc_pulse.so.0.0.0", "libvlc_pulse.so.0",
                 "libvlc_xcb_events.so.0.0.0", "libvlc_xcb_events.so.0",
                 "libvlc_vdpau.so.0.0.0", "libvlc_vdpau.so.0"]:
        src = os.path.join(SRC_VLC, name)
        if os.path.exists(src):
            shutil.copy2(src, libdir)

    # 2) plugins
    skip_dirs, skip_files = plugin_blacklist()
    copied_plugins = []
    for root, dirs, files in os.walk(os.path.join(SRC_VLC, "plugins")):
        relroot = os.path.relpath(root, os.path.join(SRC_VLC, "plugins"))
        toplevel = relroot.split(os.sep)[0] if relroot != "." else ""
        if toplevel in skip_dirs:
            dirs[:] = []
            continue
        for f in files:
            rel = os.path.join(relroot, f) if relroot != "." else f
            if rel in skip_files:
                continue
            # Ship NO plugins.dat: it records per-plugin mtimes, so after
            # re-extraction it reads "stale" and libVLC rescans anyway. Omitting
            # it makes libVLC generate a fresh cache on first launch (the
            # plugins dir under ~/.smartys-iptv is user-writable).
            if f == "plugins.dat":
                continue
            if f.endswith(".so"):
                src = os.path.join(root, f)
                dst = os.path.join(plugdir, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)
                copied_plugins.append(dst)

    # 3) transitive dependency closure — copy each dep ONCE under its SONAME
    #    (ELF NEEDED entries request the SONAME, e.g. libavcodec.so.62, never
    #    the fully-versioned real name). This avoids zipfile dereferencing
    #    symlinks into ~320MB of duplicates AND makes the ZipInputStream
    #    extraction in VlcPlayer.kt produce correct regular files.
    all_targets = [os.path.join(libdir, f) for f in os.listdir(libdir)]
    all_targets += copied_plugins
    todo = list(all_targets)
    seen = set()  # soname already bundled

    while todo:
        cur = todo.pop()
        for soname, resolved in ldd_deps(cur):
            if soname in BASE:
                continue
            real = os.path.realpath(resolved)
            if real.startswith(STAGE):
                continue
            if soname in seen:
                continue
            seen.add(soname)
            dst = os.path.join(libdir, soname)
            if not os.path.exists(dst):
                shutil.copy2(real, dst)
                todo.append(dst)

    # 4) rewrite RUNPATH
    #   lib/*   -> $ORIGIN            (all deps flat in same dir)
    #   plugins/<cat>/<name>.so -> $ORIGIN/../../lib   (nested one level)
    # NOTE: match ".so" AND ".so.N" — the core libs are libvlc.so.5 / .so.9.
    import re
    so_re = re.compile(r"\.so(\.\d+)*$")
    for root, dirs, files in os.walk(STAGE):
        for f in files:
            if not so_re.search(f):
                continue
            p = os.path.join(root, f)
            if p.startswith(plugdir):
                rp = "$ORIGIN/../../lib"
            else:
                rp = "$ORIGIN"
            r = run(f"patchelf --set-rpath '{rp}' '{p}' 2>&1")
            if r.returncode != 0:
                sys.stderr.write(f"patchelf FAIL {p}: {r.stdout}{r.stderr}\n")

    # 5) write zip
    zip_path = "/tmp/libvlc-linux-x64.zip"
    if os.path.exists(zip_path):
        os.remove(zip_path)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, dirs, files in os.walk(STAGE):
            for f in files:
                full = os.path.join(root, f)
                arc = os.path.relpath(full, STAGE)
                z.write(full, arc)

    nlibs = len([f for f in os.listdir(libdir) if f.endswith(".so")])
    nplugins = sum(1 for root, d, fs in os.walk(plugdir) for f in fs if f.endswith(".so"))
    sz = os.path.getsize(zip_path) / 1e6
    print(f"DONE: {nlibs} libs, {nplugins} plugins -> {zip_path} ({sz:.1f} MB)")

if __name__ == "__main__":
    main()
