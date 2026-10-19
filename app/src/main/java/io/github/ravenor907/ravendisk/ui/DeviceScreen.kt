package io.github.ravenor907.ravendisk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ravenor907.ravendisk.backend.Drive
import io.github.ravenor907.ravendisk.core.fs.FsSupport
import io.github.ravenor907.ravendisk.core.health.HealthReport
import io.github.ravenor907.ravendisk.core.health.HealthStatus
import io.github.ravenor907.ravendisk.core.model.Fact
import io.github.ravenor907.ravendisk.core.plan.EraseMethod
import io.github.ravenor907.ravendisk.core.plan.Op
import io.github.ravenor907.ravendisk.core.plan.PlanState
import io.github.ravenor907.ravendisk.core.plan.PlannedVolume
import io.github.ravenor907.ravendisk.core.plan.Planner
import io.github.ravenor907.ravendisk.core.plan.VolumeChange
import io.github.ravenor907.ravendisk.core.table.Align
import io.github.ravenor907.ravendisk.core.table.PartitionTypes
import io.github.ravenor907.ravendisk.core.table.TableKind
import io.github.ravenor907.ravendisk.core.table.TableRead
import io.github.ravenor907.ravendisk.core.util.Units

/** What a sheet edits. */
sealed class SheetKind {
    data class Create(val free: LongRange) : SheetKind()
    data class Format(val n: Int) : SheetKind()
    data class Label(val n: Int) : SheetKind()
    data class Name(val n: Int) : SheetKind()
    data class Type(val n: Int) : SheetKind()
    data class Flags(val n: Int) : SheetKind()
    data class Resize(val n: Int) : SheetKind()
    data class Erase(val n: Int?) : SheetKind()
    object NewTable : SheetKind()
    object QuickFormat : SheetKind()
}

@Composable
fun DeviceScreen(m: AppModel, key: String) {
    val drives by m.drives.drives.collectAsState()
    val d = drives.firstOrNull { it.key == key }
    if (d == null || d.info == null || d.snapshot == null) {
        Page {
            TopBar("Drive", onBack = { m.pop() })
            EmptySlot("This drive isn't available any more. It may have been unplugged.")
        }
        return
    }
    @Suppress("UNUSED_VARIABLE") val version = m.planVersion // recompose when the plan changes
    val planner = m.planner(key) ?: return
    val state = planner.current
    var selected by remember(key) { mutableStateOf<String?>(null) }
    var sheet by remember(key) { mutableStateOf(initialSheet(m, planner)) }
    var message by remember(key) { mutableStateOf<String?>(null) }

    fun queue(op: Op) {
        message = planner.problem(op)
        if (message == null) {
            planner.add(op)
            m.planChanged()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val segs = segments(state.table, state.volumes, planner.snap.blockSize)
                val sel = segs.firstOrNull { it.key == selected }
                if (wide) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        Column(Modifier.weight(1.1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 12.dp)) {
                            Header(m, d, planner)
                            FormatDriveCard(planner) { sheet = SheetKind.QuickFormat }
                            LayoutPanel(d, planner, state, selected) { selected = it }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 12.dp)) {
                            message?.let { Banner(it, Tone.DANGER); Gap(10) }
                            if (sel != null) SelectionPanel(planner, state, sel, { sheet = it }, ::queue)
                            DrivePanel(m, d, planner, { sheet = it })
                        }
                    }
                } else {
                    Page {
                        Header(m, d, planner)
                        FormatDriveCard(planner) { sheet = SheetKind.QuickFormat }
                        LayoutPanel(d, planner, state, selected) { selected = it }
                        message?.let { Gap(10); Banner(it, Tone.DANGER) }
                        if (sel != null) {
                            Gap(12)
                            SelectionPanel(planner, state, sel, { sheet = it }, ::queue)
                        }
                        DrivePanel(m, d, planner, { sheet = it })
                        Gap(16)
                    }
                }
            }
            if (planner.queue.isNotEmpty()) PendingBar(m, key, planner)
        }
        if (sheet == SheetKind.QuickFormat) {
            QuickFormatSheet(planner, onClose = { sheet = null }) { ops ->
                ops.forEach { planner.add(it) }
                m.planChanged()
                sheet = null
                m.push(Screen.Review(key))
            }
        } else sheet?.let { k ->
            PlanSheet(m, planner, k, onClose = { sheet = null }) { op ->
                planner.add(op)
                m.planChanged()
                message = null
                sheet = null
            }
        }
    }
}

