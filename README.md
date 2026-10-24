# Smartys IPTV Desktop

Plattformübergreifender IPTV-Player (Kotlin, Compose Multiplatform).

**Website & Download:** https://player.smartys.tech/ — Windows (.msi), Linux (.deb/.rpm), Fire TV und Enigma2.

## Features

- **Live-TV** mit Kanal-Liste, Kategorien, Favoriten, Sortierung (Nummer / A–Z / manuell),
  Mehrfachauswahl von Gruppen und Multi-View für bis zu vier Sender gleichzeitig
- **Programm-Raster** — Zeitachse waagerecht, Sender untereinander, sechs Stunden
  Fenster, laufende Sendung hervorgehoben, Klick startet den Kanal
- **VOD und Serien** mit Staffeln, Episoden, Fortsetzen an der Abbruchstelle und
  Abspann-Erkennung (sieben Minuten Fenster, drei Treffer); vor dem Sprung zur
  nächsten Folge kommen fünf Sekunden Bedenkzeit (`B` bricht ab)
- **Downloads** für Filme und einzelne Folgen, zusätzlich „Staffel laden" im
  Staffelkopf; Speicheranzeige mit Warnung unter 5 GB, fehlgeschlagene Einträge
  lassen sich gesammelt löschen
- **Aufnahme-Engine** mit Timer, Serienaufnahme und Doppel-Daemon-Schutz,
  Timeshift-Puffer auf Platte, Pause im laufenden Live-Bild
- **Erinnerungen** — Glocke im Live-Player, Mitteilung 15 Minuten vor der
  folgenden Sendung (über `notify-send`, kein AWT-Tray)
- **Bewertungen** aus TMDB und OMDb (IMDb, Rotten Tomatoes, Metacritic) über das
  eigene Backend, dazu Links zu IMDb und zur Filmstarts-Suche
- **Kindersicherung** mit vierstelliger PIN (FSK 18)
- **Mehrsprachig** — 36 Sprachen, Rechts-nach-links für Persisch und Urdu

## Sprachen

Die Oberfläche liegt als JSON unter `desktop/src/jvmMain/resources/i18n/`.
Deutsch ist Quellsprache und Fallback: fehlt ein Eintrag, erscheint der deutsche
Text, nie ein leeres Feld.

**Neue Sprache hinzufügen** — zwei Schritte, kein Code:

1. `<code>.json` anlegen: JSON-Objekt, Schlüssel ist der deutsche Originaltext
   (unverändert, inklusive Platzhaltern `{0}` und `{1}`), Wert die Übersetzung.
   Symbole wie `★ ▶ ⬇` bleiben stehen, ebenso Eigennamen (Smartys IPTV/Player,
   EPG, Timeshift, PIN).
2. Eine Zeile in `index.json` ergänzen:

```json
{ "code": "xx", "label": "Native Name", "rtl": false }
```

`rtl: true` spiegelt die Oberfläche (nur für Persisch und Urdu gesetzt).
Platzhalter werden zur Laufzeit über `I18n.sf(...)` gefüllt, einfache Texte über
`I18n.s(...)`.

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./build.sh
# → desktop/build/compose/binaries/main/deb/smartysiptv_*.deb
```

## Logs

`~/.smartys-iptv/app.log` — jede Player-Aktion mit Zeitstempel. Bei einem
Hänger hilft `pkill -9 -f SmartysIPTV`, das Log bleibt erhalten.

## Hinweis

Zugangsdaten werden per Pairing vom Panel geladen, nicht im Code gespeichert.
Keine Lines, keine Zugangsdaten im Repository.

## Lizenz

MIT — siehe [LICENSE](LICENSE).
