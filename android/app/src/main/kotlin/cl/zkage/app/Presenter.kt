package cl.zkage.app

import android.annotation.SuppressLint
import android.content.Context
import cl.zkage.verifier.Presentation
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/**
 * Answers a verifier's QR (`docs/protocol.md`, version 2): signs its origin and challenge with
 * the device key the stored proof names, and sends the proof, the public key and the signature.
 * Nothing else leaves the phone: no name, RUN or birth date.
 */
object Presenter {
    const val PROOF_FILE = "age-proof.bin"

    sealed interface Outcome {
        data class Accepted(val minAge: Int, val provedOn: String, val csca: String, val chipAuthenticated: Boolean) : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun hasProof(context: Context) = File(context.filesDir, PROOF_FILE).exists()

    /** What to tell the person for a rejection code of `docs/protocol.md`, section 5. */
    fun rejectionText(reason: String): String = when (reason) {
        "proof" -> "El sitio dice que la prueba no es válida."
        "untrusted_csca" -> "El sitio no reconoce la firma del Registro Civil de tu cédula."
        "age_too_low" -> "El sitio pide una edad mayor que la que muestra tu prueba."
        "future_proof" -> "La fecha de tu prueba es posterior a la del sitio. Revisa la fecha de tu teléfono."
        "expired_proof" -> "Tu prueba es demasiado antigua para este sitio. Genera una nueva."
        "chip_not_authenticated" -> "Tu prueba no muestra que el chip de tu cédula estuviera presente. Genera una nueva."
        "device_key" -> "La prueba no corresponde a la clave de este teléfono. Genera una nueva."
        "signature" -> "La respuesta no calza con este sitio o este código. Escanea el código otra vez."
        "malformed_body" -> "El sitio no pudo leer lo que envió el teléfono."
        else -> "El sitio no la aceptó ($reason)."
    }

    /** Blocking: Keystore, signing and network. */
    fun present(context: Context, request: Presentation.Request): Outcome = try {
        val proof = Proof.parse(File(context.filesDir, PROOF_FILE).readBytes())
        val key = DeviceKey.matching(context, proof.claim.deviceKeySha256)
            ?: return Outcome.Failed("La clave de este teléfono ya no es la de la prueba: genera la prueba de nuevo.")
        val signature = key.sign(Presentation.message(request.origin, request.challenge))
        val body = Presentation.body(proof.receipt, key.spki, signature)
        val (code, text) = post(request, body)
        when {
            code == 200 -> {
                val json = JSONObject(text)
                when (json.getString("state")) {
                    "accepted" -> Outcome.Accepted(
                        json.getInt("min_age"), json.getString("proved_on"), json.getString("csca"),
                        json.optBoolean("chip_authenticated"),
                    )
                    else -> Outcome.Rejected(json.optString("reason"))
                }
            }
            else -> Outcome.Failed("El verificador respondió $code: $text")
        }
    } catch (e: Exception) {
        Outcome.Failed("${e.javaClass.simpleName}: ${e.message}")
    }

    private fun post(request: Presentation.Request, body: ByteArray): Pair<Int, String> {
        val connection = URL(request.url).openConnection() as HttpsURLConnection
        // Without a pin, the platform's CAs and host name check, as in a browser.
        request.certSha256?.let { pin ->
            connection.sslSocketFactory = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(PinnedCertificate(pin)), null)
            }.socketFactory
        }
        // The proof goes only to the url on the signed origin, never where a redirect points.
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setFixedLengthStreamingMode(body.size)
        return try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Trusts exactly the certificate whose SHA-256 the QR carries, instead of any CA: for a demo
     * verifier with a self-signed certificate, where the QR is how the phone learns it. Hostname
     * checking stays on (the certificate names the verifier's IP).
     */
    @SuppressLint("CustomX509TrustManager") // rejects every certificate but the pinned one
    private class PinnedCertificate(private val sha256: ByteArray) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(leaf.encoded), sha256)) {
                throw CertificateException("certificate is not the one in the QR")
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client certificates are not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
