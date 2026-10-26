package cl.zkage.app

import android.nfc.Tag
import android.nfc.tech.IsoDep
import net.sf.scuba.smartcards.IsoDepCardService
import cl.zkage.verifier.ActiveAuth
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG14File

/** The chip files the verifier needs, plus which access protocol opened them. */
class ChipData(val dg1: ByteArray, val sod: ByteArray, val protocol: String, val activeAuth: ActiveAuthAnswer?)

/**
 * The chip's answer to an Active Authentication challenge, or why there is none. Stays on the
 * phone: DG15 is a key unique to this card.
 */
class ActiveAuthAnswer(
    val dg15: ByteArray,
    val challenge: ByteArray,
    val response: ByteArray?,
    /** DG14's ActiveAuthenticationInfo algorithm; ICAO requires it for EC keys. */
    val ecSignatureOid: String?,
    val error: String?,
)

/** Access key printed on the card: document number and two YYMMDD dates. */
data class AccessKey(val documentNumber: String, val birth: String, val expiry: String)

object CardReader {
    /**
     * Blocking: call off the main thread. Tries PACE first and falls back to BAC. If the chip
     * has Active Authentication, it signs [aaChallenge] (8 bytes): the start of the device
     * key hash, so the proof can show the chip answered for this phone (decision 0006).
     */
    fun read(tag: Tag, key: AccessKey, aaChallenge: ByteArray): ChipData {
        val isoDep = IsoDep.get(tag) ?: error("the tag is not ISO-DEP")
        isoDep.timeout = 10_000
        val card = IsoDepCardService(isoDep)
        card.open()
        try {
            val service = PassportService(
                card,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false,
            )
            service.open()
            val bacKey = BACKey(key.documentNumber, key.birth, key.expiry)

            val pace = runCatching {
                val access = CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE))
                val info = access.securityInfos.filterIsInstance<PACEInfo>().first()
                service.doPACE(bacKey, info.objectIdentifier, PACEInfo.toParameterSpec(info.parameterId), info.parameterId)
            }
            service.sendSelectApplet(pace.isSuccess)
            if (pace.isFailure) service.doBAC(bacKey)

            fun file(fid: Short) = service.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE).use { it.readBytes() }
            val dg1 = file(PassportService.EF_DG1)
            val sod = file(PassportService.EF_SOD)
            // A chip without DG15 has no Active Authentication: reading it fails.
            val activeAuth = runCatching { file(PassportService.EF_DG15) }.getOrNull()?.let { dg15 ->
                val ecOid = runCatching {
                    DG14File(service.getInputStream(PassportService.EF_DG14, PassportService.DEFAULT_MAX_BLOCKSIZE))
                        .activeAuthenticationInfos.firstOrNull()?.signatureAlgorithmOID
                }.getOrNull()
                val response = runCatching {
                    // JMRTD only echoes the two algorithm names; ActiveAuth reads the real ones.
                    service.doAA(ActiveAuth.publicKey(dg15), null, null, aaChallenge).response
                }
                ActiveAuthAnswer(dg15, aaChallenge, response.getOrNull(), ecOid, response.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" })
            }
            return ChipData(dg1, sod, if (pace.isSuccess) "PACE" else "BAC", activeAuth)
        } finally {
            card.close()
        }
    }
}
