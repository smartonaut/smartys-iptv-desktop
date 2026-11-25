import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.noarg")
}

/**
 * EINE Quelle fuer die Versionsnummer. Sie geht sowohl ins Paket
 * (packageVersion) als auch in die generierte BuildInfo.kt, die die App unter
 * Einstellungen anzeigt. Frueher stand die Nummer nur im Paket — die App
 * selbst konnte sie nicht anzeigen, und eine hartkodierte Fusszeile waere beim
 * naechsten Bump stehengeblieben.
 */
val appVersion = "2.3.12"

// Erzeugt BuildInfo.kt mit der Version. Laeuft bei jedem Build neu, damit die
// angezeigte Nummer nicht von der Paketversion weglaufen kann.
val generateBuildInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/buildInfo")
    val ver = appVersion
    outputs.dir(outDir)
    inputs.property("version", ver)
    doLast {
        val f = outDir.get().file("BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            package com.smartys.iptv

            /** Automatisch erzeugt von build.gradle.kts — nicht bearbeiten. */
            object BuildInfo {
                const val VERSION = "$ver"
            }
            """.trimIndent() + "\n"
        )
    }
}

// Gson-Fix für Desktop-JVM: parameterlose Konstruktoren für alle @NoArg-Klassen
// erzeugen (siehe NoArg.kt). Ohne das schlägt die Deserialisierung der
// Xtream-API-Antworten mit "Unable to create instance of class ..." fehl.
noArg {
    annotation("com.smartys.iptv.NoArg")
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
    }
    sourceSets {
        val jvmMain by getting {
            kotlin.srcDir(generateBuildInfo)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation("com.squareup.retrofit2:retrofit:2.11.0")
                implementation("com.squareup.retrofit2:converter-gson:2.11.0")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("org.json:json:20240303")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                // Video-Wiedergabe (libVLC via JNA)
                implementation("uk.co.caprica:vlcj:4.12.1")
                implementation("uk.co.caprica:vlcj-natives:4.12.0")
                // Bilder (Poster/Cover) — Coil 3 multiplatform (3.2.0 = Kotlin 2.1.20, passt zum Projekt)
                implementation("io.coil-kt.coil3:coil-compose:3.2.0")
                implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")
                // QR-Code fürs Pairing (scannen statt abtippen)
                implementation("com.google.zxing:core:3.5.3")
            }
        }
        // JVM-Tests (z.B. Testvektor der Player-ID aus PLAYER-IDENTITY.md).
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.smartys.iptv.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
            packageName = "SmartysIPTV"
            packageVersion = appVersion
            description = "Smartys IPTV — Live-TV, Filme und Serien auf dem Desktop"
            vendor = "Smartys"

            windows {
                // Installer-Auswahl: Desktop-Verknüpfung + Startmenü-Eintrag als
                // Checkboxen anbieten, Installationsverzeichnis wählbar machen.
                shortcut = true
                menu = true
                menuGroup = "Smartys"
                dirChooser = true
                // Stabiler Upgrade-Code: neuere Versionen installieren sauber
                // ÜBER die alte (statt sich daneben zu legen).
                upgradeUuid = "402511a4-d5a1-4e68-8e1f-856c7755498b"
                // Eigenes App-Icon (statt Compose-Zahnrad-Default)
                iconFile.set(rootProject.file("branding/logo.ico"))
            }

            linux {
                // Eigenes App-Icon (statt Compose-Zahnrad-Default)
                iconFile.set(rootProject.file("branding/logo_256.png"))
                // Menüeintrag im Anwendungsmenü anlegen (nicht nur Icon im App-Ordner)
                shortcut = true
                menuGroup = "Multimedia;AudioVideo;Video"
            }
        }
    }
}
