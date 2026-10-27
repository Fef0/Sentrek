package app.sentrek.geo.plan

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.TileMath
import app.sentrek.geo.dem.Terrarium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {
    private val a = LatLon(46.0, 7.0)
    private val b = LatLon(46.01, 7.0)
    private val c = LatLon(46.01, 7.01)

    @Test
    fun `undo and redo walk the history, a new change drops the redo steps`() {
        val plan = PlanEditor<LatLon>()
        assertFalse(plan.canUndo)
        plan.add(a)
        plan.add(b)
        plan.add(c)
        plan.undo()
        assertEquals(listOf(a, b), plan.points)
        plan.redo()
        assertEquals(listOf(a, b, c), plan.points)
        plan.undo()
        plan.undo()
        plan.insert(0, c)
        assertEquals(listOf(c, a), plan.points)
        assertFalse(plan.canRedo)
        plan.move(1, b)
        assertEquals(listOf(c, b), plan.points)
        plan.remove(0)
        assertEquals(listOf(b), plan.points)
        plan.clear()
        assertTrue(plan.points.isEmpty())
        plan.undo()
        assertEquals(listOf(b), plan.points)
    }

    @Test
    fun `out of range edits change nothing`() {
        val plan = PlanEditor<LatLon>()
        plan.add(a)
        plan.move(3, b)
        plan.remove(-1)
        assertEquals(listOf(a), plan.points)
        plan.undo()
        assertTrue(plan.points.isEmpty())
    }

    @Test
    fun `densify keeps the given points and spaces the rest evenly`() {
        val dense = PlanGeometry.densify(listOf(a, b, c), stepM = 100.0)
        assertTrue(a in dense && b in dense && c in dense)
        val legAB = Geo.distance(a, b) // ≈ 1112 m: 12 steps
        assertEquals(Math.ceil(legAB / 100).toInt() + 1, dense.indexOf(b) + 1)
        dense.zipWithNext().forEach { (p, q) -> assertTrue(Geo.distance(p, q) <= 100.0) }
        assertEquals(Geo.length(listOf(a, b, c)), Geo.length(dense), 0.5)
    }

    @Test
    fun `halfway is in the middle by distance`() {
        // a-b is 1112 m north, b-c 772 m east: the middle is on a-b, 942 m from a.
        val half = PlanGeometry.halfway(listOf(a, b, c))
        assertEquals(Geo.length(listOf(a, b, c)) / 2, Geo.distance(a, half), 0.5)
        assertEquals(7.0, half.lon, 1e-9)
    }

    @Test
    fun `a leg joining a path from off it is drawn in pieces`() {
        val d = LatLon(46.02, 7.01)
        val both = PlanLeg(listOf(a, b, c, d), onTrails = true, offPathStart = true, offPathEnd = true)
        assertEquals(listOf(listOf(a, b) to false, listOf(b, c) to true, listOf(c, d) to false), both.pieces)
        assertEquals(listOf(listOf(a, b, c) to true), PlanLeg(listOf(a, b, c), onTrails = true).pieces)
        assertEquals(listOf(listOf(a, c) to false), PlanLeg(listOf(a, c), onTrails = false).pieces)
        // Off the path at the start only, straight onto the path's first point.
        assertEquals(listOf(listOf(a, b) to false, listOf(b, c) to true), PlanLeg(listOf(a, b, c), true, offPathStart = true).pieces)
    }

    @Test
    fun `terrarium decoding and bilinear sampling`() {
        // 0x80 0x00 0x00 is sea level; each unit of green is a metre.
        assertEquals(0f, Terrarium.decode(0xFF800000.toInt()), 0f)
        // 1500.5 m = 34268.5 = R 0x85, G 0xDC, B 0x80.
        assertEquals(1500.5f, Terrarium.decode(0xFF85DC80.toInt()), 0f)
        val tile = Terrarium.Tile(FloatArray(256 * 256) { i -> (i % 256).toFloat() }) // height = x
        val s = Terrarium.locate(46.0, 7.0)
        assertEquals(s.px.toFloat(), Terrarium.height(tile, s), 0.01f)
        assertEquals(Terrarium.TileKey(TileMath.tileX(7.0, 12), TileMath.tileY(46.0, 12)), s.key)
    }
}
