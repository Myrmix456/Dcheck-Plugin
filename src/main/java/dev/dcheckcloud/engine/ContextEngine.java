package dev.dcheckcloud.engine;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ContextEngine {
    public static final double M_CORNER = 0.25;
    public static final double M_NEAR_WALL = 0.5;
    public static final double M_KNOCKBACK = 0.4;
    public static final double M_HIGH_PING = 0.7;
    public static final double M_CRIT = 0.55;
    public static final double M_IN_WATER = 0.4;
    public static final double M_TARGET_SWITCH = 0.5;
    public static final double M_HUMAN_DECEL = 0.5;
    public static final double M_REPEATED = 1.5;
    public static final double M_UNCONFIRMED = 0.35;
    public static final int HIGH_PING_MS = 100;
    public static final double DECEL_THRESHOLD = -2.0;
    public static final double BASE_REACH = 3.0;
    public static final double KB_SPEED = 6.0;
    public static final double PING_JITTER = 1.0;
    public static final double WATER_ALLOWANCE = 0.15;

    private ContextEngine() {
    }

    public static double lagCompensatedReachExcess(double value, CombatContext ctx) {
        double pingSec = ctx.pingMs() / 1000.0;
        double allowance = BASE_REACH;
        if (ctx.victimKnockback()) {
            allowance += KB_SPEED * pingSec;
        }
        if (ctx.inWater()) {
            allowance += WATER_ALLOWANCE;
        }
        allowance += PING_JITTER * pingSec;
        return Math.max(0.0, value - allowance);
    }

    public static double reachConfidence(double value, CombatContext ctx) {
        double excess = lagCompensatedReachExcess(value, ctx);
        return excess <= 0.0 ? 0.05 : Math.min(1.0, 0.2 + excess);
    }

    public static Map<String, Double> modifiersFor(SignalType type, CombatContext ctx) {
        return modifiersFor(type, ctx, HIGH_PING_MS);
    }

    public static Map<String, Double> modifiersFor(SignalType type, CombatContext ctx, int highPingMs) {
        Map<String, Double> mods = new LinkedHashMap<>();
        boolean snapLike = type == SignalType.HARD_SNAP || type == SignalType.HITBOX_MISS;
        boolean reachLike = snapLike || type == SignalType.REACH;

        if (snapLike && ctx.inCorner()) {
            mods.put("corner", M_CORNER);
        } else if (snapLike && ctx.nearWall()) {
            mods.put("near_wall", M_NEAR_WALL);
        }
        if (reachLike && ctx.victimKnockback()) {
            mods.put("knockback", M_KNOCKBACK);
        }
        if (reachLike && ctx.pingMs() > highPingMs) {
            mods.put("high_ping", M_HIGH_PING);
        }
        if ((type == SignalType.HARD_SNAP || type == SignalType.KINEMATIC_ROBOTIC) && ctx.isCrit()) {
            mods.put("crit", M_CRIT);
        }
        if (ctx.inWater() && (snapLike || type == SignalType.AIM_CONSISTENCY
                || type == SignalType.KINEMATIC_ROBOTIC || type == SignalType.SILENT_ROTATION)) {
            mods.put("in_water", M_IN_WATER);
        }
        if (snapLike && ctx.targetSwitch()) {
            mods.put("target_switch", M_TARGET_SWITCH);
        }
        if ((type == SignalType.HARD_SNAP || type == SignalType.KINEMATIC_ROBOTIC)
                && ctx.yawAccel() < DECEL_THRESHOLD) {
            mods.put("human_decel", M_HUMAN_DECEL);
        }
        boolean patternSignal = type == SignalType.AIM_CONSISTENCY
                || type == SignalType.KINEMATIC_ROBOTIC
                || type == SignalType.GCD_OFFSTEP;
        if (patternSignal && !ctx.repeatedPattern()) {
            mods.put("unconfirmed_pattern", M_UNCONFIRMED);
        }
        if (ctx.repeatedPattern()) {
            mods.put("repeated_pattern", M_REPEATED);
        }
        return mods;
    }
}
