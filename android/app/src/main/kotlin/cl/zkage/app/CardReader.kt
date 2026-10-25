package cl.zkage.app

import android.nfc.Tag
import android.nfc.tech.IsoDep
import net.sf.scuba.smartcards.IsoDepCardService
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo

/** The chip files the verifier needs, plus which access protocol opened them. */
class ChipData(val dg1: ByteArray, val sod: ByteArray, val protocol: String)

/** Access key printed on the card: document number and two YYMMDD dates. */
data class AccessKey(val documentNumber: String, val birth: String, val expiry: String)

object CardReader {
    /** Blocking: call off the main thread. Tries PACE first and falls back to BAC. */
    fun read(tag: Tag, key: AccessKey): ChipData {
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
            return ChipData(file(PassportService.EF_DG1), file(PassportService.EF_SOD), if (pace.isSuccess) "PACE" else "BAC")
        } finally {
            card.close()
        }
    }
}
