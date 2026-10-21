package dev.dcheckcloud.detection;

import dev.dcheckcloud.config.DCheckConfig;
import dev.dcheckcloud.util.MathUtil;

public final class DetectionBuffer {
    private double value;
    private long flagCount;

    public double getValue() {
        return value;
    }

    public long getFlagCount() {
        return flagCount;
    }

    public boolean update(double probability, DCheckConfig cfg) {
        double low = cfg.thresholdLow;
        double suspicious = cfg.thresholdSuspicious;
        double high = cfg.thresholdHigh;

        if (probability < low) {
            value -= cfg.bufferDecrease;
        } else if (probability < suspicious) {
            value += cfg.bufferWeakIncrease;
        } else if (probability < high) {
            value += cfg.bufferIncrease;
        } else {
            value += cfg.bufferIncrease * cfg.bufferHighMultiplier;
        }

        return applyAndCheckFlag(cfg);
    }

    public boolean addRisk(double risk, DCheckConfig cfg) {
        value += Math.max(0.0, risk) * cfg.hardRiskScale;
        return applyAndCheckFlag(cfg);
    }

    private boolean applyAndCheckFlag(DCheckConfig cfg) {
        value = MathUtil.clamp(value, 0.0, cfg.bufferMaximum);
        if (value >= cfg.flagThreshold) {
            flagCount++;
            if (cfg.bufferResetOnFlag) {
                value = 0.0;
            }
            return true;
        }
        return false;
    }

    public void reset() {
        value = 0.0;
    }

    public void halve() {
        value *= 0.5;
    }

    public CheckState deriveState(DCheckConfig cfg, double lastProbability, boolean collecting, boolean immune) {
        if (collecting) {
            return CheckState.COLLECTING;
        }
        if (immune) {
            return CheckState.IMMUNE;
        }
        if (value >= cfg.flagThreshold) {
            return CheckState.FLAGGED;
        }
        if (value >= cfg.flagThreshold * 0.4 || lastProbability >= cfg.thresholdSuspicious) {
            return CheckState.SUSPICIOUS;
        }
        if (value > 0.0 || lastProbability >= cfg.thresholdLow) {
            return CheckState.OBSERVATION;
        }
        return CheckState.IDLE;
    }
}
