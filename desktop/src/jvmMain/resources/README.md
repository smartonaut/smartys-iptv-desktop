# Smartys M3U Player — Desktop

Desktop-Player für Live-TV, Filme und Serien (Compose Multiplatform, Linux/Windows/macOS).
Wiedergabe über ein selbst-enthaltenes libVLC-Bundle — es ist **keine** separate
VLC-Installation nötig.

## Installation

### Linux (Debian/Ubuntu)
```bash
sudo dpkg -i smartysiptv_<version>-1_amd64.deb
```
Start über das Anwendungsmenü („Smartys M3U Player") oder direkt:
```bash
/opt/smartysiptv/bin/SmartysIPTV
```

### Erststart / Pairing
1. App starten → der Pairing-Screen zeigt **Player-ID** und **Passwort**.
2. Auf `panel.smartys.tech` gehen und mit Player-ID + Passwort anmelden.
3. Dort die Playlist hinterlegen → zurück in der App auf **„Prüfen — Playlist laden"**.
4. Player-ID und Passwort lassen sich per **Klick** in die Zwischenablage kopieren.

## Steuerung

### Wiedergabe (Live-TV, Filme, Serien)

| Aktion | Geste / Taste |
|--------|---------------|
| Vollbild an/aus | **Doppelklick** aufs Video |
| Zurück ins Menü / Senderliste | `ESC` |
| Pause / Play | ❚❚ / ▶ in der Kopfleiste |
| Vorspulen (+10 s) | `→` Pfeil rechts *(nur Filme/Serien)* |
| Zurückspulen (−10 s) | `←` Pfeil links *(nur Filme/Serien)* |
| Lautstärke erhöhen (+10) | `↑` Pfeil hoch |
| Lautstärke verringern (−10) | `↓` Pfeil runter |
| Helligkeit erhöhen | `→` Pfeil rechts *(nur Live-TV)* |
| Helligkeit verringern | `←` Pfeil links *(nur Live-TV)* |
| Aufnahme starten | `R` (fragt: laufende Sendung oder endlos) |
| Aufnahme stoppen | `S` |

Hinweis: Lautstärke, Helligkeit und der Kanalname + laufende Sendung werden
direkt **ins Videobild** eingeblendet (OSD), nicht als eigenes Overlay-Fenster.

### Aufnahme (Live-TV)

- **`R`** startet sofort eine Aufnahme des laufenden Kanals. Es erscheint eine
  Auswahl: **„Laufende Sendung"** (stoppt automatisch am EPG-Ende) oder
  **„Endlos"** (läuft bis `S`).
- **`S`** stoppt eine laufende Aufnahme.
- Aufnahmen landen als `.ts` im Aufnahme-Verzeichnis (Einstellungen).
- **Geplante Aufnahmen** (Timer) im Tab „Aufnahmen": aus dem EPG oder manuell
  (Kanal + Startzeit + Dauer).

### Live-TV-Pause (Timeshift)

Mit aktiviertem Timeshift wird der Live-Stream auf die Festplatte gepuffert —
Pause + Zurückspulen am laufenden Kanal sind dann möglich.

- **Einstellungen → Live-TV-Pause (Timeshift):** an/aus per Schalter.
- **Aufnahmepfad:** frei wählbar (Standard: `~/.smartys-iptv/timeshift`).
- Hinweis: Der Puffer belegt je nach Kanal und Laufzeit Plattenplatz; der Pfad
  sollte auf einer Festplatte mit ausreichend freiem Speicher liegen.

### Rand-Steuerung (Maus an den Bildschirmrand)

Die Rand-Steuerung wurde durch **Tastatur-Steuerung** ersetzt (siehe Tabelle oben),
weil Overlays hinter dem eingebetteten Video-Canvas nicht sichtbar waren. Lautstärke
und Helligkeit werden jetzt direkt ins Videobild (OSD) eingeblendet.

## Versionslog

### 1.5.0
- **Geplante Aufnahmen (Timer):** Sendungen aus dem EPG aufnehmen oder manuell
  (Kanal + Startzeit + Dauer) planen. Aufnahmen starten auch bei geschlossener
  App (Hintergrund-Daemon). Aufnahmeverzeichnis in den Einstellungen wählbar.
- **Film-Detailseite:** Klick auf ein Film-Cover öffnet Beschreibung (TMDB),
  Rating/Kategorie, „Fortsetzen\"/„Von vorne abspielen\" und Trailer (YouTube).
- **Doppelklick = Vollbild**, zurück ins Menü nur noch per `ESC`.
- **OSD ins Videobild:** Kanalname + laufende Sendung, Lautstärke, Helligkeit und
  `● REC`-Status erscheinen direkt im Bild (libVLC-Marquee).
- **Lautstärke** `↑`/`↓`, **Helligkeit** `←`/`→` (im Live-TV), jeweils mit OSD-Anzeige.
- **Video-Decoding wählbar** (Einstellungen): Auto / Hardware (GPU) / Software (CPU).
  Software-Decoding (Default) behebt grüne Blöcke + Mosaik auf neuer Intel-Hardware
  (Arrow Lake) — deaktiviert VAAPI/VDPAU.

### 1.4.7
- **Live-TV-Pause (Timeshift):** Stream wird auf Wunsch auf die Platte gepuffert,
  Pause + Zurückspulen am Live-Kanal möglich. An/aus + Aufnahmepfad in den Einstellungen.
- **Player-Steuerung:** `ESC` + Doppelklick führen zurück ins Menü.
- **Lautstärke** über Pfeil hoch/runter und über den rechten Bildschirmrand (Hover).
- **Helligkeit** über den linken Bildschirmrand (Hover).
- **Vor-/Zurückspulen** (Filme/Serien) über Pfeil links/rechts (±10 s).
- Player-ID + Passwort per Klick in die Zwischenablage kopieren (Pairing-Screen).

### 1.4.6
- Neues Premium-Logo (Play-Taste + Auge + S-Iris, FLUX-generiert).

### 1.4.5
- Eigenes Fenster-Icon (kein GNOME-Zahnrad mehr) + Icon-Vorschläge.

### 1.4.4
- Installer-Optionen (Desktop-Verknüpfung, Startmenü, Verzeichniswahl), App-Icons, VOD-Start-Fix.

### 1.4.3 und früher
- Desktop-Port mit Pairing, Kanal-/Kategorie-Liste, selbst-enthaltenem libVLC-Bundle.
