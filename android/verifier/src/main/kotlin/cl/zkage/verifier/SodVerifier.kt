package cl.zkage.verifier

import java.security.MessageDigest
import java.security.Security
import java.time.LocalDate
import java.time.ZoneOffset
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.icao.ICAOObjectIdentifiers
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.icao.DataGroupHash
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.DefaultAlgorithmNameFinder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.util.Selector

class VerificationException(message: String) : Exception(message)

/**
 * What a card's chip data proves, checked in plain Kotlin: the same five points the STARK
 * proves, without zero knowledge. Holds no name, number or date of the holder.
 */
data class Report(
    /** The SOD's CMS signature verifies with the DSC inside it (messageDigest included). */
    val sodSignatureValid: Boolean,
    val sodSignatureAlgorithm: String,
    val dscSignatureAlgorithm: String,
    val dscKeyBits: Int,
    /** The country key that signed the DSC, or null if none did. */
    val csca: CscaKey?,
    val dg1HashValid: Boolean,
    val dataGroupHashAlgorithm: String,
    /**
     * Numbers of the data groups the SOD signs, sorted. Says which security features the
     * chip has: DG14 means Chip Authentication, DG15 Active Authentication.
     */
    val dataGroups: List<Int>,
    /** DG15 read from the chip matches its SOD hash; null if it was not read. */
    val dg15HashValid: Boolean?,
    /** DG2, the face photo, matches its SOD hash; null if it was not read (decision 0007). */
    val dg2HashValid: Boolean? = null,
    val adult: Boolean,
    val expired: Boolean,
) {
    /** Authentic and unexpired; `adult` is the answer on top of that. */
    val authentic: Boolean get() = sodSignatureValid && csca != null && dg1HashValid && !expired
}

object SodVerifier {
    private val bc = BouncyCastleProvider()
    private val names = DefaultAlgorithmNameFinder()

    init {
        if (Security.getProvider(bc.name) == null) Security.addProvider(bc)
    }

    fun verify(
        sod: ByteArray,
        dg1: ByteArray,
        cscaKeys: List<CscaKey> = ChileCsca.keys,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
        minAge: Int = 18,
        dg15: ByteArray? = null,
        dg2: ByteArray? = null,
    ): Report {
        val signed = try {
            CMSSignedData(unwrapSod(sod))
        } catch (e: CMSException) {
            throw VerificationException("SOD is not CMS SignedData: ${e.message}")
        }
        if (signed.signedContentTypeOID != ICAOObjectIdentifiers.id_icao_ldsSecurityObject.id) {
            throw VerificationException("SOD content is ${signed.signedContentTypeOID}, not LDSSecurityObject")
        }
        val signer = signed.signerInfos.signers.singleOrNull()
            ?: throw VerificationException("SOD has ${signed.signerInfos.size()} signers, expected 1")
        // SignerId is a raw Selector in Bouncy Castle's Java API.
        @Suppress("UNCHECKED_CAST")
        val dsc = signed.certificates.getMatches(signer.sid as Selector<X509CertificateHolder>).firstOrNull()
            ?: throw VerificationException("SOD does not carry its DSC")

        val sodValid = try {
            signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(bc).build(dsc))
        } catch (e: CMSException) {
            false
        }
        val csca = cscaKeys.firstOrNull { key ->
            runCatching {
                dsc.isSignatureValid(JcaContentVerifierProviderBuilder().setProvider(bc).build(key.publicKey()))
            }.getOrDefault(false)
        }

        // LDSSecurityObject ::= SEQUENCE { version, hashAlgorithm, dataGroupHashValues, ... }.
        // Read by hand: Bouncy Castle's LDSSecurityObject requires at least two hashes, and
        // the prover accepts one.
        val lds = ASN1Sequence.getInstance(signed.signedContent.content as ByteArray)
        val hashAlg = AlgorithmIdentifier.getInstance(lds.getObjectAt(1)).algorithm
        val hashes = ASN1Sequence.getInstance(lds.getObjectAt(2)).map { DataGroupHash.getInstance(it) }
        fun hashValid(number: Int, file: ByteArray): Boolean? {
            val expected = hashes.firstOrNull { it.dataGroupNumber == number }?.dataGroupHashValue?.octets ?: return null
            return MessageDigest.isEqual(expected, MessageDigest.getInstance(hashAlg.id, bc).digest(file))
        }
        val dg1HashValid = hashValid(1, dg1) ?: throw VerificationException("SOD has no hash for DG1")

        val dates = Mrz.dates(Mrz.td1(dg1), today)
        return Report(
            sodSignatureValid = sodValid,
            sodSignatureAlgorithm = "${name(signer.digestAlgOID)} + ${name(signer.encryptionAlgOID)}",
            dscSignatureAlgorithm = names.getAlgorithmName(dsc.signatureAlgorithm),
            dscKeyBits = rsaBits(dsc),
            csca = csca,
            dg1HashValid = dg1HashValid,
            dataGroupHashAlgorithm = name(hashAlg.id),
            dataGroups = hashes.map { it.dataGroupNumber }.sorted(),
            dg15HashValid = dg15?.let { hashValid(15, it) ?: false },
            dg2HashValid = dg2?.let { hashValid(2, it) ?: false },
            adult = Mrz.isAtLeast(dates.birth, today, minAge),
            expired = !dates.expiry.isAfter(today),
        )
    }

    /** EF.SOD on the chip wraps the CMS ContentInfo in application tag 0x77. */
    fun unwrapSod(sod: ByteArray): ByteArray = unwrap(sod, 0x77, "EF.SOD")

    /** Strips the one-byte application [tag] an EF wraps its content in; returns it as is if absent. */
    fun unwrap(file: ByteArray, tag: Int, name: String): ByteArray {
        if (file.isEmpty() || file[0] != tag.toByte()) return file
        var pos = 1
        val first = file[pos++].toInt() and 0xff
        val len = if (first < 0x80) {
            first
        } else {
            val n = first and 0x7f
            if (n !in 1..4) throw VerificationException("$name length is not supported")
            (0 until n).fold(0) { acc, _ -> (acc shl 8) or (file[pos++].toInt() and 0xff) }
        }
        if (pos + len > file.size) throw VerificationException("$name is truncated")
        return file.copyOfRange(pos, pos + len)
    }

    private fun name(oid: String) = names.getAlgorithmName(ASN1ObjectIdentifier(oid))

    private fun rsaBits(cert: X509CertificateHolder): Int =
        runCatching {
            org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(cert.subjectPublicKeyInfo.parsePublicKey()).modulus.bitLength()
        }.getOrDefault(0)
}
