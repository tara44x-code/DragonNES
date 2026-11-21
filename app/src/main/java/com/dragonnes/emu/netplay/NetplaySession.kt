package com.dragonnes.emu.netplay

import android.view.KeyEvent
import com.dragonnes.emu.CrashReporter
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.math.ceil

/**
 * Two-player online play in lockstep with input delay.
 *
 * Both phones run the same game from the same emulator state. Every frame, each phone sends its
 * own pad state for a frame a few frames ahead ([delay]) and only emulates frame N once it has
 * BOTH players' input for frame N. The NES is deterministic, so both screens stay identical.
 * The host is player 1, the guest is player 2. A checksum of the emulator state is compared
 * every few seconds; if they ever differ, the host re-sends its state (automatic resync).
 *
 * Threading: [beforeFrame] runs on the GL (emulation) thread; network messages arrive on IO.
 */
class NetplaySession(
    private val transport: Transport,
    val isHost: Boolean,
    private val romCrc: String,
    private val gameTitle: String,
    private val playerName: String,
    private val engine: Engine,
    private val onStatus: (Status) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    /** Emulator access, called on the GL thread. */
    interface Engine {
        fun serializeState(): ByteArray
        fun unserializeState(state: ByteArray): Boolean
        fun resetCheats()
    }

    enum class Phase { HANDSHAKE, SYNCING, PLAYING, ENDED }

    data class Status(
        val phase: Phase,
        val peerName: String? = null,
        val pingMs: Int? = null,
        val delayFrames: Int = 0,
        val waitingForPeer: Boolean = false,
        val peerPaused: Boolean = false,
        val note: String? = null,
        val endReason: String? = null,
        val label: String = "",
        /** Ended because the connection dropped (not because someone chose to leave). */
        val lost: Boolean = false,
        /** Host: the guest left, the room stays open for them to come back. */
        val waitingRejoin: Boolean = false,
    )

    /** The local player's pad (see [Pad]); written by the UI/input thread. */
    @Volatile var localMask: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var status = Status(Phase.HANDSHAKE, label = transport.label)

    // ---- lockstep state (GL thread, except where noted) ----
    @Volatile private var epoch = 0
    private var frame = 0
    @Volatile private var delay = 2
    @Volatile private var batch = 1
    private var sentUpTo = -1
    private val local = HashMap<Int, Int>()
    private val remote = ConcurrentHashMap<Int, Int>()           // written by IO
    private val pending = ArrayList<Int>()
    private var pendingFirst = 0
    private val applied = intArrayOf(-1, -1)
    private var stallFrames = 0

    @Volatile private var running = false
    @Volatile private var hostNeedsStart = false
    @Volatile private var awaitingReadyEpoch = -1
    @Volatile private var pendingStart: Msg.Start? = null
    private val myChecks = ConcurrentHashMap<Int, Int>()
    private val peerChecks = ConcurrentHashMap<Int, Int>()
    @Volatile private var lastPingMs: Int? = null

    fun start() {
        publish(status)
        scope.launch { receiveLoop() }
        scope.launch {
            while (isActive) {
                transport.send(Protocol.encode(Msg.Ping(System.nanoTime())))
                delay(1000)
            }
        }
        log(if (isHost) "مضيف: صديقك اتصل، بانتظار التعارف" else "منضم: متصل، أرسلت التعارف")
        if (!isHost) sendHello()
    }

    fun sendHello() = transport.send(Protocol.encode(Msg.Hello(Protocol.VERSION, romCrc, playerName, gameTitle)))

    fun setPaused(paused: Boolean) = transport.send(Protocol.encode(Msg.Paused(paused)))

    fun currentStatus() = status

    /** The relay says the other phone disconnected: end, but allow an automatic rejoin. */
    fun connectionLost(reason: String) = end(reason, lost = true)

    /** Ends the session (also tells the other player). */
    fun stop(reason: String = "انتهت جلسة اللعب أونلاين") {
        if (status.phase == Phase.ENDED) return
        runCatching { transport.send(Protocol.encode(Msg.Bye)) }
        end(reason)
    }

    private fun end(reason: String, lost: Boolean = false) {
        if (status.phase == Phase.ENDED) return
        running = false
        CrashReporter.crumb("netplay end: $reason lost=$lost")
        publish(status.copy(phase = Phase.ENDED, endReason = reason, waitingForPeer = false, lost = lost))
        scope.launch { delay(300); transport.close(); scope.cancel() }
    }

    private fun publish(s: Status) { status = s; onStatus(s) }

    private fun log(text: String) { CrashReporter.crumb("netplay: $text"); onLog(text) }

    // ---------------- network ----------------

    private suspend fun receiveLoop() {
        for (bytes in transport.incoming) {
            val msg = Protocol.decode(bytes) ?: continue
            when (msg) {
                is Msg.Hello -> log("وصل تعارف من ${msg.name}")
                is Msg.Start -> log("وصلت حالة اللعبة (${msg.state.size / 1024}KB)")
                is Msg.Ready -> log("صديقك جاهز")
                is Msg.Error -> log("خطأ: ${msg.reason}")
                else -> {}
            }
            when (msg) {
                is Msg.Hello -> onHello(msg)
                is Msg.Start -> if (!isHost) { pendingStart = msg; publish(status.copy(phase = Phase.SYNCING)) }
                is Msg.Ready -> if (isHost && msg.epoch == awaitingReadyEpoch) {
                    awaitingReadyEpoch = -1; running = true
                    publish(status.copy(phase = Phase.PLAYING, note = null))
                }
                is Msg.Input -> if (msg.epoch == epoch) {
                    msg.masks.forEachIndexed { i, m -> remote[msg.first + i] = m.toInt() and 0xFF }
                }
                is Msg.Check -> if (msg.epoch == epoch) { peerChecks[msg.frame] = msg.crc; compareCheck(msg.frame) }
                is Msg.Ping -> transport.send(Protocol.encode(Msg.Pong(msg.time)))
                is Msg.Pong -> {
                    lastPingMs = ((System.nanoTime() - msg.time) / 1_000_000).toInt()
                    publish(status.copy(pingMs = lastPingMs))
                }
                is Msg.Paused -> publish(status.copy(peerPaused = msg.paused))
                is Msg.Error -> end(msg.reason)
                Msg.Bye -> if (isHost && !transport.isLan) peerLeft() else end("صديقك طلع من اللعب")
            }
        }
        end("انقطع الاتصال بصديقك", lost = true)
    }

    /**
     * Host on the internet: the guest left or lost the connection. Keep the room (and this
     * session) open and freeze the game until they come back; their Hello starts a fresh sync.
     */
    fun peerLeft() {
        if (!isHost || status.phase == Phase.ENDED) return
        running = false
        awaitingReadyEpoch = -1
        log("صديقك طلع، الغرفة مفتوحة بانتظار يرجع")
        publish(status.copy(phase = Phase.SYNCING, waitingRejoin = true, waitingForPeer = false, peerPaused = false,
            note = "صديقك طلع — بانتظار يرجع…"))
    }

    private fun onHello(h: Msg.Hello) {
        when {
            h.version != Protocol.VERSION -> fail("نسخة التطبيق عند صديقك مختلفة — حدّثوا التطبيق سوية")
            !h.romCrc.equals(romCrc, ignoreCase = true) ->
                fail("صديقك فاتح لعبة مختلفة (${h.game}). لازم تفتحون نفس اللعبة ونفس النسخة")
            isHost -> {
                running = false
                transport.send(Protocol.encode(Msg.Hello(Protocol.VERSION, romCrc, playerName, gameTitle)))
                publish(status.copy(peerName = h.name, phase = Phase.SYNCING, note = "جاري المزامنة…", waitingRejoin = false))
                scope.launch {
                    delay(if (transport.isLan) 300 else 2200)   // collect a couple of pings first
                    chooseDelay()
                    hostNeedsStart = true
                }
            }
            else -> publish(status.copy(peerName = h.name, phase = Phase.SYNCING, note = "بانتظار المضيف…"))
        }
    }

    private fun fail(reason: String) {
        transport.send(Protocol.encode(Msg.Error(reason)))
        end(reason)
    }

    /** Input delay from the measured round trip: enough frames to hide one-way latency. */
    private fun chooseDelay() {
        if (transport.isLan) { delay = 2; batch = 1; return }
        val rtt = lastPingMs ?: 160
        batch = 2
        delay = (ceil((rtt / 2.0) / 16.67).toInt() + batch + 1).coerceIn(3, 12)
    }

    // ---------------- emulation thread ----------------

    /** Lockstep gate: return true to emulate this frame now. Never throws (called from native code). */
    fun beforeFrame(): Boolean = try {
        gate()
    } catch (e: Throwable) {
        CrashReporter.crumb("netplay gate error: ${e.javaClass.simpleName}: ${e.message}")
        end("خطأ بالمزامنة: ${e.javaClass.simpleName}")
        true
    }

    private fun gate(): Boolean {
        if (status.phase == Phase.ENDED) return true
        if (isHost && hostNeedsStart) { hostNeedsStart = false; beginEpochAsHost(); return false }
        pendingStart?.let { pendingStart = null; beginEpochAsGuest(it); return false }
        if (!running) return false

        // Record + send our input for the frame `delay` frames ahead.
        val target = frame + delay
        while (sentUpTo < target) {
            sentUpTo++
            val m = localMask
            local[sentUpTo] = m
            if (pending.isEmpty()) pendingFirst = sentUpTo
            pending += m
            if (pending.size >= batch) flushInputs()
        }

        val theirs = if (frame < delay) 0 else remote[frame]
        if (theirs == null) {
            flushInputs()
            stallFrames++
            if (stallFrames == 45) publish(status.copy(waitingForPeer = true))
            return false
        }
        if (stallFrames >= 45) publish(status.copy(waitingForPeer = false))
        stallFrames = 0

        val mine = local[frame] ?: 0
        applyPad(0, if (isHost) mine else theirs)
        applyPad(1, if (isHost) theirs else mine)

        if (frame > 0 && frame % CHECK_EVERY == 0) sendCheck(frame)

        local.remove(frame - 2)
        remote.remove(frame - 2)
        frame++
        return true
    }

    private fun flushInputs() {
        if (pending.isEmpty()) return
        val masks = ByteArray(pending.size) { pending[it].toByte() }
        transport.send(Protocol.encode(Msg.Input(epoch, pendingFirst, masks)))
        pending.clear()
    }

    private fun resetLockstep() {
        frame = 0
        local.clear(); remote.clear(); pending.clear()
        myChecks.clear(); peerChecks.clear()
        for (f in 0 until delay) local[f] = 0
        sentUpTo = delay - 1
        stallFrames = 0
        applied[0] = -1; applied[1] = -1
        applyPad(0, 0); applyPad(1, 0)
        engine.resetCheats()   // cheats would make the two games differ
    }

    private fun beginEpochAsHost() {
        val state = runCatching { engine.serializeState() }.getOrNull()
        if (state == null || state.isEmpty()) { fail("تعذر حفظ حالة اللعبة للمزامنة"); return }
        epoch++
        resetLockstep()
        awaitingReadyEpoch = epoch
        running = false
        log("أرسلت حالة اللعبة (${state.size / 1024}KB) تأخير=$delay")
        transport.send(Protocol.encode(Msg.Start(epoch, delay, batch, state)))
        publish(status.copy(phase = Phase.SYNCING, delayFrames = delay, note = "جاري المزامنة…"))
    }

    private fun beginEpochAsGuest(s: Msg.Start) {
        val ok = runCatching { engine.unserializeState(s.state) }.getOrDefault(false)
        if (!ok) { fail("تعذر تحميل حالة اللعبة من المضيف"); return }
        epoch = s.epoch; delay = s.delay; batch = s.batch
        resetLockstep()
        running = true
        transport.send(Protocol.encode(Msg.Ready(epoch)))
        log("حمّلت حالة اللعبة، بدينا")
        publish(status.copy(phase = Phase.PLAYING, delayFrames = delay, note = null))
    }

    private fun sendCheck(f: Int) {
        val bytes = runCatching { engine.serializeState() }.getOrNull() ?: return
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        myChecks[f] = crc
        transport.send(Protocol.encode(Msg.Check(epoch, f, crc)))
        compareCheck(f)
    }

    private fun compareCheck(f: Int) {
        val mine = myChecks[f] ?: return
        val theirs = peerChecks[f] ?: return
        myChecks.remove(f); peerChecks.remove(f)
        if (mine != theirs && isHost) {
            CrashReporter.crumb("netplay desync at $f -> resync")
            publish(status.copy(note = "تمت إعادة المزامنة"))
            hostNeedsStart = true
        }
    }

    /** Sends the pad differences to the core for [port] (GL thread). */
    private fun applyPad(port: Int, mask: Int) {
        val prev = applied[port]
        if (prev == mask) return
        val all = prev == -1
        fun button(bit: Int, key: Int) {
            if (all || (prev and bit) != (mask and bit)) {
                LibretroDroid.onKeyEvent(port, if (mask and bit != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, key)
            }
        }
        button(Pad.A, KeyEvent.KEYCODE_BUTTON_A)
        button(Pad.B, KeyEvent.KEYCODE_BUTTON_B)
        button(Pad.SELECT, KeyEvent.KEYCODE_BUTTON_SELECT)
        button(Pad.START, KeyEvent.KEYCODE_BUTTON_START)
        val dirs = Pad.UP or Pad.DOWN or Pad.LEFT or Pad.RIGHT
        if (all || (prev and dirs) != (mask and dirs)) {
            val x = (if (mask and Pad.RIGHT != 0) 1f else 0f) - (if (mask and Pad.LEFT != 0) 1f else 0f)
            val y = (if (mask and Pad.DOWN != 0) 1f else 0f) - (if (mask and Pad.UP != 0) 1f else 0f)
            LibretroDroid.onMotionEvent(port, GLRetroView.MOTION_SOURCE_DPAD, x, y)
        }
        applied[port] = mask
    }

    companion object {
        /** Frames between state checksums (~10 s). */
        const val CHECK_EVERY = 600
    }
}
