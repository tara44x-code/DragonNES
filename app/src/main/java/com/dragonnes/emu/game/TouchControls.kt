package com.dragonnes.emu.game

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonnes.emu.data.GameSystem
import com.dragonnes.emu.ui.Neon
import kotlin.math.hypot

/**
 * Keys sent straight to the engine with sendKeyEvent(): LibretroDroid's native side maps
 * KEYCODE_BUTTON_A -> RetroPad A and KEYCODE_BUTTON_B -> RetroPad B with no swap
 * (the A<->B swap only exists for physical pads, see GameActivity.physicalToRetro),
 * and FCEUmm maps RetroPad A -> NES A, RetroPad B -> NES B.
 */
object NesKeys {
    const val A = KeyEvent.KEYCODE_BUTTON_A
    const val B = KeyEvent.KEYCODE_BUTTON_B
    const val START = KeyEvent.KEYCODE_BUTTON_START
    const val SELECT = KeyEvent.KEYCODE_BUTTON_SELECT
}

data class ControlsConfig(
    val system: GameSystem,
    val turboButtons: Boolean,
    val opacity: Float,
    val scale: Float,
    val haptics: Boolean,
    val rewindEnabled: Boolean,
)

/** Movable pieces of the on-screen pad. */
enum class PadPart { BAR, DPAD, B, A, TB, TA, SELECT, START }

/** Where one piece sits: centre as a fraction of the safe area (0..1) and its own size factor. */
data class PartPos(val x: Float, val y: Float, val scale: Float = 1f)

/** Player-editable layout of the touch controls (landscape). */
data class PadLayout(val parts: Map<PadPart, PartPos>) {
    operator fun get(p: PadPart) = parts[p] ?: DEFAULT.parts.getValue(p)
    fun with(p: PadPart, pos: PartPos) = PadLayout(parts + (p to pos))

    fun encode(): String = org.json.JSONObject().apply {
        parts.forEach { (k, v) -> put(k.name, org.json.JSONArray(listOf(v.x.toDouble(), v.y.toDouble(), v.scale.toDouble()))) }
    }.toString()

    companion object {
        val DEFAULT = PadLayout(mapOf(
            PadPart.BAR to PartPos(0.50f, 0.07f),
            PadPart.DPAD to PartPos(0.12f, 0.70f),
            PadPart.B to PartPos(0.80f, 0.82f),
            PadPart.A to PartPos(0.92f, 0.70f),
            PadPart.TB to PartPos(0.82f, 0.52f),
            PadPart.TA to PartPos(0.93f, 0.40f),
            PadPart.SELECT to PartPos(0.42f, 0.95f),
            PadPart.START to PartPos(0.58f, 0.95f),
        ))

        fun decode(json: String?): PadLayout = runCatching {
            val o = org.json.JSONObject(json!!)
            PadLayout(PadPart.entries.associateWith { p ->
                o.optJSONArray(p.name)?.let { a -> PartPos(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat()) }
                    ?: DEFAULT.parts.getValue(p)
            })
        }.getOrDefault(DEFAULT)
    }
}

/**
 * Places each child (tagged with Modifier.layoutId(PadPart)) centred on its layout position,
 * kept fully inside the area.
 */
@Composable
private fun FreeLayout(layout: PadLayout, modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val placeables = measurables.map { m ->
            (m.layoutId as PadPart) to m.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        layout(w, h) {
            placeables.forEach { (part, pl) ->
                val pos = layout[part]
                val x = (pos.x * w - pl.width / 2f).toInt().coerceIn(0, (w - pl.width).coerceAtLeast(0))
                val y = (pos.y * h - pl.height / 2f).toInt().coerceIn(0, (h - pl.height).coerceAtLeast(0))
                pl.place(x, y)
            }
        }
    }
}

