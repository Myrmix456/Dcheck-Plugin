package dev.dcheckcloud.config;

import java.io.File;
import java.util.Locale;
import org.bukkit.configuration.file.FileConfiguration;

public final class DCheckConfig {
    public static final int ASYNC_POOL_SIZE = 2;
    public static final int MITIGATION_HISTORY_SIZE = 16;
    public static final String CLOUD_URL = "http://fi14.bot-hosting.cloud:25552";

    public final String language;

    public final String cloudUrl;
    public final int cloudTimeoutMs;
    public final int reverifyMinutes;
    public final String licenseKey;

    public final boolean aiEnabled;
    public final int sequenceLength;
    public final int minFill;
    public final int inferenceInterval;
    public final int combatWindowTicks;

    public final double thresholdLow;
    public final double thresholdSuspicious;
    public final double thresholdHigh;
    public final double flagThreshold;

    public final boolean hardChecksEnabled;
    public final double hardMaxReach;
    public final double hardReachFlag;
    public final boolean hardCancelDamage;
    public final double hardRiskScale;
    public final boolean hardFpGuard;
    public final int hardSignalCooldownTicks;

    public final double bufferWeakIncrease;
    public final double bufferIncrease;
    public final double bufferHighMultiplier;
    public final double bufferDecrease;
    public final double bufferMaximum;
    public final boolean bufferResetOnFlag;

    public final boolean alertEnabled;
    public final int alertCooldownSeconds;
    public final boolean kickEnabled;
    public final int kickFlagsRequired;
    public final String kickCommand;
    public final boolean banEnabled;
    public final int banFlagsRequired;
    public final String banCommand;
    public final boolean damageCutEnabled;
    public final double damageCutMinBufferPercent;
    public final double damageCutMultiplier;

    public final int joinImmunityTicks;
    public final int teleportImmunityTicks;
    public final int respawnImmunityTicks;
    public final int knockbackImmunityTicks;
    public final boolean ignoreCreative;
    public final double minTps;
    public final long lagSpikeMs;
    public final int lagSpikeImmunityTicks;
    public final boolean sensitivityGuard;
    public final int sensitivityWindow;
    public final double sensitivityChangeRatio;
    public final int sensitivityImmunityTicks;

