package com.smartys.iptv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Mehrsprachige Oberfläche.
 *
 * **Neue Sprache hinzufügen** — ein Eintrag in [alle], kein Eingriff in die
 * Oberfläche nötig (der Umschalter in den Einstellungen liest die Liste):
 *
 * ```kotlin
 * val FRANZOESISCH = Sprache("fr", "Français", false, mapOf(
 *     "Einstellungen" to "Paramètres",
 *     "Zurück" to "Retour",
 * ))
 * ```
 *
 * Die Tabelle ist immer `deutscher Originaltext -> Übersetzung`. Fehlt ein
 * Eintrag, erscheint der deutsche Text — nie ein leeres Label. Eine Sprache
 * darf also unvollständig ausgeliefert und später ergänzt werden.
 *
 * `rtl = true` schaltet die Leserichtung auf rechts-nach-links (Persisch,
 * Urdu); die Oberfläche wird dann gespiegelt.
 *
 * Warum keine Ressourcen-Dateien: Die Texte liegen historisch im Code.
 * `s("Text")` schlägt in der Tabelle nach und fällt sonst auf Deutsch zurück.
 */
object I18n {

    /** Eine Sprache: Code (ISO 639-1), Anzeigename, Leserichtung, Tabelle. */
    data class Sprache(
        val code: String,
        val label: String,
        val rtl: Boolean = false,
        val tabelle: Map<String, String>
    )

    private val DEUTSCH = Sprache("de", "Deutsch", false, emptyMap())

    // ---------------------------------------------------------------- Englisch
    private val ENGLISCH = Sprache(
        "en", "English", false,
        mapOf(
            "Einstellungen" to "Settings",
            "Sprache" to "Language",
            "Verwaltung" to "Management",
            "Fenster" to "Window",
            "Zurück" to "Back",
            "← Zurück" to "← Back",
            "Schließen" to "Close",
            "Abbrechen" to "Cancel",
            "OK" to "OK",
            "Speichern" to "Save",
            "Löschen" to "Delete",
            "Anlegen" to "Create",
            "Name" to "Name",
            "Kanal" to "Channel",
            "Kanäle" to "Channels",
            "Player" to "Player",
            "Profile" to "Profiles",
            "Aufnahmen" to "Recordings",
            "Dein Passwort" to "Your password",
            "Deine Player-ID" to "Your Player ID",
            "Player freischalten" to "Activate player",
            "Prüfen — Playlist laden" to "Check — load playlist",
            "Entsperren — Playlist laden" to "Unlock — load playlist",
            "Lines entschlüsseln" to "Decrypt lines",
            "Sicherheits-Passwort" to "Security password",
            "Falsches Passwort — bitte erneut versuchen." to "Wrong password — please try again.",
            "Keine Lines geladen — oben „Lines vom Panel neu laden“." to
                "No lines loaded — use “Reload lines from panel” above.",
            "Verbinde…" to "Connecting…",
            "QR-Code mit dem Handy scannen" to "Scan the QR code with your phone",
            "Demo starten — ohne Einrichtung" to "Start demo — no setup needed",
            "Zurück zur Einrichtung" to "Back to setup",
            "Welche Line aus dem Panel nutzt dieses Profil?" to "Which panel line does this profile use?",
            "+ Profil hinzufügen" to "+ Add profile",
            "Neues Profil" to "New profile",
            "Sender, Filme oder Serien suchen …" to "Search channels, movies or series …",
            "Alle Kanäle" to "All channels",
            "Alle Filme" to "All movies",
            "Alle Serien" to "All series",
            "Keine Filme in dieser Kategorie" to "No movies in this category",
            "Keine Serien in dieser Kategorie" to "No series in this category",
            "Keine Episoden gefunden" to "No episodes found",
            "Kanal wählen" to "Choose channel",
            "Lade…" to "Loading…",
            "Beschreibung lädt…" to "Loading description…",
            "Keine Beschreibung verfügbar." to "No description available.",
            "Keine EPG-Daten für diesen Kanal." to "No EPG data for this channel.",
            "🎯 Gruppen markieren" to "🎯 Select groups",
            "Anklicken markiert. MARKIERT = BEHALTEN." to "Click to select. SELECTED = KEEP.",
            "Von vorne abspielen" to "Play from start",
            "▶ Trailer ansehen" to "▶ Watch trailer",
            "▶ Erste Episode abspielen" to "▶ Play first episode",
            "▶ Download fortsetzen" to "▶ Resume download",
            "● Aufnahme läuft" to "● Recording",
            "■ Stopp" to "■ Stop",
            "▦ Multi-View" to "▦ Multi-view",
            "Multi-View — Kanäle wählen (max. 4)" to "Multi-view — choose channels (max. 4)",
            "Live-TV-Pause (Timeshift)" to "Live TV pause (timeshift)",
            "Verzeichnis für den Timeshift-Puffer" to "Timeshift buffer directory",
            "Stream-Puffer" to "Stream buffer",
            "Offline-Downloads" to "Offline downloads",
            "Heruntergeladene Filme & Episoden" to "Downloaded movies & episodes",
            "⬇ Herunterladen" to "⬇ Download",
            "Download löschen?" to "Delete download?",
            "⚠ Download fehlgeschlagen — erneut versuchen" to "⚠ Download failed — try again",
            "Noch keine Downloads. Filme lädst du über den ⬇-Button auf der Detailseite." to
                "No downloads yet. Download movies with the ⬇ button on the detail page.",
            "Noch keine Downloads.\nFilme und Serien-Episoden kannst du in den jeweiligen Bereichen herunterladen." to
                "No downloads yet.\nDownload movies and episodes from their sections.",
            "Speicher" to "Storage",
            "Wenig Platz — alte Downloads löschen." to "Low space — delete old downloads.",
            "Erneut versuchen" to "Try again",
            "Aufnehmen" to "Record",
            "Aufnahme starten" to "Start recording",
            "Aufnahme-Daemon" to "Recording daemon",
            "Aufnahme-Verzeichnis" to "Recording directory",
            "Aufnahmepfad:" to "Recording path:",
            "Aus EPG aufnehmen" to "Record from EPG",
            "Diese Sendung aufnehmen?" to "Record this show?",
            "Laufende Sendung" to "Current show",
            "Endlos (bis S)" to "Endless (until S)",
            "Wie soll aufgenommen werden?" to "How should it be recorded?",
            "Geplante Aufnahmen" to "Scheduled recordings",
            "Manueller Aufnahme-Timer" to "Manual recording timer",
            "Manueller Timer" to "Manual timer",
            "Keine geplanten Aufnahmen." to "No scheduled recordings.",
            "Keine laufenden/zukünftigen Sendungen." to "No current or upcoming shows.",
            "Titel (optional)" to "Title (optional)",
            "Dauer (Minuten)" to "Duration (minutes)",
            "Minute" to "Minute",
            "Stunde" to "Hour",
            "Zielordner für geplante Aufnahmen" to "Target folder for scheduled recordings",
            "Pfad speichern" to "Save path",
            "Kindersicherung" to "Parental controls",
            "FSK18-PIN festlegen" to "Set adult PIN",
            "FSK18-PIN:" to "Adult PIN:",
            "PIN festlegen" to "Set PIN",
            "4-stelliger PIN" to "4-digit PIN",
            "Falsche PIN" to "Wrong PIN",
            "Deaktivieren" to "Disable",
            "Video-Decoding" to "Video decoding",
            "Auto erkennt das System selbst (z. B. Hybrid-Grafik → Software). Nur bei Problemen manuell wählen." to
                "Auto lets the system decide (e.g. hybrid graphics → software). Only change this on problems.",
            "aktiv" to "active",
            "gesetzt" to "set",
            "nicht gesetzt" to "not set",
            "Original" to "Original",
            "Füllen (zuschneiden)" to "Fill (crop)",
            "16:9 erzwingen" to "Force 16:9",
            "4:3 erzwingen" to "Force 4:3",
            "Aus" to "Off",
            "Smartys IPTV" to "Smartys IPTV",
            "Smartys Player" to "Smartys Player"
        )
    )

