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
 *   /ui deop <player>    — revoke OP from a specific player (online or cached offline)
 *   /ui deop all         — revoke OP from everyone in the OP list AND every real
 *                          OP (online or offline, including OPs missing from the
 *                          list); stale list-only entries are dropped
 *   /ui deop confirm     — confirm a pending deop action
 * <p>
 * A player who is in the OP list but has no real OP is not an error: the
 * stale entry is simply removed. A player with real OP but no list entry can
 * still be revoked, so the two states never deadlock each other.
 * <p>
 * All player-facing text is localized in UI-Admin.toml under {@code messages.op}
 * / {@code messages_en.op}.
 */
public final class DeopSubcommand {

    private static final String PERMISSION = "ui.command.deop";

    private static final String D_USAGE = "<red>Usage: </red><white>/ui deop <player|all|confirm></white>";
    private static final String D_TARGET_REVOKED =
            "<red>⛔</red> <white>Your</white> <gold>operator</gold> <white>status has been revoked.</white>";

    private DeopSubcommand() {}

    public static boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            CommandErrors.noPermission(sender, PERMISSION);
            return true;
        }

        // /ui deop alone — usage
        if (args.length < 2) {
            OpMessages.send(sender, "usage_revoke", D_USAGE);
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
            OpMessages.send(sender, "player_unknown", OpSubcommand.D_PLAYER_UNKNOWN,
                    "%player%", targetName);
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
            OpMessages.send(sender, "revoke.nothing",
                    "<yellow>%player%</yellow><red> does not have OP status and is not in the OP list.</red>",
                    "%player%", name);
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
            OpMessages.send(sender, "revoke.has_op_not_listed",
                    "<gray>%player% has OP but is not in the OP list — confirming will revoke the real OP.</gray>",
                    "%player%", name);
        }

        // Show confirmation dialog
        OpSubcommand.sendConfirmDialog(sender,
                OpMessages.raw("revoke.title", "Revoke"),
                OpMessages.raw("revoke.action", "<red>REVOKE</red>"),
                name + (online ? "" : OpMessages.raw("dialog_offline_suffix", " <dark_gray>(offline)</dark_gray>")),
                "/ui deop confirm");
        return true;
    }

    // ════════════════════════════════════════
    // SHOW CONFIRMATION — all
    // ════════════════════════════════════════
    private static boolean showConfirmAll(CommandSender sender) {
        List<Target> targets = collectTargets();

        if (targets.isEmpty()) {
            OpMessages.send(sender, "revoke.all_none",
                    "<red>No OP players found. The OP list is empty and nobody has real OP.</red>");
            return true;
        }

        // Store pending
        OpManager.setPending(OpManager.getSenderId(sender), "deop", "all");

        int listedCount = (int) targets.stream().filter(Target::listed).count();
        int opOnlyCount = (int) targets.stream().filter(t -> t.realOp() && !t.listed()).count();
        int offlineCount = (int) targets.stream().filter(t -> t.online() == null).count();

        String tagOp = OpMessages.raw("revoke.tag_op", " <gold>[OP]</gold>");
        String tagListOnly = OpMessages.raw("revoke.tag_list_only", " <red>[list-only]</red>");
        String tagOpOnly = OpMessages.raw("revoke.tag_op_only", " <yellow>[op-only]</yellow>");
        String tagOffline = OpMessages.raw("revoke.tag_offline", " <dark_gray>(offline)</dark_gray>");

        // Build display: online names green, offline gray; tags show the source of each target.
        String names = targets.stream()
                .map(t -> {
                    boolean online = t.online() != null;
                    String color = online ? "<green>" : "<gray>";
                    String colorClose = online ? "</green>" : "</gray>";
                    StringBuilder tags = new StringBuilder();
                    if (t.realOp()) tags.append(tagOp);
                    if (t.listed() && !t.realOp()) tags.append(tagListOnly);
                    if (t.realOp() && !t.listed()) tags.append(tagOpOnly);
                    if (!online) tags.append(tagOffline);
                    return color + t.name() + colorClose + tags;
                })
                .collect(Collectors.joining("<gray>, </gray>"));

        sender.sendMessage(MessageUtil.parse(""));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓</dark_gray>"
        ));
        OpMessages.sendRow(sender, "dialog_title", "<gold>⚡ Operator Status Change</gold>");
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));
        OpMessages.sendRow(sender, "dialog_action",
                "<gray>Action:</gray> %action%",
                "%action%", OpMessages.raw("revoke.action", "<red>REVOKE</red>"));
        OpMessages.sendRow(sender, "revoke.all_targets",
                "<gray>Targets:</gray> <white>%count% players</white>",
                "%count%", String.valueOf(targets.size()));
        OpMessages.sendRow(sender, "revoke.all_breakdown",
                "<gray>In OP list:</gray> <white>%listed%</white><gray> | real OP outside list:</gray> <yellow>%op_only%</yellow><gray> | offline:</gray> <white>%offline%</white>",
                "%listed%", String.valueOf(listedCount),
                "%op_only%", String.valueOf(opOnlyCount),
                "%offline%", String.valueOf(offlineCount));
        OpMessages.sendRow(sender, "revoke.all_players",
                "<gray>Players:</gray> %players%",
                "%players%", names);
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));
        OpMessages.sendRow(sender, "revoke.all_sure",
                "<gray>Are you sure you want to revoke OP from all?</gray>");
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));

        if (sender instanceof Player) {
            // Player: show clickable buttons
            Component confirmBtn = MessageUtil.parse(OpMessages.ROW_PREFIX + "    "
                    + OpMessages.raw("dialog_confirm_button",
                            "<dark_green>[</dark_green><green>✔ Confirm %title%</green><dark_green>]</dark_green>",
                            "%title%", OpMessages.raw("revoke.title", "Revoke"))
            ).clickEvent(ClickEvent.runCommand("/ui deop confirm"))
             .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT,
                     OpMessages.get("dialog_hover_confirm",
                             "<green>Click to confirm</green>\n<gray>Expires in 30 seconds</gray>")));
            sender.sendMessage(confirmBtn);

            Component cancelBtn = MessageUtil.parse(OpMessages.ROW_PREFIX + "    "
                    + OpMessages.raw("dialog_cancel_button",
                            "<dark_red>[</dark_red><red>✕ Cancel</red><dark_red>]</dark_red>")
            ).clickEvent(ClickEvent.runCommand("/ui oplist"))
             .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT,
                     OpMessages.get("dialog_hover_cancel", "<gray>Click to cancel</gray>")));
            sender.sendMessage(cancelBtn);

            sender.sendMessage(MessageUtil.parse(
                    "<dark_gray>┃</dark_gray>"
            ));
            OpMessages.sendRow(sender, "dialog_click_hint",
                    "<gray>Click a button or type </gray><white>%command%</white><gray> to confirm.</gray>",
                    "%command%", "/ui deop confirm");
        } else {
            // Console / Command Block: text-only instructions
            OpMessages.sendRow(sender, "dialog_console_hint",
                    "<gray>Type </gray><white>%command%</white><gray> to confirm.</gray>",
                    "%command%", "/ui deop confirm");
            OpMessages.sendRow(sender, "dialog_expiry",
                    "<gray>This action expires in </gray><white>30 seconds</white><gray>.</gray>");
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
            OpMessages.send(sender, "revoke.no_pending",
                    "<red>No pending /ui deop action found.</red> <gray>Use </gray><white>/ui deop <player|all></white><gray> first.</gray>");
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
            OpMessages.send(sender, "player_gone", OpSubcommand.D_PLAYER_GONE,
                    "%player%", targetName);
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
                OpMessages.send(sender, "revoke.nothing",
                        "<yellow>%player%</yellow><red> does not have OP status and is not in the OP list.</red>",
                        "%player%", name);
            }
            return true;
        }

        boolean stored = OpManager.revoke(target);

        OpMessages.send(sender, "revoke.revoked",
                "<green>✔</green> <gold>Operator</gold> <white>status revoked from</white> <yellow>%player%</yellow><white>.</white>",
                "%player%", name);
        if (!stored) {
            OpMessages.send(sender, "list_remove_failed",
                    "<red>⚠</red> <gray>The OP list entry could not be removed — check the database (server console).</gray>");
        }
        if (!online) {
            OpSubcommand.sendOfflineWarning(sender, name);
        } else {
            Player player = target.getPlayer();
            if (player != null) {
                player.sendMessage(OpMessages.get("target_revoked", D_TARGET_REVOKED));
            }
        }
        ConsoleLogger.info("[OpManager] " + sender.getName() + " revoked OP from " + name
                + (online ? "" : " (offline)"));
        return true;
    }

    private static boolean executeRevokeAll(CommandSender sender) {
        List<Target> targets = collectTargets();

        if (targets.isEmpty()) {
            OpMessages.send(sender, "revoke.all_none",
                    "<red>No OP players found. The OP list is empty and nobody has real OP.</red>");
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
                    t.online().sendMessage(OpMessages.get("target_revoked", D_TARGET_REVOKED));
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

        OpMessages.send(sender, "revoke.all_revoked",
                "<green>✔</green> <gold>Operator</gold> <white>status revoked from </white><yellow>%count%</yellow><white> players.</white>",
                "%count%", String.valueOf(revoked));
        if (staleEntries > 0) {
            OpMessages.send(sender, "revoke.all_stale",
                    "<gray>Removed </gray><yellow>%count%</yellow><gray> stale OP list entries that had no real OP.</gray>",
                    "%count%", String.valueOf(staleEntries));
        }
        if (failed > 0) {
            OpMessages.send(sender, "revoke.all_failed",
                    "<red>⚠</red> <gray>Could not update </gray><yellow>%count%</yellow><gray> targets (real OP or list entry) — check the server console.</gray>",
                    "%count%", String.valueOf(failed));
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

        for (OfflinePlayer op : Bukkit.getOperators()) {
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
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(t.name());
                if (cached != null && cached.getName() != null) {
                    return new Target(cached.getName(), null, cached, t.listed(), false);
                }
            }
            return t;
        });

        return new ArrayList<>(byName.values());
    }

    /** One `/ui deop all` target: the list entry, the real OP flag, or both. */
    private record Target(String name, Player online, OfflinePlayer offline,
                          boolean listed, boolean realOp) {}

    /** Drops an OP-list entry for a player who has no real OP (previous desync). */
    private static void removeStaleEntry(CommandSender sender, String playerName) {
        boolean removed = OpManager.remove(playerName);
        if (removed || Boolean.FALSE.equals(OpManager.lookup(playerName))) {
            OpMessages.send(sender, "revoke.stale_removed",
                    "<green>✔</green> <yellow>%player%</yellow> <white>had no real OP — the stale OP list entry was removed.</white>",
                    "%player%", playerName);
            ConsoleLogger.info("[OpManager] " + sender.getName()
                    + " removed a stale OP list entry for " + playerName + " (no real OP).");
        } else {
            OpMessages.send(sender, "revoke.stale_failed",
                    "<red>⚠</red> <gray>Could not remove the OP list entry for </gray><yellow>%player%</yellow><gray> — check the database (server console).</gray>",
                    "%player%", playerName);
        }
    }

    private static void sendListUnavailable(CommandSender sender) {
        OpMessages.send(sender, "list_unavailable", OpSubcommand.D_LIST_UNAVAILABLE);
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
