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

/**
 * Озвучка текста через системный TextToSpeech. Звук идёт туда же, куда и музыка, — в наушники.
 * Если в настройках Android сменили движок (например, на RHVoice) — переподключается сам.
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()
    private var boundEngine: String? = null   // движок, к которому реально подключены

    // Ожидающие окончания фразы: utteranceId -> колбэк
    private val pending = mutableMapOf<String, () -> Unit>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = finish(utteranceId)
        @Deprecated("Deprecated in Java")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = finish(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
    }

    init {
        connect()
    }

    /** Подключение к движку, выбранному в системе по умолчанию. */
    private fun connect() {
        tts?.shutdown()
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        val created = TextToSpeech(appContext) { status ->
            boundEngine = runCatching { tts?.defaultEngine }.getOrNull()
            EventLog.add(
                if (status == TextToSpeech.SUCCESS) "Синтез речи: подключён к $boundEngine"
                else "Синтез речи: НЕ запустился ($status)"
            )
            deferred.complete(status == TextToSpeech.SUCCESS)
        }
        created.setOnUtteranceProgressListener(listener)
        tts = created
    }

    private fun finish(id: String?) {
        val cb = synchronized(pending) { pending.remove(id) }
        cb?.invoke()
    }

    /** Говорит текст и ждёт, пока фраза закончится. */
    suspend fun speak(text: String, rate: Float) {
        // Движок в настройках сменили — переподключаемся
        val systemEngine = runCatching { tts?.defaultEngine }.getOrNull()
        if (ready.isCompleted && systemEngine != null && systemEngine != boundEngine) {
            EventLog.add("Синтез речи: движок сменился на $systemEngine, переподключаюсь")
            connect()
        }
        if (!ready.await()) throw IllegalStateException("синтез речи недоступен")
        val engine = tts ?: throw IllegalStateException("синтез речи недоступен")

        selectRussian(engine)
        engine.setSpeechRate(rate)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            synchronized(pending) { pending[id] = { if (cont.isActive) cont.resume(Unit) } }
            cont.invokeOnCancellation { engine.stop() }
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result == TextToSpeech.ERROR) {
                EventLog.add("Синтез речи: ошибка воспроизведения")
                finish(id)
            }
        }
    }

    /** Включает русский язык; если движок не понимает ru-RU — ищет русский голос в списке. */
    private fun selectRussian(engine: TextToSpeech) {
        val lang = engine.setLanguage(Locale.forLanguageTag("ru-RU"))
        if (lang >= TextToSpeech.LANG_AVAILABLE) return

        val voice = runCatching {
            engine.voices?.firstOrNull { it.locale.language == "ru" || it.locale.isO3Language == "rus" }
        }.getOrNull()
        if (voice != null) {
            engine.setVoice(voice)
            EventLog.add("Синтез речи: выбран голос ${voice.name}")
        } else {
            EventLog.add("Синтез речи: нет русского голоса в $boundEngine (код $lang). Установите RHVoice.")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
