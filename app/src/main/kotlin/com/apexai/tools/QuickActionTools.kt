package com.apexai.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Maps turn-by-turn navigation tool for Jarvis.
 */
@Singleton
class NavigationTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "app.navigate"
    override val description = "Start Google Maps turn-by-turn navigation or search a destination."
    override val parameters = ToolParameters(
        properties = mapOf(
            "destination" to ParameterProperty("string", "Destination name or address (e.g. 'Coffee Shop', 'Connaught Place New Delhi')")
        ),
        required = listOf("destination")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val dest = params["destination"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'destination' parameter")
        return try {
            val encodedDest = Uri.encode(dest)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encodedDest")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult.success(buildJsonObject {
                put("navigating_to", dest)
                put("message", "Google Maps navigation started for: $dest")
            })
        } catch (e: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(dest)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
                ToolResult.success(buildJsonObject {
                    put("navigating_to", dest)
                    put("message", "Opened Maps for: $dest")
                })
            } catch (ex: Exception) {
                ToolResult.error("Navigation failed: ${ex.message}")
            }
        }
    }
}

/**
 * YouTube search and play tool for Jarvis.
 */
@Singleton
class YouTubeTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "app.youtube"
    override val description = "Search and play videos or music on YouTube."
    override val parameters = ToolParameters(
        properties = mapOf(
            "query" to ParameterProperty("string", "Search query or video title (e.g. 'Interstellar theme', 'Coldplay')")
        ),
        required = listOf("query")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val query = params["query"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'query' parameter")
        return try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult.success(buildJsonObject {
                put("query", query)
                put("message", "YouTube search opened for: $query")
            })
        } catch (e: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
                ToolResult.success(buildJsonObject {
                    put("query", query)
                    put("message", "Opened YouTube in browser for: $query")
                })
            } catch (ex: Exception) {
                ToolResult.error("YouTube search failed: ${ex.message}")
            }
        }
    }
}
