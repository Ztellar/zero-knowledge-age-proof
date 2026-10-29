package cl.zkage.app

import java.io.File
import net.sf.scuba.data.Gender
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChipPhotoTest {
    /** A public-domain portrait as JPEG 2000, never a real card's photo. */
    private val jp2 = File("../../prover/facematch/tests/faces/biden-2013-chip.jp2").readBytes()

    private fun dg2(vararg images: ByteArray): ByteArray = DG2File(
        images.map { image ->
            FaceInfo(
                listOf(
                    FaceImageInfo(
                        Gender.UNSPECIFIED, FaceImageInfo.EyeColor.UNSPECIFIED, 0, FaceImageInfo.HAIR_COLOR_UNSPECIFIED, 0,
                        intArrayOf(0, 0, 0), intArrayOf(0, 0, 0), FaceImageInfo.FACE_IMAGE_TYPE_FULL_FRONTAL,
                        FaceImageInfo.IMAGE_COLOR_SPACE_RGB24, 0, 0, 0, emptyArray(), 240, 300,
                        image.inputStream(), image.size, FaceImageInfo.IMAGE_DATA_TYPE_JPEG2000,
                    ),
                ),
            )
        },
    ).encoded

    @Test
    fun photoComesOutAsStored() {
        val photo = ChipPhoto.from(dg2(jp2))!!
        assertArrayEquals(jp2, photo.bytes)
        assertEquals("image/jp2", photo.mimeType)
    }

    @Test
    fun dg2WithoutFacesHasNoPhoto() {
        assertNull(ChipPhoto.from(dg2()))
    }
}
