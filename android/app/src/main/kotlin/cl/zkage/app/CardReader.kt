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
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso39794.FaceImageDataBlock

/**
 * The chip files the verifier needs, plus which access protocol opened them. [dg2] is the face
 * photo's data group, null if the chip has none: it stays in memory for the face match
 * (decision 0007) and is never stored or sent.
 */
class ChipData(val dg1: ByteArray, val sod: ByteArray, val protocol: String, val activeAuth: ActiveAuthAnswer?, val dg2: ByteArray?)

/** The photo inside DG2, JPEG or JPEG 2000, as the chip stores it. */
class ChipPhoto(val bytes: ByteArray, val mimeType: String) {
    companion object {
        /** The first face image in DG2, in either encoding JMRTD reads (ISO 19794-5 or 39794-5). */
        fun from(dg2: ByteArray): ChipPhoto? = DG2File(dg2.inputStream()).subRecords.firstNotNullOfOrNull { block ->
            when (block) {
                is FaceInfo -> block.faceImageInfos.firstOrNull()?.let { ChipPhoto(it.imageInputStream.readBytes(), it.mimeType) }
                is FaceImageDataBlock -> block.representationBlocks.firstNotNullOfOrNull { it.imageRepresentation2DBlock }
                    ?.let { ChipPhoto(it.representationData2DInputStream.readBytes(), it.representationData2DInputMimeType) }
                else -> null
            }
        }
    }
}

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
            // Some chips leave the photo out; the face match is skipped then.
            val dg2 = runCatching { file(PassportService.EF_DG2) }.getOrNull()
            return ChipData(dg1, sod, if (pace.isSuccess) "PACE" else "BAC", activeAuth, dg2)
        } finally {
            card.close()
        }
    }
}
