package com.ultimateimprovments.mechanics.security.auth;

import com.ultimateimprovments.config.MessagesManager;
import com.ultimateimprovments.util.MessageUtil;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting for password attempts.
 * <p>
 * A wrong password locks BOTH the account (UUID) and the source IP for
 * {@code auth.request_cooldown_seconds} (default 30 s). Successful attempts are
 * never delayed — the lock is applied only after an error, so a player who types
 * the right password first time in is not punished by the limiter.
 * <p>
 * The lock is <b>persistent</b>: every penalty is written to the
 * {@code auth_lockouts} table (scope {@code account} / {@code ip}), so it survives
 * a disconnect and a server restart. The in-memory maps are only a cache; when a
 * subject is not cached as locked, the database is consulted once, which covers
 * a cold start after a restart. Expired rows are pruned at startup and when the
 * IP cache grows.
 */
public class AuthRateLimiter {

    private static final int MAX_IP_ENTRIES = 4096;

    /**
     * How long one password attempt is "reserved". A reservation prevents a burst
     * of {@code auth_submit} clicks from starting N parallel Argon2 checks before
     * the first result (and its lockout) arrives. It expires on its own so a lost
     * release can never wedge an account.
     */
    private static final long ATTEMPT_IN_FLIGHT_TTL_MS = 30_000L;

    private static AuthRateLimiter instance;

    private final Map<UUID, Long> accountLockedUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> ipLockedUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> accountAttemptInFlight = new ConcurrentHashMap<>();
    private final Map<String, Long> ipAttemptInFlight = new ConcurrentHashMap<>();

    public AuthRateLimiter() {
        instance = this;
    }

    public static AuthRateLimiter getInstance() {
        return instance;
    }

