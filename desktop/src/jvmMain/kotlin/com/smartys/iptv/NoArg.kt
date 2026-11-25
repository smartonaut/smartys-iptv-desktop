package com.smartys.iptv

/**
 * Markiert Datenklassen, für die das kotlin-noarg-Plugin einen parameterlosen
 * Konstruktor erzeugt. Nötig für Gson auf der Desktop-JVM (JDK 17+), wo Gson
 * keine Instanzen mehr per Unsafe erzeugen darf ("Unable to create instance
 * of class ... adding a no-args constructor may fix this problem").
 */
annotation class NoArg
