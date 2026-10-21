package dev.dcheckcloud.data;

import dev.dcheckcloud.config.DCheckConfig;
import dev.dcheckcloud.detection.CheckState;
import dev.dcheckcloud.detection.DetectionBuffer;
import dev.dcheckcloud.detection.MitigationScore;
import dev.dcheckcloud.engine.KinematicHistory;
import dev.dcheckcloud.engine.SensitivityEstimator;
import dev.dcheckcloud.engine.SignalType;
import dev.dcheckcloud.feature.FeatureSchema;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public final class PlayerData {
    private final UUID uuid;
    private final SequenceBuffer sequence;
    private final DetectionBuffer detectionBuffer = new DetectionBuffer();
    private final MitigationScore mitigation;

    private boolean hasPreviousRotation;
    private float prevYaw;
    private float prevPitch;
    private float prevDeltaYaw;
    private float prevDeltaPitch;
    private float prevYawAccel;
    private float prevPitchAccel;
    private float prevAngularVelocity;

    private long lastSampleTick = Long.MIN_VALUE;
    private long lastSampleTotal = -1;

    private long lastAttackTick = Long.MIN_VALUE;
    private long previousAttackTick = Long.MIN_VALUE;
    private long lastSwingTick = Long.MIN_VALUE;
    private int combatSwings;
    private int combatHits;
    private UUID targetId;
    private String targetName = "";
    private long targetSetTick = Long.MIN_VALUE;
    private long lastTargetSwitchTick = Long.MIN_VALUE;
    private long targetSwitchTiming;
    private boolean pendingPostAttack;
    private long postAttackBaseTotal;
    private int postAttackTicksLeft;
    private float postAttackAccum;

    private long immuneUntilTick;
    private String immunityReason = "";

    private boolean kickDispatched;
    private boolean banDispatched;

    private final float[] sensWindow;
    private int sensIndex;
    private long sensCount;

    private double lastProbability;
    private long lastInferenceTick = Long.MIN_VALUE;
    private long inferenceCount;
    private CheckState checkState = CheckState.COLLECTING;
    private long lastAlertMillis;

    private final float[] scratch = new float[FeatureSchema.COUNT];
    private final Set<UUID> debugWatchers = new HashSet<>(2);

    public PlayerData(UUID uuid, DCheckConfig config) {
        this.uuid = uuid;
        this.sequence = new SequenceBuffer(config.sequenceLength);
        this.mitigation = new MitigationScore(DCheckConfig.MITIGATION_HISTORY_SIZE);
        this.sensWindow = new float[config.sensitivityWindow];
    }

    public UUID getUuid() {
        return uuid;
    }

    public SequenceBuffer getSequence() {
        return sequence;
    }

    public DetectionBuffer getDetectionBuffer() {
        return detectionBuffer;
    }

    public MitigationScore getMitigation() {
        return mitigation;
    }

    public float[] getScratch() {
        return scratch;
    }

    public Set<UUID> getDebugWatchers() {
        return Collections.unmodifiableSet(debugWatchers);
    }

    public boolean toggleDebugWatcher(UUID watcher) {
        if (debugWatchers.remove(watcher)) {
            return false;
        }
        debugWatchers.add(watcher);
        return true;
    }

    public boolean removeDebugWatcher(UUID watcher) {
        return debugWatchers.remove(watcher);
    }

    public boolean hasPreviousRotation() {
        return hasPreviousRotation;
    }

    public float getPrevYaw() {
        return prevYaw;
    }

    public float getPrevPitch() {
        return prevPitch;
    }

    public float getPrevDeltaYaw() {
        return prevDeltaYaw;
    }

    public float getPrevDeltaPitch() {
        return prevDeltaPitch;
    }

    public float getPrevYawAccel() {
        return prevYawAccel;
    }

    public float getPrevPitchAccel() {
        return prevPitchAccel;
    }

    public float getPrevAngularVelocity() {
        return prevAngularVelocity;
    }

    public void setRotationDerivatives(float yaw, float pitch, float deltaYaw, float deltaPitch,
                                        float yawAccel, float pitchAccel, float angularVelocity) {
        this.prevYaw = yaw;
        this.prevPitch = pitch;
        this.prevDeltaYaw = deltaYaw;
        this.prevDeltaPitch = deltaPitch;
        this.prevYawAccel = yawAccel;
        this.prevPitchAccel = pitchAccel;
        this.prevAngularVelocity = angularVelocity;
        this.hasPreviousRotation = true;
    }

    public void resetRotationDerivatives() {
        hasPreviousRotation = false;
        prevDeltaYaw = 0;
        prevDeltaPitch = 0;
        prevYawAccel = 0;
        prevPitchAccel = 0;
        prevAngularVelocity = 0;
    }

    public long getLastSampleTick() {
        return lastSampleTick;
    }

    public void setLastSampleTick(long tick) {
        this.lastSampleTick = tick;
    }

    public long getLastSampleTotal() {
        return lastSampleTotal;
    }

    public void setLastSampleTotal(long total) {
        this.lastSampleTotal = total;
    }

    public boolean inCombat(long currentTick, DCheckConfig config) {
        long last = lastCombatActivity();
        return last != Long.MIN_VALUE && currentTick - last <= config.combatWindowTicks;
    }

    private long lastCombatActivity() {
        if (lastAttackTick == Long.MIN_VALUE) {
            return lastSwingTick;
        }
        if (lastSwingTick == Long.MIN_VALUE) {
            return lastAttackTick;
        }
        return Math.max(lastAttackTick, lastSwingTick);
    }

    public void registerSwing(long currentTick, int combatWindowTicks) {
        if (isEngagementExpired(currentTick, combatWindowTicks)) {
            combatSwings = 0;
            combatHits = 0;
        }
        combatSwings++;
        lastSwingTick = currentTick;
    }

    private boolean isEngagementExpired(long currentTick, int combatWindowTicks) {
        long last = lastCombatActivity();
        return last == Long.MIN_VALUE || currentTick - last > combatWindowTicks * 4L;
    }

    public float attackAccuracy() {
        return combatSwings == 0 ? -1.0f : (float) combatHits / combatSwings;
    }

    private final dev.dcheckcloud.engine.KinematicHistory kinematics =
            new dev.dcheckcloud.engine.KinematicHistory();
    private final dev.dcheckcloud.engine.SensitivityEstimator sensitivity =
            new dev.dcheckcloud.engine.SensitivityEstimator();
    private final java.util.EnumMap<dev.dcheckcloud.engine.SignalType, Long> signalLatchUntil =
            new java.util.EnumMap<>(dev.dcheckcloud.engine.SignalType.class);

    public dev.dcheckcloud.engine.SensitivityEstimator getSensitivity() {
        return sensitivity;
    }

    public boolean tryFireSignal(dev.dcheckcloud.engine.SignalType type, long tick, long cooldownTicks) {
        if (cooldownTicks <= 0) {
            return true;
        }
        long until = signalLatchUntil.getOrDefault(type, Long.MIN_VALUE);
        if (tick < until) {
            return false;
        }
        signalLatchUntil.put(type, tick + cooldownTicks);
        return true;
    }
    private UUID patternVictimId;
    private long patternVictimTime;
    private int roboticStreak;
    private long lastRoboticTime;

    public dev.dcheckcloud.engine.KinematicHistory getKinematics() {
        return kinematics;
    }

    public boolean[] updatePattern(UUID victimId, boolean roboticSnap, float modelProbability) {
        long now = System.currentTimeMillis();
        boolean targetSwitch = patternVictimId != null && !patternVictimId.equals(victimId)
                && now - patternVictimTime < 800L;
        patternVictimId = victimId;
        patternVictimTime = now;
        if (!roboticSnap && !(modelProbability >= 0.7f)) {
            if (now - lastRoboticTime > 1500L) {
                roboticStreak = 0;
            }
        } else {
            roboticStreak = now - lastRoboticTime <= 1500L ? roboticStreak + 1 : 1;
            lastRoboticTime = now;
        }
        return new boolean[]{targetSwitch, roboticStreak >= 4};
    }

    private float passiveEma;
    private int passiveSamples;
    private double aiProbabilityEma = -1.0;

    public double getAiProbabilityEma() {
        return aiProbabilityEma;
    }

    public void setAiProbabilityEma(double value) {
        this.aiProbabilityEma = value;
    }

    public void updatePassiveTarget(boolean still) {
        if (passiveSamples == 0) {
            passiveEma = still ? 1.0f : 0.0f;
        } else {
            passiveEma = passiveEma * 0.95f + (still ? 0.05f : 0.0f);
        }
        if (passiveSamples < 1000) {
            passiveSamples++;
        }
    }

    public boolean isPassiveTargetMode() {
        return passiveSamples >= 20 && passiveEma >= 0.8f;
    }

    public long getLastAttackTick() {
        return lastAttackTick;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public long getTargetSwitchTiming() {
        return targetSwitchTiming;
    }

    public long ticksSinceLastAttack(long currentTick) {
        return lastAttackTick == Long.MIN_VALUE ? -1 : currentTick - lastAttackTick;
    }

    public long attackInterval() {
        return previousAttackTick == Long.MIN_VALUE || lastAttackTick == Long.MIN_VALUE
                ? -1 : lastAttackTick - previousAttackTick;
    }

    public void registerAttack(Entity target, long currentTick) {
        previousAttackTick = lastAttackTick;
        lastAttackTick = currentTick;
        if (targetId != null && !targetId.equals(target.getUniqueId())) {
            lastTargetSwitchTick = currentTick;
            targetSwitchTiming = currentTick - targetSetTick;
        } else if (targetId == null) {
            lastTargetSwitchTick = currentTick;
            targetSwitchTiming = 0;
        }
        targetId = target.getUniqueId();
        targetName = target.getType().name();
        targetSetTick = currentTick;
    }

    public void registerHit(long currentTick, int combatWindowTicks) {
        if (isEngagementExpired(currentTick, combatWindowTicks)) {
            combatSwings = 0;
            combatHits = 0;
        }
        combatHits++;
    }

    public boolean hasLiveTarget() {
        return targetId != null;
    }

    public Entity resolveTarget(Player player) {
        if (targetId == null) {
            return null;
        }
        Entity entity = org.bukkit.Bukkit.getEntity(targetId);
        if (entity == null || entity.isDead() || !entity.isValid()) {
            return null;
        }
        return entity;
    }

    public void clearTarget() {
        targetId = null;
        targetName = "";
    }

    public boolean isPendingPostAttack() {
        return pendingPostAttack;
    }

    public void beginPostAttack(long attackSampleTotal, int ticks) {
        pendingPostAttack = true;
        postAttackBaseTotal = attackSampleTotal;
        postAttackTicksLeft = ticks;
        postAttackAccum = 0.0f;
    }

    public void accumulatePostAttack(float angularDelta) {
        postAttackAccum += angularDelta;
    }

    public boolean tickPostAttack() {
        if (!pendingPostAttack) {
            return false;
        }
        postAttackTicksLeft--;
        if (postAttackTicksLeft <= 0) {
            pendingPostAttack = false;
            return true;
        }
        return false;
    }

    public long getPostAttackBaseTotal() {
        return postAttackBaseTotal;
    }

    public float getPostAttackAccum() {
        return postAttackAccum;
    }

    public boolean isImmune(long currentTick) {
        return currentTick < immuneUntilTick;
    }

    public String getImmunityReason() {
        return immunityReason;
    }

    public void extendImmunity(long currentTick, int ticks, String reason) {
        long until = currentTick + ticks;
        if (until > immuneUntilTick) {
            immuneUntilTick = until;
            immunityReason = reason;
        }
    }

    public boolean isKickDispatched() {
        return kickDispatched;
    }

    public void setKickDispatched(boolean kickDispatched) {
        this.kickDispatched = kickDispatched;
    }

    public boolean isBanDispatched() {
        return banDispatched;
    }

    public void setBanDispatched(boolean banDispatched) {
        this.banDispatched = banDispatched;
    }

    public void recordSensitivitySample(float absDeltaYaw) {
        sensWindow[sensIndex] = absDeltaYaw;
        sensIndex = (sensIndex + 1) % sensWindow.length;
        sensCount++;
    }

    public long getSensCount() {
        return sensCount;
    }

    public double sensitivityChangeRatio() {
        int n = sensWindow.length;
        if (sensCount < n) {
            return 1.0;
        }
        int half = n / 2;
        double recent = 0.0;
        double older = 0.0;
        for (int i = 0; i < half; i++) {
            int recentIdx = (sensIndex - 1 - i + n * 2) % n;
            int olderIdx = (sensIndex - 1 - half - i + n * 2) % n;
            recent += sensWindow[recentIdx];
            older += sensWindow[olderIdx];
        }
        recent /= half;
        older /= half;
        if (recent < 0.01 || older < 0.01) {
            return 1.0;
        }
        return Math.max(recent / older, older / recent);
    }

    public double getLastProbability() {
        return lastProbability;
    }

    public void setLastProbability(double probability) {
        this.lastProbability = probability;
    }

    public void setLastInferenceTick(long tick) {
        this.lastInferenceTick = tick;
    }

    public long getInferenceCount() {
        return inferenceCount;
    }

    public void incrementInferenceCount() {
        inferenceCount++;
    }

    public CheckState getCheckState() {
        return checkState;
    }

    public void setCheckState(CheckState state) {
        this.checkState = state;
    }

    public long getLastAlertMillis() {
        return lastAlertMillis;
    }

    public void setLastAlertMillis(long millis) {
        this.lastAlertMillis = millis;
    }

    public void hardReset() {
        sequence.clear();
        resetRotationDerivatives();
        mitigation.reset();
        pendingPostAttack = false;
        sensCount = 0;
        sensIndex = 0;
        combatSwings = 0;
        combatHits = 0;
        lastSwingTick = Long.MIN_VALUE;
        passiveEma = 0.0f;
        passiveSamples = 0;
        aiProbabilityEma = -1.0;
        kinematics.clear();
        sensitivity.reset();
        signalLatchUntil.clear();
        roboticStreak = 0;
        lastRoboticTime = 0L;
    }
}
