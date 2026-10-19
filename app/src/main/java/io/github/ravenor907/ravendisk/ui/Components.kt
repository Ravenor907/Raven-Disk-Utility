package io.github.ravenor907.ravendisk.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Txt(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int) = Spacer(Modifier.width(w.dp))

/** A rounded surface. Selected = copper edge. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    color: Color = Raven.Slate,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    var m = modifier.clip(shape).background(color)
    m = if (selected) m.border(2.dp, Raven.accent, shape) else m.border(1.dp, Raven.Seam.copy(alpha = 0.5f), shape)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Column(m.padding(padding), content = content)
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(14.dp)
    val bg = when {
        !enabled -> Modifier.background(Raven.Iron, shape)
        danger -> Modifier.background(Raven.Danger, shape)
        else -> Modifier.background(Raven.accent, shape)
    }
    Box(
        modifier.heightIn(min = 52.dp).clip(shape).then(bg).alpha(if (pressed) 0.85f else 1f)
            .clickable(enabled = enabled, interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, TextStyle(color = if (enabled) Raven.Ink else Raven.Mist, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
    }
}

@Composable
fun QuietButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = 44.dp).clip(shape).border(1.dp, Raven.Seam, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, TextStyle(color = if (enabled) Raven.Bone else Raven.Mist, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
    }
}

/** Plain text button for top bars. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Txt(text, TextStyle(color = Raven.CopperHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** Selectable choice in a row of options. */
@Composable
fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, detail: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    var m = modifier.clip(shape).background(if (selected) Raven.IronHi else Raven.Iron)
    m = if (selected) m.border(2.dp, Raven.accent, shape) else m.border(1.dp, Raven.Seam, shape)
    Column(
        m.clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick).alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Txt(text, TextStyle(color = Raven.Bone, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
        if (detail != null) Txt(detail, Raven.small)
    }
}

@Composable
fun Tag(text: String, color: Color = Raven.Ash, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Txt(text, TextStyle(color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

enum class Tone { INFO, WARN, DANGER, GOOD }

fun toneColor(t: Tone) = when (t) {
    Tone.INFO -> Raven.CopperHi
    Tone.WARN -> Raven.Warn
    Tone.DANGER -> Raven.Danger
    Tone.GOOD -> Raven.Good
}

/** A message strip with an optional row of actions under it. */
@Composable
fun Banner(text: String, tone: Tone = Tone.WARN, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Raven.Iron).border(1.dp, toneColor(tone).copy(alpha = 0.55f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Txt(text, Raven.body.copy(fontSize = 14.sp, lineHeight = 19.sp))
        if (actions != null) {
            Gap(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text.uppercase(), Raven.section, Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** label: value, with an optional note under the value (source, inference, reason). */
@Composable
fun KeyValue(label: String, value: String, note: String? = null, valueColor: Color = Raven.Bone, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Txt(label, Raven.hint, Modifier.width(132.dp))
        Column(Modifier.weight(1f)) {
            Txt(value, (if (mono) Raven.mono else Raven.body.copy(fontSize = 14.sp)).copy(color = valueColor))
            if (note != null) Txt(note, Raven.small)
        }
    }
}

/** Progress bar with a slow shimmer while active. */
@Composable
fun ProgressBar(progress: Float, active: Boolean, modifier: Modifier = Modifier, color: Brush = Raven.accent) {
    val phase by rememberInfiniteTransition(label = "shine").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Raven.Iron, cornerRadius = r)
        val w = size.width * progress.coerceIn(0f, 1f)
        if (w <= 0f) return@Canvas
        drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
        if (active) {
            val x = (phase * 1.6f - 0.3f) * size.width
            val shine = Brush.horizontalGradient(listOf(Color(0x00FFFFFF), Color(0x55FFFFFF), Color(0x00FFFFFF)), x - 120f, x + 120f)
            drawRoundRect(shine, size = Size(w, size.height), cornerRadius = r)
        }
    }
}

/** Usage meter: used part in [color], the rest as track. */
@Composable
fun Meter(used: Float, modifier: Modifier = Modifier, color: Color = Raven.CopperHi) {
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Raven.Iron, cornerRadius = r)
        val w = size.width * used.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
    }
}

/** Ring with a check (ok), a bang (error) or a bar (stopped). */
@Composable
fun StatusMark(kind: String, size: Dp = 56.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 64f
        val brush = when (kind) {
            "ok" -> Raven.accent
            "warn" -> SolidColor(Raven.Warn)
            "error" -> SolidColor(Raven.Danger)
            else -> SolidColor(Raven.Ash)
        }
        drawCircle(brush, 28 * u, center, style = Stroke(4 * u))
        val st = Stroke(5 * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "ok" -> drawPath(Path().apply { moveTo(20 * u, 33 * u); lineTo(28 * u, 41 * u); lineTo(44 * u, 24 * u) }, brush, style = st)
            "error", "warn" -> {
                drawLine(brush, Offset(32 * u, 19 * u), Offset(32 * u, 35 * u), 5 * u, StrokeCap.Round)
                drawCircle(brush, 3 * u, Offset(32 * u, 44 * u))
            }
            else -> drawLine(brush, Offset(22 * u, 32 * u), Offset(42 * u, 32 * u), 5 * u, StrokeCap.Round)
        }
    }
}

/** A tick box the person has to tick themselves. */
@Composable
fun AckBox(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Raven.Iron)
            .then(if (checked) Modifier.border(2.dp, Raven.accent, shape) else Modifier.border(1.dp, Raven.Seam, shape))
            .clickable(role = Role.Checkbox) { onChange(!checked) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckGlyph(checked)
        HGap(14)
        Txt(text, TextStyle(color = Raven.Bone, fontSize = 14.sp, lineHeight = 19.sp), Modifier.weight(1f))
    }
}