    // ---------------------------------------------------------------- Polnisch
    private val POLNISCH = Sprache(
        "pl", "Polski", false,
        mapOf(
            "Einstellungen" to "Ustawienia",
            "Sprache" to "Język",
            "Verwaltung" to "Zarządzanie",
            "Fenster" to "Okno",
            "Zurück" to "Wstecz",
            "← Zurück" to "← Wstecz",
            "Schließen" to "Zamknij",
            "Abbrechen" to "Anuluj",
            "OK" to "OK",
            "Speichern" to "Zapisz",
            "Löschen" to "Usuń",
            "Anlegen" to "Utwórz",
            "Name" to "Nazwa",
            "Kanal" to "Kanał",
            "Kanäle" to "Kanały",
            "Player" to "Odtwarzacz",
            "Profile" to "Profile",
            "Aufnahmen" to "Nagrania",
            "Dein Passwort" to "Twoje hasło",
            "Deine Player-ID" to "Twoje ID odtwarzacza",
            "Player freischalten" to "Aktywuj odtwarzacz",
            "Prüfen — Playlist laden" to "Sprawdź — wczytaj playlistę",
            "Entsperren — Playlist laden" to "Odblokuj — wczytaj playlistę",
            "Lines entschlüsseln" to "Odszyfruj linie",
            "Sicherheits-Passwort" to "Hasło bezpieczeństwa",
            "Falsches Passwort — bitte erneut versuchen." to "Błędne hasło — spróbuj ponownie.",
            "Keine Lines geladen — oben „Lines vom Panel neu laden“." to
                "Nie wczytano linii — użyj „Wczytaj linie z panelu” powyżej.",
            "Verbinde…" to "Łączenie…",
            "QR-Code mit dem Handy scannen" to "Zeskanuj kod QR telefonem",
            "Demo starten — ohne Einrichtung" to "Uruchom demo — bez konfiguracji",
            "Zurück zur Einrichtung" to "Wróć do konfiguracji",
            "Welche Line aus dem Panel nutzt dieses Profil?" to "Której linii z panelu używa ten profil?",
            "+ Profil hinzufügen" to "+ Dodaj profil",
            "Neues Profil" to "Nowy profil",
            "Sender, Filme oder Serien suchen …" to "Szukaj kanałów, filmów lub seriali …",
            "Alle Kanäle" to "Wszystkie kanały",
            "Alle Filme" to "Wszystkie filmy",
            "Alle Serien" to "Wszystkie seriale",
            "Keine Filme in dieser Kategorie" to "Brak filmów w tej kategorii",
            "Keine Serien in dieser Kategorie" to "Brak seriali w tej kategorii",
            "Keine Episoden gefunden" to "Nie znaleziono odcinków",
            "Kanal wählen" to "Wybierz kanał",
            "Lade…" to "Ładowanie…",
            "Beschreibung lädt…" to "Ładowanie opisu…",
            "Keine Beschreibung verfügbar." to "Brak opisu.",
            "Keine EPG-Daten für diesen Kanal." to "Brak danych EPG dla tego kanału.",
            "🎯 Gruppen markieren" to "🎯 Zaznacz grupy",
            "Anklicken markiert. MARKIERT = BEHALTEN." to "Kliknięcie zaznacza. ZAZNACZONE = ZACHOWAJ.",
            "Von vorne abspielen" to "Odtwórz od początku",
            "▶ Trailer ansehen" to "▶ Obejrzyj zwiastun",
            "▶ Erste Episode abspielen" to "▶ Odtwórz pierwszy odcinek",
            "▶ Download fortsetzen" to "▶ Wznów pobieranie",
            "● Aufnahme läuft" to "● Nagrywanie",
            "■ Stopp" to "■ Stop",
            "▦ Multi-View" to "▦ Wieloobraz",
            "Multi-View — Kanäle wählen (max. 4)" to "Wieloobraz — wybierz kanały (maks. 4)",
            "Live-TV-Pause (Timeshift)" to "Pauza TV na żywo (timeshift)",
            "Verzeichnis für den Timeshift-Puffer" to "Katalog bufora timeshift",
            "Stream-Puffer" to "Bufor strumienia",
            "Offline-Downloads" to "Pobrane offline",
            "Heruntergeladene Filme & Episoden" to "Pobrane filmy i odcinki",
            "⬇ Herunterladen" to "⬇ Pobierz",
            "Download löschen?" to "Usunąć pobrany plik?",
            "⚠ Download fehlgeschlagen — erneut versuchen" to "⚠ Pobieranie nie powiodło się — spróbuj ponownie",
            "Noch keine Downloads. Filme lädst du über den ⬇-Button auf der Detailseite." to
                "Brak pobranych plików. Filmy pobierzesz przyciskiem ⬇ na stronie szczegółów.",
            "Noch keine Downloads.\nFilme und Serien-Episoden kannst du in den jeweiligen Bereichen herunterladen." to
                "Brak pobranych plików.\nFilmy i odcinki pobierzesz w odpowiednich sekcjach.",
            "Speicher" to "Pamięć",
            "Wenig Platz — alte Downloads löschen." to "Mało miejsca — usuń stare pliki.",
            "Erneut versuchen" to "Spróbuj ponownie",
            "Aufnehmen" to "Nagraj",
            "Aufnahme starten" to "Rozpocznij nagrywanie",
            "Aufnahme-Daemon" to "Demon nagrywania",
            "Aufnahme-Verzeichnis" to "Katalog nagrań",
            "Aufnahmepfad:" to "Ścieżka nagrań:",
            "Aus EPG aufnehmen" to "Nagraj z EPG",
            "Diese Sendung aufnehmen?" to "Nagrywać ten program?",
            "Laufende Sendung" to "Bieżący program",
            "Endlos (bis S)" to "Bez końca (do S)",
            "Wie soll aufgenommen werden?" to "Jak nagrywać?",
            "Geplante Aufnahmen" to "Zaplanowane nagrania",
            "Manueller Aufnahme-Timer" to "Ręczny timer nagrywania",
            "Manueller Timer" to "Ręczny timer",
            "Keine geplanten Aufnahmen." to "Brak zaplanowanych nagrań.",
            "Keine laufenden/zukünftigen Sendungen." to "Brak bieżących lub przyszłych programów.",
            "Titel (optional)" to "Tytuł (opcjonalnie)",
            "Dauer (Minuten)" to "Czas trwania (minuty)",
            "Minute" to "Minuta",
            "Stunde" to "Godzina",
            "Zielordner für geplante Aufnahmen" to "Katalog docelowy nagrań",
            "Pfad speichern" to "Zapisz ścieżkę",
            "Kindersicherung" to "Kontrola rodzicielska",
            "FSK18-PIN festlegen" to "Ustaw PIN dla dorosłych",
            "FSK18-PIN:" to "PIN dla dorosłych:",
            "PIN festlegen" to "Ustaw PIN",
            "4-stelliger PIN" to "4-cyfrowy PIN",
            "Falsche PIN" to "Błędny PIN",
            "Deaktivieren" to "Wyłącz",
            "Video-Decoding" to "Dekodowanie wideo",
            "Auto erkennt das System selbst (z. B. Hybrid-Grafik → Software). Nur bei Problemen manuell wählen." to
                "Auto pozostawia wybór systemowi (np. grafika hybrydowa → software). Zmień tylko przy problemach.",
            "aktiv" to "aktywny",
            "gesetzt" to "ustawione",
            "nicht gesetzt" to "nie ustawiono",
            "Original" to "Oryginał",
            "Füllen (zuschneiden)" to "Wypełnij (przytnij)",
            "16:9 erzwingen" to "Wymuś 16:9",
            "4:3 erzwingen" to "Wymuś 4:3",
            "Aus" to "Wyłączone",
            "Smartys IPTV" to "Smartys IPTV",
            "Smartys Player" to "Smartys Player"
        )
    )

