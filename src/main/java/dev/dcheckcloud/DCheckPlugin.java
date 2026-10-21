package dev.dcheckcloud;

import dev.dcheckcloud.action.ActionManager;
import dev.dcheckcloud.ai.CloudInferenceEngine;
import dev.dcheckcloud.ai.InferenceEngine;
import dev.dcheckcloud.ai.InferenceResult;
import dev.dcheckcloud.ai.InferenceScheduler;
import dev.dcheckcloud.cloud.CloudClient;
import dev.dcheckcloud.cloud.LicenseManager;
import dev.dcheckcloud.collector.RotationCollector;
import dev.dcheckcloud.command.DCheckCommand;
import dev.dcheckcloud.config.DCheckConfig;
import dev.dcheckcloud.core.TickClock;
import dev.dcheckcloud.data.PlayerData;
import dev.dcheckcloud.data.PlayerDataManager;
import dev.dcheckcloud.detection.CheckState;
import dev.dcheckcloud.engine.HardChecks;
import dev.dcheckcloud.engine.LagCompensator;
import dev.dcheckcloud.feature.FeatureExtractor;
import dev.dcheckcloud.feature.FeatureSchema;
import dev.dcheckcloud.lang.Messages;
import dev.dcheckcloud.record.SessionRecorder;
import dev.dcheckcloud.ui.DebugFeed;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class DCheckPlugin extends JavaPlugin {
    private volatile DCheckConfig dconfig;
    private volatile Messages messages;
    private volatile CloudClient cloudClient;
    private LicenseManager licenseManager;
    private PlayerDataManager playerDataManager;
    private TickClock tickClock;
    private FeatureExtractor featureExtractor;
    private dev.dcheckcloud.record.SessionRecorder sessionRecorder;
    private ActionManager actionManager;
    private ExecutorService inferenceExecutor;
    private final AtomicReference<InferenceEngine> engineRef = new AtomicReference<>();
    private InferenceScheduler inferenceScheduler;
    private DebugFeed debugFeed;
    private org.bukkit.scheduler.BukkitTask uploadRetryTask;
    private final dev.dcheckcloud.engine.LagCompensator lagCompensator =
            new dev.dcheckcloud.engine.LagCompensator();
    private final Set<UUID> debugWatchAll = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> warnTimes = new ConcurrentHashMap<>();

    @Override
    public void onLoad() {
        saveDefaultConfig();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dconfig = new DCheckConfig(getConfig(), getDataFolder());
        messages = new Messages(dconfig.language);
        cloudClient = new CloudClient(dconfig.cloudUrl, dconfig.licenseKey, dconfig.cloudTimeoutMs);

        licenseManager = new LicenseManager(this, this::getCloudClient,
                () -> dconfig.licenseKey, dconfig.reverifyMinutes);
        licenseManager.start();

        tickClock = new TickClock(this::getDCheckConfig);
        tickClock.runTaskTimer(this, 1L, 1L);

        sessionRecorder = new dev.dcheckcloud.record.SessionRecorder(this);
        featureExtractor = new FeatureExtractor(this::getDCheckConfig, sessionRecorder);
        playerDataManager = new PlayerDataManager(dconfig);
        actionManager = new ActionManager(this, dconfig);
        inferenceExecutor = buildExecutor(DCheckConfig.ASYNC_POOL_SIZE);
        engineRef.set(new CloudInferenceEngine(this::getCloudClient));

        getServer().getPluginManager().registerEvents(
                new RotationCollector(this, playerDataManager, featureExtractor, tickClock, actionManager),
                this);

        PluginCommand command = getCommand("dcheck");
        if (command != null) {
            DCheckCommand executor = new DCheckCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        startScheduler();

        debugFeed = new DebugFeed(this);
        debugFeed.runTaskTimer(this, 5L, 5L);

        long tick = tickClock.getTick();
        for (Player player : getServer().getOnlinePlayers()) {
            PlayerData data = playerDataManager.create(player.getUniqueId());
            data.extendImmunity(tick, dconfig.joinImmunityTicks, "plugin-enable");
        }

        getLogger().info("DCheck enabled. engine=" + getEngineName()
                + " sequence=" + dconfig.sequenceLength + "x" + FeatureSchema.COUNT
                + " interval=" + dconfig.inferenceInterval + "t"
                + " kick=" + dconfig.kickEnabled + " (after " + dconfig.kickFlagsRequired + " flags)"
                + " ban=" + dconfig.banEnabled);
        startUploadRetry();
    }

    private void startUploadRetry() {
        if (uploadRetryTask != null) {
            uploadRetryTask.cancel();
            uploadRetryTask = null;
        }
        uploadRetryTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                int uploaded = sessionRecorder.uploadPending(cloudClient);
                if (uploaded > 0) {
                    getLogger().info("[DCheck] auto-uploaded " + uploaded
                            + " pending session(s) to the cloud");
                }
            } catch (Exception ignored) {
            }
        }, 600L, 6000L);
    }

    @Override
    public void onDisable() {
        if (sessionRecorder != null) {
            for (String line : sessionRecorder.describeActive()) {
                getLogger().info("[record] flushing on shutdown: " + line);
            }
            sessionRecorder.flushAllToPending();
        }
        if (licenseManager != null) {
            licenseManager.stop();
        }
        if (debugFeed != null) {
            debugFeed.removeAll();
            debugFeed.cancel();
            debugFeed = null;
        }
        if (inferenceScheduler != null) {
            inferenceScheduler.cancel();
            inferenceScheduler = null;
        }
        if (uploadRetryTask != null) {
            uploadRetryTask.cancel();
            uploadRetryTask = null;
        }
        shutdownExecutor();
        if (playerDataManager != null) {
            playerDataManager.clear();
        }
        getLogger().info("DCheck disabled.");
    }

    public DCheckConfig getDCheckConfig() {
        return dconfig;
    }

    public Messages getMessages() {
        return messages;
    }

    public CloudClient getCloudClient() {
        return cloudClient;
    }

    public LicenseManager getLicenseManager() {
        return licenseManager;
    }

    public boolean isLicensed() {
        return licenseManager != null && licenseManager.isLicensed();
    }

    public void reloadDCheck() {
        reloadConfig();
        DCheckConfig newConfig = new DCheckConfig(getConfig(), getDataFolder());
        dconfig = newConfig;
        messages = new Messages(newConfig.language);
        cloudClient = new CloudClient(newConfig.cloudUrl, newConfig.licenseKey, newConfig.cloudTimeoutMs);
        actionManager.reconfigure(newConfig);

        licenseManager.stop();
        licenseManager = new LicenseManager(this, this::getCloudClient,
                () -> dconfig.licenseKey, newConfig.reverifyMinutes);
        licenseManager.start();

        if (inferenceScheduler != null) {
            inferenceScheduler.cancel();
        }
        startScheduler();

        playerDataManager.clear();
        long tick = tickClock.getTick();
        for (Player player : getServer().getOnlinePlayers()) {
            PlayerData data = playerDataManager.create(player.getUniqueId());
            data.extendImmunity(tick, newConfig.joinImmunityTicks, "reload");
        }
        getLogger().info("DCheck reloaded.");
    }

    public String hideUrl(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(DCheckConfig.CLOUD_URL, "cloud")
                .replace("fi14.bot-hosting.cloud:25552", "cloud")
                .replace("fi14.bot-hosting.cloud", "cloud");
    }

    private void startScheduler() {
        if (!dconfig.aiEnabled) {
            getLogger().info("AI analysis disabled by config (ai.enabled=false).");
            return;
        }
        inferenceScheduler = new InferenceScheduler(this, playerDataManager, tickClock, inferenceExecutor, engineRef);
        inferenceScheduler.runTaskTimer(this, dconfig.inferenceInterval, dconfig.inferenceInterval);
    }

    private ExecutorService buildExecutor(int poolSize) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "DCheck-Worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                factory,
                new ThreadPoolExecutor.DiscardPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private void shutdownExecutor() {
        if (inferenceExecutor == null) {
            return;
        }
        inferenceExecutor.shutdown();
        try {
            if (!inferenceExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                inferenceExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            inferenceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void runAsync(Runnable task) {
        ExecutorService executor = inferenceExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(task);
        } catch (RuntimeException ignored) {
        }
    }

    public void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    public void warnThrottled(String id, String message, long minIntervalMs) {
        long now = System.currentTimeMillis();
        Long last = warnTimes.get(id);
        if (last == null || now - last >= minIntervalMs) {
            warnTimes.put(id, now);
            getLogger().warning(message);
        }
    }

    public void applyInferenceResult(UUID uuid, PlayerData data, InferenceResult result) {
        PlayerData current = playerDataManager.get(uuid);
        if (current != data) {
            return;
        }
        DCheckConfig cfg = dconfig;
        long tick = tickClock.getTick();

        double probability = result.probability();
        boolean passiveTarget = data.isPassiveTargetMode();
        if (passiveTarget) {
            probability = Math.min(probability, cfg.thresholdLow);
        }
        double ema = data.getAiProbabilityEma();
        double smoothed = ema < 0.0 ? probability : 0.5 * ema + 0.5 * probability;
        data.setAiProbabilityEma(smoothed);
        probability = smoothed;

        data.setLastProbability(probability);
        data.setLastInferenceTick(result.tick());
        data.incrementInferenceCount();
        data.getMitigation().record(probability);

        boolean flagged = data.getDetectionBuffer().update(probability, cfg);
        boolean collecting = data.getSequence().getFillCount() < cfg.minFill;
        CheckState state = data.getDetectionBuffer()
                .deriveState(cfg, probability, collecting, data.isImmune(tick));
        data.setCheckState(state);

        Player player = getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        if (flagged) {
            getLogger().info(String.format(Locale.ROOT,
                    "%s flagged AI-Aim: probability=%.2f score=%.1f flags=%d engine=%s",
                    player.getName(), result.probability(), data.getMitigation().getScore(),
                    data.getDetectionBuffer().getFlagCount(), result.engineName()));
            actionManager.onFlag(player, data, result);
        }
    }

    public dev.dcheckcloud.record.SessionRecorder getSessionRecorder() {
        return sessionRecorder;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public TickClock getTickClock() {
        return tickClock;
    }

    public ActionManager getActionManager() {
        return actionManager;
    }

    public DebugFeed getDebugFeed() {
        return debugFeed;
    }

    public dev.dcheckcloud.engine.LagCompensator getLagCompensator() {
        return lagCompensator;
    }

    public void onHardFlag(Player player, PlayerData data,
                           dev.dcheckcloud.engine.HardChecks.Result result, long tick) {
        boolean collecting = data.getSequence().getFillCount() < dconfig.minFill;
        data.setCheckState(data.getDetectionBuffer()
                .deriveState(dconfig, data.getLastProbability(), collecting, data.isImmune(tick)));
        getLogger().info(String.format(Locale.ROOT,
                "%s flagged HardChecks: risk=%.1f top=%s buffer=%.1f flags=%d signals=[%s]",
                player.getName(), result.eventRisk(), result.topSignal(),
                data.getDetectionBuffer().getValue(), data.getDetectionBuffer().getFlagCount(),
                result.describe()));
        actionManager.onFlag(player, data,
                new InferenceResult(data.getLastProbability(), "hard:" + result.topSignal(), 0L, tick));
    }

    public boolean toggleDebugWatchAll(UUID uuid) {
        if (debugWatchAll.remove(uuid)) {
            return false;
        }
        debugWatchAll.add(uuid);
        return true;
    }

    public void removeDebugWatchAll(UUID uuid) {
        debugWatchAll.remove(uuid);
    }

    public Set<UUID> getDebugWatchAll() {
        return debugWatchAll;
    }

    public String getEngineName() {
        InferenceEngine engine = engineRef.get();
        return engine == null ? "none" : engine.getName();
    }
}
