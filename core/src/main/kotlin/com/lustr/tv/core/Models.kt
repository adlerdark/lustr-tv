package com.lustr.tv.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// JSON shapes returned by the lustr server. Only the fields the TV app uses are listed;
// everything else in a response is ignored (see LustrClient.json).
// Videos are identified by their file `path` - that's what every endpoint takes.

/** GET /api/health (public). */
@Serializable
data class Health(val status: String = "", val version: String = "")

/** GET /api/check_auth (public). */
@Serializable
data class AuthStatus(val authenticated: Boolean = false, val username: String? = null)

@Serializable
internal data class LoginBody(
    val username: String,
    val password: String,
    /** "tv": ask for a TV session (4 hours at most; servers without TV support ignore it). */
    val client: String? = null,
    /** Shown in the web app's list of linked TVs. */
    @SerialName("device_name") val deviceName: String? = null,
)

/** POST /api/tv/pair/start: the code to show, and the secret id to poll with (never shown). */
@Serializable
data class Pairing(
    @SerialName("pair_id") val pairId: String,
    val code: String,
    @SerialName("expires_in") val expiresIn: Int = 300,
    @SerialName("poll_every") val pollEvery: Int = 2,
)

@Serializable
internal data class PathBody(val path: String)

@Serializable
internal data class PairStartBody(@SerialName("device_name") val deviceName: String)

@Serializable
internal data class PairPollBody(@SerialName("pair_id") val pairId: String)

/** GET /api/settings/display-filter */
@Serializable
data class DisplayFilter(
    /** The types shown. */
    val types: List<String> = emptyList(),
    /** Every type there is (straight, gay, bisexual, trans, lesbian). */
    @SerialName("display_types") val displayTypes: List<String> = emptyList(),
)

@Serializable
internal data class DisplayFilterBody(val types: List<String>)

/** POST /api/tv/pair/poll: "pending", "expired" or "approved" (then the login cookie is set). */
@Serializable
data class PairStatus(val status: String = "expired") {
    val approved: Boolean get() = status == "approved"
    val expired: Boolean get() = status == "expired"
}

/** One video, as in /api/videos items, /api/home row items and /api/video. */
@Serializable
data class Video(
    val path: String,
    val filename: String = "",
    val title: String? = null,
    @SerialName("video_id") val videoId: String? = null,
    val library: String? = null,
    val year: String? = null,
    val favorite: Int = 0,
    val resolution: String? = null,
    val codec: String? = null,
    val extension: String? = null,
    /** "HH:MM:SS" */
    val length: String? = null,
    /** Scene number, when this video is part of a movie. */
    @SerialName("movie_scene") val movieScene: String? = null,
    @SerialName("cache_id") val cacheId: String? = null,
    /** Relative URL of the thumbnail (/cache/...), null until thumbnails are made. */
    val thumb: String? = null,
    val cast: List<String> = emptyList(),
    val site: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    // the other list fields (Video info shows them, read-only)
    val ethnicity: List<String> = emptyList(),
    val nationality: List<String> = emptyList(),
    val hair: List<String> = emptyList(),
    val body: List<String> = emptyList(),
    val tits: List<String> = emptyList(),
    val ass: List<String> = emptyList(),
    val face: List<String> = emptyList(),
    val cock: List<String> = emptyList(),
    val outfit: List<String> = emptyList(),
    val theme: List<String> = emptyList(),
    val feature: List<String> = emptyList(),
    val cumshot: List<String> = emptyList(),
    val participants: List<String> = emptyList(),
    val orientation: List<String> = emptyList(),
    val rating: String? = null,
    @SerialName("date_added") val dateAdded: String? = null,
    @SerialName("total_bitrate") val totalBitrate: String? = null,
    @SerialName("file_size") val fileSize: String? = null,
    /** The movie this scene belongs to ("" when none). */
    val movie: String? = null,
    /** Every file of this video, when there is more than one (/api/video only). */
    val versions: List<FileVersion> = emptyList(),
    /** Resume point in seconds (only present when there is one). */
    @SerialName("watch_progress") val watchProgress: Double? = null,
    @SerialName("watch_duration") val watchDuration: Double? = null,
) {
    /** The metadata fields in the web app's order, without empty ones (cast is shown on its own). */
    val metadata: List<Pair<String, List<String>>>
        get() = listOf(
            "Site" to site, "Tags" to tags, "Ethnicity" to ethnicity, "Nationality" to nationality,
            "Hair" to hair, "Body" to body, "Tits" to tits, "Ass" to ass, "Face" to face, "Cock" to cock,
            "Outfit" to outfit, "Theme" to theme, "Feature" to feature, "Cumshot" to cumshot,
            "Participants" to participants, "Orientation" to orientation,
        ).filter { it.second.isNotEmpty() }

    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: filename.substringBeforeLast('.').ifBlank { path }

    val isFavorite: Boolean get() = favorite != 0

    /** 0..1 for a progress bar, or null when not started. */
    val progressFraction: Float?
        get() {
            val at = watchProgress ?: return null
            val total = watchDuration ?: return null
            if (at <= 0 || total <= 0) return null
            return (at / total).toFloat().coerceIn(0f, 1f)
        }
}

