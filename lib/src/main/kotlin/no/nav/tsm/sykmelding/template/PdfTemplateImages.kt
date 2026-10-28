package org.example.no.nav.tsm.ocr.template.template

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import kotlin.math.roundToInt

object PdfTemplateImages {
    const val DPI = 400

    fun pageCount(file: File): Int = Loader.loadPDF(file).use { document ->
        document.numberOfPages.also { require(it > 0) { "PDF-filen har ingen sider." } }
    }

    /** Page numbers shown in the editor are one-based. PDFBox also respects crop boxes and rotation. */
    fun render(file: File, pageNumber: Int): BufferedImage = Loader.loadPDF(file).use { render(it, pageNumber) }

    /**
     * As [render], for a document that is already open, so several pages can be rendered without reading it again.
     * At [dpi]; template images are always [DPI].
     */
    fun render(document: PDDocument, pageNumber: Int, dpi: Int = DPI): BufferedImage {
        require(pageNumber in 1..document.numberOfPages) { "Ugyldig sidetall: $pageNumber" }
        require(dpi > 0) { "Ugyldig DPI: $dpi" }
        return PDFRenderer(document).renderImageWithDPI(pageNumber - 1, dpi.toFloat(), ImageType.RGB)
    }

    fun writePng(image: BufferedImage, file: File) {
        val destination = file.absoluteFile.toPath()
        val temporary = Files.createTempFile(destination.parent, ".pdf-template-", ".png")
        try {
            val writer = ImageIO.getImageWritersByFormatName("png").next()
            try {
                ImageIO.createImageOutputStream(temporary.toFile()).use { output ->
                    writer.output = output
                    val parameters = writer.defaultWriteParam
                    val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), parameters)
                    val format = "javax_imageio_png_1.0"
                    val root = IIOMetadataNode(format)
                    root.appendChild(IIOMetadataNode("pHYs").apply {
                        val pixelsPerMeter = (DPI / 0.0254).roundToInt().toString()
                        setAttribute("pixelsPerUnitXAxis", pixelsPerMeter)
                        setAttribute("pixelsPerUnitYAxis", pixelsPerMeter)
                        setAttribute("unitSpecifier", "meter")
                    })
                    metadata.mergeTree(format, root)
                    writer.write(null, IIOImage(image, null, metadata), parameters)
                }
            } finally {
                writer.dispose()
            }
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
