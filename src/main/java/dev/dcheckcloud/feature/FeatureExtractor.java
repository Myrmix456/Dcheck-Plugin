package dev.dcheckcloud.feature;

import dev.dcheckcloud.config.DCheckConfig;
import dev.dcheckcloud.data.PlayerData;
import dev.dcheckcloud.data.SequenceBuffer;
import dev.dcheckcloud.record.SessionRecorder;
import dev.dcheckcloud.util.MathUtil;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class FeatureExtractor {
    private static final int POST_ATTACK_BACKFILL_TICKS = 3;
    private static final int PRE_ATTACK_LOOKBACK = 3;

    private final Supplier<DCheckConfig> configSupplier;
    private final SessionRecorder recorder;
    private final float[] targetFeatureScratch = new float[FeatureSchema.COUNT];

    public FeatureExtractor(Supplier<DCheckConfig> configSupplier, SessionRecorder recorder) {
        this.configSupplier = configSupplier;
        this.recorder = recorder;
    }

    public void onMove(Player player, PlayerData data, Location to, long tick) {
        DCheckConfig config = configSupplier.get();
        float yaw = MathUtil.wrapDegrees(to.getYaw());
        float pitch = MathUtil.clamp(to.getPitch(), -90.0f, 90.0f);

        float deltaYaw = 0.0f;
        float deltaPitch = 0.0f;
        float yawAccel = 0.0f;
        float pitchAccel = 0.0f;
        float yawJerk = 0.0f;
        float pitchJerk = 0.0f;
        float angularVelocity = 0.0f;
        float rotationAccel = 0.0f;
        long rotationInterval = 0;

        if (data.hasPreviousRotation()) {
            deltaYaw = MathUtil.wrapDegrees(yaw - data.getPrevYaw());
            deltaPitch = pitch - data.getPrevPitch();
            yawAccel = deltaYaw - data.getPrevDeltaYaw();
            pitchAccel = deltaPitch - data.getPrevDeltaPitch();
            yawJerk = yawAccel - data.getPrevYawAccel();
            pitchJerk = pitchAccel - data.getPrevPitchAccel();
            if (data.getLastSampleTick() != Long.MIN_VALUE) {
                rotationInterval = Math.min(100L, Math.max(0L, tick - data.getLastSampleTick()));
            }
            angularVelocity = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch)
                    / Math.max(1L, rotationInterval == 0 ? 1 : rotationInterval);
            rotationAccel = angularVelocity - data.getPrevAngularVelocity();
        }

        SequenceBuffer sequence = data.getSequence();

        float[] sample = data.getScratch();
        sample[FeatureSchema.YAW] = yaw;
        sample[FeatureSchema.PITCH] = pitch;
        sample[FeatureSchema.DELTA_YAW] = deltaYaw;
        sample[FeatureSchema.DELTA_PITCH] = deltaPitch;
        sample[FeatureSchema.YAW_ACCEL] = yawAccel;
        sample[FeatureSchema.PITCH_ACCEL] = pitchAccel;
        sample[FeatureSchema.YAW_JERK] = yawJerk;
        sample[FeatureSchema.PITCH_JERK] = pitchJerk;
        sample[FeatureSchema.ANGULAR_VELOCITY] = angularVelocity;
        sample[FeatureSchema.ROTATION_ACCEL] = rotationAccel;
        sample[FeatureSchema.ATTACK_INTERVAL] = 0.0f;
        sample[FeatureSchema.ROTATION_INTERVAL] = rotationInterval;

        Entity target = resolveLiveTarget(player, data, tick);
        if (target != null) {
            fillTargetFeatures(sample, player, target, yaw, pitch);
            Vector tv = target.getVelocity();
            data.updatePassiveTarget(tv.length() < 0.15);
        } else {
            sample[FeatureSchema.ANGLE_TO_TARGET] = FeatureSchema.NO_TARGET;
            sample[FeatureSchema.YAW_DIFF_TO_TARGET] = FeatureSchema.NO_TARGET;
            sample[FeatureSchema.PITCH_DIFF_TO_TARGET] = FeatureSchema.NO_TARGET;
            sample[FeatureSchema.DISTANCE_TO_TARGET] = FeatureSchema.NO_TARGET;
        }

        sample[FeatureSchema.PRE_ATTACK_ROTATION_DELTA] = 0.0f;
        sample[FeatureSchema.POST_ATTACK_ROTATION_DELTA] = 0.0f;
        sample[FeatureSchema.TARGET_SWITCH_TIMING] = target != null ? data.getTargetSwitchTiming() : FeatureSchema.NO_TARGET;
        sample[FeatureSchema.TICKS_SINCE_LAST_ATTACK] = data.ticksSinceLastAttack(tick);
        boolean inCombat = data.inCombat(tick, config);
        sample[FeatureSchema.IN_COMBAT] = inCombat ? 1.0f : 0.0f;
        sample[FeatureSchema.ATTACK] = 0.0f;
        sample[FeatureSchema.ATTACK_ACCURACY] = inCombat ? data.attackAccuracy() : FeatureSchema.NO_TARGET;
        sample[FeatureSchema.MISSED_SWING] = 0.0f;

        sequence.push(sample);
        data.setLastSampleTick(tick);
        data.setLastSampleTotal(sequence.getTotalPushed() - 1);
        if (data.hasPreviousRotation()) {
            data.getSensitivity().observe(Math.abs(deltaYaw), Math.abs(deltaPitch), yaw, data.getPrevYaw());
        }
        data.setRotationDerivatives(yaw, pitch, deltaYaw, deltaPitch, yawAccel, pitchAccel, angularVelocity);
        data.recordSensitivitySample(Math.abs(deltaYaw));

        advancePostAttackBackfill(data, sequence, angularVelocity);
        recorder.onSample(player, sample);

    }

    public void onAttack(Player attacker, PlayerData data, Entity target, long tick) {
        data.registerAttack(target, tick);
        data.registerHit(tick, configSupplier.get().combatWindowTicks);
        SequenceBuffer sequence = data.getSequence();
        if (sequence.getFillCount() > 0) {
            float yaw = sequence.getFeature(0, FeatureSchema.YAW);
            float pitch = sequence.getFeature(0, FeatureSchema.PITCH);

            sequence.setFeature(0, FeatureSchema.ATTACK, 1.0f);
            sequence.setFeature(0, FeatureSchema.IN_COMBAT, 1.0f);
            sequence.setFeature(0, FeatureSchema.ATTACK_INTERVAL, data.attackInterval());
            sequence.setFeature(0, FeatureSchema.TICKS_SINCE_LAST_ATTACK, 0.0f);
            sequence.setFeature(0, FeatureSchema.TARGET_SWITCH_TIMING, data.getTargetSwitchTiming());

            sequence.setFeature(0, FeatureSchema.MISSED_SWING, 0.0f);
            sequence.setFeature(0, FeatureSchema.ATTACK_ACCURACY, data.attackAccuracy());
            fillTargetFeaturesAt(sequence, 0, attacker, target, yaw, pitch);

            float preAttack = 0.0f;
            for (int rowsBack = 1; rowsBack <= PRE_ATTACK_LOOKBACK; rowsBack++) {
                preAttack += Math.abs(sequence.getFeature(rowsBack, FeatureSchema.DELTA_YAW))
                        + Math.abs(sequence.getFeature(rowsBack, FeatureSchema.DELTA_PITCH));
            }
            sequence.setFeature(0, FeatureSchema.PRE_ATTACK_ROTATION_DELTA, preAttack);

        }
        data.beginPostAttack(sequence.getTotalPushed() - 1, POST_ATTACK_BACKFILL_TICKS);
    }

    public void onSwing(Player player, PlayerData data, long tick) {
        data.registerSwing(tick, configSupplier.get().combatWindowTicks);
        SequenceBuffer sequence = data.getSequence();
        if (sequence.getFillCount() > 0) {
            boolean sameTickHit = data.getLastAttackTick() == tick;
            sequence.setFeature(0, FeatureSchema.MISSED_SWING, sameTickHit ? 0.0f : 1.0f);
            sequence.setFeature(0, FeatureSchema.IN_COMBAT, 1.0f);
            sequence.setFeature(0, FeatureSchema.ATTACK_ACCURACY, data.attackAccuracy());
            sequence.copyRow(0, data.getScratch());
            recorder.amendAfterAttack(player, data.getScratch());
        }
    }

    private void advancePostAttackBackfill(PlayerData data, SequenceBuffer sequence, float angularVelocity) {
        if (!data.isPendingPostAttack()) {
            return;
        }
        data.accumulatePostAttack(Math.abs(angularVelocity));
        if (data.tickPostAttack()) {
            long attackRowTotal = data.getPostAttackBaseTotal();
            if (attackRowTotal >= 0) {
                int rowsBack = (int) Math.min(Integer.MAX_VALUE, (sequence.getTotalPushed() - 1) - attackRowTotal);
                sequence.setFeature(rowsBack, FeatureSchema.POST_ATTACK_ROTATION_DELTA, data.getPostAttackAccum());
            }
        }
    }

    private Entity resolveLiveTarget(Player player, PlayerData data, long tick) {
        if (!data.hasLiveTarget() || !data.inCombat(tick, configSupplier.get())) {
            return null;
        }
        Entity entity = data.resolveTarget(player);
        if (entity == null) {
            data.clearTarget();
        }
        return entity;
    }

    private void fillTargetFeatures(float[] sample, Player player, Entity target, float yaw, float pitch) {
        Location eye = player.getEyeLocation();
        Location targetPoint = targetPoint(target);
        Vector delta = targetPoint.toVector().subtract(eye.toVector());
        double distance = delta.length();
        if (distance < 1.0e-4) {
            sample[FeatureSchema.ANGLE_TO_TARGET] = 0.0f;
            sample[FeatureSchema.YAW_DIFF_TO_TARGET] = 0.0f;
            sample[FeatureSchema.PITCH_DIFF_TO_TARGET] = 0.0f;
            sample[FeatureSchema.DISTANCE_TO_TARGET] = 0.0f;
            return;
        }
        Vector direction = eye.getDirection();
        double angleTo = Math.toDegrees(direction.angle(delta));
        double horizontal = Math.sqrt(delta.getX() * delta.getX() + delta.getZ() * delta.getZ());
        double yawToTarget = Math.toDegrees(Math.atan2(-delta.getX(), delta.getZ()));
        double pitchToTarget = Math.toDegrees(Math.atan2(-delta.getY(), horizontal));
        sample[FeatureSchema.ANGLE_TO_TARGET] = MathUtil.safeFloat(angleTo);
        sample[FeatureSchema.YAW_DIFF_TO_TARGET] = MathUtil.safeFloat(Math.abs(MathUtil.wrapDegrees(yawToTarget - yaw)));
        sample[FeatureSchema.PITCH_DIFF_TO_TARGET] = MathUtil.safeFloat(Math.abs(MathUtil.wrapDegrees(pitchToTarget - pitch)));
        sample[FeatureSchema.DISTANCE_TO_TARGET] = MathUtil.safeFloat(distance);
    }

    private void fillTargetFeaturesAt(SequenceBuffer sequence, int rowsBack, Player player, Entity target, float yaw, float pitch) {
        float[] tmp = targetFeatureScratch;
        fillTargetFeatures(tmp, player, target, yaw, pitch);
        sequence.setFeature(rowsBack, FeatureSchema.ANGLE_TO_TARGET, tmp[FeatureSchema.ANGLE_TO_TARGET]);
        sequence.setFeature(rowsBack, FeatureSchema.YAW_DIFF_TO_TARGET, tmp[FeatureSchema.YAW_DIFF_TO_TARGET]);
        sequence.setFeature(rowsBack, FeatureSchema.PITCH_DIFF_TO_TARGET, tmp[FeatureSchema.PITCH_DIFF_TO_TARGET]);
        sequence.setFeature(rowsBack, FeatureSchema.DISTANCE_TO_TARGET, tmp[FeatureSchema.DISTANCE_TO_TARGET]);
    }

    private static Location targetPoint(Entity target) {
        if (target instanceof LivingEntity living) {
            return living.getEyeLocation();
        }
        return target.getLocation().add(0.0, target.getHeight() * 0.5, 0.0);
    }
}
