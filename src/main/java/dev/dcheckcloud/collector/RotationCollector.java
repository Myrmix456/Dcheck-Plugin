package dev.dcheckcloud.collector;

import dev.dcheckcloud.DCheckPlugin;
import dev.dcheckcloud.action.ActionManager;
import dev.dcheckcloud.config.DCheckConfig;
import dev.dcheckcloud.core.TickClock;
import dev.dcheckcloud.data.PlayerData;
import dev.dcheckcloud.data.PlayerDataManager;
import dev.dcheckcloud.engine.HardChecks;
import dev.dcheckcloud.engine.KinematicHistory;
import dev.dcheckcloud.engine.LagCompensator;
import dev.dcheckcloud.feature.FeatureExtractor;
import dev.dcheckcloud.record.SessionRecorder;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

public final class RotationCollector implements Listener {
    private final DCheckPlugin plugin;
    private final PlayerDataManager playerDataManager;
    private final FeatureExtractor featureExtractor;
    private final TickClock tickClock;
    private final ActionManager actionManager;

    public RotationCollector(DCheckPlugin plugin,
                             PlayerDataManager playerDataManager,
                             FeatureExtractor featureExtractor,
                             TickClock tickClock,
                             ActionManager actionManager) {
        this.plugin = plugin;
        this.playerDataManager = playerDataManager;
        this.featureExtractor = featureExtractor;
        this.tickClock = tickClock;
        this.actionManager = actionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Player player = event.getPlayer();
        PlayerData data = playerDataManager.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        data.getKinematics().push(to);
        plugin.getLagCompensator().record(player);
        featureExtractor.onMove(player, data, to, tickClock.getTick());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHardChecks(EntityDamageByEntityEvent event) {
        dev.dcheckcloud.config.DCheckConfig cfg = plugin.getDCheckConfig();
        if (!cfg.hardChecksEnabled) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim) || attacker.equals(victim)) {
            return;
        }
        if (attacker.hasPermission("dcheck.bypass")) {
            return;
        }
        PlayerData data = playerDataManager.get(attacker.getUniqueId());
        if (data == null) {
            return;
        }
        long tick = tickClock.getTick();
        if (data.isImmune(tick)) {
            return;
        }
        plugin.getLagCompensator().record(victim);
        org.bukkit.Location eye = attacker.getEyeLocation();
        int ping = 0;
        try {
            ping = attacker.getPing();
        } catch (Throwable ignored) {
        }
        double latencyMargin = Math.min(0.5, ping / 1000.0 * 6.0);
        dev.dcheckcloud.engine.KinematicHistory.RaycastHitResult raycast =
                dev.dcheckcloud.engine.KinematicHistory.raycastTargetHitbox(eye, victim, latencyMargin, 6.0);
        double naive = dev.dcheckcloud.engine.LagCompensator.distanceToBox(
                eye.toVector(), victim.getBoundingBox());
        double reach = plugin.getLagCompensator().compensatedReach(
                eye, victim.getUniqueId(), ping, naive);

        dev.dcheckcloud.engine.HardChecks.Result result =
                dev.dcheckcloud.engine.HardChecks.evaluate(attacker, victim, data, raycast, reach, cfg, tick);

