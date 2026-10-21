package dev.dcheckcloud.engine;

public record CombatContext(boolean inCorner, boolean nearWall, boolean victimKnockback,
                            int pingMs, boolean isCrit, boolean targetSwitch,
                            boolean repeatedPattern, boolean inWater, double yawAccel) {
}
