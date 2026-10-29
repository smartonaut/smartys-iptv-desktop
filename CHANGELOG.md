# Changelog — Smartys Player (Desktop)

Alle Versionen im Überblick. Neueste Änderungen oben.

**Getrennte Release-Straßen:** Linux (deb/rpm) und Windows (.msi) werden
unabhängig gebaut und versioniert — die Nummern dürfen auseinanderlaufen.

---

## Linux (deb/rpm)

### 2.0.4 — 29.10.2026

**Stabile Player-ID (PLAYER-IDENTITY.md v1).** Neue Installationen leiten die
Player-ID deterministisch aus gerätestabilen Merkmalen ab
(`machine_id|os|nic`, SHA-256) statt sie zu würfeln; eine bereits gespeicherte
ID bleibt unangetastet. Zusätzlich liegt die Identität als Spiegel unter
`~/.config/smartys-iptv/identity.json` und übersteht damit das Löschen des
Profils. Hintergrund: die Panel-Line ist per AAD an die Player-ID gebunden —
eine verlorene ID bedeutet Neu-Einrichtung beim Nutzer.

### 2.0.0 — 24.10.2026

**36 Sprachen.** Alle europäischen Sprachen sowie Persisch und Urdu (beide
rechts-nach-links). Deutsch bleibt Quellsprache und Fallback.

- Oberfläche liegt jetzt als JSON unter `resources/i18n/` — neue Sprache heißt:
  eine Datei plus eine Zeile in `index.json`, kein Code. Siehe README.
- Sprachauswahl in den Einstellungen als Dropdown statt Chip-Reihe.
- Übersetzt: Navigation, Kanalliste, Einstellungen, Aufnahmen, Downloads,
  Player-OSD, Programm-Raster samt Zählern und Sortier-Labels.

**Parität zur iOS-App (build35–43):**

- **Programm-Raster** — sechs Stunden Fenster, Sender untereinander, laufende
  Sendung hervorgehoben, Klick startet den Kanal.
- **Staffel laden** im Staffelkopf, überspringt vorhandene Folgen.
- **IMDb- und Filmstarts-Links** im Film-Detail (nur Links, kein Scraping —
  filmstarts.de sperrt `/suchen/` per robots.txt).
- **Fehlgeschlagene Downloads löschen**, gesammelt in der Kopfleiste.
- **Abspann mit Rückfrage:** statt sofort zur nächsten Folge zu springen,
  fünf Sekunden Hinweis, `B` bricht ab.

**Absturz beim Beenden behoben (1.8.0).** `player.stop()` lief im UI-Thread;
bei einem hängenden Stream blockierte das die gesamte Oberfläche, die App ließ
sich nicht einmal per SIGTERM beenden (nur `kill -9`). Dazu:

- Spur- und Bildabfragen greifen nach `stop()` nicht mehr auf den abbauenden
  Player zu (führte zu `SIGSEGV in libc`).
- Erinnerungen laufen über `notify-send` statt `java.awt.SystemTray` — dessen
  Initialisierung kann auf GNOME den AWT-Thread blockieren.
- EPG-Abfrage der Glocke läuft in `Dispatchers.IO`, nicht im Zeichen-Thread.

### 1.5.39 — 19.10.2026
- **Live-TV-Gruppen** im Multi-Line-Modus nur noch **einmal** — Kategorien
  nach Name dedupliziert, Kanal-`categoryId` auf die kanonische ID je Gruppe
  normalisiert. Ein Klick auf eine Gruppe zeigt jetzt die Kanäle **aller**
  Lines gebündelt.
- **Ausgeblendete Gruppen** verstecken auch ihre Sender in „Alle Kanäle"
  (vorher verschwand nur die Sidebar-Gruppe, die Sender blieben sichtbar).
- **OSD-Logo** auf sichtbaren Inhalt zugeschnitten (`trimTransparent`) vor dem
  Skalieren — der Schriftzug füllt jetzt die Box (aus ~65×10 px wird
  ~184×29 px), statt in transparenten Rändern zu verschwinden.
- **OSD-Schrift** größer und klarer: Titel 24 px, EPG-Zeile 19 px,
  „Danach" 17 px, plus `FRACTIONALMETRICS` / `STROKE_PURE`.
