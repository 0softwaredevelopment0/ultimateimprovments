package com.ultimateimprovments.combat.weapons.plasma.projectile;

import com.ultimateimprovments.core.hooks.CoreHooks;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public class PlasmaBlockCollisions {

    private PlasmaBlockCollisions() {}

    public static CollisionResult handleBlockCollision(
            Block block,
            Vector direction,
            double speed,
            BlockFace hitFace,
            Player owner
    ) {
        float hardness = block.getType().getHardness();
        Vector faceNormal = hitFace != null
                ? hitFace.getDirection()
                : PlasmaPhysics.incomingFaceNormal(direction);

        if (hardness < 0f) {
            PlasmaEffects.unbreakable(block);
            return new CollisionResult(CollisionType.RICOCHET, speed, faceNormal, null);
        }

        // Never dig through a claim: bounce off the protected block instead.
        if (CoreHooks.isBlockProtected(block.getLocation(), owner)) {
            return new CollisionResult(CollisionType.RICOCHET, speed, faceNormal, null);
        }

        // Enough energy to break in: pierce the block for half its hardness, otherwise bounce.
        if (speed >= hardness) {
            PlasmaEffects.blockBreak(block);
            block.breakNaturally();

            double newSpeed = speed - hardness * PlasmaConstants.PENETRATION_COST_FACTOR;
            return new CollisionResult(
                    CollisionType.PENETRATION,
                    Math.max(PlasmaConstants.MIN_SPEED, newSpeed),
                    null,
                    null
            );
        }

        return new CollisionResult(CollisionType.RICOCHET, speed, faceNormal, null);
    }
}
