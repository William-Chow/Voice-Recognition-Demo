package com.kotlin.voice

import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the in-app update policy. It decides when the app interrupts someone, which is exactly the
 * kind of rule that should not drift silently during a refactor — and it used to be untestable,
 * buried in a chain of if/else against a live AppUpdateManager.
 */
class UpdatePolicyTest {

    private fun policy(
        priority: Int,
        staleness: Int? = null,
        immediate: Boolean = true,
        flexible: Boolean = true,
    ): Int? = updateFlowFor(priority, staleness, immediate, flexible)

    @Test
    fun `priority 0 never interrupts, however stale the client`() {
        assertNull(policy(0, staleness = 9_999))
    }

    @Test
    fun `priority 5 goes immediate at once, staleness irrelevant`() {
        assertEquals(AppUpdateType.IMMEDIATE, policy(5))
        assertEquals(AppUpdateType.IMMEDIATE, policy(5, staleness = 0))
    }

    @Test
    fun `priority 5 stays silent when immediate is blocked`() {
        // No flexible fallback here on purpose: priority 5 is immediate or nothing.
        assertNull(policy(5, immediate = false))
    }

    @Test
    fun `priority 1 offers the flexible flow straight away`() {
        assertEquals(AppUpdateType.FLEXIBLE, policy(1))
        assertEquals(AppUpdateType.FLEXIBLE, policy(1, staleness = 0))
    }

    @Test
    fun `priority 1 stays silent when flexible is blocked`() {
        assertNull(policy(1, flexible = false))
    }

    @Test
    fun `priority 4 nudges at three days and escalates at five`() {
        assertNull(policy(4, staleness = 2))
        assertEquals(AppUpdateType.FLEXIBLE, policy(4, staleness = 3))
        assertEquals(AppUpdateType.FLEXIBLE, policy(4, staleness = 4))
        assertEquals(AppUpdateType.IMMEDIATE, policy(4, staleness = 5))
    }

    @Test
    fun `priority 3 nudges at fifteen days and escalates at thirty`() {
        assertNull(policy(3, staleness = 14))
        assertEquals(AppUpdateType.FLEXIBLE, policy(3, staleness = 15))
        assertEquals(AppUpdateType.FLEXIBLE, policy(3, staleness = 29))
        assertEquals(AppUpdateType.IMMEDIATE, policy(3, staleness = 30))
    }

    @Test
    fun `priority 2 nudges at thirty days and escalates at ninety`() {
        assertNull(policy(2, staleness = 29))
        assertEquals(AppUpdateType.FLEXIBLE, policy(2, staleness = 30))
        assertEquals(AppUpdateType.FLEXIBLE, policy(2, staleness = 89))
        assertEquals(AppUpdateType.IMMEDIATE, policy(2, staleness = 90))
    }

    @Test
    fun `a staleness Play does not report counts as not stale`() {
        assertNull(policy(4, staleness = null))
        assertNull(policy(3, staleness = null))
        assertNull(policy(2, staleness = null))
        // The two priorities that never consult staleness still act.
        assertEquals(AppUpdateType.IMMEDIATE, policy(5, staleness = null))
        assertEquals(AppUpdateType.FLEXIBLE, policy(1, staleness = null))
    }

    @Test
    fun `a blocked immediate update still gets the flexible flow`() {
        assertEquals(AppUpdateType.FLEXIBLE, policy(4, staleness = 10, immediate = false))
        assertEquals(AppUpdateType.FLEXIBLE, policy(3, staleness = 60, immediate = false))
        assertEquals(AppUpdateType.FLEXIBLE, policy(2, staleness = 200, immediate = false))
    }

    @Test
    fun `nothing happens when the store allows neither flow`() {
        for (priority in 0..5) {
            assertNull(
                "priority $priority should stay silent",
                policy(priority, staleness = 9_999, immediate = false, flexible = false),
            )
        }
    }

    @Test
    fun `a priority Play might add later is treated as do-nothing`() {
        assertNull(policy(6, staleness = 9_999))
        assertNull(policy(-1, staleness = 9_999))
    }

    // Play reports an update the app started as in progress whichever flow it was.
    private fun onResume(
        priority: Int,
        installStatus: Int,
        staleness: Int? = null,
        availability: Int = UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
    ): ResumeAction = resumeActionFor(availability, installStatus, priority, staleness)

    @Test
    fun `a flexible download in progress is left to finish, not made full-screen`() {
        assertEquals(ResumeAction.NONE, onResume(priority = 1, installStatus = InstallStatus.PENDING))
        assertEquals(ResumeAction.NONE, onResume(priority = 1, installStatus = InstallStatus.DOWNLOADING))
        assertEquals(ResumeAction.NONE, onResume(priority = 4, installStatus = InstallStatus.DOWNLOADING, staleness = 3))
    }

    @Test
    fun `a finished flexible download offers the restart`() {
        assertEquals(ResumeAction.PROMPT_RESTART, onResume(priority = 1, installStatus = InstallStatus.DOWNLOADED))
        assertEquals(
            ResumeAction.PROMPT_RESTART,
            onResume(priority = 2, installStatus = InstallStatus.DOWNLOADED, staleness = 30),
        )
    }

    @Test
    fun `an interrupted immediate update is carried to completion`() {
        assertEquals(ResumeAction.RESUME_IMMEDIATE, onResume(priority = 5, installStatus = InstallStatus.DOWNLOADING))
        assertEquals(ResumeAction.RESUME_IMMEDIATE, onResume(priority = 5, installStatus = InstallStatus.DOWNLOADED))
        assertEquals(
            ResumeAction.RESUME_IMMEDIATE,
            onResume(priority = 3, installStatus = InstallStatus.PENDING, staleness = 30),
        )
    }

    @Test
    fun `a flexible update that has since gone stale enough is escalated`() {
        // Started flexible at three days; at five, priority 4 calls for immediate.
        assertEquals(
            ResumeAction.RESUME_IMMEDIATE,
            onResume(priority = 4, installStatus = InstallStatus.DOWNLOADED, staleness = 5),
        )
    }

    @Test
    fun `nothing is resumed when no update is under way`() {
        for (availability in listOf(UpdateAvailability.UPDATE_AVAILABLE, UpdateAvailability.UPDATE_NOT_AVAILABLE)) {
            assertEquals(
                ResumeAction.NONE,
                onResume(priority = 5, installStatus = InstallStatus.UNKNOWN, availability = availability),
            )
        }
    }
}
