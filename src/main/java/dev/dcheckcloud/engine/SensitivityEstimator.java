package dev.dcheckcloud.engine;

import java.util.HashMap;
import java.util.Map;

public final class SensitivityEstimator {
    public enum LookAxis {
        YAW,
        PITCH
    }

    private final Map<LookAxis, SensitivityAxis> axes = new HashMap<>();

    public SensitivityEstimator() {
        axes.put(LookAxis.YAW, new SensitivityAxis(new YawDivisorRule()));
        axes.put(LookAxis.PITCH, new SensitivityAxis(new PitchDivisorRule()));
    }

    public void observe(double yawMagnitude, double pitchMagnitude, float yaw, float referenceYaw) {
        float resolution = Math.ulp(Math.max(Math.abs(referenceYaw), Math.abs(yaw)));
        axes.get(LookAxis.YAW).absorb(yawMagnitude, resolution);
        axes.get(LookAxis.PITCH).absorb(pitchMagnitude, resolution);
    }

    public double step(LookAxis axis) {
        SensitivityAxis a = axes.get(axis);
        return a.known() ? a.step() : 0.0;
    }

    public boolean known(LookAxis axis) {
        return axes.get(axis).known();
    }

    public void reset() {
        for (SensitivityAxis axis : axes.values()) {
            axis.reset();
        }
    }

    private interface DivisorRule {
        boolean accepts(double divisor, float precision);

        double bucketWidth(float precision);

        boolean refines(double coarse, float precision);
    }

    private static final class YawDivisorRule implements DivisorRule {
        private static final double PRECISION_SHARE = 0.0625;

        @Override
        public boolean accepts(double divisor, float precision) {
            return precision < divisor * PRECISION_SHARE;
        }

        @Override
        public double bucketWidth(float precision) {
            return Math.max(0.001, 2 * precision);
        }

        @Override
        public boolean refines(double coarse, float precision) {
            return precision < coarse * PRECISION_SHARE;
        }
    }

    private static final class PitchDivisorRule implements DivisorRule {
        @Override
        public boolean accepts(double divisor, float precision) {
            return true;
        }

        @Override
        public double bucketWidth(float precision) {
            return 0.001;
        }

        @Override
        public boolean refines(double coarse, float precision) {
            return true;
        }
    }

    private static final class SensitivityAxis {
        private static final double LARGEST_TURN = 5.0;
        private static final int QUORUM = 15;

        private final DivisorRule rule;
        private final StepFrequencyTable histogram = new StepFrequencyTable();
        private final DivisorPolisher polisher = new DivisorPolisher();
        private double acceptedMagnitude;
        private double step;
        private boolean known;

        SensitivityAxis(DivisorRule rule) {
            this.rule = rule;
        }

        void absorb(double magnitude, float resolution) {
            collectDivisor(magnitude, resolution);
            double coarse = coarseStep();
            if (coarse > 0 && rule.refines(coarse, resolution)) {
                polisher.offer(magnitude, coarse);
            }
            double polished = polisher.refined();
            if (polished > 0) {
                step = polished;
                known = true;
            }
        }

        double step() {
            return step;
        }

        boolean known() {
            return known;
        }

        void reset() {
            histogram.clear();
            polisher.reset();
            acceptedMagnitude = 0.0;
            step = 0.0;
            known = false;
        }

        private void collectDivisor(double magnitude, float resolution) {
            if (!(magnitude > 0 && magnitude < LARGEST_TURN)) {
                return;
            }
            double divisor = RotationGcd.of(magnitude, acceptedMagnitude);
            if (!(divisor > RotationGcd.SMALLEST_STEP) || !rule.accepts(divisor, resolution)) {
                return;
            }
            if (acceptedMagnitude != 0) {
                histogram.add(divisor, rule.bucketWidth(resolution));
            }
            acceptedMagnitude = magnitude;
        }

        private double coarseStep() {
            if (histogram.size() <= QUORUM) {
                return 0.0;
            }
            StepFrequencyTable.Peak peak = histogram.mostPopular();
            return peak.count() > QUORUM ? peak.key() : 0.0;
        }
    }

