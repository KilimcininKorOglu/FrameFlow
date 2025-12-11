package com.keremgok.frameflow.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keremgok.frameflow.data.StreamConfig
import com.keremgok.frameflow.data.StreamingPlatform
import com.keremgok.frameflow.ui.theme.FrameFlowTheme

/**
 * Setup screen for selecting streaming platform and entering stream key.
 */
@Composable
fun SetupScreen(
    onConnectGlasses: () -> Unit,
    onSaveAndContinue: (StreamConfig) -> Unit
) {
    var selectedPlatform by remember { mutableStateOf(StreamingPlatform.TWITCH) }
    var streamKey by remember { mutableStateOf("") }
    var customRtmpUrl by remember { mutableStateOf("") }

    // BUG-006 fix: Use StreamConfig.isValid() for consistent validation
    val currentConfig = StreamConfig(
        platform = selectedPlatform,
        streamKey = streamKey,
        customRtmpUrl = if (selectedPlatform == StreamingPlatform.CUSTOM) customRtmpUrl else null
    )
    val isConfigValid = currentConfig.isValid()
    val validationError = currentConfig.getValidationError()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        // App Title
        Text(
            text = "FrameFlow",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        
        Text(
            text = "Stream from your glasses",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Connect Glasses Button
        OutlinedButton(
            onClick = onConnectGlasses,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🕶️  Connect to Meta Glasses")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Platform Selection
        Text(
            text = "Select Platform",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Platform Cards
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(StreamingPlatform.entries) { platform ->
                PlatformCard(
                    platform = platform,
                    isSelected = selectedPlatform == platform,
                    onClick = { selectedPlatform = platform }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Custom RTMP URL (only shown for CUSTOM platform)
        if (selectedPlatform == StreamingPlatform.CUSTOM) {
            // BUG-006 fix: Show URL validation error
            val urlError = if (customRtmpUrl.isNotBlank()) {
                StreamConfig.getUrlValidationError(customRtmpUrl)
            } else null

            OutlinedTextField(
                value = customRtmpUrl,
                onValueChange = { customRtmpUrl = it },
                label = { Text("RTMP Server URL") },
                placeholder = { Text("rtmp://your-server.com/live") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = urlError != null,
                supportingText = if (urlError != null) {
                    { Text(urlError, color = MaterialTheme.colorScheme.error) }
                } else null
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Stream Key Input
        OutlinedTextField(
            value = streamKey,
            onValueChange = { streamKey = it },
            label = { Text(selectedPlatform.streamKeyHint) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        
        // Help text
        if (selectedPlatform.helpUrl.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Get your stream key from ${selectedPlatform.displayName} dashboard",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // Save & Continue Button
        Button(
            onClick = {
                // BUG-006 fix: Use already-validated currentConfig
                onSaveAndContinue(currentConfig)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = isConfigValid,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = "Start Streaming to ${selectedPlatform.displayName}",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Platform selection card.
 */
@Composable
fun PlatformCard(
    platform: StreamingPlatform,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }
    
    Card(
        modifier = Modifier
            .width(100.dp)
            .height(90.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = platform.icon,
                fontSize = 28.sp
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = platform.displayName,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SetupScreenPreview() {
    FrameFlowTheme(darkTheme = true) {
        SetupScreen(
            onConnectGlasses = {},
            onSaveAndContinue = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PlatformCardPreview() {
    FrameFlowTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlatformCard(
                platform = StreamingPlatform.TWITCH,
                isSelected = true,
                onClick = {}
            )
            PlatformCard(
                platform = StreamingPlatform.YOUTUBE,
                isSelected = false,
                onClick = {}
            )
        }
    }
}