- **Crash-Fix:** SIGSEGV beim Verlassen des Players — der OSD-Ausblend-Timer
  feuerte nach `release()` und rief `logo().enable(false)` auf dem
  freigegebenen nativen Player. Timer werden jetzt in `stop()`/`release()`
  vor dem Freigeben gestoppt, plus `playerReleased`-Guard.

### 1.5.34 — 19.10.2026
- OSD-EPG-Zeile ohne Gruppe/Kategorie — nur Startzeit, Titel, Endzeit, Uhrzeit.

### 1.5.33 — 19.10.2026
- Senderlogo größer (Box 152×60) + bikubische Interpolation, damit die
  Logo-Schrift scharf bleibt.

### 1.5.30 — 19.10.2026
- OSD als transparentes PNG über den VLC-Bild-Filter — echtes Alpha im
  Videobild statt AWT-Fenster; die AWT-Karte bleibt Fallback.

### 1.5.28–1.5.29 — 19.10.2026
- OSD-Platzierung: Fallback, wenn libVLC keine `videoDimension` liefert;
  Fenster-Transparenz vor `addNotify` setzen (sonst bleibt das Fenster opak).

### 1.5.26–1.5.27 — 19.10.2026
- OSD-Karte transparent/blau wie Android (Alpha 110, `#0C1424`), saubere
  Ecken, Logo größer, Positionierung erst nach Canvas-Layout.
- Karte im Bildbereich positionieren statt im schwarzen Letterbox-Balken.

### 1.5.25 — 19.10.2026
- OSD-Karte selbst gezeichnet (Java2D) statt VLC-Marquee/Logo-Filter: Logo,
  Titel, EPG-Zeile, echter Fortschrittsbalken, „Danach"-Zeile.

### 1.5.24 — 19.10.2026
- Profil→Panel-Line-Bindung (Labels aus dem Panel, Auswahl in den
  Einstellungen, Lines neu laden); Profilwechsel lädt die Senderliste neu
  und stellt profilgebundene ausgeblendete Gruppen wieder her.

### 1.5.21 — 11.10.2026
- Version-Bump + Serien-Episoden-Fix (episodes-Map statt seasons-Metadaten).

### 1.5.9 — 11.10.2026
- Windows-Parität: universelle Suche, Weiterschauen (alle 60 s),
  Film-Downloads in den Aufnahmen, Stream-Puffer in Sekunden,
  Kategorie-Sortierung/-Ausblenden, Leertasten-Pause + Maus-Over-OSD.
- Aufnahme-Daemon unter Windows (Autostart über den Startup-Ordner).
- VOD unter Windows self-contained.

---

## Historie aus dem Forum (Windows-Stabilisierung + ältere Versionen)

Windows: Der große Stabilisierungs-Run (11.10.2026, Nacht)
Aktuell: 1.5.18 — neun Builds in einer Nacht, jeder mit Beweis aus dem
Player-eigenen Log (`~/.smartys-iptv/app.log`):

- **1.5.12** — Der Freeze ist tot: Das VLC-Marquee-OSD verkeilte sich unter
  Windows im D3D-Output und riss Bild UND App mit. Windows-OSD ist jetzt ein
  reines Java-Overlay-Fenster (keine nativen Filter-Aufrufe mehr, kann nicht
  deadlocken).
- **1.5.14/1.5.17** — Maus zurück: Fenster-Dump bewies, VLC baut eigene
  Kindfenster in die App und verschluckt ALLE Maus-Klicks (Bewegungen kamen
  an, Klicks nie). `--no-mouse-events` allein reichte nicht; ab 1.5.17 werden
  die VLC-Fenster beim Abspielstart klick-durchlässig geschaltet
  (`LAYERED|TRANSPARENT` + `EnableWindow(false)`). Einzelklick = zurück,
  Doppelklick = Vollbild, Mausrad = Lautstärke.
- **1.5.18** — Vollbild hält: Das schwebende Fensterleisten-Fenster klaut beim
  Vollbild-Eintritt den Fokus — und AWT-Vollbild bricht bei jedem Fokusverlust
  ab. Fokus-Schutz auf allen Overlay-Fenstern, Vollbild bleibt jetzt stabil.
- **Instrumentierung:** Jeder native Aufruf loggt →/✓-Paare; beim nächsten
  Freeze steht die Ursache schwarz auf weiß im Log.

Linux (deb/rpm) bleibt unberührt bei der jeweils getrennten Release-Straße.
