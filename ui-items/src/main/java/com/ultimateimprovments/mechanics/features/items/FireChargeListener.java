package com.ultimateimprovments.mechanics.features.items;

import com.ultimateimprovments.core.Keys;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.util.SoundUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Vanilla {@code minecraft:fire_charge} shoots a fireball when used in the air.
 * <p>
 * Right-clicking a <b>block</b> keeps the vanilla behaviour (placing fire); only
 * {@link Action#RIGHT_CLICK_AIR} is handled here. One charge is consumed per
 * shot (creative mode is free), the projectile flies straight along the
 * player's view without gravity and explodes on impact with the configured
 * power.
 * <p>
 * Settings: {@code [fireball]} in {@code configs/UI-Items.toml}.
 */
public final class FireChargeListener implements Listener {

    private static final Map<UUID, Long> cooldowns = new HashMap<>();

    private static boolean enabled = true;
    private static long cooldownMs = 1000L;
    private static double speed = 1.5;
    private static float explosionPower = 1.0f;
    private static boolean incendiary = true;
    private static boolean blockDamage = true;
    private static boolean selfDamage = false;
    private static Sound shootSound = Sound.ENTITY_GHAST_SHOOT;

    public static void init(Main plugin) {
        reloadConfig();
        plugin.getServer().getPluginManager().registerEvents(new FireChargeListener(), plugin);
    }

    /** Reads the {@code [fireball]} section of {@code configs/UI-Items.toml}. */
    public static void reloadConfig() {
        Main main = Main.getInstance();
        if (main == null) return;
        FileConfiguration cfg = main.getConfig();

        enabled = cfg.getBoolean("fireball.enabled", true);
        cooldownMs = Math.max(0L, cfg.getLong("fireball.cooldown_ms", 1000L));
        speed = Math.max(0.1, cfg.getDouble("fireball.speed", 1.5));
        explosionPower = (float) Math.max(1.0, Math.min(127.0, cfg.getDouble("fireball.explosion_power", 1.0)));
        incendiary = cfg.getBoolean("fireball.incendiary", true);
        blockDamage = cfg.getBoolean("fireball.block_damage", true);
        selfDamage = cfg.getBoolean("fireball.self_damage", false);
        shootSound = SoundUtil.getSound(cfg.getString("fireball.shoot_sound"),
                Sound.ENTITY_GHAST_SHOOT);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        // Only right-click in the air: clicking a block must keep the vanilla
        // fire placement. Fired once per hand — the off-hand pass would double-shot.
        if (e.getAction() != Action.RIGHT_CLICK_AIR) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!enabled) return;

        Player player = e.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.FIRE_CHARGE || item.getAmount() <= 0) return;

        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            e.setCancelled(true);
            return;
        }
        cooldowns.put(player.getUniqueId(), now);
        e.setCancelled(true);

        if (player.getGameMode() != GameMode.CREATIVE) {
            int left = item.getAmount() - 1;
            if (left <= 0) {
                player.getInventory().setItemInMainHand(null);
            } else {
                item.setAmount(left);
                player.getInventory().setItemInMainHand(item);
            }
        }

        shoot(player);
    }

    private static void shoot(Player player) {
        Vector direction = player.getLocation().getDirection().normalize();
        Location spawn = player.getEyeLocation().add(direction.clone().multiply(0.5));

        LargeFireball fireball = player.getWorld().spawn(spawn, LargeFireball.class);
        fireball.setShooter(player);
        fireball.setYield(explosionPower);
        fireball.setIsIncendiary(incendiary);
        fireball.setDirection(direction);
        // Straight flight: no gravity and no acceleration, only the initial velocity.
        fireball.setAcceleration(new Vector());
        fireball.setVelocity(direction.clone().multiply(speed));
        fireball.setGravity(false);
        fireball.getPersistentDataContainer().set(Keys.FIREBALL, PersistentDataType.BYTE, (byte) 1);

        player.getWorld().playSound(player.getLocation(), shootSound, 1.0f, 1.0f);
    }

    /** Honours {@code block_damage = false} without touching vanilla fireballs. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (blockDamage || !isOurFireball(e.getEntity())) return;
        e.blockList().clear();
    }

    /** Honours {@code self_damage = false} so point-blank shots do not hurt the shooter. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSelfDamage(EntityDamageEvent e) {
        if (selfDamage) return;
        if (!(e instanceof EntityDamageByEntityEvent byEntity)) return;
        if (!(e.getEntity() instanceof Player player)) return;
        if (!isOurFireball(byEntity.getDamager())) return;
        if (!(byEntity.getDamager() instanceof Fireball fireball)) return;
        Entity shooter = fireball.getShooter() instanceof Entity entity ? entity : null;
        if (player.equals(shooter)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cooldowns.remove(e.getPlayer().getUniqueId());
    }

    private static boolean isOurFireball(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(Keys.FIREBALL, PersistentDataType.BYTE);
    }
}
