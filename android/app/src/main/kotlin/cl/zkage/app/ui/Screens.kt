package cl.zkage.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.zkage.app.AccessKey
import cl.zkage.app.Blocker
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val longDate = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-CL"))

fun LocalDate.spoken(): String = format(longDate)

// ——— Start ———

@Composable
fun IntroScreen(onStart: () -> Unit) {
    val c = App.colors
    Page(
        top = { AppBar() },
        background = { Rings(Modifier.size(340.dp).align(Alignment.TopEnd).offset(x = 130.dp, y = (-60).dp)) },
        actions = {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Glyph.Lock, c.muted, 20.dp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.size(10.dp))
                Text("Tus datos no salen de este teléfono.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
            PrimaryButton("Empezar", onStart)
        },
    ) {
        Text(
            "Demuestra que eres mayor de edad sin mostrar tu cédula",
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 38.sp),
            color = c.text,
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp).semantics { heading() },
        )
        Body("Quien te pide la edad recibe sólo un sí o un no. No ve tu nombre, tu RUN ni tu fecha de nacimiento.")
        SectionTitle("Lo haces una vez, en tres pasos")
        NumberedStep(1, "Lee tu cédula con el teléfono", "Necesitas tu cédula chilena con chip y el NFC encendido.")
        NumberedStep(2, "Confirma que eres tú", "La app compara tu cara con la foto del chip.")
        NumberedStep(3, "Genera tu prueba", "Tarda entre uno y tres minutos. Después la usas cuantas veces quieras.")
    }
}

@Composable
private fun NumberedStep(n: Int, title: String, body: String) {
    val c = App.colors
    Row(Modifier.padding(bottom = 18.dp).semantics(mergeDescendants = true) {}) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.tint), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.titleMedium, color = c.primary)
        }
        Spacer(Modifier.size(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
    }
}

// ——— Step 1: the card ———

