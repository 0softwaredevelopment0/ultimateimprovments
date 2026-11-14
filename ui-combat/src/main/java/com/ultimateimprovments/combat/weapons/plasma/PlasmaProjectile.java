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
        if (!PlasmaPhysics.isPassable(loc)) {
            loc = PlasmaPhysics.escapeToAir(loc, dir);
        }

        if (!PlasmaPhysics.isPassable(loc)) {
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

        Location before = loc.clone();
        advance();

        if (!dead) {
            PlasmaEffects.plasmaTrail(before, loc, remainingCharge());
        }
    }

    /** 1.0 = fresh bolt, 0.0 = about to fizzle (by lifetime or by used-up bounces). */
    private double remainingCharge() {
        double lifeLeft = 1.0 - (double) life / PlasmaConstants.MAX_LIFE;
        double bouncesLeft = 1.0 - (double) bounces / PlasmaConstants.MAX_BOUNCES;
        return Math.min(lifeLeft, bouncesLeft);
    }

    /**
     * Moves the bolt along its path for this tick, resolving every block/entity it meets.
     */
    private void advance() {
        double remaining = speed;

        for (int step = 0;
             step < PlasmaConstants.MAX_SUBSTEPS && remaining > 1.0E-4 && !dead;
             step++) {

            // A corner hit may leave us on the wrong side of the reported face — recover instead of tunnelling.
            if (!PlasmaPhysics.isPassable(loc)) {
                loc = PlasmaPhysics.escapeToAir(loc, dir);
                if (!PlasmaPhysics.isPassable(loc)) {
                    dead = true;
                    return;
                }
            }

            Location start = loc.clone();
            PlasmaRaycast.BlockHit hit = PlasmaRaycast.cast(start, dir, remaining);
            Location stepEnd = start.clone().add(dir.clone().multiply(remaining));

            if (hit != null) {
                // Entities in front of the block still take the hit.
                if (probeEntities(start, Math.min(remaining, hit.distance()))) {
                    return;
                }

                CollisionResult result = PlasmaBlockCollisions.handleBlockCollision(
                        hit.block(),
                        dir,
                        speed,
                        hit.face(),
                        owner
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

            if (probeEntities(start, remaining)) {
                return;
            }

            loc = stepEnd;
            return;
        }
    }

    /**
     * Samples the whole travelled segment instead of only its end point, so a fast
     * bolt cannot fly between ticks straight through an entity.
     *
     * @return true when an entity was hit and the travel for this tick is over.
     */
    private boolean probeEntities(Location start, double distance) {
        if (distance <= 0) {
            return false;
        }

        int probes = Math.max(1, (int) Math.ceil(distance / 0.75));
        probes = Math.min(probes, PlasmaConstants.MAX_ENTITY_PROBES);

        Vector step = dir.clone().multiply(distance / probes);

        for (int i = 1; i <= probes; i++) {
            Location probe = start.clone().add(step.clone().multiply(i));
            if (handleEntityCollision(probe)) {
                return true;
            }
        }

        return false;
    }

    private void applyBlockCollision(CollisionResult result, PlasmaRaycast.BlockHit blockHit) {
        Vector faceNormal = blockHit.faceNormal();

        switch (result.type) {
            case RICOCHET -> {
                speed = PlasmaConstants.clampSpeed(result.newSpeed);
                dir = PlasmaPhysics.ricochetOffBlock(dir, faceNormal, random);
                loc = PlasmaPhysics.positionAfterBlockRicochet(blockHit.block(), blockHit.face());

                if (!PlasmaPhysics.isPassable(loc)) {
                    loc = PlasmaPhysics.escapeToAir(loc, faceNormal);
                }

                if (!PlasmaPhysics.isPassable(loc)) {
                    dead = true;
                    return;
                }

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

                if (!PlasmaPhysics.isPassable(loc)) {
                    loc = PlasmaPhysics.escapeToAir(loc, dir);
                }

                stabilityTicks = 2;
            }
            case STOP -> dead = true;
        }
    }

    /** @return true when the bolt hit something and its travel for this tick is over. */
    private boolean handleEntityCollision(Location probe) {
        for (Entity entity : probe.getWorld().getNearbyEntities(probe, 0.6, 0.6, 0.6)) {
            if (PlasmaCollision.isOwnerProtected(entity, owner)) {
                continue;
            }

            // Continue from the actual contact point, not from the tick start.
            loc = probe.clone();

            CollisionResult result = PlasmaEntityCollisions.handleEntityCollision(
                    entity, owner, loc, dir, speed
            );
            applyEntityCollision(result);

            // One entity hit ends the tick: the bolt bounces away or pierces onward from here.
            return true;
        }

        return false;
    }

    private void applyEntityCollision(CollisionResult result) {
        speed = PlasmaConstants.clampSpeed(result.newSpeed);

        switch (result.type) {
            case RICOCHET -> {
                speed = Math.min(speed, PlasmaConstants.MAX_SPEED_CAP);

                Vector hitNormal = result.hitNormal != null
                        ? result.hitNormal
                        : PlasmaPhysics.incomingFaceNormal(dir);
                dir = PlasmaPhysics.ricochetOffEntity(dir, hitNormal, random);
                loc.add(dir.clone().multiply(0.25));
                bounces++;

                if (bounces >= PlasmaConstants.MAX_BOUNCES) {
                    dead = true;
                    return;
                }

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
