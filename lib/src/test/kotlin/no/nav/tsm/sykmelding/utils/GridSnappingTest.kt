package no.nav.tsm.sykmelding.utils

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridSnappingTest {
    @Test
    fun `lines align to parallel rules and join perpendicular rules in either direction`() {
        val lines = listOf(
            GridSnapLine(true, 40, 20, 220), GridSnapLine(true, 150, 20, 220),
            GridSnapLine(false, 50, 20, 180), GridSnapLine(false, 200, 20, 180),
            GridSnapLine(false, 197, 300, 400),
        )
        assertEquals(GridEdit(GridEditTool.HORIZONTAL, 50, 40, 200, 40),
            snapGridEdit(GridEdit(GridEditTool.HORIZONTAL, 54, 44, 196, 44), lines, 8, 8))
        assertEquals(GridEdit(GridEditTool.HORIZONTAL, 200, 40, 50, 40),
            snapGridEdit(GridEdit(GridEditTool.HORIZONTAL, 196, 44, 54, 44), lines, 8, 8))
        assertEquals(GridEdit(GridEditTool.VERTICAL, 50, 40, 50, 150),
            snapGridEdit(GridEdit(GridEditTool.VERTICAL, 54, 44, 54, 146), lines, 8, 8))
        assertEquals(GridEdit(GridEditTool.VERTICAL, 50, 150, 50, 40),
            snapGridEdit(GridEdit(GridEditTool.VERTICAL, 54, 146, 54, 44), lines, 8, 8))
        val free = GridEdit(GridEditTool.HORIZONTAL, 80, 100, 160, 100)
        assertEquals(free, snapGridEdit(free, lines, 8, 8))
        assertEquals(GridEdit(GridEditTool.HORIZONTAL, 50, 100, 200, 100),
            snapGridEdit(free.copy(x1 = 54, x2 = 196), lines, 8, 8))
        val short = GridEdit(GridEditTool.HORIZONTAL, 48, 100, 52, 100)
        assertEquals(short, snapGridEdit(short, lines, 8, 8))
    }

    @Test
    fun `snap targets follow centerlines including added and erased rules`() {
        val image = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { g ->
            g.color = Color.WHITE
            g.fillRect(0, 0, 400, 200)
            g.color = Color.BLACK
            g.fillRect(49, 20, 3, 160)
            g.fillRect(20, 39, 360, 3)
            g.dispose()
        }
        toMat(image).use { source ->
            fun targets(edits: List<GridEdit>) = createGridMask(source, GridSettings(gapSize = 0), edits).use {
                findGridSnapLines(it, 12)
            }
            val original = targets(emptyList())
            assertTrue(original.any { !it.horizontal && it.coordinate == 50 })
            assertTrue(original.any { it.horizontal && it.coordinate == 40 })
            val added = targets(listOf(GridEdit(GridEditTool.CELL, 200, 80, 350, 150)))
            assertTrue(added.any { !it.horizontal && it.coordinate == 200 })
            assertTrue(added.any { it.horizontal && it.coordinate == 150 })
            val erased = targets(listOf(GridEdit(GridEditTool.ERASE, 45, 0, 55, 199)))
            assertTrue(erased.none { !it.horizontal && it.coordinate == 50 })
        }
    }

    @Test
    fun `snapping is local bounded and does not collapse cells or affect other tools`() {
        val lines = listOf(
            GridSnapLine(false, 50, 20, 180), GridSnapLine(false, 200, 20, 180),
            GridSnapLine(true, 40, 20, 250), GridSnapLine(true, 150, 20, 250),
            GridSnapLine(false, 53, 300, 400), // Closer, but in a different part of the page.
        )
        val edit = GridEdit(GridEditTool.CELL, 54, 44, 196, 146)
        assertEquals(GridEdit(GridEditTool.CELL, 50, 40, 200, 150), snapCellToGrid(edit, lines, 8, 8))
        assertEquals(edit, snapCellToGrid(edit, lines, 2, 2))
        val narrow = edit.copy(x1 = 48, x2 = 52)
        assertEquals(narrow.copy(y1 = 40, y2 = 150), snapCellToGrid(narrow, lines, 8, 8))
        for (tool in listOf(GridEditTool.ERASE, GridEditTool.HORIZONTAL, GridEditTool.VERTICAL)) {
            assertEquals(edit.copy(tool = tool), snapCellToGrid(edit.copy(tool = tool), lines, 8, 8))
        }
    }
}