/** The rear camera reads the MRZ; after [helpShown] it suggests typing instead. */
@Composable
fun ScanScreen(camera: @Composable (Modifier) -> Unit, helpShown: Boolean, onManual: () -> Unit, onKeepTrying: () -> Unit, onBack: () -> Unit) {
    val c = App.colors
    Page(
        top = { StepHeader(1, onBack) },
        actions = {
            if (helpShown) {
                PrimaryButton("Escribir los datos a mano", onManual)
                SecondaryButton("Seguir intentando", onKeepTrying)
            } else {
                SecondaryButton("Escribir los datos a mano", onManual)
            }
        },
    ) {
        Title("Muestra el reverso de tu cédula a la cámara")
        Body("Encuadra las tres líneas de letras y signos <<< que hay abajo.", modifier = Modifier.padding(top = 8.dp))
        Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1A2B4C))) {
            camera(Modifier.fillMaxSize())
            // Where the three lines should land: the lower third of the card.
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * 0.86f
                val h = size.height * 0.30f
                drawRoundRect(
                    Color.White, Offset((size.width - w) / 2, size.height * 0.58f), Size(w, h),
                    CornerRadius(12.dp.toPx()), style = Stroke(3.dp.toPx()),
                )
            }
        }
        if (helpShown) {
            InfoPanel(
                Glyph.Alert, "No logramos leer las líneas",
                "Puede pasar con poca luz, reflejos o una cédula gastada. Escribir los datos toma menos de un minuto.",
                Modifier.padding(top = 18.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            StatusLine("Buscando las líneas…")
            Text(
                "Usa buena luz y evita reflejos. La imagen no se guarda ni sale del teléfono.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** The three fields typed by hand; dates as day, month and year. */
@Composable
fun ManualScreen(initial: AccessKey?, note: String?, onSubmit: (AccessKey) -> Unit, onBack: () -> Unit) {
    val c = App.colors
    var number by rememberSaveable { mutableStateOf(initial?.documentNumber ?: "") }
    val birth = rememberDate(initial?.birth)
    val expiry = rememberDate(initial?.expiry)
    val key = if (number.length in 8..9) {
        val b = birth.yymmdd()
        val e = expiry.yymmdd()
        if (b != null && e != null) AccessKey(number, b, e) else null
    } else {
        null
    }
    Page(
        top = { StepHeader(1, onBack) },
        actions = { PrimaryButton("Continuar", { key?.let(onSubmit) }, enabled = key != null) },
    ) {
        Title("Escribe tres datos de tu cédula")
        Body("Abren el chip. No se guardan ni salen del teléfono.", modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.padding(bottom = 16.dp))
        }
        FieldLabel("Número de documento")
        Text("No es tu RUN: es el número de la cédula.", style = MaterialTheme.typography.bodySmall, color = c.muted)
        AppField(number, { number = it.uppercase().filter(Char::isLetterOrDigit).take(9) }, "Número de documento", KeyboardType.Ascii, Modifier.fillMaxWidth())
        DateFields("Fecha de nacimiento", birth)
        DateFields("Fecha de vencimiento", expiry)
        Disclosure("¿Dónde encuentro estos datos?") {
            Text(
                "Están en el frente de tu cédula: el número de documento, la fecha de nacimiento y la fecha de vencimiento.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
            )
        }
    }
}

class DateState(day: String, month: String, year: String) {
    var day by mutableStateOf(day)
    var month by mutableStateOf(month)
    var year by mutableStateOf(year)

    /** YYMMDD for the chip, or null until it is a real date. */
    fun yymmdd(): String? {
        val y = year.toIntOrNull()?.takeIf { year.length == 4 } ?: return null
        val date = runCatching { LocalDate.of(y, month.toInt(), day.toInt()) }.getOrNull() ?: return null
        return "%02d%02d%02d".format(date.year % 100, date.monthValue, date.dayOfMonth)
    }
}

@Composable
private fun rememberDate(yymmdd: String?): DateState = remember {
    if (yymmdd == null) {
        DateState("", "", "")
    } else {
        // Only used to refill the form after a failed read: the century is a guess the user sees.
        val yy = yymmdd.take(2).toInt()
        val year = if (yy > LocalDate.now().year % 100 + 20) 1900 + yy else 2000 + yy
        DateState(yymmdd.substring(4, 6), yymmdd.substring(2, 4), year.toString())
    }
}

@Composable
private fun DateFields(label: String, state: DateState) {
    Text(label, style = MaterialTheme.typography.titleMedium, color = App.colors.text, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppField(state.day, { state.day = it.filter(Char::isDigit).take(2) }, "Día", KeyboardType.Number, Modifier.weight(1f), "DD")
        AppField(state.month, { state.month = it.filter(Char::isDigit).take(2) }, "Mes", KeyboardType.Number, Modifier.weight(1f), "MM")
        AppField(state.year, { state.year = it.filter(Char::isDigit).take(4) }, "Año", KeyboardType.Number, Modifier.weight(1.5f), "AAAA")
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = App.colors.text, modifier = Modifier.padding(bottom = 2.dp))
}

@Composable
private fun AppField(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType, modifier: Modifier, placeholder: String? = null) {
    val c = App.colors
    OutlinedTextField(
        value, onChange,
        modifier = modifier.padding(top = 4.dp),
        label = if (placeholder != null) ({ Text(label) }) else null,
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.primary, unfocusedBorderColor = c.muted, focusedLabelColor = c.primary,
            unfocusedLabelColor = c.muted, cursorColor = c.primary, focusedTextColor = c.text, unfocusedTextColor = c.text,
        ),
    )
}

/** Waiting for the card, or reading it. */
@Composable
fun ChipScreen(reading: Boolean, onCancel: () -> Unit) {
    val c = App.colors
    Page(top = { StepHeader(1, onCancel) }, actions = { if (!reading) SecondaryButton("Cancelar", onCancel) }) {
        Title("Apoya la cédula en la parte de atrás del teléfono")
        Body("Déjala quieta hasta que termine la lectura.", modifier = Modifier.padding(top = 8.dp))
        Box(Modifier.padding(top = 24.dp).fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp)).background(c.tint), contentAlignment = Alignment.Center) {
            CardOnPhone(Modifier.size(230.dp))
        }
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            StatusLine(if (reading) "Leyendo el chip… no muevas la cédula" else "Esperando la cédula…")
        }
        Text(
            "Si no la detecta, muévela despacio hacia arriba o hacia abajo: el lector cambia de lugar según el modelo de teléfono.",
            style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A card under a tilted phone with the NFC waves; decorative. */
@Composable
private fun CardOnPhone(modifier: Modifier) {
    val c = App.colors
    val still = reduceMotion()
    val t = rememberInfiniteTransition(label = "nfc")
    val pulse by t.animateFloat(0.15f, if (still) 0.15f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier) {
        val u = size.minDimension / 260f
        drawRoundRect(c.background, Offset(20 * u, 120 * u), Size(190 * u, 120 * u), CornerRadius(12 * u))
        drawRoundRect(c.primary, Offset(20 * u, 120 * u), Size(190 * u, 120 * u), CornerRadius(12 * u), style = Stroke(3 * u))
        drawRoundRect(c.line, Offset(38 * u, 142 * u), Size(44 * u, 54 * u), CornerRadius(6 * u))
        drawRoundRect(c.line, Offset(96 * u, 148 * u), Size(92 * u, 9 * u), CornerRadius(4.5f * u))
        drawRoundRect(c.line, Offset(96 * u, 166 * u), Size(70 * u, 9 * u), CornerRadius(4.5f * u))
        rotate(8f, Offset(165 * u, 130 * u)) {
            drawRoundRect(c.background, Offset(110 * u, 30 * u), Size(110 * u, 200 * u), CornerRadius(18 * u))
            drawRoundRect(c.text, Offset(110 * u, 30 * u), Size(110 * u, 200 * u), CornerRadius(18 * u), style = Stroke(3 * u))
            drawCircle(c.text, 7 * u, Offset(148 * u, 66 * u), style = Stroke(2.5f * u))
        }
        listOf(12f to 1f, 22f to 0.7f, 32f to 0.45f).forEach { (r, k) ->
            drawArc(
                c.primary.copy(alpha = (pulse * k).coerceIn(0.15f, 1f)), -50f, 100f, false,
                Offset((196 - r) * u, (120 - r) * u), Size(2 * r * u, 2 * r * u), style = Stroke(4 * u, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
fun ReadFailedScreen(detail: String, onRetry: () -> Unit, onEdit: () -> Unit) {
    Page(actions = { PrimaryButton("Intentar de nuevo", onRetry); SecondaryButton("Corregir los datos", onEdit) }) {
        Badge(Glyph.Alert, Tone.Error, modifier = Modifier.padding(top = 40.dp))
        Title("No se pudo leer el chip", Modifier.padding(top = 16.dp, bottom = 8.dp))
        Body("Pasa si la cédula se movió durante la lectura o si algún dato no coincide con la cédula.")
        SectionTitle("Qué puedes hacer")
        Bullets("Vuelve a apoyarla y no la muevas hasta que termine.", "Revisa el número de documento y las fechas.")
        Disclosure { Detail(detail) }
    }
}

@Composable
fun NfcOffScreen(onSettings: () -> Unit, onBack: () -> Unit) {
    Page(top = { BackButton(onBack) }, actions = { PrimaryButton("Abrir ajustes de NFC", onSettings); SecondaryButton("Volver", onBack) }) {
        Badge(Glyph.Alert, Tone.Error, modifier = Modifier.padding(top = 16.dp))
        Title("El NFC está apagado", Modifier.padding(top = 16.dp, bottom = 8.dp))
        Body("La app lo necesita para leer el chip de la cédula.")
        SectionTitle("Cómo encenderlo")
        Bullets("Toca Abrir ajustes de NFC.", "Activa el interruptor de NFC.", "Vuelve a esta app con el botón de atrás.", numbered = true)
    }
}

/** The card is authentic and the person can prove: on to the face. */
@Composable
fun CardOkScreen(chipOriginal: Boolean, details: List<String>, onFace: () -> Unit) {
    val c = App.colors
    Page(
        top = { StepHeader(1, done = true) },
        actions = {
            Text("Ahora confirma que eres tú", style = MaterialTheme.typography.titleLarge, color = c.text)
            Text("La app compara tu cara con la foto del chip, aquí mismo en el teléfono.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            PrimaryButton("Comparar mi cara", onFace)
        },
    ) {
        Badge(Glyph.Check, Tone.Success, modifier = Modifier.padding(top = 12.dp))
        Title("Tu cédula es auténtica", Modifier.padding(top = 16.dp, bottom = 8.dp))
        Body("La firmó el Registro Civil de Chile y dice que eres mayor de 18.", modifier = Modifier.padding(bottom = 16.dp))
        MarkRow("Firma del Registro Civil válida", Mark.Done)
        MarkRow("Cédula vigente", Mark.Done)
        MarkRow("Chip original, no copiado", if (chipOriginal) Mark.Done else Mark.No)
        Disclosure { details.forEach { Detail(it) } }
    }
}

/** Every reason the proof stops after reading, each with what the person can do. */
@Composable
fun BlockedScreen(blocker: Blocker, details: List<String>, onReadAgain: () -> Unit, onOtherCard: () -> Unit, onHome: () -> Unit) {
    when (blocker) {
        is Blocker.Minor -> Page(actions = { PrimaryButton("Volver al inicio", onHome) }) {
            Badge(Glyph.Calendar, Tone.Info, modifier = Modifier.padding(top = 40.dp))
            Title("Todavía no puedes generar la prueba", Modifier.padding(top = 16.dp, bottom = 8.dp))
            Body("Tu cédula es auténtica, pero indica que aún no cumples 18 años.")
            Column(Modifier.padding(top = 24.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(App.colors.tint).padding(20.dp)) {
                Text("Podrás generarla desde el", style = MaterialTheme.typography.bodyMedium, color = App.colors.muted)
                Text(blocker.eligibleOn.spoken(), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp), color = App.colors.text)
            }
            Body("No guardamos nada de tu cédula. Ese día, vuelve a leerla aquí.", modifier = Modifier.padding(top = 16.dp))
        }
        is Blocker.Expired -> Page(actions = { PrimaryButton("Leer otra cédula", onOtherCard); SecondaryButton("Volver al inicio", onHome) }) {
            Badge(Glyph.CalendarOff, Tone.Info, modifier = Modifier.padding(top = 40.dp))
            Title("Tu cédula está vencida", Modifier.padding(top = 16.dp, bottom = 8.dp))
            Body("Venció el ${blocker.on.spoken()}. La prueba sólo se puede generar con una cédula vigente.")
            SectionTitle("Qué puedes hacer")
            Bullets("Si ya tienes una cédula nueva, léela.", "Si no, renuévala en el Registro Civil y vuelve a esta app.")
        }
        Blocker.Unsupported -> Page(actions = { PrimaryButton("Volver al inicio", onHome) }) {
            Badge(Glyph.Alert, Tone.Info, modifier = Modifier.padding(top = 40.dp))
            Title("Este tipo de cédula todavía no es compatible", Modifier.padding(top = 16.dp, bottom = 8.dp))
            Body("Tu cédula es auténtica, pero su chip firma con un método que la prueba todavía no puede revisar.")
            Disclosure { details.forEach { Detail(it) } }
        }
        else -> Page(actions = { PrimaryButton("Leer de nuevo", onReadAgain); SecondaryButton("Volver al inicio", onHome) }) {
            Badge(Glyph.Alert, Tone.Error, modifier = Modifier.padding(top = 40.dp))
            Title("No pudimos confirmar que la cédula sea auténtica", Modifier.padding(top = 16.dp, bottom = 8.dp))
            Body(
                when (blocker) {
                    Blocker.Altered -> "Los datos del chip no coinciden con la firma del Registro Civil."
                    Blocker.UnknownIssuer -> "No la firmó una clave del Registro Civil que la app conozca. Si tu cédula se emitió hace poco, puede que la app todavía no tenga la clave nueva."
                    Blocker.NotOriginal -> "El chip no pudo demostrar que es original."
                    else -> "No se pudo leer la foto del chip."
                },
            )
            SectionTitle("Qué puedes hacer")
            Bullets("Vuelve a leerla y no muevas la cédula hasta que termine.", "Si vuelve a pasar, el chip puede estar dañado. En el Registro Civil pueden revisarla.", numbered = true)
            Disclosure { details.forEach { Detail(it) } }
        }
    }
}

@Composable
private fun Bullets(vararg lines: String, numbered: Boolean = false) {
    val c = App.colors
    lines.forEachIndexed { i, line ->
        Row(Modifier.padding(bottom = 8.dp)) {
            Text(if (numbered) "${i + 1}." else "•", style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.widthIn(min = 24.dp))
            Text(line, style = MaterialTheme.typography.bodyLarge, color = c.text)
        }
    }
}

// ——— Step 2: the face ———

/** What Ley 21.719 Art. 16 ter asks to tell before treating biometric data. */
@Composable
fun FaceNoticeScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Page(top = { StepHeader(2, onDecline) }, actions = { PrimaryButton("Acepto, abrir la cámara", onAccept); SecondaryButton("No, volver", onDecline) }) {
        Title("Antes de abrir la cámara")
        Body("La app va a comparar tu cara con la foto del chip. Esto es lo que pasa con esos datos:", modifier = Modifier.padding(top = 8.dp))
        NoticeItem("Qué sistema usa", "Detección de caras YuNet y reconocimiento SFace (OpenCV Zoo), que corren en este teléfono.")
        NoticeItem("Para qué", "Comprobar que quien genera la prueba es la persona de la foto del chip, con una prueba de vida que pide mirar de frente y girar la cabeza hacia cada lado.")
        NoticeItem("Por cuánto tiempo", "Sólo mientras dura la comparación. La foto del chip y las imágenes de la cámara quedan en la memoria del teléfono y se descartan al terminar. No se guardan, no se envían y no entran en la prueba.")
        NoticeItem("Tus derechos", "Como nada se guarda ni sale del teléfono, no queda un dato tuyo que pedir, corregir o borrar. Puedes no aceptar; en ese caso no se genera la prueba con esta cédula.")
    }
}

@Composable
private fun NoticeItem(title: String, body: String) {
    SectionTitle(title)
    Body(body, muted = false)
}

/** A side as the person sees it in the mirrored preview: their right is the screen's right. */
enum class Side { Right, Left }

/** The face check's progress as the redesign's checklist. */
data class FaceProgress(
    val instruction: String,
    val center: Mark,
    val right: Mark,
    val left: Mark,
    val confirm: Mark,
    /** The side to turn the head to now, or null. */
    val side: Side? = null,
    /** How far toward [side]: 1 or more is enough. */
    val reach: Float = 0f,
    /** The match is done and the screen holds a moment before proving. */
    val checking: Boolean = false,
)

@Composable
fun FaceScreen(camera: @Composable (Modifier) -> Unit, progress: FaceProgress?, error: String?, onCancel: () -> Unit) {
    val c = App.colors
    Page(top = { StepHeader(2) }, actions = { SecondaryButton("Cancelar", onCancel) }) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1A2B4C))) {
            camera(Modifier.fillMaxSize())
            FaceGuide(progress, Modifier.fillMaxSize())
        }
        Text(
            error ?: progress?.instruction ?: "Preparando la cámara…",
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp),
            color = c.text,
            modifier = Modifier.padding(top = 20.dp, bottom = 14.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (progress?.checking == true) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 14.dp), color = c.primary, trackColor = c.line)
        }
        if (progress != null) {
            MarkRow("Mira de frente", progress.center)
            MarkRow("Gira hacia tu derecha", progress.right)
            MarkRow("Gira hacia tu izquierda", progress.left)
            MarkRow("Vuelve a mirar de frente", progress.confirm)
        }
        Text("Las imágenes no se guardan ni salen del teléfono.", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 12.dp))
    }
}

