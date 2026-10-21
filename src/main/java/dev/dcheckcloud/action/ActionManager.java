package dev.dcheckcloud.action;

import dev.dcheckcloud.DCheckPlugin;
import dev.dcheckcloud.ai.InferenceResult;
import dev.dcheckcloud.config.DCheckConfig;
import dev.dcheckcloud.data.PlayerData;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public final class ActionManager {
    private final DCheckPlugin plugin;
    private DCheckConfig config;
    private final Set<UUID> alertViewers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alertsDisabled = ConcurrentHashMap.newKeySet();

    public ActionManager(DCheckPlugin plugin, DCheckConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void reconfigure(DCheckConfig config) {
        this.config = config;
    }

    private static boolean byPermission(Player player) {
        return player != null
                && (player.hasPermission("dcheck.alerts") || player.hasPermission("dcheck.admin"));
    }

    private boolean shouldReceive(UUID uuid, Player player) {
        if (alertsDisabled.contains(uuid)) {
            return false;
        }
        return alertViewers.contains(uuid) || byPermission(player);
    }

    public boolean toggleAlerts(UUID viewer) {
        Player player = Bukkit.getPlayer(viewer);
        boolean receiving = shouldReceive(viewer, player);
        if (receiving) {
            alertsDisabled.add(viewer);
            return false;
        }
        alertsDisabled.remove(viewer);
        if (!byPermission(player)) {
            alertViewers.add(viewer);
        }
        return true;
    }

    public void removeViewer(UUID viewer) {
        alertViewers.remove(viewer);
        alertsDisabled.remove(viewer);
    }

    public void onFlag(Player player, PlayerData data, InferenceResult result) {
        long now = System.currentTimeMillis();
        if (config.alertEnabled && now - data.getLastAlertMillis() >= config.alertCooldownSeconds * 1000L) {
            data.setLastAlertMillis(now);

            Bukkit.getConsoleSender().sendMessage(color(
                    "&c[DCheck] &f" + player.getName() + " &cfailed AI-Aim"));
            Bukkit.getConsoleSender().sendMessage(color(String.format(Locale.ROOT,
                    "&7probability=&f%.2f &7buffer=&f%.1f &7score=&f%.1f &7flags=&f%d &7engine=&f%s",
                    result.probability(),
                    data.getDetectionBuffer().getValue(),
                    data.getMitigation().getScore(),
                    data.getDetectionBuffer().getFlagCount(),
                    result.engineName())));

            String broadcast = color(plugin.getMessages().get("alert.broadcast",
                    player.getName(),
                    String.format(Locale.ROOT, "%.2f", result.probability()),
                    String.format(Locale.ROOT, "%.1f", data.getDetectionBuffer().getValue()),
                    String.format(Locale.ROOT, "%.1f", data.getMitigation().getScore()),
                    String.valueOf(data.getDetectionBuffer().getFlagCount())));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (shouldReceive(online.getUniqueId(), online)) {
                    online.sendMessage(broadcast);
                }
            }
        }

        long flags = data.getDetectionBuffer().getFlagCount();
        if (config.banEnabled && flags >= config.banFlagsRequired && !data.isBanDispatched()) {
            data.setBanDispatched(true);
            dispatch(config.banCommand, player, data, result);
            return;
        }
        if (config.kickEnabled && flags >= config.kickFlagsRequired && !data.isKickDispatched()) {
            data.setKickDispatched(true);
            dispatch(config.kickCommand, player, data, result);
        }
    }

    private static String color(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private void dispatch(String command, Player player, PlayerData data, InferenceResult result) {
        String resolved = command
                .replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString())
                .replace("%flags%", String.valueOf(data.getDetectionBuffer().getFlagCount()))
                .replace("%probability%", String.format(Locale.ROOT, "%.2f", result.probability()))
                .replace("%buffer%", String.format(Locale.ROOT, "%.1f", data.getDetectionBuffer().getValue()))
                .replace("%score%", String.format(Locale.ROOT, "%.1f", data.getMitigation().getScore()))
                .replace("%engine%", result.engineName());
        plugin.getLogger().info("[DCheck] dispatching punishment command: " + resolved);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
    }
}