    // ---------------------------------------------------------------- Türkisch
    private val TUERKISCH = Sprache(
        "tr", "Türkçe", false,
        mapOf(
            "Einstellungen" to "Ayarlar",
            "Sprache" to "Dil",
            "Verwaltung" to "Yönetim",
            "Fenster" to "Pencere",
            "Zurück" to "Geri",
            "← Zurück" to "← Geri",
            "Schließen" to "Kapat",
            "Abbrechen" to "İptal",
            "OK" to "Tamam",
            "Speichern" to "Kaydet",
            "Löschen" to "Sil",
            "Anlegen" to "Oluştur",
            "Name" to "Ad",
            "Kanal" to "Kanal",
            "Kanäle" to "Kanallar",
            "Player" to "Oynatıcı",
            "Profile" to "Profiller",
            "Aufnahmen" to "Kayıtlar",
            "Dein Passwort" to "Şifreniz",
            "Deine Player-ID" to "Oynatıcı kimliğiniz",
            "Player freischalten" to "Oynatıcıyı etkinleştir",
            "Prüfen — Playlist laden" to "Kontrol et — listeyi yükle",
            "Entsperren — Playlist laden" to "Kilidi aç — listeyi yükle",
            "Lines entschlüsseln" to "Hatların şifresini çöz",
            "Sicherheits-Passwort" to "Güvenlik şifresi",
            "Falsches Passwort — bitte erneut versuchen." to "Yanlış şifre — lütfen tekrar deneyin.",
            "Keine Lines geladen — oben „Lines vom Panel neu laden“." to
                "Hat yüklenmedi — yukarıdan „Hatları panelden yeniden yükle” seçin.",
            "Verbinde…" to "Bağlanıyor…",
            "QR-Code mit dem Handy scannen" to "QR kodunu telefonla tarayın",
            "Demo starten — ohne Einrichtung" to "Demoyu başlat — kurulum gerekmez",
            "Zurück zur Einrichtung" to "Kuruluma dön",
            "Welche Line aus dem Panel nutzt dieses Profil?" to "Bu profil hangi panel hattını kullanıyor?",
            "+ Profil hinzufügen" to "+ Profil ekle",
            "Neues Profil" to "Yeni profil",
            "Sender, Filme oder Serien suchen …" to "Kanal, film veya dizi ara …",
            "Alle Kanäle" to "Tüm kanallar",
            "Alle Filme" to "Tüm filmler",
            "Alle Serien" to "Tüm diziler",
            "Keine Filme in dieser Kategorie" to "Bu kategoride film yok",
            "Keine Serien in dieser Kategorie" to "Bu kategoride dizi yok",
            "Keine Episoden gefunden" to "Bölüm bulunamadı",
            "Kanal wählen" to "Kanal seç",
            "Lade…" to "Yükleniyor…",
            "Beschreibung lädt…" to "Açıklama yükleniyor…",
            "Keine Beschreibung verfügbar." to "Açıklama yok.",
            "Keine EPG-Daten für diesen Kanal." to "Bu kanal için EPG verisi yok.",
            "🎯 Gruppen markieren" to "🎯 Grupları işaretle",
            "Anklicken markiert. MARKIERT = BEHALTEN." to "Tıklama işaretler. İŞARETLİ = TUT.",
            "Von vorne abspielen" to "Baştan oynat",
            "▶ Trailer ansehen" to "▶ Fragmanı izle",
            "▶ Erste Episode abspielen" to "▶ İlk bölümü oynat",
            "▶ Download fortsetzen" to "▶ İndirmeye devam et",
            "● Aufnahme läuft" to "● Kayıt sürüyor",
            "■ Stopp" to "■ Durdur",
            "▦ Multi-View" to "▦ Çoklu görünüm",
            "Multi-View — Kanäle wählen (max. 4)" to "Çoklu görünüm — kanal seç (en fazla 4)",
            "Live-TV-Pause (Timeshift)" to "Canlı TV duraklatma (timeshift)",
            "Verzeichnis für den Timeshift-Puffer" to "Timeshift tamponu klasörü",
            "Stream-Puffer" to "Yayın tamponu",
            "Offline-Downloads" to "Çevrimdışı indirmeler",
            "Heruntergeladene Filme & Episoden" to "İndirilen filmler ve bölümler",
            "⬇ Herunterladen" to "⬇ İndir",
            "Download löschen?" to "İndirme silinsin mi?",
            "⚠ Download fehlgeschlagen — erneut versuchen" to "⚠ İndirme başarısız — tekrar dene",
            "Noch keine Downloads. Filme lädst du über den ⬇-Button auf der Detailseite." to
                "Henüz indirme yok. Filmleri detay sayfasındaki ⬇ düğmesiyle indirin.",
            "Noch keine Downloads.\nFilme und Serien-Episoden kannst du in den jeweiligen Bereichen herunterladen." to
                "Henüz indirme yok.\nFilmleri ve bölümleri ilgili bölümlerden indirebilirsiniz.",
            "Speicher" to "Depolama",
            "Wenig Platz — alte Downloads löschen." to "Az yer — eski indirmeleri silin.",
            "Erneut versuchen" to "Tekrar dene",
            "Aufnehmen" to "Kaydet",
            "Aufnahme starten" to "Kaydı başlat",
            "Aufnahme-Daemon" to "Kayıt servisi",
            "Aufnahme-Verzeichnis" to "Kayıt klasörü",
            "Aufnahmepfad:" to "Kayıt yolu:",
            "Aus EPG aufnehmen" to "EPG'den kaydet",
            "Diese Sendung aufnehmen?" to "Bu program kaydedilsin mi?",
            "Laufende Sendung" to "Şu anki program",
            "Endlos (bis S)" to "Sınırsız (S'ye kadar)",
            "Wie soll aufgenommen werden?" to "Nasıl kaydedilsin?",
            "Geplante Aufnahmen" to "Planlanan kayıtlar",
            "Manueller Aufnahme-Timer" to "Manuel kayıt zamanlayıcısı",
            "Manueller Timer" to "Manuel zamanlayıcı",
            "Keine geplanten Aufnahmen." to "Planlanan kayıt yok.",
            "Keine laufenden/zukünftigen Sendungen." to "Şu anki veya yaklaşan program yok.",
            "Titel (optional)" to "Başlık (isteğe bağlı)",
            "Dauer (Minuten)" to "Süre (dakika)",
            "Minute" to "Dakika",
            "Stunde" to "Saat",
            "Zielordner für geplante Aufnahmen" to "Planlanan kayıtlar için hedef klasör",
            "Pfad speichern" to "Yolu kaydet",
            "Kindersicherung" to "Ebeveyn kilidi",
            "FSK18-PIN festlegen" to "18+ PIN belirle",
            "FSK18-PIN:" to "18+ PIN:",
            "PIN festlegen" to "PIN belirle",
            "4-stelliger PIN" to "4 haneli PIN",
            "Falsche PIN" to "Yanlış PIN",
            "Deaktivieren" to "Devre dışı bırak",
            "Video-Decoding" to "Video kod çözme",
            "Auto erkennt das System selbst (z. B. Hybrid-Grafik → Software). Nur bei Problemen manuell wählen." to
                "Auto sistemin seçmesine izin verir (ör. hibrit grafik → yazılım). Sadece sorun olursa değiştirin.",
            "aktiv" to "etkin",
            "gesetzt" to "ayarlandı",
            "nicht gesetzt" to "ayarlanmadı",
            "Original" to "Orijinal",
            "Füllen (zuschneiden)" to "Doldur (kırp)",
            "16:9 erzwingen" to "16:9 zorla",
            "4:3 erzwingen" to "4:3 zorla",
            "Aus" to "Kapalı",
            "Smartys IPTV" to "Smartys IPTV",
            "Smartys Player" to "Smartys Player"
        )
    )