/** One file of a video (File info). */
@Serializable
data class FileVersion(
    val path: String,
    val filename: String = "",
    val resolution: String? = null,
    val codec: String? = null,
    val extension: String? = null,
    val length: String? = null,
    @SerialName("total_bitrate") val totalBitrate: String? = null,
    @SerialName("file_size") val fileSize: String? = null,
    @SerialName("cache_id") val cacheId: String? = null,
    @SerialName("is_preferred") val isPreferred: Int = 0,
)

/** GET /api/cast/all */
@Serializable
data class CastList(val cast: List<CastMember> = emptyList())

@Serializable
data class CastMember(
    val name: String,
    /** Videos with this performer. */
    val count: Int = 0,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("has_photo") val hasPhoto: Boolean = false,
    /** female, male, trans_female, trans_male */
    val gender: String? = null,
    /** pro, amateur, celeb */
    val category: String? = null,
    val aliases: List<String> = emptyList(),
)

/** GET /api/cast/{name}/bio */
@Serializable
data class CastBio(
    val name: String,
    val aliases: List<String> = emptyList(),
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("has_photo") val hasPhoto: Boolean = false,
    val gender: String? = null,
    val category: String? = null,
    /** Their videos. */
    @SerialName("exact_matches") val videos: List<Video> = emptyList(),
    /** library -> how many of their videos are in it */
    @SerialName("library_distribution") val libraryDistribution: Map<String, Int> = emptyMap(),
    /** hair -> [Blonde], ethnicity -> [...], ... */
    val appearance: Map<String, List<String>> = emptyMap(),
    val stats: CastStats = CastStats(),
)

@Serializable
data class CastStats(
    @SerialName("total_videos") val totalVideos: Int = 0,
    val libraries: Int = 0,
)

/** GET /api/extdb/cast?name= : the stored StashDB / ThePornDB profile, when the performer is linked. */
@Serializable
data class ExtCastLink(
    val success: Boolean = false,
    val linked: Boolean = false,
    val link: JsonObject? = null,
)

/** GET /api/videos */
@Serializable
data class VideoPage(
    val items: List<Video> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 50,
)

/** GET /api/home */
@Serializable
data class HomeResponse(
    val success: Boolean = false,
    val error: String? = null,
    val rows: List<HomeRow> = emptyList(),
)

@Serializable
data class HomeRow(
    /** continue, recently_added, recently_watched, because:<id>, most_played, favorites, rediscover, collection:<id> */
    val key: String,
    val title: String,
    val items: List<Video> = emptyList(),
    /** How the web app opens the full list (e.g. {"sort": "Recently Added"}), or null. */
    @SerialName("see_all") val seeAll: JsonObject? = null,
)

/** GET /api/init: libraries, groups and the display filter (other fields are ignored for now). */
@Serializable
data class InitData(
    /** library name -> its folders */
    val libraries: Map<String, List<String>> = emptyMap(),
    /** Top-level groups and ungrouped libraries, in the owner's order. */
    @SerialName("library_order") val libraryOrder: List<String> = emptyList(),
    /** Hidden libraries; may also hold "All Videos". */
    @SerialName("hidden_libraries") val hiddenLibraries: List<String> = emptyList(),
    @SerialName("library_groups") val libraryGroups: Map<String, LibraryGroup> = emptyMap(),
    /** library name -> straight / gay / bisexual / trans / lesbian / mixed */
    @SerialName("library_types") val libraryTypes: Map<String, String> = emptyMap(),
    /** Every type the display filter knows. */
    @SerialName("display_types") val displayTypes: List<String> = emptyList(),
    /** The types this viewer chose to see (all of them = no filtering). */
    @SerialName("display_filter") val displayFilter: List<String> = emptyList(),
) {
    /** Library names in the order the owner set, without hidden ones. */
    val visibleLibraries: List<String>
        get() = (libraryOrder + libraries.keys.filter { it !in libraryOrder })
            .filter { it in libraries && it !in hiddenLibraries }
}

