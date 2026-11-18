package com.dragonnes.emu.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonnes.emu.data.ColorPalette
import com.dragonnes.emu.data.NtscMode
import com.dragonnes.emu.data.Settings

/** Colour palette + NTSC signal pickers. [onChange] fires after each change. */
@Composable
fun ColorControls(settings: Settings, onChange: () -> Unit = {}) {
    var palette by remember { mutableStateOf(settings.palette) }
    var ntsc by remember { mutableStateOf(settings.ntsc) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("لوحة الألوان", color = Neon.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorPalette.entries.forEach { p ->
                NeonChip(p.label, palette == p) { palette = p; settings.palette = p; onChange() }
            }
        }
        Text("إشارة التلفزيون (NTSC)", color = Neon.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("تحاكي كيبل التلفزيون القديم: ألوان ممزوجة وشكل أصلي", color = Neon.TextDim, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NtscMode.entries.forEach { m ->
                NeonChip(m.label, ntsc == m) { ntsc = m; settings.ntsc = m; onChange() }
            }
        }
    }
}
