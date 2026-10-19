package io.github.ravenor907.ravendisk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ravenor907.ravendisk.core.plan.Outcome
import io.github.ravenor907.ravendisk.run.Runner

@Composable
fun RunScreen(m: AppModel, key: String) {
    val s by Runner.state.collectAsState()
    val r = s
    if (r == null || r.driveKey != key) {
        Page {
            TopBar("Operation", onBack = { m.pop() })
            EmptySlot("No operation is running on this drive.")
        }
        return
    }
    val result = r.result
    Page {
        val tool = r.tool
        TopBar(if (result == null) (tool?.title ?: "Applying changes") else "Result", r.driveName, onBack = if (result != null) ({ Runner.dismiss(); m.pop(); Unit }) else null)
        if (result == null) {
            Panel {
                Txt("${(r.fraction * 100).toInt()}%", Raven.big)
                Gap(4)
                Txt(if (r.stopping) "Stopping at the next safe point…" else r.stepTitle, Raven.h2)
                Txt("Step ${r.stepIndex + 1} of ${r.stepCount}", Raven.hint)
                Gap(14)
                ProgressBar(r.fraction, active = true)
                if (r.total > 0) {
                    Gap(6)
                    Txt("%s of %s".format(io.github.ravenor907.ravendisk.core.util.Units.size(r.done), io.github.ravenor907.ravendisk.core.util.Units.size(r.total)), Raven.small)
                }
                Gap(14)
                Banner("Keep the drive connected and leave the app open until this finishes. Unplugging now can leave the drive half-changed.", Tone.INFO)
                Gap(12)
                QuietButton(if (r.stopping) "Stopping…" else "Stop", Modifier.fillMaxWidth(), enabled = !r.stopping) { Runner.stop() }
                Gap(6)
                Txt("Stopping keeps whatever is already written; there's no rollback.", Raven.small)
            }
            if (tool != null) {
                SectionTitle("Live speed")
                Panel { SpeedGraph(r.samples, live = true) }
            }
        } else {
            val (mark, color) = when (result.outcome) {
                Outcome.SUCCESS -> "ok" to Raven.Good
                Outcome.PARTIAL -> "warn" to Raven.Warn
                Outcome.FAILED -> "error" to Raven.Danger
                Outcome.CANCELLED -> "stopped" to Raven.Ash
            }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusMark(mark)
                    HGap(16)
                    Column(Modifier.weight(1f)) {
                        Txt(result.outcome.label, Raven.title.copy(color = color))
                        Gap(4)
                        Txt(result.summary, Raven.body)
                    }
                }
            }
            if (tool != null && r.samples.isNotEmpty()) {
                SectionTitle("Speed")
                Panel { SpeedGraph(r.samples, live = false) }
            }
            if (result.checks.isNotEmpty()) {
                SectionTitle(if (tool == io.github.ravenor907.ravendisk.run.ToolKind.SPEED_TEST) "Findings" else "Verification")
                Panel {
                    result.checks.forEach { c ->
                        Txt(c, Raven.body.copy(color = when {
                            c.startsWith("✗") || c.startsWith("FAKE") || c.startsWith("LOST") -> Raven.Danger
                            c.startsWith("✓") -> Raven.Bone
                            else -> Raven.Ash
                        }))
                        Gap(4)
                    }
                }
            }
            SectionTitle("Steps")
            Panel {
                result.steps.forEach { st ->
                    Row(verticalAlignment = Alignment.Top) {
                        Txt(if (st.ok) "✓" else "✗", Raven.body.copy(color = if (st.ok) Raven.Good else Raven.Danger))
                        HGap(10)
                        Column(Modifier.weight(1f)) {
                            Txt(st.title, Raven.body)
                            if (st.detail.isNotEmpty()) Txt(st.detail, Raven.small)
                        }
                    }
                    Gap(6)
                }
                if (result.steps.isEmpty()) Txt("No steps ran.", Raven.hint)
            }
            Gap(16)
            if (tool == io.github.ravenor907.ravendisk.run.ToolKind.SPEED_TEST || (tool != null && result.outcome != Outcome.SUCCESS)) {
                PrimaryButton("Format drive", Modifier.fillMaxWidth()) {
                    Runner.dismiss()
                    m.demoSheet = "quickformat"
                    m.pop()
                }
                Gap(10)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuietButton("Operation log", Modifier.weight(1f)) { m.push(Screen.Log) }
                PrimaryButton("Back to drive", Modifier.weight(1.4f)) {
                    Runner.dismiss()
                    m.pop()
                }
            }
        }
        SectionTitle(if (tool != null) "What this does" else "Planned changes")
        Panel { r.ops.forEachIndexed { i, o -> Txt("${i + 1}. $o", Raven.body); Gap(2) } }
        if (r.log.isNotEmpty()) {
            SectionTitle("Log")
            Panel(color = Raven.Ink) {
                r.log.takeLast(40).forEach { Txt(it, Raven.mono.copy(color = Raven.Ash)) }
            }
        }
        Gap(20)
    }
}
