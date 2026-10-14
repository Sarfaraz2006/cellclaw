package com.cellclaw.provider

import com.cellclaw.config.AppConfig
import com.cellclaw.config.SecureKeyStore
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
        ProviderInfo("omniroute", "OmniRoute (Router / Proxy)", "claude-3-7-sonnet",
            hasKey("omniroute"),
            models = listOf("claude-3-7-sonnet", "claude-3-5-sonnet", "gemini-flash-lite-latest", "sonnet", "gemini-3.1-pro")),
        ProviderInfo("anthropic", "Anthropic (Claude)", "claude-3-7-sonnet",
            secureKeyStore.hasApiKey("anthropic"),
            models = listOf("claude-3-7-sonnet", "claude-3-5-sonnet", "claude-3-5-haiku", "claude-3-opus")),
        ProviderInfo("openai", "OpenAI (GPT)", "gpt-4o",
            secureKeyStore.hasApiKey("openai"),
            models = listOf("gpt-4o", "gpt-4o-mini", "o1", "o3-mini")),
        ProviderInfo("gemini", "Google (Gemini)", "gemini-2.0-flash",
            secureKeyStore.hasApiKey("gemini"),
            models = listOf("gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-pro-exp-02-05")),
        ProviderInfo("openrouter", "OpenRouter", "anthropic/claude-3.7-sonnet",
            secureKeyStore.hasApiKey("openrouter"),
            models = listOf("anthropic/claude-3.7-sonnet", "anthropic/claude-3.5-sonnet", "google/gemini-2.0-flash-exp:free", "deepseek/deepseek-r1")),
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
                    model.ifBlank { "claude-3-7-sonnet" }
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