@Composable
fun TouchControls(
    cfg: ControlsConfig,
    fastForward: Boolean,
    onKey: (keyCode: Int, pressed: Boolean) -> Unit,
    onTurbo: (keyCode: Int, pressed: Boolean) -> Unit,
    onDpad: (x: Float, y: Float) -> Unit,
    onMenu: () -> Unit,
    onToggleFastForward: () -> Unit,
    onRewind: (Boolean) -> Unit,
    recording: Boolean = false,
    onRecord: () -> Unit = {},
    layout: PadLayout = PadLayout.DEFAULT,
    /** Edit mode: pieces are dragged instead of pressed. */
    editing: Boolean = false,
    selected: PadPart? = null,
    onSelect: (PadPart) -> Unit = {},
    onMove: (PadPart, PartPos) -> Unit = { _, _ -> },
) {
    val s = cfg.scale
    var areaSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
    val currentLayout by rememberUpdatedState(layout)

    /** In edit mode, a transparent layer on top of each piece catches drags. */
    @Composable
    fun Piece(part: PadPart, content: @Composable (k: Float) -> Unit) {
        val pos = layout[part]
        Box(Modifier.layoutId(part)) {
            content(s * pos.scale)
            if (editing) {
                Box(
                    Modifier.matchParentSize()
                        .border(if (selected == part) 3.dp else 1.dp, if (selected == part) Neon.pad.sample(1f) else Neon.TextDim.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .pointerInput(part) {
                            detectDragGestures(onDragStart = { onSelect(part) }) { change, drag ->
                                change.consume()
                                val p = currentLayout[part]
                                onMove(part, p.copy(
                                    x = (p.x + drag.x / areaSize.width).coerceIn(0f, 1f),
                                    y = (p.y + drag.y / areaSize.height).coerceIn(0f, 1f),
                                ))
                            }
                        }
                        .pointerInput(part) { detectTapGestures { onSelect(part) } },
                )
            }
        }
    }

    FreeLayout(
        layout,
        Modifier.fillMaxSize().alpha(if (editing) 1f else cfg.opacity).safeDrawingPadding().padding(12.dp)
            .onSizeChanged { areaSize = it },
    ) {
        Piece(PadPart.BAR) { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (cfg.rewindEnabled) {
                    IconPad(Icons.Default.FastRewind, active = false, hold = onRewind, haptics = cfg.haptics)
                }
                IconPad(Icons.Default.Menu, active = false, tap = onMenu, haptics = cfg.haptics)
                IconPad(Icons.Default.FastForward, active = fastForward, tap = onToggleFastForward, haptics = cfg.haptics)
                IconPad(
                    if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    active = recording, tap = onRecord, haptics = cfg.haptics,
                )
            }
        }
        Piece(PadPart.DPAD) { k -> DPad(Modifier, 160.dp * k, cfg.haptics, onDpad) }
        Piece(PadPart.B) { k -> FaceButton("B", 64.dp * k, Neon.pad.sample(0.2f), NesKeys.B, cfg.haptics, onKey) }
        Piece(PadPart.A) { k -> FaceButton("A", 64.dp * k, Neon.pad.sample(1f), NesKeys.A, cfg.haptics, onKey) }
        if (cfg.turboButtons) {
            // Turbo buttons follow the same size setting as A/B.
            Piece(PadPart.TB) { k -> FaceButton("TB", 52.dp * k, Neon.pad.sample(0.35f), NesKeys.B, cfg.haptics, onTurbo) }
            Piece(PadPart.TA) { k -> FaceButton("TA", 52.dp * k, Neon.pad.sample(0.75f), NesKeys.A, cfg.haptics, onTurbo) }
        }
        Piece(PadPart.SELECT) { k -> PillButton("SELECT", NesKeys.SELECT, cfg.haptics, onKey, k) }
        Piece(PadPart.START) { k -> PillButton("START", NesKeys.START, cfg.haptics, onKey, k) }
    }
}

