package com.ultimateimprovments.op;

import com.ultimateimprovments.database.DatabaseManager;
import com.ultimateimprovments.util.ConsoleLogger;
import org.bukkit.entity.Player;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OP Manager — persistent operator list stored in SQLite.
 * <p>
 * Manages the authoritative list of who should have OP and the transition
 * helpers that keep that list and the real Bukkit OP flag in sync:
 * {@link #grant(Player, String)} sets OP and records the player,
 * {@link #revoke(Player)} clears OP and drops the record. Every module that
 * changes OP must go through these two methods — calling
 * {@code Player#setOp} directly creates a "list without OP" or "OP without
 * list" drift, after which {@code /ui op} and {@code /ui deop} used to refuse
 * to do anything at all.
 * <p>
 * The class lives in UI-Core (not UI-Admin) so UI-Punish and UI-Other can use
 * the same entry points instead of calling {@code setOp} behind the list's back.
 * <p>
 * Commands: /ui op, /ui deop, /ui oplist.
 */
public final class OpManager {

    /** Confirmation timeout in milliseconds (30 seconds). */
    public static final long CONFIRM_TIMEOUT_MS = 30_000L;

    /** Author name stored for list entries added by the join-time reconciliation. */
    private static final String SYNC_AUTHOR = "sync";

    private static final Map<String, PendingAction> pendingActions = new ConcurrentHashMap<>();

    /** Set once the table is known to exist; retried while the DB is unavailable. */
    private static volatile boolean tableReady = false;
    /** Keeps a broken DB from spamming the log on every lookup. */
    private static volatile boolean dbWarned = false;

    private OpManager() {}

    // ════════════════════════════════════════
    // INIT
    // ════════════════════════════════════════
    public static void init() {
        ensureTable();
        ConsoleLogger.info("[OpManager] Initialized.");
    }

    private static void ensureTable() {
        if (tableReady) return;
        Connection con = DatabaseManager.getConnection();
        if (con == null) {
            warnDb("connection unavailable");
            return;
        }
        try (con; Statement st = con.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS op_list (
                    player_name TEXT PRIMARY KEY,
                    added_by TEXT,
                    added_at INTEGER
                )
            """);
            tableReady = true;
        } catch (SQLException e) {
            warnDb("failed to create table — " + e.getMessage());
        }
    }

    private static void warnDb(String reason) {
        if (dbWarned) return;
        dbWarned = true;
        ConsoleLogger.warn("[OpManager] OP list unavailable: " + reason);
    }

    public static void shutdown() {
        pendingActions.clear();
    }

    // ════════════════════════════════════════
    // OP LIST MANAGEMENT
    // ════════════════════════════════════════
    public static boolean add(String playerName, String addedBy) {
        ensureTable();
        String lower = playerName.toLowerCase().trim();
        Connection con = DatabaseManager.getConnection();
        if (con == null) return false;
        try (con; PreparedStatement st = con.prepareStatement(
                     "INSERT OR IGNORE INTO op_list (player_name, added_by, added_at) VALUES (?, ?, ?)")) {
            st.setString(1, lower);
            st.setString(2, addedBy);
            st.setLong(3, System.currentTimeMillis());
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            ConsoleLogger.warn("[OpManager] Failed to add: " + lower + " — " + e.getMessage());
            return false;
        }
    }

    public static boolean remove(String playerName) {
        ensureTable();
        String lower = playerName.toLowerCase().trim();
        Connection con = DatabaseManager.getConnection();
        if (con == null) return false;
        try (con; PreparedStatement st = con.prepareStatement(
                     "DELETE FROM op_list WHERE player_name = ?")) {
            st.setString(1, lower);
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            ConsoleLogger.warn("[OpManager] Failed to remove: " + lower + " — " + e.getMessage());
            return false;
        }
    }

    /**
     * Tri-state membership check.
     *
     * @return {@code TRUE}/{@code FALSE} when the query succeeded, {@code null}
     *         when the database could not be queried. Callers must not treat
     *         {@code null} as "not in the list" — that is how a DB hiccup used
     *         to turn into a wrong grant/refusal.
     */
    public static Boolean lookup(String playerName) {
        ensureTable();
        String lower = playerName.toLowerCase().trim();
        Connection con = DatabaseManager.getConnection();
        if (con == null) return null;
        try (con; PreparedStatement st = con.prepareStatement(
                     "SELECT 1 FROM op_list WHERE player_name = ?")) {
            st.setString(1, lower);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            warnDb("lookup failed — " + e.getMessage());
            return null;
        }
    }

    /** Membership check that maps an unreadable list to {@code false}. Prefer {@link #lookup} on the command path. */
    public static boolean isInList(String playerName) {
        return Boolean.TRUE.equals(lookup(playerName));
    }

    /**
     * Returns the full sorted OP list (lowercase names).
     */
    public static List<String> getList() {
        ensureTable();
        List<String> result = new ArrayList<>();
        Connection con = DatabaseManager.getConnection();
        if (con == null) return result;
        try (con; Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT player_name FROM op_list ORDER BY player_name")) {
            while (rs.next()) {
                result.add(rs.getString("player_name"));
            }
        } catch (SQLException e) {
            ConsoleLogger.warn("[OpManager] Failed to get list: " + e.getMessage());
        }
        return result;
    }

    public static int getCount() {
        ensureTable();
        Connection con = DatabaseManager.getConnection();
        if (con == null) return 0;
        try (con; Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM op_list")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            ConsoleLogger.warn("[OpManager] Failed to count: " + e.getMessage());
        }
        return 0;
    }

    // ════════════════════════════════════════
    // STATE TRANSITIONS (list + real OP together)
    // ════════════════════════════════════════
    /**
     * Grants real OP and records the player in the OP list.
     *
     * @return {@code true} when the list entry exists after the call.
     */
    public static boolean grant(Player player, String addedBy) {
        player.setOp(true);
        String name = player.getName();
        Boolean listed = lookup(name);
        if (Boolean.TRUE.equals(listed)) return true;
        if (listed == null) return false;
        if (add(name, addedBy)) return true;
        // A concurrent insert (or a transient write failure) — verify instead of guessing.
        return Boolean.TRUE.equals(lookup(name));
    }

    /**
     * Revokes real OP and drops the player from the OP list.
     *
     * @return {@code true} when the list entry is gone after the call.
     */
    public static boolean revoke(Player player) {
        player.setOp(false);
        String name = player.getName();
        if (Boolean.FALSE.equals(lookup(name))) return true;
        remove(name);
        return Boolean.FALSE.equals(lookup(name));
    }

    /** Outcome of {@link #sync(Player)}. */
    public enum SyncResult { NONE, LISTED, FAILED }

    /**
     * Join-time reconciliation. Real OP without a list entry means somebody
     * granted OP outside {@code /ui op} (vanilla {@code ops.json}, a third-party
     * plugin, or an older build) — the player is recorded in the list so
     * {@code /ui deop} can find them later.
     * <p>
     * The opposite drift (in the list, no real OP) is deliberately <b>not</b>
     * re-granted automatically: it can be an intentional result of the OP
     * whitelist/blacklist gates, and silently restoring OP on join would defeat
     * them. Admins repair it explicitly with {@code /ui op <player>}, which now
     * treats that state as a repair instead of an error.
     */
    public static SyncResult sync(Player player) {
        Boolean listed = lookup(player.getName());
        if (listed == null) return SyncResult.FAILED;
        if (listed || !player.isOp()) return SyncResult.NONE;
        if (!add(player.getName(), SYNC_AUTHOR)) return SyncResult.FAILED;
        ConsoleLogger.info("[OpManager] " + player.getName()
                + " had real OP without a list entry — added to the OP list.");
        return SyncResult.LISTED;
    }

    // ════════════════════════════════════════
    // CONFIRMATION SYSTEM
    // ════════════════════════════════════════

    /**
     * Stores a pending action for a sender.
     */
    public static void setPending(String senderId, String action, String target) {
        pendingActions.put(senderId.toLowerCase(), new PendingAction(action, target, System.currentTimeMillis()));
    }

    /**
     * Consumes and returns the pending action if valid (exists and not expired).
     * Returns null if no valid pending action found.
     */
    public static PendingAction consumePending(String senderId, String expectedAction) {
        String key = senderId.toLowerCase();
        PendingAction pending = pendingActions.get(key);
        if (pending == null) return null;
        if (!pending.action().equalsIgnoreCase(expectedAction)) return null;
        if (System.currentTimeMillis() - pending.createdAt() > CONFIRM_TIMEOUT_MS) {
            pendingActions.remove(key);
            return null;
        }
        pendingActions.remove(key);
        return pending;
    }

    /**
     * Returns the sender identifier for confirmation tracking.
     * Players use UUID, console uses "console".
     */
    public static String getSenderId(org.bukkit.command.CommandSender sender) {
        if (sender instanceof org.bukkit.entity.Player p) {
            return p.getUniqueId().toString();
        }
        return sender.getName().toLowerCase();
    }

    /**
     * Cleans up expired pending actions. Call periodically if desired.
     */
    public static void cleanupExpired() {
        long now = System.currentTimeMillis();
        pendingActions.entrySet().removeIf(e ->
                now - e.getValue().createdAt() > CONFIRM_TIMEOUT_MS);
    }

    /**
     * Pending action record.
     */
    public record PendingAction(String action, String target, long createdAt) {}
}
