package com.ultimateimprovments.mechanics.crafting;

import com.ultimateimprovments.core.Keys;
import com.ultimateimprovments.core.Main;
import com.ultimateimprovments.util.MessageUtil;

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

import java.util.List;

/**
 * Crafting recipe of the Portable Workbench.
 * <p>
 * The recipe is registered like every other custom recipe: visible in the
 * recipe book, but {@link RecipeRegistry}'s gate only lets it craft inside a
 * vanilla Crafter block.
 */
public class PortableWorkbenchCraftListener implements Listener {

    /** Total number of opens before the table is spent. */
    public static final int MAX_CHARGES = 128;

    private static NamespacedKey RECIPE_KEY;

    public static void init() {
        RECIPE_KEY = new NamespacedKey(Main.getInstance(), "portable_workbench");
        registerRecipe();
    }

    /** Builds a fresh Portable Workbench with a full charge counter. */
    public static ItemStack createItem(int charges) {
        ItemStack item = new ItemStack(Material.CRAFTING_TABLE);
        item.editMeta(meta -> applyMeta(meta, charges));
        return item;
    }

    /** Applies name, lore, PDC tag and the charge counter to an existing meta. */
    public static void applyMeta(ItemMeta meta, int charges) {
        int clamped = Math.max(0, Math.min(MAX_CHARGES, charges));

        meta.displayName(MessageUtil.parse("<i:false><white>Portable Workbench *</white>"));

        String chargeColor = clamped > 0 ? "<green>" : "<red>";
        meta.lore(List.of(
                MessageUtil.parse("<i:false><gray>Opens a crafting table anywhere.</gray>"),
                MessageUtil.parse("<i:false><gray>Charges: " + chargeColor + clamped + "</" + chargeColor.substring(1)
                        + "<gray>/" + MAX_CHARGES + "</gray>"),
                clamped > 0
                        ? MessageUtil.parse("<i:false><dark_gray>A spent table cannot be recharged.</dark_gray>")
                        : MessageUtil.parse("<i:false><red>The table is spent — you can throw it away.</red>")
        ));

        meta.getPersistentDataContainer().set(Keys.PORTABLE_WORKBENCH, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(Keys.PORTABLE_WORKBENCH_CHARGES, PersistentDataType.INTEGER, clamped);
        meta.setMaxStackSize(1);
    }

    private static void registerRecipe() {
        Main plugin = Main.getInstance();

        ItemStack result = createItem(MAX_CHARGES);
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
        inv.setResult(createItem(MAX_CHARGES));
    }
}
