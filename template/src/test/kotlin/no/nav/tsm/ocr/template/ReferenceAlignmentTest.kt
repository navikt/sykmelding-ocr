package no.nav.tsm.ocr.template

import no.nav.tsm.sykmelding.utils.ImagePoint
import no.nav.tsm.sykmelding.utils.ReferencePair
import no.nav.tsm.sykmelding.utils.FieldTransform
import no.nav.tsm.sykmelding.utils.estimateReferenceAlignment
import org.example.no.nav.tsm.ocr.template.template.Felter
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferenceAlignmentTest {
    private val points = listOf(ImagePoint(100.0, 100.0), ImagePoint(800.0, 100.0),
        ImagePoint(100.0, 800.0), ImagePoint(800.0, 800.0))

    @Test
    fun `reported page references fit independent axis scales without ambiguity`() {
        val pairs = listOf(
            ReferencePair(ImagePoint(651.5, 4226.0), ImagePoint(688.5, 4270.0)),
            ReferencePair(ImagePoint(697.5, 294.5), ImagePoint(733.5, 438.5)),
            ReferencePair(ImagePoint(130.0, 71.0), ImagePoint(174.0, 221.0)),
            ReferencePair(ImagePoint(984.5, 676.5), ImagePoint(1017.5, 811.5)),
            ReferencePair(ImagePoint(2676.0, 621.5), ImagePoint(2688.0, 759.5)),
        )
        for (ordered in listOf(pairs, pairs.reversed())) {
            val result = estimateReferenceAlignment(ordered, 3307, 4677)
            assertTrue(result.affine)
            assertEquals(5, result.inliers)
            assertEquals(0.9875482634, result.transform.a, 1e-9)
            assertEquals(0.9744538063, result.transform.d, 1e-9)
            pairs.forEach { assertTrue(result.transform.map(it.template).distance(it.incoming) < 0.8) }
        }
    }

    @Test
    fun `affine alignment recovers unequal scale rotation and shear while rejecting an outlier`() {
        val expected = FieldTransform(1.05, 0.04, 20.0, 10.0, c = -0.01, d = 0.92)
        val source = points + listOf(ImagePoint(450.0, 450.0), ImagePoint(700.0, 400.0))
        val pairs = source.map { ReferencePair(it, expected.map(it)) }.toMutableList()
        pairs[5] = pairs[5].copy(incoming = ImagePoint(200.0, 900.0))
        val result = estimateReferenceAlignment(pairs, 1000, 1000)
        assertTrue(result.affine)
        assertEquals(5, result.inliers)
        source.forEach { assertTrue(expected.map(it).distance(result.transform.map(it)) < 1e-8) }
    }

    @Test
    fun `collinear anchors retain uniform scale without underdetermined affine fitting`() {
        val transform = FieldTransform(0.98, 0.03, 30.0, 20.0)
        val source = listOf(ImagePoint(100.0, 100.0), ImagePoint(100.0, 450.0), ImagePoint(100.0, 800.0))
        val result = estimateReferenceAlignment(source.map { ReferencePair(it, transform.map(it)) }, 1000, 1000)
        assertFalse(result.affine)
        assertEquals(3, result.inliers)
        points.forEach { assertTrue(transform.map(it).distance(result.transform.map(it)) < 1e-8) }
    }

    @Test
    fun `collapsed target anchors cannot produce an affine transform`() {
        assertFailsWith<IllegalArgumentException> {
            estimateReferenceAlignment(listOf(
                ReferencePair(points[0], ImagePoint(100.0, 100.0)),
                ReferencePair(points[1], ImagePoint(500.0, 100.0)),
                ReferencePair(points[2], ImagePoint(900.0, 100.0)),
            ), 1000, 1000)
        }
    }

    @Test
    fun `noisy affine observations are refined consistently regardless of reference order`() {
        val source = points + ImagePoint(450.0, 450.0)
        val incoming = listOf(
            ImagePoint(119.46913837224574, 112.96302807666397),
            ImagePoint(817.619486799467, 108.91483489309574),
            ImagePoint(122.700234647721, 809.696404535114),
            ImagePoint(818.2519154339947, 808.9014238994736),
            ImagePoint(472.45181506937666, 459.01341286281087),
        )
        val pairs = source.zip(incoming).map { (from, to) ->
            ReferencePair(from, ImagePoint((to.x + from.x + 20) / 2, (to.y + from.y + 10) / 2))
        }
        for (ordered in listOf(pairs, pairs.reversed())) {
            val result = estimateReferenceAlignment(ordered, 1000, 1000)
            assertEquals(5, result.inliers)
            assertEquals(5, pairs.count { result.transform.map(it.template).distance(it.incoming) <= 3.0 })
            assertTrue(result.transform.map(ImagePoint(450.0, 450.0)).distance(ImagePoint(470.0, 460.0)) < 3.0)
        }
    }

    @Test
    fun `two pairs recover translation uniform scale and both rotation directions`() {
        for ((scale, degrees) in listOf(1.0 to 0.0, 1.15 to 0.0, 1.0 to 12.0, 0.9 to -10.0)) {
            val angle = Math.toRadians(degrees)
            val expected = FieldTransform(scale * cos(angle), scale * sin(angle), 50.0, 30.0)
            val alignment = estimateReferenceAlignment(points.take(2).map { ReferencePair(it, expected.map(it)) }, 1000, 1000)
            assertFalse(alignment.affine)
            points.forEach { assertTrue(expected.map(it).distance(alignment.transform.map(it)) < 1e-8) }
            assertEquals(2, alignment.inliers)
        }
    }

    @Test
    fun `all corners are transformed without losing field identity`() {
        val field = Felter(40, 30, 60, 40, "Name", "name")
        val transform = FieldTransform(cos(0.1), sin(0.1), 20.0, 10.0)
        val region = transform.region(field)
        assertEquals(field, region.field)
        assertEquals(listOf(ImagePoint(40.0, 30.0), ImagePoint(100.0, 30.0),
            ImagePoint(100.0, 70.0), ImagePoint(40.0, 70.0)).map(transform::map), region.corners)
        assertTrue(region.corners[0].y != region.corners[1].y)
    }

    @Test
    fun `additional pairs refine noisy observations and reject one outlier`() {
        val expected = FieldTransform(1.05, 0.03, 20.0, 10.0)
        val pairs = points.map { ReferencePair(it, expected.map(it)) }.toMutableList()
        pairs[1] = pairs[1].copy(incoming = pairs[1].incoming.copy(x = pairs[1].incoming.x + 1))
        pairs[3] = pairs[3].copy(incoming = ImagePoint(400.0, 600.0))
        val result = estimateReferenceAlignment(pairs, 1000, 1000)
        assertEquals(3, result.inliers)
        assertEquals(4, result.pairs)
        points.forEach { assertTrue(expected.map(it).distance(result.transform.map(it)) < 2.0) }
    }

    @Test
    fun `insufficient degenerate and conflicting pairs fail explicitly`() {
        val valid = points.map { ReferencePair(it, it) }
        val invalid = listOf(
            emptyList(), valid.take(1), listOf(valid[0], valid[0]),
            listOf(valid[0], ReferencePair(points[1], points[0])),
            listOf(valid[0], ReferencePair(ImagePoint(110.0, 110.0), ImagePoint(110.0, 110.0))),
            valid.take(2) + ReferencePair(points[2], ImagePoint(400.0, 600.0)),
            listOf(valid[0], ReferencePair(ImagePoint(Double.NaN, 0.0), points[1])),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { estimateReferenceAlignment(it, 1000, 1000) } }
    }

    @Test
    fun `equally supported incompatible fits fail instead of choosing an arbitrary reference set`() {
        val angle = Math.toRadians(10.0)
        val competing = FieldTransform(cos(angle), sin(angle),
            500 - 500 * cos(angle) + 500 * sin(angle), 500 - 500 * sin(angle) - 500 * cos(angle))
        val points = listOf(ImagePoint(500.0, 500.0), ImagePoint(100.0, 500.0), ImagePoint(500.0, 100.0),
            ImagePoint(800.0, 500.0), ImagePoint(500.0, 800.0))
        val pairs = points.mapIndexed { index, point ->
            ReferencePair(point, if (index < 3) point else competing.map(point))
        }
        val failure = assertFailsWith<IllegalArgumentException> { estimateReferenceAlignment(pairs, 1000, 1000) }
        assertTrue(failure.message!!.contains("ambiguous"))
    }

    @Test
    fun `implausible scale rotation translation and reflection are rejected`() {
        for (transform in listOf(FieldTransform(2.0, 0.0, 0.0, 0.0),
            FieldTransform(cos(0.5), sin(0.5), 0.0, 0.0),
            FieldTransform(1.0, 0.0, 600.0, 0.0))) {
            assertFailsWith<IllegalArgumentException> {
                estimateReferenceAlignment(points.map { ReferencePair(it, transform.map(it)) }, 1000, 1000)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            estimateReferenceAlignment(points.map { ReferencePair(it, ImagePoint(1000 - it.x, it.y)) }, 1000, 1000)
        }
    }
}
