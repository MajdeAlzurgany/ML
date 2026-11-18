package ly.majde.gtatranslator.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Records the microphone and cuts it into speech segments with a lightweight,
 * adaptive energy VAD. The noise floor follows the game's music/ambience, so a
 * segment starts when voices rise above it. Each segment is delivered as a WAV file.
 */
class SpeechSegmenter(
    private val sensitivity: () -> Float,
    private val onLevel: (Float) -> Unit,
    private val onSegment: (ByteArray) -> Unit,
) {

    @SuppressLint("MissingPermission")
    suspend fun run(): Unit = withContext(Dispatchers.IO) {
        val frameSamples = SAMPLE_RATE * FRAME_MS / 1000
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "Audio recording is not supported on this device" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, frameSamples * 2 * 10),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Microphone unavailable")
        }

        val frame = ShortArray(frameSamples)
        val noiseHistory = DoubleArray(NOISE_WINDOW_FRAMES)
        var historyCount = 0
        var historyPos = 0
        val preRoll = ArrayDeque<ShortArray>()
        val segment = ByteArrayOutputStream()
        var inSpeech = false
        var voicedFrames = 0
        var silentFrames = 0
        var segmentFrames = 0

        try {
            recorder.startRecording()
            while (isActive) {
                val read = recorder.read(frame, 0, frameSamples)
                if (read <= 0) continue
                val chunk = frame.copyOf(read)
                val rms = rms(chunk)

                noiseHistory[historyPos] = rms
                historyPos = (historyPos + 1) % NOISE_WINDOW_FRAMES
                if (historyCount < NOISE_WINDOW_FRAMES) historyCount++
                val noiseFloor = percentile(noiseHistory, historyCount, NOISE_PERCENTILE)

                val s = sensitivity().coerceIn(0f, 1f)
                val threshold = maxOf(noiseFloor * (3.0 - 1.8 * s), MIN_RMS + (1f - s) * 400.0)
                val voiced = rms > threshold
                onLevel((rms / 8000.0).coerceIn(0.0, 1.0).toFloat())

                if (!inSpeech) {
                    preRoll.addLast(chunk)
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (voiced) {
                        inSpeech = true
                        segment.reset()
                        preRoll.forEach { segment.writePcm(it) }
                        preRoll.clear()
                        voicedFrames = 1
                        silentFrames = 0
                        segmentFrames = 1
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
                        if (voicedFrames >= MIN_VOICED_FRAMES) {
                            onSegment(pcm16ToWav(segment.toByteArray(), SAMPLE_RATE))
                        }
                        segment.reset()
                        // If someone is still talking at the length limit, continue straight away.
                        inSpeech = endByLength && voiced
                        voicedFrames = if (inSpeech) 1 else 0
                        silentFrames = 0
                        segmentFrames = if (inSpeech) 1 else 0
                    }
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            onLevel(0f)
        }
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (v in samples) {
            val d = v.toDouble()
            sum += d * d
        }
        return sqrt(sum / samples.size)
    }

    private fun percentile(values: DoubleArray, count: Int, p: Double): Double {
        if (count == 0) return 0.0
        val sorted = values.copyOf(count).also { it.sort() }
        val index = ((count - 1) * p).toInt().coerceIn(0, count - 1)
        return sorted[index]
    }

    private fun ByteArrayOutputStream.writePcm(samples: ShortArray) {
        for (s in samples) {
            val v = s.toInt()
            write(v and 0xFF)
            write((v shr 8) and 0xFF)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 30
        private const val NOISE_WINDOW_FRAMES = 100 // ~3 s
        private const val NOISE_PERCENTILE = 0.2
        private const val PRE_ROLL_FRAMES = 10 // ~300 ms kept before speech starts
        private const val HANGOVER_FRAMES = 25 // ~750 ms of silence ends a segment
        private const val MAX_SEGMENT_FRAMES = 400 // ~12 s max per request
        private const val MIN_VOICED_FRAMES = 12 // ~360 ms of voice to be worth sending
        private const val MIN_RMS = 200.0

        /** Wraps 16-bit mono PCM in a minimal WAV container. */
        fun pcm16ToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + pcm.size)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort()) // PCM
                putShort(1.toShort()) // mono
                putInt(sampleRate)
                putInt(sampleRate * 2) // byte rate
                putShort(2.toShort()) // block align
                putShort(16.toShort()) // bits per sample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcm.size)
            }
            return header.array() + pcm
        }
    }
}
