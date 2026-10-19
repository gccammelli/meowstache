package io.github.gccammelli.meowstache

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Visit videos (GitHub #3): a Home Assistant automation saves a clip into a media folder when a cat
 * shows up, named by its UTC time ("2026-10-15T17-09-33Z.mp4"); see docs/visit-videos.md.
 * Meowstache lists that folder and links each visit to the clip nearest in time.
 */
data class Clip(val ts: Long, val id: String)

private val CLIP_NAME = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2})-(\d{2})-(\d{2})Z\.(mp4|mov|m4v|webm|mkv)$""", RegexOption.IGNORE_CASE)

/** The UTC time in a clip's file name, or null for other files. */
fun clipTime(name: String): Long? = CLIP_NAME.find(name)?.destructured?.let { (y, mo, d, h, mi, s) ->
    LocalDateTime(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()).toInstant(TimeZone.UTC).toEpochMilliseconds()
}

/**
 * The clips of the visit weighed at [weighTs], oldest first: the nearest from the known visit start (otherwise 5
 * minutes before) to 10 minutes after, plus its neighbours less than 2 minutes apart. Ring splits a long visit into
 * recordings saved about a minute apart.
 */
fun clipsFor(weighTs: Long, clips: List<Clip>, visitMinutes: Double? = null): List<Clip> {
    val start = visitMinutes?.takeIf { it.isFinite() && it >= 0 }?.let { weighTs - (it * 60_000).toLong() - 30_000L }
    val near = clips.filter { it.ts in (start ?: weighTs - 5 * 60_000L)..weighTs + 10 * 60_000L }.sortedBy { it.ts }
    var first = near.indices.minByOrNull { kotlin.math.abs(near[it].ts - weighTs) } ?: return emptyList()
    var last = first
    // Clips that started during a visit of known length (back to 30 s before the cat came in) are its parts too:
    // Ring's short recordings of a long visit can start more than 2 minutes apart.
    while (first > 0 && (near[first].ts - near[first - 1].ts < 2 * 60_000L || start != null && near[first - 1].ts >= start)) first--
    while (last < near.lastIndex && near[last + 1].ts - near[last].ts < 2 * 60_000L) last++
    return near.subList(first, last + 1)
}

/**
 * The clips in a folder of Home Assistant's local media ("meowstache" = /media/meowstache).
 * The folder only appears with the first saved clip, so a missing one just means no clips yet.
 */
suspend fun HaBackend.clips(folder: String): List<Clip> {
    val r = try {
        ws(listOf(buildJsonObject {
            put("type", "media_source/browse_media"); put("media_content_id", "media-source://media_source/local/${folder.trim('/')}")
        })).first() as? JsonObject ?: return emptyList()
    } catch (e: ApiException) {
        if ("does not exist" in e.message.orEmpty()) return emptyList() else throw e
    }
    return r.a("children").mapNotNull { c ->
        val id = c.s("media_content_id") ?: return@mapNotNull null
        Clip(clipTime(c.s("title") ?: id) ?: return@mapNotNull null, id)
    }.sortedBy { it.ts }
}

/** A playable link to the clip, signed by Home Assistant for an hour (no token in it). */
suspend fun HaBackend.clipUrl(c: Clip): String {
    val r = ws(listOf(buildJsonObject {
        put("type", "media_source/resolve_media"); put("media_content_id", c.id); put("expires", 3600)
    })).first()?.jsonObject
    val url = r?.s("url") ?: throw ApiException("Home Assistant couldn't find that video")
    return if ("://" in url) url else base + url
}
