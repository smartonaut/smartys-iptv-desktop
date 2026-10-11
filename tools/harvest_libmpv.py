#!/usr/bin/env python3
"""Harvest ein self-contained libmpv-Bundle (libmpv.so + transitive ffmpeg-Deps).

Gleiches Muster wie harvest_libvlc.py, aber libmpv hat keine plugins/-Hierarchie —
alles landet flach in lib/ mit RUNPATH $ORIGIN. Ergebnis: /tmp/libmpv-linux-x64.zip
wird in die Resources (src/jvmMain/resources/libmpv/) gelegt und von MpvPlayer.kt
beim ersten Start nach ~/.smartys-iptv/libmpv/ extrahiert.
"""
import os, subprocess, shutil, zipfile, re

SRC_LIB = "/usr/lib/x86_64-linux-gnu"
STAGE = "/tmp/libmpv_bundle_stage"

# glibc/stdlib-Basis — auf jedem Linux vorhanden, nie bündeln
BASE = {
    "linux-vdso.so.1", "ld-linux-x86-64.so.2",
    "libc.so.6", "libm.so.6", "libpthread.so.0", "librt.so.1",
    "libdl.so.2", "libresolv.so.2", "libmvec.so.1", "libgcc_s.so.1",
    "libnsl.so.1", "libnsl.so.2", "libutil.so.1", "libcrypt.so.1",
    "libanl.so.1", "libBrokenLocale.so.1", "libnss_files.so.2",
    "libstdc++.so.6",
    # X11/GL-Basis — über jpackage-Depends (libx11-6, libgl1, ...) gedeckt
    "libX11.so.6", "libXfixes.so.3", "libXext.so.6", "libXrender.so.1",
    "libXau.so.6", "libXdmcp.so.6", "libxcb.so.1", "libGL.so.1",
    "libGLX.so.0", "libOpenGL.so.0", "libdrm.so.2", "libgbm.so.1",
    "libwayland-client.so.0", "libwayland-cursor.so.0", "libxkbcommon.so.0",
}

def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True)

def ldd_deps(path):
    r = run(f"ldd '{path}'")
    deps = []
    for line in r.stdout.splitlines():
        if "=> not found" in line:
            lib = line.split("=>")[0].strip()
            print(f"  MISSING DEP: {lib} (from {os.path.basename(path)})", file=__import__("sys").stderr)
            continue
        if "=>" not in line:
            continue
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
    os.makedirs(libdir)

    # 1) core libs — versioniert + unversioniert (JNA Native.load("mpv") -> libmpv.so)
    for name in ["libmpv.so.2.5.0", "libmpv.so.2"]:
        src = os.path.join(SRC_LIB, name)
        if os.path.exists(src):
            shutil.copy2(src, libdir)
    unver = os.path.join(libdir, "libmpv.so")
    if not os.path.exists(unver):
        shutil.copy2(os.path.join(libdir, "libmpv.so.2"), unver)

    # 2) transitive dependency closure (ffmpeg-Libs etc.)
    todo = [os.path.join(libdir, f) for f in os.listdir(libdir)]
    seen = set()
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

    # 3) RUNPATH $ORIGIN (alle flach)
    so_re = re.compile(r"\.so(\.\d+)*$")
    for f in os.listdir(libdir):
        if so_re.search(f):
            p = os.path.join(libdir, f)
            r = run(f"patchelf --set-rpath '$ORIGIN' '{p}' 2>&1")
            if r.returncode != 0:
                print(f"patchelf FAIL {p}: {r.stdout}{r.stderr}", file=__import__("sys").stderr)

    # 4) zip
    zip_path = "/tmp/libmpv-linux-x64.zip"
    if os.path.exists(zip_path):
        os.remove(zip_path)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(os.listdir(libdir)):
            z.write(os.path.join(libdir, f), os.path.join("lib", f))

    nlibs = len([f for f in os.listdir(libdir) if f.endswith(".so") or ".so." in f])
    sz = os.path.getsize(zip_path) / 1e6
    print(f"DONE: {nlibs} libs -> {zip_path} ({sz:.1f} MB)")

if __name__ == "__main__":
    main()
