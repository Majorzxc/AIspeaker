package ru.gera.assistant

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Озвучка текста через системный TextToSpeech. Звук идёт туда же, куда и музыка, — в наушники. */
class Speaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    // Ожидающие окончания фразы: utteranceId -> колбэк
    private val pending = mutableMapOf<String, () -> Unit>()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    private fun finish(id: String?) {
        val cb = synchronized(pending) { pending.remove(id) }
        cb?.invoke()
    }

    /** Говорит текст и ждёт, пока фраза закончится. */
    suspend fun speak(text: String, rate: Float) {
        if (!ready.await()) throw IllegalStateException("синтез речи недоступен")

        tts.setLanguage(Locale.forLanguageTag("ru-RU"))
        tts.setSpeechRate(rate)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            synchronized(pending) { pending[id] = { if (cont.isActive) cont.resume(Unit) } }
            cont.invokeOnCancellation { tts.stop() }
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result == TextToSpeech.ERROR) finish(id)
        }
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
