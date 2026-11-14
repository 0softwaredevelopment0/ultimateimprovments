package com.ultimateimprovments.combat.weapons.plasma.projectile;

public class PlasmaConstants {

    // SPEED = ENERGY (penetration / bonus on ricochet); entity damage is separate.
    public static final double START_SPEED = 1.2;

    public static final double MIN_SPEED = 0.35;

    public static final double MAX_SAFE_SPEED = 50.0;

    /** Damage dealt to a living entity, HP (2 HP = 1 heart). */
    public static final double ENTITY_DAMAGE = 6.0;

    public static final double INSTABILITY = 0.01;

    /** Direction noise on block ricochet (lower — more stable). */
    public static final double BLOCK_RICOCHET_NOISE = 0.04;

    /** Bonus speed multiplier when bouncing off an entity. */
    public static final double RICOCHET_SPEED_MULTIPLIER = 1.1;

    /** After this many bounces the bolt fizzles out instead of orbiting a cave forever. */
    public static final int MAX_BOUNCES = 8;

    /** Minimum "away from the wall" velocity component after a ricochet. */
    public static final double MIN_OUTWARD_DOT = 0.15;

    /** Energy spent to pierce one block: half of its hardness (the other half is absorbed by the block). */
    public static final double PENETRATION_COST_FACTOR = 0.5;

    /** Energy spent to pierce a killed entity. */
    public static final double ENTITY_PENETRATION_COST = 0.25;

    /** Ticks without drift after hitting a block. */
    public static final int POST_BLOCK_HIT_STABILITY_TICKS = 5;

    /** How many blocks a single tick may resolve (fast bolts must not skip through walls). */
    public static final int MAX_SUBSTEPS = 8;

    /** Push-out step (fallback safeguard). */
    public static final double ESCAPE_STEP = 0.5;

    public static final double MAX_ESCAPE_DISTANCE = 2.0;

    public static final int OWNER_IMMUNITY_TICKS = 40;

    public static final int MAX_LIFE = 1200;

    public static double clampSpeed(double speed) {
        return Math.max(MIN_SPEED, speed);
    }

    private PlasmaConstants() {}
}
