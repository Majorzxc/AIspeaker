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
    }

    override fun onPause() {
        super.onPause()
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
    }

    private fun currentRate(): Float = 0.5f + seekRate.progress * 0.05f

    private fun showRate() {
        textRate.text = String.format(Locale.US, "Скорость речи: %.2f", currentRate())
    }
}
