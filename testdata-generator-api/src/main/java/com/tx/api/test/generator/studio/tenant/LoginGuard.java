package com.tx.api.test.generator.studio.tenant;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Sign-in lockout and idle-session rules. Directory and local accounts share this gate.
 * The company directory password is not changed or locked.
 */
public final class LoginGuard {

    public static final int DEFAULT_MAX_FAILURES = 5;
    public static final int DEFAULT_LOCKOUT_MINUTES = 15;
    public static final int DEFAULT_IP_MAX_ATTEMPTS = 20;
    public static final int DEFAULT_IP_WINDOW_SECONDS = 60;
    public static final int DEFAULT_IDLE_MINUTES = 30;
    public static final String TOO_MANY = "Too many sign-in attempts. Try again later.";

    private LoginGuard() {
    }

    public record State(int count, Instant windowStartedAt, Instant lockedUntil) {
    }

    public record Decision(boolean allowed, State next) {
    }

    public static boolean isLocked(State state, Instant now) {
        return state != null && state.lockedUntil != null && now != null && state.lockedUntil.isAfter(now);
    }

    public static Decision observeIp(State current, int maxAttempts, int windowSeconds, Instant now) {
        if (maxAttempts <= 0 || windowSeconds <= 0) {
            return new Decision(true, current);
        }
        State active = activeWindow(current, windowSeconds, ChronoUnit.SECONDS, now);
        int count = active == null ? 1 : active.count + 1;
        Instant started = active == null ? now : active.windowStartedAt;
        return new Decision(count <= maxAttempts, new State(count, started, null));
    }

    public static Decision observeFailure(State current, int maxFailures, int lockoutMinutes, Instant now) {
        if (maxFailures <= 0 || lockoutMinutes <= 0) {
            return new Decision(true, current);
        }
        if (isLocked(current, now)) {
            return new Decision(false, current);
        }
        State baseline = current;
        if (baseline != null && baseline.lockedUntil != null && !baseline.lockedUntil.isAfter(now)) {
            baseline = null;
        }
        State active = activeWindow(baseline, lockoutMinutes, ChronoUnit.MINUTES, now);
        int count = active == null ? 1 : active.count + 1;
        Instant started = active == null ? now : active.windowStartedAt;
        Instant lockedUntil = count >= maxFailures ? now.plus(lockoutMinutes, ChronoUnit.MINUTES) : null;
        return new Decision(lockedUntil == null, new State(count, started, lockedUntil));
    }

    public static boolean idleExpired(Instant lastSeen, int idleMinutes, Instant now) {
        if (idleMinutes <= 0 || lastSeen == null || now == null) {
            return false;
        }
        return !lastSeen.plus(idleMinutes, ChronoUnit.MINUTES).isAfter(now);
    }

    public static boolean shouldTouch(Instant lastSeen, Instant now) {
        if (lastSeen == null || now == null) {
            return true;
        }
        return !lastSeen.plus(1, ChronoUnit.MINUTES).isAfter(now);
    }

    private static State activeWindow(State current, long amount, ChronoUnit unit, Instant now) {
        if (current == null || current.windowStartedAt == null || now == null) {
            return null;
        }
        if (!current.windowStartedAt.plus(amount, unit).isAfter(now)) {
            return null;
        }
        return current;
    }
}