/**
 * The oval over the camera. While turning, an arc on the side asked fills as the head turns,
 * and an arrow outside it points the way. Decorative: the instruction text says the same.
 */
@Composable
private fun FaceGuide(progress: FaceProgress?, modifier: Modifier) {
    val still = reduceMotion()
    val nudge = if (still) {
        0f
    } else {
        rememberInfiniteTransition(label = "nudge").animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "nudge").value
    }
    val reach by animateFloatAsState((progress?.reach ?: 0f).coerceIn(0f, 1f), tween(if (still) 0 else 150), label = "reach")
    val done = Color(0xFF3DDC84)
    val fill = Color(0xFF8FB0FF)
    Canvas(modifier) {
        val w = size.height * 0.62f
        val h = size.height * 0.80f
        val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2)
        val oval = if (progress?.checking == true) done else Color.White
        drawOval(oval, topLeft, Size(w, h), style = Stroke(4.dp.toPx()))
        val side = progress?.side ?: return@Canvas
        // Compose angles: 0 at three o'clock, clockwise.
        val middle = if (side == Side.Right) 0f else 180f
        val span = 100f
        val arc = Stroke(9.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Color.White.copy(alpha = 0.35f), middle - span / 2, span, false, topLeft, Size(w, h), style = arc)
        if (reach > 0f) {
            drawArc(if (reach >= 1f) done else fill, middle - span * reach / 2, span * reach, false, topLeft, Size(w, h), style = arc)
        }
        val dir = if (side == Side.Right) 1f else -1f
        val tip = Offset(size.width / 2 + dir * (w / 2 + (30 + 8 * nudge).dp.toPx()), size.height / 2)
        val arm = 14.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.45f), 26.dp.toPx(), Offset(tip.x - dir * arm / 2, tip.y))
        val chevron = Path().apply {
            moveTo(tip.x - dir * arm, tip.y - arm)
            lineTo(tip.x, tip.y)
            lineTo(tip.x - dir * arm, tip.y + arm)
        }
        drawPath(chevron, Color.White, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun FaceTimeoutScreen(onRetry: () -> Unit, onHome: () -> Unit) {
    val c = App.colors
    Page(top = { StepHeader(2) }, actions = { PrimaryButton("Intentar de nuevo", onRetry); SecondaryButton("Volver al inicio", onHome) }) {
        Badge(Glyph.Face, Tone.Info, modifier = Modifier.padding(top = 20.dp))
        Title("Tu cara no coincidió con la foto del chip", Modifier.padding(top = 16.dp, bottom = 8.dp))
        Body("Suele pasar por la luz o porque la foto de la cédula es antigua. Inténtalo otra vez así:")
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Tip(Glyph.Sun, "Con luz de frente, no detrás de ti.")
            Tip(Glyph.Glasses, "Sin lentes de sol, gorro ni mascarilla.")
            Tip(Glyph.Oval, "Con la cara completa dentro del óvalo.")
        }
        Text("Sólo la persona de la cédula puede generar su prueba.", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 24.dp))
    }
}

