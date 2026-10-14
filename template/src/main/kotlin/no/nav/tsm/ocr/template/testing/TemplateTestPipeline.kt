package no.nav.tsm.ocr.template.testing

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.bytedeco.javacpp.PointerScope
import org.bytedeco.javacpp.indexer.DoubleIndexer
import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.javacpp.indexer.UByteIndexer
import org.bytedeco.opencv.global.opencv_calib3d.RANSAC
import org.bytedeco.opencv.global.opencv_calib3d.estimateAffine2D
import org.bytedeco.opencv.global.opencv_calib3d.findHomography
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_core.transform as transformPoints
import org.bytedeco.opencv.global.opencv_imgproc.*
import org.bytedeco.opencv.global.opencv_xfeatures2d.matchGMS
import org.bytedeco.opencv.opencv_core.*
import org.bytedeco.opencv.opencv_features2d.BFMatcher
import org.bytedeco.opencv.opencv_features2d.DescriptorMatcher
import org.bytedeco.opencv.opencv_features2d.FlannBasedMatcher
import org.bytedeco.opencv.opencv_flann.KDTreeIndexParams
import org.bytedeco.opencv.opencv_flann.SearchParams
import org.example.no.nav.tsm.ocr.template.template.*
import org.w3c.dom.Node
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*

internal enum class TestStage { SCALED, DETECTED, MATCHED, ALIGNED }

internal enum class AlignmentModel(private val label: String) {
    HOMOGRAPHY("Homography + RANSAC (perspective)"), AFFINE("Affine + RANSAC (scan)");
    override fun toString() = label
}

internal data class TestInput(val file: File, val page: Int, val raw: BufferedImage, val dpi: Double?) {
    val isPdf: Boolean get() = file.extension.equals("pdf", true)

    fun at400Dpi(sourceDpi: Double): BufferedImage = if (isPdf) renderPdf(file, page, 400f) else scaleTo400(raw, sourceDpi)

    companion object {
        fun load(file: File, page: Int = 1): TestInput {
            if (file.extension.equals("pdf", true)) return TestInput(file, page, renderPdf(file, page, 72f), 72.0)
            ImageIO.createImageInputStream(file).use { input ->
                requireNotNull(input) { "Could not open image" }
                val readers = ImageIO.getImageReaders(input)
                require(readers.hasNext()) { "Unsupported image format" }
                val reader = readers.next()
                try {
                    reader.input = input
                    val image = reader.read(0)
                    val dpi = runCatching {
                        val metadata = reader.getImageMetadata(0)
                        if (!metadata.isStandardMetadataFormatSupported) null else {
                            fun pixelSize(node: Node): Double? {
                                if (node.nodeName == "HorizontalPixelSize") return node.attributes.getNamedItem("value").nodeValue.toDoubleOrNull()
                                for (i in 0 until node.childNodes.length) pixelSize(node.childNodes.item(i))?.let { return it }
                                return null
                            }
                            pixelSize(metadata.getAsTree("javax_imageio_1.0"))?.takeIf { it > 0 }?.let { 25.4 / it }
                                ?.takeIf { it.isFinite() && it in 10.0..2400.0 }
                        }
                    }.getOrNull()
                    return TestInput(file, page, image, dpi)
                } finally {
                    reader.dispose()
                }
            }
        }

        private fun renderPdf(file: File, page: Int, dpi: Float): BufferedImage = Loader.loadPDF(file).use { document ->
            require(page in 1..document.numberOfPages) { "Invalid PDF page: $page" }
            PDFRenderer(document).renderImageWithDPI(page - 1, dpi, ImageType.RGB)
        }
    }
}

internal fun scaleTo400(image: BufferedImage, sourceDpi: Double): BufferedImage {
    require(sourceDpi.isFinite() && sourceDpi in 10.0..2400.0) { "Enter the source image DPI (10–2400)." }
    val width = (image.width * 400.0 / sourceDpi).roundToInt().coerceAtLeast(1)
    val height = (image.height * 400.0 / sourceDpi).roundToInt().coerceAtLeast(1)
    require(width.toLong() * height <= 100_000_000L) { "Result exceeds 100 megapixels; check the source DPI." }
    return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().let { g ->
            try {
                g.color = Color.WHITE
                g.fillRect(0, 0, width, height)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.drawImage(image, 0, 0, width, height, null)
            } finally { g.dispose() }
        }
    }
}

