package ru.gera.assistant

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.os.Build
import kotlinx.coroutines.delay

/**
 * Управление звуком:
 *  - аудиофокус (на время вопроса музыка ставится на паузу/приглушается);
 *  - включение микрофона Bluetooth-гарнитуры (режим "звонка", экспериментально).
 */
class AudioRouting(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener { }
        .build()

    private var headsetMicOn = false

    fun requestFocus() {
        am.requestAudioFocus(focusRequest)
    }

    fun abandonFocus() {
        am.abandonAudioFocusRequest(focusRequest)
    }

    /** Переключает ввод на микрофон гарнитуры. Возвращает false, если гарнитура не найдена. */
    suspend fun enableHeadsetMic(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: return false
            if (!am.setCommunicationDevice(device)) return false
            // Ждём, пока Bluetooth переключится в режим гарнитуры (обычно < 1 с)
            repeat(20) {
                if (am.communicationDevice?.id == device.id) {
                    headsetMicOn = true
                    delay(300)
                    return true
                }
                delay(100)
            }
            am.clearCommunicationDevice()
            return false
        } else {
            @Suppress("DEPRECATION")
            run {
                if (!am.isBluetoothScoAvailableOffCall) return false
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }
            headsetMicOn = true
            delay(1000)
            return true
        }
    }

    fun disableHeadsetMic() {
        if (!headsetMicOn) return
        headsetMicOn = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            run {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
                am.mode = AudioManager.MODE_NORMAL
            }
        }
    }
}
