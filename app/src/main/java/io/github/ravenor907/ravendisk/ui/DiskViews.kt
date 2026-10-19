package io.github.ravenor907.ravendisk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.model.MediaKind
import io.github.ravenor907.ravendisk.core.plan.PlannedVolume
import io.github.ravenor907.ravendisk.core.plan.VolumeChange
import io.github.ravenor907.ravendisk.core.table.PartitionTable

/** One region of a disk as drawn in the map and listed under it. */
data class Segment(
    /** "p<number>" for partitions, "free:<firstLba>" for unallocated space. */
    val key: String,
    val first: Long,
    val last: Long,
    val volume: PlannedVolume?,
) {
    val free get() = volume == null
    val blocks get() = last - first + 1
}

/** Partitions and free space in disk order. Gaps under 1 MiB (alignment slack) are left out. */
fun segments(table: PartitionTable?, volumes: List<PlannedVolume>, blockSize: Int): List<Segment> {
    if (table == null) return emptyList()
    val mib = 1024L * 1024 / blockSize
    val out = ArrayList<Segment>()
    volumes.filter { it.partition.mbrType == null || !io.github.ravenor907.ravendisk.core.table.PartitionTypes.isExtended(it.partition.mbrType!!) }
        .forEach { out.add(Segment("p${it.partition.number}", it.partition.firstLba, it.partition.lastLba, it)) }
    for (r in table.freeRegions()) if (r.last - r.first + 1 >= mib) out.add(Segment("free:${r.first}", r.first, r.last, null))
    return out.sortedBy { it.first }
}

fun segmentColor(s: Segment): Color = if (s.free) Raven.Iron else Raven.fsColor(s.volume?.fs)

/**
 * The disk as one bar, every partition proportional to its size but never thinner than a tap
 * target's sliver, unallocated space hatched. Tapping selects a segment.
 */
@Composable
fun PartitionMap(
    segs: List<Segment>,
    totalBlocks: Long,
    selected: String?,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    onSelect: ((String) -> Unit)? = null,
) {
    val layout = mapLayout(segs, totalBlocks)
    var m = modifier.fillMaxWidth().height(height)
    if (onSelect != null) {
        m = m.pointerInput(segs, totalBlocks) {
            detectTapGestures { pos ->
                val f = pos.x / size.width
                layout.firstOrNull { f >= it.second && f <= it.third }?.let { onSelect(it.first.key) }
            }
        }
    }
    Canvas(m) {
        val r = CornerRadius(10.dp.toPx())
        drawRoundRect(Raven.Night, cornerRadius = r)
        val gap = 2.dp.toPx()
        clipRect {
            for ((s, a, b) in layout) {
                val x0 = a * size.width + if (a > 0f) gap / 2 else 0f
                val x1 = b * size.width - if (b < 1f) gap / 2 else 0f
                if (x1 <= x0) continue
                val tl = Offset(x0, 0f)
                val sz = Size(x1 - x0, size.height)
                val radius = CornerRadius(6.dp.toPx())
                if (s.free) {
                    drawRoundRect(Raven.Iron, tl, sz, radius)
                    hatch(tl, sz)
                } else {
                    drawRoundRect(segmentColor(s), tl, sz, radius)
                    val v = s.volume!!
                    if (v.change != VolumeChange.KEPT) {
                        // Planned changes get a dashed outline.
                        drawRoundRect(Raven.Bone, tl, sz, radius, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
                    }
                }
                if (s.key == selected) {
                    drawRoundRect(Raven.aurora, tl, sz, radius, style = Stroke(3.dp.toPx()))
                }
            }
        }
    }
}

/** Fractions (start, end) of the bar for each segment, with a minimum visible width. */
private fun mapLayout(segs: List<Segment>, total: Long): List<Triple<Segment, Float, Float>> {
    if (segs.isEmpty() || total <= 0) return emptyList()
    val min = 0.035f
    val raw = segs.map { maxOf(it.blocks.toFloat() / total, min) }
    val sum = raw.sum().coerceAtLeast(1e-6f)
    var x = 0f
    return segs.mapIndexed { i, s ->
        val w = raw[i] / sum
        val t = Triple(s, x, x + w)
        x += w
        t
    }
}

private fun DrawScope.hatch(tl: Offset, sz: Size) {
    val step = 9.dp.toPx()
    clipRect(tl.x, tl.y, tl.x + sz.width, tl.y + sz.height) {
        var x = tl.x - sz.height
        while (x < tl.x + sz.width) {
            drawLine(Raven.Seam, Offset(x, tl.y + sz.height), Offset(x + sz.height, tl.y), 1.2.dp.toPx())
            x += step
        }
    }
}

/** Small swatch for legends and lists. */
@Composable
fun Swatch(color: Color, free: Boolean = false, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) {
        val r = CornerRadius(4.dp.toPx())
        if (free) {
            drawRoundRect(Raven.Iron, cornerRadius = r)
            drawRoundRect(Raven.Seam, cornerRadius = r, style = Stroke(1.dp.toPx()))
        } else {
            drawRoundRect(color, cornerRadius = r)
        }
    }
}

