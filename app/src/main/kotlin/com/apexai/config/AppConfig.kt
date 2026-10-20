package com.apexai.config

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("apexai_config", Context.MODE_PRIVATE)

    var providerType: String
        get() = prefs.getString("provider_type", "anthropic") ?: "anthropic"
        set(value) = prefs.edit().putString("provider_type", value).apply()

    var model: String
        get() = prefs.getString("model", "claude-sonnet-4-6") ?: "claude-sonnet-4-6"
        set(value) = prefs.edit().putString("model", value).apply()

    var omnirouteBaseUrl: String
        get() = prefs.getString("omniroute_base_url", "http://10.0.2.2:20128/v1") ?: "http://10.0.2.2:20128/v1"
        set(value) = prefs.edit().putString("omniroute_base_url", value).apply()

    var customBaseUrl: String
        get() = prefs.getString("custom_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString("custom_base_url", value).apply()

    var customModelName: String
        get() = prefs.getString("custom_model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(value) = prefs.edit().putString("custom_model_name", value).apply()

    var maxTokens: Int
        get() = prefs.getInt("max_tokens", 4096)
        set(value) = prefs.edit().putInt("max_tokens", value).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean("auto_start_boot", false)
        set(value) = prefs.edit().putBoolean("auto_start_boot", value).apply()

    var isSetupComplete: Boolean
        get() = prefs.getBoolean("setup_complete", false)
        set(value) = prefs.edit().putBoolean("setup_complete", value).apply()

    var personalityPrompt: String
        get() = prefs.getString("personality_prompt", "") ?: ""
        set(value) = prefs.edit().putString("personality_prompt", value).apply()

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) = prefs.edit().putString("user_name", value).apply()

    var voiceEnabled: Boolean
        get() = prefs.getBoolean("voice_enabled", false)
        set(value) = prefs.edit().putBoolean("voice_enabled", value).apply()

    var autoSpeakResponses: Boolean
        get() = prefs.getBoolean("auto_speak_responses", true)
        set(value) = prefs.edit().putBoolean("auto_speak_responses", value).apply()

    var continuousVoiceMode: Boolean
        get() = prefs.getBoolean("continuous_voice_mode", true)
        set(value) = prefs.edit().putBoolean("continuous_voice_mode", value).apply()

    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", false)
        set(value) = prefs.edit().putBoolean("overlay_enabled", value).apply()

    /** Max agent loop iterations. 0 = unlimited. */
    var maxIterations: Int
        get() = prefs.getInt("max_iterations", 0)
        set(value) = prefs.edit().putInt("max_iterations", value).apply()

    var permissionProfile: String
        get() = prefs.getString("permission_profile", "FULL_AUTO") ?: "FULL_AUTO"
        set(value) = prefs.edit().putString("permission_profile", value).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word_enabled", false)
        set(value) = prefs.edit().putBoolean("wake_word_enabled", value).apply()

    var heartbeatEnabled: Boolean
        get() = prefs.getBoolean("heartbeat_enabled", true)
        set(value) = prefs.edit().putBoolean("heartbeat_enabled", value).apply()

    /** Whether to poll even when there's no active task context. */
    var heartbeatAlwaysPoll: Boolean
        get() = prefs.getBoolean("heartbeat_always_poll", false)
        set(value) = prefs.edit().putBoolean("heartbeat_always_poll", value).apply()
}
