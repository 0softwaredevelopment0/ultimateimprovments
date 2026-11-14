package com.ultimateimprovments.mechanics.features.scanner;

import com.ultimateimprovments.core.Keys;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Enemy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class ScannerItemListener implements Listener {

    // 1 second cooldown (anti-spam)
    private static final long COOLDOWN_MS = 1000L;
    private static final Map<UUID, Long> cooldowns = new HashMap<>();
    private static long lastCleanup = System.currentTimeMillis();

    @EventHandler
    public void onRightClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = e.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();

        // =========================
        // First check whether the item is a scanner
        // If so — always cancel the event so the default
        // behaviour does not trigger (e.g. ENDER_EYE being thrown)
        // =========================
        boolean isHealthMeter = pdc.has(Keys.HEALTH_METER, PersistentDataType.BYTE);
        boolean isOreFinder = pdc.has(Keys.ORE_FINDER, PersistentDataType.BYTE);
        boolean isMobFinder = pdc.has(Keys.MOB_FINDER, PersistentDataType.BYTE);
        boolean isRadar = pdc.has(Keys.RADAR, PersistentDataType.BYTE);

        if (!isHealthMeter && !isOreFinder && !isMobFinder && !isRadar) return;

        // Cancel ALWAYS, even on cooldown
        e.setCancelled(true);

        // Shift+RMB on the radar cycles its mode instead of scanning
        if (isRadar && player.isSneaking()) {
            cycleRadarMode(player, meta);
            return;
        }

        UUID uid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Global cooldown check (after cancellation — safe)
        Long last = cooldowns.get(uid);
        if (last != null && (now - last) < COOLDOWN_MS) {
            long remaining = ((COOLDOWN_MS - (now - last)) / 1000) + 1;
            player.sendActionBar(MessageUtil.parse("<red>⏳ Подождите " + remaining + " сек.</red>"));
            return;
        }

        cooldowns.put(uid, now);
        cleanupCooldowns();

        // Route to appropriate handler
        if (isHealthMeter) {
            handleHealthMeter(player);
        } else if (isOreFinder) {
            handleOreFinder(player);
        } else if (isMobFinder) {
            handleMobFinder(player);
        } else if (isRadar) {
            handleRadar(player);
        }
    }

    // ========================================================================
    // HEALTH METER
    // ========================================================================
    private void handleHealthMeter(Player player) {
        Entity target = player.getTargetEntity(5);
        if (target == null) {
            player.sendMessage(MessageUtil.parse("<red>❌ Нет существа в пределах 5 блоков!</red>"));
            return;
        }
        if (!(target instanceof LivingEntity living)) {
            player.sendMessage(MessageUtil.parse("<red>❌ Это существо не имеет здоровья!</red>"));
            return;
        }
        if (target instanceof Player) {
            player.sendMessage(MessageUtil.parse("<red>❌ Нельзя проверить здоровье игрока!</red>"));
            return;
        }

        double maxHealth = living.getAttribute(Attribute.MAX_HEALTH).getValue();
        double currentHealth = living.getHealth();
        String hpFormatted = String.format("%.1f", currentHealth);
        String maxHpFormatted = String.format("%.1f", maxHealth);

        // Color based on health %
        String colorTag = healthColor(currentHealth / maxHealth);

        String entityName = living.getType().name().toLowerCase().replace('_', ' ');
        if (!entityName.isEmpty()) {
            entityName = Character.toUpperCase(entityName.charAt(0)) + entityName.substring(1);
        }
        Component custom = living.customName();
                if (custom != null) {
                    entityName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(custom);
                }

        // Health bar (100 chars)
        double pct = currentHealth / maxHealth;
        int filledBars = (int) Math.round(pct * 100);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (i < filledBars) {
                double blockPct = (double) (i + 1) / 100;
                bar.append(healthColor(blockPct)).append("|</").append(healthColor(blockPct).substring(1));
            } else {
                bar.append("<dark_gray>|</dark_gray>");
            }
        }

        player.sendMessage(Component.empty());
        player.sendMessage(MessageUtil.parse("<gold>=== <white>Здоровье существа</white> ==="));
        player.sendMessage(MessageUtil.parse("<gray>Существо: </gray><white>" + entityName + "</white>"));
        player.sendMessage(MessageUtil.parse("<gray>Здоровье: </gray>" + colorTag + hpFormatted + "<reset><gray>/</gray>" + colorTag + maxHpFormatted + "<reset>"));
        player.sendMessage(MessageUtil.parse(bar.toString()));

        // Extended card: armor, attack damage, knockback resistance, effects, gear
        double armor = attribute(living, Attribute.ARMOR);
        if (armor > 0) {
            player.sendMessage(MessageUtil.parse("<gray>Броня: </gray><white>" + String.format("%.1f", armor) + "</white>"));
        }
        double attack = attribute(living, Attribute.ATTACK_DAMAGE);
        if (attack > 0) {
            player.sendMessage(MessageUtil.parse("<gray>Урон: </gray><white>" + String.format("%.1f", attack) + "</white>"));
        }
        double knockback = attribute(living, Attribute.KNOCKBACK_RESISTANCE);
        if (knockback > 0) {
            player.sendMessage(MessageUtil.parse("<gray>Сопр. отбрасыванию: </gray><white>"
                    + String.format("%.0f%%", knockback * 100) + "</white>"));
        }
        var effects = living.getActivePotionEffects();
        if (!effects.isEmpty()) {
            player.sendMessage(MessageUtil.parse("<gray>Эффекты:</gray>"));
            for (var effect : effects) {
                String name = effect.getType().getKey().getKey().replace('_', ' ');
                int amplifier = effect.getAmplifier() + 1;
                int seconds = effect.getDuration() / 20;
                player.sendMessage(MessageUtil.parse("  <gray>▪</gray> <white>" + name + "</white>"
                        + (amplifier > 1 ? " <yellow>" + amplifier + "</yellow>" : "")
                        + " <dark_gray>(" + seconds + "с)</dark_gray>"));
            }
        }

        var equipment = living.getEquipment();
        if (equipment != null) {
            String gear = gearLine(equipment);
            if (!gear.isEmpty()) {
                player.sendMessage(MessageUtil.parse("<gray>Экипировка: </gray><white>" + gear + "</white>"));
            }
        }

        player.sendMessage(MessageUtil.parse("<gold>======================="));
    }

    private static double attribute(LivingEntity living, Attribute attribute) {
        AttributeInstance instance = living.getAttribute(attribute);
        return instance != null ? instance.getValue() : 0.0;
    }

    private static String gearLine(org.bukkit.inventory.EntityEquipment equipment) {
        List<String> parts = new ArrayList<>();
        addGear(parts, equipment.getItemInMainHand());
        addGear(parts, equipment.getHelmet());
        addGear(parts, equipment.getChestplate());
        addGear(parts, equipment.getLeggings());
        addGear(parts, equipment.getBoots());
        return String.join(", ", parts);
    }

    private static void addGear(List<String> parts, ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return;
        String name = stack.getType().name().toLowerCase().replace('_', ' ');
        parts.add(name);
    }

    private static final int[][] GRADIENT_WAYPOINTS = {
        {0x8B, 0x00, 0x00},
        {0xFF, 0x00, 0x00},
        {0xFF, 0x8C, 0x00},
        {0xFF, 0xFF, 0x00},
        {0x00, 0xFF, 0x00},
        {0x00, 0x66, 0x00},
    };

    private static String healthColor(double pct) {
        if (pct <= 0.0) return formatHex(GRADIENT_WAYPOINTS[0]);
        if (pct >= 1.0) return formatHex(GRADIENT_WAYPOINTS[5]);

        double scaled = pct * 5.0;
        int segment = (int) scaled;
        double t = scaled - segment;
        if (segment >= 4) { segment = 4; t = Math.min(t, 1.0); }

        int[] from = GRADIENT_WAYPOINTS[segment];
        int[] to = GRADIENT_WAYPOINTS[segment + 1];
        int r = (int) Math.round(from[0] + (to[0] - from[0]) * t);
        int g = (int) Math.round(from[1] + (to[1] - from[1]) * t);
        int b = (int) Math.round(from[2] + (to[2] - from[2]) * t);
        return formatHex(new int[]{r, g, b});
    }

    private static String formatHex(int[] rgb) {
        return String.format("<#%02X%02X%02X>",
            Math.max(0, Math.min(255, rgb[0])),
            Math.max(0, Math.min(255, rgb[1])),
            Math.max(0, Math.min(255, rgb[2])));
    }

    // ========================================================================
    // ORE FINDER
    // ========================================================================
    private static final Map<Material, String> ORE_MATERIALS = new EnumMap<>(Map.ofEntries(
        Map.entry(Material.COAL_ORE, "Coal"),
        Map.entry(Material.DEEPSLATE_COAL_ORE, "Deepslate Coal"),
        Map.entry(Material.IRON_ORE, "Iron"),
        Map.entry(Material.DEEPSLATE_IRON_ORE, "Deepslate Iron"),
        Map.entry(Material.COPPER_ORE, "Copper"),
        Map.entry(Material.DEEPSLATE_COPPER_ORE, "Deepslate Copper"),
        Map.entry(Material.GOLD_ORE, "Gold"),
        Map.entry(Material.DEEPSLATE_GOLD_ORE, "Deepslate Gold"),
        Map.entry(Material.REDSTONE_ORE, "Redstone"),
        Map.entry(Material.DEEPSLATE_REDSTONE_ORE, "Deepslate Redstone"),
        Map.entry(Material.LAPIS_ORE, "Lapis Lazuli"),
        Map.entry(Material.DEEPSLATE_LAPIS_ORE, "Deepslate Lapis"),
        Map.entry(Material.DIAMOND_ORE, "Diamond"),
        Map.entry(Material.DEEPSLATE_DIAMOND_ORE, "Deepslate Diamond"),
        Map.entry(Material.EMERALD_ORE, "Emerald"),
        Map.entry(Material.DEEPSLATE_EMERALD_ORE, "Deepslate Emerald"),
        Map.entry(Material.NETHER_GOLD_ORE, "Nether Gold"),
        Map.entry(Material.NETHER_QUARTZ_ORE, "Nether Quartz"),
        Map.entry(Material.ANCIENT_DEBRIS, "Ancient Debris")
    ));

    private void handleOreFinder(Player player) {
        int chunkX = player.getChunk().getX();
        int chunkZ = player.getChunk().getZ();
        String worldName = player.getWorld().getName();

        player.sendMessage(MessageUtil.parse(
                "<gray>⛏ Сканирование чанка [</gray><yellow>" + chunkX + ", " + chunkZ + "</yellow><gray>] в </gray><yellow>" + worldName + "</yellow><gray>...</gray>"));

        // The chunk column is ~98 000 blocks. Reading it block by block on the
        // main thread froze the server for a tick spike on every click, so the
        // snapshot is taken here (main thread only) and counted asynchronously.
        ChunkSnapshot snapshot;
        try {
            snapshot = player.getChunk().getChunkSnapshot(true, false, false);
        } catch (RuntimeException ex) {
            player.sendMessage(MessageUtil.parse("<red>❌ Не удалось снять снимок чанка.</red>"));
            return;
        }
        org.bukkit.World world = player.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        UUID uid = player.getUniqueId();

        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            Map<Material, Integer> counts = new EnumMap<>(Material.class);
            int totalOres = 0;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y < maxY; y++) {
                        Material type = snapshot.getBlockType(x, y, z);
                        if (ORE_MATERIALS.containsKey(type)) {
                            counts.merge(type, 1, Integer::sum);
                            totalOres++;
                        }
                    }
                }
            }

            int finalTotalOres = totalOres;
            org.bukkit.Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
                Player online = org.bukkit.Bukkit.getPlayer(uid);
                if (online == null) return;
                sendOreReport(online, chunkX, chunkZ, worldName, counts, finalTotalOres);
            });
        });
    }

    private void sendOreReport(Player player, int chunkX, int chunkZ, String worldName,
                               Map<Material, Integer> counts, int totalOres) {
        player.sendMessage(Component.empty());
        player.sendMessage(MessageUtil.parse("<gold>=== <white>Руды в чанке</white> [<yellow>" + chunkX + ", " + chunkZ + "</yellow>] <gray>(" + worldName + ")</gray> ==="));
        player.sendMessage(MessageUtil.parse("<gray>Всего руд: <white>" + totalOres + "</white></gray>"));

        if (totalOres > 0) {
            player.sendMessage(Component.empty());
            ORE_MATERIALS.entrySet().stream()
                .filter(e -> counts.getOrDefault(e.getKey(), 0) > 0)
                .sorted(Comparator.<Map.Entry<Material, String>>comparingInt(
                    e -> counts.get(e.getKey())).reversed()
                    .thenComparing(Map.Entry.comparingByValue()))
                .forEach(e -> {
                    int count = counts.get(e.getKey());
                    player.sendMessage(MessageUtil.parse(
                            "  <gray>▪</gray> <white>" + e.getValue() + "</white><gray>: <yellow>" + count + "</yellow></gray>"));
                });
        }

        player.sendMessage(MessageUtil.parse("<gold>================================"));
    }

    // ========================================================================
    // MOB FINDER
    // ========================================================================
    private void handleMobFinder(Player player) {
        int chunkX = player.getChunk().getX();
        int chunkZ = player.getChunk().getZ();
        String worldName = player.getWorld().getName();

        player.sendMessage(MessageUtil.parse(
                "<gray>🔍 Сканирование чанка [</gray><yellow>" + chunkX + ", " + chunkZ + "</yellow><gray>] в </gray><yellow>" + worldName + "</yellow><gray>...</gray>"));

        Map<String, Integer> counts = new HashMap<>();
        int totalEntities = 0;
        int hostile = 0;

        for (Entity entity : player.getChunk().getEntities()) {
            if (entity == player) continue;

            String typeName = getEntityCategory(entity);
            if (typeName == null) continue;

            if (entity instanceof Enemy) hostile++;

            counts.merge(typeName, 1, Integer::sum);
            totalEntities++;
        }

        // Spawners of the current chunk — a cheap tile-entity walk, no block loop.
        int spawners = 0;
        for (var tile : player.getChunk().getTileEntities()) {
            if (tile.getType() == Material.SPAWNER) spawners++;
        }

        int dangerThreshold = Main.getInstance().getConfig()
                .getInt("features.mobfinder.danger_threshold", 8);

        player.sendMessage(Component.empty());
        player.sendMessage(MessageUtil.parse("<gold>=== <white>Сущности в чанке</white> [<yellow>" + chunkX + ", " + chunkZ + "</yellow>] <gray>(" + worldName + ")</gray> ==="));
        player.sendMessage(MessageUtil.parse("<gray>Всего сущностей: <white>" + totalEntities + "</white></gray>"));

        if (spawners > 0) {
            player.sendMessage(MessageUtil.parse("<gray>Спавнеры в чанке: <light_purple>" + spawners + "</light_purple></gray>"));
        }

        if (hostile > 0) {
            String color = hostile >= dangerThreshold ? "<red>" : "<yellow>";
            String suffix = hostile >= dangerThreshold ? " <red>⚠ ОПАСНО</red>" : "";
            player.sendMessage(MessageUtil.parse("<gray>Враждебные: " + color + hostile + "</" + color.substring(1) + suffix));
        }

        if (totalEntities > 0) {
            player.sendMessage(Component.empty());
            counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> player.sendMessage(MessageUtil.parse(
                        "  <gray>▪</gray> <white>" + e.getKey() + "</white><gray>: <yellow>" + e.getValue() + "</yellow></gray>")));
        }

        player.sendMessage(MessageUtil.parse("<gold>==================================="));
    }

    private static String getEntityCategory(Entity entity) {
        if (entity instanceof org.bukkit.entity.Item) return null;
        if (entity instanceof org.bukkit.entity.ThrownPotion) return null;

        var type = entity.getType();
        // Skip players, armor stands, item frames, etc.
        return switch (type) {
            case PLAYER, ARMOR_STAND, ITEM_FRAME, GLOW_ITEM_FRAME,
                 LEASH_KNOT, MARKER, INTERACTION, TEXT_DISPLAY,
                 BLOCK_DISPLAY, ITEM_DISPLAY, FALLING_BLOCK,
                 AREA_EFFECT_CLOUD, LIGHTNING_BOLT, PAINTING,
                 EVOKER_FANGS, SHULKER_BULLET, WIND_CHARGE,
                 BREEZE_WIND_CHARGE, EXPERIENCE_ORB,
                 ARROW, SPECTRAL_ARROW, TRIDENT,
                 FIREBALL, SMALL_FIREBALL, DRAGON_FIREBALL,
                 FIREWORK_ROCKET, EGG, SNOWBALL, ENDER_PEARL,
                 LLAMA_SPIT, FISHING_BOBBER -> null;
            default -> {
                String name = type.name().toLowerCase().replace('_', ' ');
                // Capitalize
                String[] words = name.split(" ");
                StringBuilder sb = new StringBuilder();
                for (String w : words) {
                    if (!w.isEmpty()) {
                        sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
                    }
                }
                yield sb.toString().trim();
            }
        };
    }

    // ========================================================================
    // PORTABLE RADAR
    // ========================================================================
    /** Radar filter modes; the index is the stored mode value. */
    private static final String[] RADAR_MODES = {"all", "hostile", "players"};
    private static final String[] RADAR_MODE_LABELS = {"все существа", "только враждебные", "только игроки"};

    /** Shift+RMB: cycles all → hostile → players → all and stores it on the item. */
    private void cycleRadarMode(Player player, ItemMeta meta) {
        String current = meta.getPersistentDataContainer().get(Keys.RADAR_MODE, PersistentDataType.STRING);
        int index = 0;
        for (int i = 0; i < RADAR_MODES.length; i++) {
            if (RADAR_MODES[i].equals(current)) { index = i; break; }
        }
        int next = (index + 1) % RADAR_MODES.length;

        meta.getPersistentDataContainer().set(Keys.RADAR_MODE, PersistentDataType.STRING, RADAR_MODES[next]);
        player.getInventory().getItemInMainHand().setItemMeta(meta);

        player.sendActionBar(MessageUtil.parse(
                "<gray>🎯 Режим радара: <yellow>" + RADAR_MODE_LABELS[next] + "</yellow> "
                        + "<dark_gray>(Shift+ПКМ — сменить)</dark_gray>"));
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
    }

    private static String radarMode(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return RADAR_MODES[0];
        String mode = meta.getPersistentDataContainer().get(Keys.RADAR_MODE, PersistentDataType.STRING);
        return mode != null ? mode : RADAR_MODES[0];
    }

    private static int modeIndex(String mode) {
        for (int i = 0; i < RADAR_MODES.length; i++) {
            if (RADAR_MODES[i].equals(mode)) return i;
        }
        return 0;
    }

    /** True when the entity passes the current radar filter. */
    private static boolean radarMatches(Entity entity, String mode) {
        return switch (mode) {
            case "hostile" -> entity instanceof Enemy;
            case "players" -> entity instanceof Player;
            default -> !(entity instanceof Player);
        };
    }

    private void handleRadar(Player player) {
        int radius = 64;
        Location playerLoc = player.getLocation();
        String mode = radarMode(player.getInventory().getItemInMainHand());

        Entity nearest = null;
        double nearestDist = radius + 1;

        for (Entity e : player.getNearbyEntities(radius, radius, radius)) {
            if (e instanceof org.bukkit.entity.Item) continue;
            if (!e.getWorld().equals(player.getWorld())) continue;
            if (!radarMatches(e, mode)) continue;

            double dist = playerLoc.distance(e.getLocation());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = e;
            }
        }

        if (nearest == null) {
            player.sendMessage(MessageUtil.parse("<red>❌ Нет целей в радиусе 64 блоков ("
                    + RADAR_MODE_LABELS[modeIndex(mode)] + ")!</red>"));
            return;
        }

        // Marker: the found target glows for 5 seconds so it is visible through walls
        final Entity marker = nearest;
        marker.setGlowing(true);
        org.bukkit.Bukkit.getScheduler().runTaskLater(Main.getInstance(), () -> {
            if (marker.isValid()) marker.setGlowing(false);
        }, 100L);

        // Entity info
        String entityName = nearest.getType().name().toLowerCase().replace('_', ' ');
        if (!entityName.isEmpty()) {
            entityName = Character.toUpperCase(entityName.charAt(0)) + entityName.substring(1);
        }
        Component custom = nearest.customName();
                if (custom != null) {
                    entityName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(custom);
                }

        Location loc = nearest.getLocation();
        String worldName = loc.getWorld().getName();
        int distBlocks = (int) Math.round(nearestDist);

        // Direction indicator
        String dirX = loc.getBlockX() >= playerLoc.getBlockX() ? "восток" : "запад";
        String dirZ = loc.getBlockZ() >= playerLoc.getBlockZ() ? "юг" : "север";
        String dirY = loc.getBlockY() >= playerLoc.getBlockY() ? "↑" : "↓";

        // Distance color: <32 = green, 32-48 = yellow, >48 = red
        String distColor;
        String proxLabel;
        if (nearestDist <= 16) {
            distColor = "<green>";
            proxLabel = "<green>Очень близко</green>";
        } else if (nearestDist <= 32) {
            distColor = "<#66FF00>";
            proxLabel = "<#66FF00>Близко</#66FF00>";
        } else if (nearestDist <= 48) {
            distColor = "<yellow>";
            proxLabel = "<yellow>Средне</yellow>";
        } else {
            distColor = "<red>";
            proxLabel = "<red>Далеко</red>";
        }

        // Distance bar (visual — 20 segments, filled based on proximity)
        int barSegments = 20;
        int filled = Math.max(0, barSegments - (int) Math.round(((double) distBlocks / radius) * barSegments));
        StringBuilder distBar = new StringBuilder();
        for (int i = 0; i < barSegments; i++) {
            if (i < filled) {
                double ratio = (double) (i + 1) / barSegments;
                if (ratio <= 0.25) {
                    distBar.append("<red>|</red>");
                } else if (ratio <= 0.5) {
                    distBar.append("<yellow>|</yellow>");
                } else if (ratio <= 0.75) {
                    distBar.append("<#66FF00>|</#66FF00>");
                } else {
                    distBar.append("<green>|</green>");
                }
            } else {
                distBar.append("<dark_gray>|</dark_gray>");
            }
        }

        // Action bar — compact info
        player.sendActionBar(MessageUtil.parse(
                "<gray>🎯 </gray>" + entityName + " " + proxLabel + " <dark_gray>|</dark_gray> " + distColor + distBlocks + "м<reset>"));

        player.sendMessage(Component.empty());
        player.sendMessage(MessageUtil.parse("<gold>=== <white>Портативный радар</white> ==="));
        player.sendMessage(MessageUtil.parse("<gray>Существо: </gray><white>" + entityName + "</white>"));
        player.sendMessage(MessageUtil.parse("<gray>Мир: </gray><yellow>" + worldName + "</yellow>"));
        player.sendMessage(MessageUtil.parse("<gray>Координаты: </gray><white>" + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ() + "</white>"));
        player.sendMessage(MessageUtil.parse("<gray>Расстояние: </gray>" + distColor + distBlocks + "<reset> <gray>блоков</gray>"));
        player.sendMessage(MessageUtil.parse("<gray>Дистанция: </gray>" + distBar.toString()));
        player.sendMessage(MessageUtil.parse("<gray>Направление: </gray><white>" + dirX + " " + dirZ + " " + dirY + "</white>"));
        player.sendMessage(MessageUtil.parse("<gray>Режим: </gray><yellow>" + RADAR_MODE_LABELS[modeIndex(mode)]
                + "</yellow> <dark_gray>(Shift+ПКМ — сменить)</dark_gray>"));
        player.sendMessage(MessageUtil.parse("<gold>==========================="));

        // Ping sound based on distance
        float pitch = (float) Math.max(0.5f, 2.0f - (nearestDist / radius) * 1.5f);
        player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BIT, 0.4f, pitch);
    }

    // ========================================================================
    // COOLDOWN CLEANUP
    // ========================================================================
    private static void cleanupCooldowns() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < 30_000L) return;
        lastCleanup = now;
        cooldowns.entrySet().removeIf(e -> (now - e.getValue()) > 60_000L);
    }
}
