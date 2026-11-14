package com.ultimateimprovments.combat.weapons.plasma;

import com.ultimateimprovments.combat.weapons.plasma.projectile.*;
import com.ultimateimprovments.combat.weapons.core.BaseProjectile;
import com.ultimateimprovments.combat.weapons.core.ProjectileManager;
import com.ultimateimprovments.combat.weapons.core.ProjectileType;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * Orchestrator: ticking, state, applying collisions.
 * Block physics — ricochet / piercing; escape is only a fallback safeguard.
 * <p>
 * One tick may resolve several blocks along the flight path ({@link PlasmaConstants#MAX_SUBSTEPS}),
 * so a fast bolt neither skips through a wall nor hovers in front of it.
 */
public class PlasmaProjectile implements BaseProjectile {

    private final Player owner;

    private Location loc;
    private Vector dir;

    private int life = 0;
    private boolean dead = false;

    private int stabilityTicks = 0;
    private int bounces = 0;

    private double speed = PlasmaConstants.START_SPEED;

    private final Random random = new Random();

    public PlasmaProjectile(Player owner) {
        this.owner = owner;
        this.loc = owner.getEyeLocation().clone();
        this.dir = owner.getLocation().getDirection().normalize();
    }

    public static void spawn(Player player) {
        ProjectileManager.add(new PlasmaProjectile(player));
    }

    @Override
    public ProjectileType getType() {
        return ProjectileType.PLASMA;
    }

    @Override
    public boolean isDead() {
        return dead;
    }

    @Override
    public void tick() {
        if (dead) {
            return;
        }

        life++;

        if (life > PlasmaConstants.MAX_LIFE) {
            dead = true;
            return;
        }

        if (speed >= PlasmaConstants.MAX_SAFE_SPEED) {
            PlasmaEffects.explode(loc);
            dead = true;
            return;
        }

        // Fallback safeguard: get out of a block along the flight direction, not back where we came from.
        if (!PlasmaPhysics.isAir(loc)) {
            loc = PlasmaPhysics.escapeToAir(loc, dir);
        }

        if (!PlasmaPhysics.isAir(loc)) {
            // Still buried (e.g. inside a huge solid mass) — fizzle instead of flying through walls.
            dead = true;
            return;
        }

        // Drift only in free flight (not right after hitting a wall)
        if (stabilityTicks > 0) {
            stabilityTicks--;
        } else {
            PlasmaPhysics.applyInstability(dir, random);
        }

        advance();

        if (!dead) {
            PlasmaEffects.plasmaTrail(loc);
        }
    }

    /**
     * Moves the bolt along its path for this tick, resolving every block/entity it meets.
     */
    private void advance() {
        double remaining = speed;

        for (int step = 0;
             step < PlasmaConstants.MAX_SUBSTEPS && remaining > 1.0E-4 && !dead;
             step++) {

            Location start = loc.clone();
            PlasmaRaycast.BlockHit hit = PlasmaRaycast.cast(start, dir, remaining);

            if (hit != null) {
                CollisionResult result = PlasmaBlockCollisions.handleBlockCollision(
                        hit.block(),
                        dir,
                        speed,
                        hit.face()
                );

                applyBlockCollision(result, hit);

                if (dead || result.type != CollisionType.PENETRATION) {
                    return;
                }

                // Continue through the pierced block within the same tick.
                double travelled = start.distance(loc);
                remaining = Math.min(speed, Math.max(0, remaining - travelled));
                continue;
            }

            Location stepEnd = start.clone().add(dir.clone().multiply(remaining));

            if (handleEntityCollision(stepEnd)) {
                return;
            }

            loc = stepEnd;
            return;
        }
    }

    private void applyBlockCollision(CollisionResult result, PlasmaRaycast.BlockHit blockHit) {
        Vector faceNormal = blockHit.faceNormal();

        switch (result.type) {
            case RICOCHET -> {
                speed = PlasmaConstants.clampSpeed(result.newSpeed);
                dir = PlasmaPhysics.ricochetOffBlock(dir, faceNormal, random);
                loc = PlasmaPhysics.positionAfterBlockRicochet(blockHit.block(), blockHit.face());
                stabilityTicks = PlasmaConstants.POST_BLOCK_HIT_STABILITY_TICKS;
                bounces++;

                if (bounces >= PlasmaConstants.MAX_BOUNCES) {
                    dead = true;
                    return;
                }

                PlasmaEffects.ricochet(loc);
            }
            case PENETRATION -> {
                speed = PlasmaConstants.clampSpeed(result.newSpeed);
                loc = PlasmaPhysics.positionAfterBlockPenetration(blockHit.block(), dir);
                stabilityTicks = 2;
            }
            case STOP -> dead = true;
        }
    }

    /** @return true when the bolt hit something and its travel for this tick is over. */
    private boolean handleEntityCollision(Location stepEnd) {
        for (Entity entity : stepEnd.getWorld().getNearbyEntities(stepEnd, 0.6, 0.6, 0.6)) {
            if (PlasmaCollision.isOwnerProtected(entity, owner, life)) {
                continue;
            }

            CollisionResult result = PlasmaEntityCollisions.handleEntityCollision(
                    entity, owner, loc, dir, speed
            );
            applyEntityCollision(result);

            if (dead || result.type != CollisionType.PENETRATION) {
                return true;
            }

            // Pierced the entity: the rest of the travel happens next tick from the new point.
            return true;
        }

        return false;
    }

    private void applyEntityCollision(CollisionResult result) {
        speed = PlasmaConstants.clampSpeed(result.newSpeed);

        switch (result.type) {
            case RICOCHET -> {
                Vector hitNormal = result.hitNormal != null
                        ? result.hitNormal
                        : PlasmaPhysics.incomingFaceNormal(dir);
                dir = PlasmaPhysics.ricochetOffEntity(dir, hitNormal, random);
                loc.add(dir.clone().multiply(0.25));
                PlasmaEffects.ricochet(loc);
            }
            case PENETRATION -> {
                if (result.moveVector != null) {
                    loc.add(result.moveVector);
                }
            }
            case STOP -> dead = true;
        }
    }
}
