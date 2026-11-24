package ru.gera.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Невидимый экран, который Android открывает, когда вызывают голосового помощника:
 * долгое нажатие/касание на гарнитуре, кнопка помощника, жест.
 * Если Гера выбрана помощником по умолчанию — сразу начинаем слушать вопрос.
 */
class VoiceCommandActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLog.add("Вызов голосового помощника (${intent?.action ?: "?"})")

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Разрешения ещё не выданы — открываем главный экран
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            val ask = Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_ASK)
            if (AssistantService.isRunning) startService(ask) else startForegroundService(ask)
        }
        finish()
    }
}
