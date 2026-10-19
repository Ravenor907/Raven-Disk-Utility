package io.github.ravenor907.ravendisk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ravenor907.ravendisk.core.fs.FsSupport
import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.plan.EraseMethod
import io.github.ravenor907.ravendisk.core.plan.Op
import io.github.ravenor907.ravendisk.core.plan.Planner
import io.github.ravenor907.ravendisk.core.table.Align
import io.github.ravenor907.ravendisk.core.table.PartitionTypes
import io.github.ravenor907.ravendisk.core.table.TableKind
import io.github.ravenor907.ravendisk.core.util.Guid
import io.github.ravenor907.ravendisk.core.util.Units

private fun fsDetail(fs: FsType?): String = when (fs) {
    FsType.EXFAT -> "Works on Windows, macOS, Linux, Android and consoles; no 4 GB file limit"
    FsType.FAT32 -> "Most compatible (firmware, EFI, old devices); files up to 4 GB"
    FsType.EXT4 -> "For Linux: Steam Deck, Bazzite, SteamOS game drives. Windows and macOS can't read it"
    FsType.SWAP -> "Swap space for a Linux system"
    null -> "Leave it empty; format it later or from another system"
    else -> ""
}

/** Size entry in MiB or GiB, returned in bytes (null when unparsable). */
@Composable
private fun SizeField(bytes: Long?, max: Long, onChange: (Long?) -> Unit) {
    var gib by remember { mutableStateOf(max >= 4L * Units.GiB) }
    var text by remember { mutableStateOf(bytes?.let { fmtSize(it, gib) } ?: "") }
    val unit = if (gib) Units.GiB else Units.MiB
    Column {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(
                text, {
                    text = it
                    onChange(parse(it, unit))
                },
                "Size", Modifier.weight(1f), number = true, suffix = if (gib) "GiB" else "MiB",
            )
        }
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("MiB", !gib) {
                gib = false
                text = parse(text, Units.GiB)?.let { fmtSize(it, false) } ?: text
            }
            Choice("GiB", gib) {
                gib = true
                text = parse(text, Units.MiB)?.let { fmtSize(it, true) } ?: text
            }
            Choice("All (${Units.sizeBinary(max)})", false) {
                text = fmtSize(max, gib)
                onChange(max)
            }
        }
    }
}

private fun parse(s: String, unit: Long): Long? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * unit).toLong() }
private fun fmtSize(b: Long, gib: Boolean): String {
    val v = if (gib) b.toDouble() / Units.GiB else b.toDouble() / Units.MiB
    return if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
}

@Composable
private fun FsChoices(selected: FsType?, allowNone: Boolean, problemFor: (FsType?) -> String?, onPick: (FsType?) -> Unit) {
    val opts: List<FsType?> = FsSupport.creatable + if (allowNone) listOf(null) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { fs ->
            val problem = problemFor(fs)
            Choice(fs?.label ?: "No filesystem", selected == fs, Modifier.fillMaxWidth(), enabled = problem == null || selected == fs, detail = problem ?: fsDetail(fs)) { onPick(fs) }
        }
    }
    Gap(6)
    Txt("NTFS, Btrfs, XFS and F2FS can be read but not created yet; see Capabilities.", Raven.small)
}

@Composable
private fun SheetButtons(confirm: String, problem: String?, danger: Boolean = false, onClose: () -> Unit, onConfirm: () -> Unit) {
    if (problem != null) {
        Gap(10)
        Txt(problem, Raven.small.copy(color = Raven.Danger))
    }
    Gap(16)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietButton("Cancel", Modifier.weight(1f), onClick = onClose)
        PrimaryButton(confirm, Modifier.weight(1.4f), enabled = problem == null, danger = danger, onClick = onConfirm)
    }
}

