package ly.majde.gtatranslator.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads translations aloud in Arabic with the system text-to-speech engine. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    @Volatile private var ready = false
    @Volatile private var currentLanguage: String? = null
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
    }

    /** Speaks Arabic by default; pass english = true for pronunciation of English words. */
    fun speak(text: String, english: Boolean = false) {
        if (!ready || text.isBlank()) return
        val tag = if (english) "en-US" else "ar"
        if (currentLanguage != tag) {
            runCatching { tts.setLanguage(Locale.forLanguageTag(tag)) }
            currentLanguage = tag
        }
        tts.setSpeechRate(if (english) 0.9f else 1f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "line")
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        runCatching { tts.shutdown() }
    }
}
