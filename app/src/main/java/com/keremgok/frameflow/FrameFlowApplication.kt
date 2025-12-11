package com.keremgok.frameflow

import android.app.Application
import android.util.Log

/**
 * Application class for FrameFlow.
 * Initializes the Meta Wearables SDK on app startup.
 */
class FrameFlowApplication : Application() {
    
    companion object {
        private const val TAG = "FrameFlowApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Meta DAT SDK is initialized automatically via AndroidManifest meta-data
        // The APPLICATION_ID is read from strings.xml
        Log.d(TAG, "FrameFlow Application initialized")
    }
}