@Composable
fun PlanSheet(@Suppress("UNUSED_PARAMETER") m: AppModel, p: Planner, kind: SheetKind, onClose: () -> Unit, onAdd: (Op) -> Unit) {
    val bs = p.snap.blockSize
    val state = p.current
    val table = state.table
    when (kind) {
        is SheetKind.Create -> Sheet("New partition", onClose) {
            val free = kind.free
            val maxBytes = run {
                val r = Planner.placeInFree(free, bs, null, p.snap.physicalBlockSize)
                if (r == null) 0L else (r.last - r.first + 1) * bs
            }
            var size by remember { mutableStateOf<Long?>(maxBytes) }
            var fs by remember { mutableStateOf<FsType?>(FsType.EXFAT) }
            var label by remember { mutableStateOf("") }
            var name by remember { mutableStateOf("") }
            var efi by remember { mutableStateOf(false) }
            Txt("In ${Units.sizeBinary((free.last - free.first + 1) * bs)} of free space. The start is aligned to 1 MiB.", Raven.hint)
            Gap(14)
            SizeField(size, maxBytes) { size = it }
            fun op(f: FsType?): Op? {
                val r = Planner.placeInFree(free, bs, size, p.snap.physicalBlockSize) ?: return null
                val gpt = if (efi && f == FsType.FAT32) PartitionTypes.EFI_SYSTEM else null
                val mbr = if (efi && f == FsType.FAT32) 0xEF else null
                return Op.Create(r.first, r.last, f, label.trim(), name.trim(), gpt, mbr)
            }
            Gap(14)
            Txt("Filesystem", Raven.small)
            Gap(6)
            FsChoices(fs, true, { f -> op(f)?.let { p.problem(it) } ?: "Enter a size" }) { fs = it }
            val f = fs
            if (f != null && f.maxLabel > 0) {
                Gap(14)
                Field(label, { label = it }, "Filesystem label", placeholder = if (f == FsType.FAT32) "UP TO 11 CHARACTERS" else "Optional")
            }
            if (table?.kind == TableKind.GPT) {
                Gap(14)
                Field(name, { name = it }, "Partition name (GPT)", placeholder = "Optional")
            }
            if (f == FsType.FAT32) {
                Gap(14)
                AckBox("Use as an EFI system partition (boot files for UEFI)", efi) { efi = it }
            }
            val o = op(fs)
            SheetButtons("Add to plan", if (o == null) "Enter a size that fits" else p.problem(o), onClose = onClose) { o?.let(onAdd) }
        }

        is SheetKind.Format -> Sheet("Format partition ${kind.n}", onClose) {
            val v = state.volume(kind.n)
            var fs by remember { mutableStateOf(FsSupport.creatable.firstOrNull { p.problem(Op.Format(kind.n, it)) == null } ?: FsType.EXFAT) }
            var label by remember { mutableStateOf(v?.label ?: "") }
            val lost = v?.before?.let { "${it.type.label}" + (it.label?.let { l -> " \"$l\"" } ?: "") }
            Banner(
                "Formatting replaces everything in this partition" + (lost?.let { " (currently $it)" } ?: "") + ". This is a quick format: old data isn't overwritten, so it isn't a secure erase.",
                Tone.WARN,
            )
            Gap(14)
            FsChoices(fs, false, { f -> f?.let { p.problem(Op.Format(kind.n, it)) } }) { it?.let { x -> fs = x } }
            if (fs.maxLabel > 0) {
                Gap(14)
                Field(label, { label = it }, "Filesystem label", placeholder = "Optional")
            }
            val o = Op.Format(kind.n, fs, label.trim())
            SheetButtons("Add to plan", p.problem(o), danger = true, onClose = onClose) { onAdd(o) }
        }

        is SheetKind.Label -> Sheet("Filesystem label", onClose) {
            val v = state.volume(kind.n)
            var label by remember { mutableStateOf(v?.label ?: "") }
            Txt("${v?.fs?.label ?: "Filesystem"} labels are at most ${v?.fs?.maxLabel ?: 11} characters. Leave empty to clear it.", Raven.hint)
            Gap(12)
            Field(label, { label = it }, "Label")
            val o = Op.SetLabel(kind.n, label.trim())
            SheetButtons("Add to plan", p.problem(o), onClose = onClose) { onAdd(o) }
        }

        is SheetKind.Name -> Sheet("Partition name", onClose) {
            val v = state.volume(kind.n)
            var name by remember { mutableStateOf(v?.partition?.name ?: "") }
            Txt("GPT partition names are separate from filesystem labels; Linux shows them as PARTLABEL.", Raven.hint)
            Gap(12)
            Field(name, { name = it }, "Name")
            val o = Op.SetName(kind.n, name.trim())
            SheetButtons("Add to plan", p.problem(o), onClose = onClose) { onAdd(o) }
        }

        is SheetKind.Type -> Sheet("Partition type", onClose) {
            val v = state.volume(kind.n)
            if (table?.kind == TableKind.GPT) {
                var pick by remember { mutableStateOf<Guid?>(v?.partition?.typeGuid) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PartitionTypes.GPT_CHOICES.forEach { (g, n) -> Choice(n, pick == g, Modifier.fillMaxWidth(), detail = g.text) { pick = g } }
                }
                val o = pick?.let { Op.SetGptType(kind.n, it) }
                SheetButtons("Add to plan", o?.let { p.problem(it) } ?: "Pick a type", onClose = onClose) { o?.let(onAdd) }
            } else {
                var pick by remember { mutableStateOf(v?.partition?.mbrType ?: 0x83) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PartitionTypes.MBR_CHOICES.forEach { (t, n) -> Choice(n, pick == t, Modifier.fillMaxWidth(), detail = "0x%02X".format(t)) { pick = t } }
                }
                val o = Op.SetMbrType(kind.n, pick)
                SheetButtons("Add to plan", p.problem(o), onClose = onClose) { onAdd(o) }
            }
            Gap(6)
            Txt("The type tells operating systems what the partition is for. It doesn't change the data inside.", Raven.small)
        }

        is SheetKind.Flags -> Sheet("Flags", onClose) {
            val v = state.volume(kind.n)
            var attrs by remember { mutableLongStateOf(v?.partition?.attributes ?: 0L) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PartitionTypes.GPT_ATTRIBUTES.forEach { (bit, name) ->
                    val on = attrs and (1L shl bit) != 0L
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).clickable(role = Role.Checkbox) { attrs = attrs xor (1L shl bit) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CheckGlyph(on)
                        HGap(12)
                        Column(Modifier.weight(1f)) {
                            Txt(name, Raven.body)
                            Txt("Bit $bit", Raven.small)
                        }
                    }
                }
            }
            Gap(4)
            Txt("Bits 60, 62 and 63 apply to Windows basic data partitions.", Raven.small)
            val o = Op.SetAttributes(kind.n, attrs)
            SheetButtons("Add to plan", p.problem(o), onClose = onClose) { onAdd(o) }
        }

        is SheetKind.Resize -> Sheet("Resize partition ${kind.n}", onClose) {
            val v = state.volume(kind.n) ?: return@Sheet
            val part = v.partition
            val next = table?.partitions?.filter { it.firstLba > part.lastLba }?.minOfOrNull { it.firstLba } ?: ((table?.lastUsable ?: part.lastLba) + 1)
            val maxBytes = (next - part.firstLba) * bs
            var size by remember { mutableStateOf<Long?>(part.sizeBytes(bs)) }
            Txt("Now ${Units.sizeBinary(part.sizeBytes(bs))}; up to ${Units.sizeBinary(maxBytes)} fits here. The start stays put.", Raven.hint)
            Gap(14)
            SizeField(size, maxBytes) { size = it }
            val o = size?.let { s ->
                val blocks = s / bs
                val mib = Align.mibBlocks(bs)
                var last = part.firstLba + blocks - 1
                // End on a MiB boundary unless that runs into the next partition.
                val aligned = Align.up(last + 1, mib) - 1
                if (aligned < next) last = aligned
                Op.Resize(kind.n, part.firstLba, minOf(last, next - 1))
            }
            SheetButtons("Add to plan", o?.let { p.problem(it) } ?: "Enter a size", onClose = onClose) { o?.let(onAdd) }
        }

        is SheetKind.Erase -> Sheet(if (kind.n == null) "Erase the whole drive" else "Erase partition ${kind.n}", onClose) {
            var method by remember { mutableStateOf(EraseMethod.ZERO) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EraseMethod.entries.forEach { e ->
                    val problem = p.problem(Op.Erase(kind.n, e))
                    Choice(e.label, method == e, Modifier.fillMaxWidth(), enabled = problem == null, detail = problem ?: e.description) { method = e }
                }
            }
            Gap(10)
            Txt(
                "Hardware secure erase (ATA Security Erase, NVMe Sanitize) isn't offered: through USB bridges it can lock or brick drives. " +
                    "For SSDs that hold sensitive data, use the drive maker's tool on a computer.",
                Raven.small,
            )
            if (kind.n == null) {
                Gap(10)
                Txt("Erasing the whole drive removes the partition table too. Add a new table to the plan afterwards to use the drive again.", Raven.small)
            }
            val o = Op.Erase(kind.n, method)
            SheetButtons("Add to plan", p.problem(o), danger = true, onClose = onClose) { onAdd(o) }
        }

        SheetKind.QuickFormat, SheetKind.SpeedTest, is SheetKind.WriteImage -> {} // shown by their own sheets
        SheetKind.NewTable -> Sheet("New partition table", onClose) {
            var kindPick by remember { mutableStateOf(TableKind.GPT) }
            Banner("A new partition table replaces the current one. Every partition on the drive, and the data in them, will be gone.", Tone.DANGER)
            Gap(14)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in TableKind.entries) {
                    val problem = p.problem(Op.NewTable(k))
                    val detail = when (k) {
                        TableKind.GPT -> "Modern standard: up to 128 partitions, any size, names, backup copy. Best for UEFI PCs and handhelds."
                        TableKind.MBR -> "Older standard: 4 partitions, drives up to 2 TiB. For old PCs, some cameras and car stereos."
                    }
                    Choice(k.label, kindPick == k, Modifier.fillMaxWidth(), enabled = problem == null, detail = problem ?: detail) { kindPick = k }
                }
            }
            val o = Op.NewTable(kindPick)
            SheetButtons("Add to plan", p.problem(o), danger = true, onClose = onClose) { onAdd(o) }
        }
    }
}

