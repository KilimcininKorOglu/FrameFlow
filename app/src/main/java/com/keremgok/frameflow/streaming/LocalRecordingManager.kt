package com.keremgok.frameflow.streaming

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * LocalRecordingManager handles recording video and audio to local MP4 files.
 *
 * Supports two modes:
 * - Record while streaming (Mode B): Records the same encoded data going to RTMP
 * - Record only (Mode C): Records without streaming, saves battery
 */
class LocalRecordingManager(private val context: Context) {

    companion object {
        private const val TAG = "LocalRecordingManager"

        // Video encoding settings
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 4_000_000  // 4 Mbps for better local quality
        private const val VIDEO_FPS = 24
        private const val I_FRAME_INTERVAL = 1  // Keyframe every second for seeking

        // Audio encoding settings
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128_000
        private const val AUDIO_CHANNEL_COUNT = 1

        // Meta Ray-Ban glasses metadata
        private const val DEVICE_MAKE = "Meta AI"
        private const val DEVICE_MODEL = "Ray-Ban Meta Smart Glasses"
        private const val SOFTWARE = "FrameFlow"

        // Camera specifications (Ray-Ban Meta glasses)
        private const val FOCAL_LENGTH = "4.7"  // mm
        private const val F_NUMBER = "2.2"
        private const val ISO = "200"
        private const val EXPOSURE_TIME = "1/30"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // MediaMuxer for MP4 container
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false

    // Video encoder for record-only mode
    private var videoEncoder: MediaCodec? = null
    private var isVideoEncoderConfigured = false
    private var videoEncoderOutputJob: Job? = null

    // Audio encoder for record-only mode
    private var audioEncoder: MediaCodec? = null
    private var isAudioEncoderConfigured = false
    private var audioEncoderOutputJob: Job? = null

    // Track encoder dimensions
    private var encoderWidth = 0
    private var encoderHeight = 0

    // Recording state
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Recording mode
    private val _isRecordOnlyMode = MutableStateFlow(false)
    val isRecordOnlyMode: StateFlow<Boolean> = _isRecordOnlyMode.asStateFlow()

    // Recording duration in seconds
    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    // Current file path
    private val _currentFilePath = MutableStateFlow<String?>(null)
    val currentFilePath: StateFlow<String?> = _currentFilePath.asStateFlow()

    // Status text
    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status.asStateFlow()

    // Recording start time for duration calculation
    private var recordingStartTime = 0L
    private var recordingStartDate: Date? = null
    private var durationUpdateJob: Job? = null

    // Track format info received
    private var videoFormatReceived = false
    private var audioFormatReceived = false

    // Synchronization lock for muxer operations
    private val muxerLock = Any()

    /**
     * Start recording to a local MP4 file.
     *
     * @param recordOnlyMode If true, uses own encoders (Mode C). If false, receives encoded data from RTMP manager (Mode B).
     * @param withAudio Whether to record audio
     */
    fun startRecording(recordOnlyMode: Boolean = false, withAudio: Boolean = true): Boolean {
        if (_isRecording.value) {
            Log.w(TAG, "Already recording")
            return true
        }

        try {
            // Create output file
            val outputPath = createOutputFile()
            if (outputPath == null) {
                _status.value = "Failed to create file"
                return false
            }

            _currentFilePath.value = outputPath
            _isRecordOnlyMode.value = recordOnlyMode

            // Initialize MediaMuxer
            mediaMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Reset track indices
            videoTrackIndex = -1
            audioTrackIndex = -1
            isMuxerStarted = false
            videoFormatReceived = false
            audioFormatReceived = !withAudio  // If no audio, mark as received

            // For record-only mode, initialize our own encoders
            if (recordOnlyMode) {
                if (!initializeVideoEncoder(VIDEO_WIDTH, VIDEO_HEIGHT)) {
                    _status.value = "Video encoder failed"
                    cleanup()
                    return false
                }

                if (withAudio && !initializeAudioEncoder()) {
                    _status.value = "Audio encoder failed"
                    cleanup()
                    return false
                }
            }

            _isRecording.value = true
            recordingStartTime = System.currentTimeMillis()
            recordingStartDate = Date()
            _status.value = "Recording..."

            // Start duration update
            startDurationUpdate()

            Log.d(TAG, "Recording started: $outputPath (recordOnly=$recordOnlyMode, audio=$withAudio)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _status.value = "Error: ${e.message}"
            cleanup()
            return false
        }
    }

    /**
     * Stop recording and finalize the MP4 file.
     */
    fun stopRecording() {
        if (!_isRecording.value) return

        Log.d(TAG, "Stopping recording...")
        _isRecording.value = false

        // Stop duration updates
        durationUpdateJob?.cancel()
        durationUpdateJob = null

        // Release encoders (for record-only mode)
        releaseVideoEncoder()
        releaseAudioEncoder()

        // Finalize muxer
        synchronized(muxerLock) {
            try {
                if (isMuxerStarted) {
                    mediaMuxer?.stop()
                }
                mediaMuxer?.release()
                mediaMuxer = null
                isMuxerStarted = false
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping muxer", e)
            }
        }

        // Add metadata and to MediaStore for gallery visibility
        _currentFilePath.value?.let { path ->
            // Write MP4 metadata (make, model, creation date, etc.)
            writeMetadataToMp4(path, recordingStartDate ?: Date())
            addToMediaStore(path)
        }

        val duration = _recordingDuration.value
        _status.value = "Saved (${formatDuration(duration)})"
        Log.d(TAG, "Recording stopped. Duration: ${formatDuration(duration)}")
    }

    /**
     * Write encoded video data to the recording.
     * Called from RtmpStreamManager when in Mode B.
     */
    fun writeVideoData(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (!_isRecording.value || _isRecordOnlyMode.value) return

        synchronized(muxerLock) {
            if (videoTrackIndex >= 0 && isMuxerStarted) {
                try {
                    mediaMuxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing video data", e)
                }
            }
        }
    }

    /**
     * Write encoded audio data to the recording.
     * Called from RtmpStreamManager when in Mode B.
     */
    fun writeAudioData(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (!_isRecording.value || _isRecordOnlyMode.value) return

        synchronized(muxerLock) {
            if (audioTrackIndex >= 0 && isMuxerStarted) {
                try {
                    mediaMuxer?.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing audio data", e)
                }
            }
        }
    }

    /**
     * Set video format info for the muxer.
     * Must be called before writing video data (Mode B).
     */
    fun setVideoFormat(format: MediaFormat) {
        if (videoFormatReceived) return

        synchronized(muxerLock) {
            try {
                mediaMuxer?.let { muxer ->
                    videoTrackIndex = muxer.addTrack(format)
                    videoFormatReceived = true
                    Log.d(TAG, "Video track added: index=$videoTrackIndex")
                    tryStartMuxer()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting video format", e)
            }
        }
    }

    /**
     * Set audio format info for the muxer.
     * Must be called before writing audio data (Mode B).
     */
    fun setAudioFormat(format: MediaFormat) {
        if (audioFormatReceived) return

        synchronized(muxerLock) {
            try {
                mediaMuxer?.let { muxer ->
                    audioTrackIndex = muxer.addTrack(format)
                    audioFormatReceived = true
                    Log.d(TAG, "Audio track added: index=$audioTrackIndex")
                    tryStartMuxer()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting audio format", e)
            }
        }
    }

    /**
     * Process raw video frame (for record-only mode).
     */
    fun processVideoFrame(buffer: ByteBuffer, width: Int, height: Int) {
        if (!_isRecording.value || !_isRecordOnlyMode.value || !isVideoEncoderConfigured) return

        // Check if we need to reinitialize for new dimensions
        if (encoderWidth != width || encoderHeight != height) {
            Log.d(TAG, "Reinitializing encoder for ${width}x${height}")
            releaseVideoEncoder()
            if (!initializeVideoEncoder(width, height)) {
                Log.e(TAG, "Failed to reinitialize video encoder")
                return
            }
        }

        try {
            videoEncoder?.let { encoder ->
                val inputIndex = encoder.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                    inputBuffer?.let { input ->
                        input.clear()
                        val dataSize = minOf(buffer.remaining(), input.remaining())
                        val tempArray = ByteArray(dataSize)
                        val originalPosition = buffer.position()
                        buffer.get(tempArray, 0, dataSize)
                        buffer.position(originalPosition)
                        input.put(tempArray)

                        val presentationTimeUs = (System.currentTimeMillis() - recordingStartTime) * 1000
                        encoder.queueInputBuffer(inputIndex, 0, dataSize, presentationTimeUs, 0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing video frame", e)
        }
    }

    /**
     * Process raw audio data (for record-only mode).
     */
    fun processAudioData(pcmData: ByteArray, size: Int) {
        if (!_isRecording.value || !_isRecordOnlyMode.value || !isAudioEncoderConfigured) return

        try {
            audioEncoder?.let { encoder ->
                val inputIndex = encoder.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                    inputBuffer?.let { input ->
                        input.clear()
                        val dataSize = minOf(size, input.remaining())
                        input.put(pcmData, 0, dataSize)

                        val presentationTimeUs = (System.currentTimeMillis() - recordingStartTime) * 1000
                        encoder.queueInputBuffer(inputIndex, 0, dataSize, presentationTimeUs, 0)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio data", e)
        }
    }

    /**
     * Initialize video encoder for record-only mode.
     */
    private fun initializeVideoEncoder(width: Int, height: Int): Boolean {
        try {
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

            encoderWidth = width
            encoderHeight = height
            isVideoEncoderConfigured = true

            // Start output processing
            startVideoEncoderOutput()

            Log.d(TAG, "Video encoder initialized: ${width}x${height}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video encoder", e)
            return false
        }
    }

    /**
     * Initialize audio encoder for record-only mode.
     */
    private fun initializeAudioEncoder(): Boolean {
        try {
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

            // Start output processing
            startAudioEncoderOutput()

            Log.d(TAG, "Audio encoder initialized")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio encoder", e)
            return false
        }
    }

    /**
     * Start processing video encoder output.
     */
    private fun startVideoEncoderOutput() {
        videoEncoderOutputJob?.cancel()
        videoEncoderOutputJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive && _isRecording.value) {
                try {
                    videoEncoder?.let { encoder ->
                        val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)

                        when {
                            outputIndex >= 0 -> {
                                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                outputBuffer?.let { buffer ->
                                    if (bufferInfo.size > 0) {
                                        synchronized(muxerLock) {
                                            if (videoTrackIndex >= 0 && isMuxerStarted) {
                                                mediaMuxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                                            }
                                        }
                                    }
                                }
                                encoder.releaseOutputBuffer(outputIndex, false)
                            }
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val newFormat = encoder.outputFormat
                                Log.d(TAG, "Video encoder format changed: $newFormat")
                                setVideoFormat(newFormat)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing video encoder output", e)
                }
            }
        }
    }

    /**
     * Start processing audio encoder output.
     */
    private fun startAudioEncoderOutput() {
        audioEncoderOutputJob?.cancel()
        audioEncoderOutputJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()

            while (isActive && _isRecording.value) {
                try {
                    audioEncoder?.let { encoder ->
                        val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)

                        when {
                            outputIndex >= 0 -> {
                                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                outputBuffer?.let { buffer ->
                                    if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                        synchronized(muxerLock) {
                                            if (audioTrackIndex >= 0 && isMuxerStarted) {
                                                mediaMuxer?.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                                            }
                                        }
                                    }
                                }
                                encoder.releaseOutputBuffer(outputIndex, false)
                            }
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val newFormat = encoder.outputFormat
                                Log.d(TAG, "Audio encoder format changed: $newFormat")
                                setAudioFormat(newFormat)
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
     * Try to start the muxer if all tracks are ready.
     */
    private fun tryStartMuxer() {
        if (!videoFormatReceived || !audioFormatReceived) return
        if (isMuxerStarted) return

        try {
            mediaMuxer?.start()
            isMuxerStarted = true
            Log.d(TAG, "Muxer started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting muxer", e)
        }
    }

    /**
     * Release video encoder resources.
     */
    private fun releaseVideoEncoder() {
        videoEncoderOutputJob?.cancel()
        videoEncoderOutputJob = null

        try {
            videoEncoder?.stop()
            videoEncoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing video encoder", e)
        }

        videoEncoder = null
        isVideoEncoderConfigured = false
        encoderWidth = 0
        encoderHeight = 0
    }

    /**
     * Release audio encoder resources.
     */
    private fun releaseAudioEncoder() {
        audioEncoderOutputJob?.cancel()
        audioEncoderOutputJob = null

        try {
            audioEncoder?.stop()
            audioEncoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio encoder", e)
        }

        audioEncoder = null
        isAudioEncoderConfigured = false
    }

    /**
     * Create output file path for recording.
     */
    private fun createOutputFile(): String? {
        return try {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val fileName = "FrameFlow_$timestamp.mp4"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+, use app-specific directory
                val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                val frameFlowDir = File(moviesDir, "FrameFlow")
                if (!frameFlowDir.exists()) {
                    frameFlowDir.mkdirs()
                }
                File(frameFlowDir, fileName).absolutePath
            } else {
                // For older versions
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val frameFlowDir = File(moviesDir, "FrameFlow")
                if (!frameFlowDir.exists()) {
                    frameFlowDir.mkdirs()
                }
                File(frameFlowDir, fileName).absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating output file", e)
            null
        }
    }

    /**
     * Write Meta Ray-Ban glasses metadata to MP4 file.
     * Uses MediaMuxer's setLocation for GPS and udta atom for custom metadata.
     */
    private fun writeMetadataToMp4(filePath: String, creationDate: Date) {
        try {
            // Format dates for metadata
            val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }

            val isoDate = isoDateFormat.format(creationDate)
            val exifDate = exifDateFormat.format(creationDate)

            // Create XMP metadata string with camera info
            val xmpMetadata = buildString {
                append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
                append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
                append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
                append("<rdf:Description rdf:about=\"\"")
                append(" xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\"")
                append(" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\"")
                append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"")
                append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")

                // Device info
                append("<tiff:Make>$DEVICE_MAKE</tiff:Make>")
                append("<tiff:Model>$DEVICE_MODEL</tiff:Model>")
                append("<tiff:Software>$SOFTWARE</tiff:Software>")

                // Dates
                append("<xmp:CreateDate>$isoDate</xmp:CreateDate>")
                append("<xmp:ModifyDate>$isoDate</xmp:ModifyDate>")
                append("<exif:DateTimeOriginal>$exifDate</exif:DateTimeOriginal>")

                // Camera settings
                append("<exif:FNumber>$F_NUMBER</exif:FNumber>")
                append("<exif:FocalLength>$FOCAL_LENGTH</exif:FocalLength>")
                append("<exif:ISOSpeedRatings>$ISO</exif:ISOSpeedRatings>")
                append("<exif:ExposureTime>$EXPOSURE_TIME</exif:ExposureTime>")
                append("<exif:MeteringMode>5</exif:MeteringMode>") // Multi-segment
                append("<exif:Flash>0</exif:Flash>") // No flash
                append("<exif:WhiteBalance>0</exif:WhiteBalance>") // Auto
                append("<exif:SceneCaptureType>0</exif:SceneCaptureType>") // Standard

                append("</rdf:Description>")
                append("</rdf:RDF>")
                append("</x:xmpmeta>")
                append("<?xpacket end=\"w\"?>")
            }

            // Write XMP to a sidecar file (.xmp) for external tools to read
            val xmpFile = File(filePath.replace(".mp4", ".xmp"))
            xmpFile.writeText(xmpMetadata)
            Log.d(TAG, "XMP metadata written to: ${xmpFile.absolutePath}")

            // Also create a simple JSON metadata file for easy access
            val jsonMetadata = buildString {
                appendLine("{")
                appendLine("  \"make\": \"$DEVICE_MAKE\",")
                appendLine("  \"model\": \"$DEVICE_MODEL\",")
                appendLine("  \"software\": \"$SOFTWARE\",")
                appendLine("  \"dateTimeOriginal\": \"$exifDate\",")
                appendLine("  \"createDate\": \"$exifDate\",")
                appendLine("  \"modifyDate\": \"$exifDate\",")
                appendLine("  \"exposureTime\": \"$EXPOSURE_TIME\",")
                appendLine("  \"fNumber\": \"$F_NUMBER\",")
                appendLine("  \"iso\": \"$ISO\",")
                appendLine("  \"focalLength\": \"$FOCAL_LENGTH mm\",")
                appendLine("  \"meteringMode\": \"Multi-segment\",")
                appendLine("  \"flash\": \"No Flash\",")
                appendLine("  \"whiteBalance\": \"Auto\",")
                appendLine("  \"sceneCaptureType\": \"Standard\"")
                appendLine("}")
            }

            val jsonFile = File(filePath.replace(".mp4", "_metadata.json"))
            jsonFile.writeText(jsonMetadata)
            Log.d(TAG, "JSON metadata written to: ${jsonFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Error writing metadata to MP4", e)
        }
    }

    /**
     * Add the recorded file to MediaStore for gallery visibility.
     * Includes Meta Ray-Ban glasses device metadata.
     */
    private fun addToMediaStore(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) return

            val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val creationDate = recordingStartDate ?: Date()

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DURATION, _recordingDuration.value * 1000)

                // Add device metadata
                put(MediaStore.Video.Media.ARTIST, DEVICE_MAKE)
                put(MediaStore.Video.Media.DESCRIPTION, "Recorded with $DEVICE_MODEL via $SOFTWARE")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/FrameFlow")
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    put(MediaStore.Video.Media.DATE_TAKEN, creationDate.time)
                } else {
                    @Suppress("DEPRECATION")
                    put(MediaStore.Video.Media.DATA, filePath)
                }

                // Add creation dates
                put(MediaStore.Video.Media.DATE_ADDED, creationDate.time / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, creationDate.time / 1000)
            }

            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            Log.d(TAG, "Added to MediaStore with metadata: $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding to MediaStore", e)
        }
    }

    /**
     * Start updating recording duration.
     */
    private fun startDurationUpdate() {
        durationUpdateJob?.cancel()
        durationUpdateJob = scope.launch {
            while (isActive && _isRecording.value) {
                val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                _recordingDuration.value = elapsed
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    /**
     * Format duration in seconds to MM:SS string.
     */
    private fun formatDuration(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    /**
     * Cleanup resources on error.
     */
    private fun cleanup() {
        releaseVideoEncoder()
        releaseAudioEncoder()

        try {
            mediaMuxer?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaMuxer = null

        _isRecording.value = false
        _currentFilePath.value = null
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopRecording()
        scope.cancel()
        Log.d(TAG, "LocalRecordingManager released")
    }
}
