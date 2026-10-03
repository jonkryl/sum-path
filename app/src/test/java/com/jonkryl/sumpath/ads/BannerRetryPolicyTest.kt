package com.jonkryl.sumpath.ads

import org.junit.Assert.*
import org.junit.Test

class BannerRetryPolicyTest {
    @Test fun offlineCyclesAreBoundedAndNeverParallel() {
        val policy = BannerRetryPolicy()
        assertTrue(policy.tryStart(0))
        assertFalse(policy.tryStart(0))
        assertNull(policy.remainingDelay(0))
        assertEquals(15_000L, policy.failed(0))
        assertNull(policy.failed(0))
        assertFalse(policy.tryStart(14_999))
        assertTrue(policy.tryStart(15_000))
        assertEquals(45_000L, policy.failed(15_000))
        assertFalse(policy.tryStart(59_999))
        assertTrue(policy.tryStart(60_000))
        assertEquals(300_000L, policy.failed(60_000))
        assertEquals(250_000L, policy.remainingDelay(110_000))
        assertFalse(policy.tryStart(359_999))
        assertTrue(policy.tryStart(360_000))
        assertEquals(15_000L, policy.failed(360_000))
    }

    @Test fun networkRecoveryStopsScheduledReloadsAfterSuccess() {
        val policy = BannerRetryPolicy()
        assertTrue(policy.tryStart(100))
        assertEquals(15_000L, policy.failed(100))
        assertTrue(policy.tryStart(15_100))
        policy.succeeded()
        assertNull(policy.remainingDelay(1_000_000))
        assertFalse(policy.tryStart(1_000_000))
        assertNull(policy.failed(1_000_000))
    }

    @Test fun deliberatePrivacyChangeResetsAnOldFailureOrRequest() {
        val policy = BannerRetryPolicy()
        assertTrue(policy.tryStart(50))
        policy.failed(50)
        policy.reset()
        assertEquals(0L, policy.remainingDelay(51))
        assertTrue(policy.tryStart(51))
        policy.reset()
        assertTrue(policy.tryStart(52))
    }
}
