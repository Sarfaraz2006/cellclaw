package com.cellclaw.provider

import android.util.Log
import com.cellclaw.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Universal OpenAI-compatible Provider.
 * Connects to OmniRoute, Groq, Ollama, DeepSeek, LocalAI, vLLM, or any custom API endpoint.
 */
@Singleton
class CustomOpenAIProvider @Inject constructor() : Provider {

    private var _name: String = "omniroute"
    override val name: String
        get() = _name

    private var baseUrl: String = AppConfig.DEFAULT_OMNIROUTE_URL
    private var apiKey: String = ""
    private var model: String = DEFAULT_MODEL

    val activeModel: String
        get() = model

    internal var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // Maps sanitized names (dots replaced with underscores) back to original tool names
    private val toolNameMap = mutableMapOf<String, String>()

    fun configure(
        baseUrl: String = this.baseUrl,
        apiKey: String = this.apiKey,
        model: String = this.model,
        providerName: String = this._name
    ) {
        if (baseUrl.isNotBlank()) this.baseUrl = baseUrl.trim()
        val key = apiKey.trim().ifBlank {
            if (providerName == "omniroute") AppConfig.DEFAULT_OMNIROUTE_TOKEN else ""
        }
        this.apiKey = key
        if (model.isNotBlank()) this.model = model.trim()
        this._name = providerName
    }

    fun getChatCompletionsUrl(): String {
        var clean = baseUrl.trim().trimEnd('/')
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        return when {
            clean.endsWith("/chat/completions") -> clean
            clean.endsWith("/v1") -> "$clean/chat/completions"
            else -> "$clean/v1/chat/completions"
        }
    }

    override suspend fun complete(request: CompletionRequest): CompletionResponse =
        withContext(Dispatchers.IO) {
            val url = getChatCompletionsUrl()
            val fallbackModels = listOf("gemini-3.8-flash-high", "gemini-3.7-flash-medium", "claude-sonnet-4-6", "claude-sonnet-5-5", "claude-opus-5", "gemini-flash-lite-latest")

            val currentModel = this@CustomOpenAIProvider.model
            val modelsToTry = mutableListOf(currentModel)
            for (fb in fallbackModels) {
                if (fb != currentModel) {
                    modelsToTry.add(fb)
                }
            }

            var lastException: Exception? = null

            for (attemptModel in modelsToTry) {
                try {
                    val body = buildRequestBody(request, attemptModel)
                    val requestBuilder = Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))

                    val tokenToUse = apiKey.ifBlank {
                        if (_name == "omniroute") AppConfig.DEFAULT_OMNIROUTE_TOKEN else ""
                    }
                    if (tokenToUse.isNotBlank()) {
                        requestBuilder.addHeader("Authorization", "Bearer $tokenToUse")
                    }

                    val httpRequest = requestBuilder.build()
                    val response = client.newCall(httpRequest).execute()
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        if (attemptModel != this@CustomOpenAIProvider.model) {
                            safeLogI("CustomOpenAIProvider", "Fallback recovered on model: $attemptModel")
                            this@CustomOpenAIProvider.model = attemptModel
                        }
                        return@withContext parseResponse(json.parseToJsonElement(responseBody).jsonObject)
                    }

                    // Fallback on recoverable errors (400 catalog error, 402 credits, 404 deprecated, 429 rate limit, 504 timeout)
                    val isRecoverable = response.code in listOf(400, 402, 404, 429, 504)
                    if (isRecoverable && attemptModel != modelsToTry.last()) {
                        safeLogW("CustomOpenAIProvider", "Model $attemptModel failed with HTTP ${response.code}, falling back...")
                        continue
                    }

