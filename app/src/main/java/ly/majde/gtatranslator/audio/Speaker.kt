package ly.majde.gtatranslator.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads translations aloud in Arabic with the system text-to-speech engine. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    @Volatile private var ready = false
    @Volatile private var languageSet = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        if (!languageSet) {
            runCatching { tts.setLanguage(Locale.forLanguageTag("ar")) }
            languageSet = true
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "line")
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        runCatching { tts.shutdown() }
    }
}
