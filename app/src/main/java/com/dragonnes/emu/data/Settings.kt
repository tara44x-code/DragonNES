package com.dragonnes.emu.data

import android.content.Context

enum class VideoFilter(val label: String) {
    AUTO("تلقائي حسب الجهاز"),
    PIXEL("بكسل أصلي"),
    SHARP("حاد ناعم"),
    SMOOTH("تنعيم ذكي (CUT2)"),
    ULTRA("دقة فائقة (CUT3)"),
    CRT("شاشة CRT"),
    LCD("شاشة LCD"),
}

/** How the picture fills the phone screen. [ratio] = forced display ratio (null = engine decides). */
enum class AspectMode(val label: String, val ratio: Float?) {
    ORIGINAL("أصلي", null),
    STRETCH_16_9("تمدد 16:9", 16f / 9f),
    STRETCH_16_10("16:10", 16f / 10f),
    STRETCH_3_2("3:2", 3f / 2f),
    FULL("ملء الشاشة", null),
}

enum class TouchMode(val label: String) {
    AUTO("تلقائي (تختفي مع الجويستك)"),
    ALWAYS("ظاهرة دائماً"),
    HIDDEN("مخفية دائماً"),
}

/**
 * Targets a physical controller button can be mapped to. [keyCode] is what we send to the core
 * (see NesKeys); [turbo] = the app presses/releases that key rapidly while the button is held.
 */
enum class PadTarget(val label: String, val keyCode: Int, val turbo: Boolean = false) {
    A("A", 97), B("B", 96),                                       // KEYCODE_BUTTON_B / _A
    TURBO_A("Turbo A", 97, turbo = true), TURBO_B("Turbo B", 96, turbo = true),
    START("START", 108), SELECT("SELECT", 109),                   // KEYCODE_BUTTON_START / _SELECT
    MENU("القائمة", -1),
}

/** How fast turbo buttons fire (presses per second). */
enum class TurboSpeed(val label: String, val hz: Int) {
    SLOW("بطيء", 8), NORMAL("عادي", 15), FAST("سريع", 20),
}

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var romFolders: Set<String>
        get() = p.getStringSet("folders", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("folders", v).apply()

    var filter: VideoFilter
        get() = runCatching { VideoFilter.valueOf(p.getString("filter", "AUTO")!!) }.getOrDefault(VideoFilter.AUTO)
        set(v) = p.edit().putString("filter", v.name).apply()

    var aspect: AspectMode
        get() = runCatching { AspectMode.valueOf(p.getString("aspect", null)!!) }
            .getOrDefault(AspectMode.ORIGINAL)
        set(v) = p.edit().putString("aspect", v.name).apply()

    var touchMode: TouchMode
        get() = runCatching { TouchMode.valueOf(p.getString("touchMode", "AUTO")!!) }.getOrDefault(TouchMode.AUTO)
        set(v) = p.edit().putString("touchMode", v.name).apply()

    /** Custom controller mapping: physical Android keycode -> target. Empty = default mapping. */
    var padMap: Map<Int, PadTarget>
        get() = (p.getString("padMap", "") ?: "").split(',').mapNotNull { e ->
            val (k, t) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val target = runCatching { PadTarget.valueOf(t) }.getOrNull() ?: return@mapNotNull null
            k.toIntOrNull()?.let { it to target }
        }.toMap()
        set(v) = p.edit().putString("padMap", v.entries.joinToString(",") { "${it.key}:${it.value.name}" }).apply()

    /** Show the Turbo A / Turbo B touch buttons. */
    var turboButtons: Boolean
        get() = p.getBoolean("turboBtns", true)
        set(v) = p.edit().putBoolean("turboBtns", v).apply()

    var turboSpeed: TurboSpeed
        get() = runCatching { TurboSpeed.valueOf(p.getString("turboSpeed", "NORMAL")!!) }.getOrDefault(TurboSpeed.NORMAL)
        set(v) = p.edit().putString("turboSpeed", v.name).apply()

    /** null = follow device profile */
    var rewind: Boolean?
        get() = if (p.contains("rewind")) p.getBoolean("rewind", true) else null
        set(v) { if (v == null) p.edit().remove("rewind").apply() else p.edit().putBoolean("rewind", v).apply() }

    var autoResume: Boolean
        get() = p.getBoolean("autoResume", true)
        set(v) = p.edit().putBoolean("autoResume", v).apply()

    // ---- Theme ----
    var uiPalette: com.dragonnes.emu.ui.NeonPalette
        get() = com.dragonnes.emu.ui.NeonPalette.byId(p.getString("uiPal", null), p.getString("uiPalC", null))
        set(v) = p.edit().putString("uiPal", v.id).putString("uiPalC", v.encode()).apply()

    var padPalette: com.dragonnes.emu.ui.NeonPalette
        get() = com.dragonnes.emu.ui.NeonPalette.byId(p.getString("padPal", null), p.getString("padPalC", null))
        set(v) = p.edit().putString("padPal", v.id).putString("padPalC", v.encode()).apply()

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()

    var controlsOpacity: Float
        get() = p.getFloat("opacity", 0.75f)
        set(v) = p.edit().putFloat("opacity", v).apply()

    var controlsScale: Float
        get() = p.getFloat("scale", 1f)
        set(v) = p.edit().putFloat("scale", v).apply()
}
