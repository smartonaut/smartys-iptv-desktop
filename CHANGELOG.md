# Changelog — Smartys Player (Desktop)

Alle Versionen im Überblick. Neueste Änderungen oben.

**Getrennte Release-Straßen:** Linux (deb/rpm) und Windows (.msi) werden
unabhängig gebaut und versioniert — die Nummern dürfen auseinanderlaufen.

---

## Linux (deb/rpm)

### 2.3.16 — 26.11.2026

- **Kamera-Wand startet jetzt zuverlaessig.** Fenster und native Videoflaeche
  entstehen komplett auf dem GUI-Thread (Swing-EDT) — vorher blieb die Kachel
  schwarz, weil `init()` ausserhalb des EDT mit `init=false` abbrach.
- Die Kachel startet erst, wenn die Zeichenflaeche wirklich `displayable` ist;
  vorher wird auf das Hierarchy-Event gewartet.
- Schlaegt **mpv** fehl (`mpv_create` NULL, `mpv_initialize` < 0), faellt die
  Kachel selbsttaetig auf **VLC** zurueck — die Wand laeuft in jedem Fall.
- Fensterposition folgt dem Raster (Setzen von Groesse und Position auf dem EDT).
- **Keine Firmenserver-Adresse mehr im Quelltext**: sie kommt aus
  `~/.smartys-iptv/firma.properties` (`firma_server`). Oeffentliche Builds ohne
  Datei bleiben neutral, der eigene Rechner laeuft wie vorher.

### 2.3.14 / 2.3.15 — 26.11.2026

- Diagnose: mpv-Fehler (NULL-Handle, Fehlercodes, Exceptions) gehen ins
  `~/.smartys-iptv/app.log` statt auf stderr — GUI-Apps haben kein stderr.
- Die tatsaechlich geladene libmpv-Datei und ihre Client-API-Version werden
  protokolliert (`MpvLib.geladeneDatei` / `geladeneApi`).

### 2.3.13 — 26.11.2026

- Wand nutzt **mpv (libmpv) je Kachel**; `wand_engine=vlc` in
  `~/.smartys-iptv/kameras.properties` schaltet zurueck.
- Rueckweg „← Zurueck" ist immer sichtbar, Kamera-Adresse ist in den
  Einstellungen korrigierbar, Protokoll je Kachel in `app.log`.

### 2.3.12 — 26.11.2026

- **Kamera-Wand: jede Kachel als eigenes Fenster, Engine umschaltbar.**
  libvlc liefert in einem Prozess nur 1–2 von 5 Videoausgaben (Rest grün),
  auch mit eigener Instanz und eigenem Fenster je Kachel. Die Wand nutzt
  deshalb jetzt **mpv** (libmpv) je Kachel; `wand_engine=vlc` in
  `~/.smartys-iptv/kameras.properties` schaltet zurück.
- Rückweg „← Zurück" ist **immer** sichtbar — auch im Fehlerbild „Server
  antwortet nicht", wo die Auto-Hide-Leiste ausgeblendet war.
- Kamera-Adresse wird in den Einstellungen angezeigt und ist korrigierbar
  (häufigste Ursache: falsche IP oder fehlendes `http://`).
- Protokoll je Kachel in `~/.smartys-iptv/app.log` (Engine, init, Fenster).

### 2.3.11 — 26.11.2026

- Wand: eigene libVLC-Instanz je Kachel (Versuch gegen grüne Kacheln).

### 2.3.10 — 26.11.2026

- Diagnose: libVLC schreibt auf Wunsch sein eigenes Log
  (`SMARTYS_VLC_LOG=1`).

### 2.3.9 — 26.11.2026

- Wand: jede Kachel in einem eigenen X11-Fenster (statt Compose-Fläche).

### 2.3.8 — 26.11.2026

- Videofläche wird erst angehängt, wenn die Kachel ein echtes Fenster hat.
- Kein zweiter Wiedergabe-Start nach 9 s mehr (riss die Ausgabe ab).

### 2.3.7 — 26.11.2026

