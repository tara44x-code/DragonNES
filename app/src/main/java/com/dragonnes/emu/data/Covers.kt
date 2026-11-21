package com.dragonnes.emu.data

import android.content.Context

/**
 * Picks the cover picture for a game from the real list of libretro-thumbnails box arts
 * (bundled in the APK, see scripts/fetch_db.sh), so the picture is found even when the ROM's
 * name or dump differs from the official No-Intro one.
 */
object Covers {
    private val cache = HashMap<GameSystem, Pair<Set<String>, List<String>>>()

    @Synchronized
    private fun names(context: Context, system: GameSystem): Pair<Set<String>, List<String>> = cache.getOrPut(system) {
        val list = runCatching {
            context.assets.open("db/${system.name}.boxarts.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        list.toHashSet() to list
    }

    /** Box-art name for [game], or null if nothing is close enough. */
    fun match(context: Context, game: Game): String? {
        val (set, list) = names(context, game.system)
        if (list.isEmpty()) return game.dbName
        game.dbName?.let { if (it in set) return it }
        val hints = listOfNotNull(game.dbName, game.fileTitle, game.headerName)
        val prefer = TitleMatch.tags(game.dbName ?: game.fileName) + setOf("USA", "World")
        return TitleMatch.bestMatches(hints, list, prefer, threshold = 0.6).firstOrNull()
    }
}
