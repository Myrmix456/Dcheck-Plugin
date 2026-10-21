package dev.dcheckcloud.data;

import dev.dcheckcloud.feature.FeatureSchema;

public final class SequenceBuffer {
    private final int capacity;
    private final int stride;
    private final float[] storage;
    private int head;
    private int fill;
    private long totalPushed;

    public SequenceBuffer(int capacity) {
        this(capacity, FeatureSchema.COUNT);
    }

    public SequenceBuffer(int capacity, int stride) {
        if (capacity <= 0 || stride <= 0) {
            throw new IllegalArgumentException("capacity and stride must be > 0");
        }
        this.capacity = capacity;
        this.stride = stride;
        this.storage = new float[capacity * stride];
    }

    public void push(float[] sample) {
        if (sample.length != stride) {
            throw new IllegalArgumentException("sample length " + sample.length + " != stride " + stride);
        }
        System.arraycopy(sample, 0, storage, head * stride, stride);
        head = (head + 1) % capacity;
        if (fill < capacity) {
            fill++;
        }
        totalPushed++;
    }

    public int getFillCount() {
        return fill;
    }

    public int getCapacity() {
        return capacity;
    }

    public long getTotalPushed() {
        return totalPushed;
    }

    public float getFeature(int rowsBack, int featureIndex) {
        if (rowsBack < 0 || rowsBack >= fill) {
            return 0.0f;
        }
        int row = ((head - 1 - rowsBack) % capacity + capacity) % capacity;
        return storage[row * stride + featureIndex];
    }

    public void setFeature(int rowsBack, int featureIndex, float value) {
        if (rowsBack < 0 || rowsBack >= fill) {
            return;
        }
        int row = ((head - 1 - rowsBack) % capacity + capacity) % capacity;
        storage[row * stride + featureIndex] = value;
    }

    public void copyRow(int rowsBack, float[] out) {
        if (rowsBack < 0 || rowsBack >= fill) {
            return;
        }
        int row = ((head - 1 - rowsBack) % capacity + capacity) % capacity;
        System.arraycopy(storage, row * stride, out, 0, stride);
    }

    public int snapshotInto(float[] out) {
        int rows = Math.min(fill, out.length / stride);
        for (int i = 0; i < rows; i++) {
            int src = ((head - rows + i) % capacity + capacity) % capacity;
            System.arraycopy(storage, src * stride, out, i * stride, stride);
        }
        return rows;
    }

    public void clear() {
        head = 0;
        fill = 0;
    }
}
