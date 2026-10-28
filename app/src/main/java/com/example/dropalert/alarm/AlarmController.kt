package com.example.dropalert.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Phase 5: Native Android Alarm & Vibration Engine.
 * - Plays looping alarm audio on USAGE_ALARM (STREAM_ALARM) so it sounds even when phone is on Silent/Vibrate.
 * - Activates a repeating vibration pattern alongside the audio.
 * - Acquires a partial WakeLock while ringing so CPU/audio never suspends while locked.
 * - Continues until manually dismissed via stopAlarm().
 */
class AlarmController(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isRinging: Boolean = false

    @Synchronized
    fun startAlarm() {
        if (isRinging) return
        isRinging = true

        acquireAlarmWakeLock()
        requestAlarmAudioFocus()
        startLoopingAlarmSound()
        startRepeatingVibration()
    }

    @Synchronized
    fun stopAlarm() {
        isRinging = false

        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }

        try {
            getVibrator().cancel()
        } catch (_: Exception) {
        }

        abandonAlarmAudioFocus()
        releaseAlarmWakeLock()
    }

    fun isAlarmStreamMuted(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        return currentVolume == 0
    }

    private fun startLoopingAlarmSound() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(audioAttributes)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            // Fallback to default ringtone if MediaPlayer data source fails on custom ROMs
            try {
                val fallbackRingtone = RingtoneManager.getRingtone(context, alarmUri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    fallbackRingtone.isLooping = true
                }
                fallbackRingtone.audioAttributes = audioAttributes
                fallbackRingtone.play()
            } catch (_: Exception) {
            }
        }
    }

    private fun startRepeatingVibration() {
        val vibrator = getVibrator()
        if (!vibrator.hasVibrator()) return

        val pattern = longArrayOf(0L, 800L, 400L, 800L, 600L)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, 0) // 0 = repeat indefinitely
            vibrator.vibrate(effect, audioAttributes)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }

    private fun requestAlarmAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()

        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun abandonAlarmAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        audioFocusRequest = null
    }

    private fun acquireAlarmWakeLock() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DropAlert::AlarmWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.acquire(15 * 60 * 1000L) // Hold up to 15 minutes unless dismissed sooner
    }

    private fun releaseAlarmWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
