package com.apexai.tools

import com.apexai.service.ApexAccessibilityService
import org.junit.Assert.*
import org.junit.Test

class M1AccessibilityOptimizationTest {

    @Test
    fun `gesture result states represent correct outcome types and default messages`() {
        val success: ApexAccessibilityService.GestureResultState = ApexAccessibilityService.GestureResultState.Success
        assertNotNull(success)

        val cancelled = ApexAccessibilityService.GestureResultState.Cancelled()
        assertEquals("Gesture cancelled by system", cancelled.reason)

        val customCancelled = ApexAccessibilityService.GestureResultState.Cancelled("User interrupted")
        assertEquals("User interrupted", customCancelled.reason)

        val rejected = ApexAccessibilityService.GestureResultState.Rejected()
        assertEquals("Gesture could not be queued", rejected.reason)

        val customRejected = ApexAccessibilityService.GestureResultState.Rejected("Queue is full")
        assertEquals("Queue is full", customRejected.reason)

        val timedOut = ApexAccessibilityService.GestureResultState.TimedOut()
        assertEquals("Gesture timed out awaiting completion", timedOut.reason)

        val customTimedOut = ApexAccessibilityService.GestureResultState.TimedOut("Exceeded 2000ms")
        assertEquals("Exceeded 2000ms", customTimedOut.reason)
    }

    @Test
    fun `exhaustive pattern matching over all gesture result state variants`() {
        val states: List<ApexAccessibilityService.GestureResultState> = listOf(
            ApexAccessibilityService.GestureResultState.Success,
            ApexAccessibilityService.GestureResultState.Cancelled("cancelled"),
            ApexAccessibilityService.GestureResultState.Rejected("rejected"),
            ApexAccessibilityService.GestureResultState.TimedOut("timed_out")
        )

        val labels = states.map { state ->
            when (state) {
                is ApexAccessibilityService.GestureResultState.Success -> "success"
                is ApexAccessibilityService.GestureResultState.Cancelled -> "cancelled: ${state.reason}"
                is ApexAccessibilityService.GestureResultState.Rejected -> "rejected: ${state.reason}"
                is ApexAccessibilityService.GestureResultState.TimedOut -> "timed_out: ${state.reason}"
            }
        }

        assertEquals(
            listOf(
                "success",
                "cancelled: cancelled",
                "rejected: rejected",
                "timed_out: timed_out"
            ),
            labels
        )
    }

    @Test
    fun `gesture result state equality and hashcode semantics`() {
        val cancelled1 = ApexAccessibilityService.GestureResultState.Cancelled("reason A")
        val cancelled2 = ApexAccessibilityService.GestureResultState.Cancelled("reason A")
        val cancelled3 = ApexAccessibilityService.GestureResultState.Cancelled("reason B")

        assertEquals(cancelled1, cancelled2)
        assertEquals(cancelled1.hashCode(), cancelled2.hashCode())
        assertNotEquals(cancelled1, cancelled3)

        val rejected1 = ApexAccessibilityService.GestureResultState.Rejected("queue full")
        val rejected2 = ApexAccessibilityService.GestureResultState.Rejected("queue full")
        assertEquals(rejected1, rejected2)

        val timedOut1 = ApexAccessibilityService.GestureResultState.TimedOut("timeout")
        val timedOut2 = ApexAccessibilityService.GestureResultState.TimedOut("timeout")
        assertEquals(timedOut1, timedOut2)
    }

    @Test
    fun `accessibility action command constant is preserved`() {
        assertEquals("com.apexai.ACCESSIBILITY_ACTION", ApexAccessibilityService.ACTION_COMMAND)
    }

    @Test
    fun `coordinate normalization and sequence parsing work correctly`() {
        val service = ApexAccessibilityService()

        // Test raw coordinates pass-through
        val raw = service.toScreenCoords(100, 200, normalized = false)
        assertEquals(Pair(100, 200), raw)

        // Test normalized coordinates with default 1080x2400
        val center = service.toScreenCoords(500, 500, normalized = true)
        assertEquals(Pair(540, 1200), center)

        val topLeft = service.toScreenCoords(0, 0, normalized = true)
        assertEquals(Pair(0, 0), topLeft)

        val bottomRight = service.toScreenCoords(1000, 1000, normalized = true)
        assertEquals(Pair(1079, 2399), bottomRight)

        // Test out-of-range clamping
        val clampedOver = service.toScreenCoords(1500, 2000, normalized = true)
        assertEquals(Pair(1079, 2399), clampedOver)

        val clampedUnder = service.toScreenCoords(-100, -500, normalized = true)
        assertEquals(Pair(0, 0), clampedUnder)

        // Test JSON sequence parsing
        val jsonSeq = service.parsePointsSequence("[[100, 200], [300, 400], [500, 600]]")
        assertEquals(3, jsonSeq.size)
        assertEquals(Pair(100, 200), jsonSeq[0])
        assertEquals(Pair(300, 400), jsonSeq[1])
        assertEquals(Pair(500, 600), jsonSeq[2])

        // Test fallback delimiter parsing ("x1,y1;x2,y2")
        val delimSeq = service.parsePointsSequence("150,250; 350,450 | 550,650")
        assertEquals(3, delimSeq.size)
        assertEquals(Pair(150, 250), delimSeq[0])
        assertEquals(Pair(350, 450), delimSeq[1])
        assertEquals(Pair(550, 650), delimSeq[2])

        // Test single point string
        val singleSeq = service.parsePointsSequence("123,456")
        assertEquals(1, singleSeq.size)
        assertEquals(Pair(123, 456), singleSeq[0])

        // Test whitespace with mixed delimiters
        val whitespaceSeq = service.parsePointsSequence("  100 , 200 ;  300 , 400  ")
        assertEquals(2, whitespaceSeq.size)
        assertEquals(Pair(100, 200), whitespaceSeq[0])
        assertEquals(Pair(300, 400), whitespaceSeq[1])

        // Test empty, whitespace-only and invalid strings handled gracefully
        val emptySeq = service.parsePointsSequence("")
        assertTrue(emptySeq.isEmpty())

        val blankSeq = service.parsePointsSequence("   \t\n  ")
        assertTrue(blankSeq.isEmpty())

        val malformedSeq = service.parsePointsSequence("invalid_json_and_not_points")
        assertTrue(malformedSeq.isEmpty())
    }
}