@Composable
private fun Tip(glyph: Glyph, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(glyph, App.colors.primary, 24.dp)
        Spacer(Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = App.colors.text)
    }
}

// ——— Step 3: the proof ———

@Composable
fun GeneratingScreen(elapsedSeconds: Long, askNotifications: Boolean, onAllowNotifications: () -> Unit) {
    val c = App.colors
    Page(
        top = { StepHeader(3) },
        background = { Rings(Modifier.size(300.dp).align(Alignment.CenterEnd).offset(x = 110.dp, y = 120.dp)) },
    ) {
        Title("Generando tu prueba")
        Body("Suele tardar entre uno y tres minutos.", modifier = Modifier.padding(top = 8.dp))
        Text(
            "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 56.sp, lineHeight = 60.sp),
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 18.dp),
        )
        LinearProgressIndicator(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = c.primary, trackColor = c.line)
        if (askNotifications) {
            Column(
                Modifier.padding(top = 40.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background)
                    .border(BorderStroke(2.dp, c.primary), RoundedCornerShape(16.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row {
                    Icon(Glyph.Bell, c.primary, 26.dp)
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text("¿Te avisamos cuando esté lista?", style = MaterialTheme.typography.titleMedium, color = c.text)
                        Text("Así puedes salir de la app mientras tanto. La prueba sigue igual si dices que no.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    }
                }
                PrimaryButton("Avisarme", onAllowNotifications)
            }
        } else {
            InfoPanel(Glyph.Bell, "Puedes salir de la app", "Sigue trabajando aunque cambies de app. Te llega una notificación cuando esté lista.", Modifier.padding(top = 40.dp))
        }
        Body("Tu teléfono está calculando una prueba matemática de que eres mayor de 18. La prueba no contiene tus datos.", modifier = Modifier.padding(top = 24.dp))
    }
}

@Composable
fun ProveFailedScreen(message: String, detail: String, canRetry: Boolean, hadProof: Boolean, onRetry: () -> Unit, onHome: () -> Unit) {
    Page(
        top = { StepHeader(3) },
        actions = {
            if (canRetry) PrimaryButton("Intentar de nuevo", onRetry)
            SecondaryButton("Volver al inicio", onHome)
        },
    ) {
        Badge(Glyph.Alert, Tone.Error, modifier = Modifier.padding(top = 20.dp))
        Title("No se pudo generar la prueba", Modifier.padding(top = 16.dp, bottom = 8.dp))
        Body(message)
        SectionTitle("Qué puedes hacer")
        if (canRetry) {
            Bullets("Cierra las apps que no estés usando.", "Toca Intentar de nuevo. No tienes que volver a leer la cédula.", numbered = true)
        } else {
            Bullets("Vuelve al inicio y lee tu cédula otra vez.")
        }
        if (hadProof) Body("Tu prueba anterior sigue funcionando.", modifier = Modifier.padding(top = 8.dp))
        Disclosure { Detail(detail) }
    }
}

/** Home once a proof exists: the proof card and the one thing to do with it. */
@Composable
fun ReadyScreen(provedOn: LocalDate?, details: List<String>, onShow: () -> Unit, onNew: () -> Unit) {
    val c = App.colors
    Page(
        top = { AppBar() },
        background = { Rings(Modifier.size(320.dp).align(Alignment.CenterEnd).offset(x = 150.dp, y = 110.dp)) },
        actions = {
            PrimaryButton("Mostrar mi prueba", onShow, Glyph.Qr, tall = true)
            SecondaryButton("Generar una prueba nueva", onNew)
        },
    ) {
        Column(
            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card).padding(24.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Text("Tu prueba de edad", style = MaterialTheme.typography.bodyLarge, color = c.onCardMuted)
            Text("Mayor de 18", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp, lineHeight = 48.sp), color = c.onCard, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(20.dp))
            if (provedOn != null) Text("Generada el ${provedOn.spoken()}", style = MaterialTheme.typography.bodyMedium, color = c.onCard)
            Text("Sólo funciona en este teléfono", style = MaterialTheme.typography.bodyMedium, color = c.onCard)
        }
        Text("¿Te piden la edad?", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp), color = c.text, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp).semantics { heading() })
        Body("Toca el botón y escanea el código que te muestran. Sólo sabrán que eres mayor de 18.")
        if (details.isNotEmpty()) Disclosure { details.forEach { Detail(it) } }
    }
}

