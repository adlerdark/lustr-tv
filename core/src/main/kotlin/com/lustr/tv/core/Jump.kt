package com.lustr.tv.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer

/** Where a letter starts in a sorted list: /api/videos sends these for Title, Cast and Filename sorts. */
@Serializable
data class LetterIndex(val letter: String, val index: Int)

/** The #-Z jump bar. */
object Letters {
    /** "#" then A-Z. */
    val ALL: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

    /** Sorts that come with letters (and so get a jump bar). */
    val SORTS = setOf(SortOption.TITLE.value, SortOption.CAST.value, SortOption.FILENAME.value)

    /** The server's rule (routes/videos.py _letter_of): first letter without accents, A-Z, else "#". */
    fun of(text: String): String {
        val first = Normalizer.normalize(text.trim(), Normalizer.Form.NFKD).firstOrNull()?.uppercaseChar() ?: return "#"
        return if (first in 'A'..'Z') first.toString() else "#"
    }

    /** Letters for an already sorted list (e.g. movie titles), as the server would send them. */
    fun index(sortedTitles: List<String>, offset: Int = 0): List<LetterIndex> {
        val seen = mutableSetOf<String>()
        return sortedTitles.mapIndexedNotNull { i, title ->
            val l = of(title)
            if (seen.add(l)) LetterIndex(l, i + offset) else null
        }
    }
}

/** A chapter / marker of a video (GET /api/player/markers?video_id=). */
@Serializable
data class Marker(
    val id: Int = 0,
    val seconds: Double,
    @SerialName("end_seconds") val endSeconds: Double? = null,
    val title: String = "",
    val tag: String = "",
    /** "user", or where it came from (e.g. chapters in the file). */
    val source: String = "user",
) {
    val label: String get() = title.ifBlank { tag }.ifBlank { "Marker" }
}

@Serializable
internal data class MarkerList(val success: Boolean = false, val error: String? = null, val markers: List<Marker> = emptyList())

/** /api/player/streams: the seek-bar preview sheet (sprite.jpg + thumbs.vtt in the video's cache folder). */
@Serializable
data class SpriteInfo(val ready: Boolean = false, val vtt: String? = null)

/** One tile of the preview sheet: the frame for [start, end) seconds is at x, y, w, h in [image]. */
data class SpriteTile(val start: Double, val end: Double, val image: String, val x: Int, val y: Int, val w: Int, val h: Int)

/** Reads thumbs.vtt ("00:00:00.000 --> 00:00:22.222" then "sprite.jpg#xywh=0,0,160,90"). */
object SpriteSheet {
    fun parse(vtt: String): List<SpriteTile> {
        val lines = vtt.lines().map { it.trim() }
        val out = mutableListOf<SpriteTile>()
        for (i in lines.indices) {
            val times = lines[i].split("-->").map { it.trim() }
            if (times.size != 2) continue
            val target = lines.getOrNull(i + 1) ?: continue
            val image = target.substringBefore("#xywh=")
            val box = target.substringAfter("#xywh=", "").split(',').mapNotNull { it.trim().toIntOrNull() }
            if (box.size != 4) continue
            out += SpriteTile(seconds(times[0]), seconds(times[1]), image, box[0], box[1], box[2], box[3])
        }
        return out
    }

    /** The tile showing [at] seconds (the last one past the end). */
    fun tileAt(tiles: List<SpriteTile>, at: Double): SpriteTile? =
        tiles.firstOrNull { at >= it.start && at < it.end } ?: tiles.lastOrNull()?.takeIf { at >= it.start }

    /** "01:02:03.500" or "02:03.500" -> seconds. */
    internal fun seconds(text: String): Double =
        text.split(':').fold(0.0) { acc, part -> acc * 60 + (part.toDoubleOrNull() ?: 0.0) }
}

/**
 * The moving preview of a focused card, like the web app's hover (index.html: startHover / getThumb):
 * the frames thumb_0 … thumb_7.webp in the video's cache folder, one every [FRAME_MS].
 */
object HoverPreview {
    const val FRAMES = 8
    const val FRAME_MS = 600L

    /** Relative URLs of the frames (with the card picture's ?v=, so rebuilt frames aren't stale); empty without a cache folder. */
    fun frames(video: Video): List<String> {
        val id = video.cacheId?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (video.thumb.isNullOrBlank()) return emptyList()            // no thumbnails made yet
        val version = Regex("""\?v=\d+""").find(video.thumb)?.value.orEmpty()
        return List(FRAMES) { "/cache/$id/thumb_$it.webp$version" }
    }
}
