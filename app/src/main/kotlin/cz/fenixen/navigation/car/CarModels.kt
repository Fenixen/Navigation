package cz.fenixen.navigation.car

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import cz.fenixen.navigation.core.model.ManeuverType
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.map.ManeuverIcons
import java.util.TimeZone
import kotlin.math.roundToInt
import cz.fenixen.navigation.core.model.Maneuver as CoreManeuver

/** Mapping from our platform-independent model to Car App Library models. */
internal object CarModels {

    fun icon(context: Context, @DrawableRes res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(context, res)).setTint(CarColor.DEFAULT).build()

    /** Same rounding as the phone banner (CzechFormat.distance) so both screens agree. */
    fun distance(meters: Double): Distance {
        val m = meters.coerceAtLeast(0.0)
        return when {
            m < 50 -> Distance.create((m / 5).roundToInt() * 5.0, Distance.UNIT_METERS)
            m < 300 -> Distance.create((m / 10).roundToInt() * 10.0, Distance.UNIT_METERS)
            m < 950 -> Distance.create((m / 50).roundToInt() * 50.0, Distance.UNIT_METERS)
            m < 9_950 -> Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS_P1)
            else -> Distance.create((m / 1000.0).roundToInt().toDouble(), Distance.UNIT_KILOMETERS)
        }
    }

    fun maneuver(m: CoreManeuver): Maneuver {
        val exit = m.roundaboutExitCount
        val type = when (m.type) {
            ManeuverType.DEPART, ManeuverType.DEPART_LEFT, ManeuverType.DEPART_RIGHT -> Maneuver.TYPE_DEPART
            ManeuverType.ARRIVE -> Maneuver.TYPE_DESTINATION
            ManeuverType.ARRIVE_LEFT -> Maneuver.TYPE_DESTINATION_LEFT
            ManeuverType.ARRIVE_RIGHT -> Maneuver.TYPE_DESTINATION_RIGHT
            ManeuverType.CONTINUE, ManeuverType.KEEP_STRAIGHT, ManeuverType.RAMP_STRAIGHT -> Maneuver.TYPE_STRAIGHT
            ManeuverType.NAME_CHANGE -> Maneuver.TYPE_NAME_CHANGE
            ManeuverType.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            ManeuverType.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            ManeuverType.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            ManeuverType.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            ManeuverType.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            ManeuverType.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            ManeuverType.UTURN_LEFT -> Maneuver.TYPE_U_TURN_LEFT
            ManeuverType.UTURN_RIGHT -> Maneuver.TYPE_U_TURN_RIGHT
            ManeuverType.RAMP_LEFT -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
            ManeuverType.RAMP_RIGHT -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
            ManeuverType.EXIT_LEFT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
            ManeuverType.EXIT_RIGHT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
            ManeuverType.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
            ManeuverType.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
            ManeuverType.MERGE -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            ManeuverType.MERGE_LEFT -> Maneuver.TYPE_MERGE_LEFT
            ManeuverType.MERGE_RIGHT -> Maneuver.TYPE_MERGE_RIGHT
            // Pravostranný provoz → kruhový objezd proti směru hodinových ručiček (CCW).
            ManeuverType.ROUNDABOUT_ENTER ->
                if (exit != null) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
            ManeuverType.ROUNDABOUT_EXIT -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
            ManeuverType.FERRY_ENTER -> Maneuver.TYPE_FERRY_BOAT
            ManeuverType.FERRY_EXIT -> Maneuver.TYPE_STRAIGHT
            ManeuverType.UNKNOWN -> Maneuver.TYPE_UNKNOWN
        }
        val builder = Maneuver.Builder(type)
            .setIcon(CarIcon.Builder(IconCompat.createWithBitmap(ManeuverIcons.bitmap(m, ICON_PX))).build())
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW && exit != null) builder.setRoundaboutExitNumber(exit)
        return builder.build()
    }

    fun step(m: CoreManeuver): Step =
        Step.Builder(m.instruction.removeSuffix("."))
            .setManeuver(maneuver(m))
            .apply { m.primaryName?.let(::setRoad) }
            .build()

    fun routingInfo(state: NavigationState.Navigating): RoutingInfo {
        val progress = state.progress
        val upcoming = state.upcoming
        if (state.isRerouting || progress == null || upcoming == null) return RoutingInfo.Builder().setLoading(true).build()
        val builder = RoutingInfo.Builder().setCurrentStep(step(upcoming), distance(progress.distanceToUpcoming))
        val following = state.following
        if (following != null && upcoming.lengthMeters < 250 && !upcoming.type.isArrival) builder.setNextStep(step(following))
        return builder.build()
    }

    fun destinationEstimate(state: NavigationState.Navigating): TravelEstimate? {
        val p = state.progress ?: return null
        val arrival = System.currentTimeMillis() + (p.durationRemaining * 1000).toLong()
        return TravelEstimate.Builder(distance(p.distanceRemaining), DateTimeWithZone.create(arrival, TimeZone.getDefault()))
            .setRemainingTimeSeconds(p.durationRemaining.toLong())
            .setRemainingTimeColor(delayColor(state.route.traffic?.delaySeconds))
            .build()
    }

    /** Green = free road, yellow = some delay, red = serious jam (live traffic). */
    private fun delayColor(delaySeconds: Double?): CarColor = when {
        delaySeconds == null || delaySeconds < 60 -> CarColor.GREEN
        delaySeconds < 600 -> CarColor.YELLOW
        else -> CarColor.RED
    }

    /** Trip for the instrument cluster / HUD (NavigationManager.updateTrip). */
    fun trip(state: NavigationState.Navigating): Trip {
        val builder = Trip.Builder().setLoading(state.isRerouting || state.progress == null)
        val p = state.progress
        val upcoming = state.upcoming
        if (p != null && upcoming != null && !state.isRerouting) {
            val stepArrival = System.currentTimeMillis() + (p.durationToUpcoming * 1000).toLong()
            builder.addStep(
                step(upcoming),
                TravelEstimate.Builder(distance(p.distanceToUpcoming), DateTimeWithZone.create(stepArrival, TimeZone.getDefault()))
                    .setRemainingTimeSeconds(p.durationToUpcoming.toLong())
                    .build(),
            )
            destinationEstimate(state)?.let { estimate ->
                builder.addDestination(
                    Destination.Builder().setName(state.destination.name).apply { state.destination.address?.let(::setAddress) }.build(),
                    estimate,
                )
            }
            state.route.maneuvers.getOrNull(p.upcomingManeuverIndex - 1)?.primaryName?.let(builder::setCurrentRoad)
        }
        return builder.build()
    }

    private const val ICON_PX = 128
}
