package io.github.ravenor907.ravendisk.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ravenor907.ravendisk.backend.RootCheck
import io.github.ravenor907.ravendisk.core.fs.FsOp
import io.github.ravenor907.ravendisk.core.fs.FsSupport
import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.fs.Support
import io.github.ravenor907.ravendisk.core.plan.Outcome
import io.github.ravenor907.ravendisk.core.plan.TextJournal
import io.github.ravenor907.ravendisk.core.util.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VolumeScreen(m: AppModel, id: String) {
    val volumes by m.drives.volumes.collectAsState()
    val v = volumes.firstOrNull { it.id == id }
    val ctx = LocalContext.current
    Page {
        TopBar(v?.description ?: "Volume", "Managed by Android", onBack = { m.pop() })
        if (v == null) {
            EmptySlot("This volume isn't listed any more.")
            return@Page
        }
        Panel {
            KeyValue("Name", v.description)
            KeyValue("Kind", when {
                v.primary -> "Internal shared storage"
                v.looksLikeSdCard -> "SD card"
                v.removable -> "Removable (USB drive or card)"
                else -> "Storage"
            })
            KeyValue("State", if (v.mounted) (if (v.readOnly) "Mounted read-only" else "Mounted") else stateLabel(v.state))
            v.totalBytes?.let { KeyValue("Capacity", "${Units.size(it)} (${Units.sizeBinary(it)})", note = if (v.primary) "Total user-visible storage, from Android" else null) }
            v.freeBytes?.let { KeyValue("Free", Units.size(it)) }
            v.fsType?.let { KeyValue("Filesystem", it, note = "From Android's mount table") } ?: KeyValue("Filesystem", "Not available", note = "Android doesn't expose it for this volume", valueColor = Raven.Mist)
            v.uuid?.let { KeyValue("Filesystem ID", it, mono = true) }
            v.path?.let { KeyValue("Path", it, mono = true) }
            KeyValue("Emulated", if (v.emulated) "Yes (a view of internal storage)" else "No")
        }
        Gap(12)
        Banner(
            if (v.primary) "This is the phone's own storage. Raven never writes to it: its partitions belong to Android, and changing them can make the phone unbootable."
            else "Android has this volume mounted. To partition or format it with Raven, eject it in Settings first. USB drives then appear under USB drives; SD card slots need root (planned).",
            Tone.INFO,
        )
        Gap(12)
        QuietButton("Open storage settings", Modifier.fillMaxWidth()) {
            try {
                ctx.startActivity(Intent(if (v.primary) Settings.ACTION_INTERNAL_STORAGE_SETTINGS else Settings.ACTION_MEMORY_CARD_SETTINGS))
            } catch (e: Exception) {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                } catch (e2: Exception) {
                }
            }
        }
    }
}

