package dev.dcheckcloud.engine;

public enum SignalType {
    HARD_SNAP(15.0, 0.9),
    HITBOX_MISS(8.0, 0.85),
    REACH(20.0, 1.0),
    AIM_CONSISTENCY(12.0, 0.8),
    KINEMATIC_ROBOTIC(10.0, 0.7),
    SILENT_ROTATION(14.0, 0.85),
    GCD_OFFSTEP(12.0, 0.85);

    private final double baseWeight;
    private final double defaultConfidence;

    SignalType(double baseWeight, double defaultConfidence) {
        this.baseWeight = baseWeight;
        this.defaultConfidence = defaultConfidence;
    }

    public double baseWeight() {
        return baseWeight;
    }

    public double defaultConfidence() {
        return defaultConfidence;
    }
}
