package cl.zkage.app

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import cl.zkage.verifier.Report
import cl.zkage.verifier.SodVerifier

/** Stage 3: read a real card and check its signatures in Kotlin. No proof yet. */
class MainActivity : ComponentActivity() {
    private sealed interface Screen {
        data object Form : Screen
        data object Waiting : Screen
        data object Reading : Screen
        data class Done(val report: Report, val protocol: String, val dg1Bytes: Int, val sodBytes: Int) : Screen
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
            Screen.Done(SodVerifier.verify(chip.sod, chip.dg1), chip.protocol, chip.dg1.size, chip.sod.size)
        } catch (e: Exception) {
            Screen.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
        runOnUiThread {
            nfc?.disableReaderMode(this)
            key = null
            screen = next
        }
    }

    @Composable
    private fun App() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Prueba de edad", style = MaterialTheme.typography.headlineMedium)
            when (val s = screen) {
                Screen.Form -> Form(::start)
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
                "Acceso: ${s.protocol}",
                "Firma del SOD: ${r.sodSignatureAlgorithm}",
                "Firma del DSC: ${r.dscSignatureAlgorithm}",
                "Clave del DSC: RSA ${r.dscKeyBits} bits",
                "CSCA: ${r.csca?.label ?: "ninguna de Chile"}",
                "Hash de los data groups: ${r.dataGroupHashAlgorithm}",
                "DG1: ${s.dg1Bytes} B, SOD: ${s.sodBytes} B",
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { screen = Screen.Form }) { Text("Leer otra") }
    }

    @Composable
    private fun Check(label: String, ok: Boolean) {
        Text("${if (ok) "✓" else "✗"}  $label")
    }
}
