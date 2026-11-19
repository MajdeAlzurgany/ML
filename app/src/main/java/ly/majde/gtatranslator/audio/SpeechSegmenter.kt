package ly.majde.gtatranslator.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Listens to the microphone and cuts out speech segments.
 * Uses the Silero neural voice-activity detector (on-device), which tells voices
 * apart from music, gunfire and traffic far better than a volume threshold.
 * Falls back to an adaptive energy detector if the model cannot load.
 */
class SpeechSegmenter(
    private val context: Context,
    private val sensitivity: () -> Float,
    private val onLevel: (Float) -> Unit,
    private val onSegment: (ByteArray) -> Unit,
) {

    @SuppressLint("MissingPermission")
    suspend fun run(): Unit = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "Audio recording is not supported on this device" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME * 2 * 16),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Microphone unavailable")
        }

        var vadMode = modeFor(sensitivity())
        var vad: VadSilero? = createVad(vadMode)
        val energy = EnergyVad()

        val frame = ShortArray(FRAME)
        val preRoll = ArrayDeque<ShortArray>()
        val segment = ByteArrayOutputStream()
        var inSpeech = false
        var speechRun = 0
        var voicedFrames = 0
        var silentFrames = 0
        var segmentFrames = 0

        try {
            recorder.startRecording()
            while (isActive) {
                if (!readFully(recorder, frame)) continue
                val chunk = frame.copyOf()
                val s = sensitivity().coerceIn(0f, 1f)
                onLevel((rms(chunk) / 7000.0).coerceIn(0.0, 1.0).toFloat())

                val wanted = modeFor(s)
                if (wanted != vadMode) {
                    vad?.close()
                    vad = createVad(wanted)
                    vadMode = wanted
                }
                val voiced = vad?.let { v -> runCatching { v.isSpeech(chunk) }.getOrNull() } ?: energy.isSpeech(chunk, s)

                if (!inSpeech) {
                    preRoll.addLast(chunk)
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    speechRun = if (voiced) speechRun + 1 else 0
                    if (speechRun >= START_FRAMES) {
                        inSpeech = true
                        segment.reset()
                        preRoll.forEach { segment.writePcm(it) }
                        segmentFrames = preRoll.size
                        preRoll.clear()
                        voicedFrames = speechRun
                        silentFrames = 0
                    }
                } else {
                    segment.writePcm(chunk)
                    segmentFrames++
                    if (voiced) {
                        voicedFrames++
                        silentFrames = 0
                    } else {
                        silentFrames++
                    }
                    val endBySilence = silentFrames >= HANGOVER_FRAMES
                    val endByLength = segmentFrames >= MAX_SEGMENT_FRAMES
                    if (endBySilence || endByLength) {
                        if (voicedFrames >= MIN_VOICED_FRAMES) onSegment(pcm16ToWav(segment.toByteArray()))
                        segment.reset()
                        inSpeech = false
                        speechRun = if (endByLength && voiced) START_FRAMES - 1 else 0
                        voicedFrames = 0
                        silentFrames = 0
                        segmentFrames = 0
                    }
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            vad?.close()
            onLevel(0f)
        }
    }

    private fun createVad(mode: Mode): VadSilero? = try {
        VadSilero(
            context,
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_512,
            mode = mode,
            speechDurationMs = 0,
            silenceDurationMs = 0,
        )
    } catch (t: Throwable) {
        null
    }

    /** Higher sensitivity → more permissive detector. */
    private fun modeFor(sensitivity: Float): Mode = when {
        sensitivity >= 0.66f -> Mode.NORMAL
        sensitivity >= 0.33f -> Mode.AGGRESSIVE
        else -> Mode.VERY_AGGRESSIVE
    }

    private fun readFully(recorder: AudioRecord, buffer: ShortArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = recorder.read(buffer, offset, buffer.size - offset)
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    private fun ByteArrayOutputStream.writePcm(samples: ShortArray) {
        for (s in samples) {
            val v = s.toInt()
            write(v and 0xFF)
            write((v shr 8) and 0xFF)
        }
    }

    /** Volume-based fallback detector with an adaptive noise floor. */
    private class EnergyVad {
        private val history = DoubleArray(94) // ~3 s of 32 ms frames
        private var count = 0
        private var pos = 0

        fun isSpeech(frame: ShortArray, sensitivity: Float): Boolean {
            val level = rms(frame)
            history[pos] = level
            pos = (pos + 1) % history.size
            if (count < history.size) count++
            val sorted = history.copyOf(count).also { it.sort() }
            val floor = sorted[((count - 1) * 0.2).toInt().coerceIn(0, count - 1)]
            val threshold = maxOf(floor * (3.0 - 1.8 * sensitivity), 200.0 + (1f - sensitivity) * 400.0)
            return level > threshold
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME = 512 // 32 ms
        private const val PRE_ROLL_FRAMES = 10 // ~320 ms kept before speech starts
        private const val START_FRAMES = 2 // ~64 ms of voice starts a segment
        private const val HANGOVER_FRAMES = 15 // ~480 ms of silence ends a segment
        private const val MAX_SEGMENT_FRAMES = 280 // ~9 s max per request
        private const val MIN_VOICED_FRAMES = 8 // ~256 ms of voice to be worth sending

        private fun rms(samples: ShortArray): Double {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (v in samples) {
                val d = v.toDouble()
                sum += d * d
            }
            return sqrt(sum / samples.size)
        }

        fun pcm16ToWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + pcm.size)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(1.toShort())
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2.toShort())
                putShort(16.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcm.size)
            }
            return header.array() + pcm
        }
    }
}
