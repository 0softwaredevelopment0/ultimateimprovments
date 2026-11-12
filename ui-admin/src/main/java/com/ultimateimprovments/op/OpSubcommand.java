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
import java.util.List;
import java.util.stream.Collectors;

/**
 * /ui op — grant operator status.
 * <p>
 * Usage:
 *   /ui op <player>    — grant OP to a specific player (online or cached offline)
 *   /ui op all         — grant OP to all online players missing it
 *   /ui op confirm     — confirm a pending op action
 * <p>
 * The OP list and the real OP flag can drift apart (a whitelist/blacklist gate,
 * a third-party plugin, a database hiccup). This command therefore treats the
 * following states as work instead of as an error:
 * <ul>
 *   <li>no OP, not listed — normal grant;</li>
 *   <li>no OP, listed — repair: the list entry exists, the flag is missing;</li>
 *   <li>OP, not listed — record the existing OP in the list.</li>
 * </ul>
 * All player-facing text is localized in UI-Admin.toml under {@code messages.op}
 * / {@code messages_en.op}.
 */
public final class OpSubcommand {

    private static final String PERMISSION = "ui.command.op";

    // Shared defaults (English fallbacks; the TOML template carries RU/EN text).
    static final String D_USAGE = "<red>Usage: </red><white>/ui op <player|all|confirm></white>";
    static final String D_PLAYER_UNKNOWN = "<red>Player </red><yellow>%player%</yellow><red> is not online and is not known on this server.</red>";
    static final String D_PLAYER_GONE = "<red>Player </red><yellow>%player%</yellow><red> is not known on this server anymore!</red>";
    static final String D_OFFLINE = "<yellow>⚠</yellow> <gray>Player </gray><white>%player%</white> <gray>is currently offline — the OP change will be written to ops.json and the OP list, and no notification will be sent to the player.</gray>";
    static final String D_LIST_UNAVAILABLE = "<red>Could not read the OP list (database error).</red> <gray>Nothing was changed — check the server console.</gray>";
    static final String D_LIST_SAVE_FAILED = "<red>⚠</red> <gray>The OP list entry could not be saved — check the database (server console).</gray>";
    static final String D_TARGET_GRANTED = "<gold>⚡</gold> <white>You are now an</white> <gold>operator</gold><white>!</white>";

    private OpSubcommand() {}