- Gemeinsame libVLC-Instanz für alle Player (Versuch).

### 2.3.6 — 26.11.2026

- Rückweg in der Wand immer sichtbar.

### 2.3.5 — 25.11.2026

- Kamera-Wand mit gestaffeltem Kachel-Start, größeres Startfenster,
  Quellen-Schalter.

---

## Windows (msi)


### 2.2.9 — 21.11.2026

- **Meine Liste (Watchlist).** Neuer Reiter „Meine Liste": Filme und Serien
  gemerkt, gemischt und neueste zuerst, pro Profil getrennt. Auf der Film- und
  der Serien-Detailseite steht dafür „＋ Meine Liste" (bzw. „✓ In meiner Liste");
  entfernt wird direkt auf der Kachel. Die Einträge tragen Name, Poster und
  Kennungen selbst, damit die Liste auch vollständig aussieht, wenn die
  Kategorie eines Titels nicht geladen ist oder das Profil an eine andere Line
  gebunden wurde. Ablage: `~/.smartys-iptv/watchlist__<Profil>.json` (Datei
  statt java.util.prefs — deren Werte sind auf ~8 KB begrenzt).

### 2.2.8 — 11.11.2026 (deployt auf player.smartys.tech)

- **VOD-Pause & Weiterschauen repariert.** Film sprang beim Pausieren
  (Leertaste) und beim Fortsetzen nach einer Unterbrechung wieder auf den
  Anfang zurück: Der Stream läuft über eine signierte, zeitlich begrenzte URL
  (302-Redirect); beim Pausieren läuft der Token ab, `--http-reconnect`
  verbindet neu und startet bei 0. Die Position wird jetzt beim Pausieren
  gemerkt und beim Resume/Weiterschauen nachgeholt — fällt `:start-time` am
  Redirect weg, setzt ein Seek im „spielt jetzt"-Zustand (VLC `playing`,
  MPV `PLAYBACK_RESTART`) die Position wieder. In beiden Engines (VLC + MPV).
- SHA256 `89e0d2df…6453d`, 344.246.748 Bytes, Range-GET 206 verifiziert.

### 2.2.7 — 08.11.2026 (deployt auf player.smartys.tech)

- **Live-TV startet unter 1 Sekunde.** mpv lädt Live-Streams direkt als
  TS-Strom (.m3u8 → .ts, gemessen 0,4 s statt 11-12 s): Der Anbieter signiert
  Segment-URLs pro Playlist-Abruf, der erste Abruf liefert ungültige Tokens
  (HTTP 403) und FFmpeg wartet erst nach einem Segment-Turnus neu — VLC
  refetcht sofort, deshalb wirkte mpv "langsamer". Kein Vorab-Check (User-
  Vorgabe): sofort starten, und nur wenn .ts scheitert, wechselt der
  Event-Loop im Hintergrund automatisch auf .m3u8 (Fallback, max. 15 s).
- **2.2.6 (WURZELFIX): MpvEvent.read() fehlte.** JNA liest @JvmField-Felder
  nicht automatisch — event_id blieb immer 0, kein mpv-Event wurde je
  bearbeitet: ewiges "Verbinde…" bei Live-TV mit laufendem Ton. Bewiesen
  (MpvReadProof): ohne read() 0 Events, mit read() 10.
- **2.2.5/2.2.4:** Diagnose-Logging (~/.smartys-iptv/player.log, Event-Loop
  exception-fest) + live_start_index=0 (brachte allein nichts — die 403er
  kommen vom Anbieter-Pfad, nicht vom Start-Index).
- **2.2.2:** Kein weißer Screen mehr beim mpv-Streamstart — onVideoOutput
  feuert erst bei PLAYBACK_RESTART statt sofort nach loadfile.
- SHA256 `6adc16e3…902133c`, 344.242.653 Bytes, Range-GET 206 verifiziert,
  UpgradeCode {402511A4-…} unverändert. **Weiterhin unsigniert** →
  Smart App Control blockt (siehe Hinweiskasten auf der Download-Seite).

