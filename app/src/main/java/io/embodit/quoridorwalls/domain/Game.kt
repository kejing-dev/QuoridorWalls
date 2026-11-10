package io.embodit.quoridorwalls.domain

enum class FirstPick { ME, THEM, RANDOM }

enum class Language { SYSTEM, ZH, EN }

/** design skins: modern (default; coral / navy pawns, mist-green board, charcoal walls) and classic (ivory / deep cinnabar, jade lacquer, maple). */
enum class Skin { MODERN, CLASSIC }

/** Who plays the far side (side 1): the AI, or the same person on this headset (2026-11-09 用户真机：「加个自己和自己下的模式」). */
enum class Mode { AI, LOCAL }

/** One game's setup with the first mover resolved. Side 0 = near pawn, side 1 = far pawn (the AI in [Mode.AI]). */
data class GameConfig(val level: Int = Engine.EASY, val firstSide: Int = Q.YOU, val mode: Mode = Mode.AI) {
    val local: Boolean get() = mode == Mode.LOCAL
    fun isHuman(side: Int): Boolean = side == Q.YOU || mode == Mode.LOCAL
    /** design gameSpec.pathBands: bands on by default for easy / medium, off for hard (the play-page button flips them). */
    val bandsDefault: Boolean get() = level != Engine.HARD
}

/** The new-game page's choices (remembered between games; a random first mover only goes into the game). */
data class GameSetup(val level: Int = Engine.EASY, val first: FirstPick = FirstPick.ME, val mode: Mode = Mode.AI)

/** App settings (SharedPreferences). design dataModel.settings: language, sound, volume, reducedMotion, firstRunDone. */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    /** Set by the first move you make or by [开始下棋] (design q1-2); a saved game is restored whatever it says. */
    val firstRunDone: Boolean = false,
    val skin: Skin = Skin.MODERN,
    /** The guided first game (three lit moves + two coach lines) has been played. */
    val coachDone: Boolean = false,
    val lastSetup: GameSetup = GameSetup(),
)

/** Stats for the result card (design gameSpec.end / dataModel.stats). */
data class Stats(val movesYou: Int = 0, val detourGiven: Int = 0)

/** Everything a turn boundary needs to be rebuilt. */
data class Snap(val pos: Pos, val side: Int, val turnNo: Int, val stats: Stats, val last: LastMove?)

/** The last move, for the from / to marks. */
data class LastMove(val side: Int, val move: Move)

/** What a game looks like on disk (files/game.txt). */
data class SavedGame(
    val config: GameConfig,
    val now: Snap,
    val undo: List<Snap> = emptyList(),
    val hintOn: Boolean = false,
    val bandsOn: Boolean = true,
)

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/**
 * Line codec: `qw1|level|first|hint|bands|<snap>|<snap>;<snap>...`; a snap is
 * `you,ai,leftYou,leftAi,walls,side,turn,movesYou,detour,last` where walls = `e3h0.d5v1` (notation + owner) and
 * last = `0:e2` / `1:e3h` / `-`.
 */
object SaveCodec {
    private fun walls(p: Pos) = p.walls.joinToString(".") { it.wall.notation + it.side }
    private fun parseWalls(s: String): List<Placed> = if (s.isBlank() || s == "-") emptyList() else s.split(".").map {
        Placed(requireNotNull(Q.parseWall(it.substring(0, 3))), it.substring(3).toInt().coerceIn(0, 1))
    }

    private fun last(m: LastMove?) = m?.let { "${it.side}:" + when (val mv = it.move) { is Move.Step -> Q.name(mv.from) + Q.name(mv.to) + if (mv.jump) "j" else ""; is Move.Place -> mv.wall.notation } } ?: "-"
    private fun parseLast(s: String): LastMove? {
        if (s == "-") return null
        val side = s.substringBefore(':').toInt()
        val t = s.substringAfter(':')
        return if (t.length == 3 && t[2] in "hv") LastMove(side, Move.Place(requireNotNull(Q.parseWall(t))))
        else LastMove(side, Move.Step(requireNotNull(Q.parseCell(t.substring(0, 2))), requireNotNull(Q.parseCell(t.substring(2, 4))), t.endsWith("j")))
    }

    fun snap(s: Snap): String = listOf(
        Q.name(s.pos.pawns[0]), Q.name(s.pos.pawns[1]), s.pos.left[0].toString(), s.pos.left[1].toString(), walls(s.pos).ifEmpty { "-" },
        s.side.toString(), s.turnNo.toString(), s.stats.movesYou.toString(), s.stats.detourGiven.toString(), last(s.last),
    ).joinToString(",")

    fun parseSnap(t: String): Snap {
        val f = t.split(",")
        require(f.size == 10)
        val pos = Pos(listOf(requireNotNull(Q.parseCell(f[0])), requireNotNull(Q.parseCell(f[1]))), parseWalls(f[4]), listOf(f[2].toInt(), f[3].toInt()))
        return Snap(pos, f[5].toInt().coerceIn(0, 1), f[6].toInt(), Stats(f[7].toInt(), f[8].toInt()), parseLast(f[9]))
    }

    fun encode(g: SavedGame): String = listOf(
        "qw1", g.config.level.toString(), g.config.firstSide.toString(), if (g.hintOn) "1" else "0", if (g.bandsOn) "1" else "0",
        snap(g.now), g.undo.joinToString(";") { snap(it) }, if (g.config.local) "local" else "ai",
    ).joinToString("|")

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size < 7 || f[0] != "qw1") return null
        SavedGame(
            config = GameConfig(f[1].toInt().coerceIn(1, 3), f[2].toInt().coerceIn(0, 1), if (f.getOrNull(7) == "local") Mode.LOCAL else Mode.AI),
            hintOn = f[3] == "1", bandsOn = f[4] == "1",
            now = parseSnap(f[5]),
            undo = if (f[6].isBlank()) emptyList() else f[6].split(";").map { parseSnap(it) },
        )
    }.getOrNull()

    /** Debug `setup`: `you=e4 ai=e6 walls=e3h0.d5v1 left=8,9 side=0` (any part optional). */
    fun parseSetup(s: String, base: Pos = Pos.START): Pair<Pos, Int?> {
        var pos = base; var side: Int? = null
        for (part in s.split(" ").filter { it.contains('=') }) {
            val (k, v) = part.split("=", limit = 2)
            when (k) {
                "you" -> Q.parseCell(v)?.let { pos = pos.copy(pawns = listOf(it, pos.pawns[1])) }
                "ai" -> Q.parseCell(v)?.let { pos = pos.copy(pawns = listOf(pos.pawns[0], it)) }
                "walls" -> { val w = parseWalls(v); pos = pos.copy(walls = w, left = listOf(Q.WALLS - w.count { it.side == 0 }, Q.WALLS - w.count { it.side == 1 })) }
                "left" -> v.split(",").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { pos = pos.copy(left = it) }
                "side" -> side = v.toIntOrNull()?.coerceIn(0, 1)
            }
        }
        return pos to side
    }
}