    public static boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            CommandErrors.noPermission(sender, PERMISSION);
            return true;
        }

        // /ui op alone — usage
        if (args.length < 2) {
            OpMessages.send(sender, "usage_grant", D_USAGE);
            return true;
        }

        String arg = args[1].toLowerCase();

        // ── /ui op confirm ──
        if (arg.equals("confirm")) {
            return executeConfirm(sender);
        }

        // ── /ui op all ──
        if (arg.equals("all")) {
            return showConfirmAll(sender);
        }

        // ── /ui op <player> ──
        return showConfirmSingle(sender, args[1]);
    }

    // ════════════════════════════════════════
    // SHOW CONFIRMATION — single player
    // ════════════════════════════════════════
    private static boolean showConfirmSingle(CommandSender sender, String targetName) {
        OfflinePlayer target = OpManager.resolve(targetName);

        if (target == null || target.getName() == null) {
            OpMessages.send(sender, "player_unknown", D_PLAYER_UNKNOWN,
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

        if (op && listed) {
            OpMessages.send(sender, "grant.already_op_listed",
                    "<yellow>%player%</yellow><red> already has OP status and is in the OP list.</red>",
                    "%player%", name);
            return true;
        }

        if (op) {
            // Real OP without a list entry — record it so /ui deop can find the player later.
            if (OpManager.add(name, sender.getName())) {
                OpMessages.send(sender, "grant.added_to_list",
                        "<green>✔</green> <yellow>%player%</yellow> <white>already has OP — added to the OP list.</white>",
                        "%player%", name);
            } else {
                OpMessages.send(sender, "grant.add_failed",
                        "<yellow>%player%</yellow> <white>already has OP, but the list entry could not be saved.</white> <gray>Check the database (server console).</gray>",
                        "%player%", name);
            }
            return true;
        }

        if (!online) {
            sendOfflineWarning(sender, name);
        }

        boolean repair = listed;
        OpManager.setPending(OpManager.getSenderId(sender), "op", name);

        if (repair) {
            OpMessages.send(sender, "grant.repair_hint",
                    "<gray>%player% is in the OP list but has no real OP — confirming will restore it.</gray>",
                    "%player%", name);
        }

        sendConfirmDialog(sender,
                repair
                        ? OpMessages.raw("grant.title_restore", "Restore OP")
                        : OpMessages.raw("grant.title", "Grant OP"),
                OpMessages.raw("grant.action", "<green>GRANT</green>"),
                name + (online ? "" : OpMessages.raw("dialog_offline_suffix", " <dark_gray>(offline)</dark_gray>")),
                "/ui op confirm");
        return true;
    }

    // ════════════════════════════════════════
    // SHOW CONFIRMATION — all
    // ════════════════════════════════════════
    private static boolean showConfirmAll(CommandSender sender) {
        EligiblePlayers eligible = getEligibleOnline();

        if (eligible == null) {
            sendListUnavailable(sender);
            return true;
        }

        if (eligible.players().isEmpty()) {
            OpMessages.send(sender, "grant.all_none",
                    "<red>No eligible players found. All online players already have OP and are in the OP list.</red>");
            return true;
        }

        // Store pending
        OpManager.setPending(OpManager.getSenderId(sender), "op", "all");

        String names = eligible.players().stream()
                .map(Player::getName)
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
                "%action%", OpMessages.raw("grant.action", "<green>GRANT</green>"));
        OpMessages.sendRow(sender, "grant.all_targets",
                "<gray>Targets:</gray> <white>%count% players</white>",
                "%count%", String.valueOf(eligible.players().size()));
        if (eligible.repairs() > 0) {
            OpMessages.sendRow(sender, "grant.all_repairs",
                    "<gray>Repairs:</gray> <yellow>%count%</yellow> <gray>in the list without real OP</gray>",
                    "%count%", String.valueOf(eligible.repairs()));
        }
        if (eligible.listSyncs() > 0) {
            OpMessages.sendRow(sender, "grant.all_list_sync",
                    "<gray>List sync:</gray> <yellow>%count%</yellow> <gray>already have OP, only the list entry is missing</gray>",
                    "%count%", String.valueOf(eligible.listSyncs()));
        }
        OpMessages.sendRow(sender, "grant.all_players",
                "<gray>Players:</gray> <white>%players%</white>",
                "%players%", names);
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));
        OpMessages.sendRow(sender, "grant.all_sure",
                "<gray>Are you sure you want to grant OP to all?</gray>");
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));

        if (sender instanceof Player) {
            // Player: show clickable buttons
            Component confirmBtn = MessageUtil.parse(OpMessages.ROW_PREFIX + "    "
                    + OpMessages.raw("dialog_confirm_button",
                            "<dark_green>[</dark_green><green>✔ Confirm %title%</green><dark_green>]</dark_green>",
                            "%title%", OpMessages.raw("grant.title", "Grant OP"))
            ).clickEvent(ClickEvent.runCommand("/ui op confirm"))
             .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT,
                     OpMessages.get("dialog_hover_confirm",
                             "<green>Click to confirm</green>\n<gray>Expires in 30 seconds</gray>")));
            sender.sendMessage(confirmBtn);

            Component cancelBtn = MessageUtil.parse(
                    "<dark_gray>┃     </dark_gray><dark_red>[</dark_red><red>✕ Cancel</red><dark_red>]</dark_red>"
            ).clickEvent(ClickEvent.runCommand("/ui oplist"))
             .hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT,
                     OpMessages.get("dialog_hover_cancel", "<gray>Click to cancel</gray>")));
            sender.sendMessage(cancelBtn);

            sender.sendMessage(MessageUtil.parse(
                    "<dark_gray>┃</dark_gray>"
            ));
            OpMessages.sendRow(sender, "dialog_click_hint",
                    "<gray>Click a button or type </gray><white>%command%</white><gray> to confirm.</gray>",
                    "%command%", "/ui op confirm");
        } else {
            // Console / Command Block: text-only instructions
            OpMessages.sendRow(sender, "dialog_console_hint",
                    "<gray>Type </gray><white>%command%</white><gray> to confirm.</gray>",
                    "%command%", "/ui op confirm");
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
        OpManager.PendingAction pending = OpManager.consumePending(senderId, "op");

        if (pending == null) {
            OpMessages.send(sender, "grant.no_pending",
                    "<red>No pending /ui op action found.</red> <gray>Use </gray><white>/ui op <player|all></white><gray> first.</gray>");
            return true;
        }

        if ("all".equals(pending.target())) {
            return executeGrantAll(sender);
        }

        return executeGrantSingle(sender, pending.target());
    }

    private static boolean executeGrantSingle(CommandSender sender, String targetName) {
        OfflinePlayer target = OpManager.resolve(targetName);

        if (target == null || target.getName() == null) {
            OpMessages.send(sender, "player_gone", D_PLAYER_GONE,
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

        boolean wasOp = OpManager.hasRealOp(target);
        if (wasOp && listed) {
            OpMessages.send(sender, "grant.already_op_listed",
                    "<yellow>%player%</yellow><red> already has OP status and is in the OP list.</red>",
                    "%player%", name);
            return true;
        }

        boolean repair = listed && !wasOp;
        boolean stored = OpManager.grant(target, sender.getName());

        if (!stored) {
            OpMessages.send(sender, "grant.granted",
                    "<green>✔</green> <gold>Operator</gold> <white>status granted to</white> <yellow>%player%</yellow><white>.</white>",
                    "%player%", name);
            OpMessages.send(sender, "list_save_failed", D_LIST_SAVE_FAILED);
        } else if (repair) {
            OpMessages.send(sender, "grant.restored",
                    "<green>✔</green> <gold>Operator</gold> <white>status restored for</white> <yellow>%player%</yellow><white> (was in the list without real OP).</white>",
                    "%player%", name);
        } else if (wasOp) {
            OpMessages.send(sender, "grant.recorded",
                    "<green>✔</green> <yellow>%player%</yellow> <white>already had OP — added to the OP list.</white>",
                    "%player%", name);
        } else {
            OpMessages.send(sender, "grant.granted",
                    "<green>✔</green> <gold>Operator</gold> <white>status granted to</white> <yellow>%player%</yellow><white>.</white>",
                    "%player%", name);
        }

        if (!online) {
            sendOfflineWarning(sender, name);
        } else if (!wasOp) {
            Player player = target.getPlayer();
            if (player != null) {
                player.sendMessage(OpMessages.get("target_granted", D_TARGET_GRANTED));
            }
        }
        ConsoleLogger.info("[OpManager] " + sender.getName() + " granted OP to " + name
                + (online ? "" : " (offline)") + (repair ? " (restored list-only drift)" : ""));
        return true;
    }

    private static boolean executeGrantAll(CommandSender sender) {
        EligiblePlayers eligible = getEligibleOnline();

        if (eligible == null) {
            sendListUnavailable(sender);
            return true;
        }

        if (eligible.players().isEmpty()) {
            OpMessages.send(sender, "grant.all_none",
                    "<red>No eligible players found. All online players already have OP and are in the OP list.</red>");
            return true;
        }

        int count = 0;
        int failedWrites = 0;
        for (Player target : eligible.players()) {
            boolean wasOp = target.isOp();
            if (!OpManager.grant(target, sender.getName())) {
                failedWrites++;
            }
            if (!wasOp) {
                target.sendMessage(OpMessages.get("target_granted", D_TARGET_GRANTED));
            }
            count++;
        }

        OpMessages.send(sender, "grant.all_granted",
                "<green>✔</green> <gold>Operator</gold> <white>status granted to </white><yellow>%count%</yellow><white> players.</white>",
                "%count%", String.valueOf(count));
        if (eligible.repairs() > 0) {
            OpMessages.send(sender, "grant.all_restored",
                    "<gray>Restored </gray><yellow>%count%</yellow><gray> player(s) that were in the list without real OP.</gray>",
                    "%count%", String.valueOf(eligible.repairs()));
        }
        if (eligible.listSyncs() > 0) {
            OpMessages.send(sender, "grant.all_recorded",
                    "<gray>Recorded </gray><yellow>%count%</yellow><gray> player(s) that already had OP but were missing from the list.</gray>",
                    "%count%", String.valueOf(eligible.listSyncs()));
        }
        if (failedWrites > 0) {
            OpMessages.send(sender, "grant.all_failed",
                    "<red>⚠</red> <gray>OP list entries failed for </gray><yellow>%count%</yellow><gray> player(s) — check the database (server console).</gray>",
                    "%count%", String.valueOf(failedWrites));
        }
        ConsoleLogger.info("[OpManager] " + sender.getName() + " granted OP to " + count + " players (all)");
        return true;
    }

    // ════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════
    /**
     * Players whose OP state and list entry do not match, or who have neither.
     *
     * @return {@code null} when the OP list cannot be read.
     */
    private static EligiblePlayers getEligibleOnline() {
        List<Player> players = new ArrayList<>();
        int repairs = 0;
        int listSyncs = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Boolean listed = OpManager.lookup(p.getName());
            if (listed == null) return null;
            if (!p.isOp() && !listed) {
                players.add(p);
            } else if (!p.isOp()) {
                players.add(p);
                repairs++;
            } else if (!listed) {
                players.add(p);
                listSyncs++;
            }
        }
        return new EligiblePlayers(players, repairs, listSyncs);
    }

    static void sendListUnavailable(CommandSender sender) {
        OpMessages.send(sender, "list_unavailable", D_LIST_UNAVAILABLE);
    }

    /**
     * Prefix-styled warning shown whenever OP is changed for a player who is
     * currently offline: the change still lands in {@code ops.json} and the OP
     * list, but the player receives no in-game notification.
     */
    static void sendOfflineWarning(CommandSender sender, String playerName) {
        OpMessages.sendPrefixed(sender, "offline_warning", D_OFFLINE,
                "%player%", playerName);
    }

    private record EligiblePlayers(List<Player> players, int repairs, int listSyncs) {}

    // ════════════════════════════════════════
    // SHARED CONFIRMATION DIALOG
    // ════════════════════════════════════════
    /**
     * @param title      localized dialog title used in the confirm button
     * @param actionTag  localized action tag (GRANT / REVOKE)
     * @param targetName display name, already including the offline suffix if needed
     */
    static void sendConfirmDialog(CommandSender sender, String title, String actionTag,
                                  String targetName, String confirmCmd) {
        sender.sendMessage(MessageUtil.parse(""));
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓</dark_gray>"
        ));
        OpMessages.sendRow(sender, "dialog_title", "<gold>⚡ Operator Status Change</gold>");
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));
        OpMessages.sendRow(sender, "dialog_player",
                "<gray>Player:</gray> <white>%player%</white>",
                "%player%", targetName);
        OpMessages.sendRow(sender, "dialog_action",
                "<gray>Action:</gray> %action%",
                "%action%", actionTag);
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));
        OpMessages.sendRow(sender, "dialog_sure", "<gray>Are you sure?</gray>");
        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┃</dark_gray>"
        ));

        if (sender instanceof Player) {
            // Player: show clickable buttons
            Component confirmBtn = MessageUtil.parse(OpMessages.ROW_PREFIX + "    "
                    + OpMessages.raw("dialog_confirm_button",
                            "<dark_green>[</dark_green><green>✔ Confirm %title%</green><dark_green>]</dark_green>",
                            "%title%", title)
            ).clickEvent(ClickEvent.runCommand(confirmCmd))
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
                    "%command%", confirmCmd);
        } else {
            // Console / Command Block: text-only instructions
            OpMessages.sendRow(sender, "dialog_console_hint",
                    "<gray>Type </gray><white>%command%</white><gray> to confirm.</gray>",
                    "%command%", confirmCmd);
            OpMessages.sendRow(sender, "dialog_expiry",
                    "<gray>This action expires in </gray><white>30 seconds</white><gray>.</gray>");
        }

        sender.sendMessage(MessageUtil.parse(
                "<dark_gray>┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛</dark_gray>"
        ));
        sender.sendMessage(MessageUtil.parse(""));
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
            java.util.Set<String> names = new java.util.LinkedHashSet<>(completions);
            for (Player p : Bukkit.getOnlinePlayers()) {
                // Players already fully OP (flag + list) have nothing to grant.
                if (!p.isOp() || !OpManager.isInList(p.getName())) {
                    names.add(p.getName());
                }
            }
            // Offline list names are repair candidates (in the list, no real OP).
            names.addAll(OpManager.getList());
            return names.stream()
                    .filter(s -> s.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
