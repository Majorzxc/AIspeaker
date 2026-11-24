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
import kotlinx.coroutines.isActive
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
        const val ACTION_MIC_TEST = "ru.gera.assistant.MIC_TEST"
        const val ACTION_PLAY_LAST = "ru.gera.assistant.PLAY_LAST"

        /** Кнопки гарнитуры, которые запускают Геру. */
        val HEADSET_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE
        )

        /** Звук последнего запроса — ровно то, что ушло на распознавание. */
        @Volatile
        var lastRequestPcm: ByteArray? = null

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
    private val recorder by lazy { VoiceRecorder(this) }
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
                buildNotification("Готов. Нажмите кнопку на наушниках или скажите слово-триггер."),
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
        startWakeLoop()
        // Раз в 2 минуты незаметно "напоминаем" Android, что кнопки наушников — наши
        scope.launch {
            while (isActive) {
                delay(120_000)
                if (!busy) claimMediaButtons()
            }
        }
        EventLog.add(
            "Сервис запущен. Распознавание: " +
                if (settings.sttViaApi) "через API (${settings.sttModel})"
                else "офлайн Vosk, модель ${if (VoskEngine.isDownloaded(this)) "скачана" else "НЕ скачана"}"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_MIC_TEST -> micTest()
            ACTION_PLAY_LAST -> playLastRequest()
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

                val ours = event.keyCode in HEADSET_KEYS
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
        // Запасной путь: если Android перестанет считать нас плеером, кнопка придёт в MediaButtonReceiver
        val receiver = android.content.ComponentName(this, MediaButtonReceiver::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            session.setMediaButtonBroadcastReceiver(receiver)
        } else {
            @Suppress("DEPRECATION")
            session.setMediaButtonReceiver(
                PendingIntent.getBroadcast(
                    this, 3, Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver), PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
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

    private val wake by lazy { WakeWordListener(this) }
    private var wakeJob: Job? = null

    @Volatile
    private var busy = false // идёт вопрос-ответ

    /** Фоновое ожидание слова-триггера (если включено в настройках). */
    private fun startWakeLoop() {
        if (!settings.wakeWordEnabled || wakeJob?.isActive == true) return
        if (!VoskEngine.isDownloaded(this)) {
            EventLog.add("Слово-триггер не работает: офлайн-модель не скачана")
            return
        }
        wakeJob = scope.launch {
            val words = wake.parseWords(settings.wakeWords)
            val whisperMode = settings.whisperWake && settings.sttViaApi
            if (settings.whisperWake && !settings.sttViaApi) {
                EventLog.add("Триггер шёпотом работает только с распознаванием через API")
            }
            while (isActive) {
                val heard = try {
                    wake.awaitWakeWord(words, settings.micSensitivity, whisperMode, settings.micChoice)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    EventLog.add("Слово-триггер: ошибка ${e.message}")
                    delay(3000)
                    continue
                }
                when (heard) {
                    is WakeWordListener.Event.Hit -> runPipeline(heard.pcm)
                    is WakeWordListener.Event.Candidate -> {
                        // Проверяем через API, было ли в этой фразе слово-триггер
                        val text = try {
                            transcribe(heard.pcm)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            EventLog.add("Проверка шёпота: ошибка ${e.message}")
                            delay(3000)
                            continue
                        }
                        if (findWakeWord(text) != null) {
                            EventLog.add("Триггер шёпотом: «$text»")
                            runPipeline(null, heardText = text)
                        } else if (text.isNotBlank()) {
                            EventLog.add("Не триггер: «${text.take(40)}»")
                        }
                    }
                }
            }
        }
    }

    private fun onTrigger() {
        val now = System.currentTimeMillis()
        if (now - lastPress < 500) return // защита от "дребезга" кнопки
        lastPress = now

        // Повторное нажатие во время работы — прервать (например, заткнуть длинный ответ)
        if (busy) {
            EventLog.add("Прервано повторным нажатием")
            speaker.stop()
            val j1 = job
            val j2 = wakeJob
            j1?.cancel()
            j2?.cancel()
            scope.launch {
                j1?.join()
                j2?.join()
                startWakeLoop()
            }
            return
        }

        // Микрофон занят ожиданием слова-триггера — освобождаем его на время вопроса
        val waiting = wakeJob
        waiting?.cancel()
        job = scope.launch {
            waiting?.join()
            runPipeline(null)
            startWakeLoop()
        }
    }

    /** Проиграть звук последнего запроса — чтобы услышать, что именно ушло на распознавание. */
    private fun playLastRequest() {
        val pcm = lastRequestPcm
        if (pcm == null) {
            EventLog.add("Запросов ещё не было")
            return
        }
        if (busy) return
        scope.launch {
            EventLog.add("Воспроизвожу последний запрос (${pcm.size / 32000.0} с)")
            Earcons.playPcm(pcm, AudioUtil.SAMPLE_RATE)
        }
    }

    /** Проверка микрофона: записать 4 секунды и проиграть обратно. */
    private fun micTest() {
        if (busy) return
        val waiting = wakeJob
        waiting?.cancel()
        job = scope.launch {
            waiting?.join()
            busy = true
            try {
                routing.requestFocus()
                if (settings.useHeadsetMic && !routing.enableHeadsetMic()) {
                    EventLog.add("Микрофон гарнитуры недоступен")
                }
                Earcons.listenStart()
                updateStatus("Проверка микрофона: говорите…")
                val pcm = recorder.recordFixed(settings.micChoice, 4000)
                routing.disableHeadsetMic()
                Earcons.listenEnd()
                updateStatus("Проверка: воспроизвожу запись")
                Earcons.playPcm(pcm, AudioUtil.SAMPLE_RATE) // как есть, без усиления — чтобы было слышно реальное качество
                updateStatus("Готов.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EventLog.add("Проверка микрофона: ошибка ${e.message}")
            } finally {
                busy = false
                routing.disableHeadsetMic()
                routing.abandonFocus()
                claimMediaButtons()
                startWakeLoop()
            }
        }
    }

    /**
     * Вопрос → ответ.
     * @param heardPcm звук фразы со словом-триггером (в ней может уже быть вопрос) или null при запуске кнопкой.
     */
    private suspend fun runPipeline(heardPcm: ByteArray?, heardText: String? = null) {
        busy = true
        try {
            routing.requestFocus() // музыка на паузу

            var question: String? = null
            if (heardText != null) {
                question = stripWakeWord(heardText)
            } else if (heardPcm != null) {
                updateStatus("Распознаю…")
                val text = transcribe(heardPcm)
                EventLog.add("Фраза с триггером: $text")
                question = stripWakeWord(text)
            }
            // Вопрос уже прозвучал вместе с триггером — подтверждаем сигналом "услышал"
            if (question != null) Earcons.listenEnd()

            // Вопроса ещё нет (нажали кнопку или сказали только "Гера") — слушаем его отдельно
            if (question == null) {
                if (settings.useHeadsetMic && !routing.enableHeadsetMic()) {
                    EventLog.add("Микрофон гарнитуры недоступен, слушаю микрофоном телефона")
                }
                Earcons.listenStart() // сигнал "говорите" (ждёт, пока доиграет, чтобы не попасть в запись)
                updateStatus("Слушаю…")
                val pcm = recorder.recordPhrase(settings.micChoice, settings.micSensitivity)
                routing.disableHeadsetMic() // ответ — снова в нормальном качестве
                if (pcm != null) Earcons.listenEnd() // сигнал "услышал, думаю"
                question = if (pcm == null) null else {
                    updateStatus("Распознаю…")
                    transcribe(pcm).ifBlank { null }
                }
            }
            EventLog.add("Распознано: ${question ?: "(ничего)"}")

            if (question == null) {
                Earcons.fail()
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
            busy = false
            routing.disableHeadsetMic()
            routing.abandonFocus() // музыка продолжается
            claimMediaButtons()
        }
    }

    /** Распознавание записанной фразы выбранным способом (с выравниванием громкости). */
    private suspend fun transcribe(pcm: ByteArray): String {
        if (!settings.sttViaApi && !VoskEngine.isDownloaded(this)) {
            throw IllegalStateException("офлайн-модель не скачана, откройте Геру и нажмите «Скачать»")
        }
        val loud = AudioUtil.normalize(pcm)
        lastRequestPcm = loud
        val text = if (settings.sttViaApi) apiStt.transcribe(loud) else VoskEngine.transcribe(this, loud)
        if (isHallucination(text)) {
            EventLog.add("Отброшено как шум: «$text»")
            return ""
        }
        return text
    }

    /**
     * Whisper на шуме или обрывке записи "слышит" типовые фразы из субтитров и смех.
     * Такие результаты считаем пустыми.
     */
    private fun isHallucination(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[^\\p{L} ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return true
        if (Regex("^(ха|хa|хе|хи|а|м|э)( (ха|хе|хи|а|м|э))*$").matches(t)) return true // "ха ха ха", "м м"
        val junk = listOf(
            "продолжение следует", "субтитры", "спасибо за просмотр", "подписывайтесь на канал",
            "редактор субтитров", "корректор", "до новых встреч", "смех", "музыка", "аплодисменты",
            "короткий вопрос голосовому ассистенту" // эхо нашей подсказки для Whisper
        )
        return junk.any { t.contains(it) } && t.split(' ').size <= 8
    }

    /** "Гера, какая погода" -> "какая погода". null — если кроме обращения ничего не сказали. */
    private fun stripWakeWord(text: String): String? {
        val after = findWakeWord(text)
        if (after != null) return text.substring(after).trim(' ', ',', '.', '!', '?', '-', '—', ':').ifBlank { null }
        // Слово не нашлось (распознаватель мог записать его иначе): одно слово — считаем это обращением
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(text).count()
        return if (tokens <= 1) null else text.trim()
    }

    /** Ищет слово-триггер целыми словами ("два" ≠ "дважды"). Возвращает позицию сразу после него или null. */
    private fun findWakeWord(text: String): Int? {
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(text).toList()
        // ё/е не различаем — Whisper пишет по-разному
        val lower = tokens.map { it.value.lowercase().replace('ё', 'е') }
        for (w in wake.parseWords(settings.wakeWords)) {
            val parts = w.replace('ё', 'е').split(' ')
            for (start in 0..lower.size - parts.size) {
                if (lower.subList(start, start + parts.size) == parts) {
                    return tokens[start + parts.size - 1].range.last + 1
                }
            }
        }
        return null
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
