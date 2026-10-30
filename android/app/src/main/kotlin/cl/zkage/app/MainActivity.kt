package cl.zkage.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import cl.zkage.app.ui.AcceptedScreen
import cl.zkage.app.ui.AppTheme
import cl.zkage.app.ui.BlockedScreen
import cl.zkage.app.ui.CardOkScreen
import cl.zkage.app.ui.ChipScreen
import cl.zkage.app.ui.ConfirmScreen
import cl.zkage.app.ui.FaceNoticeScreen
import cl.zkage.app.ui.FaceProgress
import cl.zkage.app.ui.FaceScreen
import cl.zkage.app.ui.FaceTimeoutScreen
import cl.zkage.app.ui.GeneratingScreen
import cl.zkage.app.ui.IntroScreen
import cl.zkage.app.ui.ManualScreen
import cl.zkage.app.ui.Mark
import cl.zkage.app.ui.MessageScreen
import cl.zkage.app.ui.NfcOffScreen
import cl.zkage.app.ui.NotAcceptedScreen
import cl.zkage.app.ui.ProveFailedScreen
import cl.zkage.app.ui.ReadFailedScreen
import cl.zkage.app.ui.ReadyScreen
import cl.zkage.app.ui.ScanScreen
import cl.zkage.app.ui.SendingScreen
import cl.zkage.app.ui.Side
import cl.zkage.verifier.ActiveAuth
import cl.zkage.verifier.Mrz
import cl.zkage.verifier.Presentation
import cl.zkage.verifier.Report
import cl.zkage.verifier.SodVerifier
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.File
import java.time.LocalDate
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

/**
 * Reads a real card, checks its signatures in Kotlin and, if nothing blocks it, matches the
 * face and proves the age claim with a STARK on the phone. The proving runs in
 * [ProverService]; this screen only shows [ProofJob]'s state, so it finds the proof running
 * or done when the user comes back. Screens follow the 2026-10-29 redesign.
 */
class MainActivity : ComponentActivity() {
    /** A card read whose signatures were checked. [photo] is null if the chip has no DG2. */
    private class Read(
        val report: Report,
        val chip: ChipData,
        /** Null when the chip has no DG15 or did not answer. */
        val activeAuth: Result<ActiveAuth.Result>?,
        /** The key whose hash the chip signed: the proof must be bound to this one. */
        val deviceKey: DeviceKey,
        val photo: Result<ChipPhoto>?,
        val key: AccessKey,
    ) {
        val chipCheck: ChipCheck
            get() {
                val answer = chip.activeAuth ?: return ChipCheck.Missing
                if (report.dg15HashValid != true) return ChipCheck.NotInSod
                if (answer.response == null) return ChipCheck.NoAnswer
                val result = activeAuth?.getOrNull() ?: return ChipCheck.NoAnswer
                return when {
                    !result.valid -> ChipCheck.Invalid
                    !result.provable -> ChipCheck.NotProvable
                    else -> ChipCheck.Ok
                }
            }

        val photoValid: Boolean get() = photo?.isSuccess == true && report.dg2HashValid == true
    }

    private sealed interface Screen {
        data object Home : Screen
        data object Scan : Screen
        data class Manual(val initial: AccessKey? = null, val note: String? = null) : Screen
        data object NfcOff : Screen
        data object Waiting : Screen
        data object Reading : Screen
        data class ReadFailed(val detail: String) : Screen
        data class CardOk(val read: Read) : Screen
        data class Blocked(val blocker: Blocker, val read: Read) : Screen
        data class FaceNotice(val read: Read) : Screen
        data class Face(val read: Read, val photo: ChipPhoto) : Screen
        data class FaceTimeout(val read: Read, val photo: ChipPhoto) : Screen
        data class Confirm(val request: Presentation.Request) : Screen
        data object Presenting : Screen
        data class Presented(val request: Presentation.Request, val outcome: Presenter.Outcome) : Screen
        data class Message(val title: String, val text: String) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var key: AccessKey? = null
    private var nfc: NfcAdapter? = null

