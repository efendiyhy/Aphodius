package id.sengkuni.aphodius

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer

/** Efek suara pemutar: Surround bioskop (virtualizer + bass + loudness) dan equalizer 5 pita. */
class AudioFx {
    private var session = -1
    private var virt: Virtualizer? = null
    private var bass: BassBoost? = null
    private var loud: LoudnessEnhancer? = null
    private var eq: Equalizer? = null
    private var surround = false
    private var levels: List<Float> = EQ_FLAT

    fun attach(sessionId: Int) {
        if (sessionId <= 0 || sessionId == session) return
        release()
        session = sessionId
        runCatching { virt = Virtualizer(0, sessionId) }
        runCatching { bass = BassBoost(0, sessionId) }
        runCatching { loud = LoudnessEnhancer(sessionId) }
        runCatching { eq = Equalizer(0, sessionId) }
        apply(surround, levels)
    }

    fun apply(surroundOn: Boolean, eqLevels: List<Float>) {
        surround = surroundOn
        levels = eqLevels
        runCatching {
            virt?.let { v -> v.enabled = surroundOn; if (surroundOn && v.strengthSupported) v.setStrength(850) }
        }
        runCatching {
            bass?.let { b -> b.enabled = surroundOn; if (surroundOn && b.strengthSupported) b.setStrength(400) }
        }
        runCatching {
            loud?.let { l -> l.setTargetGain(if (surroundOn) 350 else 0); l.enabled = surroundOn }
        }
        runCatching {
            val e = eq ?: return@runCatching
            val on = eqLevels.any { kotlin.math.abs(it) > 0.01f }
            e.enabled = on
            val n = e.numberOfBands.toInt()
            val lo = e.bandLevelRange[0].toInt()
            val hi = e.bandLevelRange[1].toInt()
            for (b in 0 until n) {
                val pos = if (n == 1) 0f else b * 4f / (n - 1)
                val i = pos.toInt().coerceIn(0, 4)
                val j = (i + 1).coerceAtMost(4)
                val f = pos - i
                val v = eqLevels[i] * (1 - f) + eqLevels[j] * f
                val mb = if (v >= 0) v * hi else -v * lo
                e.setBandLevel(b.toShort(), mb.toInt().coerceIn(lo, hi).toShort())
            }
        }
    }

    fun release() {
        runCatching { virt?.release() }; runCatching { bass?.release() }
        runCatching { loud?.release() }; runCatching { eq?.release() }
        virt = null; bass = null; loud = null; eq = null; session = -1
    }
}

val EQ_FLAT = listOf(0f, 0f, 0f, 0f, 0f)
val EQ_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3,6 kHz", "14 kHz")
val EQ_PRESETS = listOf(
    "Normal" to EQ_FLAT,
    "Bioskop" to listOf(0.45f, 0.2f, -0.1f, 0.25f, 0.4f),
    "Bass" to listOf(0.8f, 0.5f, 0f, 0f, 0f),
    "Vokal" to listOf(-0.3f, -0.1f, 0.5f, 0.4f, 0f),
)
