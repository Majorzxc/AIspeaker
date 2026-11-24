package ru.gera.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent

/**
 * Запасной путь для кнопки наушников. Когда Android перестаёт считать Геру
 * "текущим плеером" (после простоя), кнопка приходит сюда, а не в MediaSession.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event: KeyEvent = (if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        }) ?: return

        EventLog.add("Кнопка гарнитуры (системный приёмник): код ${event.keyCode}, действие ${event.action}")
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        if (event.keyCode !in AssistantService.HEADSET_KEYS) return

        if (AssistantService.isRunning) {
            context.startService(Intent(context, AssistantService::class.java).setAction(AssistantService.ACTION_ASK))
        } else {
            EventLog.add("Ассистент выключен — включите его в приложении")
        }
    }
}
