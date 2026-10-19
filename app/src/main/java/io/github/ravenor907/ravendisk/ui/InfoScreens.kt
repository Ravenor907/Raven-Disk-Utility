package io.github.ravenor907.ravendisk.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
            "Android has this drive mounted. To work on it, open the drive under Drives and tap Unmount.",
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
        TopBar("About this phone", "What Raven can do here", onBack = { m.pop() })
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("External drives only", Raven.h2)
                    Txt("Raven works on drives you plug in over USB. It never changes your phone's own storage, and it never asks for root.", Raven.small)
                }
            }
        }
        Gap(12)
        Panel {
            if (p == null) Txt("Reading…", Raven.hint) else {
                KeyValue("Device", p.device)
                KeyValue("Android", "${p.androidVersion} (API ${p.sdk})")
                KeyValue(
                    "USB drives", if (p.usbHost) "Supported" else "Not supported on this device",
                    note = if (p.usbHost) "Through a USB-C port, adapter or hub" else "This phone doesn't let apps talk to USB devices",
                    valueColor = if (p.usbHost) Raven.Good else Raven.Warn,
                )
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
            val shown = listOf(FsType.EXFAT, FsType.FAT32, FsType.EXT4, FsType.SWAP, FsType.NTFS, FsType.FAT16, FsType.EXT3, FsType.EXT2, FsType.BTRFS, FsType.XFS, FsType.F2FS)
            shown.forEach { fs ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(fs.label, Raven.body.copy(fontSize = Raven.hint.fontSize), Modifier.weight(1.4f))
                    ops.forEach { op ->
                        val (mark, color) = when (FsSupport.of(fs, op)) {
                            Support.Native -> "✓" to Raven.Good
                            is Support.Planned -> "Soon" to Raven.Ash
                            is Support.No -> "—" to Raven.Mist
                        }
                        Txt(mark, Raven.body.copy(color = color, textAlign = TextAlign.Center, fontSize = Raven.hint.fontSize), Modifier.weight(1f))
                    }
                }
            }
            Gap(8)
            Txt("Every filesystem listed can be inspected: type, label, ID and size.", Raven.small)
        }
        SectionTitle("Try it safely")
        Panel {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) { m.showSimulated(!m.simulated) }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckGlyph(m.simulated)
                HGap(12)
                Column(Modifier.weight(1f)) {
                    Txt("Show simulated drives", Raven.body)
                    Txt("Three made-up drives that live in memory, for trying everything with nothing at stake.", Raven.small)
                }
            }
        }
        Gap(20)
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
