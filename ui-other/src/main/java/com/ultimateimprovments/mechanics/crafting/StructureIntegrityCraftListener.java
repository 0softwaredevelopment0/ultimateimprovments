package com.ultimateimprovments.mechanics.crafting;

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

public class StructureIntegrityCraftListener implements Listener {

    private static NamespacedKey KEY;
    private static NamespacedKey RECIPE_KEY;

    public static void init() {
        KEY = new NamespacedKey(Main.getInstance(), "is_structure_integrity_indicator");
        RECIPE_KEY = new NamespacedKey(Main.getInstance(), "structure_integrity_indicator");
        registerRecipe();
    }

    /**
     * Builds the Structure Indicator item (shift+RMB on a structure).
     * The base material is a calibrated sculk sensor: the spyglass base made the
     * item feel like a scoped tool and clashed with the Mob Finder, which also
     * uses SPYGLASS.
     */
    public static ItemStack createItem() {
        ItemStack result = new ItemStack(Material.CALIBRATED_SCULK_SENSOR);
        result.editMeta(meta -> {
            meta.displayName(MessageUtil.parse("<i:false><white>Structure Indicator *</white>"));
            meta.lore(List.of(
                    MessageUtil.parse("<i:false><gray>Shift+RMB on a structure to inspect</gray>"),
                    MessageUtil.parse("<i:false><gray>its stress, degradation, and integrity.</gray>"),
                    MessageUtil.parse(""),
                    MessageUtil.parse("<i:false><dark_gray>Structure tool: the Multimeter</dark_gray>"),
                    MessageUtil.parse("<i:false><dark_gray>reads cables and energy only.</dark_gray>")
            ));
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        });
        return result;
    }

    private static void registerRecipe() {
        Main plugin = Main.getInstance();

        ItemStack result = createItem();

        Bukkit.removeRecipe(RECIPE_KEY);

        ShapedRecipe recipe = new ShapedRecipe(RECIPE_KEY, result);
        recipe.setGroup(RECIPE_KEY.getKey());
        recipe.shape(
                " I ",
                "SGS",
                " I "
        );
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('S', Material.STONE);
        recipe.setIngredient('G', Material.GLASS);

        plugin.getServer().addRecipe(recipe);
        RecipeRegistry.registerRecipe(RECIPE_KEY);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        Recipe recipe = e.getRecipe();
        if (!(recipe instanceof ShapedRecipe sr)) return;
        if (!sr.getKey().equals(RECIPE_KEY)) return;

        CraftingInventory inv = e.getInventory();
        ItemStack result = createItem();
        inv.setResult(result);
    }

    public static NamespacedKey getKey() {
        return KEY;
    }
}
