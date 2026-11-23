package ru.gera.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Экран настроек и главный тумблер. */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private val scope = MainScope()
    private var testSpeaker: Speaker? = null

    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var editBaseUrl: EditText
    private lateinit var editApiKey: EditText
    private lateinit var editModel: EditText
    private lateinit var editPrompt: EditText
    private lateinit var editMaxTokens: EditText
    private lateinit var editCity: EditText
    private lateinit var seekRate: SeekBar
    private lateinit var textRate: TextView
    private lateinit var switchTools: MaterialSwitch
    private lateinit var switchHeadsetMic: MaterialSwitch
    private lateinit var textLog: TextView
    private lateinit var switchSttApi: MaterialSwitch
    private lateinit var editSttModel: EditText
    private lateinit var textModelStatus: TextView
    private lateinit var btnDownloadModel: Button
    private var downloading = false
    private lateinit var switchWake: MaterialSwitch
    private lateinit var editWakeWords: EditText
    private lateinit var seekSensitivity: SeekBar
    private lateinit var textSensitivity: TextView

    // Обновление журнала на экране раз в 0,7 с, пока экран открыт
    private val logHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val logUpdater = object : Runnable {
        override fun run() {
            textLog.text = EventLog.text().ifEmpty { "(пока пусто)" }
            logHandler.postDelayed(this, 700)
        }
    }

    // Чтобы программная смена тумблера не запускала обработчик
    private var ignoreSwitch = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true || hasMicPermission()) {
                startAssistant()
            } else {
                Toast.makeText(this, "Без доступа к микрофону ассистент не работает", Toast.LENGTH_LONG).show()
                setSwitch(false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        switchEnabled = findViewById(R.id.switchEnabled)
        editBaseUrl = findViewById(R.id.editBaseUrl)
        editApiKey = findViewById(R.id.editApiKey)
        editModel = findViewById(R.id.editModel)
        editPrompt = findViewById(R.id.editPrompt)
        editMaxTokens = findViewById(R.id.editMaxTokens)
        editCity = findViewById(R.id.editCity)
        seekRate = findViewById(R.id.seekRate)
        textRate = findViewById(R.id.textRate)
        switchTools = findViewById(R.id.switchTools)
        switchHeadsetMic = findViewById(R.id.switchHeadsetMic)
        textLog = findViewById(R.id.textLog)
        switchSttApi = findViewById(R.id.switchSttApi)
        editSttModel = findViewById(R.id.editSttModel)
        textModelStatus = findViewById(R.id.textModelStatus)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        btnDownloadModel.setOnClickListener { downloadModel() }
        switchWake = findViewById(R.id.switchWake)
        editWakeWords = findViewById(R.id.editWakeWords)
        seekSensitivity = findViewById(R.id.seekSensitivity)
        textSensitivity = findViewById(R.id.textSensitivity)
        seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                textSensitivity.text = "Чувствительность микрофона: $progress"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                settings.micSensitivity = seekSensitivity.progress // применяется сразу, без перезапуска
            }
        })
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { EventLog.clear() }

        loadFields()

        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            if (checked) {
                saveFields()
                requestPermissionsAndStart()
            } else {
                AssistantService.stop(this)
            }
        }

        seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = showRate()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveFields()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnResetPrompt).setOnClickListener {
            editPrompt.setText(Settings.DEFAULT_PROMPT)
        }

        findViewById<Button>(R.id.btnTestVoice).setOnClickListener {
            val speaker = testSpeaker ?: Speaker(this).also { testSpeaker = it }
            scope.launch {
                runCatching {
                    speaker.speak("Привет, я Гера. Так звучит мой голос на этой скорости.", currentRate())
                }.onFailure {
                    Toast.makeText(this@MainActivity, "Ошибка синтеза: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btnTtsSettings).setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                Toast.makeText(this, "Откройте вручную: Настройки → Спец. возможности → Синтез речи", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnAskNow).setOnClickListener {
            if (!AssistantService.isRunning) {
                Toast.makeText(this, "Сначала включите ассистента", Toast.LENGTH_SHORT).show()
            } else {
                saveFields()
                startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_ASK))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setSwitch(AssistantService.isRunning)
        logHandler.post(logUpdater)
    }

    override fun onPause() {
        super.onPause()
        logHandler.removeCallbacks(logUpdater)
        saveFields()
    }

    override fun onDestroy() {
        scope.cancel()
        testSpeaker?.shutdown()
        super.onDestroy()
    }

    // ---------- Запуск ----------

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (!hasMicPermission()) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) startAssistant() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startAssistant() {
        if (settings.baseUrl.isBlank() || settings.model.isBlank()) {
            Toast.makeText(this, "Заполните адрес API и модель", Toast.LENGTH_LONG).show()
            setSwitch(false)
            return
        }
        AssistantService.start(this)
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun setSwitch(on: Boolean) {
        ignoreSwitch = true
        switchEnabled.isChecked = on
        ignoreSwitch = false
    }

    // ---------- Поля ----------

    private fun loadFields() {
        editBaseUrl.setText(settings.baseUrl)
        editApiKey.setText(settings.apiKey)
        editModel.setText(settings.model)
        editPrompt.setText(settings.systemPrompt)
        editMaxTokens.setText(settings.maxTokens.toString())
        editCity.setText(settings.city)
        switchTools.isChecked = settings.toolsEnabled
        switchHeadsetMic.isChecked = settings.useHeadsetMic
        switchSttApi.isChecked = settings.sttViaApi
        editSttModel.setText(settings.sttModel)
        switchWake.isChecked = settings.wakeWordEnabled
        editWakeWords.setText(settings.wakeWords)
        seekSensitivity.progress = settings.micSensitivity
        textSensitivity.text = "Чувствительность микрофона: ${settings.micSensitivity}"
        showModelStatus()
        // Шкала 0..30 соответствует скорости 0.5..2.0 с шагом 0.05
        seekRate.progress = ((settings.speechRate - 0.5f) / 0.05f).toInt().coerceIn(0, 30)
        showRate()
    }

    private fun saveFields() {
        settings.baseUrl = editBaseUrl.text.toString()
        settings.apiKey = editApiKey.text.toString()
        settings.model = editModel.text.toString()
        settings.systemPrompt = editPrompt.text.toString().ifBlank { Settings.DEFAULT_PROMPT }
        settings.maxTokens = editMaxTokens.text.toString().toIntOrNull()?.coerceIn(16, 4000) ?: 200
        settings.city = editCity.text.toString()
        settings.speechRate = currentRate()
        settings.toolsEnabled = switchTools.isChecked
        settings.useHeadsetMic = switchHeadsetMic.isChecked
        settings.sttViaApi = switchSttApi.isChecked
        settings.sttModel = editSttModel.text.toString()
        settings.wakeWordEnabled = switchWake.isChecked
        settings.wakeWords = editWakeWords.text.toString()
        settings.micSensitivity = seekSensitivity.progress
    }

    // ---------- Офлайн-модель ----------

    private fun showModelStatus() {
        if (downloading) return
        val ok = VoskEngine.isDownloaded(this)
        textModelStatus.text = if (ok) "Офлайн-модель: скачана ✓" else "Офлайн-модель: не скачана"
        btnDownloadModel.text = if (ok) "Скачать модель заново" else "Скачать офлайн-модель (~45 МБ)"
    }

    private fun downloadModel() {
        if (downloading) return
        downloading = true
        btnDownloadModel.isEnabled = false
        EventLog.add("Скачиваю офлайн-модель…")
        scope.launch {
            try {
                VoskEngine.download(this@MainActivity) { pct ->
                    runOnUiThread { textModelStatus.text = "Скачивание: $pct %" }
                }
                EventLog.add("Офлайн-модель скачана")
            } catch (e: Exception) {
                EventLog.add("Ошибка скачивания модели: ${e.message}")
                Toast.makeText(this@MainActivity, "Не удалось скачать: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                downloading = false
                btnDownloadModel.isEnabled = true
                showModelStatus()
            }
        }
    }

    private fun currentRate(): Float = 0.5f + seekRate.progress * 0.05f

    private fun showRate() {
        textRate.text = String.format(Locale.US, "Скорость речи: %.2f", currentRate())
    }
}