### 2.2.1 — 08.11.2026

- **MPV-Engine unter Windows repariert.** Ursache des weißen Bildes und des
  Absturzes: `MpvPlayer.nativeWindowId()` holte die Fenster-ID per Reflection
  über `Component.getPeer()` — diese Methode existiert in JDK 17 nicht mehr.
  Der Aufruf warf `NoSuchMethodException`, die Funktion gab immer `null`
  zurück, `wid` blieb ungesetzt und libmpv öffnete ein eigenes Fenster statt
  im Player-Canvas zu rendern.
- **Fenster-ID jetzt über JNA/JAWT** (`Native.getComponentID`) — liefert HWND
  unter Windows und X11-Window unter Linux.
- **Windows rendert mit D3D11** statt OpenGL. Auf dem Ziel-PC gemessen:
  `gpu-api=d3d11` rendert Frames (`VO: [gpu]`, Fortschritt bis 65 %),
  `gpu-api=opengl` auf einem fremden HWND beendet den Prozess sofort.
- **mpv-Logdatei** unter `~/.smartys-iptv/mpv.log` (Level debug) — bei
  jpackage-GUI-Apps landet stderr nirgends, ohne Logdatei ist ein Absturz
  nicht diagnostizierbar.

### 2.2.0 — 08.11.2026

Wiedergabe-Engine im Player umschaltbar: VLC oder MPV.

- **Einstellungen → Wiedergabe-Engine.** Zwei Schaltflaechen (VLC / MPV),
  wirkt ab dem naechsten Player-Start. Gilt fuer Live-TV, VOD und MultiView.
- **MPV-Hardware-Decoding einstellbar** (eigener Schalter, nur bei MPV
  sichtbar). Vorher war `hwdec` hart auf `no` gesetzt — bewusst fuer schnellen
  Init, aber bei 4K/H.265 rechnet dann die CPU jeden Frame. Default jetzt
  `auto-safe`, Software bleibt als Option.
- **VodPlayerApi erweitert**, damit beide Engines gegeneinander laufen:
  `onVideoOutput`, `showCard`, `setLogo`, `seekBy`, `hideOsd` sind jetzt Teil
  des Interfaces. VLC ueberschreibt sie mit seiner Implementierung, MPV erbt
  sinnvolle Defaults (showCard wird zu show-text, seekBy zu seekTo).
- **MPV meldet Video-Bereitschaft selbst.** mpv hat kein videoOutput-Event wie
  VLC — der Player feuert `onVideoOutput` nach angenommenem `loadfile`, damit
  der Lade-Indikator auch dort verschwindet.
- **Bekannte Einschraenkung:** 1.5.11 dokumentiert, dass das mpv-Embedding
  unter Windows strukturell einfror (Bild/App tot nach dem ersten
  Steuerbefehl). VLC bleibt deshalb der Default. Der Umschalter ist
  ausdruecklich zum A/B-Vergleich auf echter Hardware gedacht — bei
  Auffaelligkeiten zurueck auf VLC.

### 2.1.0 — 08.11.2026

Performance: GUI friert beim Mausbewegen nicht mehr ein, erster Film startet schneller.

- **OSD-Karte wird gecacht.** Bisher wurde bei jeder Mausbewegung ein
  BufferedImage gemalt, als PNG auf die Platte geschrieben und VLC
  untergeschoben (Messung app.log: 2320 PNG-Writes bei 4715 Maus-Events).
  Bei unveraendertem Inhalt wird jetzt nur noch der Ausblend-Timer neu
  aufgezogen — kein Rendern, kein Schreiben, kein Filter-Churn.
- **OSD-Rendering aus dem EDT ausgelagert.** `renderCardPng()` lief auf dem
  Event Dispatch Thread, also auf demselben Thread, der die Oberflaeche
  zeichnet. Dadurch stockte die gesamte GUI. Laeuft jetzt in einem
  Daemon-Thread; der AWT-Fenster-Fallback bleibt auf dem EDT.

