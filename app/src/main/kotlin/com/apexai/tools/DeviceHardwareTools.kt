package com.apexai.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flashlight hardware control tool for Jarvis.
 */
@Singleton
class FlashlightTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "device.flashlight"
    override val description = "Control the device flashlight (turn on, turn off, or toggle)."
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Action to perform: 'on', 'off', or 'toggle'", enum = listOf("on", "off", "toggle"))
        ),
        required = listOf("action")
    )
    override val requiresApproval = false

    private var isTorchOn = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "toggle"
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.error("No camera with flash found on this device")

            val targetState = when (action.lowercase()) {
                "on" -> true
                "off" -> false
                "toggle" -> !isTorchOn
                else -> !isTorchOn
            }

            cameraManager.setTorchMode(cameraId, targetState)
            isTorchOn = targetState

            ToolResult.success(buildJsonObject {
                put("flashlight_on", targetState)
                put("message", if (targetState) "Flashlight turned on" else "Flashlight turned off")
            })
        } catch (e: Exception) {
            ToolResult.error("Flashlight control failed: ${e.message}")
        }
    }
}

/**
 * Audio volume control tool for Jarvis.
 */
@Singleton
class VolumeTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "device.volume"
    override val description = "Adjust or query device volume levels (media, ring, alarm, notification)."
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Action: 'set', 'up', 'down', 'mute', 'unmute', 'get'", enum = listOf("set", "up", "down", "mute", "unmute", "get")),
            "stream" to ParameterProperty("string", "Stream type: 'media', 'ring', 'alarm', 'notification'", enum = listOf("media", "ring", "alarm", "notification")),
            "percent" to ParameterProperty("integer", "Volume percentage (0 to 100) when action is 'set'")
        ),
        required = listOf("action")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "get"
        val streamStr = params["stream"]?.jsonPrimitive?.contentOrNull ?: "media"
        val percent = params["percent"]?.jsonPrimitive?.intOrNull

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = when (streamStr.lowercase()) {
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "notification" -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_MUSIC
            }

            val maxVol = audioManager.getStreamMaxVolume(streamType)

            when (action.lowercase()) {
                "set" -> {
                    if (percent == null) return ToolResult.error("Missing 'percent' parameter for volume set")
                    val targetIndex = (percent.coerceIn(0, 100) * maxVol) / 100
                    audioManager.setStreamVolume(streamType, targetIndex, AudioManager.FLAG_SHOW_UI)
                }
                "up" -> audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down" -> audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute" -> audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "unmute" -> audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "get" -> {}
                else -> return ToolResult.error("Unknown volume action: $action")
            }

            val currentVol = audioManager.getStreamVolume(streamType)
            val currentPct = if (maxVol > 0) (currentVol * 100) / maxVol else 0

            ToolResult.success(buildJsonObject {
                put("stream", streamStr)
                put("volume_percent", currentPct)
                put("volume_index", currentVol)
                put("max_volume", maxVol)
            })
        } catch (e: Exception) {
            ToolResult.error("Volume control failed: ${e.message}")
        }
    }
}

/**
 * Media playback control tool for Jarvis (Spotify, YouTube, Music players).
 */
@Singleton
class MediaControlTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "device.media"
    override val description = "Control media playback (Spotify, YouTube, Music apps): play, pause, toggle, next, previous."
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Action: 'play', 'pause', 'toggle', 'next', 'previous'", enum = listOf("play", "pause", "toggle", "next", "previous"))
        ),
        required = listOf("action")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: "toggle"
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val keyCode = when (action.lowercase()) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }

            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)

            ToolResult.success(buildJsonObject {
                put("action", action)
                put("executed", true)
                put("message", "Media command '$action' sent successfully")
            })
        } catch (e: Exception) {
            ToolResult.error("Media command failed: ${e.message}")
        }
    }
}

/**
 * System diagnostic and status tool for Jarvis.
 */
@Singleton
class SystemStatusTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "device.status"
    override val description = "Get full system diagnostic report: battery %, RAM usage, storage space, and network status."
    override val parameters = ToolParameters()
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val result = buildJsonObject {
                // Battery
                val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                val batteryPct = if (scale > 0) (level * 100) / scale else level

                putJsonObject("battery") {
                    put("percentage", batteryPct)
                    put("charging", isCharging)
                    put("status", when (status) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                        BatteryManager.BATTERY_STATUS_FULL -> "full"
                        else -> "unknown"
                    })
                }

                // RAM
                val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                actManager.getMemoryInfo(memInfo)
                val totalRamMb = memInfo.totalMem / (1024 * 1024)
                val availRamMb = memInfo.availMem / (1024 * 1024)
                val usedRamMb = totalRamMb - availRamMb
                val ramPctUsed = if (totalRamMb > 0) (usedRamMb * 100) / totalRamMb else 0

                putJsonObject("memory_ram") {
                    put("total_mb", totalRamMb)
                    put("available_mb", availRamMb)
                    put("used_mb", usedRamMb)
                    put("percent_used", ramPctUsed)
                    put("low_memory", memInfo.lowMemory)
                }

                // Storage
                val stat = StatFs(Environment.getDataDirectory().path)
                val totalStorageGb = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024 * 1024)
                val freeStorageGb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)

                putJsonObject("storage") {
                    put("total_gb", totalStorageGb)
                    put("free_gb", freeStorageGb)
                    put("used_gb", totalStorageGb - freeStorageGb)
                }

                // Network
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = cm.activeNetwork
                val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

                putJsonObject("network") {
                    put("connected", activeNetwork != null)
                    put("type", if (isWifi) "WiFi" else if (isCellular) "Cellular" else "Disconnected")
                }
            }
            ToolResult.success(result)
        } catch (e: Exception) {
            ToolResult.error("Failed to query system status: ${e.message}")
        }
    }
}
