package com.keremgok.frameflow.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keremgok.frameflow.data.StreamingPlatform
import com.keremgok.frameflow.ui.theme.FrameFlowTheme
import com.keremgok.frameflow.ui.theme.StreamingLive
import com.keremgok.frameflow.ui.theme.StreamingStopped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Streaming screen showing video preview and controls.
 */
@Composable
fun StreamingScreen(
    currentFrame: StateFlow<Bitmap?>,
    glassesStatus: StateFlow<String>,
    streamStatus: StateFlow<String>,
    isStreaming: StateFlow<Boolean>,
    isBroadcasting: StateFlow<Boolean>,
    currentPlatform: StateFlow<StreamingPlatform?>,
    isAudioEnabled: StateFlow<Boolean>,
    isRecording: StateFlow<Boolean>,
    recordingDuration: StateFlow<Long>,
    onGoLive: () -> Unit,
    onStopAll: () -> Unit,
    onLogout: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleRecording: () -> Unit,
    onStartRecordOnly: () -> Unit
) {
    val frame by currentFrame.collectAsState()
    val glassesStatusText by glassesStatus.collectAsState()
    val streamStatusText by streamStatus.collectAsState()
    val streaming by isStreaming.collectAsState()
    val broadcasting by isBroadcasting.collectAsState()
    val platform by currentPlatform.collectAsState()
    val audioEnabled by isAudioEnabled.collectAsState()
    val recording by isRecording.collectAsState()
    val recDuration by recordingDuration.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header with platform info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FrameFlow",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            // Platform badge
            platform?.let { p ->
                PlatformBadge(platform = p, isLive = broadcasting)
            }
        }
        
        // Video Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            frame?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Video Preview",
                    modifier = Modifier.fillMaxSize()
                )
                
                // Live indicator, recording, and audio overlay
                if (broadcasting || recording) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Recording badge with duration
                        if (recording) {
                            RecordingBadge(durationSeconds = recDuration)
                        }
                        // Audio badge
                        AudioBadge(isEnabled = audioEnabled)
                        // Live badge (only when broadcasting)
                        if (broadcasting) {
                            LiveBadge()
                        }
                    }
                }
            } ?: run {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "👓",
                        fontSize = 48.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Glasses Offline",
                        color = Color.Gray,
                        fontSize = 16.sp
                    )
                }
            }
        }
        
        // Status Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = "Glasses",
                status = glassesStatusText,
                isActive = streaming,
                modifier = Modifier.weight(1f)
            )
            
            StatusCard(
                title = "Stream",
                status = streamStatusText,
                isActive = broadcasting,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Control Buttons - Row 1: Audio and Recording
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Audio Toggle Button
            IconButton(
                onClick = onToggleAudio,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (audioEnabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                Icon(
                    imageVector = if (audioEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = if (audioEnabled) "Mute audio" else "Enable audio",
                    tint = if (audioEnabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Recording Toggle Button (Mode B: while streaming, Mode C: record only)
            IconButton(
                onClick = {
                    if (recording) {
                        onToggleRecording()  // Stop recording
                    } else if (broadcasting) {
                        onToggleRecording()  // Start recording while streaming (Mode B)
                    } else {
                        onStartRecordOnly()  // Start record-only mode (Mode C)
                    }
                },
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (recording) Color(0xFFB71C1C)  // Dark red when recording
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                Icon(
                    imageVector = if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                    contentDescription = if (recording) "Stop recording" else "Start recording",
                    tint = if (recording) Color.White else Color(0xFFE53935)
                )
            }

            // Go Live / Stop All Button
            Button(
                onClick = {
                    if (streaming) onStopAll() else onGoLive()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (streaming) StreamingStopped else StreamingLive
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text(
                    text = if (streaming) "⏹ Stop All" else "▶ Go Live",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            // Logout Button
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.height(56.dp)
            ) {
                Text("Settings")
            }
        }
    }
}

/**
 * Platform badge showing current streaming platform.
 */
@Composable
fun PlatformBadge(
    platform: StreamingPlatform,
    isLive: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isLive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = platform.icon, fontSize = 16.sp)
            Text(
                text = platform.displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Recording status badge with duration.
 */
@Composable
fun RecordingBadge(durationSeconds: Long) {
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    val durationText = String.format("%02d:%02d", minutes, seconds)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFB71C1C)  // Dark red
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Pulsing record dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
            )
            Text(
                text = "REC $durationText",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Audio status badge.
 */
@Composable
fun AudioBadge(isEnabled: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) Color(0xFF2E7D32) else Color(0xFF424242)
        ),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = if (isEnabled) "MIC" else "MUTE",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Live indicator badge.
 */
@Composable
fun LiveBadge() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Red),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
            )
            Text(
                text = "LIVE",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Status card for glasses/stream status.
 */
@Composable
fun StatusCard(
    title: String,
    status: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = status,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isActive) StreamingLive else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StreamingScreenPreview() {
    FrameFlowTheme(darkTheme = true) {
        StreamingScreen(
            currentFrame = MutableStateFlow(null),
            glassesStatus = MutableStateFlow("Ready to Stream"),
            streamStatus = MutableStateFlow("Disconnected"),
            isStreaming = MutableStateFlow(false),
            isBroadcasting = MutableStateFlow(false),
            currentPlatform = MutableStateFlow(StreamingPlatform.TWITCH),
            isAudioEnabled = MutableStateFlow(false),
            isRecording = MutableStateFlow(false),
            recordingDuration = MutableStateFlow(0L),
            onGoLive = {},
            onStopAll = {},
            onLogout = {},
            onToggleAudio = {},
            onToggleRecording = {},
            onStartRecordOnly = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LiveBadgePreview() {
    FrameFlowTheme {
        LiveBadge()
    }
}
