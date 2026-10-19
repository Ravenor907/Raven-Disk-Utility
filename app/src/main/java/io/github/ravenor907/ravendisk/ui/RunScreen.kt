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
        TopBar(if (result == null) "Applying changes" else "Result", r.driveName, onBack = if (result != null) ({ Runner.dismiss(); m.pop(); Unit }) else null)
        if (result == null) {
            Panel {
                Txt("${(r.fraction * 100).toInt()}%", Raven.big)
                Gap(4)
                Txt(if (r.stopping) "Stopping at the next safe point…" else r.stepTitle, Raven.h2)
                Txt("Step ${r.stepIndex + 1} of ${r.stepCount}", Raven.hint)
                Gap(14)
                ProgressBar(r.fraction, active = true)
                Gap(14)
                Banner("Keep the drive connected and leave the app open until this finishes. Unplugging now can leave the drive half-changed.", Tone.INFO)
                Gap(12)
                QuietButton(if (r.stopping) "Stopping…" else "Stop", Modifier.fillMaxWidth(), enabled = !r.stopping) { Runner.stop() }
                Gap(6)
                Txt("Stopping keeps whatever is already written; there's no rollback.", Raven.small)
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
            if (result.checks.isNotEmpty()) {
                SectionTitle("Verification")
                Panel {
                    result.checks.forEach { c ->
                        Txt(c, Raven.body.copy(color = when {
                            c.startsWith("✗") -> Raven.Danger
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuietButton("Operation log", Modifier.weight(1f)) { m.push(Screen.Log) }
                PrimaryButton("Back to drive", Modifier.weight(1.4f)) {
                    Runner.dismiss()
                    m.pop()
                }
            }
        }
        SectionTitle("Planned changes")
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
