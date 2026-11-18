package com.dragonnes.emu.data

import android.content.Context
import java.io.File

/**
 * Identifies ROMs using the No-Intro DAT files from libretro-database, in three steps:
 *  1. exact CRC32 match,
 *     (the DAT lists both the headered .nes CRC and the headerless one, so both are tried),
 *  2. serial number (when the ROM carries one),
 *  3. fuzzy title match on the header name / file name.
 * Each DAT (~1 MB) is downloaded once and cached; lookups work offline afterwards.
 */
class GameDatabase(private val context: Context) {
    private val dir = File(context.filesDir, "db")
    private val cache = mutableMapOf<GameSystem, Table>()

    private class Table(val byCrc: Map<String, String>, val bySerial: Map<String, String>, val names: List<String>)

    private val block = Regex("""game \(\s*name "([^"]+)"(.*?)\n\)""", RegexOption.DOT_MATCHES_ALL)
    private val crcRe = Regex("""\bcrc ([0-9A-Fa-f]{8})""")
    private val serialRe = Regex("""serial "([^"]+)"""")

    @Synchronized
    fun identify(system: GameSystem, crcs: List<String>, serial: String?, titleHints: List<String>, regionHints: Set<String>): String? {
        val t = table(system)
        crcs.forEach { crc -> t.byCrc[crc.uppercase()]?.let { return it } }
        serial?.let { s -> t.bySerial[s]?.let { return it } }
        return TitleMatch.bestMatches(titleHints, t.names, regionHints, threshold = 0.88).firstOrNull()
    }

    /** Downloads the newest DATs (optional; the bundled copy already works offline). */
    @Synchronized
    fun refreshOnline(): Int {
        var ok = 0
        GameSystem.entries.forEach { sys ->
            if (runCatching { Net.download(LibretroUrls.datUrl(sys), File(dir, "${sys.name}.dat")) }.getOrDefault(false)) ok++
        }
        cache.clear()
        return ok
    }

    private fun table(system: GameSystem): Table = cache[system] ?: run {
        val file = OfflineDb.dat(context, system)   // bundled in the APK -> works offline
        if (!file.exists()) runCatching { Net.download(LibretroUrls.datUrl(system), file) }
        if (!file.exists()) return Table(emptyMap(), emptyMap(), emptyList())

        val byCrc = HashMap<String, String>(); val bySerial = HashMap<String, String>(); val names = ArrayList<String>()
        block.findAll(file.readText()).forEach { m ->
            val name = m.groupValues[1]; val body = m.groupValues[2]
            names += name
            crcRe.findAll(body).forEach { byCrc[it.groupValues[1].uppercase()] = name }
            serialRe.find(body)?.groupValues?.get(1)?.split(',')?.forEach { s ->
                RomHeader.normSerial(s)?.let { bySerial.putIfAbsent(it, name) }
            }
        }
        Table(byCrc, bySerial, names).also { cache[system] = it }
    }
}

/**
 * iNES / NES 2.0 header (16 bytes: "NES" 0x1A, PRG/CHR sizes, mapper...).
 * It holds no title or serial, so identification relies on CRC + file name.
 */
object RomHeader {
    class Info(val serial: String?, val name: String?, val mapper: Int)

    const val INES_SIZE = 16

    fun isINes(head: ByteArray) = head.size >= INES_SIZE &&
        head[0] == 'N'.code.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'S'.code.toByte() && head[3] == 0x1A.toByte()

    fun parse(system: GameSystem, head: ByteArray): Info? {
        if (system != GameSystem.NES || !isINes(head)) return null
        val mapper = ((head[6].toInt() and 0xF0) ushr 4) or (head[7].toInt() and 0xF0)
        return Info(serial = null, name = null, mapper = mapper)
    }

    /** "DIF-001" / "NES-SM-USA" -> normalized key used by the DAT serial table. */
    fun normSerial(raw: String): String? {
        val s = raw.uppercase().trim()
        val m = Regex("""([A-Z]{1,4})?[- ]?(\d{3,6})""").find(s) ?: return null
        return m.groupValues[1] + m.groupValues[2]
    }
}