    private static final class StepFrequencyTable {
        private static final int REMEMBERED = 80;

        private final Map<Double, int[]> tallies = new HashMap<>();
        private final double[] remembered = new double[REMEMBERED];
        private int firstRemembered;
        private int rememberedCount;

        void add(double divisor, double tolerance) {
            if (rememberedCount == REMEMBERED) {
                release(remembered[firstRemembered]);
                firstRemembered = (firstRemembered + 1) % REMEMBERED;
                rememberedCount--;
            }
            double key = existingKeyNear(divisor, tolerance);
            if (Double.isNaN(key)) {
                key = divisor;
                tallies.put(key, new int[1]);
            }
            tallies.get(key)[0]++;
            remembered[(firstRemembered + rememberedCount) % REMEMBERED] = key;
            rememberedCount++;
        }

        int size() {
            return rememberedCount;
        }

        Peak mostPopular() {
            double bestKey = 0.0;
            int bestCount = 0;
            for (Map.Entry<Double, int[]> tally : tallies.entrySet()) {
                int count = tally.getValue()[0];
                if (count > bestCount) {
                    bestKey = tally.getKey();
                    bestCount = count;
                }
            }
            return bestCount == 0 ? null : new Peak(bestKey, bestCount);
        }

        void clear() {
            tallies.clear();
            firstRemembered = 0;
            rememberedCount = 0;
        }

        private double existingKeyNear(double divisor, double tolerance) {
            for (Double key : tallies.keySet()) {
                if (Math.abs(key - divisor) < tolerance) {
                    return key;
                }
            }
            return Double.NaN;
        }

        private void release(double key) {
            int[] tally = tallies.get(key);
            tally[0]--;
            if (tally[0] == 0) {
                tallies.remove(key);
            }
        }

        record Peak(double key, int count) {
        }
    }

    private static final class DivisorPolisher {
        private static final int KEPT_PAIRS = 256;
        private static final int TRUSTED_TOTAL = 30;
        private static final double DRIFT_SHARE = 0.01;
        private static final double ROUNDING_SLACK = 0.2;
        private static final long FIRST_GUESS_LIMIT = 8;
        private static final long SETTLED_LIMIT = 60;

        private final double[] magnitudes = new double[KEPT_PAIRS + 1];
        private final long[] multiples = new long[KEPT_PAIRS + 1];
        private int head;
        private int kept;
        private double magnitudeTotal;
        private long multipleTotal;

        void offer(double magnitude, double coarse) {
            if (magnitude <= 0 || coarse <= 0) {
                return;
            }
            double estimate = refined();
            if (estimate > 0 && Math.abs(coarse - estimate) > estimate * DRIFT_SHARE) {
                reset();
                estimate = 0;
            }
            double unit = estimate > 0 ? estimate : coarse;
            double ratio = magnitude / unit;
            long multiple = Math.round(ratio);
            if (fits(ratio, multiple, estimate > 0 ? SETTLED_LIMIT : FIRST_GUESS_LIMIT)) {
                keep(magnitude, multiple);
            }
        }

        double refined() {
            return multipleTotal >= TRUSTED_TOTAL ? magnitudeTotal / multipleTotal : 0.0;
        }

        void reset() {
            head = 0;
            kept = 0;
            magnitudeTotal = 0.0;
            multipleTotal = 0L;
        }

        private static boolean fits(double ratio, long multiple, long limit) {
            return multiple >= 1 && multiple <= limit && Math.abs(ratio - multiple) <= ROUNDING_SLACK;
        }

        private void keep(double magnitude, long multiple) {
            int slot = (head + kept) % magnitudes.length;
            magnitudes[slot] = magnitude;
            multiples[slot] = multiple;
            kept++;
            magnitudeTotal += magnitude;
            multipleTotal += multiple;
            if (kept > KEPT_PAIRS) {
                magnitudeTotal -= magnitudes[head];
                multipleTotal -= multiples[head];
                head = (head + 1) % magnitudes.length;
                kept--;
            }
        }
    }
}