### 2.0.9 — 07.11.2026

- **Gruppen in Filme/Serien jetzt sichtbar verwaltbar.** Unten in der
  Kategorie-Sidebar steht ein Eintrag **„⚙ Gruppen sortieren / ausblenden"**
  (öffnet den Mehrfach-Dialog mit allen Gruppen vorab markiert). Der
  Langdruck auf eine Gruppe bleibt unverändert.
  Grund: Der Einstieg existierte nur als Langdruck und war damit praktisch
  unauffindbar; in Filme/Serien gab es außerdem gar keinen sichtbaren Punkt,
  weil der „ausgeblendet"-Hinweis erst nach dem ersten Ausblenden erscheint.
- **„⚙ N ausgeblendet — alle einblenden"** in beiden Ansichten — ein Klick
  holt alle Gruppen zurück (vorher öffnete der Hinweis nur den Dialog).
- MSI: `SmartysIPTV-2.0.9.msi`, ProductVersion 2.0.9, UpgradeCode
  {402511A4-…} unverändert, SHA256
  `7b935584…7c604326`, Deploy verifiziert (206, 344.230.359 Bytes).
  **Weiterhin unsigniert** → Smart App Control blockt; siehe `CODE-SIGNING.md`.

### 2.0.8 — 07.11.2026

- **Versionsanzeige in den Einstellungen** — kommt aus der beim Build
  generierten `BuildInfo.kt` (`appVersion` in `build.gradle.kts`), nicht aus
  einem hartkodierten String; zeigt Paketversion + OS an.
- **Live-Watchdog gibt nie auf:** nach 3 Fehlversuchen statt Abbruch weiter
  reconnecten mit Backoff (10 s, 20 s, dann alle 30 s) — vorher blieb das
  Bild schwarz, bis der Nutzer den Kanal manuell neu wählte.
- MSI gebaut auf PC-Mama (Thinky aus): `SmartysIPTV-2.0.8.msi`,
  ProductVersion 2.0.8, UpgradeCode {402511A4-...} entspricht `upgradeUuid`
  (ersetzt bestehende Installation), SHA256
  `a44c5bf6...8fd2e5b6`, Deploy auf player.smartys.tech verifiziert (206,
  344.222.171 Bytes).

---

## Linux (deb/rpm)

### 2.2.9 — 21.11.2026

- **Meine Liste (Watchlist).** Neuer Reiter „Meine Liste" (Filme + Serien
  gemischt, neueste zuerst, pro Profil getrennt), Merken-Button „＋ Meine Liste"
  auf der Film- und Serien-Detailseite, Entfernen direkt auf der Kachel.
  Einträge sind selbsttragend (Name, Poster, Kennungen), damit die Liste ohne
  geladene Kategorie vollständig bleibt; Ablage in
  `~/.smartys-iptv/watchlist__<Profil>.json`.

### 2.0.6 — 30.10.2026

- **Absturz beim Stoppen der Wiedergabe behoben (SIGSEGV in libpulse).**
  `stop()` läuft seit 1.8.0 in einem eigenen Thread, `release()` hat aber nicht
  darauf gewartet: `factory.release()` räumte die libVLC-Instanz ab, während
  `libvlc_media_player_stop()` noch lief — der Pulse-Audio-Thread von VLC
  stürzte danach in freigegebenem Speicher ab. `release()` joint den
  `vlc-stop`-Thread jetzt (max. 3 s), bevor der native Player freigegeben wird.

### 2.0.5 — 29.10.2026

**Alle Netzwerk-Schnittstellen im Seed.** Rechner haben meist LAN und WLAN
gleichzeitig; bisher zählte nur die erste. Jetzt geht jedes fest verbaute
Interface ein (`nic:<name>=<mac>`, alphabetisch sortiert). Virtuelle Netze
(docker/veth/tun/vmnet/…) und USB-Adapter (Dock, Stick) bleiben draußen, weil
sie abgezogen werden können. Testabdeckung: Seed-Stabilität und
Virtuell-Filter als eigene Fälle.

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