// ——— Showing the proof ———

@Composable
fun ConfirmScreen(origin: String, onShow: () -> Unit, onCancel: () -> Unit) {
    val c = App.colors
    Page(top = { BackButton(onCancel) }, actions = { PrimaryButton("Mostrar prueba", onShow); SecondaryButton("Cancelar", onCancel) }) {
        Title("¿Mostrar tu prueba a este sitio?", Modifier.padding(bottom = 16.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(2.dp, c.line, RoundedCornerShape(14.dp)).background(c.tint).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyph.Globe, c.primary, 24.dp)
            Spacer(Modifier.size(12.dp))
            Text(origin.removePrefix("https://"), style = MaterialTheme.typography.titleLarge, color = c.text)
        }
        Text("Revisa que sea el sitio del lugar donde estás.", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 8.dp))
        SectionTitle("Va a saber")
        MarkRow("Que eres mayor de 18", Mark.Done)
        SectionTitle("No va a saber")
        MarkRow("Tu nombre", Mark.No)
        MarkRow("Tu RUN", Mark.No)
        MarkRow("Tu fecha de nacimiento", Mark.No)
        MarkRow("Tu foto", Mark.No)
        Disclosure("Qué se envía exactamente") {
            Detail("La prueba de conocimiento cero, la clave pública de este teléfono y una firma válida sólo para este sitio y esta visita.")
        }
    }
}

