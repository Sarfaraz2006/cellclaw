package com.cellclaw.tools

import com.cellclaw.service.CellClawAccessibility
import org.junit.Assert.*
import org.junit.Test

class M1AccessibilityOptimizationTest {

    @Test
    fun `gesture result states represent correct outcome types and default messages`() {
        val success: CellClawAccessibility.GestureResultState = CellClawAccessibility.GestureResultState.Success
        assertNotNull(success)

        val cancelled = CellClawAccessibility.GestureResultState.Cancelled()
        assertEquals("Gesture cancelled by system", cancelled.reason)

        val customCancelled = CellClawAccessibility.GestureResultState.Cancelled("User interrupted")
        assertEquals("User interrupted", customCancelled.reason)

        val rejected = CellClawAccessibility.GestureResultState.Rejected()
        assertEquals("Gesture could not be queued", rejected.reason)

        val customRejected = CellClawAccessibility.GestureResultState.Rejected("Queue is full")
        assertEquals("Queue is full", customRejected.reason)

        val timedOut = CellClawAccessibility.GestureResultState.TimedOut()
        assertEquals("Gesture timed out awaiting completion", timedOut.reason)

        val customTimedOut = CellClawAccessibility.GestureResultState.TimedOut("Exceeded 2000ms")
        assertEquals("Exceeded 2000ms", customTimedOut.reason)
    }

    @Test
    fun `exhaustive pattern matching over all gesture result state variants`() {
        val states: List<CellClawAccessibility.GestureResultState> = listOf(
            CellClawAccessibility.GestureResultState.Success,
            CellClawAccessibility.GestureResultState.Cancelled("cancelled"),
            CellClawAccessibility.GestureResultState.Rejected("rejected"),
            CellClawAccessibility.GestureResultState.TimedOut("timed_out")
        )

        val labels = states.map { state ->
            when (state) {
                is CellClawAccessibility.GestureResultState.Success -> "success"
                is CellClawAccessibility.GestureResultState.Cancelled -> "cancelled: ${state.reason}"
                is CellClawAccessibility.GestureResultState.Rejected -> "rejected: ${state.reason}"
                is CellClawAccessibility.GestureResultState.TimedOut -> "timed_out: ${state.reason}"
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
        val cancelled1 = CellClawAccessibility.GestureResultState.Cancelled("reason A")
        val cancelled2 = CellClawAccessibility.GestureResultState.Cancelled("reason A")
        val cancelled3 = CellClawAccessibility.GestureResultState.Cancelled("reason B")

        assertEquals(cancelled1, cancelled2)
        assertEquals(cancelled1.hashCode(), cancelled2.hashCode())
        assertNotEquals(cancelled1, cancelled3)

        val rejected1 = CellClawAccessibility.GestureResultState.Rejected("queue full")
        val rejected2 = CellClawAccessibility.GestureResultState.Rejected("queue full")
        assertEquals(rejected1, rejected2)

        val timedOut1 = CellClawAccessibility.GestureResultState.TimedOut("timeout")
        val timedOut2 = CellClawAccessibility.GestureResultState.TimedOut("timeout")
        assertEquals(timedOut1, timedOut2)
    }

    @Test
    fun `accessibility action command constant is preserved`() {
        assertEquals("com.cellclaw.ACCESSIBILITY_ACTION", CellClawAccessibility.ACTION_COMMAND)
    }
}