@Composable
private fun DPad(modifier: Modifier, size: Dp, haptics: Boolean, onDpad: (Float, Float) -> Unit) {
    val view = LocalView.current
    var dir by remember { mutableStateOf(0 to 0) }

    fun update(nx: Int, ny: Int) {
        if (dir != nx to ny) {
            dir = nx to ny
            onDpad(nx.toFloat(), ny.toFloat())
            if (haptics && (nx != 0 || ny != 0)) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    Box(
        modifier.size(size)
            .drawBehind {
                val c = Offset(this.size.width / 2, this.size.height / 2)
                val r = this.size.minDimension / 2
                drawCircle(Neon.Surface.copy(alpha = 0.55f), r, c)
                drawCircle(Neon.pad.sample(0f).copy(alpha = 0.8f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                val arm = r * 0.34f
                // cross
                drawRoundRect(
                    Neon.SurfaceHi, Offset(c.x - arm, c.y - r * 0.82f),
                    androidx.compose.ui.geometry.Size(arm * 2, r * 1.64f),
                    androidx.compose.ui.geometry.CornerRadius(arm * 0.4f),
                )
                drawRoundRect(
                    Neon.SurfaceHi, Offset(c.x - r * 0.82f, c.y - arm),
                    androidx.compose.ui.geometry.Size(r * 1.64f, arm * 2),
                    androidx.compose.ui.geometry.CornerRadius(arm * 0.4f),
                )
                // arrows, lit when pressed
                val (dx, dy) = dir
                fun arrow(ax: Int, ay: Int) {
                    val lit = (ax != 0 && ax == dx) || (ay != 0 && ay == dy)
                    val tip = Offset(c.x + ax * r * 0.72f, c.y + ay * r * 0.72f)
                    val base = Offset(c.x + ax * r * 0.5f, c.y + ay * r * 0.5f)
                    val side = Offset(-ay * arm * 0.55f, ax * arm * 0.55f)
                    val p = Path().apply {
                        moveTo(tip.x, tip.y); lineTo(base.x + side.x, base.y + side.y); lineTo(base.x - side.x, base.y - side.y); close()
                    }
                    drawPath(p, if (lit) Neon.pad.sample(1f) else Neon.pad.sample(0f).copy(alpha = 0.7f))
                }
                arrow(0, -1); arrow(0, 1); arrow(-1, 0); arrow(1, 0)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun handle(p: Offset) {
                        val cx = this.size.width / 2f; val cy = this.size.height / 2f
                        val nx = (p.x - cx) / cx; val ny = (p.y - cy) / cy
                        if (hypot(nx, ny) < 0.2f) { update(0, 0); return }
                        val t = 0.38f
                        update(if (nx > t) 1 else if (nx < -t) -1 else 0, if (ny > t) 1 else if (ny < -t) -1 else 0)
                    }
                    handle(down.position); down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        handle(ch.position); ch.consume()
                    }
                    update(0, 0)
                }
            },
    )
}

@Composable
private fun FaceButton(
    label: String, size: Dp, color: Color, keyCode: Int, haptics: Boolean,
    onKey: (Int, Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box(
        modifier.size(size)
            .background(if (pressed) color.copy(alpha = 0.55f) else Neon.Surface.copy(alpha = 0.6f), CircleShape)
            .border(2.dp, color, CircleShape)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true; onKey(keyCode, true)
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    waitForUpOrCancellation()
                    pressed = false; onKey(keyCode, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (pressed) Neon.Bg else color, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.36f).sp)
    }
}

@Composable
private fun PillButton(label: String, keyCode: Int, haptics: Boolean, onKey: (Int, Boolean) -> Unit, scale: Float = 1f) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.height(30.dp * scale).widthIn(min = 72.dp * scale)
            .background(if (pressed) Neon.pad.sample(0f).copy(alpha = 0.5f) else Neon.Surface.copy(alpha = 0.6f), shape)
            .border(1.5.dp, Neon.pad.sample(0f), shape)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true; onKey(keyCode, true)
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    waitForUpOrCancellation()
                    pressed = false; onKey(keyCode, false)
                }
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Neon.Text, fontSize = (12 * scale).sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun IconPad(
    icon: ImageVector, active: Boolean, haptics: Boolean,
    tap: (() -> Unit)? = null, hold: ((Boolean) -> Unit)? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val lit = pressed || active
    val onTap by rememberUpdatedState(tap)
    val onHold by rememberUpdatedState(hold)
    Box(
        Modifier.size(42.dp)
            .background(if (lit) Neon.pad.sample(1f).copy(alpha = 0.45f) else Neon.Surface.copy(alpha = 0.6f), CircleShape)
            .border(1.5.dp, if (lit) Neon.pad.sample(1f) else Neon.pad.sample(0f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true; onHold?.invoke(true)
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    val up = waitForUpOrCancellation()
                    pressed = false; onHold?.invoke(false)
                    if (up != null) onTap?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (lit) Neon.Text else Neon.pad.sample(0f), modifier = Modifier.size(22.dp)) }
}