        if (result.cancelDamage() && cfg.hardCancelDamage) {
            event.setCancelled(true);
        }
        if (result.eventRisk() > 0.01) {
            boolean flagged = data.getDetectionBuffer().addRisk(result.eventRisk(), cfg);
            if (flagged) {
                plugin.onHardFlag(attacker, data, result, tick);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageCut(EntityDamageByEntityEvent event) {
        dev.dcheckcloud.config.DCheckConfig cfg = plugin.getDCheckConfig();
        if (!cfg.damageCutEnabled) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        PlayerData data = playerDataManager.get(attacker.getUniqueId());
        if (data == null) {
            return;
        }
        long tick = tickClock.getTick();
        if (data.isImmune(tick)) {
            return;
        }
        if (data.getLastProbability() < cfg.thresholdHigh) {
            return;
        }
        if (data.getDetectionBuffer().getValue() < cfg.flagThreshold * cfg.damageCutMinBufferPercent / 100.0) {
            return;
        }
        double cut = event.getDamage() * cfg.damageCutMultiplier;
        event.setDamage(cut);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (player.equals(event.getEntity())) {
            return;
        }

        handleAttack(player, event.getEntity());
    }

    private void handleAttack(Player attacker, org.bukkit.entity.Entity target) {
        if (!(target instanceof Player)) {
            return;
        }
        PlayerData data = playerDataManager.get(attacker.getUniqueId());
        if (data == null) {
            return;
        }
        long tick = tickClock.getTick();
        if (data.getLastAttackTick() == tick) {
            return;
        }
        featureExtractor.onAttack(attacker, data, target, tick);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        PlayerData data = playerDataManager.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        long tick = tickClock.getTick();
        if (!data.inCombat(tick, plugin.getDCheckConfig())) {
            return;
        }
        featureExtractor.onSwing(player, data, tick);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        PlayerData data = playerDataManager.get(victim.getUniqueId());
        if (data == null) {
            return;
        }
        data.extendImmunity(tickClock.getTick(), plugin.getDCheckConfig().knockbackImmunityTicks, "knockback");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerDataManager.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        data.extendImmunity(tickClock.getTick(), plugin.getDCheckConfig().knockbackImmunityTicks, "velocity");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = playerDataManager.create(player.getUniqueId());
        long tick = tickClock.getTick();
        data.extendImmunity(tick, plugin.getDCheckConfig().joinImmunityTicks, "join");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        playerDataManager.remove(player.getUniqueId());
        actionManager.removeViewer(player.getUniqueId());
        for (PlayerData other : playerDataManager.values()) {
            other.removeDebugWatcher(player.getUniqueId());
        }
        if (plugin.getDebugFeed() != null) {
            plugin.getDebugFeed().removeFor(player.getUniqueId());
        }
        plugin.removeDebugWatchAll(player.getUniqueId());
        plugin.getLagCompensator().forget(player.getUniqueId());
        String quitName = player.getName();
        java.util.UUID quitId = player.getUniqueId();
        plugin.runAsync(() -> {
            String csv = plugin.getSessionRecorder().buildCsv(quitId);
            dev.dcheckcloud.record.SessionRecorder.StopResult stopped =
                    plugin.getSessionRecorder().stop(quitId);
            if (stopped == null || csv == null) {
                return;
            }
            try {
                plugin.getCloudClient().uploadSession(stopped.remoteName(), csv);
                plugin.getLogger().info("[DCheck][record] " + quitName + " quit - uploaded "
                        + stopped.rows() + " rows (" + stopped.remoteName() + ")");
            } catch (Exception ex) {
                plugin.getSessionRecorder().savePending(stopped.remoteName(), csv);
                plugin.getLogger().warning("[DCheck][record] " + quitName + " quit - upload failed, "
                        + "kept locally, retry with /dcheck upload");
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        resetAndImmune(event.getPlayer(), plugin.getDCheckConfig().teleportImmunityTicks, "teleport");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        resetAndImmune(event.getPlayer(), plugin.getDCheckConfig().respawnImmunityTicks, "respawn");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        resetAndImmune(event.getPlayer(), plugin.getDCheckConfig().teleportImmunityTicks, "world-change");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        resetAndImmune(event.getPlayer(), plugin.getDCheckConfig().teleportImmunityTicks, "gamemode-change");
    }

    private void resetAndImmune(Player player, int immunityTicks, String reason) {
        PlayerData data = playerDataManager.get(player.getUniqueId());
        if (data == null) {
            return;
        }

        data.hardReset();
        data.extendImmunity(tickClock.getTick(), immunityTicks, reason);
    }
}
