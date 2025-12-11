package com.keremgok.frameflow.data

/**
 * Supported streaming platforms with their RTMP URLs.
 */
enum class StreamingPlatform(
    val displayName: String,
    val rtmpUrl: String,
    val icon: String,  // Emoji for simple display
    val streamKeyHint: String,
    val helpUrl: String
) {
    TWITCH(
        displayName = "Twitch",
        rtmpUrl = "rtmp://live.twitch.tv/app",
        icon = "🟣",
        streamKeyHint = "Enter Twitch Stream Key",
        helpUrl = "https://dashboard.twitch.tv/settings/stream"
    ),
    
    YOUTUBE(
        displayName = "YouTube",
        rtmpUrl = "rtmp://a.rtmp.youtube.com/live2",
        icon = "🔴",
        streamKeyHint = "Enter YouTube Stream Key",
        helpUrl = "https://studio.youtube.com/channel/UC/livestreaming"
    ),
    
    KICK(
        displayName = "Kick",
        rtmpUrl = "rtmp://fa723fc1b171.global-contribute.live-video.net/app",
        icon = "🟢",
        streamKeyHint = "Enter Kick Stream Key",
        helpUrl = "https://kick.com/dashboard/settings/stream"
    ),
    
    FACEBOOK(
        displayName = "Facebook",
        rtmpUrl = "rtmps://live-api-s.facebook.com:443/rtmp",
        icon = "🔵",
        streamKeyHint = "Enter Facebook Stream Key",
        helpUrl = "https://www.facebook.com/live/producer"
    ),
    
    TIKTOK(
        displayName = "TikTok",
        rtmpUrl = "rtmp://rtmp-push.tiktok.com/live",
        icon = "⚫",
        streamKeyHint = "Enter TikTok Stream Key",
        helpUrl = "https://www.tiktok.com/studio"
    ),
    
    CUSTOM(
        displayName = "Custom RTMP",
        rtmpUrl = "",  // User provides this
        icon = "⚙️",
        streamKeyHint = "Enter Stream Key",
        helpUrl = ""
    );
    
    companion object {
        /**
         * Get platform by name, defaults to TWITCH if not found.
         */
        fun fromName(name: String): StreamingPlatform {
            return entries.find { it.name == name } ?: TWITCH
        }
        
        /**
         * Get all platforms except CUSTOM for the selection list.
         */
        fun presetPlatforms(): List<StreamingPlatform> {
            return entries.filter { it != CUSTOM }
        }
    }
}

/**
 * Complete streaming configuration including platform, key, and optional custom URL.
 */
data class StreamConfig(
    val platform: StreamingPlatform,
    val streamKey: String,
    val customRtmpUrl: String? = null  // Only used when platform is CUSTOM
) {
    
    companion object {
        // BUG-005 fix: Valid RTMP URL patterns - now accepts localhost and IP addresses
        private val RTMP_URL_REGEX = Regex(
            "^rtmps?://(" +
                    "[a-zA-Z0-9][-a-zA-Z0-9.]*|" +          // hostname (with optional dots)
                    "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|" +  // IPv4 address
                    "localhost" +                            // localhost
                    ")(?::\\d+)?(?:/[^\\s]*)?\$"
        )
        
        /**
         * Validate if a URL is a valid RTMP URL.
         * @param url The URL to validate
         * @return true if valid RTMP URL format
         */
        fun isValidRtmpUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val trimmed = url.trim()
            
            // Must start with rtmp:// or rtmps://
            if (!trimmed.startsWith("rtmp://") && !trimmed.startsWith("rtmps://")) {
                return false
            }
            
            // Basic format validation
            return RTMP_URL_REGEX.matches(trimmed)
        }
        
        /**
         * Get validation error message for URL.
         * @param url The URL to validate
         * @return Error message or null if valid
         */
        fun getUrlValidationError(url: String?): String? {
            if (url.isNullOrBlank()) {
                return "RTMP URL is required"
            }
            
            val trimmed = url.trim()
            
            if (!trimmed.startsWith("rtmp://") && !trimmed.startsWith("rtmps://")) {
                return "URL must start with rtmp:// or rtmps://"
            }
            
            if (!RTMP_URL_REGEX.matches(trimmed)) {
                return "Invalid RTMP URL format"
            }
            
            return null
        }
    }
    
    /**
     * Get the full RTMP URL with stream key appended.
     */
    fun getFullRtmpUrl(): String {
        val baseUrl = if (platform == StreamingPlatform.CUSTOM) {
            customRtmpUrl?.trim() ?: ""
        } else {
            platform.rtmpUrl
        }
        
        return if (baseUrl.endsWith("/")) {
            "$baseUrl$streamKey"
        } else {
            "$baseUrl/$streamKey"
        }
    }
    
    /**
     * Check if the configuration is valid for streaming.
     */
    fun isValid(): Boolean {
        if (streamKey.isBlank()) return false
        if (platform == StreamingPlatform.CUSTOM) {
            if (!isValidRtmpUrl(customRtmpUrl)) return false
        }
        return true
    }
    
    /**
     * Get validation error message if configuration is invalid.
     * @return Error message or null if valid
     */
    fun getValidationError(): String? {
        if (streamKey.isBlank()) {
            return "Stream key is required"
        }
        if (platform == StreamingPlatform.CUSTOM) {
            return getUrlValidationError(customRtmpUrl)
        }
        return null
    }
}
