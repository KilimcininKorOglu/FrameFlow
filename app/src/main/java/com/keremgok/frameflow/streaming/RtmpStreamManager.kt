package com.keremgok.frameflow.streaming

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.keremgok.frameflow.data.StreamConfig
import com.keremgok.frameflow.data.StreamingPlatform
import com.keremgok.frameflow.util.NetworkMonitor
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.AudioCodec
import com.pedro.rtmp.rtmp.RtmpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * RtmpStreamManager handles RTMP connections to various streaming platforms.
 * Supports Twitch, YouTube, Kick, Facebook, TikTok, and custom RTMP servers.
 * 
 * Uses RootEncoder library for RTMP streaming with H.264 video encoding.
 */
class RtmpStreamManager(private val context: Context) : ConnectChecker {
    
    companion object {
        private const val TAG = "RtmpStreamManager"
        
        // Video encoding settings
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 2_500_000  // 2.5 Mbps
        private const val VIDEO_FPS = 24
        private const val I_FRAME_INTERVAL = 2  // Keyframe every 2 seconds
        
        // Audio encoding settings
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000
        private const val AUDIO_CHANNEL_COUNT = 1  // Mono
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // RTMP client from RootEncoder
    private var rtmpClient: RtmpClient? = null
    
    // Video encoder
    private var videoEncoder: MediaCodec? = null
    private var isEncoderConfigured = false

    // BUG-010 fix: Track encoder dimensions for dynamic resolution
    private var encoderWidth = 0
    private var encoderHeight = 0

    // Audio encoder
    private var audioEncoder: MediaCodec? = null
    private var isAudioEncoderConfigured = false
    private var audioEncoderOutputJob: Job? = null

    // Audio state
    private val _isAudioEnabled = MutableStateFlow(false)
    val isAudioEnabled: StateFlow<Boolean> = _isAudioEnabled.asStateFlow()

    // Bluetooth audio manager for glasses microphone
    private var bluetoothAudioManager: BluetoothAudioManager? = null

    // Local recording manager
    private var localRecordingManager: LocalRecordingManager? = null

    // Recording state
    val isRecording: StateFlow<Boolean>
        get() = localRecordingManager?.isRecording ?: MutableStateFlow(false)

    val recordingDuration: StateFlow<Long>
        get() = localRecordingManager?.recordingDuration ?: MutableStateFlow(0L)

    val isRecordOnlyMode: StateFlow<Boolean>
        get() = localRecordingManager?.isRecordOnlyMode ?: MutableStateFlow(false)

    // Current streaming configuration
    private var currentConfig: StreamConfig? = null

    // BUG-003 fix: Flag to track pending encoder initialization
    private var pendingEncoderInit = false
    
    // Broadcasting state
    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()
    
    // Connection status text
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
    
    // Current platform
    private val _currentPlatform = MutableStateFlow<StreamingPlatform?>(null)
    val currentPlatform: StateFlow<StreamingPlatform?> = _currentPlatform.asStateFlow()
    
    // Frame counter for logging
    private var frameCount = 0L
    private var lastLogTime = 0L
    
    // Encoder output processing job
    private var encoderOutputJob: Job? = null
    
    // Network monitoring
    private val networkMonitor = NetworkMonitor(context)
    private var networkMonitorJob: Job? = null
    
    // Network state
    private val _isNetworkAvailable = MutableStateFlow(true)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    // BUG-007 fix: Reconnection state
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    private var wasConnectedBeforeNetworkLoss = false
    
    init {
        // Initialize RTMP client with connection checker
        rtmpClient = RtmpClient(this)

        // Initialize local recording manager
        localRecordingManager = LocalRecordingManager(context)

        // Start network monitoring
        startNetworkMonitoring()
    }
    
    /**
     * Initialize video encoder for H.264 encoding.
     * BUG-010 fix: Reinitialize if dimensions change
     */
    private fun initializeEncoder(width: Int, height: Int): Boolean {
        try {
            // BUG-010 fix: Check if encoder needs reinitialization for new dimensions
            if (isEncoderConfigured && encoderWidth == width && encoderHeight == height) {
                return true
            }

            // Release existing encoder if dimensions changed
            if (isEncoderConfigured) {
                Log.d(TAG, "Reinitializing encoder for new dimensions: ${encoderWidth}x${encoderHeight} -> ${width}x${height}")
                releaseEncoder()
            }
            
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            // BUG-010 fix: Store configured dimensions
            encoderWidth = width
            encoderHeight = height
            isEncoderConfigured = true
            Log.d(TAG, "Video encoder initialized: ${width}x${height} @ ${VIDEO_BITRATE/1000}kbps")
            
            // Start encoder output processing
            startEncoderOutputProcessing()
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video encoder", e)
            return false
        }
    }
    
    /**
     * Initialize AAC audio encoder.
     */
    private fun initializeAudioEncoder(): Boolean {
        try {
            if (isAudioEncoderConfigured) {
                return true
            }

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            isAudioEncoderConfigured = true
            Log.d(TAG, "Audio encoder initialized: ${AUDIO_SAMPLE_RATE}Hz, ${AUDIO_BITRATE/1000}kbps")

            // Start audio encoder output processing
            startAudioEncoderOutputProcessing()

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio encoder", e)
            return false
        }
    }

    /**
     * Start processing audio encoder output in background.
     */
    private fun startAudioEncoderOutputProcessing() {
        audioEncoderOutputJob?.cancel()
        audioEncoderOutputJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()

            while (_isBroadcasting.value && isAudioEncoderConfigured) {
                try {
                    audioEncoder?.let { encoder ->
                        val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)

                        when {
                            outputIndex >= 0 -> {
                                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                outputBuffer?.let { buffer ->
                                    if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                        // Send encoded audio data via RTMP
                                        val data = ByteArray(bufferInfo.size)
                                        buffer.get(data)
                                        val dataBuffer = ByteBuffer.wrap(data)

                                        rtmpClient?.sendAudio(dataBuffer, bufferInfo)

                                        // Also write to local recording (Mode B)
                                        if (localRecordingManager?.isRecording?.value == true &&
                                            localRecordingManager?.isRecordOnlyMode?.value == false) {
                                            dataBuffer.rewind()
                                            localRecordingManager?.writeAudioData(dataBuffer, bufferInfo)
                                        }
                                    }
                                }
                                encoder.releaseOutputBuffer(outputIndex, false)
                            }
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val newFormat = encoder.outputFormat
                                Log.d(TAG, "Audio encoder output format changed: $newFormat")

                                // Set audio info for RTMP (AAC specific header)
                                newFormat.getByteBuffer("csd-0")?.let { aacHeader ->
                                    rtmpClient?.setAudioInfo(AUDIO_SAMPLE_RATE, true)
                                }

                                // Set audio format for local recording
                                localRecordingManager?.setAudioFormat(newFormat)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing audio encoder output", e)
                }
            }
        }
    }

