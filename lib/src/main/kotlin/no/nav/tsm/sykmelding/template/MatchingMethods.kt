package no.nav.tsm.sykmelding.template

import no.nav.tsm.sykmelding.utils.FeatureDetectorType
import no.nav.tsm.sykmelding.utils.ORB_FEATURES
import org.bytedeco.opencv.global.opencv_calib3d
import org.bytedeco.opencv.global.opencv_core.NORM_HAMMING
import org.bytedeco.opencv.global.opencv_core.NORM_L2
import org.bytedeco.opencv.opencv_features2d.Feature2D

/**
 * How [TemplateMatcher] matches a page to a template page: the keypoints and descriptors it finds on both ([detector]),
 * how it keeps the good matches between them ([filter]), and how it fits the homography that aligns them ([homography]).
 * [PRODUCTION] is what production uses; the others are there to compare them with it.
 */
data class MatchingMethods(
    val detector: KeypointDetector = KeypointDetector.SIFT,
    val filter: MatchFilter = MatchFilter.RATIO_TEST,
    val homography: HomographyMethod = HomographyMethod.MAGSAC,
) {
    /** E.g. "SIFT / Ratio test / MAGSAC++". */
    val text: String get() = "${detector.label} / ${filter.label} / ${homography.label}"

    companion object {
        val PRODUCTION = MatchingMethods()
    }
}

/** The keypoint detector and descriptor features are found with, on the pages and on the templates alike. */
enum class KeypointDetector(val label: String, val description: String, private val type: FeatureDetectorType) {
    SIFT("SIFT", "Scale-invariant keypoints with float descriptors, compared by L2 distance. Production's.", FeatureDetectorType.SIFT),
    ORB("ORB", "FAST corners with binary descriptors, compared by Hamming distance; the $ORB_FEATURES strongest. Fast.", FeatureDetectorType.ORB),
    AKAZE("AKAZE", "Keypoints in a nonlinear scale space with binary descriptors, compared by Hamming distance.", FeatureDetectorType.AKAZE),
    KAZE("KAZE", "Keypoints in a nonlinear scale space with float descriptors, compared by L2 distance. Slow.", FeatureDetectorType.KAZE),
    BRISK("BRISK", "FAST corners at several scales with binary descriptors, compared by Hamming distance.", FeatureDetectorType.BRISK),
    ;

    /** How descriptors are compared: binary ones by Hamming distance, float ones by L2. */
    internal val norm: Int get() = when (this) {
        SIFT, KAZE -> NORM_L2
        ORB, AKAZE, BRISK -> NORM_HAMMING
    }

    /** A new detector, as the template editor's [FeatureDetectorType] creates it. */
    internal fun create(): Feature2D = type.create()
}

/** How the matches between a template page's descriptors and a page's are narrowed down to the good ones. */
enum class MatchFilter(val label: String, val description: String) {
    RATIO_TEST("Ratio test", "Lowe's ratio test: a template keypoint's nearest page keypoint must be clearly nearer than " +
        "the second nearest. Drops the ambiguous matches that repeated letters and boxes give. Production's."),
    CROSS_CHECK("Cross-check", "Only the matches that are each other's nearest both ways."),
    GMS("GMS", "Grid-based motion statistics: each nearest match is kept when the matches around it move the same way. " +
        "Made for many keypoints, such as ORB's."),
}

/** How the homography is fitted to the matches, telling the matches that agree on it (inliers) from those that do not. */
enum class HomographyMethod(val label: String, val description: String, internal val flag: Int) {
    MAGSAC("MAGSAC++", "USAC with MAGSAC++ scoring: no hard inlier threshold. Production's.", opencv_calib3d.USAC_MAGSAC),
    RANSAC("RANSAC", "Classic RANSAC.", opencv_calib3d.RANSAC),
    LMEDS("LMedS", "Least median of squares: needs more than half of the matches to be inliers.", opencv_calib3d.LMEDS),
    RHO("RHO", "PROSAC-based RHO.", opencv_calib3d.RHO),
    USAC_DEFAULT("USAC", "USAC with its default settings.", opencv_calib3d.USAC_DEFAULT),
    USAC_FAST("USAC fast", "USAC tuned for speed.", opencv_calib3d.USAC_FAST),
    USAC_ACCURATE("USAC accurate", "USAC tuned for accuracy (graph-cut local optimisation).", opencv_calib3d.USAC_ACCURATE),
    USAC_PROSAC("PROSAC", "USAC with PROSAC sampling: the closest matches are tried first.", opencv_calib3d.USAC_PROSAC),
    ;

    /** PROSAC and RHO sample the matches closest first, so they are given them in that order. */
    internal val sortsMatches: Boolean get() = this == USAC_PROSAC || this == RHO
}
