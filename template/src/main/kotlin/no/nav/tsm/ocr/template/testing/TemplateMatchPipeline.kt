package no.nav.tsm.ocr.template.testing

import java.awt.Color
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/*
 * Matching is lib's TemplateMatcher (what production runs); this file only draws its result for the bucket test view.
 */

/** Previews are at most this wide: half the width of a 400-DPI A4 page, so 1 preview pixel ≈ 2 page pixels. */
internal const val PREVIEW_WIDTH = 1600

internal fun previewScale(width: Int) = minOf(1.0, PREVIEW_WIDTH.toDouble() / width)

/**
 * Template images drawn at preview size, each read once however many files match it. Safe to use from several threads.
 * [of] gives the preview and its scale (preview pixels per template pixel), and throws when the image cannot be read.
 */
internal class TemplatePreviews {
    private val previews = ConcurrentHashMap<String, Lazy<Pair<BufferedImage, Double>>>()

    fun of(imagePath: String): Pair<BufferedImage, Double> = previews.computeIfAbsent(imagePath) {
        lazy {
            val image = requireNotNull(ImageIO.read(File(imagePath))) { "Unsupported template image format: $imagePath" }
            val scale = previewScale(image.width)
            resized(image, scale) to scale
        }
    }.value
}

/**
 * The aligned page in black and grey with the template's ink ([templatePreview], already at preview size) painted
 * red on top: where they coincide the lines are dark red, and doubled red/black lines show misalignment.
 */
internal fun alignmentPreview(templatePreview: BufferedImage, aligned: BufferedImage, scale: Double): BufferedImage {
    val page = resized(aligned, scale)
    for (y in 0 until minOf(page.height, templatePreview.height)) for (x in 0 until minOf(page.width, templatePreview.width)) {
        val ink = templatePreview.getRGB(x, y).let { ((it shr 16 and 255) * 299 + (it shr 8 and 255) * 587 + (it and 255) * 114) / 1000 }
        if (ink >= 160) continue
        // Darker template ink is painted more strongly.
        val alpha = 0.75 * (160 - ink) / 160.0
        val pixel = page.getRGB(x, y)
        fun mix(channel: Int, target: Int) = (channel * (1 - alpha) + target * alpha).roundToInt()
        page.setRGB(x, y, (mix(pixel shr 16 and 255, 230) shl 16) or (mix(pixel shr 8 and 255, 20) shl 8) or mix(pixel and 255, 20))
    }
    return page
}

internal fun resized(image: BufferedImage, scale: Double): BufferedImage {
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().let { g ->
            try {
                g.color = Color.WHITE
                g.fillRect(0, 0, width, height)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(image, 0, 0, width, height, null)
            } finally { g.dispose() }
        }
    }
}

internal fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

/** [fields] in preview pixels. */
internal fun previewBoxes(fields: List<Rectangle>, scale: Double): List<Rectangle> = fields.map {
    Rectangle((it.x * scale).roundToInt(), (it.y * scale).roundToInt(), (it.width * scale).roundToInt(), (it.height * scale).roundToInt())
}

/**
 * Default number of files matched at once: half the cores, one per 8 GB of memory, at most 4. SIFT already spreads
 * one page over several cores, and each 400-DPI page needs a few hundred MB of heap and native memory.
 */
internal val DEFAULT_PARALLEL: Int = run {
    val memoryGb = (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.totalMemorySize?.div(1L shl 30) ?: 8
    minOf(Runtime.getRuntime().availableProcessors() / 2, (memoryGb / 8).toInt(), 4).coerceAtLeast(1)
}
