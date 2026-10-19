package com.smartys.iptv

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.prefs.Preferences

/**
 * Persistenz für den Desktop. Ersetzt Android SharedPreferences durch die
 * JVM-eigene Preferences-API (java.util.prefs) — plattformübergreifend
 * (Windows: Registry, Linux: ~/.java/.userPrefs, macOS: plist), ohne Zusatzdep.
 *
 * Pro Profil getrennte Schlüssel wie in der Android-App (`__$pid`-Suffix),
 * damit Favoriten/Fortsetzen/Kategorie-/Kanal-Einstellungen profilgebunden sind.
 */

// ---------- Profile (Netflix-Stil) ----------

data class Profile(val id: String, val name: String, val color: Long)

object ProfileStore {

    private fun prefs(): Preferences = Preferences.userRoot().node("com/smartys/iptv/profiles")

    val COLORS = listOf(
        0xFFFFC94D, 0xFF6FCF9F, 0xFF6FB1FF, 0xFFFF9B9B, 0xFFB48CFF, 0xFF4FD1C5
    )

    fun list(): List<Profile> {
        val json = prefs().get("profiles_json", null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(o.getString("id"), o.getString("name"), o.optLong("color", COLORS[0]))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<Profile>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(org.json.JSONObject().put("id", p.id).put("name", p.name).put("color", p.color))
        }
        prefs().put("profiles_json", arr.toString())
    }

    fun activeId(): String? = prefs().get("active_profile_id", null)

    fun active(): Profile? = activeId()?.let { id -> list().firstOrNull { it.id == id } }

    fun setActive(id: String) { prefs().put("active_profile_id", id) }

    /** Stellt sicher, dass mindestens ein Profil existiert (Standard-Profil beim Erststart). */
    fun ensureDefault(): Profile {
        val existing = list()
        if (existing.isNotEmpty()) {
            val active = activeId()?.let { id -> existing.firstOrNull { it.id == id } } ?: existing.first()
            if (activeId() == null) setActive(active.id)
            return active
        }
        val p = add("Standard")
        setActive(p.id)
        return p
    }

    fun add(name: String): Profile {
        val trimmed = name.trim().ifBlank { "Profil" }
        val list = list().toMutableList()
        val color = COLORS[list.size % COLORS.size]
        val p = Profile(UUID.randomUUID().toString(), trimmed, color)
        list.add(p)
        write(list)
        return p
    }

    fun rename(id: String, name: String) {
        val t = name.trim(); if (t.isEmpty()) return
        write(list().map { if (it.id == id) it.copy(name = t) else it })
    }

    fun delete(id: String) {
        val remaining = list().filter { it.id != id }
        if (remaining.isEmpty()) return
        write(remaining)
        if (activeId() == id) setActive(remaining.first().id)
    }

    /** Präfix für profilgebundene Werte. */
    fun scoped(base: String): String = "${base}__${activeId() ?: "default"}"
}

// ---------- Fortsetzen (Resume) ----------

object ResumeStore {
    private fun prefs(): Preferences = Preferences.userRoot().node("com/smartys/iptv/${ProfileStore.scoped("resume")}")
    fun position(key: String): Long = prefs().getLong(key, 0L)
    fun save(key: String, posMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && posMs > 0 && posMs >= durationMs * 95 / 100
        if (finished) prefs().remove(key) else prefs().putLong(key, posMs)
    }

    /** Zuletzt gesehene Episode einer Serie (Pointer für den
     *  "Weiter schauen"-Button auf der Serien-Detailseite).
     *  Bei >= 95 % Fortschritt gilt die Episode als beendet → Pointer weg. */
    fun saveSeriesLast(seriesId: String, epId: String, posMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && posMs > 0 && posMs >= durationMs * 95 / 100
        if (finished) prefs().remove("slast:$seriesId")
        else prefs().put("slast:$seriesId", "$epId|$posMs")
    }

