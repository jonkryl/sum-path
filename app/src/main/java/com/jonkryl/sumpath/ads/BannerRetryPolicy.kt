package com.jonkryl.sumpath.ads

/** At most three requests per cycle, then a five-minute cooldown; only one request in flight. */
class BannerRetryPolicy {
    private var inFlight = false
    private var loaded = false
    private var failuresInCycle = 0
    private var eligibleAt = 0L

    fun tryStart(nowMillis: Long): Boolean {
        if (loaded || inFlight || nowMillis < eligibleAt) return false
        inFlight = true
        return true
    }

    fun failed(nowMillis: Long): Long? {
        if (!inFlight) return null
        inFlight = false
        val delay = when (failuresInCycle++) {
            0 -> 15_000L
            1 -> 45_000L
            else -> {
                failuresInCycle = 0
                300_000L
            }
        }
        eligibleAt = nowMillis + delay
        return delay
    }

    fun succeeded() {
        inFlight = false
        loaded = true
    }

    fun remainingDelay(nowMillis: Long): Long? =
        if (loaded || inFlight) null else (eligibleAt - nowMillis).coerceAtLeast(0)

    fun reset() {
        inFlight = false
        loaded = false
        failuresInCycle = 0
        eligibleAt = 0
    }
}
