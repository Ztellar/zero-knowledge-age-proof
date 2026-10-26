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
 * Answers a verifier's QR (decision 0005): signs its origin and challenge with the device key
 * the stored proof names, and sends the proof, the public key and the signature. Nothing else
 * leaves the phone: no name, RUN or birth date.
 */
object Presenter {
    const val PROOF_FILE = "age-proof.bin"

    sealed interface Outcome {
        data class Accepted(val minAge: Int, val provedOn: String, val csca: String) : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun hasProof(context: Context) = File(context.filesDir, PROOF_FILE).exists()

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
                    "accepted" -> Outcome.Accepted(json.getInt("min_age"), json.getString("proved_on"), json.getString("csca"))
                    else -> Outcome.Rejected(json.optString("reason"))
                }
            }
            else -> Outcome.Failed("El verificador respondió $code: $text")
        }
    } catch (e: Exception) {
        Outcome.Failed("${e.javaClass.simpleName}: ${e.message}")
    }

    private fun post(request: Presentation.Request, body: ByteArray): Pair<Int, String> {
        val connection = URL(request.presentationUrl).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(PinnedCertificate(request.certSha256)), null)
        }.socketFactory
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
     * Trusts exactly the certificate whose SHA-256 the QR carries, instead of any CA: the
     * verifier's certificate is self-signed, and the QR is how the phone learns it. Hostname
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
