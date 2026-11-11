package com.ultimateimprovments.mechanics.security.auth;

import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the authentication state of players.
 * <p>
 * Stores:
 * - Who is already authenticated (authenticated)
 * - Who is awaiting authentication (pendingAuth)
 * <p>
 * The wrong-password counter is no longer kept here: it lives in the database
 * ({@code auth.failed_attempts}) so a reconnect cannot reset it. Both sets are
 * concurrent — they are read from async event threads (chat, commands).
 */
public class AuthPlayerState {

    private static AuthPlayerState instance;

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingAuth = ConcurrentHashMap.newKeySet();

    public AuthPlayerState() {
        instance = this;
        // Publish the pending-auth state through the core hook so unrelated addons
        // (e.g. flight/levitation) can respect the freeze without importing auth.
        com.ultimateimprovments.core.hooks.CoreHooks.setPendingAuth(this::isPendingAuth);
    }

    public static AuthPlayerState getInstance() {
        return instance;
    }

    // =========================
    // AUTHENTICATED
    // =========================
    public boolean isAuthenticated(UUID uuid) {
        return authenticated.contains(uuid);
    }

    public void setAuthenticated(UUID uuid) {
        authenticated.add(uuid);
        pendingAuth.remove(uuid);
    }

    public void removeAuthenticated(UUID uuid) {
        authenticated.remove(uuid);
    }

    // =========================
    // PENDING AUTH
    // =========================
    public boolean isPendingAuth(UUID uuid) {
        return pendingAuth.contains(uuid);
    }

    public void setPendingAuth(UUID uuid) {
        pendingAuth.add(uuid);
    }

    public void removePendingAuth(UUID uuid) {
        pendingAuth.remove(uuid);
    }

    // =========================
    // FULL CLEANUP
    // =========================
    public void removePlayer(UUID uuid) {
        authenticated.remove(uuid);
        pendingAuth.remove(uuid);
    }

    // =========================
    // PLAYER FROZEN?
    // =========================
    public boolean needsAuth(Player player) {
        if (!AuthConfig.isEnabled()) return false;
        return !authenticated.contains(player.getUniqueId());
    }
}
