package com.ultimateimprovments.combat.weapons.plasma.projectile;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class PlasmaCollision {

    private PlasmaCollision() {}

    /**
     * The shooter is never hit by their own bolt — it flies through them instead.
     * A ricochet returning to the shooter is a common case and must not cost health.
     */
    public static boolean isOwnerProtected(Entity entity, Player owner) {
        return entity instanceof Player p
                && p.getUniqueId().equals(owner.getUniqueId());
    }
}