/** [norm] is the descriptor distance (Hamming for binary, L2 for float descriptors). */
internal data class FeatureSet(
    val points: List<Point2D.Double>,
    val descriptors: Mat,
    val norm: Int = NORM_HAMMING,
    val millis: Long = 0,
) : AutoCloseable {
    override fun close() = descriptors.close()
}

/**
 * [incoming] belongs to its [PreparedPage] and is never closed here; [ownsReference] is false when the template's
 * features are shared (see [TemplateCache]).
 */
internal data class FeaturePair(val reference: FeatureSet, val incoming: FeatureSet, val ownsReference: Boolean = true) : AutoCloseable {
    override fun close() { if (ownsReference) reference.close() }
}

/** Matching found too few consistent matches: only [inliers] of [matches] agreed on one transform. */
internal class AlignmentRejected(message: String, val inliers: Int, val matches: Int) : IllegalStateException(message)

/**
 * A whole page's features with [settings]: a template page's (once per run, see [TemplateCache]) or a document page's
 * (once per file, see [DocumentPages]). The caller owns the result.
 */
internal fun detectPageFeatures(reference: BufferedImage, settings: FeatureSettings): FeatureSet {
    settings.resolvedDescriptor
    return detectFeatures(reference, settings)
}

internal data class FeatureMatch(val reference: Point2D.Double, val incoming: Point2D.Double, val inlier: Boolean)
internal data class MatchInspection(
    val matches: List<FeatureMatch>,
    val transform: DoubleArray,
    val model: AlignmentModel = AlignmentModel.AFFINE,
    val millis: Long = 0,
) {
    val inliers: Int get() = matches.count { it.inlier }
    val description: String get() {
        if (model == AlignmentModel.HOMOGRAPHY) {
            return "$inliers / ${matches.size} RANSAC inliers. Incoming → template: homography (perspective). " +
                "H = " + transform.toList().chunked(3).joinToString("; ") { row -> row.joinToString(", ") { "%.5g".format(it) } } +
                ". Inlier coverage and projected page geometry validated."
        }
        val a = transform[0]; val b = transform[1]; val c = transform[3]; val d = transform[4]
        val sx = hypot(a, c); val sy = hypot(b, d)
        val skew = Math.toDegrees(asin(((a * b + c * d) / (sx * sy)).coerceIn(-1.0, 1.0)))
        return "$inliers / ${matches.size} RANSAC inliers. Incoming → template: " +
            "rotation %.2f°, scale %.4f × %.4f, skew %.2f°, translation %.1f, %.1f px."
                .format(Math.toDegrees(atan2(c, a)), sx, sy, skew, transform[2], transform[5])
    }
}

/** Rotation, scale and shift of a document → template transform (6 affine or 9 homography values). */
internal fun summarize(transform: DoubleArray): String {
    val a = transform[0]; val b = transform[1]; val c = transform[3]; val d = transform[4]
    return "rotation %.2f°, scale %.4f × %.4f, shift %.1f, %.1f px".format(
        Math.toDegrees(atan2(c, a)), hypot(a, c), hypot(b, d), transform[2], transform[5]) +
        if (transform.size == 9) ", perspective %.2g, %.2g".format(transform[6], transform[7]) else ""
}

/** Maps points with a 3 × 3 homography or 2 × 3 affine transform (OpenCV's perspectiveTransform / transform). */
internal fun DoubleArray.project(points: List<Point2D.Double>): List<Point2D.Double> {
    if (points.isEmpty()) return emptyList()
    return pointMat(points).use { source ->
        transformMat(this).use { matrix ->
            Mat().use { result ->
                if (size == 9) perspectiveTransform(source, result, matrix) else transformPoints(source, result, matrix)
                result.createIndexer<FloatIndexer>().use { values ->
                    points.indices.map { Point2D.Double(values.get(it.toLong(), 0, 0).toDouble(), values.get(it.toLong(), 0, 1).toDouble()) }
                }
            }
        }
    }
}