    // ------------------------------------------------- Persisch (Iran, RTL)
    private val PERSISCH = Sprache(
        "fa", "فارسی", true,
        mapOf(
            "Einstellungen" to "تنظیمات",
            "Sprache" to "زبان",
            "Verwaltung" to "مدیریت",
            "Fenster" to "پنجره",
            "Zurück" to "بازگشت",
            "← Zurück" to "بازگشت →",
            "Schließen" to "بستن",
            "Abbrechen" to "لغو",
            "OK" to "تأیید",
            "Speichern" to "ذخیره",
            "Löschen" to "حذف",
            "Anlegen" to "ایجاد",
            "Name" to "نام",
            "Kanal" to "کانال",
            "Kanäle" to "کانال‌ها",
            "Player" to "پخش‌کننده",
            "Profile" to "پروفایل‌ها",
            "Aufnahmen" to "ضبط‌ها",
            "Dein Passwort" to "رمز عبور شما",
            "Deine Player-ID" to "شناسه پخش‌کننده شما",
            "Player freischalten" to "فعال‌سازی پخش‌کننده",
            "Prüfen — Playlist laden" to "بررسی — بارگذاری فهرست",
            "Entsperren — Playlist laden" to "باز کردن قفل — بارگذاری فهرست",
            "Lines entschlüsseln" to "رمزگشایی خطوط",
            "Sicherheits-Passwort" to "رمز امنیتی",
            "Falsches Passwort — bitte erneut versuchen." to "رمز اشتباه است — دوباره تلاش کنید.",
            "Keine Lines geladen — oben „Lines vom Panel neu laden“." to
                "خطی بارگذاری نشد — از گزینه «بارگذاری مجدد خطوط از پنل» در بالا استفاده کنید.",
            "Verbinde…" to "در حال اتصال…",
            "QR-Code mit dem Handy scannen" to "کد QR را با گوشی اسکن کنید",
            "Demo starten — ohne Einrichtung" to "شروع نسخه نمایشی — بدون تنظیمات",
            "Zurück zur Einrichtung" to "بازگشت به تنظیمات",
            "Welche Line aus dem Panel nutzt dieses Profil?" to "این پروفایل از کدام خط پنل استفاده می‌کند؟",
            "+ Profil hinzufügen" to "+ افزودن پروفایل",
            "Neues Profil" to "پروفایل جدید",
            "Sender, Filme oder Serien suchen …" to "جستجوی کانال، فیلم یا سریال …",
            "Alle Kanäle" to "همه کانال‌ها",
            "Alle Filme" to "همه فیلم‌ها",
            "Alle Serien" to "همه سریال‌ها",
            "Keine Filme in dieser Kategorie" to "فیلمی در این دسته نیست",
            "Keine Serien in dieser Kategorie" to "سریالی در این دسته نیست",
            "Keine Episoden gefunden" to "قسمتی یافت نشد",
            "Kanal wählen" to "انتخاب کانال",
            "Lade…" to "در حال بارگذاری…",
            "Beschreibung lädt…" to "در حال بارگذاری توضیحات…",
            "Keine Beschreibung verfügbar." to "توضیحی موجود نیست.",
            "Keine EPG-Daten für diesen Kanal." to "داده راهنمای برنامه برای این کانال نیست.",
            "🎯 Gruppen markieren" to "🎯 انتخاب گروه‌ها",
            "Anklicken markiert. MARKIERT = BEHALTEN." to "کلیک انتخاب می‌کند. انتخاب‌شده = نگه داشته می‌شود.",
            "Von vorne abspielen" to "پخش از ابتدا",
            "▶ Trailer ansehen" to "▶ تماشای تریلر",
            "▶ Erste Episode abspielen" to "▶ پخش قسمت اول",
            "▶ Download fortsetzen" to "▶ ادامه دانلود",
            "● Aufnahme läuft" to "● در حال ضبط",
            "■ Stopp" to "■ توقف",
            "▦ Multi-View" to "▦ نمای چندگانه",
            "Multi-View — Kanäle wählen (max. 4)" to "نمای چندگانه — انتخاب کانال (حداکثر ۴)",
            "Live-TV-Pause (Timeshift)" to "توقف پخش زنده (تایم‌شیفت)",
            "Verzeichnis für den Timeshift-Puffer" to "پوشه بافر تایم‌شیفت",
            "Stream-Puffer" to "بافر پخش",
            "Offline-Downloads" to "دانلودهای آفلاین",
            "Heruntergeladene Filme & Episoden" to "فیلم‌ها و قسمت‌های دانلودشده",
            "⬇ Herunterladen" to "⬇ دانلود",
            "Download löschen?" to "دانلود حذف شود؟",
            "⚠ Download fehlgeschlagen — erneut versuchen" to "⚠ دانلود ناموفق بود — دوباره تلاش کنید",
            "Noch keine Downloads. Filme lädst du über den ⬇-Button auf der Detailseite." to
                "هنوز دانلودی نیست. فیلم‌ها را با دکمه ⬇ در صفحه جزئیات دانلود کنید.",
            "Noch keine Downloads.\nFilme und Serien-Episoden kannst du in den jeweiligen Bereichen herunterladen." to
                "هنوز دانلودی نیست.\nفیلم‌ها و قسمت‌ها را از بخش‌های مربوطه دانلود کنید.",
            "Speicher" to "حافظه",
            "Wenig Platz — alte Downloads löschen." to "فضا کم است — دانلودهای قدیمی را حذف کنید.",
            "Erneut versuchen" to "تلاش دوباره",
            "Aufnehmen" to "ضبط",
            "Aufnahme starten" to "شروع ضبط",
            "Aufnahme-Daemon" to "سرویس ضبط",
            "Aufnahme-Verzeichnis" to "پوشه ضبط",
            "Aufnahmepfad:" to "مسیر ضبط:",
            "Aus EPG aufnehmen" to "ضبط از راهنمای برنامه",
            "Diese Sendung aufnehmen?" to "این برنامه ضبط شود؟",
            "Laufende Sendung" to "برنامه فعلی",
            "Endlos (bis S)" to "بی‌پایان (تا S)",
            "Wie soll aufgenommen werden?" to "چگونه ضبط شود؟",
            "Geplante Aufnahmen" to "ضبط‌های زمان‌بندی‌شده",
            "Manueller Aufnahme-Timer" to "تایمر دستی ضبط",
            "Manueller Timer" to "تایمر دستی",
            "Keine geplanten Aufnahmen." to "ضبط زمان‌بندی‌شده‌ای نیست.",
            "Keine laufenden/zukünftigen Sendungen." to "برنامه فعلی یا آینده‌ای نیست.",
            "Titel (optional)" to "عنوان (اختیاری)",
            "Dauer (Minuten)" to "مدت (دقیقه)",
            "Minute" to "دقیقه",
            "Stunde" to "ساعت",
            "Zielordner für geplante Aufnahmen" to "پوشه مقصد برای ضبط‌های زمان‌بندی‌شده",
            "Pfad speichern" to "ذخیره مسیر",
            "Kindersicherung" to "کنترل والدین",
            "FSK18-PIN festlegen" to "تعیین PIN بزرگسالان",
            "FSK18-PIN:" to "PIN بزرگسالان:",
            "PIN festlegen" to "تعیین PIN",
            "4-stelliger PIN" to "PIN چهاررقمی",
            "Falsche PIN" to "PIN اشتباه",
            "Deaktivieren" to "غیرفعال کردن",
            "Video-Decoding" to "رمزگشایی ویدیو",
            "Auto erkennt das System selbst (z. B. Hybrid-Grafik → Software). Nur bei Problemen manuell wählen." to
                "حالت خودکار به سیستم واگذار می‌شود (مثلاً گرافیک هیبریدی → نرم‌افزار). فقط در صورت مشکل تغییر دهید.",
            "aktiv" to "فعال",
            "gesetzt" to "تنظیم‌شده",
            "nicht gesetzt" to "تنظیم‌نشده",
            "Original" to "اصلی",
            "Füllen (zuschneiden)" to "پر کردن (برش)",
            "16:9 erzwingen" to "اجبار ۱۶:۹",
            "4:3 erzwingen" to "اجبار ۴:۳",
            "Aus" to "خاموش",
            "Smartys IPTV" to "Smartys IPTV",
            "Smartys Player" to "Smartys Player"
        )
    )

