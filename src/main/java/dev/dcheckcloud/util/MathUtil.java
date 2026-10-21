package dev.dcheckcloud.util;

public final class MathUtil {
    private MathUtil() {
    }

    public static float wrapDegrees(float degrees) {
        float d = degrees % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        }
        if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }

    public static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    public static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    public static float safeFloat(double value) {
        if (Double.isNaN(value)) {
            return 0.0f;
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
        }
        return (float) value;
    }
}