    /** Once asked in this run, the "Avisarme" card does not come back. */
    private var notificationsAsked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Status bar icons follow the theme: dark on the white screens, light on the dark ones.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)
        setContent { AppTheme { App() } }
        onLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onLink(intent)
    }

    /** A `zkage://present` link, from the verifier page opened on this phone. */
    private fun onLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        answer(link)
    }

    private fun answer(link: String?) {
        val request = link?.let(Presentation::parse)
        screen = when {
            request == null -> Screen.Message("Ese código no es de un verificador de edad", "Escanea el código que muestra la página del verificador.")
            !Presenter.hasProof(this) -> Screen.Message("Todavía no tienes una prueba", "Primero genera tu prueba con tu cédula. Lo haces una sola vez.")
            else -> Screen.Confirm(request)
        }
    }

    /** Google's code scanner: no camera permission, the scanning UI runs in Play services. */
    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { answer(it.rawValue) }
            .addOnFailureListener { screen = Screen.Message("No se pudo abrir el lector de códigos", it.message ?: "") }
    }

    private fun present(request: Presentation.Request) {
        screen = Screen.Presenting
        thread(name = "presenter") {
            val outcome = Presenter.present(this, request)
            runOnUiThread { screen = Screen.Presented(request, outcome) }
        }
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    override fun onResume() {
        super.onResume()
        when (screen) {
            Screen.Waiting -> listen()
            // Back from the NFC settings: go on if it is on now.
            Screen.NfcOff -> if (nfc?.isEnabled == true) key?.let(::start)
            else -> Unit
        }
    }

    // ——— Step 1 ———

    private fun openScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            screen = Screen.Scan
        } else {
            afterCamera = { granted ->
                screen = if (granted) Screen.Scan else Screen.Manual(note = "Sin permiso para la cámara, escribe los datos a mano.")
            }
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun start(accessKey: AccessKey) {
        key = accessKey
        if (nfc?.isEnabled != true) {
            screen = Screen.NfcOff
            return
        }
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
            // Before reading: the chip signs the start of this key's hash (decision 0006).
            val deviceKey = DeviceKey.forNewProof(this)
            val chip = CardReader.read(tag, accessKey, deviceKey.spkiSha256.copyOf(8))
            val aa = chip.activeAuth?.takeIf { it.response != null }?.let {
                runCatching { ActiveAuth.verify(it.dg15, it.challenge, it.response!!, it.ecSignatureOid) }
            }
            val photo = chip.dg2?.let { dg2 -> runCatching { ChipPhoto.from(dg2) ?: error("DG2 has no face image") } }
            val report = SodVerifier.verify(chip.sod, chip.dg1, dg15 = chip.activeAuth?.dg15, dg2 = chip.dg2)
            classify(Read(report, chip, aa, deviceKey, photo, accessKey))
        } catch (e: Exception) {
            Screen.ReadFailed("${e.javaClass.simpleName}: ${e.message}")
        }
        runOnUiThread {
            nfc?.disableReaderMode(this)
            screen = next
        }
    }

    /** Decision 0009: anything but a valid, provable chip answer stops here, before the face. */
    private fun classify(read: Read): Screen {
        val today = LocalDate.now()
        val dates = runCatching { Mrz.dates(Mrz.td1(read.chip.dg1), today) }.getOrNull()
            ?: return Screen.Blocked(Blocker.Altered, read)
        val blocker = Gate.check(
            signaturesValid = read.report.sodSignatureValid && read.report.dg1HashValid,
            issuerKnown = read.report.csca != null,
            chip = read.chipCheck,
            photoValid = read.photoValid,
            birth = dates.birth,
            expiry = dates.expiry,
            today = today,
        )
        return if (blocker == null) Screen.CardOk(read) else Screen.Blocked(blocker, read)
    }

    // ——— Step 2 and 3 ———

    private fun matchFace(read: Read) {
        val photo = read.photo?.getOrNull() ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            screen = Screen.Face(read, photo)
        } else {
            afterCamera = { granted ->
                screen = if (granted) {
                    Screen.Face(read, photo)
                } else {
                    Screen.Message("Sin cámara no se puede seguir", "La app necesita la cámara para comparar tu cara con la foto del chip. Puedes darle permiso en los ajustes del teléfono.")
                }
            }
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** Starts as soon as the face matched: the person already asked for the proof. */
    private fun prove(read: Read) {
        val csca = read.report.csca ?: return
        ProofJob.start(this, csca, read.chip, read.deviceKey, read.chip.activeAuth)
        key = null
        screen = Screen.Home
    }

    private val needsNotificationPermission: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private var afterCamera: ((Boolean) -> Unit)? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        afterCamera?.invoke(granted)
        afterCamera = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAsked = true
    }

    private fun home() {
        key = null
        screen = Screen.Home
    }

    @Composable
    private fun App() {
        val job by ProofJob.state.collectAsState()
        BackHandler(enabled = job !is ProofJob.State.Running && screen != Screen.Home && screen != Screen.Reading) {
            screen = when (val s = screen) {
                is Screen.Manual -> Screen.Scan
                Screen.Waiting, Screen.NfcOff -> Screen.Manual(key)
                is Screen.Face -> Screen.CardOk(s.read)
                is Screen.FaceNotice -> Screen.CardOk(s.read)
                else -> Screen.Home.also { key = null }
            }
        }
        when (val j = job) {
            is ProofJob.State.Running -> Generating(j)
            is ProofJob.State.Failed -> ProveFailedScreen(
                j.message, j.detail, ProofJob.canRetry, Presenter.hasProof(this),
                onRetry = { ProofJob.retry(this) },
                onHome = { ProofJob.reset(); home() },
            )
            else -> Current(job as? ProofJob.State.Done)
        }
    }

    @Composable
    private fun Generating(running: ProofJob.State.Running) {
        var elapsed by remember { mutableLongStateOf(0L) }
        LaunchedEffect(running.startedAt) {
            while (true) {
                elapsed = (System.currentTimeMillis() - running.startedAt) / 1000
                delay(1000)
            }
        }
        GeneratingScreen(elapsed, needsNotificationPermission && !notificationsAsked) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Composable
    private fun Current(done: ProofJob.State.Done?) {
        when (val s = screen) {
            Screen.Home -> if (done != null || Presenter.hasProof(this)) {
                ReadyScreen(
                    provedOn(), done?.let(::proofDetails) ?: emptyList(),
                    onShow = { ProofJob.reset(); scanQr() },
                    onNew = { ProofJob.reset(); openScanner() },
                )
            } else {
                IntroScreen(::openScanner)
            }
            Screen.Scan -> Scan()
            is Screen.Manual -> ManualScreen(s.initial, s.note, ::start) { screen = Screen.Scan }
            Screen.NfcOff -> NfcOffScreen({ startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { screen = Screen.Manual(key) }
            Screen.Waiting -> ChipScreen(reading = false) { screen = Screen.Manual(key) }
            Screen.Reading -> ChipScreen(reading = true) {}
            is Screen.ReadFailed -> ReadFailedScreen(s.detail, onRetry = { key?.let(::start) }, onEdit = { screen = Screen.Manual(key) })
            is Screen.CardOk -> CardOkScreen(s.read.chipCheck == ChipCheck.Ok, readDetails(s.read)) { screen = Screen.FaceNotice(s.read) }
            is Screen.Blocked -> BlockedScreen(
                s.blocker, readDetails(s.read),
                onReadAgain = { start(s.read.key) },
                onOtherCard = ::openScanner,
                onHome = ::home,
            )
            is Screen.FaceNotice -> FaceNoticeScreen({ matchFace(s.read) }) { screen = Screen.CardOk(s.read) }
            is Screen.Face -> Face(s)
            is Screen.FaceTimeout -> FaceTimeoutScreen({ screen = Screen.Face(s.read, s.photo) }, ::home)
            is Screen.Confirm -> ConfirmScreen(s.request.origin, { present(s.request) }, ::home)
            Screen.Presenting -> SendingScreen()
            is Screen.Presented -> when (val o = s.outcome) {
                is Presenter.Outcome.Accepted -> AcceptedScreen(s.request.origin, ::home)
                is Presenter.Outcome.Rejected -> NotAcceptedScreen("El sitio rechazó tu prueba", Presenter.rejectionText(o.reason), null, null, ::home)
                is Presenter.Outcome.Failed -> NotAcceptedScreen(
                    "No se pudo enviar la prueba",
                    "Revisa tu conexión a internet y que el código no haya vencido.",
                    o.message, { present(s.request) }, ::home,
                )
            }
            is Screen.Message -> MessageScreen(s.title, s.text, ::home)
        }
    }

    @Composable
    private fun Scan() {
        val scanner = remember { MrzScanner(this) }
        DisposableEffect(scanner) { onDispose { scanner.close() } }
        val found by scanner.found.collectAsState()
        LaunchedEffect(found) { found?.let(::start) }
        // After 20 s without a valid read, suggest typing. A guess, to tune on real cards.
        var attempt by remember { mutableLongStateOf(0L) }
        var help by remember { mutableStateOf(false) }
        LaunchedEffect(attempt) {
            help = false
            delay(20_000)
            help = true
        }
        ScanScreen(
            camera = { modifier -> AndroidView({ PreviewView(it).also { view -> scanner.start(this, view) } }, modifier) },
            helpShown = help,
            onManual = { screen = Screen.Manual() },
            onKeepTrying = { attempt++ },
            onBack = ::home,
        )
    }

    @Composable
    private fun Face(s: Screen.Face) {
        val check = remember(s) { FaceCheck(this, s.photo) }
        DisposableEffect(check) { onDispose { check.close() } }
        val state by check.state.collectAsState()
        // A turn just done holds "¡Bien!" for a moment before asking the next one.
        val turnsDone = (state as? FaceCheck.State.Running)?.turnsDone
        var praising by remember(check) { mutableStateOf(false) }
        var checking by remember(check) { mutableStateOf(false) }
        val view = LocalView.current
        LaunchedEffect(turnsDone) {
            if ((turnsDone ?: 0) > 0) {
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                praising = true
                delay(900)
                praising = false
            }
        }
        val progress = when (val st = state) {
            is FaceCheck.State.Running -> faceProgress(st, praising)
            FaceCheck.State.Matched ->
                if (checking) {
                    FaceProgress("Comprobando que eres tú…", Mark.Done, Mark.Done, Mark.Done, Mark.Done, checking = true)
                } else {
                    FaceProgress("¡Bien!", Mark.Done, Mark.Done, Mark.Done, Mark.Done)
                }
            else -> null
        }
        // 30 s without a step forward ends the attempt. A guess, to tune with real users.
        LaunchedEffect(progress?.let { listOf(it.center, it.left, it.right, it.confirm) }) {
            delay(30_000)
            if (check.state.value !is FaceCheck.State.Matched) screen = Screen.FaceTimeout(s.read, s.photo)
        }
        if (state == FaceCheck.State.Matched) {
            LaunchedEffect(Unit) {
                // Not a check: the match is already decided. The pauses let the person see the
                // last step done, then it succeed, before the screen moves on.
                delay(900)
                checking = true
                delay(2_000)
                prove(s.read)
            }
        }
        FaceScreen(
            camera = { modifier -> AndroidView({ PreviewView(it).also { view -> check.start(this, view) } }, modifier) },
            progress = progress,
            error = (state as? FaceCheck.State.Failed)?.message,
            onCancel = { screen = Screen.CardOk(s.read) },
        )
    }

    private fun faceProgress(st: FaceCheck.State.Running, praising: Boolean): FaceProgress {
        val pastCenter = st.center == null
        val confirming = st.confirm != null
        val turning = pastCenter && !confirming
        // Wandering a little the wrong way is normal; only a clear turn the other way is wrong.
        val wrongWay = turning && !praising && (st.reach ?: 0f) <= -0.7f
        val instruction = when {
            !st.faceFound -> "Pon tu cara dentro del óvalo"
            // Turned faces score lower against the frontal photo: only judge it facing ahead.
            (!turning) && st.similarity < 0.363f -> "Mira de frente: tu cara aún no coincide con la foto del chip"
            praising -> "¡Bien!"
            confirming -> "Vuelve a mirar de frente"
            !pastCenter -> "Mira de frente a la cámara"
            wrongWay && st.turnsDone == 0 -> "Hacia el otro lado: a tu derecha"
            wrongWay -> "Hacia el otro lado: a tu izquierda"
            st.turnsDone == 0 -> "Gira la cabeza despacio hacia tu derecha"
            else -> "Ahora gira la cabeza hacia tu izquierda"
        }
        fun turn(index: Int) = when {
            st.turnsDone > index -> Mark.Done
            turning && st.turnsDone == index && !praising -> Mark.Current
            else -> Mark.Pending
        }
        return FaceProgress(
            instruction,
            center = if (pastCenter) Mark.Done else Mark.Current,
            right = turn(0),
            left = turn(1),
            confirm = if (confirming && !praising) Mark.Current else Mark.Pending,
            side = if (turning && !praising && st.faceFound) (if (st.turnsDone == 0) Side.Right else Side.Left) else null,
            reach = st.reach ?: 0f,
        )
    }

    private fun provedOn(): LocalDate? = runCatching {
        Proof.parse(File(filesDir, Presenter.PROOF_FILE).readBytes()).claim.today
    }.getOrNull()

    private fun readDetails(r: Read): List<String> {
        val report = r.report
        return listOfNotNull(
            "Acceso: ${r.chip.protocol}",
            "Firma del SOD: ${report.sodSignatureAlgorithm} (${if (report.sodSignatureValid) "válida" else "inválida"})",
            "Firma del DSC: ${report.dscSignatureAlgorithm}, RSA ${report.dscKeyBits} bits",
            "CSCA: ${report.csca?.label ?: "ninguna conocida de Chile"}",
            "Hash de DG1 en el SOD: ${if (report.dg1HashValid) "coincide" else "no coincide"}",
            "Hash de los data groups: ${report.dataGroupHashAlgorithm}",
            "Data groups en el SOD: ${report.dataGroups.joinToString(", ")}",
            "Active Authentication: ${r.chipCheck}",
            r.activeAuth?.getOrNull()?.let { "Clave de AA: ${it.algorithm} ${it.keyBits} bits${it.curve?.let { c -> " ($c)" } ?: ""}, ${it.digest}" },
            r.chip.activeAuth?.error?.let { "Respuesta del chip: error ($it)" },
            when (val photo = r.photo) {
                null -> "Foto (DG2): el chip no la tiene"
                else -> photo.fold({ "Foto (DG2): ${it.mimeType}, ${it.bytes.size} B" }, { "Foto (DG2): no se pudo leer (${it.message})" })
            },
            "DG1: ${r.chip.dg1.size} B, SOD: ${r.chip.sod.size} B",
        )
    }

    private fun proofDetails(d: ProofJob.State.Done): List<String> {
        val c = d.proof.claim
        return listOf(
            "Sistema: STARK sucinto (RISC Zero), post-cuántico",
            "Tiempo: ${"%.1f".format(d.seconds)} s",
            "Tamaño: ${d.proof.receipt.size} B",
            "CSCA: ${d.csca}",
            "Clave del teléfono: ML-DSA-65, ${d.presentation.keyStorage}",
            "Chip original (Active Authentication, P-256): ${if (c.chipAuthenticated) "sí" else "no"}",
            "Segmento del zkVM: 2^${d.segment.po2} (${d.segment.reason})",
            "Firma de prueba con la clave del teléfono: ${if (d.presentation.valid) "válida" else "inválida"}, ${d.presentation.signatureBytes} B en ${d.presentation.signMillis} ms",
        )
    }
}
