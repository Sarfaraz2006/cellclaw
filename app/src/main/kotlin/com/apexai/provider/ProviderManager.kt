package com.apexai.provider

import com.apexai.config.AppConfig
import com.apexai.config.SecureKeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages available AI providers and handles provider switching.
 * Reads the active provider from AppConfig and configures it with
 * the encrypted API key from SecureKeyStore.
 */
@Singleton
class ProviderManager @Inject constructor(
    private val appConfig: AppConfig,
    private val secureKeyStore: SecureKeyStore,
    private val anthropicProvider: AnthropicProvider,
    private val openAIProvider: OpenAIProvider,
    private val geminiProvider: GeminiProvider,
    private val openRouterProvider: OpenRouterProvider,
    private val customOpenAIProvider: CustomOpenAIProvider
) {
    private val providers = mapOf(
        "anthropic" to anthropicProvider,
        "openai" to openAIProvider,
        "gemini" to geminiProvider,
        "openrouter" to openRouterProvider,
        "omniroute" to customOpenAIProvider,
        "custom" to customOpenAIProvider
    )

    /** Get the currently active provider, configured with its API key */
    fun activeProvider(): Provider {
        val type = appConfig.providerType
        val provider = providers[type] ?: anthropicProvider
        configureProvider(type, provider)
        return provider
    }

    /** Switch to a different provider */
    fun switchProvider(type: String) {
        if (type in providers) {
            appConfig.providerType = type
        }
    }

    /** Get the active provider type name */
    fun activeType(): String = appConfig.providerType

    /** Get all available provider types */
    fun availableProviders(): List<ProviderInfo> = listOf(
        ProviderInfo("anthropic", "Anthropic (Claude)", "claude-sonnet-4-6",
            secureKeyStore.hasApiKey("anthropic"),
            models = listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5")),
        ProviderInfo("openai", "OpenAI (GPT)", "gpt-5.2",
            secureKeyStore.hasApiKey("openai"),
            models = listOf("gpt-5.2", "gpt-5.2-chat-latest", "gpt-5-mini", "gpt-4.1", "gpt-4.1-mini")),
        ProviderInfo("gemini", "Google (Gemini)", "gemini-3-flash-preview",
            secureKeyStore.hasApiKey("gemini"),
            models = listOf("gemini-3.1-pro-preview", "gemini-3-flash-preview", "gemini-2.5-flash", "gemini-2.5-pro")),
        ProviderInfo("openrouter", "OpenRouter", "google/gemini-2.5-flash",
            secureKeyStore.hasApiKey("openrouter"),
            models = listOf("google/gemini-2.5-flash", "google/gemini-2.5-pro", "anthropic/claude-sonnet-4.6", "openai/gpt-5.2")),
        ProviderInfo("omniroute", "OmniRoute (Router / Proxy)", "gemini-2.5-flash",
            hasKey("omniroute"),
            models = listOf("gemini-2.5-flash", "gemini-2.5-pro", "claude-3-5-sonnet", "deepseek-chat", "gpt-4o", "gpt-4o-mini")),
        ProviderInfo("custom", "Custom Provider (OpenAI-Compatible)", "custom-model",
            hasKey("custom"),
            models = emptyList())
    )

    /** Check if a provider has an API key configured or is usable */
    fun hasKey(type: String): Boolean {
        if (type == "omniroute" || type == "custom") {
            return true
        }
        return secureKeyStore.hasApiKey(type)
    }

    /** Store an API key for a provider */
    fun setApiKey(type: String, apiKey: String) {
        secureKeyStore.storeApiKey(type, apiKey)
    }

    /** Remove an API key for a provider */
    fun removeApiKey(type: String) {
        secureKeyStore.deleteApiKey(type)
    }

    private fun configureProvider(type: String, provider: Provider) {
        val apiKey = secureKeyStore.getApiKey(type) ?: ""
        val model = appConfig.model
        when (provider) {
            is AnthropicProvider -> provider.configure(apiKey, model)
            is OpenAIProvider -> provider.configure(apiKey, model)
            is GeminiProvider -> provider.configure(apiKey, model)
            is OpenRouterProvider -> provider.configure(apiKey, model)
            is CustomOpenAIProvider -> {
                val baseUrl = if (type == "omniroute") appConfig.omnirouteBaseUrl else appConfig.customBaseUrl
                val activeModel = if (type == "custom" && appConfig.customModelName.isNotBlank()) {
                    appConfig.customModelName
                } else {
                    model.ifBlank { "gemini-2.5-flash" }
                }
                provider.configure(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = activeModel,
                    providerName = type
                )
            }
        }
    }
}

data class ProviderInfo(
    val type: String,
    val displayName: String,
    val defaultModel: String,
    val hasKey: Boolean,
    val models: List<String> = emptyList()
)
