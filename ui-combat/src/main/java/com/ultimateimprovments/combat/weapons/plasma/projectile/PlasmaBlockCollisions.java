package com.ultimateimprovments.combat.weapons.plasma.projectile;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

public class PlasmaBlockCollisions {

    private PlasmaBlockCollisions() {}

    public static CollisionResult handleBlockCollision(
            Block block,
            Vector direction,
            double speed,
            BlockFace hitFace
    ) {
        float hardness = block.getType().getHardness();
        Vector faceNormal = hitFace != null
                ? hitFace.getDirection()
                : PlasmaPhysics.incomingFaceNormal(direction);

        if (hardness < 0f) {
            PlasmaEffects.unbreakable(block);
            return new CollisionResult(CollisionType.RICOCHET, speed, faceNormal, null);
        }

        // Only flimsy blocks are pierced; anything solid is bounced off. A bolt never gets
        // parked inside world geometry, so the cannon stays predictable.
        double newSpeed = speed - PlasmaConstants.PENETRATION_COST;

        if (hardness <= PlasmaConstants.MAX_PENETRABLE_HARDNESS
                && newSpeed >= PlasmaConstants.MIN_SPEED) {
            PlasmaEffects.blockBreak(block);
            block.breakNaturally();
            return new CollisionResult(CollisionType.PENETRATION, newSpeed, null, null);
        }

        double ricochetSpeed = speed * PlasmaConstants.RICOCHET_SPEED_RETENTION;
        return new CollisionResult(CollisionType.RICOCHET, ricochetSpeed, faceNormal, null);
    }
}