    // ----------------------------------------------------- Urdu (Pakistan, RTL)
    private val URDU = Sprache(
        "ur", "اردو", true,
        mapOf(
            "Einstellungen" to "ترتیبات",
            "Sprache" to "زبان",
            "Verwaltung" to "انتظام",
            "Fenster" to "ونڈو",
            "Zurück" to "واپس",
            "← Zurück" to "واپس →",
            "Schließen" to "بند کریں",
            "Abbrechen" to "منسوخ",
            "OK" to "ٹھیک ہے",
            "Speichern" to "محفوظ کریں",
            "Löschen" to "حذف کریں",
            "Anlegen" to "بنائیں",
            "Name" to "نام",
            "Kanal" to "چینل",
            "Kanäle" to "چینلز",
            "Player" to "پلیئر",
            "Profile" to "پروفائلز",
            "Aufnahmen" to "ریکارڈنگز",
            "Dein Passwort" to "آپ کا پاس ورڈ",
            "Deine Player-ID" to "آپ کا پلیئر آئی ڈی",
            "Player freischalten" to "پلیئر فعال کریں",
            "Prüfen — Playlist laden" to "جانچیں — فہرست لوڈ کریں",
            "Entsperren — Playlist laden" to "ان لاک کریں — فہرست لوڈ کریں",
            "Lines entschlüsseln" to "لائنز ڈکرپٹ کریں",
            "Sicherheits-Passwort" to "سیکیورٹی پاس ورڈ",
            "Falsches Passwort — bitte erneut versuchen." to "غلط پاس ورڈ — دوبارہ کوشش کریں۔",
            "Keine Lines geladen — oben „Lines vom Panel neu laden“." to
                "کوئی لائن لوڈ نہیں ہوئی — اوپر «پینل سے لائنز دوبارہ لوڈ کریں» استعمال کریں۔",
            "Verbinde…" to "رابطہ ہو رہا ہے…",
            "QR-Code mit dem Handy scannen" to "موبائل سے QR کوڈ اسکین کریں",
            "Demo starten — ohne Einrichtung" to "ڈیمو شروع کریں — بغیر سیٹ اپ",
            "Zurück zur Einrichtung" to "سیٹ اپ پر واپس",
            "Welche Line aus dem Panel nutzt dieses Profil?" to "یہ پروفائل پینل کی کون سی لائن استعمال کرتا ہے؟",
            "+ Profil hinzufügen" to "+ پروفائل شامل کریں",
            "Neues Profil" to "نیا پروفائل",
            "Sender, Filme oder Serien suchen …" to "چینل، فلم یا سیریز تلاش کریں …",
            "Alle Kanäle" to "تمام چینلز",
            "Alle Filme" to "تمام فلمیں",
            "Alle Serien" to "تمام سیریز",
            "Keine Filme in dieser Kategorie" to "اس زمرے میں کوئی فلم نہیں",
            "Keine Serien in dieser Kategorie" to "اس زمرے میں کوئی سیریز نہیں",
            "Keine Episoden gefunden" to "کوئی قسط نہیں ملی",
            "Kanal wählen" to "چینل منتخب کریں",
            "Lade…" to "لوڈ ہو رہا ہے…",
            "Beschreibung lädt…" to "تفصیل لوڈ ہو رہی ہے…",
            "Keine Beschreibung verfügbar." to "کوئی تفصیل دستیاب نہیں۔",
            "Keine EPG-Daten für diesen Kanal." to "اس چینل کے لیے EPG ڈیٹا نہیں۔",
            "🎯 Gruppen markieren" to "🎯 گروپس منتخب کریں",
            "Anklicken markiert. MARKIERT = BEHALTEN." to "کلک کرنے سے منتخب ہوتا ہے۔ منتخب = رکھیں۔",
            "Von vorne abspielen" to "شروع سے چلائیں",
            "▶ Trailer ansehen" to "▶ ٹریلر دیکھیں",
            "▶ Erste Episode abspielen" to "▶ پہلی قسط چلائیں",
            "▶ Download fortsetzen" to "▶ ڈاؤن لوڈ جاری رکھیں",
            "● Aufnahme läuft" to "● ریکارڈنگ جاری",
            "■ Stopp" to "■ بند کریں",
            "▦ Multi-View" to "▦ ملٹی ویو",
            "Multi-View — Kanäle wählen (max. 4)" to "ملٹی ویو — چینلز منتخب کریں (زیادہ سے زیادہ 4)",
            "Live-TV-Pause (Timeshift)" to "لائیو ٹی وی وقفہ (ٹائم شفٹ)",
            "Verzeichnis für den Timeshift-Puffer" to "ٹائم شفٹ بفر فولڈر",
            "Stream-Puffer" to "اسٹریم بفر",
            "Offline-Downloads" to "آف لائن ڈاؤن لوڈز",
            "Heruntergeladene Filme & Episoden" to "ڈاؤن لوڈ شدہ فلمیں اور اقساط",
            "⬇ Herunterladen" to "⬇ ڈاؤن لوڈ",
            "Download löschen?" to "ڈاؤن لوڈ حذف کریں؟",
            "⚠ Download fehlgeschlagen — erneut versuchen" to "⚠ ڈاؤن لوڈ ناکام — دوبارہ کوشش کریں",
            "Noch keine Downloads. Filme lädst du über den ⬇-Button auf der Detailseite." to
                "ابھی کوئی ڈاؤن لوڈ نہیں۔ فلمیں تفصیل کے صفحے پر ⬇ بٹن سے ڈاؤن لوڈ کریں۔",
            "Noch keine Downloads.\nFilme und Serien-Episoden kannst du in den jeweiligen Bereichen herunterladen." to
                "ابھی کوئی ڈاؤن لوڈ نہیں۔\nفلمیں اور اقساط متعلقہ حصوں سے ڈاؤن لوڈ کریں۔",
            "Speicher" to "اسٹوریج",
            "Wenig Platz — alte Downloads löschen." to "جگہ کم ہے — پرانے ڈاؤن لوڈز حذف کریں۔",
            "Erneut versuchen" to "دوبارہ کوشش",
            "Aufnehmen" to "ریکارڈ",
            "Aufnahme starten" to "ریکارڈنگ شروع کریں",
            "Aufnahme-Daemon" to "ریکارڈنگ سروس",
            "Aufnahme-Verzeichnis" to "ریکارڈنگ فولڈر",
            "Aufnahmepfad:" to "ریکارڈنگ راستہ:",
            "Aus EPG aufnehmen" to "EPG سے ریکارڈ کریں",
            "Diese Sendung aufnehmen?" to "یہ پروگرام ریکارڈ کریں؟",
            "Laufende Sendung" to "موجودہ پروگرام",
            "Endlos (bis S)" to "لامحدود (S تک)",
            "Wie soll aufgenommen werden?" to "کیسے ریکارڈ کریں؟",
            "Geplante Aufnahmen" to "شیڈول شدہ ریکارڈنگز",
            "Manueller Aufnahme-Timer" to "دستی ریکارڈنگ ٹائمر",
            "Manueller Timer" to "دستی ٹائمر",
            "Keine geplanten Aufnahmen." to "کوئی شیڈول شدہ ریکارڈنگ نہیں۔",
            "Keine laufenden/zukünftigen Sendungen." to "کوئی موجودہ یا آنے والا پروگرام نہیں۔",
            "Titel (optional)" to "عنوان (اختیاری)",
            "Dauer (Minuten)" to "دورانیہ (منٹ)",
            "Minute" to "منٹ",
            "Stunde" to "گھنٹہ",
            "Zielordner für geplante Aufnahmen" to "شیڈول شدہ ریکارڈنگ کے لیے ہدف فولڈر",
            "Pfad speichern" to "راستہ محفوظ کریں",
            "Kindersicherung" to "والدین کا کنٹرول",
            "FSK18-PIN festlegen" to "بالغ PIN مقرر کریں",
            "FSK18-PIN:" to "بالغ PIN:",
            "PIN festlegen" to "PIN مقرر کریں",
            "4-stelliger PIN" to "4 ہندسوں کا PIN",
            "Falsche PIN" to "غلط PIN",
            "Deaktivieren" to "غیر فعال کریں",
            "Video-Decoding" to "ویڈیو ڈیکوڈنگ",
            "Auto erkennt das System selbst (z. B. Hybrid-Grafik → Software). Nur bei Problemen manuell wählen." to
                "خودکار نظام کو انتخاب دیتا ہے (مثلاً ہائبرڈ گرافکس → سافٹ ویئر)۔ صرف مسئلہ ہونے پر تبدیل کریں۔",
            "aktiv" to "فعال",
            "gesetzt" to "مقرر",
            "nicht gesetzt" to "غیر مقرر",
            "Original" to "اصل",
            "Füllen (zuschneiden)" to "بھریں (کاٹیں)",
            "16:9 erzwingen" to "16:9 لازمی",
            "4:3 erzwingen" to "4:3 لازمی",
            "Aus" to "بند",
            "Smartys IPTV" to "Smartys IPTV",
            "Smartys Player" to "Smartys Player"
        )
    )

