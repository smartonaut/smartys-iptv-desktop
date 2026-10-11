package com.smartys.iptv

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.zip.ZipInputStream

/**
 * libmpv C-API (include/mpv/client.h) — minimale JNA-Bindings.
 * Nur die Funktionen, die der Desktop-Player braucht.
 */
interface MpvLib : Library {

    fun mpv_create(): Pointer
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_set_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_observe_property(ctx: Pointer, reply_userdata: Long, name: String, format: Int): Int
    fun mpv_command(ctx: Pointer, args: Array<String>): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_free(data: Pointer)

    companion object {
        private val isWindows =
            System.getProperty("os.name").lowercase().contains("win")

        /**
         * Extrahiert das gebündelte libmpv (inkl. ffmpeg-Deps) beim ersten Start
         * nach ~/.smartys-iptv/libmpv/ und liefert das lib/-Verzeichnis.
         * Gleiches Muster wie VlcPlayer.ensureLibVlc().
         */
        private fun ensureLibMpv(): File? = runCatching {
            val home = File(System.getProperty("user.home"), ".smartys-iptv/libmpv")
            val marker = File(home, "lib/libmpv.so")
            if (!marker.exists()) {
                val zipRes = MpvLib::class.java.getResourceAsStream("/libmpv/libmpv-linux-x64.zip")
                    ?: return@runCatching null
                home.mkdirs()
                ZipInputStream(zipRes).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val target = File(home, entry.name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zis.copyTo(it, 1 shl 16) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            File(home, "lib")
        }.getOrNull()

        val INSTANCE: MpvLib by lazy {
            if (!isWindows) {
                ensureLibMpv()?.let { NativeLibrary.addSearchPath("mpv", it.absolutePath) }
            }
            Native.load(
                if (isWindows) "mpv-2" else "mpv",
                MpvLib::class.java
            )
        }
    }
}

/** mpv_event (C-Struktur). event_id/error sind int, reply_userdata ist u64, data ein void*. */
@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
class MpvEvent(ptr: Pointer? = null) : Structure(ptr) {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null

    companion object {
        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22
        const val EVENT_QUEUE_OVERFLOW = 24
    }
}

/** mpv_format (für get/set/observe_property). */
object MpvFormat {
    const val NONE = 0
    const val STRING = 1
    const val OSD_STRING = 2
    const val FLAG = 3
    const val INT64 = 4
    const val DOUBLE = 5
    const val NODE = 6
}
