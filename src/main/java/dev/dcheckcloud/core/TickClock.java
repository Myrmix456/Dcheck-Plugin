package dev.dcheckcloud.core;

import dev.dcheckcloud.config.DCheckConfig;
import java.util.function.Supplier;
import org.bukkit.scheduler.BukkitRunnable;

public final class TickClock extends BukkitRunnable {
    private volatile Supplier<DCheckConfig> configSupplier;
    private long tick;
    private long lastRunNanos = System.nanoTime();
    private long lagSpikeUntilTick;

    public TickClock(Supplier<DCheckConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    public void setConfigSupplier(Supplier<DCheckConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    @Override
    public void run() {
        long now = System.nanoTime();
        long elapsedMs = (now - lastRunNanos) / 1_000_000L;
        lastRunNanos = now;
        tick++;
        DCheckConfig config = configSupplier.get();
        if (config != null && elapsedMs > config.lagSpikeMs) {
            lagSpikeUntilTick = tick + config.lagSpikeImmunityTicks;
        }
    }

    public long getTick() {
        return tick;
    }

    public boolean isLagSpike() {
        return tick < lagSpikeUntilTick;
    }
}
