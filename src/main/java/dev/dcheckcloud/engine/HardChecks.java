package dev.dcheckcloud.engine;

import dev.dcheckcloud.config.DCheckConfig;
import dev.dcheckcloud.data.PlayerData;
import dev.dcheckcloud.feature.FeatureSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class HardChecks {
    public record Result(List<Signal> signals, CombatContext context, boolean cancelDamage,
                         double eventRisk, double reach, float angleOffsetDeg, boolean rayHit,
                         String topSignal) {
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (Signal s : signals) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(s.type().name()).append(' ').append(s.detail());
            }
            return sb.toString();
        }
    }

    private HardChecks() {
    }

    public static Result evaluate(Player attacker, LivingEntity victim, PlayerData data,
                                  KinematicHistory.RaycastHitResult raycast, double compensatedReach,
                                  DCheckConfig cfg, long tick) {
        KinematicHistory history = data.getKinematics();
        float angleOffset = raycast.angleOffsetDeg();
        boolean rayHit = raycast.hit();
        float yawDelta5t = history.getYawDelta(5);
        float yawSpeedChange = history.getYawSpeedChange();
        float angularVel = history.getAngularVelocity();
        long timeSinceRot = history.getTimeSinceLastRotation();

        boolean strict = cfg.hardFpGuard;
        float snapVel = strict ? 75.0f : 65.0f;
        float silentAngle = strict ? 20.0f : 12.0f;
        float wideMissAngle = strict ? 45.0f : 35.0f;
        float distMissAngle = strict ? 25.0f : 15.0f;
        float aimVarMax = strict ? 2.2f : 3.0f;
        float aimAngleMax = strict ? 8.0f : 12.0f;
        float roboticAccel = strict ? 25.0f : 15.0f;

        boolean hardSnap = angularVel > snapVel && timeSinceRot <= 40L
                && yawSpeedChange > 15.0f && angleOffset < 6.0f;
        boolean roboticSnap = Math.abs(yawDelta5t) > 40.0f && yawSpeedChange > 15.0f && angleOffset < 6.0f;

        int solidSides = countSolidSides(victim.getLocation());
        boolean nearWall = solidSides >= 1;
        boolean inCorner = solidSides >= 2;
        boolean inWater = isEntityInWater(attacker) || isEntityInWater(victim);
        boolean isCrit = isCritical(attacker, history);
        boolean victimKnockback = isBeingKnockedBack(victim);
        int ping = safePing(attacker);

        boolean[] pattern = data.updatePattern(victim.getUniqueId(), roboticSnap,
                (float) data.getLastProbability());
        CombatContext ctx = new CombatContext(inCorner, nearWall, victimKnockback, ping,
                isCrit, pattern[0], pattern[1], inWater, yawSpeedChange);

        double excess = ContextEngine.lagCompensatedReachExcess(compensatedReach, ctx);
        boolean cancelDamage = compensatedReach >= cfg.hardReachFlag && excess > 0.0;

        List<Signal> signals = new ArrayList<>();
        boolean passive = data.isPassiveTargetMode();

        if (compensatedReach > cfg.hardMaxReach && excess > 0.01) {
            signals.add(Signal.of(SignalType.REACH, SignalType.REACH.defaultConfidence(),
                    compensatedReach, String.format(Locale.ROOT, "reach %.2fm (lag-comp)", compensatedReach)));
        }

        if (!rayHit && angularVel < 0.2f && angleOffset > silentAngle
                && (!strict || ping <= 100)
                && !inWater && !inCorner && !nearWall) {
            signals.add(Signal.of(SignalType.SILENT_ROTATION, SignalType.SILENT_ROTATION.defaultConfidence(),
                    angleOffset, String.format(Locale.ROOT, "silent packet rotation (0 deg/t rot, %.1f deg angle)",
                    angleOffset)));
        }

        boolean wideMiss = !rayHit && angleOffset > wideMissAngle && !inWater && !inCorner && !nearWall;
        boolean distanceMiss = !rayHit && angleOffset > distMissAngle && raycast.distance() >= 1.8f
                && !inWater && !inCorner && !nearWall;
        if (wideMiss || distanceMiss) {
            signals.add(Signal.of(SignalType.HITBOX_MISS, SignalType.HITBOX_MISS.defaultConfidence(),
                    angleOffset, String.format(Locale.ROOT, "ray miss @ %.1f deg (dist %.1fm)",
                    angleOffset, (double) raycast.distance())));
        }

        if (hardSnap) {
            signals.add(Signal.of(SignalType.HARD_SNAP, SignalType.HARD_SNAP.defaultConfidence(),
                    angularVel, String.format(Locale.ROOT, "snap %.0f deg/t accel %.0f",
                    angularVel, yawSpeedChange)));
        }

        if (!passive) {
            float rotVar = history.getRotationVariance10t();
            if (rotVar < aimVarMax && angleOffset < aimAngleMax && raycast.distance() >= 2.0f
                    && Math.abs(yawDelta5t) > 3.0f && !inWater && !inCorner && !nearWall) {
                signals.add(Signal.of(SignalType.AIM_CONSISTENCY, SignalType.AIM_CONSISTENCY.defaultConfidence(),
                        rotVar, String.format(Locale.ROOT, "aimbot lock (var %.2f)", rotVar)));
            }
            float raycastAngleError = rayHit ? 0.0f : angleOffset;
            if (angleOffset < 1.0f && raycastAngleError < 2.0f && compensatedReach >= 2.0) {
                signals.add(Signal.of(SignalType.AIM_CONSISTENCY, SignalType.AIM_CONSISTENCY.defaultConfidence(),
                        angleOffset, String.format(Locale.ROOT, "aim offset %.2f deg", angleOffset)));
            }
            if (angleOffset < 8.0f && raycastAngleError < 6.0f
                    && Math.abs(yawDelta5t) > 60.0f && yawSpeedChange > roboticAccel) {
                signals.add(Signal.of(SignalType.KINEMATIC_ROBOTIC, SignalType.KINEMATIC_ROBOTIC.defaultConfidence(),
                        yawSpeedChange, String.format(Locale.ROOT,
                        "no deceleration onto target (accel %.0f)", yawSpeedChange)));
            }
        }

        dev.dcheckcloud.engine.SensitivityEstimator sensitivity = data.getSensitivity();
        boolean yawKnown = sensitivity.known(dev.dcheckcloud.engine.SensitivityEstimator.LookAxis.YAW);
        boolean pitchKnown = sensitivity.known(dev.dcheckcloud.engine.SensitivityEstimator.LookAxis.PITCH);
        if (yawKnown || pitchKnown) {
            float dy = Math.abs(data.getSequence().getFeature(0, dev.dcheckcloud.feature.FeatureSchema.DELTA_YAW));
            float dp = Math.abs(data.getSequence().getFeature(0, dev.dcheckcloud.feature.FeatureSchema.DELTA_PITCH));
            double bestRatio = 0.0;
            double bestResidue = 0.0;
            StringBuilder axes = new StringBuilder();
            if (yawKnown) {
                double step = sensitivity.step(dev.dcheckcloud.engine.SensitivityEstimator.LookAxis.YAW);
                if (step > 0 && dy > 2 * step) {
                    double rest = dy % step;
                    double residue = Math.min(rest, step - rest);
                    if (residue > 0.30 * step) {
                        bestRatio = residue / step;
                        bestResidue = residue;
                        axes.append("yaw");
                    }
                }
            }
            if (pitchKnown) {
                double step = sensitivity.step(dev.dcheckcloud.engine.SensitivityEstimator.LookAxis.PITCH);
                if (step > 0 && dp > 2 * step) {
                    double rest = dp % step;
                    double residue = Math.min(rest, step - rest);
                    if (residue > 0.30 * step && residue / step > bestRatio) {
                        bestRatio = residue / step;
                        bestResidue = residue;
                        if (axes.length() > 0) {
                            axes.append('+');
                        }
                        axes.append("pitch");
                    }
                }
            }
            if (bestRatio > 0) {
                double conf = Math.min(0.95, 0.5 + 0.45 * bestRatio);
                signals.add(Signal.of(SignalType.GCD_OFFSTEP, conf, bestResidue,
                        String.format(Locale.ROOT, "off sensitivity grid (%s)", axes)));
            }
        }

        List<Signal> counted = new ArrayList<>();
        for (Signal s : signals) {
            if (s.type() == SignalType.REACH || s.type() == SignalType.SILENT_ROTATION
                    || data.tryFireSignal(s.type(), tick, cfg.hardSignalCooldownTicks)) {
                counted.add(s);
            }
        }
        signals = counted;

        double risk = 0.0;
        String top = "";
        double topContribution = 0.0;
        for (Signal s : signals) {
            double conf = s.type() == SignalType.REACH
                    ? ContextEngine.reachConfidence(s.value(), ctx)
                    : s.confidence();
            double factor = 1.0;
            for (Double mod : ContextEngine.modifiersFor(s.type(), ctx, strict ? 80 : 100).values()) {
                factor *= mod;
            }
            double contribution = s.baseWeight() * conf * factor;
            risk += contribution;
            if (contribution > topContribution) {
                topContribution = contribution;
                top = s.type().name();
            }
        }
        return new Result(signals, ctx, cancelDamage, risk, compensatedReach, angleOffset, rayHit, top);
    }

    private static int countSolidSides(Location feet) {
        Location torso = feet.clone().add(0.0, 1.0, 0.0);
        int count = 0;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            try {
                if (torso.getBlock().getRelative(face).getType().isSolid()) {
                    count++;
                }
            } catch (Throwable ignored) {
            }
        }
        return count;
    }

    private static boolean isEntityInWater(LivingEntity entity) {
        try {
            if (entity.isInWater()) {
                return true;
            }
            Location loc = entity.getLocation();
            return loc.getBlock().isLiquid() || loc.clone().add(0.0, 0.5, 0.0).getBlock().isLiquid();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isCritical(Player attacker, KinematicHistory history) {
        try {
            boolean airborne = !attacker.isOnGround();
            boolean falling = attacker.getFallDistance() > 0.0f
                    || (history != null && history.isMovingDownwards());
            float fullCharge = attacker.getAttackCooldown();
            boolean grounded = attacker.isInsideVehicle() || attacker.isClimbing();
            return (airborne || falling) && fullCharge > 0.7f && !grounded;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isBeingKnockedBack(LivingEntity victim) {
        try {
            Vector v = victim.getVelocity();
            double horiz = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
            return horiz > 0.35 || (victim.getNoDamageTicks() > 10 && horiz > 0.15);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int safePing(Player attacker) {
        try {
            return attacker.getPing();
        } catch (Throwable t) {
            return 0;
        }
    }
}