/** Whole-drive format: a new table and one partition with the chosen filesystem, reviewed next. */
@Composable
fun QuickFormatSheet(p: Planner, onClose: () -> Unit, onDone: (List<Op>) -> Unit) {
    Sheet("Format drive", onClose) {
        var fs by remember { mutableStateOf(FsType.EXFAT) }
        var label by remember { mutableStateOf("") }
        // The same plan Handheld Forge uses (shared engine).
        fun plan(f: FsType) = io.github.ravenor907.ravendisk.core.plan.QuickFormat.plan(p.snap, f, label)
        Banner("Everything on this drive will be erased.", Tone.DANGER)
        Gap(14)
        Txt("Use it with", Raven.small)
        Gap(6)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            io.github.ravenor907.ravendisk.core.plan.QuickFormat.CHOICES.forEach { (f, why) ->
                val problem = plan(f).second
                Choice(f.label, fs == f, Modifier.fillMaxWidth(), enabled = problem == null, detail = problem ?: why) { fs = f }
            }
        }
        Gap(14)
        Field(label, { label = it }, "Name (optional)", placeholder = if (fs == FsType.FAT32) "UP TO 11 CHARACTERS" else "My drive")
        val (ops, problem) = plan(fs)
        SheetButtons("Continue", problem, danger = true, onClose = onClose) { ops?.let(onDone) }
    }
}