    public DCheckConfig(FileConfiguration yaml, File dataFolder) {
        language = yaml.getString("language", "ru");

        cloudUrl = CLOUD_URL;
        cloudTimeoutMs = Math.max(500, yaml.getInt("cloud.timeout-ms", 5000));
        reverifyMinutes = Math.max(1, yaml.getInt("cloud.reverify-minutes", 30));
        licenseKey = sanitizeKey(yaml.getString("license.key", ""));

        aiEnabled = yaml.getBoolean("ai.enabled", true);
        sequenceLength = clampInt(yaml.getInt("ai.sequence-length", 100), 16, 2048);
        minFill = clampInt(yaml.getInt("ai.min-fill", 50), 1, sequenceLength);
        inferenceInterval = clampInt(yaml.getInt("ai.inference-interval", 10), 1, 200);
        combatWindowTicks = clampInt(yaml.getInt("ai.combat-window-ticks", 40), 5, 600);

        thresholdLow = clampDouble(yaml.getDouble("thresholds.low", 0.30), 0.0, 1.0);
        thresholdSuspicious = clampDouble(yaml.getDouble("thresholds.suspicious", 0.70), thresholdLow, 1.0);
        thresholdHigh = clampDouble(yaml.getDouble("thresholds.high", 0.90), thresholdSuspicious, 1.0);
        flagThreshold = Math.max(1.0, yaml.getDouble("thresholds.flag", 100.0));

        hardChecksEnabled = yaml.getBoolean("hard-checks.enabled", true);
        hardMaxReach = clampDouble(yaml.getDouble("hard-checks.max-reach", 3.25), 3.0, 6.0);
        hardReachFlag = Math.max(hardMaxReach, yaml.getDouble("hard-checks.reach-hard-flag", 3.65));
        hardCancelDamage = yaml.getBoolean("hard-checks.cancel-reach-damage", true);
        hardRiskScale = clampDouble(yaml.getDouble("hard-checks.risk-scale", 1.0), 0.0, 10.0);
        hardFpGuard = yaml.getBoolean("hard-checks.fp-guard", true);
        hardSignalCooldownTicks = clampInt(yaml.getInt("hard-checks.signal-cooldown", 100), 0, 1200);

        bufferWeakIncrease = Math.max(0.0, yaml.getDouble("buffer.weak-increase", 0.15));
        bufferIncrease = Math.max(0.01, yaml.getDouble("buffer.increase", 3.5));
        bufferHighMultiplier = Math.max(1.0, yaml.getDouble("buffer.high-multiplier", 3.0));
        bufferDecrease = Math.max(0.0, yaml.getDouble("buffer.decrease", 2.0));
        bufferMaximum = Math.max(flagThreshold, yaml.getDouble("buffer.maximum", 150.0));
        bufferResetOnFlag = yaml.getBoolean("buffer.reset-on-flag", true);

        alertEnabled = yaml.getBoolean("actions.alert.enabled", true);
        alertCooldownSeconds = clampInt(yaml.getInt("actions.alert.cooldown-seconds", 30), 1, 3600);
        kickEnabled = yaml.getBoolean("actions.kick.enabled", true);
        kickFlagsRequired = clampInt(yaml.getInt("actions.kick.flags-required", 3), 1, 1000);
        kickCommand = yaml.getString("actions.kick.command",
                "kick %player% [DCheck] Automated aim detected (flags: %flags%)");
        banEnabled = yaml.getBoolean("actions.ban.enabled", false);
        banFlagsRequired = clampInt(yaml.getInt("actions.ban.flags-required", 5), 1, 1000);
        banCommand = yaml.getString("actions.ban.command",
                "ban %player% [DCheck] Automated aim detected");
        damageCutEnabled = yaml.getBoolean("actions.damage-cut.enabled", true);
        damageCutMinBufferPercent = clampDouble(yaml.getDouble("actions.damage-cut.min-buffer-percent", 50.0), 0.0, 100.0);
        damageCutMultiplier = clampDouble(yaml.getDouble("actions.damage-cut.multiplier", 0.5), 0.0, 1.0);

        joinImmunityTicks = secondsToTicks(yaml.getDouble("protection.join", 5.0), 0, 1200);
        teleportImmunityTicks = secondsToTicks(yaml.getDouble("protection.teleport", 2.0), 0, 1200);
        respawnImmunityTicks = secondsToTicks(yaml.getDouble("protection.respawn", 3.0), 0, 1200);
        knockbackImmunityTicks = secondsToTicks(yaml.getDouble("protection.knockback", 1.25), 0, 1200);
        ignoreCreative = yaml.getBoolean("protection.ignore-creative", true);
        minTps = clampDouble(yaml.getDouble("protection.min-tps", 17.0), 0.0, 20.0);
        lagSpikeMs = Math.max(25L, yaml.getLong("protection.lag-spike-ms", 150L));
        lagSpikeImmunityTicks = secondsToTicks(yaml.getDouble("protection.lag-spike", 1.0), 0, 200);
        sensitivityGuard = yaml.getBoolean("protection.sensitivity-guard", true);
        sensitivityWindow = clampInt(yaml.getInt("protection.sensitivity-window", 40), 8, 400);
        sensitivityChangeRatio = Math.max(1.5, yaml.getDouble("protection.sensitivity-change-ratio", 4.0));
        sensitivityImmunityTicks = secondsToTicks(yaml.getDouble("protection.sensitivity", 3.0), 0, 1200);
    }

    private static String sanitizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\\s\\uFEFF\\u200B\\u200C\\u200D\\u00A0\"'`\u201C\u201D\u2018\u2019]", "")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private static int secondsToTicks(double seconds, int min, int max) {
        return clampInt((int) Math.round(seconds * 20.0), min, max);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
