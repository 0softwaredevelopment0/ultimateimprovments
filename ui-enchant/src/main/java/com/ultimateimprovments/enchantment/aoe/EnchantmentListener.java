package com.ultimateimprovments.enchantment.aoe;

import com.ultimateimprovments.core.hooks.CoreHooks;
import com.ultimateimprovments.mechanics.features.integrity.ItemDurabilityUtil;
import com.ultimateimprovments.util.BlockVisibility;
import com.ultimateimprovments.util.LocationUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Listener: Area-of-Effect (AoE) block breaking.
 * <p>
 * When a player breaks a block with an AoE tool,
 * all blocks of the same type within the radius also break.
 * <p>
 * 🛡 <b>No breaking through walls:</b> the sweep is a flood fill that may only
 * travel through blocks of the same type (they are mined anyway) and through
 * see-through blocks (air, liquids, plants, ...). A solid block of another type
 * (bedrock, stone, planks, glass, ...) stops the sweep — blocks sealed behind
 * it are never touched, even if they match the broken type and lie inside the
 * radius (see {@link BlockVisibility}).
 * <p>
 * Radius = enchantment level (max 10).
 * Sneaking disables AoE for precise single-block mining.
 */
public class EnchantmentListener implements Listener {

    // Material#isInteractable() below is deprecated as unreliable with no
    // replacement — kept deliberately to preserve the AoE block filter.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        // Sneaking = precise single-block mining
        if (player.isSneaking()) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || tool.getType() == Material.AIR) return;

        // Get AoE level (real enchantment, with PDC failsafe fallback)
        int level = Enchantment.getLevel(tool);
        if (level <= 0) return;

        Block originBlock = event.getBlock();
        Material targetType = originBlock.getType();
        World world = originBlock.getWorld();
        if (world == null) return;

        // Skip non-solids, fluids, and instant-break blocks
        // Material#isInteractable() is deprecated as unreliable in Paper 26.3
        // with no replacement — kept deliberately to preserve the AoE filter.
        if (!targetType.isBlock() || targetType.isAir() || targetType.isInteractable()) return;

        int radius = Math.min(level, Enchantment.MAX_LEVEL); // Clamp to max level

        // 🛡 Collect only the matching blocks REACHABLE from the broken one:
        // the sweep may pass through the same type and through see-through
        // blocks, but a solid foreign block stops it. This is what prevents
        // mining a sealed room through its walls (e.g. magma inside a bedrock box).
        List<Block> targets = BlockVisibility.collectReachable(world, originBlock, targetType, radius);

        if (targets.isEmpty()) return;

        // Break all matching blocks (the original one is handled by the event)
        for (Block block : targets) {
            // Skip the original block
            if (block.equals(originBlock)) continue;

            if (block.getType() != targetType) continue;

            Location loc = LocationUtil.normalize(block.getLocation());
            if (loc == null || loc.getWorld() == null) continue;

            // Check world border
            if (!world.getWorldBorder().isInside(loc)) continue;

            // Check if player can build here (basic permission check)
            // Note: full WorldGuard/GriefPrevention integration would need external hooks
            if (!player.hasPermission("ui.enchant.aoe.bypass")) {
                if (!loc.getBlock().isPreferredTool(tool)) continue;
            }

            // 🛡 Protection zones: never break a block of a foreign protection area,
            // and charge the zone one integrity hit per block the enchant refused.
            // (Before, breakNaturally() bypassed BlockBreakEvent entirely, so AoE
            // happily mined through protected bases.)
            if (CoreHooks.isBlockProtected(loc, player)) {
                CoreHooks.chargeProtectionAttempt(loc, 1);
                continue;
            }

            // Break naturally with tool (respects Silk Touch, Fortune)
            // Remember the type BEFORE breaking — after breakNaturally() the block is already AIR
            Material brokenType = block.getType();
            block.breakNaturally(tool, true);

            // Count toward the Woodcutter timed challenge (no BlockBreakEvent fires here)
            CoreHooks.onBlockBroken(player, brokenType);

            // "Ore → stone" mechanic: leave stone instead of ore, like for
            // the block in BlockBreakEvent (otherwise holes remain in the veins)
            CoreHooks.scheduleOreStone(block, brokenType);

            // Consume integrity as from breaking 1 block (mirrors PlayerItemDamageEvent
            // which the durability system maps to 1 vanilla durability point)
            ItemDurabilityUtil.decreaseItemIntegrity(tool, 1, player);

            // Tool broke from integrity loss — stop, as vanilla would
            if (tool.getAmount() <= 0) break;
        }
    }
}