@Composable
fun SpeedTestSheet(p: Planner, onClose: () -> Unit, onStart: () -> Unit) {
    Sheet("Speed and capacity test", onClose) {
        var ack by remember { mutableStateOf(false) }
        Banner("This writes test data over the whole drive. Everything on it will be erased.", Tone.DANGER)
        Gap(12)
        Txt("About two to four minutes. A live graph shows the speed as it happens: a fast start that drops means a small cache, dips mean the drive is struggling.", Raven.hint)
        Gap(8)
        Txt("Markers are written right up to the last megabyte, so a fake drive that claims more space than it has gets caught.", Raven.hint)
        Gap(14)
        AckBox("I understand everything on this drive will be permanently lost.", ack) { ack = it }
        SheetButtons("Start test", if (ack) null else "", danger = true, onClose = onClose, onConfirm = onStart)
    }
}

@Composable
fun WriteImageSheet(p: Planner, img: io.github.ravenor907.ravendisk.run.PickedImage, onClose: () -> Unit, onStart: () -> Unit) {
    Sheet("Write image", onClose) {
        var ack by remember { mutableStateOf(false) }
        Panel(color = Raven.Iron) {
            Txt(img.name, Raven.body)
            Gap(2)
            Txt("${img.kind.label} · ${if (img.size >= 0) Units.size(img.size) else "size unknown"}", Raven.small)
        }
        img.kind.warning?.let {
            Gap(10)
            Banner(it, Tone.WARN)
        }
        Gap(10)
        Banner("Everything on this drive will be erased and replaced by the image.", Tone.DANGER)
        Gap(12)
        Txt(if (img.kind.compressed) "It's unpacked while it's written, then the whole drive is read back and compared." else "When it's written, the whole image is read back from the drive and compared.", Raven.hint)
        Gap(14)
        val problem = io.github.ravenor907.ravendisk.run.Tools.imageProblem(img, p.snap)
        if (problem == null) AckBox("I understand everything on this drive will be permanently lost.", ack) { ack = it }
        SheetButtons("Write image", problem ?: if (ack) null else "", danger = true, onClose = onClose, onConfirm = onStart)
    }
}
