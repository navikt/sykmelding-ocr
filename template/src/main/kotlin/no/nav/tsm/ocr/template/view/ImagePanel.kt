package no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.template.Felter
import no.nav.tsm.sykmelding.ocr.ReferencesRectangle
import org.bytedeco.javacpp.PointerScope
import org.bytedeco.javacpp.indexer.IntIndexer
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_features2d.DRAW_RICH_KEYPOINTS
import org.bytedeco.opencv.global.opencv_features2d.drawKeypoints
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.opencv.opencv_core.*
import org.bytedeco.opencv.opencv_features2d.KAZE
import org.bytedeco.opencv.opencv_imgproc.Vec4iVector
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import javax.swing.JPanel
import kotlin.collections.get
import kotlin.math.PI

enum class GridMode(val label: String, val retrievalMode: Int, val description: String) {
    EXTERNAL("EXTERNAL (outer box)", RETR_EXTERNAL, "Only the outermost grid contours"),
    LIST("LIST (all contours)", RETR_LIST, "All grid and cell contours without hierarchy"),
    CCOMP("CCOMP (two levels)", RETR_CCOMP, "Grid contours and holes in a two-level hierarchy"),
    TREE("TREE (full hierarchy)", RETR_TREE, "All grid contours with their full nesting hierarchy")
}

class ImagePanel() : JPanel() {

    private var imgRatio: Float = 1.0f
    private var showOrbKeypoints = false
    private var orbImage: BufferedImage? = null
    private var gridOnlyKeypoints = false
    private var showHoughLines = false
    private var lineOverlay: BufferedImage? = null
    private var gridMode: GridMode? = null
    private val gridOverlays = mutableMapOf<GridMode, BufferedImage>()
    private var fields: List<Felter> = emptyList()
    private var selectedField = -1
    private var references: List<ReferencesRectangle?> = emptyList()
    private var selectedReference = -1
    var image: BufferedImage? = null

    var imgWidth: Int = 0
    var imgHeight: Int = 0

    fun load(bufferedImage: BufferedImage) {
        image = bufferedImage
        imgHeight = bufferedImage.height
        imgWidth = bufferedImage.width
        imgRatio = bufferedImage.getWidth() / bufferedImage.getHeight().toFloat()
        orbImage = null
        lineOverlay = null
        gridOverlays.clear()
        fields = emptyList()
        selectedField = -1
        references = emptyList()
        selectedReference = -1
        if (showOrbKeypoints) showOrbs()
        if (showHoughLines) showLines()
        gridMode?.let { showGrids(it) }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val ratio = this.width / this.height.toFloat()
        var renderHight = this.height
        var renderWidth = this.width
        if(ratio > imgRatio) {
            renderWidth = (imgRatio * renderHight).toInt()

        } else {
            renderHight = (renderWidth / imgRatio).toInt()
        }
        val displayedImage = if (showOrbKeypoints) orbImage ?: image else image
        displayedImage?.let { g.drawImage(it, 0, 0, renderWidth, renderHight, null) }
        if (showHoughLines) {
            lineOverlay?.let { g.drawImage(it, 0, 0, renderWidth, renderHight, null) }
        }
        gridOverlays[gridMode]?.let { g.drawImage(it, 0, 0, renderWidth, renderHight, null) }
        val source = image ?: return
        val overlay = g.create() as Graphics2D
        try {
            overlay.clipRect(0, 0, renderWidth, renderHight)
            overlay.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val scaleX = renderWidth.toDouble() / source.width
            val scaleY = renderHight.toDouble() / source.height
            fields.forEachIndexed { index, field ->
                val rectangle = Rectangle2D.Double(
                    field.x * scaleX, field.y * scaleY, field.bredde * scaleX, field.hoyde * scaleY,
                )
                if (index == selectedField) {
                    overlay.color = Color(255, 0, 0, 25)
                    overlay.fill(rectangle)
                }
                overlay.color = Color.RED
                overlay.stroke = BasicStroke(if (index == selectedField) 3f else 1.5f)
                overlay.draw(rectangle)
            }
            references.forEachIndexed { index, reference ->
                if (reference == null) return@forEachIndexed
                val rectangle = Rectangle2D.Double(
                    reference.x * scaleX, reference.y * scaleY,
                    reference.width * scaleX, reference.height * scaleY,
                )
                if (index == selectedReference) {
                    overlay.color = Color(0, 0, 255, 25)
                    overlay.fill(rectangle)
                }
                overlay.color = Color.BLUE
                overlay.stroke = BasicStroke(if (index == selectedReference) 3f else 1.5f)
                overlay.draw(rectangle)
            }
        } finally {
            overlay.dispose()
        }
    }

    fun showFields(fields: List<Felter>, selectedIndex: Int = -1) {
        this.fields = fields.toList()
        selectedField = selectedIndex
        repaint()
    }

    fun showReferences(references: List<ReferencesRectangle?>, selectedIndex: Int = -1) {
        this.references = references.toList()
        selectedReference = selectedIndex
        repaint()
    }

