package cl.zkage.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The face match and liveness of `prover/facematch`, through `prover/ffi/src/face.rs`. */
object NativeFace {
    init {
        System.loadLibrary("age_prover")
    }

    /** Embeds the face in the chip photo; throws `IllegalArgumentException` if it has none. */
    external fun open(yunet: ByteArray, sface: ByteArray, chipPhoto: ByteArray): Long

    /**
     * `[face found, similarity, liveness state (0 center, 1 turns, 2 done, 3 confirm), center or
     * confirm frames, turns done (0 to 2), yaw, pitch, reach toward the side asked or NaN]`.
     */
    external fun frame(handle: Long, rgba: ByteArray, width: Int, height: Int, rotation: Int): FloatArray

    external fun close(handle: Long)
}

/**
 * Compares the front camera with the chip photo on the phone, with the liveness check of
 * decision 0007: a local check before proving. Frames and the photo stay in memory and are
 * never stored or sent; nothing of this enters the proof.
 */
class FaceCheck(private val context: Context, private val photo: ChipPhoto) {
    sealed interface State {
        data object Loading : State
        data class Running(
            val faceFound: Boolean,
            /** Cosine similarity to the chip photo; SFace calls 0.363 or more the same person. */
            val similarity: Float,
            /** Frames looking straight ahead so far, out of [CENTER_FRAMES]; null once past it. */
            val center: Int?,
            /** After the turns, frames looking straight ahead again; null before. */
            val confirm: Int?,
            /** Turns done, in the order asked: the person's own right, then their left. */
            val turnsDone: Int,
            /**
             * How far the head is turned toward the side asked now: 1 or more is enough, below 0
             * is the wrong side. Null outside the turns or without a face.
             */
            val reach: Float?,
            val millis: Long,
        ) : State
        data object Matched : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    /** One thread: frames, `open` and `close` never overlap. */
    private val worker = Executors.newSingleThreadExecutor()
    private var handle = 0L
    private var camera: ProcessCameraProvider? = null

    fun start(owner: LifecycleOwner, preview: PreviewView) {
        worker.execute {
            try {
                val yunet = context.assets.open(YUNET).use { it.readBytes() }
                val sface = context.assets.open(SFACE).use { it.readBytes() }
                handle = NativeFace.open(yunet, sface, photo.bytes)
            } catch (e: Exception) {
                _state.value = State.Failed(
                    if (e is IllegalArgumentException) "No se encontró una cara en la foto del chip." else "${e.javaClass.simpleName}: ${e.message}",
                )
                return@execute
            }
            ContextCompat.getMainExecutor(context).execute { bind(owner, preview) }
        }
    }

    private fun bind(owner: LifecycleOwner, view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get().also { camera = it }
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    ).build(),
                )
                .build()
            analysis.setAnalyzer(worker, ::analyze)
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            if (handle == 0L || _state.value is State.Matched) return
            val started = SystemClock.elapsedRealtime()
            val out = NativeFace.frame(handle, rgba(it), it.width, it.height, it.imageInfo.rotationDegrees)
            val millis = SystemClock.elapsedRealtime() - started
            // Timing only: no image data, similarity or pose is logged.
            Log.d(TAG, "frame ${it.width}x${it.height} in $millis ms")
            _state.value = when (out[2].toInt()) {
                2 -> State.Matched
                else -> State.Running(
                    faceFound = out[0] == 1f,
                    similarity = out[1],
                    center = out[3].toInt().takeIf { out[2].toInt() == 0 },
                    confirm = out[3].toInt().takeIf { out[2].toInt() == 3 },
                    turnsDone = out[4].toInt(),
                    reach = out[7].takeUnless { it.isNaN() },
                    millis = millis,
                )
            }
            if (_state.value is State.Matched) ContextCompat.getMainExecutor(context).execute { camera?.unbindAll() }
        }
    }

    /** The RGBA plane without the row padding some devices add. */
    private fun rgba(image: ImageProxy): ByteArray {
        val plane = image.planes[0]
        val row = image.width * 4
        val out = ByteArray(row * image.height)
        val buffer = plane.buffer
        for (y in 0 until image.height) {
            buffer.position(y * plane.rowStride)
            buffer.get(out, y * row, row)
        }
        return out
    }

    /** Stops the camera and frees the native session, with the photo's embedding. */
    fun close() {
        camera?.unbindAll()
        worker.execute {
            NativeFace.close(handle)
            handle = 0L
        }
        worker.shutdown()
    }

    companion object {
        const val CENTER_FRAMES = 2
        private const val TAG = "FaceCheck"
        const val YUNET = "face_detection_yunet_2023mar.onnx"
        const val SFACE = "face_recognition_sface_2021dec.onnx"
    }
}
