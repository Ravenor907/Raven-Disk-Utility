package io.github.ravenor907.ravendisk.run

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.ravenor907.ravendisk.core.bench.DriveTest
import io.github.ravenor907.ravendisk.core.image.CorruptImageException
import io.github.ravenor907.ravendisk.core.image.ImageKind
import io.github.ravenor907.ravendisk.core.image.ImageWriter
import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.job.CancelledException
import io.github.ravenor907.ravendisk.core.plan.DiskSession
import io.github.ravenor907.ravendisk.core.plan.DiskSnapshot
import io.github.ravenor907.ravendisk.core.plan.Outcome
import io.github.ravenor907.ravendisk.core.plan.RunResult
import io.github.ravenor907.ravendisk.core.plan.StepResult
import io.github.ravenor907.ravendisk.core.util.Units
import java.io.IOException

/** A file the person picked to write to a drive. */
data class PickedImage(val uri: Uri, val name: String, val size: Long, val kind: ImageKind)

/**
 * The speed test and image writer, wrapped with Raven's safety checks: the drive must still be the
 * one the person looked at (same identity and partition table fingerprint), and Android must not
 * have it mounted. The engine itself is shared with Handheld Forge.
 */
object Tools {
    /** Reads what a picked file is from its first bytes, and its size. */
    fun inspect(context: Context, uri: Uri): PickedImage {
        val cr = context.contentResolver
        var name = uri.lastPathSegment ?: "image"
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
        }
        if (size < 0) size = try {
            cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        } catch (e: Exception) {
            -1
        }
        val head = ByteArray(ImageKind.SNIFF_BYTES)
        var got = 0
        (cr.openInputStream(uri) ?: throw IOException("Couldn't open the file")).use { s ->
            while (got < head.size) {
                val n = s.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
        }
        return PickedImage(uri, name, size, ImageKind.sniff(head.copyOf(got)))
    }

    /** Why the image can't go on this drive, or null. Compressed images are checked while writing. */
    fun imageProblem(img: PickedImage, snap: DiskSnapshot): String? = when {
        !img.kind.writable -> "${img.kind.label} files can't be written yet. Unpack it first, then pick the unpacked file."
        !img.kind.compressed && img.size > snap.blockSize.toLong() * snap.blockCount ->
            "The image is ${Units.size(img.size)}, bigger than this drive (${Units.size(snap.blockSize.toLong() * snap.blockCount)})."
        else -> null
    }

    private fun checkSameDrive(session: DiskSession, snap: DiskSnapshot) {
        snap.identity.mismatch(session.identity())?.let { throw IOException("$it. Nothing was written; go back and open the drive again.") }
    }

    fun speedTest(session: DiskSession, snap: DiskSnapshot, simulated: Boolean, c: ToolContext): RunResult {
        return guard("Speed and capacity test", c) { steps ->
            checkSameDrive(session, snap)
            steps.add(StepResult("Checked it's the same drive", true, snap.identity.describe()))
            val dev: BlockDevice = if (simulated) SimulatedSpeed(session.dev) else session.dev
            val r = if (simulated) {
                DriveTest.run(dev, c::stage, c::log, { c.cancelled }, minBytes = 96L shl 20, maxBytes = 112L shl 20, budgetSeconds = 12.0, spots = 24, sample = c::sample)
            } else {
                DriveTest.run(dev, c::stage, c::log, { c.cancelled }, sample = c::sample)
            }
            c.report(r.lines())
            steps.add(StepResult("Wrote and read back ${Units.size(r.testedBytes)} plus ${r.spots} markers across the drive", !r.failing && !r.fake, ""))
            val outcome = if (r.fake || r.failing) Outcome.FAILED else Outcome.SUCCESS
            val summary = when {
                r.fake -> "This drive is fake: it doesn't really hold ${Units.size(r.driveBytes)}."
                r.failing -> "Data came back wrong. The drive is failing or fake."
                else -> "Write %.0f MB/s, read %.0f MB/s. The full capacity is real.".format(r.writeMBs, r.readMBs)
            }
            RunResult(outcome, "$summary The test erased the drive; format it to use it again.", steps, r.lines())
        }
    }

    fun writeImage(context: Context, img: PickedImage, session: DiskSession, snap: DiskSnapshot, simulated: Boolean, c: ToolContext): RunResult {
        return guard("Write image", c) { steps ->
            checkSameDrive(session, snap)
            steps.add(StepResult("Checked it's the same drive", true, snap.identity.describe()))
            c.log("Image: ${img.name} (${img.kind.label}, ${Units.size(img.size)})")
            val written = ImageWriter.writeStream(
                { context.contentResolver.openInputStream(img.uri) ?: throw IOException("Couldn't open the file") },
                img.size.coerceAtLeast(1), ImageKind.decoder(img.kind), if (simulated) SimulatedSpeed(session.dev) else session.dev, null,
                c::stage, c::log, { c.cancelled }, sample = c::sample,
            )
            steps.add(StepResult("Wrote ${Units.size(written)}", true, img.name))
            steps.add(StepResult("Read every byte back and compared", true, "It matches the image"))
            val lines = listOfNotNull(
                "✓ Every byte on the drive matches the image",
                img.kind.warning,
            )
            c.report(lines)
            RunResult(Outcome.SUCCESS, "${img.name} is on the drive and checked.", steps, lines)
        }
    }

    private fun guard(what: String, c: ToolContext, body: (MutableList<StepResult>) -> RunResult): RunResult {
        val steps = ArrayList<StepResult>()
        return try {
            body(steps)
        } catch (e: CancelledException) {
            RunResult(Outcome.CANCELLED, "Stopped. Whatever was written so far stays on the drive; format it before using it.", steps, emptyList())
        } catch (e: CorruptImageException) {
            c.log("Image error: ${e.message}")
            RunResult(Outcome.FAILED, "${e.message} Download it again; the drive is partly written, so write it again or format it.", steps + StepResult(what, false, e.message ?: ""), emptyList())
        } catch (e: Exception) {
            c.log("Failed: ${e.javaClass.simpleName}: ${e.message}")
            RunResult(Outcome.FAILED, e.message ?: e.javaClass.simpleName, steps + StepResult(what, false, e.message ?: ""), emptyList())
        }
    }
}

/**
 * Simulated drives answer at memory speed; this makes them behave like a real stick for the demo
 * (fast until a small cache fills, then slower and uneven), so the live graph has something to show.
 */
private class SimulatedSpeed(private val inner: BlockDevice) : BlockDevice by inner {
    private var written = 0L
    private val rnd = java.util.Random(7)

    private fun pace(bytes: Int, mbs: Double) {
        val jitter = 0.75 + rnd.nextDouble() * 0.5
        val ms = bytes / (mbs * jitter * 1e6) * 1000
        Thread.sleep(ms.toLong().coerceAtLeast(1))
    }

    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        inner.write(lba, blocks, buf, offset)
        val n = blocks * blockSize
        written += n
        pace(n, if (written < (24L shl 20)) 140.0 else 12.0)
    }

    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        inner.read(lba, blocks, buf, offset)
        pace(blocks * blockSize, 60.0)
    }
}
