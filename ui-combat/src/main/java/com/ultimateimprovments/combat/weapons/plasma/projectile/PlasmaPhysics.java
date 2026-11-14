package com.ultimateimprovments.combat.weapons.plasma.projectile;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.Random;

public class PlasmaPhysics {

    private PlasmaPhysics() {}

    public static boolean isPassable(Block block) {
        if (block == null) {
            return false;
        }
        var type = block.getType();
        // Air, and also fluids: a plasma bolt must fly through water/lava, not bounce off them.
        return type.isAir() || type == Material.WATER || type == Material.LAVA;
    }

    public static boolean isPassable(Location location) {
        return isPassable(location.getBlock());
    }

    public static void applyInstability(Vector dir, Random random) {
        Vector drift = new Vector(
                (random.nextDouble() - 0.5) * PlasmaConstants.INSTABILITY,
                (random.nextDouble() - 0.5) * PlasmaConstants.INSTABILITY,
                (random.nextDouble() - 0.5) * PlasmaConstants.INSTABILITY
        );
        dir.add(drift).normalize();
    }

    public static Vector incomingFaceNormal(Vector direction) {
        double ax = Math.abs(direction.getX());
        double ay = Math.abs(direction.getY());
        double az = Math.abs(direction.getZ());

        if (ax >= ay && ax >= az) {
            return new Vector(-Math.signum(direction.getX()), 0, 0);
        }
        if (ay >= az) {
            return new Vector(0, -Math.signum(direction.getY()), 0);
        }
        return new Vector(0, 0, -Math.signum(direction.getZ()));
    }

    public static Vector entityHitNormal(Entity entity, Location projectileLocation) {
        Location center = entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
        Vector normal = projectileLocation.toVector().subtract(center.toVector());
        if (normal.lengthSquared() < 1.0E-6) {
            return incomingFaceNormal(projectileLocation.getDirection());
        }
        return normal.normalize();
    }

    /**
     * Block ricochet: reflection + slight noise + guaranteed movement away from the face.
     */
    public static Vector ricochetOffBlock(Vector direction, Vector faceNormal, Random random) {
        Vector reflected = reflect(direction, faceNormal);

        Vector noise = new Vector(
                (random.nextDouble() - 0.5) * PlasmaConstants.BLOCK_RICOCHET_NOISE,
                (random.nextDouble() - 0.5) * PlasmaConstants.BLOCK_RICOCHET_NOISE,
                (random.nextDouble() - 0.5) * PlasmaConstants.BLOCK_RICOCHET_NOISE
        );

        reflected.add(noise);
        return ensureMovingAwayFromWall(reflected, faceNormal);
    }

    /** Ricochet off an entity (more noise). */
    public static Vector ricochetOffEntity(Vector direction, Vector hitNormal, Random random) {
        Vector result = reflect(direction, hitNormal);

        Vector noise = new Vector(
                (random.nextDouble() - 0.5) * 0.2,
                (random.nextDouble() - 0.5) * 0.2,
                (random.nextDouble() - 0.5) * 0.2
        );

        return result.add(noise).normalize();
    }

    private static Vector reflect(Vector direction, Vector faceNormal) {
        if (faceNormal == null || faceNormal.lengthSquared() < 1.0E-8) {
            return direction.clone().multiply(-1).normalize();
        }

        Vector normal = faceNormal.clone().normalize();
        double dot = direction.dot(normal);
        return direction.clone()
                .subtract(normal.multiply(2 * dot))
                .normalize();
    }

    /**
     * The normal points from the face toward the projectile; after reflection the velocity must move away from the wall.
     * The out-of-wall component is topped up instead of re-reflected: flipping a direction that already points
     * outward (but less than {@link PlasmaConstants#MIN_OUTWARD_DOT}) would send the bolt straight back into the wall.
     */
    public static Vector ensureMovingAwayFromWall(Vector direction, Vector faceNormal) {
        Vector n = faceNormal.clone().normalize();
        Vector dir = direction.clone();

        if (dir.lengthSquared() < 1.0E-8) {
            return n;
        }
        dir.normalize();

        double outward = dir.dot(n);
        if (outward < PlasmaConstants.MIN_OUTWARD_DOT) {
            dir.add(n.clone().multiply(PlasmaConstants.MIN_OUTWARD_DOT - outward));
            if (dir.lengthSquared() < 1.0E-8) {
                return n;
            }
            dir.normalize();
        }

        return dir;
    }

    /** Just outside the hit face (the adjacent air cell), not the air-cell centre blocks away. */
    public static Location positionAfterBlockRicochet(Block block, BlockFace hitFace) {
        return block.getLocation()
                .add(0.5, 0.5, 0.5)
                .add(hitFace.getDirection().clone().multiply(0.52));
    }

    /** First position outside the pierced block along the travel direction (works for diagonal hits too). */
    public static Location positionAfterBlockPenetration(Block block, Vector direction) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        Vector dir = direction.clone();

        if (dir.lengthSquared() < 1.0E-8) {
            return center;
        }
        dir.normalize();

        for (double step = 0.05; step <= 2.0; step += 0.05) {
            Location candidate = center.clone().add(dir.clone().multiply(step));
            if (!candidate.getBlock().equals(block)) {
                return candidate.add(dir.clone().multiply(0.05));
            }
        }

        return center.add(dir.multiply(0.6));
    }

    /** Fallback safeguard: only if the coordinates are inside a solid block. */
    public static Location escapeToAir(Location location, Vector preferredOutward) {
        if (isPassable(location)) {
            return location;
        }

        Location current = location.clone();
        Vector preferred = preferredOutward != null && preferredOutward.lengthSquared() > 1.0E-8
                ? preferredOutward.clone().normalize()
                : new Vector(0, 1, 0);

        Vector[] directions = {
                preferred,
                new Vector(1, 0, 0),
                new Vector(-1, 0, 0),
                new Vector(0, 1, 0),
                new Vector(0, -1, 0),
                new Vector(0, 0, 1),
                new Vector(0, 0, -1),
        };

        for (Vector outward : directions) {
            for (double step = PlasmaConstants.ESCAPE_STEP;
                 step <= PlasmaConstants.MAX_ESCAPE_DISTANCE;
                 step += PlasmaConstants.ESCAPE_STEP) {

                Location candidate = current.clone().add(outward.clone().multiply(step));
                if (isPassable(candidate)) {
                    return candidate;
                }
            }
        }

        return current;
    }
}
