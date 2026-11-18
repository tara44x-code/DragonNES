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
        }
        retroView = GLRetroView(this, data)
        lifecycle.addObserver(retroView)

        lifecycleScope.launch(safe) {
            retroView.getGLRetroErrors().collect { code -> onEngineError(code) }
        }
        lifecycleScope.launch(safe) {
            retroView.getGLRetroEvents().first { it == GLRetroView.GLRetroEvents.FrameRendered }
            onCoreReady()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = openMenu()
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
        val showTouch = touchOverride ?: when (settings.touchMode) {
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
                        if (coreReady) retroView.sendKeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
                    },
                    onTurbo = ::setTurbo,
                    onDpad = { x, y -> if (coreReady) retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y) },
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

            message?.let {
                Text(
                    it, color = Neon.Text,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Neon.Surface.copy(alpha = 0.9f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LaunchedEffect(it) { delay(2200); message = null }
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

    private fun setTurbo(keyCode: Int, pressed: Boolean, port: Int = 0) {
        if (!coreReady) return
        if (pressed) {
            turboKeys[keyCode] = port
            if (turboJob?.isActive != true) turboJob = lifecycleScope.launch(safe) {
                val half = (500L / settings.turboSpeed.hz).coerceAtLeast(16L)
                var down = true
                while (turboKeys.isNotEmpty()) {
                    turboKeys.forEach { (k, p) ->
                        retroView.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, k, p)
                    }
                    down = !down
                    delay(half)
                }
            }
        } else {
            turboKeys.remove(keyCode)
            retroView.sendKeyEvent(KeyEvent.ACTION_UP, keyCode, port)
        }
    }

    private fun toggleFastForward() {
        if (!coreReady) return
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
        if (data.getBooleanExtra(GameMenuActivity.RES_CHEATS_CHANGED, false)) {
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
        when (action) {
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

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        padMap = settings.padMap
        refreshGamepads(announce = false)
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshGamepads(announce = true)
    override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepads(announce = true)
    override fun onInputDeviceChanged(deviceId: Int) = refreshGamepads(announce = false)

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
                    retroView.sendKeyEvent(event.action, target.keyCode, port)
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
            if (coreReady && event.repeatCount == 0) retroView.sendKeyEvent(event.action, mapped, port)
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
                retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, dir.first.toFloat(), dir.second.toFloat(), port)
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
