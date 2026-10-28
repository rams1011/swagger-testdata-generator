package com.tx.api.test.generator.studio.tenant;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginGuardTest {

    private final Instant now = Instant.parse("2026-10-27T20:00:00Z");

    @Test
    void fifthFailureLocksAndALaterWindowStartsOver() {
        LoginGuard.State state = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            LoginGuard.Decision decision = LoginGuard.observeFailure(state, 5, 15, now);
            assertTrue(decision.allowed());
            state = decision.next();
        }
        LoginGuard.Decision locked = LoginGuard.observeFailure(state, 5, 15, now.plusSeconds(30));
        assertFalse(locked.allowed());
        assertTrue(LoginGuard.isLocked(locked.next(), now.plus(14, ChronoUnit.MINUTES)));
        assertFalse(LoginGuard.isLocked(locked.next(), now.plus(16, ChronoUnit.MINUTES)));

        LoginGuard.Decision again = LoginGuard.observeFailure(locked.next(), 5, 15, now.plus(16, ChronoUnit.MINUTES));
        assertTrue(again.allowed());
        assertEquals(1, again.next().count());
        assertNull(again.next().lockedUntil());
    }

    @Test
    void addressThrottleResetsAfterTheWindow() {
        LoginGuard.State state = null;
        for (int attempt = 1; attempt <= 20; attempt++) {
            LoginGuard.Decision decision = LoginGuard.observeIp(state, 20, 60, now);
            assertTrue(decision.allowed());
            state = decision.next();
        }
        assertFalse(LoginGuard.observeIp(state, 20, 60, now.plusSeconds(30)).allowed());
        assertTrue(LoginGuard.observeIp(state, 20, 60, now.plusSeconds(61)).allowed());
        assertTrue(LoginGuard.observeIp(state, 0, 60, now).allowed());
    }

    @Test
    void idleTimeoutUsesLastActivity() {
        assertFalse(LoginGuard.idleExpired(now.minus(29, ChronoUnit.MINUTES), 30, now));
        assertTrue(LoginGuard.idleExpired(now.minus(30, ChronoUnit.MINUTES), 30, now));
        assertFalse(LoginGuard.idleExpired(now.minus(2, ChronoUnit.HOURS), 0, now));
        assertFalse(LoginGuard.shouldTouch(now.minusSeconds(30), now));
        assertTrue(LoginGuard.shouldTouch(now.minusSeconds(61), now));
    }
}
