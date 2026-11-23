package ru.gera.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Распознавание речи через системный SpeechRecognizer (обычно это движок Google).
 * Вызывать только из главного потока — так требует Android.
 */
class SpeechInput(private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Слушает одну фразу до паузы и возвращает текст.
     * Возвращает null, если ничего не распознано; бросает исключение при ошибке.
     */
    suspend fun listenOnce(): String? = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                EventLog.add("Распознавание: результат получен")
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                recognizer.destroy()
                if (cont.isActive) cont.resume(if (text.isNullOrEmpty()) null else text)
            }

            override fun onError(error: Int) {
                EventLog.add("Распознавание: ошибка $error (${SpeechException(error).message})")
                recognizer.destroy()
                if (!cont.isActive) return
                when (error) {
                    // "Ничего не сказали" — это не ошибка, просто пустой результат
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> cont.resume(null)
                    else -> cont.resumeWith(Result.failure(SpeechException(error)))
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = EventLog.add("Микрофон готов — говорите")
            override fun onBeginningOfSpeech() = EventLog.add("Слышу речь…")
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = EventLog.add("Речь закончилась, распознаю…")
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        cont.invokeOnCancellation {
            // Отмена приходит не обязательно из главного потока — уходим в него
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                recognizer.cancel()
                recognizer.destroy()
            }
        }

        recognizer.startListening(intent)
    }
}

class SpeechException(val code: Int) : Exception() {
    override val message: String
        get() = when (code) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "нет сети для распознавания"
            SpeechRecognizer.ERROR_AUDIO -> "ошибка микрофона"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "нет доступа к микрофону"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "распознаватель занят"
            SpeechRecognizer.ERROR_NO_MATCH -> "речь не распознана"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "не было речи"
            SpeechRecognizer.ERROR_CLIENT -> "ошибка клиента распознавания"
            else -> "ошибка распознавания, код $code"
        }
}
