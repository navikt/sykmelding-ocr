package no.nav.tsm.ocr.template.view

import org.example.no.nav.tsm.ocr.template.template.Felter
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

internal data class ImagePoint(val x: Double, val y: Double) {
    fun distance(other: ImagePoint) = hypot(x - other.x, y - other.y)
}

internal data class ReferencePair(val template: ImagePoint, val incoming: ImagePoint)

internal data class FieldRegion(val field: Felter, val corners: List<ImagePoint>)

internal data class FieldTransform(
    val a: Double,
    val b: Double,
    val tx: Double,
    val ty: Double,
    val c: Double = -b,
    val d: Double = a,
) {
    fun map(point: ImagePoint) = ImagePoint(a * point.x + c * point.y + tx, b * point.x + d * point.y + ty)

    fun region(field: Felter): FieldRegion = FieldRegion(field, listOf(
        ImagePoint(field.x.toDouble(), field.y.toDouble()),
        ImagePoint(field.x.toDouble() + field.bredde, field.y.toDouble()),
        ImagePoint(field.x.toDouble() + field.bredde, field.y.toDouble() + field.hoyde),
        ImagePoint(field.x.toDouble(), field.y.toDouble() + field.hoyde),
    ).map(::map))
}

internal data class ReferenceAlignment(
    val transform: FieldTransform,
    val inliers: Int,
    val pairs: Int,
    val affine: Boolean,
)

internal fun estimateReferenceAlignment(
    pairs: List<ReferencePair>,
    width: Int,
    height: Int,
): ReferenceAlignment {
    require(width > 0 && height > 0)
    require(pairs.size >= 2) { "At least two corresponding reference matches are required." }
    require(pairs.all { listOf(it.template.x, it.template.y, it.incoming.x, it.incoming.y).all(Double::isFinite) }) {
        "Reference centers must be finite."
    }
    val diagonal = hypot(width.toDouble(), height.toDouble())
    val tolerance = maxOf(3.0, diagonal * 0.002)
    val minimumSpan = diagonal * 0.05
    val indices = pairs.indices
    require(indices.none { i -> (i + 1 until pairs.size).any { j ->
        pairs[i].template.distance(pairs[j].template) <= tolerance ||
            pairs[i].incoming.distance(pairs[j].incoming) <= tolerance
    } }) { "Reference matches overlap or reuse the same center; use distinct references." }

    fun supported(transform: FieldTransform): Boolean =
        hypot(transform.a, transform.b) in 0.8..1.25 &&
            hypot(transform.c, transform.d) in 0.8..1.25 &&
            transform.a * transform.d - transform.b * transform.c > 0 &&
            abs(atan2(transform.b, transform.a)) <= Math.toRadians(15.0) &&
            abs(atan2(-transform.c, transform.d)) <= Math.toRadians(15.0) &&
            hypot(transform.tx, transform.ty) <= diagonal * 0.25

    fun nonCollinear(points: List<ImagePoint>): Boolean {
        val first = points[0]
        val second = points[1]
        val third = points[2]
        val span = maxOf(first.distance(second), first.distance(third), second.distance(third))
        val twiceArea = abs((second.x - first.x) * (third.y - first.y) - (second.y - first.y) * (third.x - first.x))
        return span >= minimumSpan && twiceArea / span >= diagonal * 0.01
    }

    val triples = indices.flatMap { i ->
        (i + 1 until pairs.size).flatMap { j ->
            (j + 1 until pairs.size).map { k -> listOf(i, j, k) }
        }
    }.filter { selected -> nonCollinear(selected.map { pairs[it].template }) }
    val affine = triples.isNotEmpty()
    val seeds = if (affine) triples.filter { selected -> nonCollinear(selected.map { pairs[it].incoming }) }
    else indices.flatMap { i ->
        (i + 1 until pairs.size).mapNotNull { j ->
            if (pairs[i].template.distance(pairs[j].template) < minimumSpan ||
                pairs[i].incoming.distance(pairs[j].incoming) < minimumSpan) null else listOf(i, j)
        }
    }

    fun fit(selected: List<Int>): FieldTransform {
        val source = ImagePoint(selected.map { pairs[it].template.x }.average(), selected.map { pairs[it].template.y }.average())
        val target = ImagePoint(selected.map { pairs[it].incoming.x }.average(), selected.map { pairs[it].incoming.y }.average())
        var denominator = 0.0
        var real = 0.0
        var imaginary = 0.0
        var xx = 0.0
        var xy = 0.0
        var yy = 0.0
        var xu = 0.0
        var yu = 0.0
        var xv = 0.0
        var yv = 0.0
        selected.forEach {
            val x = pairs[it].template.x - source.x
            val y = pairs[it].template.y - source.y
            val u = pairs[it].incoming.x - target.x
            val v = pairs[it].incoming.y - target.y
            denominator += x * x + y * y
            real += x * u + y * v
            imaginary += x * v - y * u
            xx += x * x
            xy += x * y
            yy += y * y
            xu += x * u
            yu += y * u
            xv += x * v
            yv += y * v
        }
        if (affine) {
            val determinant = xx * yy - xy * xy
            val a = (xu * yy - yu * xy) / determinant
            val b = (xv * yy - yv * xy) / determinant
            val c = (yu * xx - xu * xy) / determinant
            val d = (yv * xx - xv * xy) / determinant
            return FieldTransform(a, b, target.x - a * source.x - c * source.y,
                target.y - b * source.x - d * source.y, c, d)
        }
        val a = real / denominator
        val b = imaginary / denominator
        return FieldTransform(a, b, target.x - a * source.x + b * source.y, target.y - b * source.x - a * source.y)
    }

    val hypotheses = seeds.mapNotNull { selected ->
        val transform = fit(selected)
        if (!supported(transform)) return@mapNotNull null
        indices.filter { transform.map(pairs[it].template).distance(pairs[it].incoming) <= tolerance }
    }.distinct()
    val required = if (pairs.size == 2) 2 else maxOf(3, pairs.size / 2 + 1)
    val candidates = hypotheses.filter { it.size >= required }.mapNotNull { initial ->
        var inliers = initial
        val visited = mutableSetOf<List<Int>>()
        while (visited.add(inliers)) {
            val transform = fit(inliers)
            if (!supported(transform)) return@mapNotNull null
            val refined = indices.filter { transform.map(pairs[it].template).distance(pairs[it].incoming) <= tolerance }
            if (refined.size < required) return@mapNotNull null
            if (refined == inliers) return@mapNotNull inliers to transform
            inliers = refined
        }
        null
    }.distinctBy { it.first }
    require(candidates.isNotEmpty()) {
        "No supported consistent alignment: use well-spread references with axis scales 0.8–1.25, axis rotations ≤15° and translation ≤25% of the page diagonal."
    }
    val maximum = candidates.maxOf { it.first.size }
    val best = candidates.filter { it.first.size == maximum }
    val corners = listOf(ImagePoint(0.0, 0.0), ImagePoint(width.toDouble(), 0.0),
        ImagePoint(0.0, height.toDouble()), ImagePoint(width.toDouble(), height.toDouble()))
    require(best.indices.all { i -> (i + 1 until best.size).all { j ->
        corners.all { best[i].second.map(it).distance(best[j].second.map(it)) <= tolerance }
    } }) { "Reference matches are ambiguous: equally supported alignments place fields differently. Check the reference rectangles." }
    val (inliers, transform) = best.minBy { (inliers, transform) ->
        inliers.sumOf { transform.map(pairs[it].template).distance(pairs[it].incoming).let { error -> error * error } }
    }
    return ReferenceAlignment(transform, inliers.size, pairs.size, affine)
}
