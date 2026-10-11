#!/usr/bin/env bash
# Smartys IPTV — Build + Install + 3-Backup-Regel
# Baut das .deb, installiert es, und behält die letzten 3 Builds in backups/.
set -euo pipefail

cd "$(dirname "$0")"

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

echo "==> Kompiliere + baue .deb ..."
gradle :desktop:packageDeb --console=plain

DEB=$(ls -1t desktop/build/compose/binaries/main/deb/smartysiptv_*-1_amd64.deb 2>/dev/null | head -1)
if [[ ! -f "$DEB" ]]; then
    echo "FEHLER: $DEB nicht gefunden" >&2
    exit 1
fi

# ---- 3-Backup-Regel: aktuelle Version sichern, dann auf 3 kappen ----
mkdir -p backups
TS=$(date +%Y%m%d_%H%M%S)
VERSION=$(dpkg-deb -f "$DEB" Version 2>/dev/null || echo unknown)
cp "$DEB" "backups/smartysiptv_${VERSION}_${TS}_amd64.deb"
echo "==> Backup gesichert: backups/smartysiptv_${VERSION}_${TS}_amd64.deb"

# Nur die neuesten 3 .deb-Backups behalten
mapfile -t BACKUPS < <(ls -1t backups/smartysiptv_*.deb 2>/dev/null || true)
if (( ${#BACKUPS[@]} > 3 )); then
    for old in "${BACKUPS[@]:3}"; do
        echo "==> Lösche altes Backup (>3): $old"
        rm -f "$old"
    done
fi

# ---- Installieren ----
echo "==> Installiere ..."
sudo -n dpkg -i "$DEB"

echo "==> Fertig. Aktuelle Backups:"
ls -1t backups/smartysiptv_*.deb 2>/dev/null | head -3
