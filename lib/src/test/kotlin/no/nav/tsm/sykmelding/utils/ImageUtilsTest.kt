package no.nav.tsm.sykmelding.utils

import org.bytedeco.opencv.opencv_core.Mat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class ImageUtilsTest {

   private fun findCellBoxes(image: Mat, settings: GridSettings = GridSettings(), edits: List<GridEdit> = emptyList()) =
        createGridMask(image, settings, edits).use { findGridCells(it, settings.minimumCellSize) }

    @Test
    fun `small near-miss junctions close but larger gaps require a manual repair`() {
        val image = gridImage {
            drawRect(10, 10, 180, 100)
            drawLine(100, 13, 100, 107)
        }
        toMat(image).use { source ->
            assertEquals(1, findCellBoxes(source, GridSettings(gapSize = 0)).size)
            assertEquals(2, findCellBoxes(source, GridSettings(gapSize = 3)).size)
        }
        val broken = gridImage {
            drawRect(10, 10, 180, 100)
            drawLine(100, 25, 100, 110)
        }
        toMat(broken).use { source ->
            assertEquals(1, findCellBoxes(source).size)
            assertEquals(2, findCellBoxes(source, edits = listOf(GridEdit(GridEditTool.VERTICAL, 100, 10, 100, 30))).size)
        }
    }

    @Test
    fun `ordered grid edits add split and merge cells without changing the image`() {
        val image = gridImage {}
        toMat(image).use { source ->
            val border = GridEdit(GridEditTool.CELL, 190, 110, 10, 10)
            val split = GridEdit(GridEditTool.VERTICAL, 100, 10, 100, 110)
            val erase = GridEdit(GridEditTool.ERASE, 97, 13, 103, 107)
            assertEquals(0, findCellBoxes(source).size)
            assertEquals(1, findCellBoxes(source, edits = listOf(border)).size)
            assertEquals(2, findCellBoxes(source, edits = listOf(border, split)).size)
            assertEquals(1, findCellBoxes(source, edits = listOf(border, split, erase)).size)
            assertEquals(0, findCellBoxes(source).size)
        }
    }

    @Test
    fun `text and foreground islands are not returned as cells`() {
        val image = gridImage {
            drawRect(10, 10, 180, 100)
            drawString("OOOO BBBB 8888", 20, 35)
            fillRect(40, 55, 30, 30)
        }
        toMat(image).use { source ->
            assertEquals(1, findCellBoxes(source, GridSettings(minimumLineLength = 20)).size)
        }
        toMat(gridImage { drawString("OOOO BBBB 8888", 20, 35) }).use { source ->
            assertEquals(0, findCellBoxes(source, GridSettings(minimumLineLength = 20)).size)
        }
    }

    private fun gridImage(draw: java.awt.Graphics2D.() -> Unit): BufferedImage =
        BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().let { graphics ->
                try {
                    graphics.color = Color.WHITE
                    graphics.fillRect(0, 0, width, height)
                    graphics.color = Color.BLACK
                    graphics.draw()
                } finally {
                    graphics.dispose()
                }
            }
        }

    @Test
    fun `finds cells enclosed by faint lines without treating text as a cell`() {
        val image = BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color(200, 200, 200)
            graphics.drawRect(5, 5, 89, 69)
            graphics.drawLine(50, 5, 50, 74)
            graphics.color = Color.BLACK
            graphics.drawString("Test", 10, 25)
        } finally {
            graphics.dispose()
        }

        val cells = findCellBoxes(toMat(image))
        assertEquals(2, cells.size)
        assertEquals(2, cells.count { it.bounds.y() < 40 && it.bounds.y() + it.bounds.height() > 40 })
    }

    @Test
    fun `tree mode reports parent indices for nested contours`() {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 32, 32)
            graphics.color = Color.BLACK
            graphics.fillRect(4, 4, 24, 24)
            graphics.color = Color.WHITE
            graphics.fillRect(10, 10, 12, 12)
        } finally {
            graphics.dispose()
        }

        val contours = findContourBoxes(toMat(image), RetreivalMode.RETR_TREE)
        assertEquals(2, contours.size)
        val root = contours.indexOfFirst { it.parentIndex == -1 }
        assertEquals(1, contours.count { it.parentIndex == -1 })
        assertEquals(1, contours.count { it.parentIndex == root })
    }

    @Test
    fun `finds contours in color and grayscale images`() {
        for (type in listOf(BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_BYTE_GRAY)) {
            val image = BufferedImage(32, 32, type)
            val graphics = image.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, 32, 32)
                graphics.color = Color.BLACK
                graphics.fillRect(8, 9, 10, 8)
            } finally {
                graphics.dispose()
            }

            val boxes = findContourBoxes(toMat(image)).map { it.bounds }
            assertEquals(1, boxes.size, "BufferedImage type $type")
            assertEquals(8, boxes.single().x())
            assertEquals(9, boxes.single().y())
            assertEquals(10, boxes.single().width())
            assertEquals(8, boxes.single().height())
        }
    }
}