private fun initialSheet(m: AppModel, p: Planner): SheetKind? {
    val demo = m.demoSheet ?: return null
    m.demoSheet = null
    val t = p.current.table
    return when (demo) {
        "create" -> SheetKind.NewTable
        "table" -> SheetKind.NewTable
        "format" -> SheetKind.Format(t?.partitions?.lastOrNull()?.number ?: 1)
        "erase" -> SheetKind.Erase(null)
        else -> null
    }
}

@Composable
private fun Header(m: AppModel, d: Drive, p: Planner) {
    val i = d.info!!
    TopBar(
        d.title,
        listOfNotNull(i.capacityBytes.valueOrNull?.let { Units.size(it) }, i.connection.label, i.media.valueOrNull?.label).joinToString(" · "),
        onBack = { m.pop() },
    ) {
        if (d.simulated) Tag("Simulated", Raven.AuroraA)
    }
    val notes = ArrayList<Pair<String, Tone>>()
    if (p.snap.readOnly) notes.add("This drive is write-protected. Check for a lock switch on the drive or card." to Tone.WARN)
    if (p.snap.deviceMounted) notes.add("Android is using the filesystem on this drive. Eject it in Settings before changing it." to Tone.WARN)
    if (p.snap.mounted.isNotEmpty()) notes.add("Android has partition ${p.snap.mounted.joinToString()} mounted. Those can't be changed until it's ejected." to Tone.WARN)
    when (val t = p.snap.table) {
        is TableRead.Found -> {
            if (t.table.damaged) notes.add("The partition table is damaged: ${t.table.issues.first()}. Changes are blocked until a new table is created." to Tone.DANGER)
            else t.table.issues.forEach { notes.add("$it. Writing any change repairs this." to Tone.INFO) }
            if (t.table.hasLogical) notes.add("This MBR drive has logical partitions. They're shown but can't be edited yet." to Tone.INFO)
        }
        TableRead.Blank -> notes.add("No partition table yet. Create one to start." to Tone.INFO)
        TableRead.Superfloppy -> notes.add("This drive has a filesystem with no partition table (${d.inspection?.wholeDeviceFs?.type?.label ?: "unknown"}). Creating a partition table erases it." to Tone.INFO)
        is TableRead.Unreadable -> notes.add("Raven doesn't recognize this drive's layout (${t.reason}). Creating a new table erases the drive." to Tone.WARN)
    }
    notes.forEach { (text, tone) ->
        Banner(text, tone)
        Gap(8)
    }
}

@Composable
private fun LayoutPanel(d: Drive, p: Planner, state: PlanState, selected: String?, onSelect: (String) -> Unit) {
    val bs = p.snap.blockSize
    val segs = segments(state.table, state.volumes, bs)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(
                state.table?.let { "${it.kind.label} partition table" } ?: if (state.wholeErase != null) "Whole drive erased" else "No partition table",
                Raven.h2, Modifier.weight(1f),
            )
            if (p.queue.isNotEmpty()) Tag("Planned", Raven.AuroraA) else Tag("Current", Raven.Ash)
        }
        Gap(12)
        PartitionMap(segs, p.snap.blockCount, selected, onSelect = onSelect)
        Gap(12)
        if (segs.isEmpty()) Txt(if (state.table == null) "Nothing to show until there's a partition table." else "The table is empty.", Raven.hint)
        segs.forEach { s -> SegmentRow(s, bs, s.key == selected) { onSelect(s.key) } }
        if (segs.any { !it.free }) {
            Gap(6)
            Txt("Tap a partition to format, rename or delete just that part.", Raven.small)
        }
    }
}

