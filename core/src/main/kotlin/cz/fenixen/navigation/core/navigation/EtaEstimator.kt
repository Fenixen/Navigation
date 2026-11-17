package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.model.RouteTraffic

/**
 * Turns the router's (static, usually pessimistic) remaining time into a realistic ETA.
 *
 * Two corrections, multiplied together:
 *  1. **Traffic factor** – when live traffic is known: (traffic travel time) / (router time) for the
 *     same remaining stretch. Captures jams *ahead* and the router's general pessimism.
 *  2. **Pace factor** – learned while driving: how long the traveled part actually took vs. what
 *     the router predicted for it. That's your driving style + conditions so far, like a race
 *     engineer adjusting the predicted stint time from the first laps.
 *
 * The pace factor fades in (weight grows with traveled router-time) and is clamped, so one red
 * light or the first parking-lot minute can't wreck the estimate.
 */
class EtaEstimator(private val config: EtaConfig = EtaConfig()) {
    private var startedAt: Long? = null
    private var predictedTraveled = 0.0
    private var lastRawRemaining: Double? = null
    private var trafficFactor: Double? = null

    /** New route (start or reroute): keep learned pace, re-baseline the router time. */
    fun onNewRoute() {
        lastRawRemaining = null
        trafficFactor = null
    }

    fun onTraffic(traffic: RouteTraffic, rawRemainingAtFetch: Double) {
        if (rawRemainingAtFetch < 60) return
        trafficFactor = (traffic.travelTimeSeconds / rawRemainingAtFetch).coerceIn(config.minFactor, config.maxTrafficFactor)
    }

    /** Feed the raw (router-based) remaining time, get the corrected remaining time. */
    fun update(rawRemaining: Double, speedMps: Double?, nowMillis: Long): Double {
        // Hodiny se spouští až s prvním rozjezdem – čekání na parkovišti se nepočítá.
        if (startedAt == null && (speedMps ?: 0.0) > config.startSpeedMps) startedAt = nowMillis
        val last = lastRawRemaining
        if (last != null && startedAt != null) {
            val delta = last - rawRemaining
            if (delta > 0) predictedTraveled += delta
        }
        lastRawRemaining = rawRemaining
        return rawRemaining * factor(nowMillis)
    }

    fun factor(nowMillis: Long): Double {
        val traffic = trafficFactor ?: 1.0
        val start = startedAt ?: return traffic
        if (predictedTraveled < config.minLearnSeconds) return traffic
        val actual = (nowMillis - start) / 1000.0
        val ratio = (actual / predictedTraveled).coerceIn(config.minFactor, config.maxPaceFactor)
        val weight = (predictedTraveled / config.fullConfidenceSeconds).coerceIn(0.0, 1.0) *
            (if (trafficFactor != null) config.paceWeightWithTraffic else 1.0)
        val pace = 1.0 + weight * (ratio - 1.0)
        return (traffic * pace).coerceIn(config.minFactor, config.maxTrafficFactor)
    }
}

data class EtaConfig(
    val startSpeedMps: Double = 2.0,
    /** Router-time that must be driven before pace learning kicks in. */
    val minLearnSeconds: Double = 90.0,
    val fullConfidenceSeconds: Double = 900.0,
    /** With live traffic the pace correction is applied only partly (traffic already explains a lot). */
    val paceWeightWithTraffic: Double = 0.5,
    val minFactor: Double = 0.55,
    val maxPaceFactor: Double = 1.6,
    val maxTrafficFactor: Double = 4.0,
)
