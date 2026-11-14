package com.ultimateimprovments.mechanics.features.items;

import com.ultimateimprovments.core.Keys;
import com.ultimateimprovments.mechanics.crafting.PortableWorkbenchCraftListener;
import com.ultimateimprovments.util.MessageUtil;

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
 * {@link InventoryType#WORKBENCH} inventory is opened. One opening costs one
 * charge; a table with no charges left refuses to open and cannot be recharged.
 */
public class PortableWorkbenchListener implements Listener {

    /** Absorbs duplicated use packets (main + off hand) from one click. */
    private static final long USE_COOLDOWN_MS = 250L;
    private static final Map<UUID, Long> LAST_USE = new ConcurrentHashMap<>();

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

        if (isOnCooldown(player)) return;

        int charges = charges(item);
        if (charges <= 0) {
            player.sendActionBar(MessageUtil.parse(
                    "<red>❌ Этот верстак израсходован — заряды не восстанавливаются.</red>"));
            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_LAND, 0.4f, 0.6f);
            return;
        }

        // Spend one charge on the live stack of the clicked hand
        ItemStack handItem = player.getInventory().getItem(hand);
        if (handItem == null) return;
        ItemMeta handMeta = handItem.getItemMeta();
        if (handMeta == null) return;
        PortableWorkbenchCraftListener.applyMeta(handMeta, charges - 1);
        handItem.setItemMeta(handMeta);
        player.getInventory().setItem(hand, handItem);

        // A real workbench menu (CraftingTableMenu), not a dummy chest-shaped
        // inventory: only this way the crafting result slot actually works.
        player.openWorkbench(null, true);

        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_WOOD_PLACE, 0.6f, 1.4f);
    }

    private static int charges(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        Integer stored = meta.getPersistentDataContainer()
                .get(Keys.PORTABLE_WORKBENCH_CHARGES, PersistentDataType.INTEGER);
        return stored == null ? 0 : Math.max(0, stored);
    }

    private static boolean isOnCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long previous = LAST_USE.get(player.getUniqueId());
        if (previous != null && now - previous < USE_COOLDOWN_MS) return true;

        if (LAST_USE.size() > 256) {
            LAST_USE.values().removeIf(stamp -> now - stamp >= USE_COOLDOWN_MS * 40);
        }
        LAST_USE.put(player.getUniqueId(), now);
        return false;
    }
}