@Composable
private fun SegmentRow(s: io.github.ravenor907.ravendisk.ui.Segment, bs: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    var mod = Modifier.fillMaxWidth().clip(shape)
    if (selected) mod = mod.background(Raven.IronHi).border(1.dp, Raven.AuroraA.copy(alpha = 0.6f), shape)
    Row(mod.clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Swatch(segmentColor(s), s.free)
        HGap(12)
        Column(Modifier.weight(1f)) {
            val v = s.volume
            if (v == null) {
                Txt("Free space", Raven.body)
            } else {
                Txt(
                    "Partition ${v.partition.number} · ${fsName(v.fs)}" + (v.label?.let { " · $it" } ?: ""),
                    Raven.body, maxLines = 1,
                )
                val sub = listOfNotNull(v.partition.name.takeIf { it.isNotEmpty() }, v.partition.typeName(), if (v.partition.logical) "logical" else null)
                Txt(sub.joinToString(" · "), Raven.small, maxLines = 1)
            }
        }
        s.volume?.let { changeTag(it) }
        HGap(8)
        Txt(Units.sizeBinary(s.blocks * bs), Raven.hint)
    }
}

@Composable
private fun changeTag(v: PlannedVolume) {
    when (v.change) {
        VolumeChange.NEW -> Tag("New", Raven.AuroraA)
        VolumeChange.FORMATTED -> Tag("Format", Raven.Warn)
        VolumeChange.MODIFIED -> Tag("Changed", Raven.AuroraA)
        VolumeChange.ERASED -> Tag("Erase", Raven.Danger)
        VolumeChange.KEPT -> {}
    }
}

@Composable
private fun ActionRow(title: String, problem: String?, danger: Boolean = false, onClick: () -> Unit) {
    val enabled = problem == null
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, Raven.body.copy(color = when {
                !enabled -> Raven.Mist
                danger -> Raven.Danger
                else -> Raven.Bone
            }))
            if (problem != null) Txt(problem, Raven.small)
        }
        if (enabled) Txt("›", Raven.h2.copy(color = Raven.Ash))
    }
}

