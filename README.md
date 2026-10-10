# Smartys IPTV Desktop

Plattformübergreifender IPTV-Player (Kotlin, Compose Multiplatform).

## Features

- Live-TV, VOD und Serien
- Aufnahme-Engine (Timer, Doppel-Daemon-Schutz)
- Auto-Advance bei Serien

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./build.sh
# → desktop/build/compose/binaries/main/deb/smartysiptv_*.deb
```

## Hinweis

Zugangsdaten werden per Pairing vom Panel geladen, nicht im Code gespeichert.

## Lizenz

MIT — siehe [LICENSE](LICENSE).