@Composable
fun SendingScreen() {
    val c = App.colors
    Page {
        Title("Enviando la prueba…", Modifier.padding(top = 40.dp, bottom = 20.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = c.primary, trackColor = c.line)
    }
}

@Composable
fun AcceptedScreen(origin: String, onDone: () -> Unit) {
    val c = App.colors
    Page(actions = { PrimaryButton("Listo", onDone) }) {
        Box(Modifier.fillMaxWidth().heightIn(min = 420.dp), contentAlignment = Alignment.Center) {
            Rings(Modifier.size(400.dp), ring = c.successTint, accent = c.success.copy(alpha = 0.45f), fill = c.successTint)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(112.dp).clip(CircleShape).background(c.success), contentAlignment = Alignment.Center) {
                    Icon(Glyph.Check, c.background, 60.dp, 2.8f)
                }
                Text(
                    "Prueba aceptada", style = MaterialTheme.typography.headlineLarge, color = c.text,
                    modifier = Modifier.padding(top = 28.dp, bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite; heading() },
                )
                Text(
                    "${origin.removePrefix("https://")} ya sabe que eres mayor de 18. Nada más.",
                    style = MaterialTheme.typography.bodyLarge, color = c.muted, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun NotAcceptedScreen(title: String, message: String, detail: String?, onRetry: (() -> Unit)?, onDone: () -> Unit) {
    Page(actions = {
        if (onRetry != null) PrimaryButton("Intentar de nuevo", onRetry)
        SecondaryButton("Volver", onDone)
    }) {
        Badge(Glyph.Alert, Tone.Error, modifier = Modifier.padding(top = 40.dp))
        Title(title, Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        Body(message)
        if (detail != null) Disclosure { Detail(detail) }
    }
}

@Composable
fun MessageScreen(title: String, message: String, onBack: () -> Unit) {
    Page(actions = { PrimaryButton("Volver", onBack) }) {
        Badge(Glyph.Alert, Tone.Info, modifier = Modifier.padding(top = 40.dp))
        Title(title, Modifier.padding(top = 16.dp, bottom = 8.dp))
        Body(message)
    }
}

@Composable
fun TextLink(text: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = 48.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = App.colors.primary, fontWeight = FontWeight.SemiBold)
    }
}
