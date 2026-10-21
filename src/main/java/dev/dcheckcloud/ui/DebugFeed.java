package dev.dcheckcloud.ui;

import dev.dcheckcloud.DCheckPlugin;
import dev.dcheckcloud.data.PlayerData;
import dev.dcheckcloud.lang.Messages;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;

public final class DebugFeed extends BukkitRunnable {
    private static final int SHOWN = 5;

    private final DCheckPlugin plugin;
    private final Map<UUID, Map<UUID, TextDisplay>> displays = new HashMap<>();

    public DebugFeed(DCheckPlugin plugin) {
        this.plugin = plugin;
    }

    private static String color(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    @Override
    public void run() {
        Messages messages = plugin.getMessages();
        Map<UUID, Set<UUID>> desired = new HashMap<>();

        for (PlayerData data : plugin.getPlayerDataManager().values()) {
            if (data.getDebugWatchers().isEmpty()) {
                continue;
            }
            Player target = Bukkit.getPlayer(data.getUuid());
            if (target == null || !target.isOnline()) {
                continue;
            }
            String line = color(buildLine(data, messages));
            boolean hasHistory = data.getMitigation().getFill() > 0;
            for (UUID watcher : data.getDebugWatchers()) {
                Player watcherPlayer = Bukkit.getPlayer(watcher);
                if (watcherPlayer == null || !watcherPlayer.isOnline()) {
                    continue;
                }
                if (watcher.equals(data.getUuid())) {
                    watcherPlayer.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                            TextComponent.fromLegacyText(line));
                } else if (hasHistory) {
                    desired.computeIfAbsent(watcher, k -> new HashSet<>()).add(target.getUniqueId());
                }
            }
        }

        for (UUID watcher : plugin.getDebugWatchAll()) {
            Player watcherPlayer = Bukkit.getPlayer(watcher);
            if (watcherPlayer == null || !watcherPlayer.isOnline()) {
                continue;
            }
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.getUniqueId().equals(watcher)) {
                    continue;
                }
                PlayerData data = plugin.getPlayerDataManager().get(target.getUniqueId());
                if (data == null) {
                    continue;
                }
                if (data.getMitigation().getFill() > 0) {
                    desired.computeIfAbsent(watcher, k -> new HashSet<>()).add(target.getUniqueId());
                }
            }
        }

        pruneDisplays(desired);
        updateDisplays(desired, messages);
    }

    private void pruneDisplays(Map<UUID, Set<UUID>> desired) {
        displays.entrySet().removeIf(watcherEntry -> {
            Map<UUID, TextDisplay> map = watcherEntry.getValue();
            Set<UUID> wanted = desired.getOrDefault(watcherEntry.getKey(), Set.of());
            map.entrySet().removeIf(targetEntry -> {
                TextDisplay display = targetEntry.getValue();
                boolean stale = !wanted.contains(targetEntry.getKey())
                        || display == null || !display.isValid();
                if (stale) {
                    safeRemove(display);
                }
                return stale;
            });
            return map.isEmpty();
        });
    }

    private void updateDisplays(Map<UUID, Set<UUID>> desired, Messages messages) {
        for (Map.Entry<UUID, Set<UUID>> entry : desired.entrySet()) {
            Player watcher = Bukkit.getPlayer(entry.getKey());
            if (watcher == null || !watcher.isOnline()) {
                continue;
            }
            Map<UUID, TextDisplay> map = displays.computeIfAbsent(entry.getKey(), k -> new HashMap<>());
            for (UUID targetId : entry.getValue()) {
                Player target = Bukkit.getPlayer(targetId);
                PlayerData data = plugin.getPlayerDataManager().get(targetId);
                if (target == null || !target.isOnline() || data == null) {
                    continue;
                }
                TextDisplay display = map.get(targetId);
                if (display == null || !display.isValid()) {
                    display = spawnDisplay(target);
                    if (display == null) {
                        continue;
                    }
                    map.put(targetId, display);
                }
                display.setText(color(buildLine(data, messages)));
                try {
                    display.teleport(aboveLocation(target));
                } catch (Throwable ignored) {
                }
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getUniqueId().equals(entry.getKey())) {
                        online.showEntity(plugin, display);
                    } else {
                        online.hideEntity(plugin, display);
                    }
                }
            }
        }
    }

    private TextDisplay spawnDisplay(Player target) {
        try {
            return target.getWorld().spawn(aboveLocation(target), TextDisplay.class, display -> {
                display.setPersistent(false);
                display.setBillboard(Display.Billboard.CENTER);
                display.setSeeThrough(false);
                display.setDefaultBackground(false);
                display.setShadowed(true);
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(0);
                display.setTeleportDuration(4);
                display.setText("");
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Cannot spawn debug TextDisplay: " + t.getMessage());
            return null;
        }
    }

    private static Location aboveLocation(Player target) {
        return target.getLocation().add(0.0, target.getHeight() + 0.75, 0.0);
    }

    private void safeRemove(TextDisplay display) {
        try {
            if (display != null && display.isValid()) {
                display.remove();
            }
        } catch (Throwable ignored) {
        }
    }

    public void removeFor(UUID uuid) {
        Map<UUID, TextDisplay> asWatcher = displays.remove(uuid);
        if (asWatcher != null) {
            for (TextDisplay display : asWatcher.values()) {
                safeRemove(display);
            }
        }
        for (Map<UUID, TextDisplay> map : displays.values()) {
            TextDisplay display = map.remove(uuid);
            safeRemove(display);
        }
    }

    public void removeAll() {
        for (Map<UUID, TextDisplay> map : displays.values()) {
            for (TextDisplay display : map.values()) {
                safeRemove(display);
            }
        }
        displays.clear();
    }

    private String buildLine(PlayerData data, Messages messages) {
        double[] recent = data.getMitigation().recent(SHOWN);
        if (recent.length == 0) {
            return messages.get("feed.empty");
        }
        double sum = 0.0;
        for (double probability : recent) {
            sum += probability;
        }
        long average = Math.round(sum / recent.length * 100.0);

        StringBuilder sb = new StringBuilder(96);
        sb.append(avgBand(average)).append(average).append("% &7| ");
        for (int i = 0; i < recent.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(itemColor(recent[i])).append((long) (recent[i] * 100.0));
        }
        return sb.toString();
    }

    private static String avgBand(long percent) {
        if (percent >= 90L) {
            return "&4&l";
        }
        if (percent >= 70L) {
            return "&c";
        }
        return "&f";
    }

    private static String itemColor(double probability) {
        if (probability >= 0.90) {
            return "&c";
        }
        if (probability >= 0.70) {
            return "&6";
        }
        if (probability >= 0.30) {
            return "&e";
        }
        return "&a";
    }
}
