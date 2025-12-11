package com.keremgok.frameflow.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * GlassesStreamManager handles the connection to Ray-Ban Meta glasses
 * and receives video frames from the glasses camera.
 * 
 * Uses Meta Wearables DAT SDK for Android.
 */
class GlassesStreamManager(private val context: Context) {
    
    companion object {
        private const val TAG = "GlassesStreamManager"
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Meta DAT SDK components
    private val deviceSelector: DeviceSelector = AutoDeviceSelector()
    private var streamSession: StreamSession? = null
    private var videoJob: Job? = null
    private var stateJob: Job? = null

    // BUG-004 fix: Track monitoring jobs to prevent duplicates
    private var registrationMonitorJob: Job? = null
    private var devicesMonitorJob: Job? = null
    
    // Current frame from glasses camera
    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()
    
    // Status text
    private val _status = MutableStateFlow("Ready to Stream")
    val status: StateFlow<String> = _status.asStateFlow()
    
    // Streaming state
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    
    // Registration state - default to Unavailable like Meta sample
    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Unavailable())
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()
    
    // Reference to RTMP manager for forwarding frames
    var rtmpManager: RtmpStreamManager? = null
    
    /**
     * Start monitoring Wearables state.
     * Call this after Wearables.initialize()
     *
     * BUG-004 fix: Idempotent - cancels existing jobs before starting new ones
     */
    fun startMonitoring() {
        // Cancel existing jobs to prevent duplicate collectors
        registrationMonitorJob?.cancel()
        devicesMonitorJob?.cancel()

        registrationMonitorJob = scope.launch {
            Wearables.registrationState.collect { state ->
                _registrationState.value = state
                when (state) {
                    is RegistrationState.Registered -> {
                        _status.value = "Connected to glasses"
                        Log.d(TAG, "Registered with glasses")
                    }
                    is RegistrationState.Registering -> {
                        _status.value = "Connecting..."
                        Log.d(TAG, "Registering with glasses")
                    }
                    else -> {
                        _status.value = "Not connected"
                        Log.d(TAG, "Registration state: $state")
                    }
                }
            }
        }

        devicesMonitorJob = scope.launch {
            Wearables.devices.collect { devices ->
                Log.d(TAG, "Devices updated: ${devices.size} device(s)")
            }
        }
    }
    
    /**
     * Check if camera permission is granted on the glasses.
     */
    suspend fun checkCameraPermission(): PermissionStatus {
        return Wearables.checkPermissionStatus(Permission.CAMERA)
    }
    
    /**
     * Start streaming from glasses.
     * Creates a StreamSession and begins receiving video frames.
     */
    fun startStreaming() {
        videoJob?.cancel()
        stateJob?.cancel()
        
        _status.value = "Starting stream..."
        Log.d(TAG, "Starting stream session")
        
        try {
            // Create stream session with medium quality
            val session = Wearables.startStreamSession(
                context,
                deviceSelector,
                StreamConfiguration(
                    videoQuality = VideoQuality.MEDIUM,
                    frameRate = 24
                )
            ).also { streamSession = it }
            
            // Collect video frames
            videoJob = scope.launch {
                session.videoStream.collect { frame ->
                    handleVideoFrame(frame)
                }
            }
            
            // Monitor session state
            stateJob = scope.launch {
                session.state.collect { state ->
                    Log.d(TAG, "Stream state: $state")
                    when (state) {
                        StreamSessionState.STREAMING -> {
                            _status.value = "Streaming Live"
                            _isStreaming.value = true
                        }
                        StreamSessionState.STOPPED -> {
                            _status.value = "Stream stopped"
                            _isStreaming.value = false
                        }
                        StreamSessionState.STARTING -> {
                            _status.value = "Starting..."
                        }
                        StreamSessionState.STOPPING -> {
                            _status.value = "Stopping..."
                        }
                        else -> {
                            _status.value = "State: $state"
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting stream", e)
            _status.value = "Error: ${e.message}"
            _isStreaming.value = false
        }
    }
    
    /**
     * Handle incoming video frame from glasses.
     * Properly manages bitmap memory and stream resources.
     */
    private fun handleVideoFrame(videoFrame: VideoFrame) {
        var outputStream: ByteArrayOutputStream? = null
        
        try {
            val buffer = videoFrame.buffer
            val dataSize = buffer.remaining()
            val byteArray = ByteArray(dataSize)
            
            val originalPosition = buffer.position()
            buffer.get(byteArray)
            buffer.position(originalPosition)
            
            // Convert I420 to NV21 for Android display
            val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
            val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)
            
            // Use outputStream with proper cleanup
            outputStream = ByteArrayOutputStream()
            // Use lower quality for faster preview (sample uses 50)
            image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, outputStream)
            val jpegBytes = outputStream.toByteArray()
            
            // Create new bitmap for preview
            val newBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

            // BUG-002 fix: Don't explicitly recycle - let GC handle it
            // Explicit recycle causes race condition with Compose rendering
            // The old bitmap may still be in use by the UI when we recycle it
            _currentFrame.value = newBitmap
            
            // Forward raw frame to RTMP manager for encoding
            rtmpManager?.processVideoFrame(buffer, videoFrame.width, videoFrame.height)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing video frame", e)
        } finally {
            // Close ByteArrayOutputStream (BUG-007 fix)
            try {
                outputStream?.close()
            } catch (e: Exception) {
                // Ignore close exception
            }
        }
    }
    
    /**
     * Convert I420 (YUV420P) to NV21 format.
     */
    private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
        val output = ByteArray(input.size)
        val size = width * height
        val quarter = size / 4
        
        input.copyInto(output, 0, 0, size)
        
        for (n in 0 until quarter) {
            output[size + n * 2] = input[size + quarter + n]
            output[size + n * 2 + 1] = input[size + n]
        }
        return output
    }
    
    /**
     * Stop streaming from glasses.
     * Properly cleans up all resources including bitmap memory.
     */
    fun stopStreaming() {
        _status.value = "Stopping..."
        Log.d(TAG, "Stopping stream")
        
        videoJob?.cancel()
        videoJob = null
        stateJob?.cancel()
        stateJob = null
        
        streamSession?.close()
        streamSession = null
        
        _status.value = "Ready to Stream"
        _isStreaming.value = false

        // BUG-002 fix: Don't explicitly recycle - let GC handle it
        _currentFrame.value = null
    }
    
    /**
     * Start glasses registration flow.
     */
    fun startRegistration() {
        Log.d(TAG, "Starting registration")
        _status.value = "Opening Meta AI app..."
        Wearables.startRegistration(context)
    }
    
    /**
     * Start unregistration flow.
     */
    fun startUnregistration() {
        Log.d(TAG, "Starting unregistration")
        Wearables.startUnregistration(context)
    }
    
    /**
     * Clean up resources.
     * Cancels all coroutines and releases bitmap memory.
     */
    fun release() {
        Log.d(TAG, "Releasing GlassesStreamManager")
        stopStreaming()
        
        // Cancel all coroutines to prevent leaks
        scope.cancel()
    }
}
