#!/usr/bin/env python3
"""Build the self-contained Windows libVLC bundle from the official VLC win64 zip.

Layout (flat, matching VLC's natural Windows layout):
    libvlc.dll
    libvlccore.dll
    plugins/  (all plugin categories EXCEPT gui/ — Qt5 not in the zip, and vlcj
               embeds the player directly, never loads the Qt GUI interface)

VLC win64 is already self-contained (all codec/demux deps statically linked
into each plugin DLL), so unlike Linux there is NO transitive dependency
harvesting needed. We only drop the Qt/skins GUI plugins.

Output: /tmp/libvlc-windows-x64.zip
"""
import os, shutil, sys, zipfile

SRC = "/tmp/vlcwin/vlc-3.0.23"
STAGE = "/tmp/vlc_win_stage"
EXCLUDE_PLUGIN_DIRS = {"gui"}  # libqt_plugin.dll + libskins2_plugin.dll need Qt5
# Browser plugins (npvlc/axvlc) and installer data are not needed for vlcj.
EXCLUDE_ROOT = {"axvlc.dll", "npvlc.dll"}

def main():
    if os.path.exists(STAGE):
        shutil.rmtree(STAGE)
    os.makedirs(os.path.join(STAGE, "plugins"))

    # core DLLs
    for name in ["libvlc.dll", "libvlccore.dll"]:
        shutil.copy2(os.path.join(SRC, name), os.path.join(STAGE, name))

    # plugins
    n_plugins = 0
    plugins_src = os.path.join(SRC, "plugins")
    for cat in sorted(os.listdir(plugins_src)):
        if cat in EXCLUDE_PLUGIN_DIRS:
            continue
        catdir = os.path.join(plugins_src, cat)
        if not os.path.isdir(catdir):
            continue
        for f in os.listdir(catdir):
            if f.endswith(".dll"):
                dst = os.path.join(STAGE, "plugins", cat, f)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(os.path.join(catdir, f), dst)
                n_plugins += 1

    # lua scripts (playlist parsing for HLS/YouTube etc. — small, keep)
    lua_src = os.path.join(SRC, "lua")
    if os.path.isdir(lua_src):
        shutil.copytree(lua_src, os.path.join(STAGE, "lua"))

    zip_path = "/tmp/libvlc-windows-x64.zip"
    if os.path.exists(zip_path):
        os.remove(zip_path)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, dirs, files in os.walk(STAGE):
            for f in files:
                full = os.path.join(root, f)
                arc = os.path.relpath(full, STAGE)
                z.write(full, arc)

    sz = os.path.getsize(zip_path) / 1e6
    print(f"DONE: libvlc.dll + libvlccore.dll + {n_plugins} plugin DLLs -> {zip_path} ({sz:.1f} MB)")

if __name__ == "__main__":
    main()
