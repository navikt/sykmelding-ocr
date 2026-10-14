package no.nav.tsm.ocr.template.testing

import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.global.opencv_core.CV_32F
import org.bytedeco.opencv.global.opencv_core.NORM_HAMMING
import org.bytedeco.opencv.global.opencv_core.NORM_L2
import org.bytedeco.opencv.opencv_core.KeyPointVector
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_features2d.AgastFeatureDetector
import org.bytedeco.opencv.opencv_features2d.Feature2D
import org.bytedeco.opencv.opencv_features2d.FastFeatureDetector
import org.bytedeco.opencv.opencv_features2d.GFTTDetector
import org.bytedeco.opencv.opencv_features2d.KeyPointsFilter
import org.bytedeco.opencv.opencv_xfeatures2d.BEBLID
import org.bytedeco.opencv.opencv_xfeatures2d.BriefDescriptorExtractor
import org.bytedeco.opencv.opencv_xfeatures2d.FREAK
import org.bytedeco.opencv.opencv_xfeatures2d.LATCH
import org.bytedeco.opencv.opencv_xfeatures2d.StarDetector
import org.bytedeco.opencv.opencv_xfeatures2d.TEBLID
import java.awt.geom.Point2D
import kotlin.math.sqrt

/**
 * Keypoint detectors for step 3. [ownDescriptor] is the descriptor computed by the same algorithm, if any.
 * [patchSized]: point sizes are patch diameters (ORB-like), which sets the BEBLID/TEBLID sampling scale.
 */
internal enum class FeatureDetectorKind(private val label: String, val ownDescriptor: FeatureDescriptorKind?, val patchSized: Boolean) {
    AKAZE("AKAZE", FeatureDescriptorKind.AKAZE, false),
    KAZE("KAZE", FeatureDescriptorKind.KAZE, false),
    SIFT("SIFT", FeatureDescriptorKind.SIFT, false),
    ORB("ORB", FeatureDescriptorKind.ORB, true),
    BRISK("BRISK", FeatureDescriptorKind.BRISK, false),
    FAST("FAST", null, true),
    AGAST("AGAST", null, true),
    GFTT("GFTT (Shi-Tomasi)", null, true),
    MSER("MSER", null, true),
    STAR("STAR (CenSurE)", null, true);

    override fun toString() = label

    /** Detectors that report no meaningful point size; their points get [FIXED_POINT_SIZE]. */
    val sizeless: Boolean get() = this == FAST || this == AGAST || this == GFTT

    fun create(maxPoints: Int): Feature2D = when (this) {
        AKAZE -> org.bytedeco.opencv.opencv_features2d.AKAZE.create()
        KAZE -> org.bytedeco.opencv.opencv_features2d.KAZE.create()
        SIFT -> org.bytedeco.opencv.opencv_features2d.SIFT.create()
        // ORB keeps only 500 points by default; a page needs far more.
        ORB -> org.bytedeco.opencv.opencv_features2d.ORB.create(if (maxPoints > 0) maxPoints else 200_000, 1.2f, 8, 31, 0, 2,
            org.bytedeco.opencv.opencv_features2d.ORB.HARRIS_SCORE, 31, 20)
        BRISK -> org.bytedeco.opencv.opencv_features2d.BRISK.create()
        FAST -> FastFeatureDetector.create()
        AGAST -> AgastFeatureDetector.create()
        // 0 corners = no limit; GFTT keeps only 1000 by default.
        GFTT -> GFTTDetector.create(maxOf(0, maxPoints), 0.01, 3.0, 3, false, 0.04)
        MSER -> org.bytedeco.opencv.opencv_features2d.MSER.create()
        STAR -> StarDetector.create()
    }
}

/** Descriptors for step 3; [SAME_AS_DETECTOR] uses the detector's own. */
internal enum class FeatureDescriptorKind(private val label: String) {
    SAME_AS_DETECTOR("Same as detector"),
    SIFT("SIFT"),
    ROOT_SIFT("RootSIFT"),
    ORB("ORB"),
    BRISK("BRISK"),
    BEBLID("BEBLID"),
    TEBLID("TEBLID"),
    FREAK("FREAK"),
    BRIEF("BRIEF"),
    LATCH("LATCH"),
    AKAZE("AKAZE"),
    KAZE("KAZE");

    override fun toString() = label

    /** The algorithm whose detector produced points this descriptor can describe natively. */
    val family: FeatureDescriptorKind get() = if (this == ROOT_SIFT) SIFT else this
}

/** Fixed patch diameter for detectors without point sizes, as for ORB at its finest level. */
internal const val FIXED_POINT_SIZE = 31f

/**
 * Step 3 settings. [maxPoints] keeps the strongest points per page (0 = all).
 */
