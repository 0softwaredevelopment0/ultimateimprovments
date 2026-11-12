package com.ultimateimprovments.op;

import com.ultimateimprovments.command.CommandErrors;
import com.ultimateimprovments.util.ConsoleLogger;
import com.ultimateimprovments.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /ui deop — revoke operator status.
 * <p>
 * Usage:
 *   /ui deop <player>    — revoke OP from a specific player
 *   /ui deop all         — revoke OP from ALL players in the OP list
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
        @SuppressWarnings("deprecation")
        Player target = Bukkit.getPlayerExact(targetName);

        if (target == null) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>Player </red><yellow>" + targetName + "</yellow><red> is not online.</red>"
            ));
            return true;
        }

        Boolean listed = OpManager.lookup(target.getName());
        if (listed == null) {
            sendListUnavailable(sender);
            return true;
        }

        boolean op = target.isOp();

        if (!op && !listed) {
            sender.sendMessage(MessageUtil.parse(
                    "<yellow>" + target.getName()
                            + "</yellow><red> does not have OP status and is not in the OP list.</red>"
            ));
            return true;
        }

        if (!op) {
            // List-only drift: there is nothing to revoke, only a stale entry to drop.
            removeStaleEntry(sender, target.getName());
            return true;
        }

        // Store pending
        OpManager.setPending(OpManager.getSenderId(sender), "deop", target.getName());

        if (!listed) {
            sender.sendMessage(MessageUtil.parse(
                    "<gray>" + target.getName() + " has OP but is not in the OP list — "
                            + "confirming will revoke the real OP.</gray>"
            ));
        }

        // Show confirmation dialog
        OpSubcommand.sendConfirmDialog(sender,
                "Revoke",
                "<red>REVOKE</red>",
                target.getName(),
                "/ui deop confirm");
        return true;
    }

    // ════════════════════════════════════════
    // SHOW CONFIRMATION — all
    // ════════════════════════════════════════
    private static boolean showConfirmAll(CommandSender sender) {
        List<String> opList = OpManager.getList();

        if (opList.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>The OP list is empty. No players to revoke.</red>"
            ));
            return true;
        }

        // Store pending
        OpManager.setPending(OpManager.getSenderId(sender), "deop", "all");

        // Build display
        String names = opList.stream()
                .map(name -> {
                    Player p = Bukkit.getPlayerExact(name);
                    boolean online = p != null && p.isOnline();
                    return (online ? "<green>" : "<gray>") + name + "</gray>";
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
                "<dark_gray>┃</dark_gray> <gray>Targets:</gray> <white>" + opList.size() + " players in OP list</white>"
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
        @SuppressWarnings("deprecation")
        Player target = Bukkit.getPlayerExact(targetName);

        if (target == null) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>Player </red><yellow>" + targetName + "</yellow><red> is no longer online!</red>"
            ));
            return true;
        }

        Boolean listed = OpManager.lookup(target.getName());
        if (listed == null) {
            sendListUnavailable(sender);
            return true;
        }

        if (!target.isOp()) {
            if (listed) {
                removeStaleEntry(sender, target.getName());
            } else {
                sender.sendMessage(MessageUtil.parse(
                        "<yellow>" + target.getName()
                                + "</yellow><red> does not have OP status and is not in the OP list.</red>"
                ));
            }
            return true;
        }

        boolean stored = OpManager.revoke(target);

        sender.sendMessage(MessageUtil.parse(
                "<green>✔</green> <gold>Operator</gold> <white>status revoked from</white> <yellow>" + target.getName() + "</yellow><white>.</white>"
        ));
        if (!stored) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>⚠</red> <gray>The OP list entry could not be removed — check the database (server console).</gray>"
            ));
        }
        target.sendMessage(MessageUtil.parse(
                "<red>⛔</red> <white>Your</white> <gold>operator</gold> <white>status has been revoked.</white>"
        ));
        ConsoleLogger.info("[OpManager] " + sender.getName() + " revoked OP from " + target.getName());
        return true;
    }

    private static boolean executeRevokeAll(CommandSender sender) {
        List<String> opList = OpManager.getList();

        if (opList.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>The OP list is empty. No players to revoke.</red>"
            ));
            return true;
        }

        int count = 0;
        int failed = 0;
        for (String name : opList) {
            Player target = Bukkit.getPlayerExact(name);
            boolean ok;
            if (target != null && target.isOnline()) {
                ok = OpManager.revoke(target);
                if (ok) {
                    target.sendMessage(MessageUtil.parse(
                            "<red>⛔</red> <white>Your</white> <gold>operator</gold> <white>status has been revoked.</white>"
                    ));
                }
            } else {
                ok = OpManager.remove(name);
            }
            if (ok) {
                count++;
            } else {
                failed++;
            }
        }

        sender.sendMessage(MessageUtil.parse(
                "<green>✔</green> <gold>Operator</gold> <white>status revoked from </white><yellow>" + count + "</yellow><white> players.</white>"
        ));
        if (failed > 0) {
            sender.sendMessage(MessageUtil.parse(
                    "<red>⚠</red> <gray>Could not update </gray><yellow>" + failed
                            + "</yellow><gray> list entries — check the database (server console).</gray>"
            ));
        }
        ConsoleLogger.info("[OpManager] " + sender.getName() + " revoked OP from " + count + " players (all)");
        return true;
    }

    // ════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════
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
            // Listed players plus anyone with real OP outside the list — both can be revoked.
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.isOp() || OpManager.isInList(p.getName())) {
                    completions.add(p.getName());
                }
            }
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
