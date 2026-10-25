package cl.zkage.verifier

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as `prover/core/tests/fixtures.rs`, on the same mock card from `fixtures/gen.py`. */
class SodVerifierTest {
    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "$name missing: run prover/fixtures/gen.py" }.readBytes()

    private val sod = fixture("sod.der")
    private val dg1 = fixture("dg1.bin")
    private val testCsca: CscaKey = run {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(fixture("csca.der")))
        val key = cert.publicKey as RSAPublicKey
        CscaKey("test CSCA", "2026-01-01", "2040-01-01", null, key.modulus, key.publicExponent)
    }
    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun mockCardProvesAdult() {
        val report = SodVerifier.verify(sod, dg1, listOf(testCsca), today)
        assertTrue(report.sodSignatureValid)
        assertEquals(testCsca, report.csca)
        assertTrue(report.dg1HashValid)
        assertTrue(report.authentic)
        assertTrue(report.adult)
        assertEquals("SHA256", report.dataGroupHashAlgorithm)
        assertEquals("SHA256WITHRSA", report.dscSignatureAlgorithm)
        assertEquals(4096, report.dscKeyBits)
    }

    @Test
    fun tamperedDg1IsRejected() {
        val tampered = dg1.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val report = SodVerifier.verify(sod, tampered, listOf(testCsca), today)
        assertFalse(report.dg1HashValid)
        assertFalse(report.authentic)
    }

    @Test
    fun cardNotSignedByChileIsRejected() {
        val report = SodVerifier.verify(sod, dg1, ChileCsca.keys, today)
        assertTrue(report.sodSignatureValid)
        assertNull(report.csca)
        assertFalse(report.authentic)
    }

    @Test
    fun underageThresholdIsRejected() {
        assertFalse(SodVerifier.verify(sod, dg1, listOf(testCsca), today, minAge = 40).adult)
    }

    @Test
    fun expiredCardIsRejected() {
        // The mock card expires 2030-01-01; on that day it already counts as expired.
        assertFalse(SodVerifier.verify(sod, dg1, listOf(testCsca), LocalDate.of(2029, 12, 31)).expired)
        assertTrue(SodVerifier.verify(sod, dg1, listOf(testCsca), LocalDate.of(2030, 1, 1)).expired)
    }

    @Test
    fun chipWrappedSodIsAccepted() {
        val len = sod.size
        val wrapped = byteArrayOf(0x77, 0x82.toByte(), (len shr 8).toByte(), len.toByte()) + sod
        assertTrue(SodVerifier.verify(wrapped, dg1, listOf(testCsca), today).authentic)
    }

    @Test
    fun chileHasFourCscaKeys() {
        assertEquals(listOf(3072, 3072, 3072, 4096), ChileCsca.keys.map { it.modulus.bitLength() })
    }
}
