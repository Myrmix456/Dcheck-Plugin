package dev.dcheckcloud.ai;

public record InferenceResult(double probability, String engineName, long latencyNanos, long tick) {
    public double latencyMillis() {
        return latencyNanos / 1_000_000.0;
    }
}