    /**
     * Checks whether the account or its IP is currently locked.
     * When locked, sends the "wait N seconds" message and returns false.
     *
     * @param player the player attempting to submit a password
     * @return true when an attempt is allowed right now
     */
    public boolean checkCooldown(Player player) {
        long remaining = getRemainingMs(player);
        if (remaining <= 0) return true;

        long seconds = (remaining / 1000) + 1;
        player.sendMessage(MessageUtil.parse(
                MessagesManager.getString("auth.messages.rate_limit",
                        "<red>❌ Please wait </red><yellow>%seconds%</yellow> <red>seconds before the next request!</red>")
                        .replace("%seconds%", String.valueOf(seconds))));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.8f);
        return false;
    }

    /**
     * Remaining lock time in milliseconds ({@code 0} when the attempt is allowed now).
     * Used to re-open the auth dialog exactly when the lock expires — the client has
     * already closed the dialog by the time the attempt is rejected.
     */
    public long getRemainingMs(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        String ip = ipOf(player);

        long until = accountLockedUntil.getOrDefault(uuid, 0L);
        if (!ip.isEmpty()) {
            until = Math.max(until, ipLockedUntil.getOrDefault(ip, 0L));
        }

        // Cold cache (fresh start or a lock written by another login attempt):
        // read the persisted deadlines once and remember them.
        if (until <= now) {
            until = AuthDatabase.getLockoutUntil(AuthDatabase.SCOPE_ACCOUNT, uuid.toString());
            if (!ip.isEmpty()) {
                until = Math.max(until, AuthDatabase.getLockoutUntil(AuthDatabase.SCOPE_IP, ip));
            }
            if (until > now) {
                accountLockedUntil.put(uuid, until);
                if (!ip.isEmpty()) ipLockedUntil.put(ip, until);
            }
        }

        return Math.max(0L, until - now);
    }

    /**
     * Reserves one password verification for this account/IP. Must be called
     * synchronously (main thread) right before the async Argon2 check.
     * <p>
     * Without this, N {@code auth_submit} packets fired in one tick all passed the
     * cooldown (which is only applied after an error) and started N parallel
     * checks — the 15-second lock never saw the burst.
     *
     * @return {@code false} when another attempt is already being verified
     */
    public boolean tryBeginAttempt(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        String ip = ipOf(player);

        Long accountMark = accountAttemptInFlight.get(uuid);
        if (accountMark != null && accountMark > now) return false;
        if (!ip.isEmpty()) {
            Long ipMark = ipAttemptInFlight.get(ip);
            if (ipMark != null && ipMark > now) return false;
        }

        long until = now + ATTEMPT_IN_FLIGHT_TTL_MS;
        accountAttemptInFlight.put(uuid, until);
        if (!ip.isEmpty()) ipAttemptInFlight.put(ip, until);
        return true;
    }

    /** Releases the reservation taken by {@link #tryBeginAttempt}. */
    public void finishAttempt(Player player) {
        if (player == null) return;
        accountAttemptInFlight.remove(player.getUniqueId());
        String ip = ipOf(player);
        if (!ip.isEmpty()) ipAttemptInFlight.remove(ip);
    }

    /**
     * Locks the account and the player's IP after a wrong password, in memory and
     * in the database (so it outlives the session). The IP lock also covers other
     * accounts sharing that address.
     * <p>
     * The lock grows with every consecutive failure
     * ({@code auth.lockout.backoff_multiplier}, capped by
     * {@code auth.lockout.max_seconds}) and is monotonic: a new penalty never
     * shortens a lock that is already active.
     *
     * @param attempts the persistent failure counter, including this one
     */
    public void applyPenalty(Player player, int attempts) {
        long now = System.currentTimeMillis();
        long delay = lockoutDelayMs(attempts);

        UUID uuid = player.getUniqueId();
        long existingAccount = Math.max(
                accountLockedUntil.getOrDefault(uuid, 0L),
                AuthDatabase.getLockoutUntil(AuthDatabase.SCOPE_ACCOUNT, uuid.toString()));
        long accountUntil = Math.max(now + delay, existingAccount);
        accountLockedUntil.put(uuid, accountUntil);
        AuthDatabase.setLockout(AuthDatabase.SCOPE_ACCOUNT, uuid.toString(), accountUntil);

        String ip = ipOf(player);
        if (!ip.isEmpty()) {
            if (ipLockedUntil.size() >= MAX_IP_ENTRIES) {
                pruneExpired(now);
            }
            long existingIp = Math.max(
                    ipLockedUntil.getOrDefault(ip, 0L),
                    AuthDatabase.getLockoutUntil(AuthDatabase.SCOPE_IP, ip));
            long ipUntil = Math.max(now + delay, existingIp);
            ipLockedUntil.put(ip, ipUntil);
            AuthDatabase.setLockout(AuthDatabase.SCOPE_IP, ip, ipUntil);
        }
    }

    /** Exponential delay: base × multiplier^(attempts−1), clamped to the configured cap. */
    private long lockoutDelayMs(int attempts) {
        return backoffDelayMs(
                AuthConfig.getRequestCooldownMs(),
                AuthConfig.getLockoutBackoffMultiplier(),
                AuthConfig.getLockoutMaxSeconds() * 1000L,
                attempts);
    }

    /**
     * Pure lockout-growth math (extracted for tests).
     *
     * @param baseMs     lock duration after the first wrong password
     * @param multiplier growth factor per consecutive failure (≤1 = constant)
     * @param capMs      upper bound of one lock
     * @param attempts   consecutive failures including the current one
     */
    static long backoffDelayMs(long baseMs, int multiplier, long capMs, int attempts) {
        long base = Math.max(baseMs, 0L);
        long cap = Math.max(capMs, base);
        if (multiplier <= 1 || attempts <= 1) return Math.min(base, cap);
        long delay = base;
        for (int i = 1; i < attempts && delay < cap; i++) {
            delay = Math.min(cap, delay * multiplier);
        }
        return Math.min(delay, cap);
    }

    /**
     * Clears the cached account lock. Deliberately not called on quit — the
     * persisted lock is what makes a reconnect useless for a brute-forcer.
     */
    public void removePlayer(UUID uuid) {
        accountLockedUntil.remove(uuid);
    }

    private void pruneExpired(long now) {
        ipLockedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        accountLockedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        ipAttemptInFlight.entrySet().removeIf(entry -> entry.getValue() <= now);
        accountAttemptInFlight.entrySet().removeIf(entry -> entry.getValue() <= now);
        AuthDatabase.purgeExpiredLockouts(now);
    }

    private static String ipOf(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) return "";
        return address.getAddress().getHostAddress();
    }
}
