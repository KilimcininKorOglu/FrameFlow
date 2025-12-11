package com.keremgok.frameflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.keremgok.frameflow.data.PreferencesManager
import com.keremgok.frameflow.data.StreamConfig
import com.keremgok.frameflow.streaming.GlassesStreamManager
import com.keremgok.frameflow.streaming.RtmpStreamManager
import com.keremgok.frameflow.ui.SetupScreen
import com.keremgok.frameflow.ui.StreamingScreen
import com.keremgok.frameflow.ui.theme.FrameFlowTheme
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Main Activity for FrameFlow.
 * Handles initialization of Meta DAT SDK and navigation between screens.
 */
class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        
        // Required Android permissions for DAT SDK and streaming
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,  // Required for Android 12+ device discovery
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.RECORD_AUDIO  // For glasses microphone via Bluetooth SCO
        )
    }
    
    private lateinit var glassesManager: GlassesStreamManager
    private lateinit var rtmpManager: RtmpStreamManager
    
    // For wearables permission requests
    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    
    // Wearables permission result launcher
    private val wearablesPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        permissionContinuation?.resume(result)
        permissionContinuation = null
    }
    
    // Android permissions launcher
    private val androidPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            initializeWearables()
        } else {
            // BUG-006 fix: Show user-friendly feedback for denied permissions
            val deniedPermissions = permissions.filter { !it.value }.keys
            Log.e(TAG, "Required permissions not granted: $deniedPermissions")
            
            val message = when {
                deniedPermissions.any { it.contains("BLUETOOTH") } ->
                    "Bluetooth permission required to connect to glasses"
                deniedPermissions.any { it.contains("INTERNET") || it.contains("NETWORK") } ->
                    "Network permission required for streaming"
                deniedPermissions.any { it.contains("RECORD_AUDIO") } ->
                    "Microphone permission required for audio streaming"
                else ->
                    "Please grant required permissions to use FrameFlow"
            }
            
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize managers
        glassesManager = GlassesStreamManager(this)
        rtmpManager = RtmpStreamManager(this)
        
        // Link managers - glasses frames go to RTMP
        glassesManager.rtmpManager = rtmpManager
        
        // Check and request Android permissions, then initialize Wearables
        checkAndRequestPermissions()
        
        enableEdgeToEdge()
        
        setContent {
            FrameFlowTheme(darkTheme = true) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FrameFlowApp(
                        glassesManager = glassesManager,
                        rtmpManager = rtmpManager,
                        onRequestWearablesPermission = ::requestWearablesPermission,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
    
    private fun checkAndRequestPermissions() {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isEmpty()) {
            initializeWearables()
        } else {
            androidPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    private fun initializeWearables() {
        Log.d(TAG, "Initializing Wearables SDK")
        
        // Initialize the DAT SDK - REQUIRED before using any Wearables APIs
        Wearables.initialize(this)
        
        // Start monitoring wearables state
        glassesManager.startMonitoring()
    }
    
    /**
     * Request permission from wearable device via Meta AI app.
     */
    suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                wearablesPermissionLauncher.launch(permission)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        glassesManager.release()
        rtmpManager.release()
    }
}

/**
 * Main app composable that handles navigation between screens.
 */
@Composable
fun FrameFlowApp(
    glassesManager: GlassesStreamManager,
    rtmpManager: RtmpStreamManager,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferencesManager = remember { PreferencesManager.getInstance(context) }
    
    // Observe stream configuration
    val streamConfig by preferencesManager.streamConfigFlow.collectAsState(initial = null)
    
    if (streamConfig == null) {
        // Setup Screen - no config saved yet
        SetupScreen(
            onConnectGlasses = {
                glassesManager.startRegistration()
            },
            onSaveAndContinue = { config ->
                scope.launch {
                    preferencesManager.saveStreamConfig(config)
                }
            }
        )
    } else {
        // Streaming Screen - config exists
        val config = streamConfig!!
        
        StreamingScreen(
            currentFrame = glassesManager.currentFrame,
            glassesStatus = glassesManager.status,
            streamStatus = rtmpManager.connectionStatus,
            isStreaming = glassesManager.isStreaming,
            isBroadcasting = rtmpManager.isBroadcasting,
            currentPlatform = rtmpManager.currentPlatform,
            isAudioEnabled = rtmpManager.isAudioEnabled,
            isRecording = rtmpManager.isRecording,
            recordingDuration = rtmpManager.recordingDuration,
            onGoLive = {
                scope.launch {
                    // Check and request camera permission from glasses
                    val permissionStatus = glassesManager.checkCameraPermission()
                    if (permissionStatus != PermissionStatus.Granted) {
                        val result = onRequestWearablesPermission(Permission.CAMERA)
                        if (result != PermissionStatus.Granted) {
                            Log.e("FrameFlowApp", "Camera permission denied")
                            return@launch
                        }
                        // Delay after permission grant to prevent video freeze
                        // (Known issue with 'allow once' permission)
                        delay(2000)
                    }

                    // Start glasses stream
                    glassesManager.startStreaming()
                    // Start RTMP broadcast to selected platform
                    rtmpManager.startBroadcast(config)
                }
            },
            onStopAll = {
                scope.launch {
                    // Stop recording if active
                    rtmpManager.stopRecording()
                    glassesManager.stopStreaming()
                    rtmpManager.stopBroadcast()
                }
            },
            onLogout = {
                scope.launch {
                    rtmpManager.stopRecording()
                    glassesManager.stopStreaming()
                    rtmpManager.stopBroadcast()
                    preferencesManager.clearConfig()
                }
            },
            onToggleAudio = {
                // Toggle audio streaming from glasses microphone
                val newState = !rtmpManager.isAudioEnabled.value
                rtmpManager.setAudioEnabled(newState)
            },
            onToggleRecording = {
                // Toggle recording (Mode B: while streaming)
                if (rtmpManager.isRecording.value) {
                    rtmpManager.stopRecording()
                } else {
                    rtmpManager.startRecordingWhileStreaming()
                }
            },
            onStartRecordOnly = {
                // Start record-only mode (Mode C)
                scope.launch {
                    // Check camera permission first
                    val permissionStatus = glassesManager.checkCameraPermission()
                    if (permissionStatus != PermissionStatus.Granted) {
                        val result = onRequestWearablesPermission(Permission.CAMERA)
                        if (result != PermissionStatus.Granted) {
                            Log.e("FrameFlowApp", "Camera permission denied")
                            return@launch
                        }
                        delay(2000)
                    }

                    // Start glasses stream for recording
                    glassesManager.startStreaming()
                    // Start record-only mode with audio
                    rtmpManager.startRecordOnly(withAudio = rtmpManager.isAudioEnabled.value)
                }
            }
        )
    }
}