@Composable
fun CheckGlyph(checked: Boolean, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val r = CornerRadius(5 * u)
        if (checked) {
            drawRoundRect(Raven.accent, cornerRadius = r)
            drawPath(
                Path().apply { moveTo(6 * u, 12.5f * u); lineTo(10 * u, 16.5f * u); lineTo(18 * u, 8 * u) }, Raven.Ink,
                style = Stroke(2.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        } else {
            drawRoundRect(Raven.Ash, cornerRadius = r, style = Stroke(2 * u))
        }
    }
}

/** Single-line text input in the app's style. */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    number: Boolean = false,
    error: String? = null,
    suffix: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Txt(label, Raven.small)
        Gap(6)
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(Raven.Iron)
                .border(1.dp, if (error != null) Raven.Danger else Raven.Seam, shape).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, Raven.body.copy(color = Raven.Mist))
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = Raven.body,
                    cursorBrush = SolidColor(Raven.CopperHi),
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (suffix != null) {
                HGap(8)
                Txt(suffix, Raven.hint)
            }
        }
        if (error != null) {
            Gap(4)
            Txt(error, Raven.small.copy(color = Raven.Danger))
        }
    }
}

/** A modal panel over the screen, pinned to the bottom on phones and centered on wide screens. */
@Composable
fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val none = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(interactionSource = none, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().clip(shape).background(Raven.Slate)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding().imePadding()
                .heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Raven.Seam))
            Gap(14)
            Txt(title, Raven.h2.copy(fontSize = 20.sp))
            Gap(14)
            content()
        }
    }
}

/** Dashed placeholder where a drive will appear. */
@Composable
fun EmptySlot(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 92.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(Raven.Seam, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        }
        Txt(text, Raven.hint.copy(textAlign = TextAlign.Center), Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
    }
}

@Composable
fun BoxScope.Centered(content: @Composable () -> Unit) {
    Box(Modifier.align(Alignment.Center)) { content() }
}
