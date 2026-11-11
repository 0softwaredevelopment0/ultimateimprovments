package com.ultimateimprovments.mechanics.security.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wrong-password locks must grow after consecutive failures and never exceed the
 * configured cap — the old constant 15 s let a dictionary attack run forever.
 */
class AuthRateLimiterBackoffTest {

    private static final long BASE = 15_000L;
    private static final long CAP = 300_000L;

    @Test
    @DisplayName("Lock grows exponentially: 15s, 30s, 60s, ...")
    void growsExponentially() {
        assertEquals(15_000L, AuthRateLimiter.backoffDelayMs(BASE, 2, CAP, 1));
        assertEquals(30_000L, AuthRateLimiter.backoffDelayMs(BASE, 2, CAP, 2));
        assertEquals(60_000L, AuthRateLimiter.backoffDelayMs(BASE, 2, CAP, 3));
        assertEquals(120_000L, AuthRateLimiter.backoffDelayMs(BASE, 2, CAP, 4));
        assertEquals(240_000L, AuthRateLimiter.backoffDelayMs(BASE, 2, CAP, 5));
    }

    @Test
    @DisplayName("Lock is clamped to the configured maximum")
    void clampsToCap() {
        assertEquals(CAP, AuthRateLimiter.backoffDelayMs(BASE, 2, CAP, 6));
        assertEquals(CAP, AuthRateLimiter.backoffDelayMs(BASE, 2, CAP, 100));
    }

    @Test
    @DisplayName("Multiplier 1 keeps the old constant lock; a cap below the base cannot shorten it")
    void handlesDegenerateConfigs() {
        assertEquals(BASE, AuthRateLimiter.backoffDelayMs(BASE, 1, CAP, 50));
        assertEquals(5_000L, AuthRateLimiter.backoffDelayMs(5_000L, 2, 1_000L, 10));
    }
}
