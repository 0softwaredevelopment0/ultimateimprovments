package com.ultimateimprovments.op;

import com.ultimateimprovments.command.CommandErrors;
import com.ultimateimprovments.util.ConsoleLogger;
import com.ultimateimprovments.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /ui deop — revoke operator status.
 * <p>
 * Usage:
 *   /ui deop <player>    — revoke OP from a specific player
 *   /ui deop all         — revoke OP from everyone in the OP list AND every real
 *                          OP (online or offline, including OPs missing from the
 *                          list); stale list-only entries are dropped
 *   /ui deop confirm     — confirm a pending deop action
 * <p>
 * A player who is in the OP list but has no real OP is not an error: the
 * stale entry is simply removed. A player with real OP but no list entry can
 * still be revoked, so the two states never deadlock each other.
 */
public final class DeopSubcommand {

    private static final String PERMISSION = "ui.command.deop";

    private DeopSubcommand() {}

    public static boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            CommandErrors.noPermission(sender, PERMISSION);
            return true;
        }

        // /ui deop alone — usage
        if (args.length < 2) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>Usage: </red><white>/ui deop <player|all|confirm></white>"
            ));
            return true;
        }

        String arg = args[1].toLowerCase();

        // ── /ui deop confirm ──
        if (arg.equals("confirm")) {
            return executeConfirm(sender);
        }

        // ── /ui deop all ──
        if (arg.equals("all")) {
            return showConfirmAll(sender);
        }

        // ── /ui deop <player> ──
        return showConfirmSingle(sender, args[1]);
    }

    // ════════════════════════════════════════
    // SHOW CONFIRMATION — single player
    // ════════════════════════════════════════
    private static boolean showConfirmSingle(CommandSender sender, String targetName) {
        OfflinePlayer target = OpManager.resolve(targetName);

        if (target == null || target.getName() == null) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>Player </red><yellow>" + targetName
                            + "</yellow><red> is not online and is not known on this server.</red>"
            ));
            return true;
        }

        String name = target.getName();
        boolean online = target.isOnline();
        Boolean listed = OpManager.lookup(name);
        if (listed == null) {
            sendListUnavailable(sender);
            return true;
        }

        boolean op = OpManager.hasRealOp(target);

        if (!op && !listed) {
            sender.sendMessage(MessageUtil.parse(
                    "<yellow>" + name
                            + "</yellow><red> does not have OP status and is not in the OP list.</red>"
            ));
            return true;
        }

        if (!op) {
            // List-only drift: there is nothing to revoke, only a stale entry to drop.
            removeStaleEntry(sender, name);
            return true;
        }

        if (!online) {
            OpSubcommand.sendOfflineWarning(sender, name);
        }

        // Store pending
        OpManager.setPending(OpManager.getSenderId(sender), "deop", name);

        if (!listed) {
            sender.sendMessage(MessageUtil.parse(
                    "<gray>" + name + " has OP but is not in the OP list — "
                            + "confirming will revoke the real OP.</gray>"
            ));
        }

        // Show confirmation dialog
        OpSubcommand.sendConfirmDialog(sender,
                "Revoke",
                "<red>REVOKE</red>",
                name + (online ? "" : " <dark_gray>(offline)</dark_gray>"),
                "/ui deop confirm");
        return true;
    }

    // ════════════════════════════════════════
    // SHOW CONFIRMATION — all
    // ════════════════════════════════════════
    private static boolean showConfirmAll(CommandSender sender) {
        List<Target> targets = collectTargets();

        if (targets.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>No OP players found. The OP list is empty and nobody has real OP.</red>"
            ));
            return true;
        }

        // Store pending
        OpManager.setPending(OpManager.getSenderId(sender), "deop", "all");

        int listedCount = (int) targets.stream().filter(Target::listed).count();
        int opOnlyCount = (int) targets.stream().filter(t -> t.realOp() && !t.listed()).count();
        int offlineCount = (int) targets.stream().filter(t -> t.online() == null).count();

        // Build display: online names green, offline gray; tags show the source of each target.
        String names = targets.stream()
                .map(t -> {
                    boolean online = t.online() != null;
                    String color = online ? "<green>" : "<gray>";
                    String colorClose = online ? "</green>" : "</gray>";
                    StringBuilder tags = new StringBuilder();
                    if (t.realOp()) tags.append(" <gold>[OP]</gold>");
                    if (t.listed() && !t.realOp()) tags.append(" <red>[list-only]</red>");
                    if (t.realOp() && !t.listed()) tags.append(" <yellow>[op-only]</yellow>");
                    if (!online) tags.append(" <dark_gray>(offline)</dark_gray>");
                    return color + t.name() + colorClose + tags;
                })
                .collect(Collectors.joining("<gray>, </gray>"));

        sender.sendMessage(MessageUtil.parse(""));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓</dark_gray>"
        ));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray> <gold>⚡ Operator Status Change</gold>"
        ));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray> <gray>Action:</gray> <red>REVOKE</red>"
        ));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray> <gray>Targets:</gray> <white>" + targets.size() + " players</white>"
        ));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray> <gray>In OP list:</gray> <white>" + listedCount
                        + "</white><gray> | real OP outside list:</gray> <yellow>" + opOnlyCount
                        + "</yellow><gray> | offline:</gray> <white>" + offlineCount + "</white>"
        ));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray> <gray>Players:</gray> " + names
        ));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray> <gray>Are you sure you want to revoke OP from all?</gray>"
        ));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));

        if (sender instanceof Player) {
            // Player: show clickable buttons
            Component confirmBtn = MessageUtil.parse(
                    "<dark_gray>┃     </dark_gray><dark_green>[</dark_green><green>✔ Confirm Revoke</green><dark_green>]</dark_green>"
            ).clickEvent(ClickEvent.runCommand("/ui deop confirm"))
             .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT,
                     MessageUtil.parse("<green>Click to confirm</green>\n<gray>Expires in 30 seconds</gray>")));
            sender.sendMessage(confirmBtn);

            Component cancelBtn = MessageUtil.parse(
                    "<dark_gray>┃     </dark_gray><dark_red>[</dark_red><red>✕ Cancel</red><dark_red>]</dark_red>"
            ).clickEvent(ClickEvent.runCommand("/ui oplist"))
             .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT,
                     MessageUtil.parse("<gray>Click to cancel</gray>")));
            sender.sendMessage(cancelBtn);

            sender.sendMessage(MessageUtil.parse(
                    "<dark_gray>┃</dark_gray>"
            ));
            sender.sendMessage(MessageUtil.parse(
                    "<dark_gray>┃</dark_gray> <gray>Click a button or type </gray><white>/ui deop confirm</white><gray> to confirm.</gray>"
            ));
        } else {
            // Console / Command Block: text-only instructions
            sender.sendMessage(MessageUtil.parse(
                    "<dark_gray>┃</dark_gray> <gray>Type </gray><white>/ui deop confirm</white><gray> to confirm.</gray>"
            ));
            sender.sendMessage(MessageUtil.parse(
                    "<dark_gray>┃</dark_gray> <gray>This action expires in </gray><white>30 seconds</white><gray>.</gray>"
            ));
        }

        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛</dark_gray>"
        ));
        sender.sendMessage(MessageUtil.parse(""));

        return true;
    }

    // ════════════════════════════════════════
    // EXECUTE CONFIRM
    // ════════════════════════════════════════
    private static boolean executeConfirm(CommandSender sender) {
        String senderId = OpManager.getSenderId(sender);
        OpManager.PendingAction pending = OpManager.consumePending(senderId, "deop");

        if (pending == null) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>No pending /ui deop action found.</red> <gray>Use </gray><white>/ui deop <player|all></white><gray> first.</gray>"
            ));
            return true;
        }

        if ("all".equals(pending.target())) {
            return executeRevokeAll(sender);
        }

        return executeRevokeSingle(sender, pending.target());
    }

    private static boolean executeRevokeSingle(CommandSender sender, String targetName) {
        OfflinePlayer target = OpManager.resolve(targetName);

        if (target == null || target.getName() == null) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>Player </red><yellow>" + targetName
                            + "</yellow><red> is not known on this server anymore!</red>"
            ));
            return true;
        }

        String name = target.getName();
        boolean online = target.isOnline();
        Boolean listed = OpManager.lookup(name);
        if (listed == null) {
            sendListUnavailable(sender);
            return true;
        }

        if (!OpManager.hasRealOp(target)) {
            if (listed) {
                removeStaleEntry(sender, name);
            } else {
                sender.sendMessage(MessageUtil.parse(
                        "<yellow>" + name
                                + "</yellow><red> does not have OP status and is not in the OP list.</red>"
                ));
            }
            return true;
        }

        boolean stored = OpManager.revoke(target);

        sender.sendMessage(MessageUtil.parse(
                "<green>✔</green> <gold>Operator</gold> <white>status revoked from</white> <yellow>" + name + "</yellow><white>.</white>"
        ));
        if (!stored) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>⚠</red> <gray>The OP list entry could not be removed — check the database (server console).</gray>"
            ));
        }
        if (!online) {
            OpSubcommand.sendOfflineWarning(sender, name);
        } else {
            Player player = target.getPlayer();
            if (player != null) {
                player.sendMessage(MessageUtil.parse(
                        "<red>⛔</red> <white>Your</white> <gold>operator</gold> <white>status has been revoked.</white>"
                ));
            }
        }
        ConsoleLogger.info("[OpManager] " + sender.getName() + " revoked OP from " + name
                + (online ? "" : " (offline)"));
        return true;
    }

    private static boolean executeRevokeAll(CommandSender sender) {
        List<Target> targets = collectTargets();

        if (targets.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>No OP players found. The OP list is empty and nobody has real OP.</red>"
            ));
            return true;
        }

        int revoked = 0;
        int staleEntries = 0;
        int failed = 0;
        for (Target t : targets) {
            boolean ok;
            if (t.online() != null) {
                ok = OpManager.revoke(t.online());
                if (ok && t.realOp()) {
                    t.online().sendMessage(MessageUtil.parse(
                            "<red>⛔</red> <white>Your</white> <gold>operator</gold> <white>status has been revoked.</white>"
                    ));
                }
            } else if (t.realOp() && t.offline() != null) {
                // Offline OP: OfflinePlayer#setOp rewrites ops.json without the player being online.
                ok = OpManager.revoke(t.offline());
            } else {
                // List-only entry with no real OP — nothing to revoke, just drop the record.
                ok = OpManager.remove(t.name()) || Boolean.FALSE.equals(OpManager.lookup(t.name()));
            }

            if (!ok) {
                failed++;
            } else if (t.realOp()) {
                revoked++;
            } else {
                staleEntries++;
            }
        }

        sender.sendMessage(MessageUtil.parse(
                "<green>✔</green> <gold>Operator</gold> <white>status revoked from </white><yellow>" + revoked + "</yellow><white> players.</white>"
        ));
        if (staleEntries > 0) {
            sender.sendMessage(MessageUtil.parse(
                    "<gray>Removed </gray><yellow>" + staleEntries
                            + "</yellow><gray> stale OP list entries that had no real OP.</gray>"
            ));
        }
        if (failed > 0) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>⚠</red> <gray>Could not update </gray><yellow>" + failed
                            + "</yellow><gray> targets (real OP or list entry) — check the server console.</gray>"
            ));
        }
        ConsoleLogger.info("[OpManager] " + sender.getName() + " revoked OP from " + revoked
                + " players (all), removed " + staleEntries + " stale entries, " + failed + " failed");
        return true;
    }

    // ════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════
    /**
     * Union of everyone who has real OP ({@link Bukkit#getOperators()}, online or
     * offline) and everyone recorded in the OP list. Keyed by lowercase name so
     * an entry that appears in both sources is handled once.
     */
    private static List<Target> collectTargets() {
        Map<String, Target> byName = new LinkedHashMap<>();

        for (String name : OpManager.getList()) {
            byName.put(name.toLowerCase(Locale.ROOT), new Target(name, null, null, true, false));
        }

        for (org.bukkit.OfflinePlayer op : Bukkit.getOperators()) {
            String name = op.getName();
            if (name == null) continue;
            String key = name.toLowerCase(Locale.ROOT);
            Target existing = byName.get(key);
            boolean listed = existing != null && existing.listed();
            byName.put(key, new Target(name, op.isOnline() ? op.getPlayer() : null, op, listed, true));
        }

        // Resolve online players for list entries and give cached names their real case.
        byName.replaceAll((key, t) -> {
            if (t.online() != null) return t;
            Player online = Bukkit.getPlayerExact(t.name());
            if (online != null) {
                return new Target(online.getName(), online, t.offline(), t.listed(), t.realOp());
            }
            if (t.offline() == null) {
                org.bukkit.OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(t.name());
                if (cached != null && cached.getName() != null) {
                    return new Target(cached.getName(), null, cached, t.listed(), false);
                }
            }
            return t;
        });

        return new ArrayList<>(byName.values());
    }

    /** One `/ui deop all` target: the list entry, the real OP flag, or both. */
    private record Target(String name, Player online, org.bukkit.OfflinePlayer offline,
                          boolean listed, boolean realOp) {}

    /** Drops an OP-list entry for a player who has no real OP (previous desync). */
    private static void removeStaleEntry(CommandSender sender, String playerName) {
        boolean removed = OpManager.remove(playerName);
        if (removed || Boolean.FALSE.equals(OpManager.lookup(playerName))) {
            sender.sendMessage(MessageUtil.parse(
                    "<green>✔</green> <yellow>" + playerName
                            + "</yellow> <white>had no real OP — the stale OP list entry was removed.</white>"
            ));
            ConsoleLogger.info("[OpManager] " + sender.getName()
                    + " removed a stale OP list entry for " + playerName + " (no real OP).");
        } else {
            sender.sendMessage(MessageUtil.parse(
                    "<red>⚠</red> <gray>Could not remove the OP list entry for </gray><yellow>" + playerName
                            + "</yellow><gray> — check the database (server console).</gray>"
            ));
        }
    }

    private static void sendListUnavailable(CommandSender sender) {
        sender.sendMessage(MessageUtil.parse(
                "<red>Could not read the OP list (database error).</red> "
                        + "<gray>Nothing was changed — check the server console.</gray>"
        ));
    }

    // ════════════════════════════════════════
    // TAB COMPLETION
    // ════════════════════════════════════════
    public static List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            List<String> completions = new ArrayList<>();
            completions.add("all");
            completions.add("confirm");
            // Listed players plus anyone with real OP outside the list — online or offline —
            // all of them can be revoked now.
            java.util.Set<String> names = new java.util.LinkedHashSet<>(completions);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOp() || OpManager.isInList(p.getName())) {
                    names.add(p.getName());
                }
            }
            for (String name : OpManager.getList()) {
                names.add(name);
            }
            for (OfflinePlayer op : Bukkit.getOperators()) {
                if (op.getName() != null) {
                    names.add(op.getName());
                }
            }
            return names.stream()
                    .filter(s -> s.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
