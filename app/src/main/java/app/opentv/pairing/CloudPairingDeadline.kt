/* Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.pairing

/** One elapsed-realtime deadline shared by the countdown and local expiry. */
internal class CloudPairingDeadline private constructor(private val until: Long) {
    fun remainingMillis(now: Long): Long = (until - now).coerceAtLeast(0)

    companion object {
        fun fromServer(pairUntil: Long, serverNow: Long, requestStarted: Long): CloudPairingDeadline {
            require(pairUntil >= 0 && serverNow >= 0 && requestStarted >= 0)
            // No device wall clock is involved. Charge the entire create round trip to the
            // budget, conservatively expiring at most one RTT early instead of extending it.
            // The Worker still enforces pairUntil and the separate hard session deadline.
            val budget = (pairUntil - serverNow).coerceAtLeast(0)
            require(budget <= Long.MAX_VALUE - requestStarted)
            return CloudPairingDeadline(requestStarted + budget)
        }

        fun secondsRemaining(millis: Long): Long =
            millis.coerceAtLeast(0).let { it / 1000 + if (it % 1000 > 0) 1 else 0 }

        fun format(seconds: Long): String {
            val clamped = seconds.coerceAtLeast(0)
            return "${clamped / 60}:${(clamped % 60).toString().padStart(2, '0')}"
        }
    }
}
