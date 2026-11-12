package com.ultimateimprovments.mechanics.features.items;

import com.ultimateimprovments.config.MessagesManager;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.util.MessageUtil;
import com.ultimateimprovments.util.SoundUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Remote Detonator interaction:
 * <ul>
 *   <li><b>RMB in the air</b> — detonate the bound TNT block (vanilla redstone
 *       ignition: the block is consumed and a primed TNT with the configured
 *       fuse takes its place);</li>
 *   <li><b>Shift + RMB on TNT</b> — bind that block (one binding, re-binding
 *       overrides the previous one);</li>
 *   <li><b>Shift + RMB in the air</b> — clear the binding;</li>
 *   <li><b>RMB on a block</b> — does not detonate; shows the {@code air_only}
 *       actionbar hint;</li>
 *   <li><b>Shift + RMB on a non-TNT block</b> — does not reset; shows the
 *       {@code shift_air_only} actionbar hint.</li>
 * </ul>
 * The binding lives in the item's PDC, so it survives restarts and travels with
 * the item. Settings: {@code [remote_detonator]} in {@code configs/UI-Items.toml}.
 */
public final class RemoteDetonatorListener implements Listener {

    private static final Map<UUID, Long> cooldowns = new HashMap<>();

    private static boolean enabled = true;
    private static int fuseTicks = 80;
    private static long cooldownMs = 1000L;
    private static double maxDistance = 0.0;
    private static boolean crossWorld = true;
    private static Sound igniteSound = Sound.ITEM_FLINTANDSTEEL_USE;
    private static Sound bindSound = Sound.BLOCK_NOTE_BLOCK_PLING;

    public static void init(Main plugin) {
        reloadConfig();
        plugin.getServer().getPluginManager().registerEvents(new RemoteDetonatorListener(), plugin);
    }

    /** Reads the {@code [remote_detonator]} section of {@code configs/UI-Items.toml}. */
    public static void reloadConfig() {
        Main main = Main.getInstance();
        if (main == null) return;
        FileConfiguration cfg = main.getConfig();

        enabled = cfg.getBoolean("remote_detonator.enabled", true);
        fuseTicks = Math.max(1, cfg.getInt("remote_detonator.fuse_ticks", 80));
        cooldownMs = Math.max(0L, cfg.getLong("remote_detonator.cooldown_ms", 1000L));
        maxDistance = Math.max(0.0, cfg.getDouble("remote_detonator.max_distance", 0.0));
        crossWorld = cfg.getBoolean("remote_detonator.cross_world", true);
        igniteSound = SoundUtil.getSound(cfg.getString("remote_detonator.ignite_sound"),
                Sound.ITEM_FLINTANDSTEEL_USE);
        bindSound = SoundUtil.getSound(cfg.getString("remote_detonator.bind_sound"),
                Sound.BLOCK_NOTE_BLOCK_PLING);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        Player player = e.getPlayer();
        EquipmentSlot hand = e.getHand() == null ? EquipmentSlot.HAND : e.getHand();
        ItemStack item = player.getInventory().getItem(hand);
        if (!RemoteDetonatorItem.isDetonator(item)) return;

        // Bukkit quirk: RIGHT_CLICK_AIR has no block, so useInteractedBlock() is
        // DENY by default and isCancelled() is always true. Only useItemInHand()
        // is a real cancellation signal.
        if (e.useItemInHand() == Event.Result.DENY) return;

        Block clicked = e.getClickedBlock();

        // Containers, doors, buttons and friends keep their vanilla behaviour.
        // Everything else (air included) is ours, so the detonator is NEVER a
        // flint and steel: no fire, no vanilla TNT priming, no vanilla wear.
        if (clicked != null && isInteractive(clicked.getType())
                && !isIgnitable(clicked.getType())) {
            return;
        }
        e.setCancelled(true);

        if (!enabled) return;

        // Detonation and reset are air-only. Clicking a block never detonates.
        if (action == Action.RIGHT_CLICK_AIR) {
            if (player.isSneaking()) {
                reset(player, item);
            } else {
                detonate(player, item, hand);
            }
            return;
        }

        // RIGHT_CLICK_BLOCK: only Shift+RMB on TNT binds. Every other block
        // click is cancelled with a hint — reset happens ONLY in the air.
        boolean tnt = clicked != null && clicked.getType() == Material.TNT;
        if (tnt) {
            if (player.isSneaking()) {
                bind(player, item, clicked);
            } else {
                sendActionBar(player, "air_only");
            }
            return;
        }

        sendActionBar(player, player.isSneaking() ? "shift_air_only" : "air_only");
    }

