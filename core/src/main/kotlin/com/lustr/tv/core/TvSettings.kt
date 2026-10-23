package com.lustr.tv.core

import kotlinx.serialization.Serializable

/**
 * The TV app's own settings (kept on the TV). The orientation filter isn't here: it's the
 * server's display filter (GET / POST /api/settings/display-filter).
 */
@Serializable
data class TvSettings(
    /** Length ranges the Library tab starts with (LengthRange values). Default: everything but < 2 min. */
    val defaultLengths: Set<String> = LengthRange.entries.filter { it != LengthRange.UNDER_2 }.map { it.value }.toSet(),
    /** Quality the player starts with: "direct" (falls back to HLS), or an HLS resolution. */
    val defaultQuality: String = "direct",
    /** Left / right on the remote jump this far. */
    val seekStepSeconds: Int = 10,
    val resume: ResumeMode = ResumeMode.ALWAYS,
    /** Show captions when a video has them. */
    val subtitlesOn: Boolean = false,
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val zoom: VideoZoom = VideoZoom.FIT,
    /** Let the TV switch its refresh rate to the video's (only when the switch is seamless). */
    val matchFrameRate: Boolean = true,
    /** Hide the app's picture in the recent-apps list and block screenshots / casting. */
    val privacyScreen: Boolean = true,
    /** The selected video card plays through its preview frames, like hovering in the web app. */
    val animatedPreviews: Boolean = true,
) {
    companion object {
        val QUALITIES = listOf("direct" to "Original file (direct play)", "original" to "Transcode: original size",
            "1080" to "Transcode: 1080p", "720" to "Transcode: 720p", "480" to "Transcode: 480p")
        val SEEK_STEPS = listOf(5, 10, 15, 30)

        fun decode(text: String?): TvSettings = try {
            if (text.isNullOrBlank()) TvSettings() else LustrClient.json.decodeFromString(serializer(), text)
        } catch (e: Exception) {
            TvSettings()
        }

        fun encode(settings: TvSettings): String = LustrClient.json.encodeToString(serializer(), settings)
    }
}

enum class ResumeMode(val label: String) { ALWAYS("Resume where I left off"), ASK("Ask each time"), NEVER("Always start from the beginning") }

enum class SubtitleSize(val label: String, val scale: Float) { SMALL("Small", 0.8f), NORMAL("Normal", 1f), LARGE("Large", 1.3f), HUGE("Very large", 1.7f) }

enum class VideoZoom(val label: String) { FIT("Fit (black bars)"), ZOOM("Zoom to fill (crops)"), STRETCH("Stretch") }

/** The next value of an enum / list, wrapping around: settings change with one press of OK. */
fun <T> List<T>.after(current: T): T = this[(indexOf(current) + 1).mod(size)]
