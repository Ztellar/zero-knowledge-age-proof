package cl.zkage.app.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stroke icons drawn from 24×24 path data, like the redesign canvas's inline SVGs. */
enum class Glyph(vararg val paths: String) {
    Check("M5 12.5l4.5 4.5L19 7.5"),
    Cross("M6 6l12 12M18 6L6 18"),
    Back("M15 18l-6-6 6-6"),
    Chevron("M9 6l6 6-6 6"),
    Shield("M12 3l7 3v5c0 4.5-3 8.5-7 10-4-1.5-7-5.5-7-10V6l7-3z", "M9 12l2 2 4-4"),
    Alert("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0", "M12 7v6", "M12 17h.01"),
    Calendar("M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3 10h18M8 3v4M16 3v4"),
    CalendarOff("M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3 10h18M8 3v4M16 3v4M10 14l4 4M14 14l-4 4"),
    Bell("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0"),
    Qr(
        "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
        "M14 14h3v3h-3zM20 14v.01M14 20h.01M17 20h4v-3",
    ),
    Globe("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0", "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"),
    Lock("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V7a4 4 0 0 1 8 0v4"),
    Face("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0", "M9 10h.01M15 10h.01M9 15.5c1.8-1 4.2-1 6 0"),
    Help("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0", "M9.5 9.5a2.5 2.5 0 0 1 4.8 1c0 1.7-2.3 2-2.3 3.5", "M12 17h.01"),
    Sun("M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0", "M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"),
    Glasses("M10.5 14a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0M20.5 14a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0", "M10.5 14h3M3 10l2-4M21 10l-2-4"),
    Oval("M19 12a7 9 0 1 1-14 0a7 9 0 1 1 14 0"),
}

@Composable
fun Icon(glyph: Glyph, color: Color, size: Dp = 24.dp, strokeWidth: Float = 2f, modifier: Modifier = Modifier) {
    val paths = remember(glyph) { glyph.paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            paths.forEach { drawPath(it, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

/**
 * One screen: content that scrolls (at 200% font it will) and the actions pinned at the
 * bottom, so the main button never scrolls out of reach. [background] draws behind both.
 */
@Composable
fun Page(
    top: @Composable (() -> Unit)? = null,
    actions: @Composable (ColumnScope.() -> Unit)? = null,
    background: @Composable (BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = App.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
        background?.invoke(this)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (top != null) Box(Modifier.padding(horizontal = 24.dp).padding(top = 8.dp)) { top() }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                content = content,
            )
            if (actions != null) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = actions,
                )
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, glyph: Glyph? = null, enabled: Boolean = true, tall: Boolean = false) {
    val c = App.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = if (tall) 64.dp else 56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary, disabledContainerColor = c.line, disabledContentColor = c.muted),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (glyph != null) {
            Icon(glyph, if (enabled) c.onPrimary else c.muted, 24.dp, 2.2f)
            Spacer(Modifier.size(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit) {
    val c = App.colors
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(2.dp, c.primary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.primary),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** The shield and the app's name, on screens outside the three steps. */
@Composable
fun AppBar() {
    val c = App.colors
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyph.Shield, c.primary, 26.dp)
        Spacer(Modifier.size(10.dp))
        Text("Prueba de edad", style = MaterialTheme.typography.titleMedium, color = c.text)
    }
}

/** Back button (when [onBack] is set), "Paso n de 3" and the three bars. */
@Composable
fun StepHeader(step: Int, onBack: (() -> Unit)? = null, done: Boolean = false) {
    val c = App.colors
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack)
                        .semantics { contentDescription = "Volver" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Glyph.Back, c.text, 24.dp, 2.2f) }
            }
            Spacer(Modifier.weight(1f))
            Text(if (done) "Paso $step de 3, listo" else "Paso $step de 3", style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < step) c.primary else c.line))
            }
        }
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    val c = App.colors
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "Volver" },
        contentAlignment = Alignment.Center,
    ) { Icon(Glyph.Back, c.text, 24.dp, 2.2f) }
}

enum class Tone { Info, Success, Error }

/** The round icon above a screen's title. */
@Composable
fun Badge(glyph: Glyph, tone: Tone, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val c = App.colors
    val (fg, bg) = when (tone) {
        Tone.Info -> c.primary to c.tint
        Tone.Success -> c.success to c.successTint
        Tone.Error -> c.error to c.errorTint
    }
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(glyph, fg, size / 2, 2.4f)
    }
}

@Composable
fun Title(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = App.colors.text, modifier = modifier.semantics { heading() })
}

