package com.dragonnes.emu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A neon gradient (2+ colour stops). Interface and touch buttons each use one. */
data class NeonPalette(val id: String, val label: String, val stops: List<Color>) {
    /** Colour at position [t] (0..1) along the gradient. */
    fun sample(t: Float): Color {
        if (stops.size == 1) return stops[0]
        val x = t.coerceIn(0f, 1f) * (stops.size - 1)
        val i = x.toInt().coerceAtMost(stops.size - 2)
        return lerp(stops[i], stops[i + 1], x - i)
    }

    fun encode() = stops.joinToString(",") { it.toArgb().toUInt().toString(16) }

    companion object {
        val presets = listOf(
            NeonPalette("dragon", "لهب التنين", listOf(Color(0xFFFFE08A), Color(0xFFFFA62B), Color(0xFFFF5A1F), Color(0xFFC81D11))),
            NeonPalette("gold", "ذهبي ملكي", listOf(Color(0xFFFFF4C2), Color(0xFFF2C14E), Color(0xFFC98A1B), Color(0xFF8A5A12))),
            NeonPalette("ember", "جمر", listOf(Color(0xFFFF8A3D), Color(0xFFE5391A), Color(0xFF8E1B10))),
            NeonPalette("nes", "NES كلاسيك", listOf(Color(0xFFE8E4DA), Color(0xFFA7A39A), Color(0xFF55524D), Color(0xFFE60012))),
            NeonPalette("famicom", "فاميكوم", listOf(Color(0xFFF5E9D3), Color(0xFFD9A441), Color(0xFFB0122B))),
            NeonPalette("blue_fire", "لهب أزرق", listOf(Color(0xFFE0FBFF), Color(0xFF7DF9FF), Color(0xFF2F6BFF), Color(0xFF6A2BE2))),
            NeonPalette("emerald", "تنين أخضر", listOf(Color(0xFFD8FF8A), Color(0xFF39D353), Color(0xFF0E8A4A))),
            NeonPalette("neon", "نيون", listOf(Color(0xFF00E5FF), Color(0xFF2F6BFF), Color(0xFF8A2BE2), Color(0xFFFF2BD6))),
            NeonPalette("synth", "سينث ويف", listOf(Color(0xFFFFD319), Color(0xFFFF901F), Color(0xFFFF2975), Color(0xFF8C1EFF))),
            NeonPalette("royal", "بنفسجي ملكي", listOf(Color(0xFFFFD700), Color(0xFFC77DFF), Color(0xFF7B2CBF))),
            NeonPalette("pink", "وردي", listOf(Color(0xFFFFB3F0), Color(0xFFFF4FD8), Color(0xFFB5179E))),
            NeonPalette("rainbow", "قوس قزح", listOf(Color(0xFFFF2525), Color(0xFFFFD319), Color(0xFF39FF14), Color(0xFF00E5FF), Color(0xFF8A2BE2), Color(0xFFFF2BD6))),
        )
        val default = presets.first()

        /** Swatches for the custom gradient picker. */
        val swatches = listOf(
            0xFFFFE08A, 0xFFF2C14E, 0xFFFF5A1F, 0xFFC81D11, 0xFF00E5FF, 0xFF00FFC6, 0xFF39FF14, 0xFFB6FF00, 0xFFFFE53B, 0xFFFFD700, 0xFFFF901F, 0xFFFF2525,
            0xFFFF2975, 0xFFFF2BD6, 0xFFC77DFF, 0xFF8A2BE2, 0xFF7A5CFF, 0xFF2F6BFF, 0xFF3D8BFF, 0xFFFFFFFF,
        ).map { Color(it) }

        fun custom(stops: List<Color>) = NeonPalette("custom", "مخصص", stops)

        fun byId(id: String?, customCode: String?): NeonPalette {
            if (id == "custom") decode(customCode)?.let { return custom(it) }
            return presets.firstOrNull { it.id == id } ?: default
        }

        private fun decode(code: String?): List<Color>? = code?.split(',')
            ?.mapNotNull { it.toULongOrNull(16)?.let { v -> Color(v.toInt()) } }
            ?.takeIf { it.size >= 2 }
    }
}

/** Segmented glowing bar (like an equalizer) that previews a palette. */
@Composable
fun PaletteBar(palette: NeonPalette, modifier: Modifier = Modifier, segments: Int = 16, height: Dp = 22.dp) {
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(segments) { i ->
            val c = palette.sample(i / (segments - 1f))
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .drawBehind {
                        drawRoundRect(c.copy(alpha = 0.35f), Offset(-2f, -2f), Size(size.width + 4f, size.height + 4f), CornerRadius(6f))
                    }
                    .clip(RoundedCornerShape(3.dp))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.6f)))),
            )
        }
    }
}

/** Selectable palette card: name + bar, highlighted when selected. */
@Composable
fun PaletteOption(palette: NeonPalette, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) palette.sample(0f).copy(alpha = 0.12f) else Neon.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) palette.sample(0.5f) else Neon.SurfaceHi, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(palette.label, color = if (selected) palette.sample(0f) else Neon.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (selected) Text("✓", color = palette.sample(1f), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        PaletteBar(palette, Modifier.fillMaxWidth(), height = 16.dp)
    }
}

/**
 * Horizontal theme bar: a glowing gradient pill per preset. Tapping one recolours the interface
 * and the game buttons at once (the full pickers stay in Settings).
 */
@Composable
fun QuickThemeBar(modifier: Modifier = Modifier, onPick: (NeonPalette) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) {
        items(NeonPalette.presets.size) { i ->
            val p = NeonPalette.presets[i]
            val selected = Neon.ui.id == p.id
            val shape = RoundedCornerShape(50)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(p) }) {
                Box(
                    Modifier.size(width = 54.dp, height = 26.dp)
                        .then(if (selected) Modifier.neonGlow(13.dp, glow = p.sample(0.5f), strength = 0.5f) else Modifier)
                        .clip(shape)
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(p.stops))
                        .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.15f), shape),
                    contentAlignment = Alignment.Center,
                ) { if (selected) Text("✓", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                Spacer(Modifier.height(4.dp))
                Text(p.label, color = if (selected) p.sample(0.3f) else Neon.TextDim, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}
