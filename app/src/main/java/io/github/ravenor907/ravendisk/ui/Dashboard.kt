package io.github.ravenor907.ravendisk.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ravenor907.ravendisk.BuildConfig
import io.github.ravenor907.ravendisk.backend.AndroidVolume
import io.github.ravenor907.ravendisk.backend.Drive
import io.github.ravenor907.ravendisk.backend.DriveState
import io.github.ravenor907.ravendisk.core.health.HealthStatus
import io.github.ravenor907.ravendisk.core.model.MediaKind
import io.github.ravenor907.ravendisk.core.table.TableRead
import io.github.ravenor907.ravendisk.core.util.Units
import io.github.ravenor907.ravendisk.run.Runner

@Composable
fun DashboardScreen(m: AppModel) {
    val drives by m.drives.drives.collectAsState()
    val volumes by m.drives.volumes.collectAsState()
    val run by Runner.state.collectAsState()
    val ctx = LocalContext.current
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("RAVEN DISK UTILITY", Raven.brand)
                Gap(2)
                Txt("Drives", Raven.title)
            }
            LinkButton("About") { m.push(Screen.Capabilities) }
            LinkButton("Log") { m.push(Screen.Log) }
        }
        Gap(10)

        m.interrupted?.let { i ->
            Banner(
                "An operation on ${i.device} was interrupted (${i.started}) during \"${i.steps.lastOrNull() ?: "start"}\". " +
                    "The drive may be partly changed; open it to see what's on it now.",
                Tone.DANGER,
            ) {
                QuietButton("Details") { m.push(Screen.Log) }
                QuietButton("Dismiss") { m.acknowledgeInterrupted() }
            }
            Gap(10)
        }
        run?.let { r ->
            Banner(if (r.running) "Working on ${r.driveName}: ${r.stepTitle}" else "Finished on ${r.driveName}: ${r.result?.outcome?.label}", if (r.running) Tone.INFO else Tone.GOOD) {
                QuietButton("Open") { m.push(Screen.Run(r.driveKey)) }
            }
            Gap(10)
        }

        val usbDrives = drives.filter { !it.simulated }
        SectionTitle("External drives", trailing = { if (m.refreshing) Txt("Reading…", Raven.small) else LinkButton("Refresh") { m.refresh() } })
        if (!m.drives.usbAvailable || m.platform?.usbHost == false) {
            Banner("This device doesn't offer USB host mode to apps, so USB drives can't be opened here. Volume info below still works.", Tone.WARN)
            Gap(10)
        }
        if (usbDrives.isEmpty()) {
            EmptySlot("Plug in a USB drive, SSD, hard drive or card reader. A USB-C adapter or hub works.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                usbDrives.forEach { DriveCard(it, m) }
            }
        }

        val sims = drives.filter { it.simulated }
        if (sims.isNotEmpty()) {
            SectionTitle("Simulated drives", trailing = { Tag("In memory only", Raven.AuroraA) })
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { sims.forEach { DriveCard(it, m) } }
        }

        val androidMounted = volumes.filter { it.removable && it.mounted }
        if (androidMounted.isNotEmpty()) {
            SectionTitle("Mounted by Android")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidMounted.forEach { v -> VolumeCard(v) { m.push(Screen.Volume(v.id)) } }
            }
            Gap(8)
            Txt("Eject these in Settings to work on them here.", Raven.small)
        }
        Gap(18)
        Row(verticalAlignment = Alignment.Top) {
            Txt("●", Raven.small.copy(color = Raven.Good))
            HGap(8)
            Txt("Raven only works on drives you plug in. It never changes your phone's own storage, and nothing is written until you review and confirm.", Raven.small, Modifier.weight(1f))
        }
        Gap(20)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Version ${BuildConfig.VERSION_NAME}", Raven.small, Modifier.weight(1f))
            LinkButton("Storage settings") {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                } catch (e: Exception) {
                }
            }
        }
        Gap(12)
    }
}

