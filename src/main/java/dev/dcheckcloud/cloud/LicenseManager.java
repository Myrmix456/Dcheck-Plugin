package dev.dcheckcloud.cloud;

import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class LicenseManager {
    private final Plugin plugin;
    private final Supplier<CloudClient> clientSupplier;
    private final Supplier<String> keySupplier;
    private final long reverifyTicks;

    private volatile boolean licensed;
    private volatile String licenseName = "";
    private volatile int daysLeft;
    private volatile long expiresAt;
    private volatile String lastError = "";
    private int lastErrorLogged;
    private BukkitTask task;

    public LicenseManager(Plugin plugin, Supplier<CloudClient> clientSupplier,
                          Supplier<String> keySupplier, int reverifyMinutes) {
        this.plugin = plugin;
        this.clientSupplier = clientSupplier;
        this.keySupplier = keySupplier;
        this.reverifyTicks = Math.max(600L, reverifyMinutes * 60L * 20L);
    }

    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::check, 1L, reverifyTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void check() {
        String key = keySupplier.get();
        if (key == null || key.isBlank()) {
            setState(false, "", 0, 0, "license key not set");
            return;
        }
        try {
            CloudClient.VerifyResult result = clientSupplier.get().verify();
            if (result.ok()) {
                setState(true, result.name(), result.daysLeft(), result.expiresAt(), "");
            } else {
                setState(false, "", 0, 0, result.error());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            setState(false, "", 0, 0, "cloud unreachable");
        }
    }

    private synchronized void setState(boolean ok, String name, int daysLeft, long expiresAt, String error) {
        boolean wasLicensed = licensed;
        this.licensed = ok;
        this.licenseName = name;
        this.daysLeft = daysLeft;
        this.expiresAt = expiresAt;
        this.lastError = error;
        if (ok && !wasLicensed) {
            plugin.getLogger().info("[license] valid - customer '" + name + "', " + daysLeft + " day(s) left");
        } else if (!ok && (wasLicensed || lastErrorLogged != error.hashCode())) {
            lastErrorLogged = error.hashCode();
            plugin.getLogger().warning("[license] inactive: " + error);
        }
    }

    public boolean isLicensed() {
        return licensed;
    }

    public String getLicenseName() {
        return licenseName;
    }

    public int getDaysLeft() {
        return daysLeft;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public String getLastError() {
        return lastError;
    }
}
