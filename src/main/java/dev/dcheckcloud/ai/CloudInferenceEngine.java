package dev.dcheckcloud.ai;

import dev.dcheckcloud.cloud.CloudClient;
import dev.dcheckcloud.feature.FeatureSchema;
import java.util.function.Supplier;

public final class CloudInferenceEngine implements InferenceEngine {
    private final Supplier<CloudClient> clientSupplier;

    public CloudInferenceEngine(Supplier<CloudClient> clientSupplier) {
        this.clientSupplier = clientSupplier;
    }

    @Override
    public String getName() {
        return "cloud";
    }

    @Override
    public boolean isNeural() {
        return true;
    }

    @Override
    public double infer(float[] sequence, int rows) throws Exception {
        return clientSupplier.get().infer(sequence, rows, FeatureSchema.COUNT);
    }
}
