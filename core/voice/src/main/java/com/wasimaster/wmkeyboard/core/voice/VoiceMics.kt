package com.wasimaster.wmkeyboard.core.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The microphones dictation can listen through (#581), by kind rather than by
 * device: "Bluetooth" is the pick a user means when they say "always use my
 * headset", whichever headset that turns out to be today. The kinds are the
 * strings stored in `VoiceBarSettings.mic`.
 *
 * Two engines, two reaches. Whisper and the transcription server record with
 * the keyboard's own `AudioRecord`, which takes a preferred device, so every
 * kind works for them. For Bluetooth the headset is also made the
 * communication device: FUTO's keyboard moves its own VOICE_RECOGNITION
 * capture to a headset exactly that way (AudioRecognizer.setCommunicationDevice,
 * no preferred device, no extra permission), which is the evidence it works.
 * The system recognizer records in its own process and cannot be handed a
 * device; the communication device is the one lever that might reach it, so
 * for that engine only Bluetooth is offered ([choices]). That reach is not
 * device-verified: FUTO has no system-recognizer engine to show it.
 */
object VoiceMics {
    const val AUTO = "auto"
    const val PHONE = "phone"
    const val WIRED = "wired"
    const val USB = "usb"
    const val BLUETOOTH = "bluetooth"

    /** Display order, and the order the panel's chip cycles in. */
    private val ORDER = listOf(PHONE, WIRED, USB, BLUETOOTH)

    // Literal values: TYPE_USB_HEADSET is API 26 and TYPE_BLE_HEADSET API 31,
    // and a device of a type the platform does not have simply never shows up.
    private const val TYPE_USB_HEADSET = 22
    private const val TYPE_BLE_HEADSET = 26

    /** The kind an input device counts as, or null for one dictation has no use for (telephony, FM, echo reference…). */
    fun kindOf(device: AudioDeviceInfo): String? = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> PHONE
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, TYPE_USB_HEADSET -> USB
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET -> BLUETOOTH
        else -> null
    }

    private fun inputs(context: Context): List<AudioDeviceInfo> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        return runCatching { audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }.getOrDefault(emptyList())
    }

    /**
     * The kinds connected now, in [ORDER]. A phone exposes several built-in
     * microphones (bottom, back, top); they are one "phone" here, and Android
     * picks among them as it always does.
     */
    fun available(context: Context): List<String> {
        val kinds = inputs(context).mapNotNullTo(HashSet()) { kindOf(it) }
        return ORDER.filter { it in kinds }
    }

    /**
     * What the panel's chip cycles through: automatic, then each connected
     * kind the engine can actually be pointed at. [ownCapture] is true for
     * Whisper and the server, false for the system recognizer.
     */
    fun choices(available: List<String>, ownCapture: Boolean): List<String> =
        listOf(AUTO) + available.filter { ownCapture || it == BLUETOOTH }

    /** The next pick after [current] in [choices]; a pick that is gone starts the cycle over. */
    fun next(current: String, choices: List<String>): String {
        if (choices.isEmpty()) return AUTO
        val at = choices.indexOf(current)
        return choices[(at + 1) % choices.size]
    }

    /** The connected device for [kind], or null for [AUTO] and for a kind with nothing plugged in. */
    fun deviceFor(context: Context, kind: String): AudioDeviceInfo? {
        if (kind == AUTO) return null
        val matches = inputs(context).filter { kindOf(it) == kind }
        // A Bluetooth headset can be there twice, as a classic SCO link and as
        // LE Audio; the classic one is what every headset speaks.
        return matches.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: matches.firstOrNull()
    }

    /**
     * The microphone Android records from when nobody asks for one: a wired
     * or USB headset before the phone's own, never Bluetooth. A guess for the
     * system recognizer, which records out of sight; the keyboard's own
     * capture asks its recorder instead.
     */
    fun autoDevice(context: Context): AudioDeviceInfo? {
        val all = inputs(context)
        return listOf(WIRED, USB, PHONE).firstNotNullOfOrNull { kind -> all.firstOrNull { kindOf(it) == kind } }
    }

    /**
     * Calls [onChange] on the main thread whenever a microphone is plugged in
     * or taken away, and once straight after [start] with what is there now.
     */
    class Watcher(context: Context, private val onChange: (List<String>) -> Unit) {
        private val app = context.applicationContext
        private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        private var registered = false

        private val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChange(available(app))
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChange(available(app))
        }

        fun start() {
            val audio = audio ?: return
            if (registered) return
            // Registering reports every device already connected as added,
            // which is the initial answer.
            runCatching { audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
                .onSuccess { registered = true }
        }

        fun stop() {
            if (!registered) return
            runCatching { audio?.unregisterAudioDeviceCallback(callback) }
            registered = false
        }
    }

    /**
     * Holds the audio route a Bluetooth microphone needs. A headset's
     * microphone is only open while its voice link (SCO) is up, and that link
     * is what a call uses: held a moment too long, music on the headset drops
     * to call quality. So it is taken for a dictation and given back after it,
     * and [release] must run wherever the microphone is let go.
     */
    class Route(context: Context) {
        private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        private var held = false

        /** Routes to a Bluetooth headset for [kind] == [BLUETOOTH]; any other kind lets go of it. */
        fun hold(kind: String) {
            if (kind != BLUETOOTH) {
                release()
                return
            }
            val audio = audio ?: return
            if (held) return
            held = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val headset = audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == TYPE_BLE_HEADSET
                }
                headset != null && runCatching { audio.setCommunicationDevice(headset) }.getOrDefault(false)
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                }.isSuccess
            }
        }

        /** Gives the headset's voice link back. Idempotent. */
        fun release() {
            val audio = audio ?: return
            if (!held) return
            held = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { audio.clearCommunicationDevice() }
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    audio.isBluetoothScoOn = false
                    audio.stopBluetoothSco()
                }
            }
        }
    }
}