                    throw ProviderException(formatUserFriendlyError(response.code, responseBody))
                } catch (e: ProviderException) {
                    lastException = e
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    if (attemptModel == modelsToTry.last()) {
                        throw e
                    }
                    safeLogW("CustomOpenAIProvider", "Model $attemptModel encountered ${e.message}, falling back...")
                }
            }

            throw lastException ?: ProviderException("Failed to get completion from $url")
        }

    internal fun formatUserFriendlyError(code: Int, rawBody: String): String {
        val trimmedBody = rawBody.trim()

        // 1. Detect and handle HTML responses (e.g. reverse proxy 504/502/503 errors) cleanly
        if (isHtmlResponse(trimmedBody)) {
            return when (code) {
                504 -> "Gateway timeout (504): The server took too long to respond. Please try again."
                502 -> "Bad gateway (502): The upstream server is temporarily unreachable."
                503 -> "Service unavailable (503): The AI service is overloaded or down for maintenance."
                else -> "Server error ($code): Unexpected HTML response received."
            }
        }

        // 2. Extract error message from JSON safely without throwing IllegalArgumentException
        val extractedMessage: String = if (trimmedBody.isNotBlank()) {
            try {
                val element = json.parseToJsonElement(trimmedBody)
                if (element is JsonObject) {
                    when (val errorElement = element["error"]) {
                        is JsonObject -> errorElement["message"]?.jsonPrimitive?.contentOrNull
                            ?: errorElement["detail"]?.jsonPrimitive?.contentOrNull
                        is JsonPrimitive -> errorElement.contentOrNull
                        else -> null
                    } ?: element["message"]?.jsonPrimitive?.contentOrNull
                    ?: element["detail"]?.jsonPrimitive?.contentOrNull
                    ?: trimmedBody.take(180)
                } else if (element is JsonPrimitive) {
                    element.contentOrNull ?: trimmedBody.take(180)
                } else {
                    trimmedBody.take(180)
                }
            } catch (_: Exception) {
                trimmedBody.take(180)
            }
        } else {
            ""
        }

        val detail = if (extractedMessage.isNotBlank()) ": $extractedMessage" else ""

        // 3. Concise, user-friendly human readable errors
        return when (code) {
            400 -> "Request failed (400): Model unavailable or invalid request parameters$detail"
            401 -> "Authentication failed (401): Please check your API key."
            402 -> "Provider credits exhausted (402)$detail"
            403 -> "Access forbidden (403): You do not have permission to access this model."
            404 -> "Model unavailable or deprecated (404)$detail"
            429 -> "Rate limit reached / cooldown in effect (429)$detail"
            500 -> "Internal server error (500)$detail"
            502 -> "Bad gateway (502)$detail"
            503 -> "Service unavailable (503)$detail"
            504 -> "Gateway timeout (504): The server took too long to respond."
            else -> "API Error ($code)$detail"
        }
    }

    private fun isHtmlResponse(body: String): Boolean {
        val lower = body.lowercase()
        return lower.startsWith("<!doctype html") ||
               lower.startsWith("<html") ||
               lower.contains("<head>") ||
               lower.contains("<title>") ||
               (lower.startsWith("<") && lower.contains("</html>"))
    }

    private fun safeLogI(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: Throwable) {}
    }

    private fun safeLogW(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: Throwable) {}
    }

    override fun stream(request: CompletionRequest): Flow<StreamEvent> = flow {
        try {
            val response = complete(request)
            for (block in response.content) {
                when (block) {
                    is ContentBlock.Text -> emit(StreamEvent.TextDelta(block.text))
                    is ContentBlock.ToolUse -> {
                        emit(StreamEvent.ToolUseStart(block.id, block.name))
                    }
                    else -> {}
                }
            }
            emit(StreamEvent.Complete(response))
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(request: CompletionRequest, targetModel: String = this.model): String {
        toolNameMap.clear()
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", request.systemPrompt)
            })
            for (msg in request.messages) {
                val toolResults = msg.content.filterIsInstance<ContentBlock.ToolResult>()
                val toolCalls = msg.content.filterIsInstance<ContentBlock.ToolUse>()

                if (toolResults.isNotEmpty()) {
                    // OpenAI format: each tool result is a separate message with role: "tool"
                    for (result in toolResults) {
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", result.toolUseId)
                            put("content", result.content)
                        })
                    }
                } else if (toolCalls.isNotEmpty()) {
                    // Assistant message with tool calls
                    add(buildJsonObject {
                        put("role", "assistant")
                        val textContent = msg.content.filterIsInstance<ContentBlock.Text>()
                            .joinToString("") { it.text }
                        if (textContent.isNotBlank()) {
                            put("content", textContent)
                        } else {
                            put("content", JsonNull)
                        }
                        putJsonArray("tool_calls") {
                            for (call in toolCalls) {
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name.replace(".", "_"))
                                        put("arguments", json.encodeToString(JsonObject.serializer(), call.input))
                                    }
                                })
                            }
                        }
                    })
                } else {
                    // Regular user or assistant message
                    add(buildJsonObject {
                        put("role", msg.role.name.lowercase())
                        val hasImages = msg.content.any { it is ContentBlock.Image }
                        if (hasImages) {
                            putJsonArray("content") {
                                for (block in msg.content) {
                                    when (block) {
                                        is ContentBlock.Text -> add(buildJsonObject {
                                            put("type", "text")
                                            put("text", block.text)
                                        })
                                        is ContentBlock.Image -> add(buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", "data:${block.mediaType};base64,${block.base64Data}")
                                            }
                                        })
                                        else -> {}
                                    }
                                }
                            }
                        } else {
                            val textContent = msg.content.filterIsInstance<ContentBlock.Text>()
                                .joinToString("") { it.text }
                            put("content", textContent)
                        }
                    })
                }
            }
        }

        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("model", targetModel)
            put("messages", messages)
            put("max_tokens", request.maxTokens)
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    for (tool in request.tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                val sanitized = tool.name.replace(".", "_")
                                toolNameMap[sanitized] = tool.name
                                put("name", sanitized)
                                put("description", tool.description)
                                putJsonObject("parameters") {
                                    put("type", tool.inputSchema.type)
                                    putJsonObject("properties") {
                                        for ((key, prop) in tool.inputSchema.properties) {
                                            putJsonObject(key) {
                                                put("type", prop.type)
                                                put("description", prop.description)
                                                prop.enum?.let { enumList ->
                                                    if (enumList.isNotEmpty()) {
                                                        putJsonArray("enum") {
                                                            enumList.forEach { add(it) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (tool.inputSchema.required.isNotEmpty()) {
                                        putJsonArray("required") {
                                            tool.inputSchema.required.forEach { add(it) }
                                        }
                                    }
                                }
                            }
                        })
                    }
                }
            }
        })
    }

    private fun parseResponse(obj: JsonObject): CompletionResponse {
        val choices = obj["choices"]?.jsonArray ?: return CompletionResponse(emptyList(), StopReason.ERROR)
        val choice = choices.firstOrNull()?.jsonObject ?: return CompletionResponse(emptyList(), StopReason.ERROR)
        val message = choice["message"]?.jsonObject ?: return CompletionResponse(emptyList(), StopReason.ERROR)

        val blocks = mutableListOf<ContentBlock>()

        message["content"]?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotBlank()) blocks.add(ContentBlock.Text(it))
        }

        message["tool_calls"]?.jsonArray?.forEach { toolCallElement ->
            val toolCall = toolCallElement.jsonObject
            val function = toolCall["function"]?.jsonObject ?: return@forEach
            val input = try {
                json.parseToJsonElement(function["arguments"]?.jsonPrimitive?.content ?: "{}").jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }

            val rawName = function["name"]?.jsonPrimitive?.content ?: ""
            val toolName = toolNameMap[rawName] ?: rawName.replace("_", ".")
            blocks.add(ContentBlock.ToolUse(
                id = toolCall["id"]?.jsonPrimitive?.content ?: "",
                name = toolName,
                input = input
            ))
        }

        val finishReason = choice["finish_reason"]?.jsonPrimitive?.content
        val stopReason = when (finishReason) {
            "tool_calls" -> StopReason.TOOL_USE
            "length" -> StopReason.MAX_TOKENS
            else -> StopReason.END_TURN
        }

        return CompletionResponse(blocks, stopReason)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash-high"
    }
}
