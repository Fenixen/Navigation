package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.geo.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraControllerTest {
    private val p = GeoPoint(49.19, 16.61)
    private val persisted = mutableListOf<Double?>()

    private fun controller(initialLock: Double? = null) =
        CameraController(initialZoomLock = initialLock, onZoomLockChanged = { persisted += it })

    private fun CameraController.follow(speedKmh: Double, toManeuver: Double? = null): CameraCommand.Follow =
        assertIs(onPosition(p, 90.0, speedKmh / 3.6, toManeuver, navigating = true))

    @Test
    fun `auto zoom zooms out with speed`() {
        val c = controller()
        repeat(30) { c.follow(30.0) }
        val city = c.follow(30.0).zoom
        repeat(30) { c.follow(130.0) }
        val highway = c.follow(130.0).zoom
        assertTrue(city > highway + 1.5, "city=$city highway=$highway")
    }

    @Test
    fun `max zoom stays forever - speed, maneuvers, pan and recenter do not change it`() {
        val c = controller()
        val max = CameraConfig().maxZoom
        assertEquals(max, assertIs<CameraCommand.ZoomTo>(c.lockMaxZoom()).zoom, "works before first GPS fix")
        c.follow(50.0)
        assertEquals(max, assertIs<CameraCommand.Follow>(c.lockMaxZoom()).zoom)
        // Jedeme dálnicí, blíží se manévr, posouváme mapu, vracíme se…
        for (speed in listOf(0.0, 50.0, 130.0, 90.0)) assertEquals(max, c.follow(speed, toManeuver = 50.0).zoom)
        c.onUserPan()
        assertNull(c.onPosition(p, 0.0, 30.0, null, navigating = true), "no camera moves while user pans")
        val back = assertIs<CameraCommand.Follow>(c.recenter())
        assertEquals(max, back.zoom)
        assertEquals(max, c.follow(130.0).zoom)
        assertTrue(c.isZoomLocked)
        assertEquals(listOf<Double?>(max, max), persisted)
    }

    @Test
    fun `pinch while following locks that zoom, pinch while exploring does not`() {
        val c = controller()
        c.follow(50.0)
        assertEquals(18.2, assertIs<CameraCommand.Follow>(c.onUserZoom(18.2)).zoom)
        assertEquals(18.2, c.follow(120.0).zoom)

        c.onUserPan()
        assertNull(c.onUserZoom(10.0), "exploring the map is not changing the driving zoom")
        assertEquals(18.2, assertIs<CameraCommand.Follow>(c.recenter()).zoom)
        assertEquals(listOf<Double?>(18.2), persisted)
    }

    @Test
    fun `zoom buttons step and clamp to limits`() {
        val c = controller(initialLock = 20.0)
        c.follow(50.0)
        assertEquals(20.5, assertIs<CameraCommand.Follow>(c.zoomBy(+1)).zoom)
        assertEquals(20.5, assertIs<CameraCommand.Follow>(c.zoomBy(+1)).zoom)
        assertEquals(19.5, assertIs<CameraCommand.Follow>(c.zoomBy(-1)).zoom)
        c.onUserPan()
        assertEquals(13.0, assertIs<CameraCommand.ZoomTo>(c.zoomBy(-1, currentMapZoom = 14.0)).zoom)
        assertEquals(19.5, (c.state.value.zoomPolicy as ZoomPolicy.Locked).zoom, "FREE zoom keeps the lock")
    }

    @Test
    fun `lock is restored from persistence and only AUTO unlocks it`() {
        val c = controller(initialLock = 19.0)
        assertEquals(19.0, c.follow(130.0).zoom)
        c.showOverview(listOf(p, GeoPoint(49.3, 16.8)))
        assertEquals(19.0, assertIs<CameraCommand.Follow>(c.recenter()).zoom, "overview does not reset lock")
        c.enableAutoZoom()
        assertEquals(ZoomPolicy.Auto, c.state.value.zoomPolicy)
        assertNotEquals(19.0, c.follow(130.0).zoom)
        assertEquals(listOf<Double?>(null), persisted)
    }

    @Test
    fun `north up disables rotation and tilt`() {
        val c = controller()
        assertEquals(90.0, c.follow(50.0).bearing)
        assertEquals(MapOrientation.NORTH_UP, c.toggleOrientation())
        val cmd = c.follow(50.0)
        assertEquals(0.0, cmd.bearing)
        assertEquals(0.0, cmd.tilt)
    }
}
