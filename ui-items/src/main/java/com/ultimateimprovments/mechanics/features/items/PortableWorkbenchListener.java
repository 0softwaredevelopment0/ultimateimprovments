package com.ultimateimprovments.mechanics.features.items;

import com.ultimateimprovments.config.MessagesManager;
import com.ultimateimprovments.core.Keys;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.mechanics.crafting.PortableWorkbenchCraftListener;
import com.ultimateimprovments.util.MessageUtil;
import com.ultimateimprovments.util.SoundUtil;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Right-click behaviour of the Portable Workbench.
 * <p>
 * The item is a crafting table, so the block placement must be suppressed:
 * both the item use and the interacted block are denied before the real
 * workbench menu is opened. One opening costs one charge; the last charge
 * breaks the item (there is no vanilla durability on a block item, so the
 * break is simulated: the stack is removed, with sound and particles).
 * <p>
 * Settings: {@code [portable_workbench]} in {@code configs/UI-Items.toml}.
 * Texts: {@code [messages.portable_workbench]} / {@code [messages_en.portable_workbench]}.
 */
public class PortableWorkbenchListener implements Listener {

    /** Absorbs duplicated use packets (main + off hand) from one click. */
    private static final long USE_COOLDOWN_FALLBACK_MS = 250L;

    private static final Map<UUID, Long> LAST_USE = new ConcurrentHashMap<>();

    private static long cooldownMs = USE_COOLDOWN_FALLBACK_MS;
    private static Sound openSound = Sound.BLOCK_WOOD_PLACE;
    private static Sound breakSound = Sound.ENTITY_ITEM_BREAK;
    private static Particle breakParticle = Particle.ITEM;
    private static int breakParticleCount = 16;

    /** Reads the {@code [portable_workbench]} section of {@code configs/UI-Items.toml}. */
    public static void reloadConfig() {
        Main main = Main.getInstance();
        if (main == null) return;

        cooldownMs = Math.max(0L, main.getConfig().getLong("portable_workbench.cooldown_ms", USE_COOLDOWN_FALLBACK_MS));
        openSound = soundOrNull(main.getConfig().getString("portable_workbench.open_sound", "BLOCK_WOOD_PLACE"),
                Sound.BLOCK_WOOD_PLACE);
        breakSound = soundOrNull(main.getConfig().getString("portable_workbench.break_sound", "ENTITY_ITEM_BREAK"),
                Sound.ENTITY_ITEM_BREAK);

        String particleName = main.getConfig().getString("portable_workbench.break_particle", "ITEM");
        breakParticle = parseParticle(particleName, Particle.ITEM);
        breakParticleCount = Math.max(0, main.getConfig().getInt("portable_workbench.break_particle_count", 16));
    }

    @SuppressWarnings("deprecation") // openWorkbench is the only API that opens a real crafting-table menu
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        ItemStack item = player.getInventory().getItem(hand);
        if (item == null || item.getType().isAir()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        if (!meta.getPersistentDataContainer().has(Keys.PORTABLE_WORKBENCH, PersistentDataType.BYTE)) return;

        // Never place the table and never touch the clicked block
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        if (!PortableWorkbenchCraftListener.isEnabled()) return;
        if (isOnCooldown(player)) return;

        int max = PortableWorkbenchCraftListener.maxCharges();
        int charges = Math.min(charges(item), max);

        // A legacy item that already ran out is broken on the first use
        if (charges <= 0) {
            breakItem(player, hand, item);
            return;
        }

        int remaining = charges - 1;

        if (remaining > 0) {
            ItemStack handItem = player.getInventory().getItem(hand);
            if (handItem == null) return;
            ItemMeta handMeta = handItem.getItemMeta();
            if (handMeta == null) return;
            PortableWorkbenchCraftListener.applyMeta(handMeta, remaining);
            handItem.setItemMeta(handMeta);
            player.getInventory().setItem(hand, handItem);
        }

        // A real workbench menu (CraftingTableMenu), not a dummy chest-shaped
        // inventory: only this way the crafting result slot actually works.
        player.openWorkbench(null, true);
        if (openSound != null) SoundUtil.playSound(player, openSound, 0.6f, 1.4f);

        if (remaining <= 0) {
            breakItem(player, hand, item);
        } else {
            sendActionBar(player, "opened", remaining, max);
        }
    }

    /** Simulated item break: the block item has no durability component. */
    private void breakItem(Player player, EquipmentSlot hand, ItemStack broken) {
        player.getInventory().setItem(hand, new ItemStack(Material.AIR));

        if (breakSound != null) SoundUtil.playSound(player, breakSound, 1.0f, 1.0f);
        if (breakParticle != null && breakParticleCount > 0) {
            Location at = player.getEyeLocation();
            if (breakParticle.getDataType() == ItemStack.class) {
                // Data-carrying particles (ITEM, BLOCK_ITEM...) need an item stack
                ItemStack data = broken != null ? broken.clone() : new ItemStack(Material.CRAFTING_TABLE);
                player.getWorld().spawnParticle(breakParticle, at, breakParticleCount,
                        0.3, 0.3, 0.3, 0.05, data);
            } else {
                player.getWorld().spawnParticle(breakParticle, at, breakParticleCount,
                        0.3, 0.3, 0.3, 0.05);
            }
        }

        sendRaw(player, MessagesManager.getString("portable_workbench.broken",
                "<red>✧</red> <gray>The portable workbench broke.</gray>"));
    }

    // =========================
    // HELPERS
    // =========================

    private static int charges(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        Integer stored = meta.getPersistentDataContainer()
                .get(Keys.PORTABLE_WORKBENCH_CHARGES, PersistentDataType.INTEGER);
        return stored == null ? 0 : Math.max(0, stored);
    }

    private static void sendActionBar(Player player, String key, int remaining, int max) {
        String raw = MessagesManager.getString("portable_workbench." + key, "");
        if (raw.isBlank()) return;
        player.sendActionBar(MessageUtil.parse(raw
                .replace("%charges%", String.valueOf(remaining))
                .replace("%max%", String.valueOf(max))));
    }

    private static void sendRaw(Player player, String raw) {
        if (raw == null || raw.isBlank()) return;
        player.sendMessage(MessageUtil.parse(raw));
    }

    private static boolean isOnCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long previous = LAST_USE.get(player.getUniqueId());
        if (previous != null && now - previous < cooldownMs) return true;

        if (LAST_USE.size() > 256) {
            LAST_USE.values().removeIf(stamp -> now - stamp >= Math.max(1000L, cooldownMs * 40));
        }
        LAST_USE.put(player.getUniqueId(), now);
        return false;
    }

    private static Sound soundOrNull(String raw, Sound fallback) {
        if (raw == null || raw.isBlank()) return null;
        return SoundUtil.getSound(raw, fallback);
    }

    private static Particle parseParticle(String raw, Particle fallback) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Particle.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