/** Points as an N × 1 two-channel float Mat, the layout OpenCV's geometry functions take. The caller owns the result. */
private fun pointMat(points: List<Point2D.Double>): Mat = Mat(points.size, 1, CV_32FC2).apply {
    createIndexer<FloatIndexer>().use { values ->
        points.forEachIndexed { i, p -> values.put(i.toLong(), 0, 0, p.x.toFloat()); values.put(i.toLong(), 0, 1, p.y.toFloat()) }
    }
}

/** Positions only; GMS uses nothing else of a keypoint. The caller owns the result. */
private fun keypoints(points: List<Point2D.Double>): KeyPointVector =
    KeyPointVector(*points.map { KeyPoint(it.x.toFloat(), it.y.toFloat(), 1f) }.toTypedArray())

/** Area of the convex hull of [points] (OpenCV's convexHull and contourArea). */
private fun hullArea(points: List<Point2D.Double>): Double = pointMat(points).use { source ->
    Mat().use { hull -> convexHull(source, hull); contourArea(hull) }
}

/** Aligns one shared [PreparedPage] to one template page; closes only template features it detected itself. */
internal class TemplateTestSession(val template: Template, val reference: BufferedImage, private val page: PreparedPage) : AutoCloseable {
    var stage = TestStage.SCALED
        private set
    val scaled: BufferedImage get() = page.image
    /** Template and document features detected from the full pages. */
    private var detected: FeaturePair? = null
    /** Features currently in use: the template's and the page's. */
    val features: FeaturePair? get() = detected
    var featureSettings: FeatureSettings? = null
        private set
    var inspection: MatchInspection? = null
        private set
    var aligned: BufferedImage? = null
        private set

    /**
     * Detection can be repeated with other [settings] after matching and alignment; their results are discarded.
     * [templateFeatures], from [detectPageFeatures] with the same [settings] as the page's, is used instead of detecting
     * the template again; it is not closed by this session.
     */
    fun detect(settings: FeatureSettings = FeatureSettings(), templateFeatures: FeatureSet? = null) {
        settings.resolvedDescriptor // Reject impossible combinations before discarding earlier results.
        val first = templateFeatures ?: detectPageFeatures(reference, settings)
        discardFeatures()
        detected = FeaturePair(first, page.features, ownsReference = templateFeatures == null)
        featureSettings = settings
        stage = TestStage.DETECTED
    }

    private fun discardFeatures() {
        detected?.close(); detected = null
        inspection = null
        aligned = null
    }

    /** Matching can be repeated with another model; alignment must then be redone. */
    fun match(model: AlignmentModel = AlignmentModel.AFFINE) {
        check(stage == TestStage.DETECTED || stage == TestStage.MATCHED || stage == TestStage.ALIGNED)
        val pair = requireNotNull(detected)
        val incoming = scaled
        inspection = null
        aligned = null
        stage = TestStage.DETECTED
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        inspection = matchFeatures(pair, model, reference.width, reference.height, incoming.width, incoming.height).copy(millis = elapsed())
        stage = TestStage.MATCHED
    }

    fun align() {
        check(stage == TestStage.MATCHED)
        PointerScope().use {
            val resultInspection = requireNotNull(inspection)
            val matrix = resultInspection.matrix()
            val source = bgrMat(scaled)
            val result = Mat()
            warpForModel(resultInspection.model, source, result, matrix, Size(reference.width, reference.height), INTER_LINEAR,
                Scalar(255.0, 255.0, 255.0, 0.0))
            aligned = BufferedImage(reference.width, reference.height, BufferedImage.TYPE_3BYTE_BGR).apply {
                result.data().get((raster.dataBuffer as DataBufferByte).data)
            }
        }
        stage = TestStage.ALIGNED
    }