@Composable
fun DriveCard(d: Drive, m: AppModel) {
    val ready = d.state == DriveState.READY && d.info != null
    Panel(onClick = {
        when (d.state) {
            DriveState.READY -> m.push(Screen.Device(d.key))
            DriveState.NEEDS_PERMISSION -> m.requestPermission(d.key)
            else -> {}
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DriveGlyph(d.info?.media?.valueOrNull ?: if (d.state == DriveState.NO_MEDIUM) MediaKind.SD_CARD else MediaKind.FLASH_DRIVE, hot = ready)
            HGap(14)
            Column(Modifier.weight(1f)) {
                Txt(d.title, Raven.h2, maxLines = 1)
                Gap(2)
                Txt(cardSubtitle(d), Raven.hint, maxLines = 2)
            }
            if (ready) d.info?.health?.let { h ->
                when (h.status) {
                    HealthStatus.GOOD -> Tag("Healthy", Raven.Good)
                    HealthStatus.WARNING -> Tag("Check health", Raven.Warn)
                    HealthStatus.FAILING -> Tag("Failing", Raven.Danger)
                    HealthStatus.UNKNOWN -> {}
                }
            }
        }
        if (ready) {
            val ins = d.inspection!!
            val t = (ins.table as? TableRead.Found)?.table
            Gap(12)
            val planner = m.planner(d.key)
            val vols = planner?.base?.volumes ?: emptyList()
            if (t != null) {
                PartitionMap(segments(t, vols, d.snapshot!!.blockSize), d.snapshot.blockCount, null, height = 18.dp)
            } else {
                PartitionMap(emptyList(), 1, null, height = 18.dp)
            }
            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(tableSummary(d), Raven.small, Modifier.weight(1f), maxLines = 1)
                if (d.snapshot!!.readOnly) Tag("Write-protected", Raven.Warn)
                val pending = planner?.queue?.size ?: 0
                if (pending > 0) {
                    HGap(6)
                    Tag("$pending pending", Raven.AuroraA)
                }
            }
        }
        when (d.state) {
            DriveState.ANDROID_MOUNTED -> {
                Gap(10)
                Txt(d.message ?: "", Raven.small)
                Gap(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ctx = LocalContext.current
                    QuietButton("Eject in Settings") {
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                        } catch (e: Exception) {
                        }
                    }
                    QuietButton("Open anyway") { m.inspectAnyway(d.key) }
                }
            }
            DriveState.ERROR, DriveState.UNSUPPORTED -> {
                Gap(8)
                Txt(d.message ?: "", Raven.small.copy(color = if (d.state == DriveState.ERROR) Raven.Danger else Raven.Ash))
                if (d.state == DriveState.ERROR) {
                    var open by androidx.compose.runtime.remember(d.details) { androidx.compose.runtime.mutableStateOf(false) }
                    val ctx = LocalContext.current
                    Gap(8)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietButton("Try again") { m.refresh(setOf(d.key)) }
                        if (d.details != null) {
                            QuietButton(if (open) "Hide details" else "Details") { open = !open }
                            QuietButton("Copy") {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Raven Disk Utility report", "${d.title}\n${d.message}\n\n${d.details}"))
                            }
                        }
                    }
                    if (open && d.details != null) {
                        Gap(10)
                        Panel(color = Raven.Ink, padding = 12.dp) { Txt(d.details, Raven.mono.copy(color = Raven.Ash, fontSize = Raven.small.fontSize)) }
                    }
                }
            }
            else -> {}
        }
    }
}

private fun cardSubtitle(d: Drive): String = when (d.state) {
    DriveState.READY -> {
        val i = d.info!!
        listOfNotNull(
            i.capacityBytes.valueOrNull?.let { Units.size(it) },
            i.connection.label,
            i.media.valueOrNull?.label,
        ).joinToString(" · ")
    }
    DriveState.NEEDS_PERMISSION -> "Tap to allow Raven to access this drive"
    DriveState.ANDROID_MOUNTED -> "In use by Android"
    DriveState.READING -> "Reading…"
    DriveState.NO_MEDIUM -> "No card in this slot"
    DriveState.UNSUPPORTED -> "Not supported"
    DriveState.ERROR -> "Couldn't read this drive"
}

fun tableSummary(d: Drive): String {
    val ins = d.inspection ?: return ""
    return when (val t = ins.table) {
        is TableRead.Found -> {
            val n = t.table.partitions.size
            "${t.table.kind.label} · $n partition${if (n == 1) "" else "s"}" + if (t.table.issues.isNotEmpty()) " · needs attention" else ""
        }
        TableRead.Blank -> "No partition table"
        TableRead.Superfloppy -> "No partition table · ${ins.wholeDeviceFs?.type?.label ?: "filesystem"} on the whole drive"
        is TableRead.Unreadable -> "Unrecognized layout"
    }
}

@Composable
fun VolumeCard(v: AndroidVolume, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DriveGlyph(
                when {
                    v.primary -> MediaKind.INTERNAL_STORAGE
                    v.looksLikeSdCard -> MediaKind.SD_CARD
                    else -> MediaKind.FLASH_DRIVE
                },
                hot = false,
            )
            HGap(14)
            Column(Modifier.weight(1f)) {
                Txt(v.description, Raven.h2, maxLines = 1)
                Gap(2)
                val bits = listOfNotNull(
                    v.totalBytes?.let { Units.size(it) },
                    v.fsType,
                    if (v.mounted) (if (v.readOnly) "Mounted read-only" else "Mounted") else stateLabel(v.state),
                )
                Txt(bits.joinToString(" · "), Raven.hint, maxLines = 1)
            }
            Tag("Android", Raven.Ash)
        }
        if (v.totalBytes != null && v.freeBytes != null && v.totalBytes > 0) {
            Gap(12)
            Meter(1f - v.freeBytes.toFloat() / v.totalBytes)
            Gap(6)
            Txt("${Units.size(v.freeBytes)} free of ${Units.size(v.totalBytes)}", Raven.small)
        }
    }
}

fun stateLabel(s: String) = when (s) {
    "unmounted" -> "Not mounted"
    "checking" -> "Being checked"
    "removed" -> "Removed"
    "ejecting" -> "Ejecting"
    "unmountable" -> "Can't be mounted"
    "nofs" -> "Blank or unsupported"
    "bad_removal" -> "Removed without ejecting"
    else -> s
}
