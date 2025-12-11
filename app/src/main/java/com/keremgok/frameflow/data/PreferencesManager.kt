package com.keremgok.frameflow.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStore delegate at module level to ensure single instance
// Used for non-sensitive preferences (platform selection)
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "frameflow_preferences"
)

/**
 * DataStore-based preferences manager with encrypted storage for sensitive data.
 * Persists streaming configuration locally on device.
 *
 * Uses singleton pattern to ensure single DataStore instance.
 * Stream keys are stored in EncryptedSharedPreferences using AES-256 encryption.
 *
 * Security: Stream keys are encrypted at rest using Android Keystore-backed keys.
 */
class PreferencesManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: PreferencesManager? = null

        /**
         * Get singleton instance of PreferencesManager.
         * Thread-safe double-checked locking pattern.
         */
        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        // DataStore keys (non-sensitive data)
        private val PLATFORM = stringPreferencesKey("platform")
        private val CUSTOM_RTMP_URL = stringPreferencesKey("custom_rtmp_url")

        // EncryptedSharedPreferences keys (sensitive data)
        private const val ENCRYPTED_PREFS_NAME = "frameflow_secure_prefs"
        private const val KEY_STREAM_KEY = "encrypted_stream_key"
    }

    // Encrypted SharedPreferences for sensitive data (stream keys)
    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Get the stored stream key (encrypted at rest).
     * Note: Stream key should never be logged for security.
     */
    fun getStreamKey(): String {
        return encryptedPrefs.getString(KEY_STREAM_KEY, "") ?: ""
    }

    /**
     * Flow of the stored stream key.
     * Note: This reads from encrypted storage on each emission.
     */
    val streamKeyFlow: Flow<String> = context.dataStore.data
        .map { _ ->
            // Read from encrypted storage
            getStreamKey()
        }

    /**
     * Flow of the selected platform.
     */
    val platformFlow: Flow<StreamingPlatform> = context.dataStore.data
        .map { preferences ->
            val platformName = preferences[PLATFORM] ?: StreamingPlatform.TWITCH.name
            StreamingPlatform.fromName(platformName)
        }

    /**
     * Flow of custom RTMP URL (for CUSTOM platform).
     */
    val customRtmpUrlFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[CUSTOM_RTMP_URL] ?: ""
        }

    /**
     * Flow of complete streaming configuration.
     * Returns null if no stream key is configured.
     */
    val streamConfigFlow: Flow<StreamConfig?> = context.dataStore.data
        .map { preferences ->
            val streamKey = getStreamKey()
            if (streamKey.isEmpty()) {
                null
            } else {
                val platformName = preferences[PLATFORM] ?: StreamingPlatform.TWITCH.name
                val platform = StreamingPlatform.fromName(platformName)
                val customUrl = preferences[CUSTOM_RTMP_URL]
                StreamConfig(platform, streamKey, customUrl)
            }
        }

    /**
     * Check if a stream configuration exists.
     */
    val hasConfigFlow: Flow<Boolean> = context.dataStore.data
        .map { _ ->
            getStreamKey().isNotEmpty()
        }

    /**
     * Save the complete streaming configuration.
     * Stream key is stored in encrypted storage.
     */
    suspend fun saveStreamConfig(config: StreamConfig) {
        // Save stream key to encrypted storage
        encryptedPrefs.edit()
            .putString(KEY_STREAM_KEY, config.streamKey)
            .apply()

        // Save non-sensitive data to DataStore
        context.dataStore.edit { preferences ->
            preferences[PLATFORM] = config.platform.name
            config.customRtmpUrl?.let {
                preferences[CUSTOM_RTMP_URL] = it
            }
        }
    }

    /**
     * Save just the stream key (encrypted).
     * Key is stored securely and never logged.
     */
    suspend fun saveStreamKey(key: String) {
        encryptedPrefs.edit()
            .putString(KEY_STREAM_KEY, key)
            .apply()
    }

    /**
     * Save the selected platform.
     */
    suspend fun savePlatform(platform: StreamingPlatform) {
        context.dataStore.edit { preferences ->
            preferences[PLATFORM] = platform.name
        }
    }

    /**
     * Save custom RTMP URL.
     */
    suspend fun saveCustomRtmpUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[CUSTOM_RTMP_URL] = url
        }
    }

    /**
     * Clear all stored configuration (logout).
     * Securely removes stream key from encrypted storage.
     */
    suspend fun clearConfig() {
        // Clear encrypted stream key
        encryptedPrefs.edit()
            .remove(KEY_STREAM_KEY)
            .apply()

        // Clear non-sensitive data
        context.dataStore.edit { preferences ->
            preferences.remove(PLATFORM)
            preferences.remove(CUSTOM_RTMP_URL)
        }
    }

    /**
     * Clear just the stream key from encrypted storage.
     */
    suspend fun clearStreamKey() {
        encryptedPrefs.edit()
            .remove(KEY_STREAM_KEY)
            .apply()
    }
}
