package com.ultimateimprovments.op;

import com.ultimateimprovments.config.MessagesManager;
import com.ultimateimprovments.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

/**
 * Localized text of {@code /ui op}, {@code /ui deop} and {@code /ui oplist}.
 * <p>
 * Keys live in UI-Admin.toml under {@code messages.op.*} (Russian) and
 * {@code messages_en.op.*} (English). The defaults passed to every call are the
 * English fallbacks used when an older config has not been regenerated yet.
 * Placeholders use the project-wide {@code %name%} syntax and are substituted
 * before MiniMessage parsing.
 */
final class OpMessages {

    /** Message group routed to UI-Admin (see AddonCatalog.routeMsg). */
    private static final String GROUP = "op.";
    /** Box row prefix used by the OP dialogs. */
    static final String ROW_PREFIX = "<dark_gray>┃</dark_gray> ";

    private OpMessages() {}

    /** Raw MiniMessage text with {@code %placeholder%} substitution. */
    static String raw(String key, String def, String... pairs) {
        String text = MessagesManager.getString(GROUP + key, def);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        return text;
    }

    static Component get(String key, String def, String... pairs) {
        return MessageUtil.parse(raw(key, def, pairs));
    }

    /** A line inside the {@code ┃} dialog box. */
    static Component row(String key, String def, String... pairs) {
        return MessageUtil.parse(ROW_PREFIX + raw(key, def, pairs));
    }

    static void send(CommandSender sender, String key, String def, String... pairs) {
        sender.sendMessage(get(key, def, pairs));
    }

    static void sendRow(CommandSender sender, String key, String def, String... pairs) {
        sender.sendMessage(row(key, def, pairs));
    }

    /** Prefix-styled line (warnings, offline notices). */
    static void sendPrefixed(CommandSender sender, String key, String def, String... pairs) {
        sender.sendMessage(MessageUtil.parse(MessageUtil.PREFIX + raw(key, def, pairs)));
    }
}
