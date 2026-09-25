package org.example.no.nav.tsm.ocr.template

import org.bytedeco.javacpp.PointerScope
import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.global.opencv_calib3d.RANSAC
import org.bytedeco.opencv.global.opencv_calib3d.estimateAffinePartial2D
import org.bytedeco.opencv.global.opencv_core.*
import org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR
import org.bytedeco.opencv.global.opencv_imgproc.warpAffine
import org.bytedeco.opencv.opencv_core.*
import org.bytedeco.opencv.opencv_features2d.BFMatcher
import org.bytedeco.opencv.opencv_features2d.SIFT
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte

internal data class AlignedImages(
    val image: BufferedImage,
    val overlay: BufferedImage
)

internal fun alignImages(
    reference: BufferedImage,
    target: BufferedImage
): AlignedImages {
    PointerScope().use {
        val referenceMat = reference.toBgrMat()
        val targetMat = target.toBgrMat()
        val detector = SIFT.create()
        val referencePoints = KeyPointVector()
        val targetPoints = KeyPointVector()
        val referenceDescriptors = Mat()
        val targetDescriptors = Mat()
        val mask = Mat()
        detector.detectAndCompute(referenceMat, mask, referencePoints, referenceDescriptors)
        detector.detectAndCompute(targetMat, mask, targetPoints, targetDescriptors)
        check(referenceDescriptors.rows() >= 2 && targetDescriptors.rows() >= 2) {
            "Not enough image features to align the overlay"
        }

        val matcher = BFMatcher(NORM_L2, false)
        val candidates = DMatchVectorVector()
        val reverseCandidates = DMatchVectorVector()
        matcher.knnMatch(referenceDescriptors, targetDescriptors, candidates, 2)
        matcher.knnMatch(targetDescriptors, referenceDescriptors, reverseCandidates, 2)
        val matches = (0 until candidates.size()).mapNotNull { index ->
            val pair = candidates.get(index)
            if (pair.size() < 2) return@mapNotNull null
            val best = pair.get(0)
            val reverse = reverseCandidates.get(best.trainIdx().toLong())
            // Added form text must not count as a match unless both images agree on the feature.
            if (best.distance() < 0.75f * pair.get(1).distance() &&
                reverse.size() >= 2 &&
                reverse.get(0).trainIdx() == best.queryIdx() &&
                reverse.get(0).distance() < 0.75f * reverse.get(1).distance()
            ) best else null
        }
        check(matches.size >= 8) { "Not enough matching features to align the overlay" }

        val from = Mat(matches.size, 1, CV_32FC2)
        val to = Mat(matches.size, 1, CV_32FC2)
        from.createIndexer<FloatIndexer>().use { fromIndexer ->
            to.createIndexer<FloatIndexer>().use { toIndexer ->
                matches.forEachIndexed { index, match ->
                    val source = referencePoints.get(match.queryIdx().toLong()).pt()
                    val destination = targetPoints.get(match.trainIdx().toLong()).pt()
                    fromIndexer.put(index.toLong(), 0, 0, source.x())
                    fromIndexer.put(index.toLong(), 0, 1, source.y())
                    toIndexer.put(index.toLong(), 0, 0, destination.x())
                    toIndexer.put(index.toLong(), 0, 1, destination.y())
                }
            }
        }
        val inliers = Mat()
        val transform = estimateAffinePartial2D(from, to, inliers, RANSAC, 3.0, 3000, 0.99, 10)
        check(!transform.empty()) { "Could not estimate an overlay transform" }
        val inlierCount = countNonZero(inliers)
        check(hasEnoughInliers(inliers, matches.size)) {
            "Too few consistent matches to align the overlay reliably ($inlierCount/${matches.size})"
        }
        return warpAlignedImage(referenceMat, transform, target.width, target.height)
    }
}

private fun warpAlignedImage(source: Mat, transform: Mat, width: Int, height: Int): AlignedImages {
    val aligned = Mat()
    val size = Size(width, height)
    val background = Scalar(255.0, 255.0, 255.0, 0.0)
    warpAffine(source, aligned, transform, size, INTER_LINEAR, BORDER_CONSTANT, background)
    val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    aligned.data().get((image.raster.dataBuffer as DataBufferByte).data)
    return AlignedImages(image, createDarkOverlay(image))
}

private fun hasEnoughInliers(inliers: Mat, matchCount: Int): Boolean {
    if (inliers.empty()) return false
    val count = countNonZero(inliers)
    return count >= 6 && count >= matchCount * 0.4
}

private fun BufferedImage.toBgrMat(): Mat {
    val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.drawImage(this, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return Mat(height, width, CV_8UC3).apply {
        val pixels = (image.raster.dataBuffer as DataBufferByte).data
        data().put(pixels, 0, pixels.size)
    }
}
