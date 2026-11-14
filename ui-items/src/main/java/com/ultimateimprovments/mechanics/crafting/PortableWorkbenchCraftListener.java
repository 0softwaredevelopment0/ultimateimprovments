package com.ultimateimprovments.mechanics.crafting;

import com.ultimateimprovments.config.MessagesManager;
import com.ultimateimprovments.core.Keys;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.util.MessageUtil;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Crafting recipe and item factory of the Portable Workbench.
 * <p>
 * The recipe is registered like every other custom recipe: visible in the
 * recipe book, but {@code RecipeRegistry}'s gate only lets it craft inside a
 * vanilla Crafter block.
 * <p>
 * Settings live in {@code configs/UI-Items.toml}:
 * <ul>
 *   <li>{@code [portable_workbench]} — {@code enabled}, {@code max_charges};</li>
 *   <li>{@code [messages.portable_workbench]} / {@code [messages_en.portable_workbench]}
 *       — {@code name}, {@code lore} ({@code %charges%}, {@code %max%}).</li>
 * </ul>
 */
public class PortableWorkbenchCraftListener implements Listener {

    /** Charge count used when the config does not say otherwise. */
    public static final int DEFAULT_MAX_CHARGES = 64;

    private static final String DEFAULT_NAME = "<white>Portable Workbench *</white>";
    private static final List<String> DEFAULT_LORE = List.of(
            "<gray>Opens a crafting table anywhere.</gray>",
            "<gray>Charges: <green>%charges%</green><gray>/%max%</gray> <dark_gray>(%percent%%%)</dark_gray>",
            "<gray>Breaks after the last charge.</gray>");

    private static NamespacedKey RECIPE_KEY;

    private static boolean enabled = true;
    private static int maxCharges = DEFAULT_MAX_CHARGES;
    private static String nameFormat = DEFAULT_NAME;
    private static List<String> loreFormats = DEFAULT_LORE;

    public static void init() {
        reloadConfig();
        RECIPE_KEY = new NamespacedKey(Main.getInstance(), "portable_workbench");
        registerRecipe();
    }

    /** Reads the {@code [portable_workbench]} section of {@code configs/UI-Items.toml}. */
    public static void reloadConfig() {
        Main main = Main.getInstance();
        if (main == null) return;

        enabled = main.getConfig().getBoolean("portable_workbench.enabled", true);
        maxCharges = Math.max(1, main.getConfig().getInt("portable_workbench.max_charges", DEFAULT_MAX_CHARGES));

        nameFormat = MessagesManager.getString("portable_workbench.name", DEFAULT_NAME);

        // Same routing as every other addon message: current language, then the
        // other language, then the built-in default (English).
        List<String> rawLore = MessagesManager.getStringList("portable_workbench.lore", DEFAULT_LORE);
        loreFormats = (rawLore == null || rawLore.isEmpty()) ? DEFAULT_LORE : rawLore;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int maxCharges() {
        return maxCharges;
    }

    /** Builds a fresh Portable Workbench with a full charge counter. */
    public static ItemStack createItem() {
        return createItem(maxCharges);
    }

    /** Builds a Portable Workbench with the given number of charges. */
    public static ItemStack createItem(int charges) {
        ItemStack item = new ItemStack(Material.CRAFTING_TABLE);
        item.editMeta(meta -> applyMeta(meta, charges));
        return item;
    }

    /** Applies name, lore, PDC tag and the charge counter to an existing meta. */
    public static void applyMeta(ItemMeta meta, int charges) {
        int clamped = Math.max(0, Math.min(maxCharges, charges));
        int percent = percentOf(clamped);

        meta.displayName(MessageUtil.parse("<i:false>" + nameFormat));

        List<Component> lore = new ArrayList<>(loreFormats.size());
        for (String line : loreFormats) {
            lore.add(MessageUtil.parse("<i:false>" + line
                    .replace("%charges%", String.valueOf(clamped))
                    .replace("%max%", String.valueOf(maxCharges))
                    .replace("%percent%", String.valueOf(percent))));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(Keys.PORTABLE_WORKBENCH, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(Keys.PORTABLE_WORKBENCH_CHARGES, PersistentDataType.INTEGER, clamped);
        meta.setMaxStackSize(1);
    }

    /** Remaining charge percentage (0..100), durability-style. */
    public static int percentOf(int charges) {
        if (maxCharges <= 0) return 0;
        int clamped = Math.max(0, Math.min(maxCharges, charges));
        return (int) Math.round(clamped * 100.0 / maxCharges);
    }

    private static void registerRecipe() {
        Main plugin = Main.getInstance();

        ItemStack result = createItem();
        Bukkit.removeRecipe(RECIPE_KEY);

        ShapedRecipe recipe = new ShapedRecipe(RECIPE_KEY, result);
        recipe.setGroup(RECIPE_KEY.getKey());
        recipe.shape(
                "ILI",
                "LTL",
                "ILI"
        );
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('L', Material.LEATHER);
        recipe.setIngredient('T', Material.CRAFTING_TABLE);

        plugin.getServer().addRecipe(recipe);
        RecipeRegistry.registerRecipe(RECIPE_KEY);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        Recipe recipe = e.getRecipe();
        if (!(recipe instanceof ShapedRecipe sr)) return;
        if (!sr.getKey().equals(RECIPE_KEY)) return;

        CraftingInventory inv = e.getInventory();
        inv.setResult(createItem());
    }
}
