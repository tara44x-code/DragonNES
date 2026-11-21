package com.dragonnes.emu.game

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dragonnes.emu.CrashReporter
import com.dragonnes.emu.data.AspectMode
import com.dragonnes.emu.data.CheatRepository
import com.dragonnes.emu.data.Game
import com.dragonnes.emu.data.GameSystem
import com.dragonnes.emu.data.PadTarget
import com.dragonnes.emu.data.Settings
import com.dragonnes.emu.data.TouchMode
import com.dragonnes.emu.device.DeviceProfile
import com.dragonnes.emu.device.DeviceProfiler
import com.dragonnes.emu.device.Tier
import com.dragonnes.emu.ui.Neon
import com.dragonnes.emu.ui.NeonTheme
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class GameActivity : ComponentActivity(), InputManager.InputDeviceListener {

    companion object {
        private const val EXTRA_GAME = "game"
        private const val EXTRA_ROM = "rom"
        const val CORE_NORMAL = "libfceumm_libretro_android.so"
        const val CORE_WIDE = CORE_NORMAL
        const val AUTO_SLOT = 0
        /** NES: two controller ports. */
        const val MAX_PLAYERS = 2
        private const val STICK_DEADZONE = 0.5f

        private const val EXTRA_RESUME = "resume"

        /** [resume]: continue from the last exit point (true) or start the game fresh (false). */
        fun start(context: Context, game: Game, rom: File, resume: Boolean = false) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_GAME, game.toJson().toString())
                    .putExtra(EXTRA_ROM, rom.absolutePath)
                    .putExtra(EXTRA_RESUME, resume)
            )
        }

        /** NES has no widescreen core; kept so state file names stay stable. */
        @Suppress("UNUSED_PARAMETER")
        fun usesWideCore(settings: Settings, game: Game) = false

        fun statesDir(context: Context) = File(context.filesDir, "states").apply { mkdirs() }

        /** States from the normal and the widescreen core are not compatible, so they are stored apart. */
        fun stateFile(context: Context, game: Game, slot: Int, wide: Boolean = usesWideCore(Settings(context), game)) =
            File(statesDir(context), "${game.crc}${if (wide) "_w" else ""}_$slot.state")
    }

    private lateinit var retroView: GLRetroView
    private lateinit var game: Game
    private lateinit var romPath: String
    private lateinit var settings: Settings
    private lateinit var profile: DeviceProfile
    private lateinit var cheats: CheatRepository
    private lateinit var sramFile: File
    private var wideCore = false

    @Volatile private var coreReady = false
    private var rewindEnabled = false
    private val rewindBuffer = ArrayDeque<ByteArray>()
    private var rewindMax = 30
    @Volatile private var rewinding = false

    private var fastForward by mutableStateOf(false)
    private var gamepadName by mutableStateOf<String?>(null)
    private var touchOverride by mutableStateOf<Boolean?>(null)   // set from the pause menu for this session
    private var aspect by mutableStateOf(AspectMode.ORIGINAL)
    private var message by mutableStateOf<String?>(null)
    private var lastSram: ByteArray? = null
    private var padMap: Map<Int, PadTarget> = emptyMap()

    // ---- Touch layout editor ----
    private var padLayout by mutableStateOf(PadLayout.DEFAULT)
    private var editingLayout by mutableStateOf(false)
    private var editSelected by mutableStateOf<PadPart?>(null)
    private var layoutBeforeEdit = PadLayout.DEFAULT

    // ---- Video recording ----
    private lateinit var recorder: com.dragonnes.emu.record.GameRecorder
    private var recording by mutableStateOf(false)
    private var savedVideo by mutableStateOf<android.net.Uri?>(null)

    // ---- Netplay ----
    private var netplay: com.dragonnes.emu.netplay.NetplaySession? = null
    private var netStatus by mutableStateOf<com.dragonnes.emu.netplay.NetplaySession.Status?>(null)
    private var lobby by mutableStateOf<Lobby>(Lobby.Menu)
    private var showLobby by mutableStateOf(false)
    private var lanHost: com.dragonnes.emu.netplay.LanHost? = null
    private var pendingTransport: com.dragonnes.emu.netplay.Transport? = null
    /** Last internet room (code, I am host), so we can rejoin it after leaving / losing the connection. */
    private var lastRoom: Pair<String, Boolean>? = null
    private var reconnectTries = 0
    private var netLog by mutableStateOf<List<String>>(emptyList())
    private fun netLogAdd(text: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        netLog = (netLog + "$t $text").takeLast(20)
    }
    private val netActive: Boolean
        get() = netplay != null && netStatus?.phase != com.dragonnes.emu.netplay.NetplaySession.Phase.ENDED

    /** Any unexpected error in background work is reported instead of killing the game. */
    private val safe = CoroutineExceptionHandler { _, e ->
        CrashReporter.crumb("coroutine error: ${e::class.java.simpleName}: ${e.message}")
        message = "خطأ داخلي: ${e::class.java.simpleName}"
    }

    private val menuLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.let(::handleMenuResult)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.dragonnes.emu.Integrity.isOfficial(this)) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Draw under the notch / punch-hole too, so "full screen" really is edge to edge.
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()

        game = Game.fromJson(JSONObject(intent.getStringExtra(EXTRA_GAME)!!))
        romPath = intent.getStringExtra(EXTRA_ROM)!!
        settings = Settings(this)
        profile = DeviceProfiler.get(this)
        cheats = CheatRepository(this)
        rewindEnabled = settings.rewind ?: profile.rewindByDefault
        aspect = settings.aspect
        padLayout = PadLayout.decode(settings.padLayout)
        padMap = settings.padMap
        wideCore = usesWideCore(settings, game)

        val savesDir = File(filesDir, "saves").apply { mkdirs() }
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        sramFile = File(savesDir, "${game.crc}.srm")

        val coreName = if (wideCore) CORE_WIDE else CORE_NORMAL
        val core = File(applicationInfo.nativeLibraryDir, coreName)
        CrashReporter.crumb("open '${game.title}' [${game.system}] core=$coreName aspect=$aspect rewind=$rewindEnabled tier=${profile.tier}")
        if (!core.exists()) {
            Toast.makeText(this, "المحرك غير موجود داخل التطبيق: $coreName", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val data = GLRetroViewData(this).apply {
            coreFilePath = core.absolutePath
            gameFilePath = romPath
            systemDirectory = systemDir.absolutePath
            savesDirectory = savesDir.absolutePath
            saveRAMState = sramFile.takeIf { it.exists() && it.length() > 0 }?.readBytes()
            shader = profile.shaderFor(settings.filter, settings)
            rumbleEventsEnabled = false
            preferLowLatencyAudio = true
            skipDuplicateFrames = profile.skipDuplicateFrames
            variables = settings.coreVariables()
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)
        recorder = com.dragonnes.emu.record.GameRecorder(applicationContext, game.title)
        retroView.afterFrameHook = { if (recorder.isRecording) recorder.onFrameDrawn(retroView.width, retroView.height) }

        lifecycleScope.launch(safe) {
            retroView.getGLRetroErrors().collect { code -> onEngineError(code) }
        }
        lifecycleScope.launch(safe) {
            retroView.getGLRetroEvents().first { it == GLRetroView.GLRetroEvents.FrameRendered }
            onCoreReady()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (editingLayout) { padLayout = layoutBeforeEdit; editingLayout = false }
                else if (showLobby) lobbyActions.closePanel() else openMenu()
            }
        })

        // Game controls must stay left-to-right even when the phone language is Arabic.
        setContent {
            NeonTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { GameScreen() }
            }
        }
    }

    // ---------- Screen ----------

    @Composable
    private fun GameScreen() {
        val showTouch = editingLayout || touchOverride ?: when (settings.touchMode) {
            TouchMode.AUTO -> gamepadName == null
            TouchMode.ALWAYS -> true
            TouchMode.HIDDEN -> false
        }

        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            // The view always covers the whole screen at native resolution; the engine itself
            // sizes the picture (core ratio, a forced ratio, or exactly the screen for FULL).
            var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            AndroidView(
                factory = { retroView },
                modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it },
            )
            LaunchedEffect(aspect, viewSize) { applyAspect(aspect, viewSize.width, viewSize.height) }

            if (showTouch) {
                TouchControls(
                    cfg = ControlsConfig(
                        system = game.system,
                        turboButtons = settings.turboButtons,
                        opacity = settings.controlsOpacity,
                        scale = settings.controlsScale,
                        haptics = settings.haptics,
                        rewindEnabled = rewindEnabled,
                    ),
                    fastForward = fastForward,
                    onKey = { code, pressed ->
                        if (coreReady) sendKey(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, ab(code))
                    },
                    onTurbo = ::setTurbo,
                    onDpad = { x, y -> if (coreReady) sendDpad(x, y) },
                    recording = recording,
                    onRecord = ::toggleRecording,
                    layout = padLayout,
                    editing = editingLayout,
                    selected = editSelected,
                    onSelect = { editSelected = it },
                    onMove = { part, pos -> padLayout = padLayout.with(part, pos) },
                    onMenu = ::openMenu,
                    onToggleFastForward = ::toggleFastForward,
                    onRewind = ::setRewinding,
                )
            } else {
                // Controls hidden: keep a faint menu button so the player is never stuck.
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(34.dp).alpha(0.35f)
                        .clip(CircleShape).background(Neon.Surface).clickable { openMenu() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Menu, "القائمة", tint = Neon.Cyan, modifier = Modifier.size(18.dp)) }
            }

            if (editingLayout) LayoutEditorBar(Modifier.align(Alignment.Center))
            if (recording) RecordingBadge(Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 14.dp))
            netStatus?.takeIf { netActive }?.let { NetBadge(it, Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 14.dp)) }
            savedVideo?.let { uri -> SavedVideoBanner(uri, Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp)) }

            if (showLobby) {
                NetplayPanel(lobby, netStatus?.takeIf { netActive }, onlineReady = relayUrl().isNotEmpty(), log = netLog, actions = lobbyActions)
            }

            message?.let {
                Text(
                    it, color = Neon.Text,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Neon.Surface.copy(alpha = 0.9f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LaunchedEffect(it) { delay(2200L + it.length * 35L); message = null }
            }
        }
    }

    /**
     * ORIGINAL: the core's own ratio. FULL: exactly the screen's ratio (no black bars at all).
     * Others: the chosen ratio, centred. Done inside the engine, so nothing is rescaled by Android.
     */
    private fun applyAspect(mode: AspectMode, width: Int, height: Int) {
        val ratio = when (mode) {
            AspectMode.ORIGINAL -> 0f
            AspectMode.FULL -> if (width > 0 && height > 0) width.toFloat() / height else return
            else -> mode.ratio ?: 0f
        }
        CrashReporter.crumb("aspect=$mode ratio=$ratio view=${width}x$height")
        retroView.aspectRatioOverride = ratio
    }

    // ---------- Core lifecycle ----------

    /** Only loading/GL failures are fatal. Cheat or save-state errors must never close the game. */
    private fun onEngineError(code: Int) {
        CrashReporter.crumb("engine error $code")
        when (code) {
            GLRetroView.ERROR_LOAD_LIBRARY, GLRetroView.ERROR_LOAD_GAME, GLRetroView.ERROR_GL_NOT_COMPATIBLE -> {
                Toast.makeText(this, "تعذر تشغيل اللعبة ($code) — تأكد من سلامة ملف اللعبة", Toast.LENGTH_LONG).show()
                finish()
            }
            GLRetroView.ERROR_CHEAT -> message = "أحد أكواد الجيتات غير صالح لهاي النسخة من اللعبة"
            GLRetroView.ERROR_SERIALIZATION -> {
                stateFile(this, game, AUTO_SLOT, wideCore).delete()
                message = "تعذر تحميل الحفظ"
            }
            else -> message = "تنبيه من المحرك ($code)"
        }
    }

    // ---- Crash guard: if the app dies right after resuming a state or applying cheats,
    // the next launch skips that step instead of crashing again.
    private val guardFile by lazy { File(filesDir, "guard_${game.crc}") }

    private fun recoverFromLastCrash() {
        if (!guardFile.exists()) return
        val pending = guardFile.readText()
        CrashReporter.crumb("recovering after crash during: $pending")
        if ("cheats" in pending) {
            cheats.save(game, cheats.load(game).map { it.copy(enabled = false) })
            message = "عطّلنا الجيتات لأنها سببت إغلاق اللعبة المرة السابقة"
        }
        if ("resume" in pending) stateFile(this, game, AUTO_SLOT, wideCore).delete()
        guardFile.delete()
    }

    private fun onCoreReady() {
        coreReady = true
        CrashReporter.crumb("core ready")
        recoverFromLastCrash()
        lifecycleScope.launch(Dispatchers.Default + safe) {
            val auto = stateFile(this@GameActivity, game, AUTO_SLOT, wideCore)
            val willResume = intent.getBooleanExtra(EXTRA_RESUME, false) && auto.exists() && auto.length() > 0
            val enabledCheats = cheats.load(game).count { it.enabled }
            if (willResume || enabledCheats > 0) {
                guardFile.writeText(listOfNotNull("resume".takeIf { willResume }, "cheats".takeIf { enabledCheats > 0 }).joinToString(","))
            }
            if (willResume) {
                CrashReporter.crumb("auto-resume (${auto.length()} bytes)")
                if (runCatching { retroView.unserializeState(auto.readBytes()) }.getOrDefault(false)) {
                    message = "تم الاستئناف من آخر نقطة لعبت بيها"
                }
            }
            if (enabledCheats > 0) { CrashReporter.crumb("apply $enabledCheats cheats"); applyCheats() }
            delay(5_000)          // survived 5 s -> it's safe
            guardFile.delete()
            CrashReporter.crumb("running OK")
        }
        // Periodic SRAM/auto-state save + rewind capture, only while the game is on screen.
        lifecycleScope.launch(safe) {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                withContext(Dispatchers.Default) {
                    var elapsed = 0L
                    val step = if (rewindEnabled) profile.rewindIntervalMs else 1_000L
                    while (isActive) {
                        delay(step)
                        if (rewindEnabled && !rewinding) captureRewind()
                        elapsed += step
                        if (elapsed >= 30_000) { elapsed = 0; saveSram(); saveAutoState() }
                    }
                }
            }
        }
    }

    private fun captureRewind() {
        val state = runCatching { retroView.serializeState() }.getOrNull() ?: return
        if (state.isEmpty()) return
        synchronized(rewindBuffer) {
            if (rewindBuffer.isEmpty()) {
                // Stay well inside the Java heap: at most 1/8 of it (and never above 64 MB).
                val budget = minOf(Runtime.getRuntime().maxMemory() / 8, 64L shl 20)
                rewindMax = (budget / state.size).toInt().coerceIn(10, 180)
                CrashReporter.crumb("rewind: state=${state.size / 1024}KB max=$rewindMax")
            }
            rewindBuffer.addLast(state)
            while (rewindBuffer.size > rewindMax) rewindBuffer.removeFirst()
        }
    }

    private fun setRewinding(on: Boolean) {
        if (!coreReady) return
        if (netActive) { if (on) message = "الرجوع بالزمن يتعطل وقت اللعب أونلاين"; return }
        rewinding = on
        if (!on) return
        lifecycleScope.launch(Dispatchers.Default + safe) {
            while (rewinding) {
                val state = synchronized(rewindBuffer) { rewindBuffer.removeLastOrNull() }
                if (state == null) { rewinding = false; break }
                retroView.unserializeState(state)
                delay(70)
            }
        }
    }

    // ---------- Turbo ----------

    /** Keys held in turbo mode -> controller port. A single loop presses/releases them all. */
    private val turboKeys = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private var turboJob: kotlinx.coroutines.Job? = null

    private fun setTurbo(rawKey: Int, pressed: Boolean, port: Int = 0) {
        if (!coreReady) return
        val keyCode = ab(rawKey)
        if (pressed) {
            turboKeys[keyCode] = port
            if (turboJob?.isActive != true) turboJob = lifecycleScope.launch(safe) {
                val half = (500L / settings.turboSpeed.hz).coerceAtLeast(16L)
                var down = true
                while (turboKeys.isNotEmpty()) {
                    turboKeys.forEach { (k, p) ->
                        sendKey(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, k, p)
                    }
                    down = !down
                    delay(half)
                }
            }
        } else {
            turboKeys.remove(keyCode)
            sendKey(KeyEvent.ACTION_UP, keyCode, port)
        }
    }

    private fun toggleFastForward() {
        if (!coreReady) return
        if (netActive) { message = "التسريع يتعطل وقت اللعب أونلاين"; return }
        fastForward = !fastForward
        retroView.frameSpeed = if (fastForward) (if (profile.tier == Tier.LOW) 2 else 3) else 1
        CrashReporter.crumb("fast-forward=$fastForward")
    }

    /** Core calls happen only while the game is running (never while it is paused). */
    private fun saveSram() {
        if (!coreReady) return
        runCatching {
            val bytes = retroView.serializeSRAM()
            if (bytes.isEmpty() || bytes.contentEquals(lastSram)) return
            lastSram = bytes
            sramFile.writeBytes(bytes)
        }
    }

    /** Snapshot used by "auto resume" next time the game is opened. */
    private fun saveAutoState() {
        if (!coreReady || guardFile.exists()) return
        runCatching {
            val bytes = retroView.serializeState()
            if (bytes.isNotEmpty()) stateFile(this, game, AUTO_SLOT, wideCore).writeBytes(bytes)
        }
    }

    /**
     * Clear the core's cheat list, then send ONLY enabled cheats (Game Genie "SXIOPO" or raw "0025:63"),
     * never more than [CheatRepository.MAX_PARTS] code parts in total.
     */
    private suspend fun applyCheats() = withContext(Dispatchers.Default) {
        val enabled = cheats.load(game).filter { it.enabled }
        onEmulationThread { LibretroDroid.resetCheat() }
        var parts = 0
        var skipped = 0
        enabled.forEachIndexed { i, c ->
            val code = c.code.uppercase().replace(" ", "")
            val p = CheatRepository.parts(code)
            if (parts + p > CheatRepository.MAX_PARTS) { skipped++; return@forEachIndexed }
            parts += p
            runCatching { retroView.setCheat(i, true, code) }
        }
        CrashReporter.crumb("cheats applied: ${enabled.size - skipped} ($parts parts), skipped $skipped")
        if (skipped > 0) message = "تم تفعيل ${enabled.size - skipped} جيت • $skipped ما تفعّل لأن المحرك وصل الحد"
    }

    /** Runs [block] on the GL/emulation thread and waits for it (same as LibretroDroid's own calls). */
    private fun onEmulationThread(block: () -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1)
        retroView.queueEvent { try { runCatching(block) } finally { latch.countDown() } }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        if (::recorder.isInitialized && recorder.isRecording) retroView.queueEvent { recorder.stopOnGlThread { } }
        netplay?.stop("صديقك طلع من اللعبة")
        lanHost?.stop(); pendingTransport?.close()
        super.onDestroy()
        if (isFinishing && ::game.isInitialized) {
            guardFile.delete() // normal exit, not a crash
            CrashReporter.crumb("closed normally")
        }
    }

    // ---------- Menu ----------

    private var menuOpening = false

    /** Saves first (while the core is still running), then opens the pause menu. */
    private fun openMenu() {
        if (menuOpening) return
        menuOpening = true
        lifecycleScope.launch(safe) {
            withContext(Dispatchers.Default) { saveSram(); saveAutoState() }
            menuLauncher.launch(GameMenuActivity.intent(this@GameActivity, game))
            menuOpening = false
        }
    }

    private fun handleMenuResult(data: Intent) {
        if (data.getBooleanExtra(GameMenuActivity.RES_COLORS_CHANGED, false)) {
            val vars = settings.coreVariables()
            retroView.queueEvent { runCatching { retroView.updateVariables(*vars) } }
            CrashReporter.crumb("colors: ${vars.joinToString { "${it.key}=${it.value}" }}")
        }
        if (data.getBooleanExtra(GameMenuActivity.RES_FILTER_CHANGED, false)) {
            retroView.shader = profile.shaderFor(settings.filter, settings)
        }
        if (data.getBooleanExtra(GameMenuActivity.RES_ASPECT_CHANGED, false)) {
            val newAspect = settings.aspect
            if (usesWideCore(settings, game) != wideCore) {
                // Switching to/from the widescreen core needs the engine to restart.
                CrashReporter.crumb("restart for core change")
                lifecycleScope.launch(safe) {
                    withContext(Dispatchers.Default) { saveSram() }
                    finish(); start(this@GameActivity, game, File(romPath), resume = false)
                }
                return
            }
            aspect = newAspect
        }
        if (data.getBooleanExtra(GameMenuActivity.RES_CHEATS_CHANGED, false) && netActive) {
            message = "الجيتات تتفعّل بعد ما تخلص اللعب أونلاين"
        } else if (data.getBooleanExtra(GameMenuActivity.RES_CHEATS_CHANGED, false)) {
            lifecycleScope.launch(safe) {
                guardFile.writeText("cheats")
                CrashReporter.crumb("cheats changed")
                applyCheats(); message = "تم تحديث الجيتات"
                delay(5_000); guardFile.delete()
            }
        }
        val slot = data.getIntExtra(GameMenuActivity.RES_SLOT, 0)
        val action = data.getStringExtra(GameMenuActivity.RES_ACTION) ?: return
        CrashReporter.crumb("menu action=$action slot=$slot")
        if (netActive && action in setOf(GameMenuActivity.ACTION_LOAD, GameMenuActivity.ACTION_RESET, GameMenuActivity.ACTION_SWAP_PLAYERS)) {
            message = "هذا الخيار يتعطل وقت اللعب أونلاين"
            return
        }
        when (action) {
            GameMenuActivity.ACTION_RECORD -> toggleRecording()
            GameMenuActivity.ACTION_EDIT_LAYOUT -> {
                layoutBeforeEdit = padLayout; editSelected = null; editingLayout = true
            }
            GameMenuActivity.ACTION_NETPLAY -> { if (!netActive) lobby = Lobby.Menu; showLobby = true }
            GameMenuActivity.ACTION_SAVE -> lifecycleScope.launch(Dispatchers.Default + safe) {
                val ok = runCatching { stateFile(this@GameActivity, game, slot, wideCore).writeBytes(retroView.serializeState()) }.isSuccess
                message = if (ok) "تم الحفظ في الخانة $slot" else "فشل الحفظ"
            }
            GameMenuActivity.ACTION_LOAD -> lifecycleScope.launch(Dispatchers.Default + safe) {
                val f = stateFile(this@GameActivity, game, slot, wideCore)
                val ok = f.exists() && runCatching { retroView.unserializeState(f.readBytes()) }.getOrDefault(false)
                synchronized(rewindBuffer) { rewindBuffer.clear() }
                message = if (ok) "تم التحميل من الخانة $slot" else "الخانة $slot فارغة"
            }
            GameMenuActivity.ACTION_RESET -> lifecycleScope.launch(Dispatchers.Default + safe) {
                retroView.reset(); synchronized(rewindBuffer) { rewindBuffer.clear() }
                stateFile(this@GameActivity, game, AUTO_SLOT, wideCore).delete()
            }
            GameMenuActivity.ACTION_TOUCH -> {
                val visibleNow = touchOverride ?: (settings.touchMode == TouchMode.ALWAYS ||
                    (settings.touchMode == TouchMode.AUTO && gamepadName == null))
                touchOverride = !visibleNow
            }
            GameMenuActivity.ACTION_SWAP_PLAYERS -> swapPlayers()
            GameMenuActivity.ACTION_QUIT -> finish() // state + SRAM were saved when the menu opened
        }
    }

    // ---------- Physical controllers ----------

    private val inputManager by lazy { getSystemService(Context.INPUT_SERVICE) as InputManager }

    /** Player option "swap A and B" (Settings), applied to every input source. */
    private var swapAB = false

    private fun ab(code: Int): Int = if (!swapAB) code else when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        else -> code
    }

    override fun onResume() {
        super.onResume()
        if (netActive) netplay?.setPaused(false)
        swapAB = settings.swapAB
        hideSystemBars()
        padMap = settings.padMap
        refreshGamepads(announce = false)
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
    }

    override fun onPause() {
        if (netActive) netplay?.setPaused(true)
        inputManager.unregisterInputDeviceListener(this)
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshGamepads(announce = true)
    override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepads(announce = true)
    override fun onInputDeviceChanged(deviceId: Int) = refreshGamepads(announce = false)

    // ---------- Input routing (local engine or netplay) ----------

    /** Every button press ends here: straight to the engine, or into our netplay pad state. */
    private fun sendKey(action: Int, keyCode: Int, port: Int = 0) {
        val np = netplay
        if (np != null && netActive) {
            val bit = when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> com.dragonnes.emu.netplay.Pad.A
                KeyEvent.KEYCODE_BUTTON_B -> com.dragonnes.emu.netplay.Pad.B
                KeyEvent.KEYCODE_BUTTON_SELECT -> com.dragonnes.emu.netplay.Pad.SELECT
                KeyEvent.KEYCODE_BUTTON_START -> com.dragonnes.emu.netplay.Pad.START
                KeyEvent.KEYCODE_DPAD_UP -> com.dragonnes.emu.netplay.Pad.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> com.dragonnes.emu.netplay.Pad.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> com.dragonnes.emu.netplay.Pad.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> com.dragonnes.emu.netplay.Pad.RIGHT
                else -> 0
            }
            if (bit != 0) np.localMask = if (action == KeyEvent.ACTION_DOWN) np.localMask or bit else np.localMask and bit.inv()
            return
        }
        retroView.sendKeyEvent(action, keyCode, port)
    }

    private fun sendDpad(x: Float, y: Float, port: Int = 0) {
        val np = netplay
        if (np != null && netActive) {
            val P = com.dragonnes.emu.netplay.Pad
            var m = np.localMask and (P.UP or P.DOWN or P.LEFT or P.RIGHT).inv()
            if (x > 0.5f) m = m or P.RIGHT else if (x < -0.5f) m = m or P.LEFT
            if (y > 0.5f) m = m or P.DOWN else if (y < -0.5f) m = m or P.UP
            np.localMask = m
            return
        }
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y, port)
    }

    // ---------- Touch layout editor ----------

    @Composable
    private fun LayoutEditorBar(modifier: Modifier) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(18.dp)).background(Neon.Surface.copy(alpha = 0.92f)).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("✋ اسحب أي زر لمكانه", color = Neon.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 15.sp)
                val part = editSelected
                if (part == null) {
                    Text("اضغط على زر حتى تغيّر حجمه", color = Neon.TextDim, fontSize = 12.sp)
                } else {
                    val pos = padLayout[part]
                    Text("حجم ${partName(part)}: ${(pos.scale * 100).toInt()}%", color = Neon.Cyan, fontSize = 13.sp)
                    androidx.compose.material3.Slider(
                        pos.scale, { padLayout = padLayout.with(part, pos.copy(scale = it)) }, valueRange = 0.5f..2f,
                        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Neon.Magenta, activeTrackColor = Neon.Cyan),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Button({
                        settings.padLayout = padLayout.encode(); editingLayout = false; message = "انحفظت أماكن الأزرار ✅"
                    }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Neon.Cyan)) {
                        Text("حفظ", color = Neon.Bg, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                    androidx.compose.material3.OutlinedButton({ padLayout = PadLayout.DEFAULT; editSelected = null }) {
                        Text("الافتراضي", color = Neon.Text)
                    }
                    androidx.compose.material3.OutlinedButton({ padLayout = layoutBeforeEdit; editingLayout = false }) {
                        Text("إلغاء", color = Neon.Magenta)
                    }
                }
            }
        }
    }

    private fun partName(p: PadPart) = when (p) {
        PadPart.BAR -> "الشريط الفوكاني"; PadPart.DPAD -> "الأسهم"; PadPart.A -> "A"; PadPart.B -> "B"
        PadPart.TA -> "Turbo A"; PadPart.TB -> "Turbo B"; PadPart.SELECT -> "SELECT"; PadPart.START -> "START"
    }

    // ---------- Video recording ----------

    private fun toggleRecording() {
        if (!coreReady) return
        if (recording) {
            recording = false
            retroView.queueEvent {
                recorder.stopOnGlThread { uri ->
                    runOnUiThread {
                        if (uri != null) { savedVideo = uri; message = "🎬 انحفظ الفيديو بالمعرض (Movies/DragonNES)" }
                        else message = "تعذر حفظ الفيديو: ${recorder.lastProblem}"
                    }
                }
            }
        } else {
            val w = retroView.width; val h = retroView.height
            retroView.queueEvent {
                val error = recorder.startOnGlThread(w, h)
                runOnUiThread {
                    if (error == null) { recording = true; savedVideo = null } else message = error
                }
            }
        }
    }

    @Composable
    private fun RecordingBadge(modifier: Modifier) {
        var ms by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) { while (true) { ms = recorder.elapsedMs(); delay(250) } }
        val blink = (ms / 500) % 2 == 0L
        Row(
            modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                .clickable { toggleRecording() }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (blink) Color(0xFFFF2D2D) else Color(0x55FF2D2D)))
            Spacer(Modifier.width(8.dp))
            val s = ms / 1000
            Text("REC %02d:%02d".format(s / 60, s % 60), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }

    @Composable
    private fun SavedVideoBanner(uri: android.net.Uri, modifier: Modifier) {
        LaunchedEffect(uri) { delay(8000); if (savedVideo == uri) savedVideo = null }
        Row(
            modifier.clip(RoundedCornerShape(16.dp)).background(Neon.Surface.copy(alpha = 0.95f)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🎬 الفيديو جاهز", color = Neon.Text, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Text("مشاركة", color = Neon.Cyan, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { shareVideo(uri) }.padding(6.dp))
            Text("✕", color = Neon.TextDim, modifier = Modifier.clickable { savedVideo = null }.padding(6.dp))
        }
    }

    private fun shareVideo(uri: android.net.Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "${game.title} — مسجّل بـ DragonNES 🐉")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "مشاركة الفيديو"))
    }

    // ---------- Netplay ----------

    private fun relayUrl(): String = settings.relayUrl.ifBlank { com.dragonnes.emu.BuildConfig.RELAY_URL }

    private fun playerName(): String = settings.playerName

    @Composable
    private fun NetBadge(st: com.dragonnes.emu.netplay.NetplaySession.Status, modifier: Modifier) {
        val me = if (netplay?.isHost == true) "لاعب 1" else "لاعب 2"
        val text = when {
            st.waitingRejoin -> "⏳ صديقك طلع — بانتظار يرجع" + (lastRoom?.first?.let { " • $it" } ?: "")
            st.peerPaused -> "⏸ صديقك فاتح القائمة"
            st.waitingForPeer -> "⏳ بانتظار صديقك…"
            st.phase == com.dragonnes.emu.netplay.NetplaySession.Phase.SYNCING -> st.note ?: "جاري المزامنة…"
            else -> (if (st.label == "إنترنت") "🌐 " else "🏠 ") + me + " • ويه ${st.peerName ?: "صديقك"}" + (st.pingMs?.let { " • ${it}ms" } ?: "")
        }
        Text(
            text, color = Color.White, fontSize = 12.sp,
            modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                .clickable { showLobby = true }.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    private fun startSession(transport: com.dragonnes.emu.netplay.Transport, host: Boolean) {
        pendingTransport = null
        netplay?.stop()
        // Stop things that would make the two games differ.
        if (fastForward) { fastForward = false; retroView.frameSpeed = 1 }
        rewinding = false
        val session = com.dragonnes.emu.netplay.NetplaySession(
            transport, host, game.crc, game.title, playerName(),
            engine = object : com.dragonnes.emu.netplay.NetplaySession.Engine {
                override fun serializeState(): ByteArray = retroView.serializeState()
                override fun unserializeState(state: ByteArray): Boolean = retroView.unserializeState(state)
                override fun resetCheats() { LibretroDroid.resetCheat() }
            },
            onStatus = { st -> runOnUiThread { onNetStatus(st) } },
            onLog = { t -> runOnUiThread { netLogAdd(t) } },
        )
        netplay = session
        // Release anything held locally, then hand the controls to the lockstep engine.
        for (p in 0 until MAX_PLAYERS) {
            listOf(NesKeys.A, NesKeys.B, NesKeys.START, NesKeys.SELECT).forEach { retroView.sendKeyEvent(KeyEvent.ACTION_UP, it, p) }
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f, p)
        }
        turboKeys.clear()
        retroView.lockstepHook = session::beforeFrame
        session.start()
    }

    private fun onNetStatus(st: com.dragonnes.emu.netplay.NetplaySession.Status) {
        val before = netStatus
        netStatus = st
        if (st.phase == com.dragonnes.emu.netplay.NetplaySession.Phase.PLAYING &&
            before?.phase != com.dragonnes.emu.netplay.NetplaySession.Phase.PLAYING) {
            showLobby = false
            message = if (netplay?.isHost == true) "🎮 بدأ اللعب! إنت لاعب 1" else "🎮 بدأ اللعب! إنت لاعب 2"
        }
        st.note?.takeIf { it != before?.note && st.phase == com.dragonnes.emu.netplay.NetplaySession.Phase.PLAYING }?.let { message = it }
        if (st.phase == com.dragonnes.emu.netplay.NetplaySession.Phase.ENDED) {
            val wasHost = netplay?.isHost == true
            retroView.lockstepHook = null
            netplay = null
            lanHost?.stop(); lanHost = null
            lobby = Lobby.Menu
            val room = lastRoom
            if (st.lost && room != null && reconnectTries < 8) {
                // Internet connection dropped: rejoin the same room automatically (same player number).
                reconnectTries++
                message = "انقطع الاتصال — جاري إعادة الاتصال ($reconnectTries)…"
                lifecycleScope.launch(safe) {
                    delay(1500L * reconnectTries)
                    if (netplay == null) { if (room.second) lobbyActions.hostRoom(room.first) else lobbyActions.joinOnline(room.first) }
                }
            } else {
                message = (st.endReason ?: "انتهى اللعب أونلاين") + " — هسه تلعب لوحدك"
                if (!wasHost) reconnectTries = 0
            }
        }
        if (st.phase == com.dragonnes.emu.netplay.NetplaySession.Phase.PLAYING) reconnectTries = 0
    }

    private fun rememberRoom(code: String, host: Boolean) {
        lastRoom = code to host
        getSharedPreferences("netplay", MODE_PRIVATE).edit()
            .putString("code", code).putBoolean("host", host).putString("crc", game.crc)
            .putLong("time", System.currentTimeMillis()).apply()
    }

    /** Internet room used with this game in the last 30 minutes. */
    private fun savedRoom(): Pair<String, Boolean>? {
        val p = getSharedPreferences("netplay", MODE_PRIVATE)
        val code = p.getString("code", null) ?: return null
        if (p.getString("crc", null) != game.crc) return null
        if (System.currentTimeMillis() - p.getLong("time", 0) > 30 * 60_000L) return null
        return code to p.getBoolean("host", false)
    }

    private val lobbyActions = object : LobbyActions {
        override fun hostLan() {
            val host = com.dragonnes.emu.netplay.LanHost(this@GameActivity, playerName(), game.title)
            lanHost = host
            lobby = Lobby.HostingLan(com.dragonnes.emu.netplay.LanHost.localAddresses())
            netLog = emptyList(); netLogAdd("استضافة على الشبكة: ${com.dragonnes.emu.netplay.LanHost.localAddresses().joinToString()}")
            host.start(lifecycleScope,
                onGuest = { t -> runOnUiThread { lobby = Lobby.Connecting("صديقك دخل، جاري المزامنة…"); startSession(t, host = true) } },
                onError = { e -> runOnUiThread { lobby = Lobby.Failed(e) } },
            )
        }

        override fun findLan() {
            lobby = Lobby.FindingLan(scanning = true, games = emptyList())
            lifecycleScope.launch(safe) {
                val games = com.dragonnes.emu.netplay.LanHost.discover(this@GameActivity)
                if (lobby is Lobby.FindingLan) lobby = Lobby.FindingLan(scanning = false, games = games)
            }
        }

        override fun joinLan(address: String) {
            lobby = Lobby.Connecting("جاري الاتصال بـ $address…")
            netLog = emptyList(); netLogAdd("اتصال بـ $address")
            lifecycleScope.launch(safe) {
                runCatching { com.dragonnes.emu.netplay.LanHost.connect(address) }
                    .onSuccess { t -> lobby = Lobby.Connecting("متصل، جاري المزامنة…"); startSession(t, host = false) }
                    .onFailure { netLogAdd("فشل: ${it.message}"); lobby = Lobby.Failed("ما كدرت أتصل بـ $address. تأكدوا إنكم على نفس الشبكة.") }
            }
        }

        override fun hostOnline() = hostRoom((1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString(""))

        override fun rejoinLast() {
            val room = savedRoom() ?: return
            reconnectTries = 0
            if (room.second) hostRoom(room.first) else joinOnline(room.first)
        }

        override fun lastRoomLabel(): String? = savedRoom()?.let { (code, host) ->
            "↩ رجوع للغرفة $code (" + (if (host) "إنت المضيف • لاعب 1" else "لاعب 2") + ")"
        }

        fun hostRoom(code: String) {
            rememberRoom(code, host = true)
            lobby = Lobby.HostingOnline(code, connected = false)
            lateinit var transport: com.dragonnes.emu.netplay.RelayTransport
            transport = com.dragonnes.emu.netplay.RelayTransport.connect(relayUrl(), code, host = true) { ev ->
                runOnUiThread {
                    when {
                        (ev == "peer-joined" || ev == "peer-present") && netplay == null -> {
                            lobby = Lobby.HostingOnline(code, connected = true)
                            startSession(transport, host = true)
                        }
                        ev == "room-taken" -> lobby = Lobby.Failed("هذا الكود مستخدم، جرّب مرة ثانية")
                        ev == "peer-left" -> netplay?.peerLeft()
                        ev == "room-taken" && reconnectTries > 0 -> lifecycleScope.launch(safe) {
                            delay(2500); if (netplay == null) hostRoom(code)   // old connection not closed yet
                        }
                        ev.startsWith("error:") && netplay == null -> lobby = Lobby.Failed("ما كدرت أوصل للسيرفر. تأكد من الإنترنت.")
                    }
                }
            }
            pendingTransport = transport
        }

        override fun openJoinOnline() { lobby = Lobby.JoinOnline }

        override fun joinOnline(code: String) {
            rememberRoom(code, host = false)
            lobby = Lobby.Connecting("جاري الدخول للغرفة $code…")
            lateinit var transport: com.dragonnes.emu.netplay.RelayTransport
            transport = com.dragonnes.emu.netplay.RelayTransport.connect(relayUrl(), code, host = false) { ev ->
                runOnUiThread {
                    when {
                        (ev == "peer-present" || ev == "peer-joined") && netplay == null -> startSession(transport, host = false)
                        ev == "no-host" && reconnectTries in 1..7 -> {
                            // Rejoining: the host may still be reconnecting, try again shortly.
                            transport.close(); reconnectTries++
                            lobby = Lobby.Connecting("بانتظار المضيف يرجع ($reconnectTries)…")
                            lifecycleScope.launch(safe) { delay(1500L * reconnectTries); if (netplay == null) joinOnline(code) }
                        }
                        ev == "no-host" -> { transport.close(); lobby = Lobby.Failed("ما لكيت غرفة بهذا الكود. تأكد من الكود وإن صديقك بعده بالانتظار.") }
                        ev == "room-full" -> { transport.close(); lobby = Lobby.Failed("الغرفة مليانة") }
                        ev == "peer-left" -> netplay?.connectionLost("المضيف انقطع")
                        ev.startsWith("error:") && netplay == null -> lobby = Lobby.Failed("ما كدرت أوصل للسيرفر. تأكد من الإنترنت.")
                    }
                }
            }
            pendingTransport = transport
        }

        override fun shareCode(code: String) {
            val text = "تعال العب ويايه ${game.title} على DragonNES 🐉🎮\nافتح نفس اللعبة ← القائمة ← لعب أونلاين ← انضمام بالكود\nالكود: $code"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "دز الكود"))
        }

        override fun cancelLobby() {
            lanHost?.stop(); lanHost = null
            if (netplay == null) { pendingTransport?.close(); pendingTransport = null }
            lobby = Lobby.Menu
        }

        override fun closePanel() {
            if (!netActive) cancelLobby()
            showLobby = false
        }

        override fun endSession() {
            netplay?.stop()
            showLobby = false
        }
    }

    // ---------- Players (up to 2 controllers) ----------

    /** Connected controller (device id) -> player slot, in connection order. */
    private val padPorts = LinkedHashMap<Int, Int>()
    /** Swaps player 1 and 2 (from the pause menu). With one pad + touch, makes the pad player 2. */
    private var playersSwapped = false

    private fun connectedPads(): List<InputDevice> = InputDevice.getDeviceIds().asSequence()
        .mapNotNull { InputDevice.getDevice(it) }
        .filter { !it.isVirtual && isGamepad(it.sources) }
        .sortedBy { it.id }
        .toList()

    /** Engine port for a controller: first pad = player 1, second = player 2. */
    private fun portOf(device: InputDevice?): Int {
        val id = device?.id ?: return if (playersSwapped) 1 else 0
        if (id !in padPorts) refreshGamepads(announce = false)
        val slot = padPorts[id] ?: 0
        return if (playersSwapped) (MAX_PLAYERS - 1 - slot) else slot
    }

    private fun refreshGamepads(announce: Boolean) {
        val pads = connectedPads()
        val before = padPorts.keys.toSet()
        padPorts.keys.retainAll(pads.map { it.id }.toSet())
        pads.forEach { d ->
            if (d.id !in padPorts) {
                padPorts[d.id] = (0 until MAX_PLAYERS).firstOrNull { it !in padPorts.values } ?: (MAX_PLAYERS - 1)
            }
        }
        gamepadName = pads.minByOrNull { padPorts[it.id] ?: 0 }?.name
        if (announce && before != padPorts.keys) {
            message = if (pads.isEmpty()) "تم فصل الجويستك" else playersText(pads)
            CrashReporter.crumb("gamepads=${pads.joinToString { "${it.name}->${padPorts[it.id]}" }}")
        }
    }

    private fun playersText(pads: List<InputDevice> = connectedPads()): String =
        pads.sortedBy { portOf(it) }.joinToString("  •  ") { "🎮 لاعب ${portOf(it) + 1}: ${it.name}" }

    private fun swapPlayers() {
        // Release everything first so no button stays stuck on the old port.
        for (port in 0 until MAX_PLAYERS) {
            listOf(NesKeys.A, NesKeys.B, NesKeys.START, NesKeys.SELECT).forEach {
                retroView.sendKeyEvent(KeyEvent.ACTION_UP, it, port)
            }
            retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f, port)
            lastDpad[port] = null
        }
        turboKeys.clear()
        playersSwapped = !playersSwapped
        val pads = connectedPads()
        message = when {
            pads.isEmpty() -> "ماكو جويستك متصل"
            pads.size == 1 -> if (playersSwapped) "🎮 الجويستك صار لاعب 2 • أزرار اللمس لاعب 1" else "🎮 الجويستك رجع لاعب 1"
            else -> playersText(pads)
        }
    }

    private fun has(source: Int, flag: Int) = (source and flag) == flag

    private fun isGamepad(source: Int) =
        has(source, InputDevice.SOURCE_GAMEPAD) || has(source, InputDevice.SOURCE_JOYSTICK)

    /**
     * Physical pad -> key sent to the engine. Android names the bottom face button A and the
     * right one B, while the NES has B on the left/bottom and A on the right: swap them
     * (same as LibretroDroid's own mapping). Returns null for keys the NES doesn't use.
     */
    private fun physicalToRetro(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> keyCode
        else -> null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::retroView.isInitialized && isGamepad(event.source)) {
            if (gamepadName == null) gamepadName = event.device?.name ?: "Gamepad"
            val port = portOf(event.device)

            // 1) Custom mapping chosen by the player
            padMap[event.keyCode]?.let { target ->
                if (target == PadTarget.MENU) {
                    if (event.action == KeyEvent.ACTION_UP) openMenu()
                } else if (target.turbo) {
                    if (event.repeatCount == 0) setTurbo(target.keyCode, event.action == KeyEvent.ACTION_DOWN, port)
                } else if (coreReady && event.repeatCount == 0) {
                    sendKey(event.action, ab(target.keyCode), port)
                }
                return true
            }
            // 2) Default: MODE/BACK opens the menu, everything else goes to the core
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) openMenu()
                return true
            }
            // Default turbo: X -> Turbo B, Y -> Turbo A (left and top face buttons).
            val defaultTurbo = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_X -> NesKeys.B
                KeyEvent.KEYCODE_BUTTON_Y -> NesKeys.A
                else -> null
            }
            if (defaultTurbo != null) {
                if (coreReady && event.repeatCount == 0) setTurbo(defaultTurbo, event.action == KeyEvent.ACTION_DOWN, port)
                return true
            }
            val mapped = physicalToRetro(event.keyCode) ?: return super.dispatchKeyEvent(event)
            if (coreReady && event.repeatCount == 0) sendKey(event.action, ab(mapped), port)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Last d-pad direction sent per player, so stick noise doesn't flood the engine. */
    private val lastDpad = arrayOfNulls<Pair<Int, Int>>(MAX_PLAYERS)

    /** Hat (d-pad) and left analog stick both steer the NES d-pad, per player. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::retroView.isInitialized && coreReady && isGamepad(event.source) && event.action == MotionEvent.ACTION_MOVE) {
            val port = portOf(event.device)
            fun axis(hat: Int, stick: Int): Int {
                val h = event.getAxisValue(hat)
                if (kotlin.math.abs(h) > 0.5f) return if (h > 0) 1 else -1
                val v = event.getAxisValue(stick)
                return if (v > STICK_DEADZONE) 1 else if (v < -STICK_DEADZONE) -1 else 0
            }
            val dir = axis(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X) to axis(MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y)
            if (lastDpad[port] != dir) {
                lastDpad[port] = dir
                sendDpad(dir.first.toFloat(), dir.second.toFloat(), port)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