    /** A dispenser holding the detonator must not light fires either. */
    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (RemoteDetonatorItem.isDetonator(e.getItem())) e.setCancelled(true);
    }

    // =========================
    // BINDING
    // =========================

    private void reset(Player player, ItemStack item) {
        if (RemoteDetonatorItem.getTarget(item) == null) {
            send(player, "nothing_bound");
            return;
        }
        RemoteDetonatorItem.clearTarget(item);
        send(player, "reset");
    }

    private void bind(Player player, ItemStack item, Block clicked) {
        if (clicked == null) {
            sendActionBar(player, player.isSneaking() ? "shift_air_only" : "air_only");
            return;
        }

        if (clicked.getType() != Material.TNT) {
            sendActionBar(player, player.isSneaking() ? "shift_air_only" : "air_only");
            return;
        }

        boolean rebind = RemoteDetonatorItem.getTarget(item) != null;
        DetonatorTarget target = new DetonatorTarget(clicked.getWorld().getName(),
                clicked.getX(), clicked.getY(), clicked.getZ());
        RemoteDetonatorItem.setTarget(item, target);

        SoundUtil.playSound(player, bindSound, 1.0f, rebind ? 1.4f : 1.0f);
        send(player, rebind ? "rebound" : "bound", target);
    }

    // =========================
    // DETONATION
    // =========================

    private void detonate(Player player, ItemStack item, EquipmentSlot hand) {
        DetonatorTarget target = RemoteDetonatorItem.getTarget(item);
        if (target == null) {
            send(player, "nothing_bound");
            return;
        }

        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            send(player, "cooldown", target);
            return;
        }

        World world = Bukkit.getWorld(target.world());
        if (world == null || (!crossWorld && !world.equals(player.getWorld()))) {
            RemoteDetonatorItem.clearTarget(item);
            send(player, "wrong_world", target);
            return;
        }

        Location loc = new Location(world, target.x(), target.y(), target.z());
        if (maxDistance > 0) {
            boolean outOfReach = !world.equals(player.getWorld())
                    || player.getLocation().distanceSquared(loc) > maxDistance * maxDistance;
            if (outOfReach) {
                send(player, "too_far", target);
                return;
            }
        }

        Block block = world.getBlockAt(loc);
        if (block.getType() != Material.TNT) {
            RemoteDetonatorItem.clearTarget(item);
            send(player, "target_lost", target);
            return;
        }

        // Same result as a redstone pulse on the block: it disappears and a
        // primed TNT takes its place.
        block.setType(Material.AIR, false);
        Location centre = block.getLocation().add(0.5, 0.0, 0.5);
        TNTPrimed primed = world.spawn(centre, TNTPrimed.class);
        primed.setFuseTicks(fuseTicks);
        primed.setSource(player);
        world.playSound(centre, igniteSound, 1.0f, 1.0f);

        cooldowns.put(player.getUniqueId(), now);
        RemoteDetonatorItem.clearTarget(item);
        send(player, "detonated", target);

        if (RemoteDetonatorItem.useOnce(item)) {
            breakItem(player, hand);
        }
    }

    private void breakItem(Player player, EquipmentSlot hand) {
        player.getInventory().setItem(hand, new ItemStack(Material.AIR));
        SoundUtil.playSound(player, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        send(player, "broken");
    }

    // =========================
    // HELPERS
    // =========================

    /**
     * Whether right-clicking the block opens/uses it (chest, door, button).
     * <p>
     * Paper deprecates both {@code Material#isInteractable()} and
     * {@code BlockType#isInteractable()} without naming a replacement yet, so the
     * stable flag is used deliberately: hand-rolling the block list would go
     * stale on every Minecraft update.
     */
    @SuppressWarnings("deprecation")
    private static boolean isInteractive(Material type) {
        return type.isInteractable();
    }

    /**
     * Block types a flint and steel can act on. They are cancelled even though
     * they are interactable, so the detonator never lights anything.
     */
    private static boolean isIgnitable(Material type) {
        String name = type.name();
        return type == Material.TNT
                || type == Material.CAMPFIRE
                || type == Material.SOUL_CAMPFIRE
                || name.equals("CANDLE")
                || name.endsWith("_CANDLE")
                || name.endsWith("_CANDLE_CAKE");
    }

    /** Sends {@code remote_detonator.<key>} to the actionbar, without a plugin prefix. */
    private static void sendActionBar(Player player, String key) {
        String raw = MessagesManager.getString("remote_detonator." + key, "");
        if (raw == null || raw.isBlank()) return;
        if (maxDistance > 0) {
            raw = raw.replace("%max%", Long.toString(Math.round(maxDistance)));
        }
        player.sendActionBar(MessageUtil.parse(raw, player));
    }

    /** Sends {@code remote_detonator.<key>} with the target placeholders applied. */    private static void send(Player player, String key) {
        send(player, key, null);
    }

    private static void send(Player player, String key, DetonatorTarget target) {
        String raw = MessagesManager.getString("remote_detonator." + key, "");
        if (raw == null || raw.isBlank()) return;

        if (target != null) {
            raw = raw.replace("%world%", target.world())
                    .replace("%x%", Integer.toString(target.x()))
                    .replace("%y%", Integer.toString(target.y()))
                    .replace("%z%", Integer.toString(target.z()));
        }
        if (maxDistance > 0) {
            raw = raw.replace("%max%", Long.toString(Math.round(maxDistance)));
        }
        player.sendMessage(MessageUtil.parse(raw, player));
    }
}