    fun showOrbs(show: Boolean = true) {
        showOrbKeypoints = show
        val source = image
        if (show && source != null && orbImage == null) {
            val bgrImage = toBgrImage(source)

            PointerScope().use {
                val mat = Mat(source.height, source.width, CV_8UC3)
                val pixels = (bgrImage.raster.dataBuffer as DataBufferByte).data
                mat.data().put(pixels, 0, pixels.size)
                val gray = Mat()
                cvtColor(mat, gray, COLOR_BGR2GRAY)

                val detector = KAZE.create(false, false, 0.001f, 6, 6, 3)
                val keypoints = KeyPointVector()
                if (gridOnlyKeypoints) {
                    val ink = Mat()
                    threshold(gray, ink, 0.0, 255.0, THRESH_BINARY_INV or THRESH_OTSU)
                    val grid = extractGridRules(ink)
                    val mask = Mat()
                    dilate(grid, mask, getStructuringElement(MORPH_RECT, Size(7, 7)))
                    detector.detect(grid, keypoints, mask)
                } else {
                    detector.detect(gray, keypoints)
                }

                val overlayMat = Mat()
                drawKeypoints(mat, keypoints, overlayMat, Scalar(0.0, 255.0, 0.0, 0.0), DRAW_RICH_KEYPOINTS)
                overlayMat.data().get(pixels)
            }
            orbImage = bgrImage
        }
        repaint()
    }

    fun showLines(show: Boolean = true) {
        showHoughLines = show
        val source = image
        if (show && source != null && lineOverlay == null) {
            val bgrImage = toBgrImage(source)
            val overlay = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
            PointerScope().use {
                val mat = Mat(source.height, source.width, CV_8UC3)
                val pixels = (bgrImage.raster.dataBuffer as DataBufferByte).data
                mat.data().put(pixels, 0, pixels.size)
                val gray = Mat()
                cvtColor(mat, gray, COLOR_BGR2GRAY)
                val canny = Mat()
                Canny(gray, canny, 125.0, 350.0, 3, false)
                val lines = Vec4iVector()
                val minimumLineLength = maxOf(80.0, minOf(source.width, source.height) * 0.1)
                val maximumLineGap = 10.0
                HoughLinesP(canny, lines, 1.0, PI / 720, 80, minimumLineLength, maximumLineGap)
                val inkMask = Mat()
                threshold(gray, inkMask, 0.0, 255.0, THRESH_BINARY_INV or THRESH_OTSU)
                val ink = ByteArray(source.width * source.height)
                inkMask.data().get(ink)

                val graphics = overlay.createGraphics()
                try {
                    graphics.color = Color.RED
                    for (i in 0 until lines.size()) {
                        val detectedLine = lines.get(i)
                        graphics.drawLine(
                            detectedLine.get(0), detectedLine.get(1),
                            detectedLine.get(2), detectedLine.get(3)
                        )
                    }
                } finally {
                    graphics.dispose()
                }
            }
            lineOverlay = overlay
        }
        repaint()
    }

    fun showGrids(mode: GridMode? = GridMode.EXTERNAL) {
        gridMode = mode
        val source = image
        if (mode != null && source != null && mode !in gridOverlays) {
            val overlay = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
            PointerScope().use {
                val bgrImage = toBgrImage(source)
                val pixels = (bgrImage.raster.dataBuffer as DataBufferByte).data
                val mat = Mat(source.height, source.width, CV_8UC3)
                mat.data().put(pixels, 0, pixels.size)
                val gray = Mat()
                cvtColor(mat, gray, COLOR_BGR2GRAY)
                val ink = Mat()
                threshold(gray, ink, 0.0, 255.0, THRESH_BINARY_INV or THRESH_OTSU)

                val minimumRuleLength = maxOf(25, minOf(source.width, source.height) / 40)
                val grid = extractGridRules(ink)
                val contours = MatVector()
                val hierarchy = Mat()
                findContours(grid, contours, hierarchy, mode.retrievalMode, CHAIN_APPROX_SIMPLE)

                val graphics = overlay.createGraphics()
                try {
                    graphics.color = Color.BLUE
                    graphics.stroke = BasicStroke(2f)
                    for (i in 0 until contours.size()) {
                        val contour = contours.get(i)
                        val bounds = boundingRect(contour)
                        if (bounds.width() < minimumRuleLength || bounds.height() < minimumRuleLength ||
                            contourArea(contour) < minimumRuleLength.toDouble() * minimumRuleLength) continue
                        val polygon = Polygon()
                        contour.createIndexer<IntIndexer>().use { points ->
                            for (point in 0 until contour.rows()) {
                                polygon.addPoint(points.get(point.toLong(), 0, 0), points.get(point.toLong(), 0, 1))
                            }
                        }
                        graphics.drawPolygon(polygon)
                    }
                } finally {
                    graphics.dispose()
                }
            }
            gridOverlays[mode] = overlay
        }
        repaint()
    }

    private fun extractGridRules(ink: Mat): Mat {
        val minimumRuleLength = maxOf(25, minOf(ink.cols(), ink.rows()) / 40)
        val horizontal = Mat()
        val vertical = Mat()
        morphologyEx(ink, horizontal, MORPH_OPEN,
            getStructuringElement(MORPH_RECT, Size(minimumRuleLength, 1)))
        morphologyEx(ink, vertical, MORPH_OPEN,
            getStructuringElement(MORPH_RECT, Size(1, minimumRuleLength)))
        return Mat().also { bitwise_or(horizontal, vertical, it) }
    }

    private fun toBgrImage(source: BufferedImage): BufferedImage {
        val bgrImage = BufferedImage(source.width, source.height, BufferedImage.TYPE_3BYTE_BGR)
        val graphics = bgrImage.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, source.width, source.height)
            graphics.drawImage(source, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return bgrImage
    }
}