@Composable
fun Body(text: String, muted: Boolean = true, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = if (muted) App.colors.muted else App.colors.text, modifier = modifier)
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = App.colors.text, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp).semantics { heading() })
}

enum class Mark { Done, Current, Pending, No }

/** A line with a check, a cross or a progress dot; TalkBack reads its state. */
@Composable
fun MarkRow(text: String, mark: Mark) {
    val c = App.colors
    Row(
        Modifier.padding(vertical = 5.dp).semantics(mergeDescendants = true) {
            stateDescription = when (mark) {
                Mark.Done -> "hecho"
                Mark.Current -> "ahora"
                Mark.Pending -> "pendiente"
                Mark.No -> "no"
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (mark) {
            Mark.Done -> Icon(Glyph.Check, c.success, 22.dp, 2.6f)
            Mark.No -> Icon(Glyph.Cross, c.muted, 22.dp, 2.4f)
            Mark.Current -> Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(14.dp).clip(CircleShape).background(c.primary)) }
            Mark.Pending -> Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(c.background).padding(0.dp))
                Canvas(Modifier.size(14.dp)) { drawCircle(c.muted, style = Stroke(2.dp.toPx())) }
            }
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (mark == Mark.Pending) c.muted else c.text,
            fontWeight = if (mark == Mark.Current) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** A tinted panel with an icon, a bold line and a body. */
@Composable
fun InfoPanel(glyph: Glyph, title: String, body: String, modifier: Modifier = Modifier, extra: @Composable (ColumnScope.() -> Unit)? = null) {
    val c = App.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.tint).padding(20.dp)) {
        Row {
            Icon(glyph, c.primary, 26.dp, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.size(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
        }
        extra?.invoke(this)
    }
}

/** "Ver detalles técnicos": closed by default, so the plain answer comes first. */
@Composable
fun Disclosure(title: String = "Ver detalles técnicos", content: @Composable ColumnScope.() -> Unit) {
    val c = App.colors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "abierto" else "cerrado" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyph.Chevron, c.primary, 20.dp, 2.2f, Modifier.rotate(if (open) 90f else 0f))
            Spacer(Modifier.size(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.primary)
        }
        AnimatedVisibility(open) { Column(Modifier.padding(top = 4.dp), content = content) }
    }
}

/** Monospace-free technical lines, small. */
@Composable
fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = App.colors.muted, modifier = Modifier.padding(vertical = 2.dp))
}

/** True when the user turned animations off ("Quitar animaciones" sets the scale to 0). */
@Composable
fun reduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The background figure: four concentric rings turning at different speeds and directions,
 * with dots that make the turning visible. Still when motion is reduced.
 */
@Composable
fun Rings(modifier: Modifier = Modifier, ring: Color = App.colors.ring, accent: Color = App.colors.ringAccent, fill: Color = App.colors.ringFill) {
    val still = reduceMotion()
    val t = rememberInfiniteTransition(label = "rings")
    val a1 by t.animateFloat(0f, if (still) 0f else 360f, infiniteRepeatable(tween(30_000, easing = LinearEasing)), label = "r1")
    val a2 by t.animateFloat(0f, if (still) 0f else -360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "r2")
    val a4 by t.animateFloat(0f, if (still) 0f else 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "r4")
    val breathe by t.animateFloat(1f, if (still) 1f else 1.12f, infiniteRepeatable(tween(3_500), RepeatMode.Reverse), label = "r3")
    Canvas(modifier) {
        val r = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        withTransform({ rotate(a1, center) }) {
            drawCircle(ring, r - 1.dp.toPx(), center, style = Stroke(2.dp.toPx(), pathEffect = dash))
            drawCircle(accent, 7.dp.toPx(), Offset(center.x, center.y - r + 1.dp.toPx()))
        }
        val r2 = r * 0.72f
        withTransform({ rotate(a2, center) }) {
            drawCircle(ring, r2, center, style = Stroke(3.dp.toPx()))
            drawArc(accent, -90f, 180f, false, Offset(center.x - r2, center.y - r2), androidx.compose.ui.geometry.Size(r2 * 2, r2 * 2), style = Stroke(3.dp.toPx()))
            drawCircle(accent, 6.dp.toPx(), Offset(center.x, center.y + r2))
        }
        drawCircle(fill, r * 0.42f * breathe, center)
        val r4 = r * 0.24f
        withTransform({ rotate(a4, center) }) {
            drawArc(accent, -90f, 90f, false, Offset(center.x - r4, center.y - r4), androidx.compose.ui.geometry.Size(r4 * 2, r4 * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** A small filled dot, for "esperando…" status lines. */
@Composable
fun StatusLine(text: String) {
    val c = App.colors
    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(c.primary))
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = c.text)
    }
}
