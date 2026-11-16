package cz.fenixen.navigation.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text-to-speech with navigation-grade audio handling.
 *
 * Why it matters in the car: with USAGE_ASSISTANCE_NAVIGATION_GUIDANCE the head unit knows
 * this is a nav prompt – it routes it to the right channel and *ducks* (quietens) Spotify
 * instead of pausing it. AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK says "short interruption".
 */
class VoiceGuide(context: Context, private val isEnabled: () -> Boolean) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val utteranceCounter = AtomicInteger()

    fun speak(text: String) {
        if (!isEnabled()) return
        val engine = tts ?: createEngine()
        if (!ready) {
            pending = text // nejnovější pokyn je nejdůležitější, starší zahodíme
            return
        }
        audioManager.requestAudioFocus(focusRequest)
        val id = "nav-${utteranceCounter.incrementAndGet()}"
        // QUEUE_FLUSH: když přijde nový pokyn, starý (už neaktuální) přerušíme.
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun stop() {
        tts?.stop()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun createEngine(): TextToSpeech {
        val engine = TextToSpeech(appContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed: $status")
                return@TextToSpeech
            }
            configure()
        }
        tts = engine
        return engine
    }

    private fun configure() {
        val engine = tts ?: return
        val czech = Locale.forLanguageTag("cs-CZ")
        val result = engine.setLanguage(czech)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Czech TTS voice not installed – using default voice")
        }
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        })
        ready = true
        pending?.let {
            pending = null
            speak(it)
        }
    }

    private companion object {
        const val TAG = "VoiceGuide"
    }
}