    fun fieldCrop(index: Int): BufferedImage {
        check(stage == TestStage.ALIGNED)
        val field = template.felter[index]
        val image = requireNotNull(aligned)
        require(field.x >= 0 && field.y >= 0 && field.bredde > 0 && field.hoyde > 0 &&
            field.x.toLong() + field.bredde <= image.width && field.y.toLong() + field.hoyde <= image.height) {
            "Field '${field.navn}' is outside the aligned page."
        }
        return image.getSubimage(field.x, field.y, field.bredde, field.hoyde)
    }

    override fun close() { discardFeatures() }
}

private fun bgrMat(source: BufferedImage): Mat {
    val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_3BYTE_BGR)
    image.createGraphics().let { g ->
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.drawImage(source, 0, 0, null)
        } finally { g.dispose() }
    }
    return Mat(image.height, image.width, CV_8UC3).apply {
        val bytes = (image.raster.dataBuffer as DataBufferByte).data
        data().put(bytes, 0, bytes.size)
    }
}

/** Detects and describes [image] with [settings], using every part of the page. */
private fun detectFeatures(image: BufferedImage, settings: FeatureSettings): FeatureSet {
    val started = System.nanoTime()
    bgrMat(image).use { source ->
        Mat().use { gray ->
            cvtColor(source, gray, COLOR_BGR2GRAY)
            val found = extractFeatures(gray, settings)
            return FeatureSet(found.points, found.descriptors, found.norm, (System.nanoTime() - started) / 1_000_000)
        }
    }
}

private fun MatchInspection.matrix(): Mat = transformMat(transform)

/** 2 × 3 (affine, 6 values) or 3 × 3 (homography, 9 values). */
private fun transformMat(transform: DoubleArray): Mat = Mat(transform.size / 3, 3, CV_64F).apply {
    createIndexer<DoubleIndexer>().use { indexer ->
        transform.forEachIndexed { i, value -> indexer.put((i / 3).toLong(), (i % 3).toLong(), value) }
    }
}

private fun warpForModel(model: AlignmentModel, source: Mat, result: Mat, transform: Mat, size: Size, flags: Int, background: Scalar) {
    if (model == AlignmentModel.HOMOGRAPHY) warpPerspective(source, result, transform, size, flags, BORDER_CONSTANT, background)
    else warpAffine(source, result, transform, size, flags, BORDER_CONSTANT, background)
}

private fun matchFeatures(features: FeaturePair, model: AlignmentModel,
                          referenceWidth: Int, referenceHeight: Int, incomingWidth: Int, incomingHeight: Int): MatchInspection {
    val reference = features.reference
    val incoming = features.incoming
    check(reference.descriptors.rows() >= 2 && incoming.descriptors.rows() >= 2) {
        "Too few features. Inspect the page or adjust the point limit."
    }
    check(reference.norm == incoming.norm) { "The pages were described with different descriptors." }
    PointerScope().use {
        // Brute force on thousands of float descriptors takes minutes; FLANN's k-d trees are approximate but fast.
        val matcher: DescriptorMatcher = if (isBinaryNorm(reference.norm)) BFMatcher(reference.norm, false)
            else FlannBasedMatcher(KDTreeIndexParams(4), SearchParams(64, 0f, true))
        val nearest = DMatchVector()
        matcher.match(reference.descriptors, incoming.descriptors, nearest)
        // GMS keeps nearest-neighbour matches whose neighbourhoods move together; withRotation also covers turned pages.
        val consistent = DMatchVector()
        matchGMS(Size(referenceWidth, referenceHeight), Size(incomingWidth, incomingHeight),
            keypoints(reference.points), keypoints(incoming.points), nearest, consistent, true, false, 6.0)
        val pairs = (0 until consistent.size()).map { i ->
            consistent.get(i).let { reference.points[it.queryIdx()] to incoming.points[it.trainIdx()] }
        }
        if (pairs.size < 8) throw AlignmentRejected("Only ${pairs.size} consistent matches; need at least 8 to estimate alignment.", 0, pairs.size)
        val from = pointMat(pairs.map { it.second })
        val to = pointMat(pairs.map { it.first })
        val inliers = Mat()
        val transform = if (model == AlignmentModel.HOMOGRAPHY) findHomography(from, to, RANSAC, 3.0, inliers, 3000, 0.99)
            else estimateAffine2D(from, to, inliers, RANSAC, 3.0, 3000, 0.99, 10)
        check(!transform.empty()) { "Could not estimate $model." }
        val count = countNonZero(inliers)
        if (count < (if (model == AlignmentModel.HOMOGRAPHY) 8 else 6) || count < pairs.size * 0.4) {
            throw AlignmentRejected("Alignment rejected: only $count/${pairs.size} consistent matches.", count, pairs.size)
        }
        val values = transform.createIndexer<DoubleIndexer>().use { indexer ->
            DoubleArray(if (model == AlignmentModel.HOMOGRAPHY) 9 else 6) { indexer.get((it / 3).toLong(), (it % 3).toLong()) }
        }
        val matches = inliers.createIndexer<UByteIndexer>().use { indexer ->
            pairs.mapIndexed { i, (ref, doc) -> FeatureMatch(ref, doc, indexer.get(i.toLong(), 0) != 0) }
        }
        if (model == AlignmentModel.HOMOGRAPHY) {
            validateHomography(values, matches, referenceWidth, referenceHeight, incomingWidth, incomingHeight)
        } else {
            check(values.all { it.isFinite() } && values[0] * values[4] - values[1] * values[3] > 0.0001) { "Degenerate or mirrored alignment." }
        }
        return MatchInspection(matches, values, model = model)
    }
}