/** Drive glyphs drawn on a 32x24 grid: flash stick, SSD, hard drive, SD card, NVMe stick, phone. */
@Composable
fun DriveGlyph(kind: MediaKind?, hot: Boolean, size: Dp = 36.dp) {
    Canvas(Modifier.size(width = size, height = size * 0.75f)) {
        val u = this.size.width / 32f
        val body: Brush = if (hot) Raven.aurora else SolidColor(Raven.Ash)
        val r = CornerRadius(3 * u)
        when (kind) {
            MediaKind.FLASH_DRIVE, null, MediaKind.UNKNOWN -> {
                drawRoundRect(body, Offset(1 * u, 5 * u), Size(21 * u, 14 * u), r)
                drawRoundRect(Raven.Seam, Offset(22 * u, 8 * u), Size(8 * u, 8 * u), CornerRadius(1.5f * u))
                drawRect(Raven.Night, Offset(25 * u, 10 * u), Size(2.5f * u, 1.5f * u))
                drawRect(Raven.Night, Offset(25 * u, 12.5f * u), Size(2.5f * u, 1.5f * u))
            }
            MediaKind.SSD -> {
                drawRoundRect(body, Offset(3 * u, 2 * u), Size(26 * u, 20 * u), CornerRadius(4 * u))
                drawRoundRect(Raven.Night, Offset(13 * u, 18.5f * u), Size(6 * u, 2 * u), CornerRadius(1 * u))
            }
            MediaKind.HDD -> {
                drawRoundRect(body, Offset(3 * u, 1 * u), Size(26 * u, 22 * u), CornerRadius(3 * u))
                drawCircle(Raven.Night, 7 * u, Offset(16 * u, 11 * u))
                drawCircle(if (hot) Raven.AuroraA else Raven.Ash, 1.8f * u, Offset(16 * u, 11 * u))
            }
            MediaKind.NVME -> {
                drawRoundRect(body, Offset(1 * u, 8 * u), Size(30 * u, 8 * u), CornerRadius(1.5f * u))
                for (i in 0 until 3) drawRect(Raven.Night, Offset((6 + i * 7) * u, 10 * u), Size(5 * u, 4 * u))
            }
            MediaKind.SD_CARD -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(9 * u, 1 * u); lineTo(25 * u, 1 * u); lineTo(25 * u, 23 * u); lineTo(7 * u, 23 * u); lineTo(7 * u, 5 * u); close()
                }
                drawPath(p, body)
                for (i in 0 until 4) drawRect(Raven.Night, Offset((10 + i * 3.5f) * u, 3 * u), Size(2 * u, 4 * u))
            }
            MediaKind.INTERNAL_STORAGE -> {
                drawRoundRect(body, Offset(9 * u, 0f), Size(14 * u, 24 * u), CornerRadius(3 * u))
                drawRoundRect(Raven.Night, Offset(11 * u, 2.5f * u), Size(10 * u, 17 * u), CornerRadius(1 * u))
            }
        }
    }
}

/** Legend text for a filesystem (or none). */
fun fsName(fs: FsType?): String = fs?.label ?: "No filesystem"

private const val RAVEN_PATH = "M2,34 L13,26.5 L24,20.5 L30,15.6 L37,14.8 L43,18 L50,26 C58,33 68,45 78,58 L91,76 L99,93 L92,90.5 L94.5,97 L85,90 L73,83 C70,85 66,86 63,86 L62,92.5 L65.5,95.5 L58.5,95.5 L58,86.3 L54.6,86 L54,93 L57.5,96 L50.5,96 L50.5,85.4 L43,82.5 L35.5,73.5 L31.4,63 L30,56 L25,53.5 L29.2,50.6 L22.6,47.6 L27,44.6 L21,42 L24.6,39.4 L21.4,36.4 L5,35.6 Z"

/** The raven head from the app icon, in the aurora gradient. */
@Composable
fun RavenMark(modifier: Modifier = Modifier, brush: Brush = Raven.aurora) {
    val path = androidx.compose.runtime.remember { androidx.compose.ui.graphics.vector.PathParser().parsePathString(RAVEN_PATH).toPath() }
    Canvas(modifier) {
        // Source box: x 2..99, y 14.8..97 (the perched raven from the app icon).
        val s = minOf(size.width / 97f, size.height / 82.2f)
        val dx = (size.width - 97f * s) / 2f - 2f * s
        val dy = (size.height - 82.2f * s) / 2f - 14.8f * s
        withTransform({
            translate(dx, dy)
            scale(s, s, Offset.Zero)
        }) {
            drawPath(path, brush)
            drawCircle(Raven.Night, 1.9f, Offset(33f, 22.6f))
        }
    }
}
