package dev.dcheckcloud.ai;

public interface InferenceEngine extends AutoCloseable {
    String getName();

    boolean isNeural();

    double infer(float[] sequence, int rows) throws Exception;

    @Override
    default void close() {
    }
}