@Composable
fun CapabilitiesScreen(m: AppModel) {
    val p = m.platform
    Page {
        TopBar("Capabilities", "What this phone lets Raven do", onBack = { m.pop() })
        Panel {
            Txt("THIS DEVICE", Raven.section)
            Gap(6)
            if (p == null) Txt("Reading…", Raven.hint) else {
                KeyValue("Device", p.device)
                KeyValue("Android", "${p.androidVersion} (API ${p.sdk})")
                KeyValue("Processor ABI", p.abi)
                KeyValue("USB host", if (p.usbHost) "Available" else "Not available", valueColor = if (p.usbHost) Raven.Good else Raven.Warn)
                KeyValue("su binary", if (p.suBinaries.isEmpty()) "Not found" else p.suBinaries.joinToString())
            }
        }
        SectionTitle("Access paths")
        Panel {
            PathRow("USB drives, directly", p?.usbHost != false, "Raven talks to USB drives itself (SCSI over USB, no root). Partition tables, FAT32, exFAT and swap, labels, erase and SMART on SATA bridges. Needs Bulk-Only Transport; UAS-only enclosures aren't reachable.")
            PathRow("Android's volumes", true, "Capacity, free space and filesystem for internal storage, SD cards and mounted USB drives. Read-only.")
            val rootLine = when (val r = m.root) {
                RootCheck.NotChecked -> if (p?.suBinaries?.isNotEmpty() == true) "An su binary is present. Tap below to ask for root." else "No su binary found."
                RootCheck.Checking -> "Waiting for the root prompt…"
                is RootCheck.Granted -> "Root granted (${r.detail}). Root-only operations (SD card slots, ext4/F2FS tools) arrive in a later version; nothing uses root yet."
                is RootCheck.Denied -> "Root not available: ${r.reason}"
            }
            PathRow("Root", m.root is RootCheck.Granted, rootLine)
            if (m.root !is RootCheck.Granted && m.root !is RootCheck.Checking) {
                Gap(8)
                QuietButton("Check root access", Modifier.fillMaxWidth()) { m.checkRoot() }
                Gap(4)
                Txt("Runs \"su -c id\" once, which shows your root manager's prompt. Nothing else runs as root.", Raven.small)
            }
        }
        if (p != null) {
            SectionTitle("Disk tools on this phone")
            Panel {
                p.tools.forEach { (tool, path) -> KeyValue(tool, path ?: "Not found", valueColor = if (path != null) Raven.Bone else Raven.Mist, mono = path != null) }
                Gap(4)
                Txt("Android ships some of these for its own use. They can only run against drives with root.", Raven.small)
            }
        }
        SectionTitle("Filesystems")
        Panel {
            val ops = listOf(FsOp.CREATE, FsOp.LABEL, FsOp.CHECK, FsOp.RESIZE)
            Row {
                Txt("", Raven.small, Modifier.weight(1.4f))
                ops.forEach { Txt(it.label, Raven.small.copy(textAlign = TextAlign.Center), Modifier.weight(1f)) }
            }
            Gap(6)
            val shown = listOf(FsType.EXFAT, FsType.FAT32, FsType.FAT16, FsType.SWAP, FsType.EXT4, FsType.EXT3, FsType.EXT2, FsType.NTFS, FsType.BTRFS, FsType.XFS, FsType.F2FS, FsType.UDF)
            shown.forEach { fs ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(fs.label, Raven.body.copy(fontSize = Raven.hint.fontSize), Modifier.weight(1.4f))
                    ops.forEach { op ->
                        val (mark, color) = when (FsSupport.of(fs, op)) {
                            Support.Native -> "✓" to Raven.Good
                            is Support.NeedsTool -> "root" to Raven.Ash
                            is Support.No -> "—" to Raven.Mist
                        }
                        Txt(mark, Raven.body.copy(color = color, textAlign = TextAlign.Center), Modifier.weight(1f))
                    }
                }
            }
            Gap(8)
            Txt("✓ built into Raven and available now. \"root\" needs the phone's own tools through root, planned for a later version. Every filesystem in the list can be inspected (type, label, ID, size).", Raven.small)
        }
        SectionTitle("Try it safely")
        Panel {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) { m.setSimulated(!m.simulated) }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckGlyph(m.simulated)
                HGap(12)
                Column(Modifier.weight(1f)) {
                    Txt("Show simulated drives", Raven.body)
                    Txt("Three made-up drives that live in memory, for trying every operation with nothing at stake.", Raven.small)
                }
            }
        }
        Gap(20)
    }
}

@Composable
private fun PathRow(title: String, on: Boolean, detail: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Txt(if (on) "●" else "○", Raven.body.copy(color = if (on) Raven.Good else Raven.Mist), Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Raven.body)
            Txt(detail, Raven.small)
        }
    }
}

@Composable
fun LogScreen(m: AppModel) {
    var runs by remember { mutableStateOf<List<TextJournal.Run>?>(null) }
    LaunchedEffect(Unit) { runs = withContext(Dispatchers.IO) { m.journal.runs() } }
    Page {
        TopBar("Operation log", "Every write Raven makes, newest first", onBack = { m.pop() })
        val list = runs
        when {
            list == null -> Txt("Reading…", Raven.hint)
            list.isEmpty() -> EmptySlot("Nothing has been written yet.")
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.take(50).forEach { r ->
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(r.device, Raven.h2, Modifier.weight(1f), maxLines = 1)
                            when (r.outcome) {
                                Outcome.SUCCESS -> Tag("Done", Raven.Good)
                                Outcome.PARTIAL -> Tag("Warnings", Raven.Warn)
                                Outcome.FAILED -> Tag("Failed", Raven.Danger)
                                Outcome.CANCELLED -> Tag("Stopped", Raven.Ash)
                                null -> Tag("Interrupted", Raven.Danger)
                            }
                        }
                        Txt(r.started, Raven.small)
                        Gap(6)
                        r.ops.forEach { Txt("• $it", Raven.body.copy(fontSize = Raven.hint.fontSize)) }
                        r.summary?.let {
                            Gap(6)
                            Txt(it, Raven.hint)
                        }
                        if (r.outcome == null && r.steps.isNotEmpty()) {
                            Gap(6)
                            Txt("Stopped during: ${r.steps.last()}", Raven.small.copy(color = Raven.Danger))
                        }
                        r.notes.forEach { Txt(it, Raven.small) }
                    }
                }
            }
        }
        Gap(20)
    }
}
