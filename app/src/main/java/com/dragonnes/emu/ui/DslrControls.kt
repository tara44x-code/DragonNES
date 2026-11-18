package com.dragonnes.emu.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonnes.emu.data.DslrGrade
import com.dragonnes.emu.data.Settings

/**
 * Tuning for the DSLR lens look: colour grade + strength of each optical effect.
 * [onChange] fires after every change (the game menu uses it to re-apply the shader live).
 */
@Composable
fun DslrControls(settings: Settings, gles3: Boolean, onChange: () -> Unit = {}) {
    var grade by remember { mutableStateOf(settings.dslrGrade) }
    var bloom by remember { mutableFloatStateOf(settings.dslrBloom) }
    var dof by remember { mutableFloatStateOf(settings.dslrDof) }
    var vignette by remember { mutableFloatStateOf(settings.dslrVignette) }
    var aberration by remember { mutableFloatStateOf(settings.dslrAberration) }
    var grain by remember { mutableFloatStateOf(settings.dslrGrain) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!gles3) {
            Text(
                "جهازك ما يدعم OpenGL ES 3، فراح يشتغل بداله فلتر التنعيم الذكي.",
                color = Neon.Magenta, fontSize = 12.sp,
            )
        }
        Text("تدرّج الألوان", color = Neon.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DslrGrade.entries.forEach { g ->
                NeonChip(g.label, grade == g) { grade = g; settings.dslrGrade = g; onChange() }
            }
        }
        Spacer(Modifier.height(4.dp))
        Effect("توهج الضوء (Bloom)", bloom, onChange) { bloom = it; settings.dslrBloom = it }
        Effect("عمق المجال (تضبيب الأطراف)", dof, onChange) { dof = it; settings.dslrDof = it }
        Effect("تعتيم الزوايا (Vignette)", vignette, onChange) { vignette = it; settings.dslrVignette = it }
        Effect("انحراف ألوان العدسة", aberration, onChange) { aberration = it; settings.dslrAberration = it }
        Effect("حبيبات الفلم", grain, onChange) { grain = it; settings.dslrGrain = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("يتطبّق مباشرة بعد ما ترفع إصبعك من الشريط", color = Neon.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton({
                settings.resetDslr()
                grade = settings.dslrGrade; bloom = settings.dslrBloom; dof = settings.dslrDof
                vignette = settings.dslrVignette; aberration = settings.dslrAberration; grain = settings.dslrGrain
                onChange()
            }) { Text("الافتراضي", color = Neon.Magenta, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Effect(label: String, value: Float, onDone: () -> Unit, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Neon.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("${(value * 100).toInt()}%", color = Neon.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Slider(
        value = value, onValueChange = onValue, onValueChangeFinished = onDone, valueRange = 0f..1f,
        colors = SliderDefaults.colors(thumbColor = Neon.Magenta, activeTrackColor = Neon.Cyan, inactiveTrackColor = Neon.Surface),
    )
}
