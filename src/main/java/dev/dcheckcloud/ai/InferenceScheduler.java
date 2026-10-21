package dev.dcheckcloud.ai;

import dev.dcheckcloud.DCheckPlugin;
import dev.dcheckcloud.config.DCheckConfig;
import dev.dcheckcloud.core.TickClock;
import dev.dcheckcloud.data.PlayerData;
import dev.dcheckcloud.data.PlayerDataManager;
import dev.dcheckcloud.feature.FeatureSchema;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public final class InferenceScheduler extends BukkitRunnable {
    private final DCheckPlugin plugin;
    private final PlayerDataManager playerDataManager;
    private final TickClock tickClock;
    private final ExecutorService executor;
    private final AtomicReference<InferenceEngine> engineRef;

    public InferenceScheduler(DCheckPlugin plugin,
                              PlayerDataManager playerDataManager,
                              TickClock tickClock,
                              ExecutorService executor,
                              AtomicReference<InferenceEngine> engineRef) {
        this.plugin = plugin;
        this.playerDataManager = playerDataManager;
        this.tickClock = tickClock;
        this.executor = executor;
        this.engineRef = engineRef;
    }

    @Override
    public void run() {
        final DCheckConfig config = plugin.getDCheckConfig();
        if (!config.aiEnabled) {
            return;
        }
        if (!plugin.isLicensed()) {
            return;
        }
        InferenceEngine engine = engineRef.get();
        if (engine == null) {
            return;
        }
        if (tickClock.isLagSpike()) {
            return;
        }
        double tps;
        try {
            tps = Bukkit.getTPS()[0];
        } catch (Throwable ignored) {
            tps = 20.0;
        }
        if (tps < config.minTps) {
            return;
        }

        long tick = tickClock.getTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("dcheck.bypass")) {
                continue;
            }
            PlayerData data = playerDataManager.get(player.getUniqueId());
            if (data == null) {
                continue;
            }
            if (!isEligible(player, data, tick, config)) {
                continue;
            }

            int rows = Math.min(data.getSequence().getFillCount(), config.sequenceLength);
            if (rows < config.minFill) {
                continue;
            }
            float[] snapshot = new float[rows * FeatureSchema.COUNT];
            data.getSequence().snapshotInto(snapshot);

            final int finalRows = rows;
            final long submissionTick = tick;
            executor.execute(() -> {
                long start = System.nanoTime();
                double probability;
                try {
                    probability = engine.infer(snapshot, finalRows);
                } catch (Throwable t) {
                    plugin.warnThrottled("cloud-infer",
                            "[DCheck] cloud inference failed: " + t.getMessage(), 30_000L);
                    return;
                }
                long latency = System.nanoTime() - start;
                InferenceResult result = new InferenceResult(
                        clampProbability(probability), engine.getName(), latency, submissionTick);
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.applyInferenceResult(player.getUniqueId(), data, result));
            });
        }
    }

    private boolean isEligible(Player player, PlayerData data, long tick, DCheckConfig config) {
        if (!player.isOnline()) {
            return false;
        }
        GameMode gameMode = player.getGameMode();
        if (gameMode == GameMode.SPECTATOR
                || (config.ignoreCreative && gameMode == GameMode.CREATIVE)) {
            return false;
        }
        if (player.isInsideVehicle()) {
            return false;
        }
        if (data.isImmune(tick)) {
            return false;
        }
        if (data.getSequence().getFillCount() < config.minFill) {
            return false;
        }
        if (!data.inCombat(tick, config)) {
            return false;
        }
        if (config.sensitivityGuard && data.getSensCount() >= config.sensitivityWindow) {
            double ratio = data.sensitivityChangeRatio();
            if (ratio >= config.sensitivityChangeRatio) {
                data.extendImmunity(tick, config.sensitivityImmunityTicks, "sensitivity-change");
                data.getDetectionBuffer().halve();
                return false;
            }
        }
        return true;
    }

    private static double clampProbability(double p) {
        if (Double.isNaN(p)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, p));
    }
}
