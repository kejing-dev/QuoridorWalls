package io.embodit.quoridorwalls.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.quoridorwalls.data.FileGameStore
import io.embodit.quoridorwalls.data.SettingsStore
import io.embodit.quoridorwalls.domain.GameFlow
import io.embodit.quoridorwalls.domain.Sound
import io.embodit.quoridorwalls.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

const val TAG = "Quoridor"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.quoridorwalls.ui.board.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = FileGameStore(context)
    val flow = GameFlow(
        scope = scope,
        engineDispatcher = Dispatchers.Default,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onSound = { sounds.play(it) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()

    init {
        scope.launch {
            settings.settings.collect { s ->
                sounds.enabled = s.sound
                sounds.volumeLevel = s.volume
            }
        }
        flow.start()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/** Volume percent of each snap stop (Settings.volume 0..2). */
val VOLUME_PERCENT = intArrayOf(35, 65, 100)

/**
 * Short effects from assets/sounds/<name>.wav (+ _2 / _3 variants) through a SoundPool. Repeated sounds (dice, piece
 * landing, CC0 recordings) rotate their 2-4 variants with a random rate of 0.95-1.05 that never repeats back to back (design sound.variation).
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled = true
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sound, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private val lastRate = HashMap<Sound, Float>()
    private val lastVariant = HashMap<Sound, Int>()
    private val varied = setOf(Sound.PLACE, Sound.JUMP, Sound.GRAB, Sound.WALL, Sound.BOUNCE)
    /** D51 补: one instance per event at a time; a new one stops the previous (no stacking, no clipping on fast repeats). */
    private val lastStream = HashMap<Sound, Int>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (s in Sound.entries) {
            val base = s.name.lowercase()
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav", "${base}_4.wav")) {
                runCatching { context.assets.openFd("sounds/$name").use { fd -> found += pool.load(fd, 1) } }
            }
            if (found.isNotEmpty()) ids[s] = found else if (s != Sound.SELECT) Log.w(TAG, "no sound for $s")
        }
    }

    fun play(s: Sound) {
        if (!enabled) return
        val variants = ids[s] ?: return
        val vol = VOLUME_PERCENT[volumeLevel.coerceIn(0, 2)] / 100f
        var rate = 1f
        var v = 0
        if (s in varied) {
            rate = 0.95f + random.nextFloat() * 0.10f
            val last = lastRate[s] ?: 1f
            if (abs(rate - last) < 0.01f) rate = if (rate < 1f) rate + 0.02f else rate - 0.02f
            lastRate[s] = rate
            v = random.nextInt(variants.size)
            if (v == lastVariant[s] && variants.size > 1) v = (v + 1) % variants.size
            lastVariant[s] = v
        }
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        // the band swish sits ~6 dB under the knocks; win / lose are quieter UI cues
        val gain = when (s) { Sound.WIN, Sound.LOSE, Sound.ILLEGAL -> 0.8f; else -> 1f }
        synchronized(lastStream) {
            lastStream[s]?.let { pool.stop(it) }
            val stream = pool.play(id, vol * gain, vol * gain, 1, 0, rate)
            if (stream != 0) lastStream[s] = stream
        }
    }

    fun release() = runCatching { pool.release() }
}
