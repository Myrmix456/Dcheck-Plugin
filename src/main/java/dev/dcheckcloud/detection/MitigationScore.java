package dev.dcheckcloud.detection;

public final class MitigationScore {
    private final double[] history;
    private final int capacity;
    private int head;
    private int fill;

    public MitigationScore(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.history = new double[this.capacity];
    }

    public void record(double probability) {
        history[head] = probability;
        head = (head + 1) % capacity;
        if (fill < capacity) {
            fill++;
        }
    }

    public int getFill() {
        return fill;
    }

    public double getScore() {
        if (fill == 0) {
            return 0.0;
        }
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (int i = 0; i < fill; i++) {
            int idx = ((head - fill + i) % capacity + capacity) % capacity;
            int weight = i + 1;
            weightedSum += history[idx] * weight;
            weightTotal += weight;
        }
        return (weightedSum / weightTotal) * 100.0;
    }

    public double[] recent(int count) {
        int n = Math.min(Math.max(0, count), fill);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int idx = ((head - n + i) % capacity + capacity) % capacity;
            out[i] = history[idx];
        }
        return out;
    }

    public void reset() {
        head = 0;
        fill = 0;
        for (int i = 0; i < capacity; i++) {
            history[i] = 0.0;
        }
    }
}
