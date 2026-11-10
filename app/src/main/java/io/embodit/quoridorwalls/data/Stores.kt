package io.embodit.quoridorwalls.data

import android.content.Context
import android.util.Log
import io.embodit.quoridorwalls.domain.FirstPick
import io.embodit.quoridorwalls.domain.Mode
import io.embodit.quoridorwalls.domain.GameSetup
import io.embodit.quoridorwalls.domain.GameStore
import io.embodit.quoridorwalls.domain.Language
import io.embodit.quoridorwalls.domain.SaveCodec
import io.embodit.quoridorwalls.domain.SavedGame
import io.embodit.quoridorwalls.domain.Settings
import io.embodit.quoridorwalls.domain.Skin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * files/game.txt, written atomically after every step: a temp file, a read-back check, then a rename; the previous
 * good copy stays as game.bak (Chess Stores.kt pattern).
 */
class FileGameStore(context: Context) : GameStore {
    private val dir = context.filesDir
    private val file = File(dir, "game.txt")
    private val backup = File(dir, "game.bak")

    override fun load(): SavedGame? =
        listOf(file, backup).firstNotNullOfOrNull { f -> runCatching { if (f.exists()) SaveCodec.decode(f.readText()) else null }.getOrNull() }

    override fun save(game: SavedGame) {
        val text = SaveCodec.encode(game)
        runCatching {
            val tmp = File(dir, "game.tmp")
            tmp.writeText(text)
            check(SaveCodec.decode(tmp.readText()) == game) { "read-back mismatch" }
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("Quoridor", "SAVE failed", it) }
    }

    override fun clear() {
        file.delete()
        backup.delete()
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so every window recomposes on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putBoolean("sound", s.sound)
            .putInt("volume", s.volume)
            .putBoolean("reducedMotion", s.reducedMotion)
            .putBoolean("firstRunDone", s.firstRunDone)
            .putString("skin", s.skin.name)
            .putBoolean("coachDone", s.coachDone)
            .putInt("setupLevel", s.lastSetup.level)
            .putString("setupFirst", s.lastSetup.first.name)
            .putString("setupMode", s.lastSetup.mode.name)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            sound = prefs.getBoolean("sound", true),
            volume = prefs.getInt("volume", 2).coerceIn(0, 2),
            reducedMotion = prefs.getBoolean("reducedMotion", false),
            firstRunDone = prefs.getBoolean("firstRunDone", false),
            skin = runCatching { Skin.valueOf(prefs.getString("skin", Skin.MODERN.name)!!) }.getOrDefault(Skin.MODERN),
            coachDone = prefs.getBoolean("coachDone", false),
            lastSetup = GameSetup(
                level = prefs.getInt("setupLevel", 1).coerceIn(1, 3),
                first = FirstPick.valueOf(prefs.getString("setupFirst", FirstPick.ME.name)!!),
                mode = runCatching { Mode.valueOf(prefs.getString("setupMode", Mode.AI.name)!!) }.getOrDefault(Mode.AI),
            ),
        )
    }.getOrElse { Settings() }
}
