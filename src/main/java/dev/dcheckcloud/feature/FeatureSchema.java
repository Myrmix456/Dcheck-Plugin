package dev.dcheckcloud.feature;

public final class FeatureSchema {
    public static final String[] NAMES = {
            "yaw",
            "pitch",
            "deltaYaw",
            "deltaPitch",
            "yawAccel",
            "pitchAccel",
            "yawJerk",
            "pitchJerk",
            "angularVelocity",
            "rotationAccel",
            "attackInterval",
            "rotationInterval",
            "angleToTarget",
            "yawDiffToTarget",
            "pitchDiffToTarget",
            "distanceToTarget",
            "preAttackRotationDelta",
            "postAttackRotationDelta",
            "targetSwitchTiming",
            "ticksSinceLastAttack",
            "inCombat",
            "attack",
            "attackAccuracy",
            "missedSwing"
    };

    public static final int COUNT = NAMES.length;

    public static final int YAW = 0;
    public static final int PITCH = 1;
    public static final int DELTA_YAW = 2;
    public static final int DELTA_PITCH = 3;
    public static final int YAW_ACCEL = 4;
    public static final int PITCH_ACCEL = 5;
    public static final int YAW_JERK = 6;
    public static final int PITCH_JERK = 7;
    public static final int ANGULAR_VELOCITY = 8;
    public static final int ROTATION_ACCEL = 9;
    public static final int ATTACK_INTERVAL = 10;
    public static final int ROTATION_INTERVAL = 11;
    public static final int ANGLE_TO_TARGET = 12;
    public static final int YAW_DIFF_TO_TARGET = 13;
    public static final int PITCH_DIFF_TO_TARGET = 14;
    public static final int DISTANCE_TO_TARGET = 15;
    public static final int PRE_ATTACK_ROTATION_DELTA = 16;
    public static final int POST_ATTACK_ROTATION_DELTA = 17;
    public static final int TARGET_SWITCH_TIMING = 18;
    public static final int TICKS_SINCE_LAST_ATTACK = 19;
    public static final int IN_COMBAT = 20;
    public static final int ATTACK = 21;

    public static final int ATTACK_ACCURACY = 22;

    public static final int MISSED_SWING = 23;

    public static final float NO_TARGET = -1.0f;

    private FeatureSchema() {
    }

    public static int indexOf(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