    /** (epId, Position) der zuletztgesehenen Episode — null wenn keine. */
    fun seriesLast(seriesId: String): Pair<String, Long>? {
        val v = prefs().get("slast:$seriesId", null) ?: return null
        val i = v.indexOf('|')
        if (i <= 0) return null
        val pos = v.substring(i + 1).toLongOrNull() ?: return null
        return v.substring(0, i) to pos
    }
    fun clear(key: String) { prefs().remove(key) }
}

// ---------- Favoriten + Zuletzt gesehen ----------

object FavoritesStore {
    private fun prefs(): Preferences = Preferences.userRoot().node("com/smartys/iptv/${ProfileStore.scoped("state")}")
    fun favorites(): Set<String> = prefs().get("favorites", "")!!.split(",").filter { it.isNotBlank() }.toSet()
    fun toggle(streamId: String): Set<String> {
        val cur = favorites().toMutableSet()
        if (!cur.add(streamId)) cur.remove(streamId)
        prefs().put("favorites", cur.joinToString(","))
        return cur
    }
    fun recentIds(): List<String> = prefs().get("recent_ids", "")!!.split(",").filter { it.isNotBlank() }.take(10)
    fun markWatched(streamId: String): List<String> {
        val cur = (listOf(streamId) + recentIds().filter { it != streamId }).take(10)
        prefs().put("recent_ids", cur.joinToString(","))
        return cur
    }
}

// ---------- Kindersicherung (PIN) ----------

object ParentalControl {
    private fun prefs(): Preferences = Preferences.userRoot().node("com/smartys/iptv/parental")
    fun isEnabled(): Boolean = prefs().getBoolean("pin_enabled", false)
    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }
    fun setPin(pin: String) { prefs().put("pin_hash", hash(pin)); prefs().putBoolean("pin_enabled", true) }
    fun disable() { prefs().remove("pin_hash"); prefs().putBoolean("pin_enabled", false) }
    fun verify(pin: String): Boolean {
        val stored = prefs().get("pin_hash", null) ?: return false
        return stored == hash(pin)
    }
}

// ---------- Kategorie-Verwaltung (VOD-Sidebar) ----------

object CategoryPrefs {
    private fun prefs(scope: String): Preferences = Preferences.userRoot().node("com/smartys/iptv/${ProfileStore.scoped("vodcat")}/$scope")

    fun applyTo(scope: String, cats: List<Category>): List<Category> {
        val p = prefs(scope)
        val order = p.get("order", "")!!.split(",").filter { it.isNotBlank() }
        val hidden = p.get("hidden", "")!!.split(",").filter { it.isNotBlank() }.toSet()
        val visible = cats.filter { it.id !in hidden }
        if (order.isEmpty()) return visible
        val inOrder = order.mapNotNull { id -> visible.firstOrNull { it.id == id } }
        val rest = visible.filter { it.id !in order }
        return inOrder + rest
    }

