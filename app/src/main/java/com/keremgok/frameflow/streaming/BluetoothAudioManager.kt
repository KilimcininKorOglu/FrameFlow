package com.keremgok.frameflow.streaming

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * BluetoothAudioManager captures audio from the Meta glasses via Bluetooth SCO.
 *
 * The glasses' 5-microphone array is accessible as a standard Bluetooth audio device.
 * This manager handles SCO connection and audio capture.
 */
class BluetoothAudioManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothAudioManager"

        // Audio recording settings
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Buffer size for audio capture
        val BUFFER_SIZE: Int by lazy {
            val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            maxOf(minSize * 2, 4096)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var scoReceiver: BroadcastReceiver? = null

    // Audio capture state
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Bluetooth SCO state
    private val _isScoConnected = MutableStateFlow(false)
    val isScoConnected: StateFlow<Boolean> = _isScoConnected.asStateFlow()

    // Status text
    private val _status = MutableStateFlow("Audio: Not Started")
    val status: StateFlow<String> = _status.asStateFlow()

    // Callback for audio data
    var onAudioData: ((ByteArray, Int) -> Unit)? = null

    /**
     * Check if Bluetooth audio device (glasses) is connected.
     */
    fun isBluetoothAudioConnected(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoAvailableOffCall
        }
    }

    /**
     * Start Bluetooth SCO connection for glasses microphone.
     */
    fun startBluetoothSco() {
        if (!isBluetoothAudioConnected()) {
            _status.value = "Audio: No Bluetooth device"
            Log.w(TAG, "No Bluetooth audio device connected")
            return
        }

        // Register SCO state receiver
        registerScoReceiver()

        // Start SCO
        try {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            _status.value = "Audio: Connecting SCO..."
            Log.d(TAG, "Starting Bluetooth SCO")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Bluetooth SCO", e)
            _status.value = "Audio: SCO failed"
        }
    }

    /**
     * Stop Bluetooth SCO connection.
     */
    fun stopBluetoothSco() {
        try {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
            _isScoConnected.value = false
            _status.value = "Audio: SCO stopped"
            Log.d(TAG, "Stopped Bluetooth SCO")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Bluetooth SCO", e)
        }

        unregisterScoReceiver()
    }

    /**
     * Register receiver for SCO connection state changes.
     */
    private fun registerScoReceiver() {
        if (scoReceiver != null) return

        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR
                )

                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        _isScoConnected.value = true
                        _status.value = "Audio: Glasses mic connected"
                        Log.d(TAG, "SCO connected - glasses microphone ready")
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        _isScoConnected.value = false
                        _status.value = "Audio: Disconnected"
                        Log.d(TAG, "SCO disconnected")
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        _isScoConnected.value = false
                        _status.value = "Audio: SCO error"
                        Log.e(TAG, "SCO error")
                    }
                }
            }
        }

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(scoReceiver, filter)
        }
    }

    /**
     * Unregister SCO state receiver.
     */
    private fun unregisterScoReceiver() {
        scoReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Already unregistered
            }
            scoReceiver = null
        }
    }

    /**
     * Start audio recording from Bluetooth device.
     * Must have RECORD_AUDIO permission.
     */
    fun startRecording(): Boolean {
        if (_isRecording.value) {
            Log.w(TAG, "Already recording")
            return true
        }

        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            _status.value = "Audio: Permission denied"
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return false
        }

        try {
            // Create AudioRecord - use VOICE_COMMUNICATION for Bluetooth SCO
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                _status.value = "Audio: Init failed"
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            _isRecording.value = true
            _status.value = "Audio: Recording"
            Log.d(TAG, "Started audio recording: ${SAMPLE_RATE}Hz, buffer=$BUFFER_SIZE")

            // Start capture loop
            startCaptureLoop()

            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting recording", e)
            _status.value = "Audio: Permission error"
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            _status.value = "Audio: Error"
            return false
        }
    }

    /**
     * Start the audio capture loop in a coroutine.
     */
    private fun startCaptureLoop() {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            val buffer = ByteArray(BUFFER_SIZE)

            while (isActive && _isRecording.value) {
                try {
                    val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1

                    if (bytesRead > 0) {
                        // Send audio data to callback
                        onAudioData?.invoke(buffer.copyOf(bytesRead), bytesRead)
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read error: $bytesRead")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading audio", e)
                }
            }
        }
    }

    /**
     * Stop audio recording.
     */
    fun stopRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            _status.value = "Audio: Stopped"
            Log.d(TAG, "Stopped audio recording")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    /**
     * Start full audio capture (SCO + recording).
     */
    fun start() {
        startBluetoothSco()
        // Recording will be started when SCO connects (or immediately if already connected)
        if (_isScoConnected.value || !isBluetoothAudioConnected()) {
            // If no Bluetooth, fall back to default mic
            startRecording()
        }
    }

    /**
     * Stop full audio capture.
     */
    fun stop() {
        stopRecording()
        stopBluetoothSco()
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        scope.cancel()
        Log.d(TAG, "BluetoothAudioManager released")
    }
}
