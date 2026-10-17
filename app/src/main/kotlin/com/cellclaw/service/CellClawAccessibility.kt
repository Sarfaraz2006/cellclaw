package com.cellclaw.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream

class CellClawAccessibility : AccessibilityService() {

    private var screenWidth = 1080
    private var screenHeight = 2400

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val actionReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_COMMAND) return

            val resultReceiver = intent.getParcelableExtra<ResultReceiver>("result_receiver")
            val action = intent.getStringExtra("action") ?: return

            Log.d(TAG, "Received action: $action")

            serviceScope.launch {
                try {
                    val result = when (action) {
                        "tap" -> {
                            val isNorm = intent.getBooleanExtra("normalized", false)
                            val rawX = intent.getIntExtra("x", 0)
                            val rawY = intent.getIntExtra("y", 0)
                            val (x, y) = toScreenCoords(rawX, rawY, isNorm)
                            handleTap(x, y, intent.getStringExtra("text"))
                        }
                        "tap_and_read" -> {
                            val isNorm = intent.getBooleanExtra("normalized", false)
                            val rawX = intent.getIntExtra("x", 0)
                            val rawY = intent.getIntExtra("y", 0)
                            val (x, y) = toScreenCoords(rawX, rawY, isNorm)
                            handleTapAndRead(
                                x,
                                y,
                                intent.getStringExtra("text"),
                                intent.getLongExtra("settle_ms", 150L)
                            )
                        }
                        "click_sequence" -> {
                            val seqStr = intent.getStringExtra("sequence") ?: "[]"
                            val delayMs = intent.getLongExtra("delay_ms", 50L)
                            val isNorm = intent.getBooleanExtra("normalized", false)
                            val points = parsePointsSequence(seqStr)
                            handleSequence(points, delayMs, isNorm)
                        }
                        "type" -> handleType(intent.getStringExtra("text") ?: "")
                        "swipe" -> handleSwipe(intent.getStringExtra("direction") ?: "right")
                        "scroll" -> handleScroll(intent.getStringExtra("direction") ?: "down")
                        "back" -> {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            successResult("back", "Pressed back button")
                        }
                        "home" -> {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            successResult("home", "Pressed home button")
                        }
                        "recents" -> {
                            performGlobalAction(GLOBAL_ACTION_RECENTS)
                            successResult("recents", "Opened recents")
                        }
                        "read_screen" -> {
                            val incScreenshot = intent.getBooleanExtra("include_screenshot", false)
                            readScreen(incScreenshot)
                        }
                        "screenshot_and_read", "atomic_snapshot" -> readScreen(true)
                        "find_element" -> findElement(intent.getStringExtra("text") ?: "")
                        "screenshot" -> {
                            handleScreenshot(resultReceiver)
                            null // Result sent asynchronously by handleScreenshot
                        }
                        "wait_and_read" -> {
                            // Trimmed default delay from 500ms to 150ms
                            val delayMs = intent.getLongExtra("delay_ms", 150L)
                            delay(delayMs)
                            readScreen(false)
                        }
                        else -> buildJsonObject {
                            put("error", "Unknown action: $action")
                        }
                    }

                    if (result != null) {
                        sendResult(resultReceiver, result)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling action $action", e)
                    sendResult(resultReceiver, errorResult("Action failed: ${e.message}"))
                }
            }
        }
    }

    private fun sendResult(receiver: ResultReceiver?, result: JsonObject) {
        receiver?.send(0, Bundle().apply {
            putString("result", result.toString())
        })
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateScreenDimensions()

        val filter = IntentFilter(ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // RECEIVER_EXPORTED so broadcasts from the test process (different PID) can reach us.
            // Safety: we only act on intents with our specific action + setPackage.
            registerReceiver(actionReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }

        AccessibilityBridge.onServiceConnected()
        Log.d(TAG, "Accessibility service connected (${screenWidth}x${screenHeight})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        serviceScope.cancel()
        AccessibilityBridge.onServiceDisconnected()
        try {
            unregisterReceiver(actionReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering actionReceiver", e)
        }
        super.onDestroy()
    }

    private fun updateScreenDimensions() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }
    }

    // ── Asynchronous Gesture Support ─────────────────────────────────────

    sealed class GestureResultState {
        data object Success : GestureResultState()
        data class Cancelled(val reason: String = "Gesture cancelled by system") : GestureResultState()
        data class Rejected(val reason: String = "Gesture could not be queued") : GestureResultState()
        data class TimedOut(val reason: String = "Gesture timed out awaiting completion") : GestureResultState()
    }

    private suspend fun dispatchGestureAwait(
        gesture: GestureDescription,
        timeoutMs: Long = 2000L
    ): GestureResultState {
        val deferred = CompletableDeferred<GestureResultState>()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(GestureResultState.Success)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(GestureResultState.Cancelled())
            }
        }

        val queued = dispatchGesture(gesture, callback, null)
        if (!queued) {
            return GestureResultState.Rejected()
        }

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            GestureResultState.TimedOut()
        }
    }

    // ── Resilient Active Window Acquisition (Artemis 3-Tier Multi-Window Penetration) ──

    private suspend fun getActiveRootNodeWithRetry(maxAttempts: Int = 5, intervalMs: Long = 40L): AccessibilityNodeInfo? {
        for (i in 1..maxAttempts) {
            // Tier 1: Multi-window enumeration sorted by layer descending (captures Dialogs, Popups, IME)
            try {
                val windowList = windows
                if (!windowList.isNullOrEmpty()) {
                    val sorted = windowList.sortedByDescending { it.layer }
                    for (w in sorted) {
                        val root = if (Build.VERSION.SDK_INT >= 33) {
                            try {
                                w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
                            } catch (_: Throwable) {
                                w.root
                            }
                        } else {
                            w.root
                        }
                        if (root != null) return root
                    }
                }
            } catch (_: Throwable) {}

            // Tier 2: rootInActiveWindow fallback
            val root = rootInActiveWindow
            if (root != null) return root

            // Tier 3: Focused node backtracking (recovers window tree during transient animations/transitions)
            try {
                val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                if (focused != null) {
                    var current = focused
                    var parent = current.parent
                    while (parent != null) {
                        current = parent
                        parent = current.parent
                    }
                    return current
                }
            } catch (_: Throwable) {}

            if (i < maxAttempts) {
                delay(intervalMs)
            }
        }
        return null
    }

    // ── Coordinate Normalization & Chained Sequence Support (Artemis Paradigm) ──

    fun toScreenCoords(nx: Int, ny: Int, normalized: Boolean): Pair<Int, Int> {
        return if (normalized) {
            val px = (nx * screenWidth / 1000).coerceIn(0, screenWidth - 1)
            val py = (ny * screenHeight / 1000).coerceIn(0, screenHeight - 1)
            Pair(px, py)
        } else {
            Pair(nx, ny)
        }
    }

    fun parsePointsSequence(seqStr: String): List<Pair<Int, Int>> {
        val points = mutableListOf<Pair<Int, Int>>()
        try {
            val element = Json.parseToJsonElement(seqStr)
            if (element is JsonArray) {
                for (item in element) {
                    if (item is JsonArray && item.size >= 2) {
                        val x = item[0].jsonPrimitive.intOrNull ?: 0
                        val y = item[1].jsonPrimitive.intOrNull ?: 0
                        points.add(Pair(x, y))
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback comma/semicolon parser: "500,500;600,600"
            val parts = seqStr.split(';', '|')
            for (p in parts) {
                val coords = p.trim().split(',', ' ')
                if (coords.size >= 2) {
                    val x = coords[0].trim().toIntOrNull() ?: continue
                    val y = coords[1].trim().toIntOrNull() ?: continue
                    points.add(Pair(x, y))
                }
            }
        }
        return points
    }

    private suspend fun handleSequence(
        points: List<Pair<Int, Int>>,
        delayMs: Long = 50L,
        normalized: Boolean = false
    ): JsonObject {
        if (points.isEmpty()) return errorResult("Empty sequence points")
        val executed = mutableListOf<String>()

        for (i in points.indices) {
            val pt = points[i]
            val (x, y) = toScreenCoords(pt.first, pt.second, normalized)
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            val state = dispatchGestureAwait(gesture)
            if (state !is GestureResultState.Success) {
                return errorResult("Sequence failed at step ${i + 1} ($x, $y): ${state::class.simpleName}")
            }
            executed.add("($x, $y)")
            if (i < points.size - 1 && delayMs > 0) {
                delay(delayMs)
            }
        }

        return buildJsonObject {
            put("success", true)
            put("action", "click_sequence")
            put("message", "Executed sequence of ${points.size} clicks")
            putJsonArray("executed") {
                executed.forEach { add(it) }
            }
        }
    }

    // ── Tap ──────────────────────────────────────────────────────────────

    private suspend fun handleTap(x: Int, y: Int, text: String?): JsonObject {
        if (text != null) {
            val rootNode = getActiveRootNodeWithRetry() ?: return errorResult("No active window")
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (nodes.isNullOrEmpty()) return errorResult("Element not found: '$text'")

            val node = nodes.first()
            val clickable = findClickableParent(node) ?: node
            val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                return successResult("tap", "Tapped on '$text'")
            }

            // Fallback: node click failed; inject coordinate tap at center of bounds
            val bounds = Rect()
            clickable.getBoundsInScreen(bounds)
            if (bounds.isEmpty) {
                node.getBoundsInScreen(bounds)
            }

            if (!bounds.isEmpty && bounds.centerX() > 0 && bounds.centerY() > 0) {
                val cx = bounds.centerX()
                val cy = bounds.centerY()
                val path = Path().apply { moveTo(cx.toFloat(), cy.toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50)) // Trimmed to 50ms
                    .build()

                return when (val state = dispatchGestureAwait(gesture)) {
                    is GestureResultState.Success -> successResult("tap", "Tapped on '$text' via coordinate fallback ($cx, $cy)")
                    is GestureResultState.Cancelled -> errorResult("Gesture cancelled for '$text' fallback: ${state.reason}")
                    is GestureResultState.Rejected -> errorResult("Gesture rejected for '$text' fallback: ${state.reason}")
                    is GestureResultState.TimedOut -> errorResult("Gesture timed out for '$text' fallback: ${state.reason}")
                }
            }

            return errorResult("Click action failed on '$text' and coordinate bounds unavailable")
        }

        // Tap at coordinates
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)) // Trimmed from 100ms to 50ms
            .build()

        return when (val state = dispatchGestureAwait(gesture)) {
            is GestureResultState.Success -> successResult("tap", "Tapped at ($x, $y)")
            is GestureResultState.Cancelled -> errorResult("Gesture cancelled at ($x, $y): ${state.reason}")
            is GestureResultState.Rejected -> errorResult("Gesture rejected at ($x, $y): ${state.reason}")
            is GestureResultState.TimedOut -> errorResult("Gesture timed out at ($x, $y): ${state.reason}")
        }
    }

    // ── Composite Tap and Read ───────────────────────────────────────────

    private suspend fun handleTapAndRead(
        x: Int,
        y: Int,
        text: String?,
        settleMs: Long = 150L
    ): JsonObject {
        val tapResult = handleTap(x, y, text)
        val tapSuccess = tapResult["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!tapSuccess) {
            val tapError = tapResult["error"]?.jsonPrimitive?.contentOrNull ?: "Tap failed"
            return buildJsonObject {
                put("success", false)
                put("action", "tap_and_read")
                put("error", tapError)
                put("tap_result", tapResult)
            }
        }

        val settleDelay = if (settleMs > 0) settleMs else 150L
        delay(settleDelay)

        val screenResult = readScreen()
        return buildJsonObject {
            put("success", true)
            put("action", "tap_and_read")
            put("tap_result", tapResult)
            for ((key, value) in screenResult) {
                if (key != "action") {
                    put(key, value)
                }
            }
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        while (true) {
            if (current.isClickable) return current
            current = current.parent ?: return null
        }
    }

    // ── Type ─────────────────────────────────────────────────────────────

    private fun handleType(text: String): JsonObject {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return errorResult("No focused input field")
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return successResult("type", "Typed '${text.take(50)}...'")
    }

    // ── Swipe ────────────────────────────────────────────────────────────

    private suspend fun handleSwipe(direction: String): JsonObject {
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val swipeDistance = screenWidth * 0.6f
        val duration = 200L // Trimmed from 300ms to 200ms

        val (startX, startY, endX, endY) = when (direction) {
            "left" -> listOf(centerX + 100f, centerY, centerX - swipeDistance, centerY - 50f)
            "right" -> listOf(centerX - 100f, centerY, centerX + swipeDistance, centerY - 50f)
            "up" -> listOf(centerX, centerY + 200f, centerX, centerY - swipeDistance)
            "down" -> listOf(centerX, centerY - 200f, centerX, centerY + swipeDistance)
            else -> return errorResult("Invalid swipe direction: $direction")
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        return when (val state = dispatchGestureAwait(gesture)) {
            is GestureResultState.Success -> {
                Log.d(TAG, "Swipe $direction completed")
                successResult("swipe", "Swiped $direction")
            }
            is GestureResultState.Cancelled -> {
                Log.w(TAG, "Swipe $direction cancelled: ${state.reason}")
                errorResult("Swipe $direction cancelled: ${state.reason}")
            }
            is GestureResultState.Rejected -> {
                Log.w(TAG, "Swipe $direction rejected: ${state.reason}")
                errorResult("Swipe $direction rejected: ${state.reason}")
            }
            is GestureResultState.TimedOut -> {
                Log.w(TAG, "Swipe $direction timed out: ${state.reason}")
                errorResult("Swipe $direction timed out: ${state.reason}")
            }
        }
    }

    // ── Scroll ───────────────────────────────────────────────────────────

    private suspend fun handleScroll(direction: String): JsonObject {
        val rootNode = getActiveRootNodeWithRetry() ?: return errorResult("No active window")
        val scrollableNode = findScrollableNode(rootNode)

        if (scrollableNode != null) {
            when (direction) {
                "down" -> scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                "up" -> scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                "left", "right" -> {
                    return handleSwipe(direction)
                }
            }
            return successResult("scroll", "Scrolled $direction")
        }

        // Fallback: gesture-based scroll
        val centerX = screenWidth / 2f
        val scrollDist = screenHeight * 0.3f
        val (startY, endY) = when (direction) {
            "down" -> Pair(screenHeight / 2f + scrollDist / 2, screenHeight / 2f - scrollDist / 2)
            "up" -> Pair(screenHeight / 2f - scrollDist / 2, screenHeight / 2f + scrollDist / 2)
            else -> return errorResult("Invalid scroll direction: $direction")
        }

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250)) // Trimmed from 500ms to 250ms
            .build()

        return when (val state = dispatchGestureAwait(gesture)) {
            is GestureResultState.Success -> successResult("scroll", "Scrolled $direction (gesture)")
            is GestureResultState.Cancelled -> errorResult("Scroll $direction cancelled: ${state.reason}")
            is GestureResultState.Rejected -> errorResult("Scroll $direction rejected: ${state.reason}")
            is GestureResultState.TimedOut -> errorResult("Scroll $direction timed out: ${state.reason}")
        }
    }

    // ── Read Screen (Artemis Enhanced Atomic Snapshot & Semantic Extraction) ──

    private suspend fun captureScreenshotAsync(): String? = withTimeoutOrNull(2500L) {
        val deferred = CompletableDeferred<String?>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                val screenshotsDir = File(cacheDir, "screenshots").apply { mkdirs() }
                val file = File(screenshotsDir, "screenshot_${System.currentTimeMillis()}.png")
                val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", file.absolutePath))
                val exitCode = proc.waitFor()
                if (exitCode == 0 && file.exists()) {
                    deferred.complete(file.absolutePath)
                } else {
                    deferred.complete(null)
                }
            } catch (_: Exception) {
                deferred.complete(null)
            }
        } else {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        if (bitmap != null) {
                            val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            val screenshotsDir = File(cacheDir, "screenshots").apply { mkdirs() }
                            val file = File(screenshotsDir, "screenshot_${System.currentTimeMillis()}.png")
                            FileOutputStream(file).use { out ->
                                softBitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                            }
                            softBitmap.recycle()
                            bitmap.recycle()
                            result.hardwareBuffer.close()
                            deferred.complete(file.absolutePath)
                        } else {
                            result.hardwareBuffer.close()
                            deferred.complete(null)
                        }
                    } catch (_: Exception) {
                        deferred.complete(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    deferred.complete(null)
                }
            })
        }
        deferred.await()
    }

    private suspend fun readScreen(includeScreenshot: Boolean = false): JsonObject {
        val screenshotJob = if (includeScreenshot) {
            serviceScope.async { captureScreenshotAsync() }
        } else null

        val rootNode = getActiveRootNodeWithRetry() ?: return buildJsonObject {
            put("error", "No active window")
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
        }

        val elements = mutableListOf<JsonObject>()
        traverseNode(rootNode, elements)

        val screenshotPath = screenshotJob?.await()

        return buildJsonObject {
            put("package", rootNode.packageName?.toString() ?: "unknown")
            put("screen_width", screenWidth)
            put("screen_height", screenHeight)
            put("element_count", elements.size)
            if (screenshotPath != null) {
                put("screenshot_path", screenshotPath)
                put("has_screenshot", true)
            }
            putJsonArray("elements") {
                elements.forEach { add(it) }
            }
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, elements: MutableList<JsonObject>, depth: Int = 0) {
        if (depth > 25 || elements.size >= 500) return

        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Clip bounds to display so center points are guaranteed visible on-screen
        bounds.intersect(0, 0, screenWidth, screenHeight)

        // Include elements that have text, content description, or are interactive
        if (text != null || contentDesc != null || node.isClickable || node.isEditable) {
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            val normX = if (screenWidth > 0) (cx * 1000 / screenWidth).coerceIn(0, 1000) else 0
            val normY = if (screenHeight > 0) (cy * 1000 / screenHeight).coerceIn(0, 1000) else 0

            elements.add(buildJsonObject {
                put("index", elements.size)
                put("type", className)
                if (text != null) put("text", text)
                if (contentDesc != null) put("desc", contentDesc)
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("scrollable", node.isScrollable)
                put("checked", node.isChecked)
                put("enabled", node.isEnabled)

                // Artemis rich semantics
                node.error?.let { put("error", it.toString()) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    if (node.isHeading) put("is_heading", true)
                    node.paneTitle?.let { put("pane_title", it.toString()) }
                    node.tooltipText?.let { put("tooltip", it.toString()) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.stateDescription?.let { put("state_desc", it.toString()) }
                }

                // Bounds in physical pixels & normalized 0-1000 scale
                putJsonObject("bounds") {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                    put("centerX", cx)
                    put("centerY", cy)
                    put("normX", normX)
                    put("normY", normY)
                }
            })
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = if (Build.VERSION.SDK_INT >= 33) {
                try {
                    node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
                } catch (_: Throwable) {
                    node.getChild(i)
                }
            } else {
                node.getChild(i)
            } ?: continue
            traverseNode(child, elements, depth + 1)
        }
    }

    // ── Find Element ─────────────────────────────────────────────────────

    private suspend fun findElement(text: String): JsonObject {
        val rootNode = getActiveRootNodeWithRetry() ?: return errorResult("No active window")
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)

        if (nodes.isNullOrEmpty()) return buildJsonObject {
            put("found", false)
            put("query", text)
        }

        return buildJsonObject {
            put("found", true)
            put("query", text)
            put("count", nodes.size)
            putJsonArray("matches") {
                for (node in nodes.take(10)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    add(buildJsonObject {
                        put("text", node.text?.toString() ?: "")
                        put("desc", node.contentDescription?.toString() ?: "")
                        put("clickable", node.isClickable)
                        put("centerX", bounds.centerX())
                        put("centerY", bounds.centerY())
                    })
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val scrollable = findScrollableNode(child)
            if (scrollable != null) return scrollable
        }
        return null
    }

    private fun successResult(action: String, message: String) = buildJsonObject {
        put("success", true)
        put("action", action)
        put("message", message)
    }

    private fun errorResult(message: String) = buildJsonObject {
        put("success", false)
        put("error", message)
    }

    // ── Screenshot ─────────────────────────────────────────────────────

    private fun handleScreenshot(resultReceiver: ResultReceiver?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 26-29: fallback to screencap shell command
            try {
                val screenshotsDir = File(cacheDir, "screenshots").apply { mkdirs() }
                val file = File(screenshotsDir, "screenshot_${System.currentTimeMillis()}.png")
                val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", file.absolutePath))
                val exitCode = proc.waitFor()
                if (exitCode == 0 && file.exists()) {
                    sendResult(resultReceiver, buildJsonObject {
                        put("success", true)
                        put("file_path", file.absolutePath)
                    })
                } else {
                    sendResult(resultReceiver, errorResult("screencap failed with exit code $exitCode"))
                }
            } catch (e: Exception) {
                sendResult(resultReceiver, errorResult("Screenshot failed: ${e.message}"))
            }
            return
        }

        // API 30+: use AccessibilityService.takeScreenshot
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    if (bitmap == null) {
                        sendResult(resultReceiver, errorResult("Failed to create bitmap from screenshot"))
                        result.hardwareBuffer.close()
                        return
                    }

                    // Downscale to 540px width to keep base64 size reasonable
                    val scale = 540f / bitmap.width
                    val scaledWidth = 540
                    val scaledHeight = (bitmap.height * scale).toInt()
                    val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val scaled = Bitmap.createScaledBitmap(softBitmap, scaledWidth, scaledHeight, true)

                    val screenshotsDir = File(cacheDir, "screenshots").apply { mkdirs() }
                    val file = File(screenshotsDir, "screenshot_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { out ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 85, out)
                    }

                    if (scaled !== softBitmap) scaled.recycle()
                    softBitmap.recycle()
                    bitmap.recycle()
                    result.hardwareBuffer.close()

                    sendResult(resultReceiver, buildJsonObject {
                        put("success", true)
                        put("file_path", file.absolutePath)
                    })
                } catch (e: Exception) {
                    sendResult(resultReceiver, errorResult("Screenshot save failed: ${e.message}"))
                }
            }

            override fun onFailure(errorCode: Int) {
                sendResult(resultReceiver, errorResult("Screenshot failed with error code $errorCode"))
            }
        })
    }

    companion object {
        private const val TAG = "CellClawA11y"
        const val ACTION_COMMAND = "com.cellclaw.ACCESSIBILITY_ACTION"
    }
}