    /**
     * Release audio encoder resources.
     */
    private fun releaseAudioEncoder() {
        try {
            audioEncoderOutputJob?.cancel()
            audioEncoderOutputJob = null

            audioEncoder?.stop()
            audioEncoder?.release()
            audioEncoder = null
            isAudioEncoderConfigured = false

            Log.d(TAG, "Audio encoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio encoder", e)
        }
    }

    /**
     * Process raw PCM audio data from microphone.
     * Encodes to AAC and sends via RTMP.
     *
     * @param pcmData Raw PCM audio data (16-bit, mono, 44.1kHz)
     * @param size Size of valid data in buffer
     */
    fun processAudioData(pcmData: ByteArray, size: Int) {
        if (!_isBroadcasting.value || !isAudioEncoderConfigured || !_isAudioEnabled.value) return

        try {
            audioEncoder?.let { encoder ->
                val inputIndex = encoder.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                    inputBuffer?.let { input ->
                        input.clear()
                        val dataSize = minOf(size, input.remaining())
                        input.put(pcmData, 0, dataSize)

                        val presentationTimeUs = System.nanoTime() / 1000
                        encoder.queueInputBuffer(inputIndex, 0, dataSize, presentationTimeUs, 0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio data", e)
        }
    }

    /**
     * Enable/disable audio streaming.
     */
    fun setAudioEnabled(enabled: Boolean) {
        _isAudioEnabled.value = enabled

        if (enabled && _isBroadcasting.value) {
            // Initialize audio encoder if not already
            if (!isAudioEncoderConfigured) {
                initializeAudioEncoder()
            }
            // Start Bluetooth audio capture
            startAudioCapture()
        } else {
            stopAudioCapture()
        }
    }

    /**
     * Start capturing audio from glasses microphone via Bluetooth.
     */
    private fun startAudioCapture() {
        if (bluetoothAudioManager == null) {
            bluetoothAudioManager = BluetoothAudioManager(context)
        }

        bluetoothAudioManager?.onAudioData = { data, size ->
            processAudioData(data, size)
        }

        bluetoothAudioManager?.start()
        Log.d(TAG, "Audio capture started")
    }

    /**
     * Stop audio capture.
     */
    private fun stopAudioCapture() {
        bluetoothAudioManager?.stop()
        bluetoothAudioManager?.onAudioData = null
        Log.d(TAG, "Audio capture stopped")
    }

    /**
     * Start processing encoder output in background.
     */
    private fun startEncoderOutputProcessing() {
        encoderOutputJob?.cancel()
        encoderOutputJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            
            while (_isBroadcasting.value) {
                try {
                    videoEncoder?.let { encoder ->
                        val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000) // 10ms timeout
                        
                        when {
                            outputIndex >= 0 -> {
                                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                outputBuffer?.let { buffer ->
                                    if (bufferInfo.size > 0) {
                                        // Send encoded data via RTMP
                                        val data = ByteArray(bufferInfo.size)
                                        buffer.get(data)
                                        val dataBuffer = ByteBuffer.wrap(data)

                                        rtmpClient?.sendVideo(dataBuffer, bufferInfo)

                                        // Also write to local recording (Mode B)
                                        if (localRecordingManager?.isRecording?.value == true &&
                                            localRecordingManager?.isRecordOnlyMode?.value == false) {
                                            dataBuffer.rewind()
                                            localRecordingManager?.writeVideoData(dataBuffer, bufferInfo)
                                        }
                                    }
                                }
                                encoder.releaseOutputBuffer(outputIndex, false)
                            }
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val newFormat = encoder.outputFormat
                                Log.d(TAG, "Encoder output format changed: $newFormat")

                                // Extract SPS/PPS for RTMP
                                val sps = newFormat.getByteBuffer("csd-0")
                                val pps = newFormat.getByteBuffer("csd-1")

                                if (sps != null && pps != null) {
                                    rtmpClient?.setVideoInfo(sps, pps, null)
                                }

                                // Set video format for local recording
                                localRecordingManager?.setVideoFormat(newFormat)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing encoder output", e)
                }
            }
        }
    }
    
    /**
     * Start monitoring network connectivity.
     * BUG-007 fix: Auto-reconnect when network recovers
     */
    private fun startNetworkMonitoring() {
        networkMonitorJob?.cancel()
        networkMonitorJob = scope.launch {
            networkMonitor.isConnected.collect { isConnected ->
                val wasAvailable = _isNetworkAvailable.value
                _isNetworkAvailable.value = isConnected

                if (!isConnected && _isBroadcasting.value) {
                    // Network lost during broadcast
                    Log.w(TAG, "Network lost during broadcast!")
                    _connectionStatus.value = "Network Lost - Will Reconnect"
                    wasConnectedBeforeNetworkLoss = true
                } else if (isConnected && !wasAvailable && wasConnectedBeforeNetworkLoss) {
                    // Network recovered, attempt reconnection
                    Log.d(TAG, "Network recovered, attempting reconnection")
                    attemptReconnection()
                }
            }
        }
    }

    /**
     * BUG-007 fix: Attempt reconnection with exponential backoff
     */
    private fun attemptReconnection() {
        val config = currentConfig ?: return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempts = 0
            wasConnectedBeforeNetworkLoss = false

            while (reconnectAttempts < maxReconnectAttempts) {
                reconnectAttempts++
                val delayMs = 1000L * (1 shl (reconnectAttempts - 1)) // 1s, 2s, 4s

                Log.d(TAG, "Reconnection attempt $reconnectAttempts/$maxReconnectAttempts in ${delayMs}ms")
                _connectionStatus.value = "Reconnecting ($reconnectAttempts/$maxReconnectAttempts)..."

                delay(delayMs)

                if (!_isNetworkAvailable.value) {
                    Log.d(TAG, "Network still unavailable, stopping reconnection")
                    _connectionStatus.value = "Network Lost"
                    return@launch
                }

                try {
                    val fullUrl = config.getFullRtmpUrl()
                    rtmpClient?.let { client ->
                        client.setVideoCodec(VideoCodec.H264)
                        client.setAudioCodec(AudioCodec.AAC)
                        pendingEncoderInit = true
                        client.connect(fullUrl)
                        Log.d(TAG, "Reconnection attempt $reconnectAttempts initiated")
                        return@launch  // Let callback handle success/failure
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnection attempt $reconnectAttempts failed", e)
                }
            }

            // Max attempts reached
            Log.e(TAG, "Max reconnection attempts reached")
            _connectionStatus.value = "Reconnection Failed"
            _isBroadcasting.value = false
            releaseEncoder()
        }
    }
    
    /**
     * Release video encoder resources.
     */
    private fun releaseEncoder() {
        try {
            encoderOutputJob?.cancel()
            encoderOutputJob = null
            
            videoEncoder?.stop()
            videoEncoder?.release()
            videoEncoder = null
            isEncoderConfigured = false

            // BUG-010 fix: Reset encoder dimensions
            encoderWidth = 0
            encoderHeight = 0

            Log.d(TAG, "Video encoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
    }
    
    /**
     * Start broadcasting to the configured platform.
     * @param config Complete streaming configuration
     */
    fun startBroadcast(config: StreamConfig) {
        if (!config.isValid()) {
            _connectionStatus.value = config.getValidationError() ?: "Invalid configuration"
            Log.e(TAG, "Invalid stream config: ${config.getValidationError()}")
            return
        }
        
        // Check network availability before attempting connection
        if (!networkMonitor.isCurrentlyConnected()) {
            _connectionStatus.value = "No network connection"
            Log.e(TAG, "Cannot start broadcast: no network")
            return
        }
        
        currentConfig = config
        _currentPlatform.value = config.platform
        
        scope.launch {
            try {
                _connectionStatus.value = "Connecting to ${config.platform.displayName}..."
                frameCount = 0
                lastLogTime = System.currentTimeMillis()
                
                val fullUrl = config.getFullRtmpUrl()
                Log.d(TAG, "Connecting to ${config.platform.displayName}")
                
                rtmpClient?.let { client ->
                    // Set video/audio codec info before connecting
                    client.setVideoCodec(VideoCodec.H264)
                    client.setAudioCodec(AudioCodec.AAC)

                    // BUG-003 fix: Mark that we need to init encoder on connection success
                    // Don't init encoder here - connection is async and may fail
                    pendingEncoderInit = true

                    // connect() returns Unit, connection result comes via callbacks
                    client.connect(fullUrl)
                    Log.d(TAG, "Connection initiated to ${config.platform.displayName}")
                } ?: run {
                    _connectionStatus.value = "RTMP client not initialized"
                    _isBroadcasting.value = false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting broadcast", e)
                _connectionStatus.value = "Error: ${e.message}"
                _isBroadcasting.value = false
            }
        }
    }
    
    /**
     * Start broadcasting with just a stream key (uses saved platform or defaults to Twitch).
     * Backward compatible method.
     */
    fun startBroadcast(streamKey: String, platform: StreamingPlatform = StreamingPlatform.TWITCH) {
        val config = StreamConfig(platform, streamKey)
        startBroadcast(config)
    }
    
    /**
     * Stop broadcasting.
     */
    fun stopBroadcast() {
        scope.launch {
            try {
                val platform = currentConfig?.platform?.displayName ?: "stream"
                Log.d(TAG, "Stopping $platform. Total frames: $frameCount")
                
                releaseEncoder()
                rtmpClient?.disconnect()
                
                _isBroadcasting.value = false
                _connectionStatus.value = "Disconnected"
                _currentPlatform.value = null
                currentConfig = null
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping broadcast", e)
                _connectionStatus.value = "Error: ${e.message}"
            }
        }
    }
    
    /**
     * Process a video frame and send to the streaming platform.
     * Converts I420 to encoder input format, encodes to H.264, and sends via RTMP.
     * 
     * @param buffer Raw video frame data (I420/YUV420P format)
     * @param width Frame width
     * @param height Frame height
     */
    fun processVideoFrame(buffer: ByteBuffer, width: Int = VIDEO_WIDTH, height: Int = VIDEO_HEIGHT) {
        if (!_isBroadcasting.value || !isEncoderConfigured) return
        
        try {
            frameCount++
            
            // Log FPS every 5 seconds
            // BUG-009 fix: Proper elapsed time check instead of +1 hack
            val now = System.currentTimeMillis()
            val elapsed = now - lastLogTime
            if (elapsed >= 5000) {
                val fps = if (elapsed > 0) frameCount * 1000.0 / elapsed else 0.0
                Log.d(TAG, "Streaming to ${currentConfig?.platform?.displayName}: ${String.format("%.1f", fps)} fps, $frameCount frames")
                frameCount = 0
                lastLogTime = now
            }
            
            videoEncoder?.let { encoder ->
                // Get input buffer
                val inputIndex = encoder.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                    inputBuffer?.let { input ->
                        input.clear()
                        
                        // Copy frame data to encoder input
                        val dataSize = minOf(buffer.remaining(), input.remaining())
                        val tempArray = ByteArray(dataSize)
                        val originalPosition = buffer.position()
                        buffer.get(tempArray, 0, dataSize)
                        buffer.position(originalPosition)
                        
                        input.put(tempArray)
                        
                        // Queue input buffer with presentation time
                        val presentationTimeUs = System.nanoTime() / 1000
                        encoder.queueInputBuffer(inputIndex, 0, dataSize, presentationTimeUs, 0)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing video frame", e)
        }
    }
    
    /**
     * Process a Bitmap frame (alternative input format).
     * Useful when receiving pre-rendered frames.
     */
    fun processVideoFrame(bitmap: Bitmap) {
        if (!_isBroadcasting.value) return
        
        // Convert bitmap to YUV and process
        // This is a simplified version - production should use more efficient conversion
        try {
            val width = bitmap.width
            val height = bitmap.height
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            
            val yuv = convertARGBtoYUV420(argb, width, height)
            processVideoFrame(ByteBuffer.wrap(yuv), width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing bitmap frame", e)
        }
    }
    
    /**
     * Convert ARGB pixel array to YUV420 format.
     * BUG-008 fix: Proper operator precedence and value clamping
     */
    private fun convertARGBtoYUV420(argb: IntArray, width: Int, height: Int): ByteArray {
        val yuvSize = width * height * 3 / 2
        val yuv = ByteArray(yuvSize)
        var yIndex = 0
        var uvIndex = width * height

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                // Y - BUG-008 fix: proper parentheses and clamping
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                // UV (subsampled 2x2)
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }

        return yuv
    }
    
    // ===== ConnectChecker Callbacks =====
    
    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "Connection started")
        _connectionStatus.value = "Connecting..."
    }
    
    override fun onConnectionSuccess() {
        val platform = currentConfig?.platform?.displayName ?: "Stream"
        Log.d(TAG, "Connected to $platform!")

        // BUG-003 fix: Initialize encoder only after connection is confirmed
        if (pendingEncoderInit) {
            pendingEncoderInit = false
            if (!initializeEncoder(VIDEO_WIDTH, VIDEO_HEIGHT)) {
                _connectionStatus.value = "Encoder init failed"
                rtmpClient?.disconnect()
                return
            }
        }

        _connectionStatus.value = "Live on $platform!"
        _isBroadcasting.value = true
    }
    
    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Connection failed: $reason")
        _connectionStatus.value = "Failed: $reason"
        _isBroadcasting.value = false
        pendingEncoderInit = false  // BUG-003 fix: Clear pending flag
        releaseEncoder()
    }
    
    override fun onDisconnect() {
        Log.d(TAG, "Disconnected")
        _connectionStatus.value = "Disconnected"
        _isBroadcasting.value = false
        _currentPlatform.value = null
        releaseEncoder()
    }
    
    override fun onAuthError() {
        Log.e(TAG, "Auth error - check stream key")
        _connectionStatus.value = "Auth Error - Check Key"
        _isBroadcasting.value = false
        releaseEncoder()
    }
    
    override fun onAuthSuccess() {
        Log.d(TAG, "Auth successful")
    }
    
    override fun onNewBitrate(bitrate: Long) {
        Log.d(TAG, "Bitrate: ${bitrate / 1000} kbps")
    }
    
    // ===== Recording Control Methods =====

    /**
     * Start local recording while streaming (Mode B).
     * Records the same encoded data being sent to RTMP.
     */
    fun startRecordingWhileStreaming(): Boolean {
        if (!_isBroadcasting.value) {
            Log.w(TAG, "Cannot start recording: not broadcasting")
            return false
        }
        return localRecordingManager?.startRecording(
            recordOnlyMode = false,
            withAudio = _isAudioEnabled.value
        ) ?: false
    }

    /**
     * Start record-only mode (Mode C).
     * Records locally without streaming, saves battery.
     */
    fun startRecordOnly(withAudio: Boolean = true): Boolean {
        if (_isBroadcasting.value) {
            Log.w(TAG, "Cannot start record-only: already broadcasting")
            return false
        }

        val result = localRecordingManager?.startRecording(
            recordOnlyMode = true,
            withAudio = withAudio
        ) ?: false

        if (result && withAudio) {
            // Start audio capture for record-only mode
            startAudioCapture()
        }

        return result
    }

    /**
     * Stop local recording.
     */
    fun stopRecording() {
        localRecordingManager?.stopRecording()

        // If in record-only mode, stop audio capture too
        if (localRecordingManager?.isRecordOnlyMode?.value == true) {
            stopAudioCapture()
        }
    }

    /**
     * Process video frame for record-only mode.
     * Called from GlassesStreamManager when recording without streaming.
     */
    fun processVideoFrameForRecording(buffer: ByteBuffer, width: Int, height: Int) {
        if (localRecordingManager?.isRecordOnlyMode?.value == true) {
            localRecordingManager?.processVideoFrame(buffer, width, height)
        }
    }

    /**
     * Process audio data for record-only mode.
     */
    fun processAudioDataForRecording(pcmData: ByteArray, size: Int) {
        if (localRecordingManager?.isRecordOnlyMode?.value == true) {
            localRecordingManager?.processAudioData(pcmData, size)
        }
    }

    /**
     * Clean up resources.
     */
    fun release() {
        Log.d(TAG, "Releasing RtmpStreamManager")

        // BUG-007 fix: Cancel reconnection job
        reconnectJob?.cancel()
        reconnectJob = null
        wasConnectedBeforeNetworkLoss = false

        // Stop network monitoring
        networkMonitorJob?.cancel()
        networkMonitorJob = null

        // Stop audio capture and release manager
        stopAudioCapture()
        bluetoothAudioManager?.release()
        bluetoothAudioManager = null

        // Stop and release local recording
        localRecordingManager?.release()
        localRecordingManager = null

        // Cancel all coroutines
        scope.cancel()

        // Release encoders
        releaseEncoder()
        releaseAudioEncoder()

        // Disconnect RTMP
        rtmpClient?.disconnect()
        rtmpClient = null
    }
}
