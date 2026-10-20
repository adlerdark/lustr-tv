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

    /**
     * Like [tvOrder], but starting with the quality chosen in Settings ([preferredRes]: "direct",
     * "original", "1080", ...). If the file has no such source, the next lower HLS one is used;
     * the rest stay as fallbacks.
     */
    fun order(sources: List<StreamSource>, preferredRes: String): List<StreamSource> {
        val base = tvOrder(sources)
        if (preferredRes == "direct") return base
        val hls = base.filter { it.isHls }
        val first = hls.firstOrNull { it.res == preferredRes }
            ?: preferredRes.toIntOrNull()?.let { want -> hls.firstOrNull { (it.res.toIntOrNull() ?: Int.MAX_VALUE) <= want } }
            ?: return base
        return listOf(first) + base.filter { it != first }
    }

    /** Where to start, in seconds: the saved resume point, unless it's at the very start or end. */
    fun startPosition(resumeAt: Double?, duration: Double): Double {
        val at = resumeAt ?: return 0.0
        if (at < MIN_RESUME_SECONDS) return 0.0
        if (duration > 0 && at / duration > COMPLETED_FRACTION) return 0.0
        return at
    }
}

/**
 * Left / right on the remote, without the seek bar: a press jumps [stepMs]; holding the button
 * seeks faster the longer it's held (shown as a target, applied when the button is let go).
 */
class RemoteSeek(private val stepMs: Long) {
    /** Where the seek will land, while one is building up; null when none is pending. */
    var target: Long? = null
        private set

    /** One press (key down, no repeat): [direction] = +1 forward, -1 back. Returns the new target. */
    fun tap(current: Long, direction: Int, duration: Long): Long =
        move(current, direction * stepMs, duration)

    /**
     * The button is held: called for each key repeat. [heldMs] = how long it's been down,
     * [sinceLastMs] = time since the previous call. Speed (video time per real second):
     * 6 steps/s for the first 2 s, 18 after that, 36 after 5 s.
     */
    fun hold(current: Long, direction: Int, heldMs: Long, sinceLastMs: Long, duration: Long): Long {
        val stepsPerSecond = when {
            heldMs < 2_000 -> 6
            heldMs < 5_000 -> 18
            else -> 36
        }
        val delta = stepMs * stepsPerSecond * sinceLastMs.coerceIn(0, 500) / 1000
        return move(current, direction * delta, duration)
    }

    /** The seek to apply now (and forget); null if none is pending. */
    fun take(): Long? = target.also { target = null }

    private fun move(current: Long, delta: Long, duration: Long): Long {
        val from = target ?: current
        val max = if (duration > 0) duration - 1_000 else Long.MAX_VALUE
        return (from + delta).coerceIn(0, max.coerceAtLeast(0)).also { target = it }
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