/** Reject locally convincing matches that cannot safely describe the full page. */
internal fun validateHomography(h: DoubleArray, matches: List<FeatureMatch>, referenceWidth: Int, referenceHeight: Int,
                                incomingWidth: Int, incomingHeight: Int) {
    check(h.size == 9 && h.all { it.isFinite() }) { "Invalid homography coefficients." }
    fun cross(a: Point2D.Double, b: Point2D.Double, c: Point2D.Double) =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    fun coverage(points: List<Point2D.Double>, width: Int, height: Int) {
        check(points.size >= 8) { "Homography needs at least 8 inliers." }
        check(hullArea(points) >= width.toDouble() * height * 0.03 &&
            points.maxOf { it.x } - points.minOf { it.x } >= width * 0.2 &&
            points.maxOf { it.y } - points.minOf { it.y } >= height * 0.2) {
            "Homography rejected: inliers are too clustered or collinear. Need matches spread across the page."
        }
    }
    val inliers = matches.filter { it.inlier }
    coverage(inliers.map { it.reference }, referenceWidth, referenceHeight)
    coverage(inliers.map { it.incoming }, incomingWidth, incomingHeight)
    val corners = listOf(Point2D.Double(0.0, 0.0), Point2D.Double(incomingWidth.toDouble(), 0.0),
        Point2D.Double(incomingWidth.toDouble(), incomingHeight.toDouble()), Point2D.Double(0.0, incomingHeight.toDouble()))
    val denominators = corners.map { h[6] * it.x + h[7] * it.y + h[8] }
    val magnitude = denominators.maxOf { abs(it) }
    check(magnitude > 0 && denominators.all { abs(it) > magnitude * 1e-6 && it * denominators[0] > 0 }) {
        "Homography rejected: perspective horizon crosses the page."
    }
    val projected = h.project(corners)
    check(projected.all { it.x.isFinite() && it.y.isFinite() } &&
        projected.indices.all { cross(projected[it], projected[(it + 1) % 4], projected[(it + 2) % 4]) > 0 }) {
        "Homography rejected: degenerate, folded or mirrored page."
    }
    val areaRatio = pointMat(projected).use { contourArea(it) } / (referenceWidth.toDouble() * referenceHeight)
    check(areaRatio in 0.1..10.0 && projected.all {
        it.x in -2.0 * referenceWidth..3.0 * referenceWidth && it.y in -2.0 * referenceHeight..3.0 * referenceHeight
    }) { "Homography rejected: implausible projected page size or position." }
}
