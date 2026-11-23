package ru.gera.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Фоновый сервис ассистента. Пока он запущен:
 *  - держит MediaSession, чтобы ловить нажатия кнопки на наушниках;
 *  - по нажатию: слушает вопрос -> отправляет ИИ -> озвучивает ответ.
 */
class AssistantService : Service() {

    companion object {
        private const val TAG = "Gera"
        private const val CHANNEL_ID = "gera_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "ru.gera.assistant.STOP"
        const val ACTION_ASK = "ru.gera.assistant.ASK"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AssistantService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AssistantService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var settings: Settings
    private val recorder = VoiceRecorder()
    private lateinit var apiStt: ApiTranscriber
    private lateinit var speaker: Speaker
    private lateinit var llm: LlmClient
    private lateinit var routing: AudioRouting
    private lateinit var session: MediaSession
    private var tone: ToneGenerator? = null

    private var job: Job? = null
    private var lastPress = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        apiStt = ApiTranscriber(settings)
        speaker = Speaker(this)
        llm = LlmClient(settings)
        routing = AudioRouting(this)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()

        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Готов. Нажмите кнопку на наушниках."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            // Например, если сервис пытаются запустить из фона — Android запрещает доступ к микрофону
            Log.e(TAG, "startForeground failed", e)
            EventLog.add("Сервис НЕ запустился: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return
        }

        setupMediaSession()
        claimMediaButtons()
        isRunning = true
        EventLog.add(
            "Сервис запущен. Распознавание: " +
                if (settings.sttViaApi) "через API (${settings.sttModel})"
                else "офлайн Vosk, модель ${if (VoskEngine.isDownloaded(this)) "скачана" else "НЕ скачана"}"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_ASK -> {
                EventLog.add("Нажата кнопка «Спросить»")
                onTrigger()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        if (::session.isInitialized) session.release()
        routing.disableHeadsetMic()
        routing.abandonFocus()
        speaker.shutdown()
        tone?.release()
        super.onDestroy()
    }

    // ---------- Кнопка на наушниках ----------

    private fun setupMediaSession() {
        session = MediaSession(this, "GeraSession")
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event: KeyEvent = (if (Build.VERSION.SDK_INT >= 33) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                }) ?: return false

                val ours = event.keyCode in setOf(
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
                EventLog.add("Кнопка гарнитуры: код ${event.keyCode}, действие ${event.action}")
                if (!ours) return false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onTrigger()
                return true
            }
        })
        // Говорим системе "мы сейчас играем" — тогда кнопки гарнитуры приходят нам
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
        session.isActive = true
    }

    /**
     * Android отдаёт кнопки гарнитуры приложению, которое последним воспроизводило звук.
     * Проигрываем 0,2 секунды тишины, чтобы стать таким приложением.
     */
    private fun claimMediaButtons() {
        try {
            val rate = 16000
            val samples = ShortArray(rate / 5)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            mainHandler.postDelayed({ runCatching { track.stop(); track.release() } }, 600)
        } catch (e: Exception) {
            Log.w(TAG, "claimMediaButtons failed", e)
        }
    }

    // ---------- Основной сценарий ----------

    private fun onTrigger() {
        val now = System.currentTimeMillis()
        if (now - lastPress < 500) return // защита от "дребезга" кнопки
        lastPress = now

        // Повторное нажатие во время работы — прервать (например, заткнуть длинный ответ)
        if (job?.isActive == true) {
            EventLog.add("Прервано повторным нажатием")
            job?.cancel()
            speaker.stop()
            return
        }
        job = scope.launch { runPipeline() }
    }

    private suspend fun runPipeline() {
        try {
            routing.requestFocus() // музыка на паузу
            if (settings.useHeadsetMic && !routing.enableHeadsetMic()) {
                EventLog.add("Микрофон гарнитуры недоступен, слушаю микрофоном телефона")
            }

            beep()
            delay(250) // чтобы сигнал не попал в запись
            updateStatus("Слушаю…")

            if (!settings.sttViaApi && !VoskEngine.isDownloaded(this)) {
                throw IllegalStateException("офлайн-модель не скачана, откройте Геру и нажмите «Скачать»")
            }
            val pcm = recorder.recordPhrase(settings.useHeadsetMic)
            routing.disableHeadsetMic() // ответ — снова в нормальном качестве

            val question = if (pcm == null) null else {
                updateStatus("Распознаю…")
                val text = if (settings.sttViaApi) apiStt.transcribe(pcm)
                           else VoskEngine.transcribe(this, pcm)
                text.ifBlank { null }
            }
            EventLog.add("Распознано: ${question ?: "(ничего)"}")

            if (question == null) {
                updateStatus("Не расслышал")
                speaker.speak("Не расслышал.", settings.speechRate)
                return
            }

            updateStatus("Думаю: $question")
            val answer = llm.ask(question)
            EventLog.add("Ответ ИИ: $answer")

            updateStatus("Ответ: $answer")
            speaker.speak(answer, settings.speechRate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "pipeline error", e)
            EventLog.add("ОШИБКА: ${e.javaClass.simpleName}: ${e.message}")
            updateStatus("Ошибка: ${e.message}")
            val spoken = when (e) {
                is LlmClient.ApiException -> "Ошибка API, код ${e.code}."
                is java.net.UnknownHostException -> "Нет интернета."
                is java.net.SocketTimeoutException -> "Сервер не ответил вовремя."
                else -> "Ошибка: ${e.message ?: "неизвестная"}."
            }
            runCatching { speaker.speak(spoken, settings.speechRate) }
        } finally {
            routing.disableHeadsetMic()
            routing.abandonFocus() // музыка продолжается
            claimMediaButtons()
        }
    }

    private fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    // ---------- Уведомление ----------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Ассистент Гера", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val ask = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_ASK), flags
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gera)
            .setContentTitle("Гера включена")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Спросить", ask)
            .addAction(0, "Выключить", stop)
            .build()
    }
}
