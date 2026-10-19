package io.github.ravenor907.ravendisk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ravenor907.ravendisk.core.plan.Op
import io.github.ravenor907.ravendisk.core.plan.VolumeChange
import io.github.ravenor907.ravendisk.core.util.Units
import io.github.ravenor907.ravendisk.run.Runner

@Composable
fun ReviewScreen(m: AppModel, key: String) {
    val drives by m.drives.drives.collectAsState()
    val run by Runner.state.collectAsState()
    @Suppress("UNUSED_VARIABLE") val version = m.planVersion
    val d = drives.firstOrNull { it.key == key }
    val p = m.planner(key)
    if (d == null || d.info == null || p == null || p.queue.isEmpty()) {
        Page {
            TopBar("Review changes", onBack = { m.pop() })
            EmptySlot("Nothing to review.")
        }
        return
    }
    val bs = p.snap.blockSize
    val ops = p.queue
    val destructive = ops.any { it.destructive }
    val whole = ops.any { it is Op.NewTable || (it is Op.Erase && it.number == null) }
    var ack by remember(key) { mutableStateOf(false) }

    // Data that disappears: partitions that existed before and are deleted, formatted or erased.
    val after = p.current
    val lost = p.base.volumes.mapNotNull { v ->
        val now = after.volume(v.partition.number)
        val gone = after.newTable || after.wholeErase != null || now == null ||
            now.change == VolumeChange.FORMATTED || now.change == VolumeChange.ERASED ||
            (now.partition.firstLba != v.partition.firstLba)
        if (!gone) null else "Partition ${v.partition.number}: ${v.fs?.label ?: "no filesystem"}" +
            (v.label?.let { " \"$it\"" } ?: "") + ", ${Units.sizeBinary(v.partition.sizeBytes(bs))}"
    }.let { l ->
        val wholeFs = d.inspection?.wholeDeviceFs
        if (l.isEmpty() && (after.newTable || after.wholeErase != null) && wholeFs != null) listOf("The ${wholeFs.type.label} filesystem on the whole drive") else l
    }

    Page {
        TopBar("Review changes", d.title, onBack = { m.pop() })
        Panel {
            Txt("TARGET DRIVE", Raven.section)
            Gap(6)
            Txt(d.title, Raven.h2)
            val i = d.info
            KeyValue("Capacity", i.capacityBytes.valueOrNull?.let { "${Units.size(it)} (${p.snap.blockCount} sectors of $bs bytes)" } ?: "?")
            i.serial.valueOrNull?.let { KeyValue("Serial", it, mono = true) }
            KeyValue("Connection", listOfNotNull(i.connection.label, i.transport.valueOrNull).joinToString(", "))
            KeyValue("Device ID", d.key.substringAfter(':'), mono = true)
            Gap(4)
            Txt("Raven checks this is still the same, unchanged drive right before writing.", Raven.small)
        }
        Gap(12)
        Panel {
            Txt("NOW", Raven.section)
            Gap(6)
            PartitionMap(segments(p.base.table, p.base.volumes, bs), p.snap.blockCount, null, height = 28.dp)
            Gap(12)
            Txt("AFTER", Raven.section)
            Gap(6)
            PartitionMap(segments(after.table, after.volumes, bs), p.snap.blockCount, null, height = 28.dp)
        }
        SectionTitle("${ops.size} change${if (ops.size == 1) "" else "s"}, in this order")
        Panel {
            ops.forEachIndexed { i, op ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Txt("${i + 1}.", Raven.hint, Modifier.width(28.dp))
                    Txt(op.describe(bs), Raven.body.copy(color = if (op.destructive) Raven.Danger.copy(alpha = 0.95f) else Raven.Bone), Modifier.weight(1f))
                }
            }
        }
        if (lost.isNotEmpty()) {
            SectionTitle("Data that will be lost")
            Panel {
                lost.forEach { Txt("• $it", Raven.body) }
            }
        }
        Gap(16)
        if (destructive) {
            AckBox(if (whole) "I understand everything on this drive will be permanently lost. Once writing starts there's no undo." else "I understand the data listed above will be permanently lost. Once writing starts there's no undo.", ack) { ack = it }
        } else {
            Txt("These changes don't touch your files: they rewrite labels, names or flags only.", Raven.hint)
        }
        Gap(16)
        val busy = run?.running == true
        val ready = (!destructive || ack) && !busy
        if (busy) {
            Banner("Another operation is running. Wait for it to finish.", Tone.WARN)
            Gap(10)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietButton("Back to edit", Modifier.weight(1f)) { m.pop() }
            PrimaryButton("Apply ${ops.size} change${if (ops.size == 1) "" else "s"}", Modifier.weight(1.4f), enabled = ready, danger = destructive) {
                m.startRun(key)
            }
        }
        Gap(20)
    }
}
