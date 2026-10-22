package no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.template.Felter
import no.nav.tsm.sykmelding.ocr.ReferencesRectangle
import no.nav.tsm.sykmelding.utils.FieldRegion
import no.nav.tsm.sykmelding.utils.FieldTransform
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.JPanel

class ImagePanel : JPanel() {

    private var imgRatio: Float = 1.0f
    private var fields: List<FieldRegion> = emptyList()
    private var selectedField = -1
    private var references: List<ReferencesRectangle?> = emptyList()
    private var selectedReference = -1
    var image: BufferedImage? = null

    fun load(bufferedImage: BufferedImage) {
        image = bufferedImage
        imgRatio = bufferedImage.width / bufferedImage.height.toFloat()
        this.fields = emptyList()
        selectedField = -1
        references = emptyList()
        selectedReference = -1
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
        val source = image ?: return
        g.drawImage(source, 0, 0, renderWidth, renderHight, null)
        val overlay = g.create() as Graphics2D
        try {
            overlay.clipRect(0, 0, renderWidth, renderHight)
            overlay.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val scaleX = renderWidth.toDouble() / source.width
            val scaleY = renderHight.toDouble() / source.height
            fields.forEachIndexed { index, field ->
                val rectangle = Path2D.Double().apply {
                    field.corners.forEachIndexed { corner, point ->
                        if (corner == 0) moveTo(point.x * scaleX, point.y * scaleY)
                        else lineTo(point.x * scaleX, point.y * scaleY)
                    }
                    closePath()
                }
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
        showFieldRegions(fields.map { FieldTransform(1.0, 0.0, 0.0, 0.0).region(it) }, selectedIndex)
    }

    internal fun showFieldRegions(fields: List<FieldRegion>, selectedIndex: Int = -1) {
        this.fields = fields.toList()
        selectedField = selectedIndex
        repaint()
    }

    fun showReferences(references: List<ReferencesRectangle?>, selectedIndex: Int = -1) {
        this.references = references.toList()
        selectedReference = selectedIndex
        repaint()
    }
}
