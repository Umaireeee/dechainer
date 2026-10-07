package io.github.warleysr.dechainer.urge

/**
 * The blackout screen's clock. Pure: the screen hands in how much time is left, never the clock.
 * HH:MM:SS once there is an hour or more, MM:SS below that, and 00:00 the moment it is over.
 */
object BlackoutTimer {
    fun format(msLeft: Long): String {
        val total = msLeft.coerceAtLeast(0L) / 1000L
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }
}