    /**
     * Alle verfügbaren Sprachen. **Hier eine neue Zeile eintragen** — der
     * Umschalter in den Einstellungen zeigt sie sofort an.
     */
    val alle: List<Sprache> = listOf(DEUTSCH, ENGLISCH, POLNISCH, TUERKISCH, PERSISCH, URDU)

    /** Aktive Sprache. */
    var sprache: Sprache = DEUTSCH
        private set

    /** Zähler für Compose: ein Sprachwechsel zeichnet die Oberfläche neu. */
    var version by mutableStateOf(0)
        private set

    fun setze(neu: Sprache) {
        if (sprache.code == neu.code) return
        sprache = neu
        AppSettings.setLanguage(neu.code)
        version++
    }

    /** Lädt die gespeicherte Sprache (beim App-Start aufrufen). */
    fun laden() {
        val code = AppSettings.language()
        sprache = alle.firstOrNull { it.code == code } ?: DEUTSCH
    }

    /** Übersetzt einen festen Text; fällt auf Deutsch zurück. */
    fun s(de: String): String =
        if (sprache.code == "de") de else sprache.tabelle[de] ?: de

    /**
     * Übersetzt mit Platzhaltern: `sf("Noch {0}", "12 Min")`.
     * In den Vorlagen `{0}`, `{1}` … statt String-Interpolation verwenden.
     */
    fun sf(de: String, vararg werte: Any?): String {
        var ergebnis = s(de)
        werte.forEachIndexed { i, wert ->
            ergebnis = ergebnis.replace("{$i}", wert?.toString() ?: "")
        }
        return ergebnis
    }

    /** Schreibrichtung der aktiven Sprache. */
    fun istRtl(): Boolean = sprache.rtl

    /** Für Verifikation/Tests: Anzahl Übersetzungen der aktiven Sprache. */
    fun anzahlUebersetzungen(): Int = sprache.tabelle.size
}
