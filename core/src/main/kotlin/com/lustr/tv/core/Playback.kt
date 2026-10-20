package com.lustr.tv.core

import java.util.UUID

/** Playback rules shared by the player screen, kept here so they can be unit-tested. */
object Playback {
    /** Resume points before this are ignored (the web player does the same). */
    const val MIN_RESUME_SECONDS = 5.0

    /** The server marks a video as watched past 90 %; starting over from there makes more sense. */
    const val COMPLETED_FRACTION = 0.9

    /** How often the player saves progress while playing. */
    const val REPORT_EVERY_MS = 10_000L

    /**
     * The order to try sources in on the Shield. The web player avoids direct play for files a
     * browser can't decode (MKV, HEVC, ...), but ExoPlayer plays nearly all of them, and direct
     * play is full quality with no load on the server. HLS (server transcoding) is the fallback,
     * best quality first, as the server lists them.
     */
    fun tvOrder(sources: List<StreamSource>): List<StreamSource> =
        sources.filter { it.isDirect } + sources.filter { it.isHls }

    /** Where to start, in seconds: the saved resume point, unless it's at the very start or end. */
    fun startPosition(resumeAt: Double?, duration: Double): Double {
        val at = resumeAt ?: return 0.0
        if (at < MIN_RESUME_SECONDS) return 0.0
        if (duration > 0 && at / duration > COMPLETED_FRACTION) return 0.0
        return at
    }
}

/**
 * One opening of a video in the player, for POST /api/watch/progress: a play_id, plus how many
 * seconds have really been playing (seeking doesn't count). Mirrors the web player's rules.
 */
class PlayTracker(val path: String, val playId: String = UUID.randomUUID().toString()) {
    var playedSeconds: Double = 0.0
        private set

    /** Call with the wall-clock time that passed while the player was actually playing. */
    fun addPlayed(seconds: Double) {
        if (seconds > 0) playedSeconds += seconds
    }

    /**
     * The report to send, or null when there's nothing worth sending.
     * final = the player is closing; closing within the first seconds keeps the old resume point.
     */
    fun report(position: Double, duration: Double, final: Boolean): ProgressReport? {
        val early = final && position < Playback.MIN_RESUME_SECONDS
        if (early && playedSeconds <= 0) return null
        return ProgressReport(
            path = path,
            currentTime = position.coerceAtLeast(0.0),
            duration = duration.coerceAtLeast(0.0),
            playId = playId,
            played = playedSeconds,
            logOnly = early,
        )
    }
}
