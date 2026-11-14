package com.ultimateimprovments.mechanics.features.world;

import com.ultimateimprovments.core.Keys;
import com.ultimateimprovments.core.Main;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Passive "sixth sense" of the Entity Locator.
 * <p>
 * The item is held (main or off hand) and does <b>not</b> answer with text:
 * <ul>
 *   <li>a short note sound whose pitch rises as the nearest entity gets closer;</li>
 *   <li>a dust trail in the direction of the target, green when close and red
 *       when far.</li>
 * </ul>
 * Only players actually carrying the locator are processed, and nothing is sent
 * to the chat — the old build spammed a message per player per second.
 */
public class EntityLocatorManager extends BukkitRunnable {

    private static EntityLocatorManager instance;
    private static boolean enabled = true;
    private static int scanRadius = 12;

    /** Dust trail length in blocks. */
    private static final int TRAIL_LENGTH = 5;
    /** Number of particles per block of the trail. */
    private static final int PARTICLES_PER_BLOCK = 3;

    public static void init(Main plugin) {
        instance = new EntityLocatorManager();
        reloadConfig();
        int interval = Main.getInstance().getConfig().getInt("features.entitylocator.interval_ticks", 10);
        instance.runTaskTimer(plugin, 20L, Math.max(2, interval));
    }

    public static void reloadConfig() {
        var cfg = Main.getInstance().getConfig().getConfigurationSection("features.entitylocator");
        if (cfg == null) return;
        enabled = cfg.getBoolean("enabled", true);
        scanRadius = Math.max(2, cfg.getInt("scan_radius", 12));
    }

    private boolean isLocatorItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        // In Paper 1.21.4+ hasItemMeta() returns false for fresh items.
        // getItemMeta() always returns non-null for non-AIR items.
        var meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(Keys.LOCATOR, PersistentDataType.BYTE);
    }

    @Override
    public void run() {
        if (!enabled) return;

        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            // Only holders cost anything: the old build scanned every online player.
            if (!isLocatorItem(player.getInventory().getItemInMainHand())
                    && !isLocatorItem(player.getInventory().getItemInOffHand())) {
                continue;
            }
            try {
                pulse(player);
            } catch (RuntimeException ex) {
                // A broken locator tick must never kill the whole scheduler task.
                Main.getInstance().getLogger().warning("[EntityLocator] " + ex.getMessage());
            }
        }
    }

    /** One proximity pulse: sound pitch + short dust trail toward the target. */
    private void pulse(Player player) {
        // Find nearest non-player, non-item entity in the same world
        Entity nearest = null;
        double nearestDist = scanRadius + 1;
        for (Entity e : player.getNearbyEntities(scanRadius, scanRadius, scanRadius)) {
            if (e instanceof Player) continue;
            if (e instanceof org.bukkit.entity.Item) continue;
            if (!e.getWorld().equals(player.getWorld())) continue;
            double dist = player.getLocation().distance(e.getLocation());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = e;
            }
        }

        Location eye = player.getEyeLocation();

        if (nearest == null) {
            // Silence is information: only a very quiet low tick while empty.
            player.playSound(eye, Sound.BLOCK_NOTE_BLOCK_BASS, 0.15f, 0.5f);
            return;
        }

        double ratio = Math.min(1.0, nearestDist / scanRadius); // 0 = close, 1 = far
        float pitch = (float) (2.0 - ratio * 1.3);              // 2.0 close .. 0.7 far
        player.playSound(eye, Sound.BLOCK_NOTE_BLOCK_BIT, 0.45f, pitch);

        spawnTrail(player, eye, nearest.getLocation().add(0, nearest.getHeight() * 0.5, 0), ratio);
    }

    /** Dust line from the player's eyes toward the target (only a few blocks). */
    private void spawnTrail(Player player, Location from, Location to, double ratio) {
        Vector dir = to.toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 0.25) return;
        dir.normalize();

        Color color = ratio <= 0.33 ? Color.fromRGB(0, 220, 0)
                : ratio <= 0.66 ? Color.fromRGB(255, 200, 0)
                : Color.fromRGB(220, 40, 40);
        Particle.DustOptions dust = new Particle.DustOptions(color, 0.9f);

        for (int step = 1; step <= TRAIL_LENGTH; step++) {
            Location point = from.clone().add(dir.clone().multiply(step));
            player.spawnParticle(Particle.DUST, point, PARTICLES_PER_BLOCK, 0.08, 0.08, 0.08, 0.0, dust);
        }
    }
}
