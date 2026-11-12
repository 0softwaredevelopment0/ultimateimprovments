package com.ultimateimprovments.op;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Join-time OP reconciliation: a player who already has real OP but no entry in
 * the {@code op_list} table is recorded there, so {@code /ui deop} can find and
 * revoke them later. The opposite drift (listed, no real OP) is left alone on
 * purpose — see {@link OpManager#sync(org.bukkit.entity.Player)}.
 * <p>
 * Runs on MONITOR so the OP whitelist/blacklist checks (which may revoke OP on
 * join) have already made their decision and the list is updated afterwards.
 */
public final class OpJoinSyncListener implements Listener {

    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        OpManager.SyncResult result = OpManager.sync(event.getPlayer());
        if (result == OpManager.SyncResult.FAILED && failureLogged.compareAndSet(false, true)) {
            org.bukkit.Bukkit.getLogger().warning(
                    "[OpManager] Join-time OP reconciliation failed — the OP list is unreadable. "
                            + "Further failures are suppressed until restart.");
        }
    }
}
