package dev.dcheckcloud.engine;

public record Signal(SignalType type, double confidence, double value, String detail) {
    public static Signal of(SignalType type, double confidence, double value, String detail) {
        return new Signal(type, confidence, value, detail);
    }

    public double baseWeight() {
        return type.baseWeight();
    }
}