internal data class FeatureSettings(
    val detector: FeatureDetectorKind = FeatureDetectorKind.AKAZE,
    val descriptor: FeatureDescriptorKind = FeatureDescriptorKind.SAME_AS_DETECTOR,
    val maxPoints: Int = 20_000,
) {
    init { require(maxPoints >= 0) { "Max points must be 0 (no limit) or more." } }

    /** The descriptor actually used; throws [IllegalArgumentException] for combinations that cannot work. */
    val resolvedDescriptor: FeatureDescriptorKind get() {
        val resolved = if (descriptor == FeatureDescriptorKind.SAME_AS_DETECTOR) {
            requireNotNull(detector.ownDescriptor) { "$detector has no descriptor of its own; choose a descriptor." }
        } else descriptor
        // AKAZE and KAZE descriptors read scale information that only their own detector stores in each point.
        require(resolved != FeatureDescriptorKind.AKAZE || detector == FeatureDetectorKind.AKAZE) { "The AKAZE descriptor only works with AKAZE points." }
        require(resolved != FeatureDescriptorKind.KAZE || detector == FeatureDetectorKind.KAZE) { "The KAZE descriptor only works with KAZE points." }
        return resolved
    }

    /** Whether the descriptor uses the detector's own point data (scale level, orientation, size). */
    val native: Boolean get() = detector.ownDescriptor == resolvedDescriptor.family

    val description: String get() {
        val descriptorName = resolvedDescriptor.toString()
        return (if (descriptorName == detector.toString()) "$detector" else "$detector → $descriptorName") +
            ", " + (if (maxPoints > 0) "max %,d points".format(maxPoints) else "all points")
    }

    fun createDescriptor(): Feature2D = when (resolvedDescriptor) {
        FeatureDescriptorKind.SIFT, FeatureDescriptorKind.ROOT_SIFT -> org.bytedeco.opencv.opencv_features2d.SIFT.create()
        FeatureDescriptorKind.ORB -> org.bytedeco.opencv.opencv_features2d.ORB.create()
        FeatureDescriptorKind.BRISK -> org.bytedeco.opencv.opencv_features2d.BRISK.create()
        FeatureDescriptorKind.BEBLID -> BEBLID.create(blidScale(), BEBLID.SIZE_512_BITS)
        FeatureDescriptorKind.TEBLID -> TEBLID.create(blidScale(), TEBLID.SIZE_256_BITS)
        FeatureDescriptorKind.FREAK -> FREAK.create()
        FeatureDescriptorKind.BRIEF -> BriefDescriptorExtractor.create(32, false)
        FeatureDescriptorKind.LATCH -> LATCH.create()
        FeatureDescriptorKind.AKAZE -> org.bytedeco.opencv.opencv_features2d.AKAZE.create()
        FeatureDescriptorKind.KAZE -> org.bytedeco.opencv.opencv_features2d.KAZE.create()
        FeatureDescriptorKind.SAME_AS_DETECTOR -> error("Unresolved descriptor")
    }

    /** Sampling-window scales recommended by OpenCV for BEBLID/TEBLID, per kind of point size. */
    private fun blidScale(): Float = when (detector) {
        FeatureDetectorKind.SIFT -> 6.75f
        FeatureDetectorKind.KAZE -> 6.25f
        FeatureDetectorKind.AKAZE, FeatureDetectorKind.BRISK -> 5.0f
        else -> 1.0f // Point sizes are patch diameters (ORB, MSER, STAR, and the fixed size).
    }
}

internal class ExtractedFeatures(val points: List<Point2D.Double>, val descriptors: Mat, val norm: Int)

/**
 * Finds points across the full page, keeps the strongest, then describes them. Describing may drop points, e.g. near
 * the page edge, so the returned points are those that were described. The caller owns the descriptors.
 */
internal fun extractFeatures(gray: Mat, settings: FeatureSettings): ExtractedFeatures {
    val descriptorKind = settings.resolvedDescriptor
    val descriptors = Mat()
    try {
        KeyPointVector().use { points ->
            settings.detector.create(settings.maxPoints).use { detector ->
                detector.detect(gray, points)
            }
            if (settings.maxPoints > 0 && points.size() > settings.maxPoints) {
                // retainBest also keeps points tied with the last one (common for FAST's integer scores); the best come first.
                KeyPointsFilter.retainBest(points, settings.maxPoints)
                points.resize(settings.maxPoints.toLong())
            }
            if (!settings.native) {
                // Detector-specific fields (pyramid level, packed SIFT octave, AKAZE class) would be misread.
                for (i in 0 until points.size()) {
                    val point = points.get(i)
                    point.octave(0).class_id(-1)
                    if (settings.detector.sizeless) point.size(FIXED_POINT_SIZE)
                }
            }
            val norm = settings.createDescriptor().use { describer ->
                describer.compute(gray, points, descriptors)
                describer.defaultNorm()
            }
            if (descriptorKind == FeatureDescriptorKind.ROOT_SIFT) rootSift(descriptors)
            val located = (0 until points.size()).map { i ->
                val point = points.get(i).pt()
                Point2D.Double(point.x().toDouble(), point.y().toDouble())
            }
            check(descriptors.empty() || descriptors.rows() == located.size) { "Descriptor count does not match the points." }
            return ExtractedFeatures(located, descriptors, if (descriptorKind == FeatureDescriptorKind.ROOT_SIFT) NORM_L2 else norm)
        }
    } catch (error: Throwable) {
        descriptors.close()
        throw error
    }
}

/** RootSIFT (Arandjelović & Zisserman 2012): L1-normalise, then square-root; L2 then compares like Hellinger distance. */
private fun rootSift(descriptors: Mat) {
    if (descriptors.empty()) return
    check(descriptors.type() == CV_32F) { "RootSIFT needs float SIFT descriptors." }
    descriptors.createIndexer<FloatIndexer>().use { values ->
        val row = FloatArray(descriptors.cols())
        for (r in 0 until descriptors.rows().toLong()) {
            values.get(r, 0L, row)
            val sum = row.sumOf { kotlin.math.abs(it).toDouble() }.toFloat() + 1e-7f
            for (c in row.indices) row[c] = sqrt(kotlin.math.abs(row[c]) / sum)
            values.put(r, 0L, row, 0, row.size)
        }
    }
}

/** Binary descriptors are compared by differing bits, float descriptors by Euclidean distance. */
internal fun isBinaryNorm(norm: Int) = norm == NORM_HAMMING || norm == org.bytedeco.opencv.global.opencv_core.NORM_HAMMING2
