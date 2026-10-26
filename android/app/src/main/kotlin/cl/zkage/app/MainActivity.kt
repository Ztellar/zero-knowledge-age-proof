package cl.zkage.app

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.WindowManager
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
import cl.zkage.verifier.ChileCsca
import cl.zkage.verifier.Report
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import cl.zkage.verifier.SodVerifier

/**
 * Reads a real card, checks its signatures in Kotlin and, on request, proves the age claim
 * with a STARK on the phone. The proving runs in this activity for now; a background
 * service is still to do.
 */
class MainActivity : ComponentActivity() {
    private sealed interface Screen {
        data object Form : Screen
        data object Waiting : Screen
        data object Reading : Screen
        data class Done(val report: Report, val chip: ChipData) : Screen
        data class Proving(val startedAt: Long) : Screen
        data class Proved(val proof: Proof, val seconds: Double, val csca: String) : Screen
        data class Failed(val message: String) : Screen
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

    /** Proves on a plain thread: the native call blocks for about a minute. */
    private fun prove(done: Screen.Done) {
        val csca = done.report.csca ?: return
        val started = System.currentTimeMillis()
        screen = Screen.Proving(started)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        thread(name = "prover") {
            val next = try {
                val proof = NativeProver.prove(csca.spki(), done.chip.sod, done.chip.dg1, LocalDate.now(ZoneOffset.UTC), 18)
                // No personal data in it: only the CSCA hash, the date and the threshold are public.
                File(filesDir, "age-proof.bin").writeBytes(proof.raw)
                val label = ChileCsca.keys.firstOrNull { it.spkiSha256().contentEquals(proof.claim.cscaSha256) }?.label
                Screen.Proved(proof, (System.currentTimeMillis() - started) / 1000.0, label ?: "desconocida")
            } catch (e: Throwable) {
                Screen.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                screen = next
            }
        }
    }

    @Composable
    private fun App() {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Prueba de edad", style = MaterialTheme.typography.headlineMedium)
            when (val s = screen) {
                Screen.Form -> Form(::start)
                Screen.Waiting -> Text("Acerca la cédula a la parte trasera del teléfono y no la muevas.")
                Screen.Reading -> Text("Leyendo el chip… no muevas la cédula.")
                is Screen.Done -> Result(s)
                is Screen.Proving -> Proving(s)
                is Screen.Proved -> Proved(s)
                is Screen.Failed -> {
                    Text("No se pudo leer", fontWeight = FontWeight.Bold)
                    Text(s.message)
                    Button(onClick = { screen = Screen.Form }) { Text("Volver") }
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
                "DG1: ${s.chip.dg1.size} B, SOD: ${s.chip.sod.size} B",
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
        )
        if (r.authentic && r.adult) {
            Text("La prueba de conocimiento cero tarda alrededor de un minuto y usa bastante memoria. Deja la app abierta.")
            Button(onClick = { prove(s) }) { Text("Generar prueba") }
        }
        Button(onClick = { screen = Screen.Form }) { Text("Leer otra") }
    }

    @Composable
    private fun Proving(s: Screen.Proving) {
        var elapsed by remember { mutableStateOf(0L) }
        LaunchedEffect(s.startedAt) {
            while (true) {
                elapsed = (System.currentTimeMillis() - s.startedAt) / 1000
                delay(1000)
            }
        }
        Text("Generando la prueba STARK…", style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("$elapsed s. Deja la app abierta.")
    }

    @Composable
    private fun Proved(s: Screen.Proved) {
        val c = s.proof.claim
        Text("Prueba generada", style = MaterialTheme.typography.headlineSmall)
        Text("✓  Verificada en el teléfono")
        Text("Dice: mayor de ${c.minAge} el ${c.today}, con una cédula firmada por la CSCA ${s.csca}. Nada más.")
        Text("Detalles técnicos", fontWeight = FontWeight.Bold)
        Text(
            listOf(
                "Sistema: STARK sucinto (RISC Zero), post-cuántico",
                "Tiempo: ${"%.1f".format(s.seconds)} s",
                "Tamaño: ${s.proof.receipt.size} B",
                "Hash de la CSCA: ${c.cscaSha256.take(8).joinToString("") { "%02x".format(it) }}…",
                "Guardada en el almacenamiento privado de la app",
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { screen = Screen.Form }) { Text("Volver") }
    }

    @Composable
    private fun Check(label: String, ok: Boolean) {
        Text("${if (ok) "✓" else "✗"}  $label")
    }
}
