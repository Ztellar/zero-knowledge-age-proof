package cl.zkage.app

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cl.zkage.verifier.ActiveAuth
import cl.zkage.verifier.Presentation
import cl.zkage.verifier.Report
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import cl.zkage.verifier.SodVerifier

/**
 * Reads a real card, checks its signatures in Kotlin and, on request, proves the age claim
 * with a STARK on the phone. The proving runs in [ProverService]; this screen only shows
 * [ProofJob]'s state, so it finds the proof running or done when the user comes back.
 */
class MainActivity : ComponentActivity() {
    private sealed interface Screen {
        data object Form : Screen
        data object Waiting : Screen
        data object Reading : Screen
        /** [activeAuth] is null when the chip has no DG15 or did not answer. */
        data class Done(
            val report: Report,
            val chip: ChipData,
            val activeAuth: Result<ActiveAuth.Result>?,
            /** The key whose hash the chip signed: the proof must be bound to this one. */
            val deviceKey: DeviceKey,
            /** DG2's photo, or why it could not be read; null if the chip has no DG2. */
            val photo: Result<ChipPhoto>?,
            /** The camera matched the chip photo, with liveness (decision 0007). */
            val faceMatched: Boolean = false,
        ) : Screen {
            /** The chip's answer, if the proof can check it. */
            val provableAnswer: ActiveAuthAnswer?
                get() = chip.activeAuth.takeIf { report.dg15HashValid == true && activeAuth?.getOrNull()?.provable == true }

            /**
             * A chip with a photo must match the person first; one without DG2 proves without
             * it. The match is a local check: it does not enter the proof.
             */
            val faceReady: Boolean get() = photo == null || faceMatched
        }
        /** The Ley 21.719 notice before the camera opens. */
        data class FaceNotice(val done: Done) : Screen
        data class Face(val done: Done, val photo: ChipPhoto) : Screen
        data class Failed(val message: String) : Screen
        data class Confirm(val request: Presentation.Request) : Screen
        data object Presenting : Screen
        data class Presented(val outcome: Presenter.Outcome) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Form)
    private var key: AccessKey? = null
    private var nfc: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
        onLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onLink(intent)
    }

    /** A `zkage://present` link, from the verifier page opened on this phone. */
    private fun onLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        answer(link)
    }

    private fun answer(link: String?) {
        val request = link?.let(Presentation::parse)
        screen = when {
            request == null -> Screen.Failed("Ese código no es de un verificador de edad.")
            !Presenter.hasProof(this) -> Screen.Failed("Primero genera la prueba con tu cédula.")
            else -> Screen.Confirm(request)
        }
    }

    /** Google's code scanner: no camera permission, the scanning UI runs in Play services. */
    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { answer(it.rawValue) }
            .addOnFailureListener { screen = Screen.Failed("No se pudo escanear: ${it.message}") }
    }

    private fun present(request: Presentation.Request) {
        screen = Screen.Presenting
        thread(name = "presenter") {
            val outcome = Presenter.present(this, request)
            runOnUiThread { screen = Screen.Presented(outcome) }
        }
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.Waiting) listen()
    }

    private fun start(accessKey: AccessKey) {
        if (nfc?.isEnabled != true) {
            screen = Screen.Failed("Activa el NFC en los ajustes del teléfono.")
            return
        }
        key = accessKey
        screen = Screen.Waiting
        listen()
    }

    private fun listen() {
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfc?.enableReaderMode(this, ::onTag, flags, null)
    }

    /** Runs on an NFC binder thread, so blocking I/O is fine here. */
    private fun onTag(tag: Tag) {
        val accessKey = key ?: return
        runOnUiThread { screen = Screen.Reading }
        val next = try {
            // Before reading: the chip signs the start of this key's hash (decision 0006).
            val deviceKey = DeviceKey.forNewProof(this)
            val chip = CardReader.read(tag, accessKey, deviceKey.spkiSha256.copyOf(8))
            val aa = chip.activeAuth?.takeIf { it.response != null }?.let {
                runCatching { ActiveAuth.verify(it.dg15, it.challenge, it.response!!, it.ecSignatureOid) }
            }
            val photo = chip.dg2?.let { dg2 -> runCatching { ChipPhoto.from(dg2) ?: error("DG2 has no face image") } }
            Screen.Done(SodVerifier.verify(chip.sod, chip.dg1, dg15 = chip.activeAuth?.dg15, dg2 = chip.dg2), chip, aa, deviceKey, photo)
        } catch (e: Exception) {
            Screen.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
        runOnUiThread {
            nfc?.disableReaderMode(this)
            key = null
            screen = next
        }
    }

    /** Asks for the notification permission once (Android 13+), then proves either way. */
    private fun prove(done: Screen.Done) {
        val csca = done.report.csca ?: return
        val begin = {
            ProofJob.start(this, csca, done.chip, done.deviceKey, done.provableAnswer)
            screen = Screen.Form
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = begin
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            begin()
        }
    }

    /** Asks for the camera, then opens the face match; without it the proof cannot start. */
    private fun matchFace(done: Screen.Done) {
        val photo = done.photo?.getOrNull() ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            screen = Screen.Face(done, photo)
        } else {
            afterCamera = { granted ->
                screen = if (granted) Screen.Face(done, photo) else Screen.Failed("Sin la cámara no se puede comparar tu cara con la foto del chip.")
            }
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private var afterCamera: ((Boolean) -> Unit)? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        afterCamera?.invoke(granted)
        afterCamera = null
    }

    private var afterPermission: (() -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermission?.invoke()
        afterPermission = null
    }

    @Composable
    private fun App() {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Prueba de edad", style = MaterialTheme.typography.headlineMedium)
            val job by ProofJob.state.collectAsState()
            when (val j = job) {
                is ProofJob.State.Running -> Proving(j)
                is ProofJob.State.Done -> Proved(j)
                is ProofJob.State.Failed -> ProveFailed(j)
                ProofJob.State.Idle -> when (val s = screen) {
                    Screen.Form -> {
                        if (Presenter.hasProof(this@MainActivity)) {
                            Button(onClick = ::scan) { Text("Presentar mi prueba (escanear QR)") }
                            Text("O lee tu cédula otra vez para generar una prueba nueva:", style = MaterialTheme.typography.bodySmall)
                        }
                        Form(::start)
                    }
                    is Screen.Confirm -> Confirm(s.request)
                    Screen.Presenting -> {
                        Text("Enviando la prueba…", style = MaterialTheme.typography.headlineSmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is Screen.Presented -> Presented(s.outcome)
                    Screen.Waiting -> Text("Acerca la cédula a la parte trasera del teléfono y no la muevas.")
                    Screen.Reading -> Text("Leyendo el chip… no muevas la cédula.")
                    is Screen.Done -> Result(s)
                    is Screen.FaceNotice -> FaceNotice(s.done)
                    is Screen.Face -> FaceMatch(s)
                    is Screen.Failed -> {
                        Text("No se pudo leer", fontWeight = FontWeight.Bold)
                        Text(s.message)
                        Button(onClick = { screen = Screen.Form }) { Text("Volver") }
                    }
                }
            }
        }
    }

    @Composable
    private fun Form(onSubmit: (AccessKey) -> Unit) {
        var number by remember { mutableStateOf("") }
        var birth by remember { mutableStateOf("") }
        var expiry by remember { mutableStateOf("") }
        val ready = number.length in 8..9 && birth.length == 6 && expiry.length == 6
        Text("Estos tres datos abren el chip. No se guardan ni salen del teléfono.")
        OutlinedTextField(
            number, { number = it.uppercase().filter(Char::isLetterOrDigit).take(9) },
            label = { Text("Número de documento") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        DateField("Fecha de nacimiento (AAMMDD)", birth) { birth = it }
        DateField("Fecha de vencimiento (AAMMDD)", expiry) { expiry = it }
        Button(onClick = { onSubmit(AccessKey(number, birth, expiry)) }, enabled = ready) { Text("Leer cédula") }
    }

    @Composable
    private fun DateField(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value, { onChange(it.filter(Char::isDigit).take(6)) },
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun Result(s: Screen.Done) {
        val r = s.report
        Text(
            when {
                !r.authentic -> "Cédula no válida"
                r.adult -> "Mayor de 18: sí"
                else -> "Mayor de 18: no"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Check("Firma del SOD con el DSC", r.sodSignatureValid)
        Check("DSC firmado por el Registro Civil", r.csca != null)
        Check("Hash de DG1 en el SOD", r.dg1HashValid)
        Check("Documento vigente", !r.expired)
        if (s.chip.activeAuth != null) {
            Check("Chip original (Active Authentication)", r.dg15HashValid == true && s.activeAuth?.getOrNull()?.valid == true)
        }
        if (s.photo != null) {
            Check("Foto del chip (DG2) coincide con el SOD", r.dg2HashValid == true)
            if (s.faceMatched) Check("Tu cara coincide con la foto del chip", true)
        }
        Text("Detalles técnicos", fontWeight = FontWeight.Bold)
        Text(
            listOf(
                "Acceso: ${s.chip.protocol}",
                "Firma del SOD: ${r.sodSignatureAlgorithm}",
                "Firma del DSC: ${r.dscSignatureAlgorithm}",
                "Clave del DSC: RSA ${r.dscKeyBits} bits",
                "CSCA: ${r.csca?.label ?: "ninguna de Chile"}",
                "Hash de los data groups: ${r.dataGroupHashAlgorithm}",
                "Data groups en el SOD: ${r.dataGroups.joinToString(", ")}",
                "Active Authentication (DG15): ${if (15 in r.dataGroups) "sí" else "no"}",
                "Chip Authentication (DG14): ${if (14 in r.dataGroups) "sí" else "no"}",
                *activeAuthLines(s).toTypedArray(),
                when (val photo = s.photo) {
                    null -> "Foto (DG2): el chip no la tiene"
                    else -> photo.fold(
                        { "Foto (DG2): ${it.mimeType}, ${it.bytes.size} B" },
                        { "Foto (DG2): no se pudo leer (${it.message})" },
                    )
                },
                "DG1: ${s.chip.dg1.size} B, SOD: ${s.chip.sod.size} B",
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
        )
        if (r.authentic && r.adult) {
            if (s.provableAnswer == null) {
                Text("La prueba no incluirá Active Authentication: el chip no la tiene o su respuesta no es una firma P-256 válida. Algunos verificadores pueden rechazarla.")
            }
            val photo = s.photo
            when {
                photo == null -> Text("Esta cédula no trae foto en el chip, así que no se compara tu cara.")
                photo.isFailure || r.dg2HashValid != true ->
                    Text("No se puede generar la prueba: la foto del chip no se pudo leer o no coincide con el SOD.")
                !s.faceMatched -> {
                    Text("Antes de generar la prueba, la app compara tu cara con la foto del chip. Es una comprobación local: no entra en la prueba.")
                    Button(onClick = { screen = Screen.FaceNotice(s) }) { Text("Comparar mi cara") }
                }
            }
            if (s.faceReady) {
                Text("La prueba de conocimiento cero tarda entre uno y tres minutos y usa bastante memoria. Corre en segundo plano: puedes salir de la app.")
                Button(onClick = { prove(s) }) { Text("Generar prueba") }
            }
        }
        Button(onClick = { screen = Screen.Form }) { Text("Leer otra") }
    }

    /** What Ley 21.719 Art. 16 ter asks to tell before treating biometric data. */
    @Composable
    private fun FaceNotice(done: Screen.Done) {
        Text("Comparar tu cara", style = MaterialTheme.typography.headlineSmall)
        Text(
            listOf(
                "Sistema: detección de caras YuNet y reconocimiento SFace (OpenCV Zoo), que corren en este teléfono.",
                "Para qué: comprobar que quien genera la prueba es la persona de la foto del chip, con una prueba de vida que pide mirar de frente y girar la cabeza hacia cada lado.",
                "Por cuánto tiempo: sólo mientras dura la comparación. La foto del chip y las imágenes de la cámara quedan en la memoria del teléfono y se descartan al terminar. No se guardan, no se envían y no entran en la prueba.",
                "Tus derechos: como nada se guarda ni sale del teléfono, no queda un dato tuyo que pedir, corregir o borrar. Puedes no aceptar; en ese caso no se genera la prueba con esta cédula.",
            ).joinToString("\n\n"),
        )
        Button(onClick = { matchFace(done) }) { Text("Acepto, abrir la cámara") }
        Button(onClick = { screen = done }) { Text("No, volver") }
    }

    @Composable
    private fun FaceMatch(s: Screen.Face) {
        val check = remember(s) { FaceCheck(this, s.photo) }
        DisposableEffect(check) { onDispose { check.close() } }
        val state by check.state.collectAsState()
        Text("Comparar tu cara", style = MaterialTheme.typography.headlineSmall)
        AndroidView(
            factory = { context -> PreviewView(context).also { check.start(this@MainActivity, it) } },
            modifier = Modifier.fillMaxWidth().height(360.dp),
        )
        when (val st = state) {
            FaceCheck.State.Loading -> Text("Cargando los modelos…")
            is FaceCheck.State.Running -> {
                Text(
                    when {
                        !st.faceFound -> "No se ve una cara: ponla dentro del recuadro."
                        // Turned faces score lower against the frontal photo: only judge it facing ahead.
                        st.turns.all { it } || st.center != null -> when {
                            st.similarity < 0.363f -> "Mira de frente: esa cara aún no coincide con la foto del chip."
                            st.confirm != null -> "Vuelve a mirar de frente (${st.confirm} de ${FaceCheck.CENTER_FRAMES})."
                            else -> "Mira de frente a la cámara (${st.center} de ${FaceCheck.CENTER_FRAMES})."
                        }
                        else -> "Ahora gira la cabeza hacia cada lado, en cualquier orden."
                    },
                    fontWeight = FontWeight.Bold,
                )
                if (st.center == null && st.confirm == null) {
                    // turns: image right, then left. The preview is mirrored, so the image's
                    // right is the user's own left.
                    val labels = listOf("Hacia tu izquierda", "Hacia tu derecha")
                    labels.forEachIndexed { i, label -> Check(label, st.turns[i]) }
                }
                Text(
                    "Similitud con la foto: ${"%.2f".format(st.similarity)} (desde 0,36 es la misma persona) · ${st.millis} ms por imagen",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FaceCheck.State.Matched -> LaunchedEffect(Unit) { screen = s.done.copy(faceMatched = true) }
            is FaceCheck.State.Failed -> Text(st.message)
        }
        Button(onClick = { screen = s.done }) { Text("Cancelar") }
    }

    /** Algorithm, size and outcome only: the DG15 key itself identifies the card. */
    private fun activeAuthLines(s: Screen.Done): List<String> {
        val answer = s.chip.activeAuth ?: return emptyList()
        val outcome = s.activeAuth
        return listOfNotNull(
            "DG15 coincide con el SOD: ${if (s.report.dg15HashValid == true) "sí" else "no"}",
            outcome?.getOrNull()?.let { "Clave de AA: ${it.algorithm} ${it.keyBits} bits${it.curve?.let { c -> " ($c)" } ?: ""}, ${it.digest}" },
            "Algoritmo de AA en DG14: ${answer.ecSignatureOid ?: "no indicado"}",
            when {
                answer.error != null -> "Respuesta del chip: error (${answer.error})"
                outcome == null -> null
                outcome.isFailure -> "Respuesta del chip: no se pudo verificar (${outcome.exceptionOrNull()?.message})"
                else -> "Respuesta del chip: ${answer.response!!.size} B, firma ${if (outcome.getOrThrow().valid) "válida" else "inválida"}"
            },
        )
    }

    @Composable
    private fun Proving(s: ProofJob.State.Running) {
        var elapsed by remember { mutableStateOf(0L) }
        LaunchedEffect(s.startedAt) {
            while (true) {
                elapsed = (System.currentTimeMillis() - s.startedAt) / 1000
                delay(1000)
            }
        }
        Text("Generando la prueba STARK…", style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("$elapsed s. Puedes salir de la app: te avisa una notificación al terminar.")
    }

    @Composable
    private fun Proved(s: ProofJob.State.Done) {
        val c = s.proof.claim
        Text("Prueba generada", style = MaterialTheme.typography.headlineSmall)
        Text("✓  Verificada en el teléfono")
        Text(
            "Dice: mayor de ${c.minAge} el ${c.today}, con una cédula firmada por la CSCA ${s.csca}, ligada a la clave ML-DSA de este teléfono" +
                (if (c.chipAuthenticated) ", y el chip de la cédula firmó para esa clave." else ". El chip no firmó: no incluye Active Authentication.") +
                " Nada más.",
        )
        Text("Detalles técnicos", fontWeight = FontWeight.Bold)
        Text(
            listOf(
                "Sistema: STARK sucinto (RISC Zero), post-cuántico",
                "Tiempo: ${"%.1f".format(s.seconds)} s",
                "Tamaño: ${s.proof.receipt.size} B",
                "Hash de la CSCA: ${c.cscaSha256.hex8()}…",
                "Clave del teléfono: ML-DSA-65, ${s.presentation.keyStorage}",
                "Hash de la clave: ${c.deviceKeySha256.hex8()}…",
                "Chip original (Active Authentication, P-256): ${if (c.chipAuthenticated) "sí" else "no"}",
                "Segmento del zkVM: 2^${s.segment.po2} (${s.segment.reason})",
                "Guardada en el almacenamiento privado de la app",
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Presentación de prueba", fontWeight = FontWeight.Bold)
        val p = s.presentation
        Check("Desafío firmado con la clave del teléfono y verificado contra el hash de la prueba", p.valid)
        Text("Firma ML-DSA-65: ${p.signatureBytes} B en ${p.signMillis} ms", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { ProofJob.reset() }) { Text("Volver") }
    }

    private fun ByteArray.hex8() = take(8).joinToString("") { "%02x".format(it) }

    @Composable
    private fun ProveFailed(s: ProofJob.State.Failed) {
        Text("No se pudo generar la prueba", fontWeight = FontWeight.Bold)
        Text(s.message)
        Button(onClick = { ProofJob.reset() }) { Text("Volver") }
    }

    @Composable
    private fun Confirm(request: Presentation.Request) {
        Text("¿Responder a este verificador?", style = MaterialTheme.typography.headlineSmall)
        Text(request.origin, fontWeight = FontWeight.Bold)
        Text(
            "Recibirá tu prueba de mayoría de edad, la clave pública del teléfono y una firma " +
                "válida sólo para este sitio. No recibe tu nombre, RUN ni fecha de nacimiento.",
        )
        Button(onClick = { present(request) }) { Text("Responder") }
        Button(onClick = { screen = Screen.Form }) { Text("Cancelar") }
    }

    @Composable
    private fun Presented(outcome: Presenter.Outcome) {
        when (outcome) {
            is Presenter.Outcome.Accepted -> {
                Text("✓ El verificador aceptó la prueba", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Mayor de ${outcome.minAge}, cédula firmada por ${outcome.csca}, prueba del ${outcome.provedOn}" +
                        if (outcome.chipAuthenticated) ", con el chip de la cédula presente." else ", sin Active Authentication.",
                )
            }
            is Presenter.Outcome.Rejected -> {
                Text("✗ El verificador rechazó la prueba", style = MaterialTheme.typography.headlineSmall)
                Text(outcome.reason)
            }
            is Presenter.Outcome.Failed -> {
                Text("No se pudo presentar", fontWeight = FontWeight.Bold)
                Text(outcome.message)
            }
        }
        Button(onClick = { screen = Screen.Form }) { Text("Volver") }
    }

    @Composable
    private fun Check(label: String, ok: Boolean) {
        Text("${if (ok) "✓" else "✗"}  $label")
    }
}