/** A group of libraries (and of other groups) in the sidebar. A group can be opened like a library. */
@Serializable
data class LibraryGroup(
    /** Library names and sub-group names, in order. */
    val libraries: List<String> = emptyList(),
    /** The parent group's name for a sub-group; empty / null for a top-level group. */
    val parent: String? = null,
)

/** GET /api/collections?library= */
@Serializable
data class CollectionList(
    val success: Boolean = false,
    val error: String? = null,
    val collections: List<VideoCollection> = emptyList(),
)

/** A hand-picked ("collection") or saved-filter ("smart") collection. */
@Serializable
data class VideoCollection(
    val id: Int,
    val name: String,
    val kind: String = "collection",
    val description: String? = null,
    val count: Int = 0,
    /** Relative thumbnail URL of its cover video, or null. */
    val cover: String? = null,
) {
    val isSmart: Boolean get() = kind == "smart"
}

/** GET /api/collections/{id}/items */
@Serializable
data class CollectionItems(
    val success: Boolean = false,
    val error: String? = null,
    val collection: VideoCollection? = null,
    val items: List<Video> = emptyList(),
    val total: Int = 0,
)

/** GET /api/movies */
@Serializable
data class MovieList(
    val success: Boolean = false,
    val error: String? = null,
    val movies: List<Movie> = emptyList(),
    val total: Int = 0,
)

/** A full movie made of scenes / parts (videos). */
@Serializable
data class Movie(
    val id: Int,
    val title: String? = null,
    /** Title with edition, as the web app shows it. */
    val display: String? = null,
    val year: String? = null,
    val studio: String? = null,
    val director: String? = null,
    val description: String? = null,
    /** Scenes in this library (list) / in total (detail). */
    val count: Int = 0,
    /** Minutes. */
    val runtime: Double? = null,
    val favorite: Boolean = false,
    /** True when the cover is a real cover (poster), false when it's a scene thumbnail. */
    @SerialName("has_cover") val hasCover: Boolean = false,
    /** Relative URL of the cover, or null. */
    val cover: String? = null,
) {
    val displayTitle: String get() = display?.takeIf { it.isNotBlank() } ?: title.orEmpty()
}

/** GET /api/movies/{id}: the movie and its scenes in order. */
@Serializable
data class MovieDetail(
    val success: Boolean = false,
    val error: String? = null,
    val movie: Movie? = null,
    val items: List<Video> = emptyList(),
)

/** GET /api/available_filters and /api/movies/filters: tag / site -> how many match. */
@Serializable
data class FilterCounts(
    val tags: Map<String, Int> = emptyMap(),
    val sites: Map<String, Int> = emptyMap(),
)

/** GET /api/player/streams: every way this file can be played. */
@Serializable
data class StreamInfo(
    val success: Boolean = false,
    val error: String? = null,
    val path: String = "",
    @SerialName("video_id") val videoId: String? = null,
    /** seconds */
    val duration: Double = 0.0,
    val width: Int? = null,
    val height: Int? = null,
    val codec: String? = null,
    val sources: List<StreamSource> = emptyList(),
    /** Index into sources the web player would start with (browser-oriented). */
    @SerialName("start_source") val startSource: Int = 0,
    val captions: CaptionInfo? = null,
)

@Serializable
data class StreamSource(
    /** "direct" (the original file, with range requests) or "hls" (transcoded on the fly) */
    val kind: String,
    val label: String = "",
    /** "direct", "original", "1080", "720", "480", "240" */
    val res: String = "",
    /** Whether a *web browser* can likely play it - not meaningful for ExoPlayer. */
    val likely: Boolean = true,
    /** Relative URL. */
    val src: String,
) {
    val isDirect: Boolean get() = kind == "direct"
    val isHls: Boolean get() = kind == "hls"
}

@Serializable
data class CaptionInfo(val tracks: List<CaptionTrack> = emptyList())

/** A WebVTT subtitle file. */
@Serializable
data class CaptionTrack(
    val lang: String = "und",
    val label: String = "",
    /** Relative URL of the .vtt file. */
    val src: String,
)

/** POST /api/watch/progress */
@Serializable
data class ProgressReport(
    val path: String,
    @SerialName("current_time") val currentTime: Double,
    val duration: Double,
    /** One per opening of a video in a player (Play History). */
    @SerialName("play_id") val playId: String,
    /** Seconds really played, skips not counted. */
    val played: Double,
    /** True when it's too early to save a resume point: only the play log is updated. */
    @SerialName("log_only") val logOnly: Boolean = false,
)