    fun move(scope: String, catId: String, cats: List<Category>, delta: Int) {
        val p = prefs(scope)
        val saved = p.get("order", "")!!.split(",").filter { it.isNotBlank() }
        val ids = cats.map { it.id }
        val current = (saved.filter { it in ids } + ids.filter { it !in saved }).toMutableList()
        val idx = current.indexOf(catId)
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, current.size - 1)
        if (target == idx) return
        current.removeAt(idx); current.add(target, catId)
        p.put("order", current.joinToString(","))
    }

    /** Mehrere Kategorien als EIN Block um eine Position verschieben (interne Reihenfolge bleibt). */
    fun moveBlock(scope: String, catIds: List<String>, cats: List<Category>, up: Boolean) {
        if (catIds.isEmpty()) return
        val p = prefs(scope)
        val saved = p.get("order", "")!!.split(",").filter { it.isNotBlank() }
        val ids = cats.map { it.id }
        val list = (saved.filter { it in ids } + ids.filter { it !in saved }).toMutableList()
        val sel = catIds.toSet()
        val first = list.indexOfFirst { it in sel }
        val last = list.indexOfLast { it in sel }
        if (first < 0) return
        if (first == last) { move(scope, catIds.first(), cats, if (up) -1 else 1); return }
        val neighbor = if (up) first - 1 else last + 1
        if (neighbor < 0 || neighbor >= list.size) return
        val item = list.removeAt(neighbor)
        list.add(if (up) last + 1 else first, item)
        p.put("order", list.joinToString(","))
    }

    fun hide(scope: String, catId: String) {
        val p = prefs(scope)
        val hidden = p.get("hidden", "")!!.split(",").filter { it.isNotBlank() }.toMutableSet()
        hidden.add(catId); p.put("hidden", hidden.joinToString(","))
    }

    fun hideMany(scope: String, catIds: List<String>) {
        if (catIds.isEmpty()) return
        val p = prefs(scope)
        val hidden = p.get("hidden", "")!!.split(",").filter { it.isNotBlank() }.toMutableSet()
        hidden.addAll(catIds); p.put("hidden", hidden.joinToString(","))
    }

    fun unhideMany(scope: String, catIds: List<String>) {
        if (catIds.isEmpty()) return
        val p = prefs(scope)
        val hidden = p.get("hidden", "")!!.split(",").filter { it.isNotBlank() }.toMutableSet()
        hidden.removeAll(catIds.toSet()); p.put("hidden", hidden.joinToString(","))
    }


    fun unhideAll(scope: String) { prefs(scope).put("hidden", "") }
    fun isHidden(scope: String, catId: String): Boolean =
        prefs(scope).get("hidden", "")!!.split(",").filter { it.isNotBlank() }.contains(catId)
}

// ---------- Kanal-Verwaltung (Live-TV) ----------

object ChannelPrefs {
    private fun prefs(): Preferences = Preferences.userRoot().node("com/smartys/iptv/${ProfileStore.scoped("chan")}")

    fun sortMode(): String = prefs().get("sort_mode", "num") ?: "num"
    fun setSortMode(mode: String) { prefs().put("sort_mode", mode) }

    fun isHidden(streamId: String): Boolean = hiddenSet().contains(streamId)
    fun hide(streamId: String) { val s = hiddenSet().toMutableSet(); s.add(streamId); saveHidden(s) }
    fun unhide(streamId: String) { val s = hiddenSet().toMutableSet(); s.remove(streamId); saveHidden(s) }
    fun unhideAll() { saveHidden(emptySet()) }
    private fun hiddenSet(): Set<String> = prefs().get("hidden", "")!!.split(",").filter { it.isNotBlank() }.toSet()
    private fun saveHidden(s: Set<String>) { prefs().put("hidden", s.joinToString(",")) }

    fun isLocked(streamId: String): Boolean = lockedSet().contains(streamId)
    fun setLocked(streamId: String, locked: Boolean) {
        val s = lockedSet().toMutableSet()
        if (locked) s.add(streamId) else s.remove(streamId)
        prefs().put("locked", s.joinToString(","))
    }
    private fun lockedSet(): Set<String> = prefs().get("locked", "")!!.split(",").filter { it.isNotBlank() }.toSet()

    fun apply(list: List<LiveStream>): List<LiveStream> {
        val visible = list.filter { !isHidden(it.streamId) }
        return when (sortMode()) {
            "name" -> visible.sortedBy { it.name.lowercase() }
            "manual" -> {
                val order = orderIds()
                val inOrder = order.mapNotNull { id -> visible.firstOrNull { it.streamId == id } }
                val rest = visible.filter { it.streamId !in order }.sortedBy { it.num ?: 0 }
                inOrder + rest
            }
            else -> visible.sortedBy { it.num ?: 0 }
        }
    }

    fun move(channels: List<LiveStream>, streamId: String, delta: Int) {
        val current = apply(channels).toMutableList()
        val idx = current.indexOfFirst { it.streamId == streamId }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, current.size - 1)
        if (target == idx) return
        val item = current.removeAt(idx); current.add(target, item)
        prefs().put("order", current.map { it.streamId }.joinToString(","))
        setSortMode("manual")
    }

    private fun orderIds(): List<String> = prefs().get("order", "")!!.split(",").filter { it.isNotBlank() }
}
