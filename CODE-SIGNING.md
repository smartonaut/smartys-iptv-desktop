# MSI signieren — Smartys Player (Windows)

Stand: 07.11.2026. Grund: Smart App Control (SAC) blockiert das unsignierte
MSI. **Alle** bisherigen MSIs (1.5.0 … 2.0.8) haben keinen Signatur-Stream —
geprüft am CFB-Container (`DigitalSignature` fehlt). Das ist kein
Versionsproblem, sondern ein Zustand, der irgendwann eintritt, sobald SAC auf
„An" steht.

## Warum „SAC ausschalten" keine Tester-Anweisung ist

| Mechanismus | Verhalten | Bypass |
|---|---|---|
| **Smart App Control** | Blockt unsignierte/unbekannte Apps **hart** | **Keiner.** Keine Allow-List, kein „Trotzdem ausführen" |
| **SmartScreen** | Warnung „Windows hat den PC geschützt" | „Weitere Informationen" → „Trotzdem ausführen" |

SAC startet im **Evaluationsmodus** (blockt nichts) und schaltet sich selbst auf
**On**, wenn Windows meint, es sei nötig. Deshalb liefen ältere Builds durch und
ein neuer nicht. Ab dem Moment ist jeder unsignierte Build ein Glücksspiel.

Kontrolle des Zustands (nur lesen, **nie** schreiben):
```
Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy" -Name VerifiedAndReputablePolicyState
```
`0` = Aus, `1` = Ein, `2` = Evaluationsmodus.
Seit **KB5083769** (April 2026, Windows 11 25H2) ist SAC umschaltbar
(aus → installieren → wieder an), aber das ist ein Workaround pro Gerät und
keine Verteilstrategie.

## Optionen im Vergleich (Microsoft-Doku, Nov 2026)

| Option | Kosten | SAC | SmartScreen | Aufwand |
|---|---|---|---|---|
| **Azure Artifact Signing** (ex Trusted Signing) | ~9,99 $/Monat, 5.000 Signaturen | ✅ lässt durch (trusted cert) | ⚠️ Reputation baut sich auf, erste Releases können warnen | Tage (Identity Validation) |
| OV-Zertifikat (DigiCert/Sectigo) | 150–300 €/Jahr + HSM/Token | ✅ | ⚠️ wie oben | Tage + Token-Handling |
| EV-Zertifikat | 400+ €/Jahr | ✅ | ⚠️ seit 2024 **kein** Instant-Bypass mehr | Tage + Token |
| Selbstsigniert | 0 € | ❌ blockt | ❌ blockt | — |
| Ohne Signatur | 0 € | ❌ blockt | ❌ harter Block | — |
| MSIX über Microsoft Store | 0 € | ✅ (Store re-signiert) | ✅ keine Warnung | Store-Zertifizierung, MSIX-Umbau |

**Empfehlung: Azure Artifact Signing.** Microsofts Empfehlung für Apps
außerhalb des Stores, kein Hardware-Token, CI-fähig, Zertifikatsverwaltung
inklusive. Organisationen in der **EU** sind zugelassen (Deutschland ✓ —
Einzelentwickler nur USA/Kanada, mit Gewerbe zählt man als Organisation).

Wichtig zur Erwartung: Artifact Signing liefert **kein** sofortiges
SmartScreen-Vertrauen. SAC ist damit aber sofort zufrieden („signed with a
trusted certificate"). SmartScreen-Warnungen verschwinden erst mit wachsender
Reputation bei gleichbleibender Signatur-Identität.

## Einrichtung (einmalig, ~30–60 Min + Wartezeit auf Validierung)

### 1. Voraussetzungen
- Microsoft Entra Tenant + Azure-Abo (Abo-Typ **Organization**, Rechnungsname
  und Adresse = Gewerbedaten)
- Region: **West Europe** oder **North Europe** (Frankfurt/Amsterdam)

### 2. Resource Provider registrieren
Azure Portal → **Subscriptions** → Abo wählen → **Settings → Resource
providers** → `Microsoft.CodeSigning` → **Register**.

### 3. Artifact Signing Account
Azure Portal → „Artifact Signing Accounts" → **Create**
- Name: 3–24 Zeichen, global eindeutig, beginnt mit Buchstabe
- Region: West Europe
- Endpoint danach: `https://weu.codesigning.azure.net`

### 4. Identity Validation
**Nur im Portal möglich** (nicht per CLI). Angaben: Firma, Anschrift,
Handelsregister/Steuernummer, Vertretungsberechtigter. Dauer: einige
Werktage. Ergebnis gilt abo-weit für alle Signing-Accounts.

### 5. Certificate Profile
Im Signing-Account → **Certificate profiles** → **Create**
- Typ: **Public Trust**
- Sub-Profile: `Default` (Authenticode für EXE/MSI)

### 6. Signieren im Build (PC-Mama)

Variante A — `signtool` mit Microsoft-Dlib (kein Extra-Tooling):
```
signtool sign /v /fd SHA256 /tr http://timestamp.acs.microsoft.com /td SHA256 ^
  /dlib "C:\Program Files\Microsoft\ArtifactSigning\Azure.CodeSigning.Dlib.dll" ^
  /dmdf "C:\signing\metadata.json" ^
  "C:\Users\smarty\SmartysIPTV-Build\desktop\build\compose\binaries\main\msi\SmartysIPTV-<ver>.msi"
```
`metadata.json`:
```json
{
  "Endpoint": "https://weu.codesigning.azure.net/",
  "CodeSigningAccountName": "<account>",
  "CertificateProfileName": "SmartysPlayer",
  "CorrelationId": "<beliebig>"
}
```
Anmeldung: `az login` (oder Service Principal via `AZURE_CLIENT_ID` /
`AZURE_TENANT_ID` / `AZURE_CLIENT_SECRET`).

Variante B — `trusted-signing-cli` (bequemer, gleiche API):
```
trusted-signing-cli -e https://weu.codesigning.azure.net -a <account> -c SmartysPlayer -d "Smartys Player" <msi>
```

### 7. Nach dem Signieren — Pflichtprüfung vor dem Upload
```
signtool verify /pa /v <msi>
```
Erwartet: `Successfully verified`, Signatur von „Microsoft Corporation" /
Artifact Signing-CA, mit Zeitstempel. **Nur signierte und verifizierte MSIs
gehen auf player.smartys.tech.** Zusätzlich wie bisher ProductVersion,
UpgradeCode und SHA256 prüfen.

## Reihenfolge für den nächsten Release

1. Azure-Setup + Identity Validation abschließen (Wartezeit!)
2. Signatur-Schritt in den Windows-Build auf PC-Mama einbauen (nach
   `packageMsi`, vor dem Kopieren)
3. `signtool verify` als Gate einbauen: kein Upload ohne grüne Prüfung
4. MSI neu bauen, signieren, verifizieren, deployen
5. Testern sagen: „neue Version installieren" — ohne SAC-Anweisung

## Quellen

- Microsoft Learn — Code signing options for Windows app developers
- Microsoft Learn — Quickstart: Set up Artifact Signing
- Microsoft Learn — Smart App Control (Evaluations-/Enforcement-Modus)
- KB5083769 (April 2026, 25H2): SAC ohne Neuinstallation umschaltbar