@Composable
private fun SelectionPanel(p: Planner, state: PlanState, s: io.github.ravenor907.ravendisk.ui.Segment, open: (SheetKind) -> Unit, queue: (Op) -> Unit) {
    val bs = p.snap.blockSize
    Panel {
        val v = s.volume
        if (v == null) {
            Txt("Free space", Raven.h2)
            Gap(6)
            KeyValue("Size", "${Units.sizeBinary(s.blocks * bs)} (${Units.size(s.blocks * bs)})")
            KeyValue("Sectors", "${s.first} – ${s.last}", mono = false)
            Gap(8)
            val place = Planner.placeInFree(s.first..s.last, bs, null, p.snap.physicalBlockSize)
            val problem = if (place == null) "Too small for an aligned partition" else p.problem(Op.Create(place.first, place.last, null))
            ActionRow("Create partition…", problem) { open(SheetKind.Create(s.first..s.last)) }
            return@Panel
        }
        val part = v.partition
        val n = part.number
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Partition $n", Raven.h2, Modifier.weight(1f))
            changeTag(v)
        }
        Gap(10)
        val fsCandidates0 = FsSupport.creatable.map { Op.Format(n, it) }
        PrimaryButton("Format this partition", Modifier.fillMaxWidth(), enabled = fsCandidates0.any { p.problem(it) == null }) { open(SheetKind.Format(n)) }
        Gap(10)
        KeyValue("Filesystem", fsName(v.fs), note = when {
            v.change == VolumeChange.NEW && v.fs != null -> "Will be created"
            v.change == VolumeChange.FORMATTED -> "Will be formatted (was ${v.before?.type?.label ?: "unformatted"})"
            v.change == VolumeChange.ERASED -> "Contents will be erased"
            else -> null
        })
        v.label?.let { KeyValue("Label", it, note = if (v.relabel) "Will be changed" else null) }
        v.before?.uuid?.let { if (v.change == VolumeChange.KEPT || v.change == VolumeChange.MODIFIED) KeyValue("Filesystem ID", it, mono = true) }
        v.before?.let { f ->
            if (v.change == VolumeChange.KEPT || v.change == VolumeChange.MODIFIED) {
                val size = f.sizeBytes
                val free = f.freeBytes
                if (size != null && free != null && size > 0) {
                    KeyValue("Used", "${Units.size(size - free)} of ${Units.size(size)}", note = if (f.freeIsHint) "From the filesystem's own free-space hint" else null)
                }
                f.detail?.let { KeyValue("Details", it) }
            }
        }
        KeyValue("Size", "${Units.sizeBinary(part.sizeBytes(bs))} (${Units.size(part.sizeBytes(bs))})")
        KeyValue("Sectors", "${part.firstLba} – ${part.lastLba}")
        KeyValue("Alignment", Align.describe(part.firstLba, bs, p.snap.physicalBlockSize))
        KeyValue("Type", part.typeName(), note = part.typeGuid?.text ?: part.mbrType?.let { "0x%02X".format(it) }, mono = false)
        if (state.table?.kind == TableKind.GPT) {
            KeyValue("Name", part.name.ifEmpty { "None" })
            part.uniqueGuid?.let { KeyValue("Partition GUID", it.text, mono = true) }
            val attrs = PartitionTypes.GPT_ATTRIBUTES.filter { (bit, _) -> part.attributes and (1L shl bit) != 0L }.map { it.second }
            KeyValue("Flags", attrs.joinToString().ifEmpty { "None" })
        } else {
            KeyValue("Active (bootable)", if (part.bootable) "Yes" else "No")
        }
        Gap(8)
        Txt("ACTIONS", Raven.section)
        Gap(4)
        val fsCandidates = FsSupport.creatable.map { Op.Format(n, it) }
        ActionRow("Format…", if (fsCandidates.any { p.problem(it) == null }) null else p.problem(fsCandidates.first())) { open(SheetKind.Format(n)) }
        if (v.fs != null) ActionRow("Change label…", p.problem(Op.SetLabel(n, v.label ?: ""))) { open(SheetKind.Label(n)) }
        if (state.table?.kind == TableKind.GPT) {
            ActionRow("Rename partition…", p.problem(Op.SetName(n, part.name))) { open(SheetKind.Name(n)) }
            ActionRow("Change type…", p.problem(Op.SetGptType(n, part.typeGuid ?: PartitionTypes.LINUX_FS))) { open(SheetKind.Type(n)) }
            ActionRow("Flags…", p.problem(Op.SetAttributes(n, part.attributes))) { open(SheetKind.Flags(n)) }
        } else {
            ActionRow("Change type…", p.problem(Op.SetMbrType(n, part.mbrType ?: 0x83))) { open(SheetKind.Type(n)) }
            ActionRow(if (part.bootable) "Clear active flag" else "Mark as active (bootable)", p.problem(Op.SetBootable(n, !part.bootable))) { queue(Op.SetBootable(n, !part.bootable)) }
        }
        ActionRow("Resize…", p.problem(Op.Resize(n, part.firstLba, part.lastLba))) { open(SheetKind.Resize(n)) }
        ActionRow("Erase…", p.problem(Op.Erase(n, EraseMethod.ZERO)), danger = true) { open(SheetKind.Erase(n)) }
        ActionRow("Delete partition", p.problem(Op.Delete(n)), danger = true) { queue(Op.Delete(n)) }
        Gap(4)
        Txt("Changes are queued, not written. You'll review them before anything touches the drive.", Raven.small)
    }
}

