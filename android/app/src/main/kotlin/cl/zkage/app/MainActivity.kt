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
import androidx.compose.runtime.Composable
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
        data class Done(val report: Report, val chip: ChipData) : Screen
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
            val chip = CardReader.read(tag, accessKey)
            Screen.Done(SodVerifier.verify(chip.sod, chip.dg1), chip)
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
            ProofJob.start(this, csca, done.chip)
            screen = Screen.Form
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = begin
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            begin()
        }
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
                "DG1: ${s.chip.dg1.size} B, SOD: ${s.chip.sod.size} B",
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
        )
        if (r.authentic && r.adult) {
            Text("La prueba de conocimiento cero tarda entre uno y tres minutos y usa bastante memoria. Corre en segundo plano: puedes salir de la app.")
            Button(onClick = { prove(s) }) { Text("Generar prueba") }
        }
        Button(onClick = { screen = Screen.Form }) { Text("Leer otra") }
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
        Text("Dice: mayor de ${c.minAge} el ${c.today}, con una cédula firmada por la CSCA ${s.csca}, ligada a la clave ML-DSA de este teléfono. Nada más.")
        Text("Detalles técnicos", fontWeight = FontWeight.Bold)
        Text(
            listOf(
                "Sistema: STARK sucinto (RISC Zero), post-cuántico",
                "Tiempo: ${"%.1f".format(s.seconds)} s",
                "Tamaño: ${s.proof.receipt.size} B",
                "Hash de la CSCA: ${c.cscaSha256.hex8()}…",
                "Clave del teléfono: ML-DSA-65, ${s.presentation.keyStorage}",
                "Hash de la clave: ${c.deviceKeySha256.hex8()}…",
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
                Text("Mayor de ${outcome.minAge}, cédula firmada por ${outcome.csca}, prueba del ${outcome.provedOn}.")
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