@Composable
private fun DrivePanel(m: AppModel, d: Drive, p: Planner, open: (SheetKind) -> Unit) {
    val i = d.info!!
    var advanced by remember(d.key) { mutableStateOf(false) }
    SectionTitle("Advanced", trailing = { LinkButton(if (advanced) "Hide" else "Show") { advanced = !advanced } })
    if (advanced) {
        Panel {
            ActionRow("New partition table…", p.problem(Op.NewTable(TableKind.GPT)), danger = true) { open(SheetKind.NewTable) }
            ActionRow("Securely erase the whole drive…", p.problem(Op.Erase(null, EraseMethod.ZERO)), danger = true) { open(SheetKind.Erase(null)) }
            ActionRow("Read the drive again", if (p.queue.isNotEmpty()) "Review or discard the pending changes first" else null) { m.refresh(setOf(d.key)) }
        }
    } else {
        Txt("Partition tables, secure erase, re-reading the drive.", Raven.small)
    }
    SectionTitle("Properties")
    Panel {
        KeyValue("Model", i.model)
        FactRow("Vendor", i.vendor) { it }
        FactRow("Serial number", i.serial) { it }
        FactRow("Capacity", i.capacityBytes) { "${Units.size(it)} (${Units.sizeBinary(it)})" }
        FactRow("Logical sector", i.logicalSector) { "$it bytes" }
        FactRow("Physical sector", i.physicalSector) { "$it bytes" }
        FactRow("Media", i.media) { it.label }
        KeyValue("Connection", i.connection.label)
        FactRow("Transport", i.transport) { it }
        FactRow("Removable media", i.removable) { if (it) "Yes" else "No" }
        FactRow("Write-protected", i.readOnly) { if (it) "Yes" else "No" }
        FactRow("Discard (TRIM)", i.discard) { if (it) "Supported" else "Not supported" }
        FactRow("Mounted by Android", i.mounted) { if (it.isEmpty()) "No" else it.joinToString() }
        when (val t = i.table) {
            is TableRead.Found -> {
                KeyValue("Partition table", t.table.kind.label)
                t.table.diskGuid?.let { KeyValue("Disk GUID", it.text, mono = true) }
                t.table.diskSignature?.let { KeyValue("Disk signature", "%08X".format(it), mono = true) }
                KeyValue("Usable sectors", "${t.table.firstUsable} – ${t.table.lastUsable}")
                if (t.table.kind == TableKind.GPT) KeyValue("Protective MBR", if (t.table.hybridMbr) "Hybrid" else if (t.table.protectiveMbr) "Present" else "Missing")
            }
            else -> KeyValue("Partition table", "None")
        }
        KeyValue("Access", i.backend)
    }
    SectionTitle("Health")
    HealthPanel(i.health)
}

@Composable
fun <T> FactRow(label: String, f: Fact<T>, show: (T) -> String) {
    when (f) {
        is Fact.Detected -> KeyValue(label, show(f.value))
        is Fact.Inferred -> KeyValue(label, show(f.value), note = "Inferred: ${f.reason}")
        is Fact.Unavailable -> KeyValue(label, "Not available", note = f.reason, valueColor = Raven.Mist)
    }
}

@Composable
fun HealthPanel(h: HealthReport?) {
    Panel {
        if (h == null || h.status == HealthStatus.UNKNOWN) {
            Txt("Not available", Raven.h2.copy(color = Raven.Ash))
            Gap(4)
            Txt(h?.source ?: "No health data", Raven.hint)
            return@Panel
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(h.status.label, Raven.h2.copy(color = when (h.status) {
                HealthStatus.GOOD -> Raven.Good
                HealthStatus.WARNING -> Raven.Warn
                else -> Raven.Danger
            }), Modifier.weight(1f))
            h.temperatureC?.let { Tag("$it °C", if (it >= 60) Raven.Warn else Raven.Ash) }
        }
        Gap(4)
        Txt(h.source, Raven.small)
        Gap(8)
        h.items.drop(1).forEach { KeyValue(it.name, it.value, note = it.note, valueColor = if (it.warn) Raven.Warn else Raven.Bone) }
        h.selfTest?.let { KeyValue("Self-test", it) }
    }
}

@Composable
private fun PendingBar(m: AppModel, key: String, p: Planner) {
    Box(Modifier.fillMaxWidth().background(Raven.Slate).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                val n = p.queue.size
                Txt("$n pending change${if (n == 1) "" else "s"}", Raven.h2)
                Txt("Nothing is written until you apply", Raven.small)
            }
            QuietButton("Undo") {
                p.undo()
                m.planChanged()
            }
            QuietButton("Discard") { m.discardPlan(key) }
            PrimaryButton("Review") { m.push(Screen.Review(key)) }
        }
    }
}

/** The one thing most people came to do, up front. */
@Composable
private fun FormatDriveCard(p: Planner, onClick: () -> Unit) {
    val blocked = when {
        p.snap.readOnly -> "This drive is write-protected"
        p.queue.isNotEmpty() -> "Review or discard the pending changes first"
        else -> null
    }
    Panel {
        Txt("Format drive", Raven.h2)
        Gap(4)
        Txt("Wipes the drive and sets it up fresh as one partition. Takes seconds.", Raven.hint)
        Gap(12)
        PrimaryButton("Format drive", Modifier.fillMaxWidth(), enabled = blocked == null, onClick = onClick)
        blocked?.let {
            Gap(6)
            Txt(it, Raven.small)
        }
    }
    Gap(12)
}
