package dev.dcheckcloud.command;

import dev.dcheckcloud.DCheckPlugin;
import dev.dcheckcloud.cloud.CloudClient;
import dev.dcheckcloud.config.DCheckConfig;
import dev.dcheckcloud.data.PlayerData;
import dev.dcheckcloud.detection.CheckState;
import dev.dcheckcloud.lang.Messages;
import dev.dcheckcloud.record.SessionRecorder;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class DCheckCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS =
            List.of("help", "reload", "status", "debug", "alerts", "record", "verify");
    private final DCheckPlugin plugin;

    public DCheckCommand(DCheckPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.getMessages();
    }

    private static String c(Messages messages, String key, Object... args) {
        return ChatColor.translateAlternateColorCodes('&', messages.get(key, args));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> sendHelp(sender);
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender, args);
            case "debug" -> handleDebug(sender, args);
            case "alerts" -> handleAlerts(sender);
            case "record" -> handleRecord(sender, args);
            case "verify" -> handleVerify(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(c(m(), "help.compact"));
    }

    private void handleReload(CommandSender sender) {
        try {
            plugin.reloadDCheck();
            sender.sendMessage(c(m(), "msg.reloadOk", plugin.getEngineName(),
                    plugin.getDCheckConfig().sequenceLength,
                    plugin.getDCheckConfig().inferenceInterval));
        } catch (RuntimeException ex) {
            sender.sendMessage(c(m(), "msg.reloadFail", ex.getMessage()));
            plugin.getLogger().severe("Reload failed: " + ex.getMessage());
        }
    }

    private void handleStatus(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(c(m(), "err.usageStatus"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(c(m(), "err.playerNotOnline", args[1]));
            return;
        }
        PlayerData data = plugin.getPlayerDataManager().get(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(c(m(), "err.noData", target.getName()));
            return;
        }
        DCheckConfig cfg = plugin.getDCheckConfig();
        long tick = plugin.getTickClock().getTick();
        CheckState state = liveState(data, cfg, tick);
        String yes = m().get("msg.yes");
        String no = m().get("msg.no");

        sender.sendMessage(c(m(), "msg.statusTitle", target.getName()));
        sender.sendMessage(ChatColor.GRAY + m().get("st.probability") + "=" + ChatColor.YELLOW
                + String.format(Locale.ROOT, "%.3f", data.getLastProbability())
                + ChatColor.GRAY + "  " + m().get("st.buffer") + "=" + ChatColor.YELLOW
                + String.format(Locale.ROOT, "%.1f", data.getDetectionBuffer().getValue())
                + ChatColor.GRAY + "/" + String.format(Locale.ROOT, "%.0f", cfg.bufferMaximum)
                + ChatColor.GRAY + "  " + m().get("st.score") + "=" + ChatColor.YELLOW
                + String.format(Locale.ROOT, "%.1f", data.getMitigation().getScore()));
        sender.sendMessage(ChatColor.GRAY + m().get("st.sequence") + "=" + ChatColor.YELLOW
                + data.getSequence().getFillCount() + "/" + data.getSequence().getCapacity()
                + ChatColor.GRAY + "  " + m().get("st.inferences") + "=" + ChatColor.YELLOW + data.getInferenceCount()
                + ChatColor.GRAY + "  " + m().get("st.state") + "=" + stateColor(state) + m().state(state.name()));
        sender.sendMessage(ChatColor.GRAY + m().get("st.combat") + "=" + ChatColor.YELLOW
                + (data.inCombat(tick, cfg) ? yes : no)
                + ChatColor.GRAY + "  " + m().get("st.target") + "=" + ChatColor.YELLOW
                + (data.getTargetName().isEmpty() ? "-" : data.getTargetName())
                + ChatColor.GRAY + "  " + m().get("st.sinceAttack") + "=" + ChatColor.YELLOW + data.ticksSinceLastAttack(tick));
        sender.sendMessage(ChatColor.GRAY + m().get("st.immune") + "=" + ChatColor.YELLOW
                + (data.isImmune(tick) ? yes + " (" + m().reason(data.getImmunityReason()) + ")" : no)
                + ChatColor.GRAY + "  " + m().get("st.engine") + "=" + ChatColor.YELLOW + plugin.getEngineName()
                + ChatColor.GRAY + "  " + m().get("st.flags") + "=" + ChatColor.YELLOW + data.getDetectionBuffer().getFlagCount());
    }

    private CheckState liveState(PlayerData data, DCheckConfig cfg, long tick) {
        boolean collecting = data.getSequence().getFillCount() < cfg.minFill;
        return data.getDetectionBuffer().deriveState(cfg, data.getLastProbability(), collecting, data.isImmune(tick));
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(c(m(), "err.playerOnly"));
            return;
        }
        if (args.length < 2) {
            boolean all = plugin.toggleDebugWatchAll(player.getUniqueId());
            player.sendMessage(c(m(), all ? "msg.debugAllOn" : "msg.debugAllOff"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(c(m(), "err.playerNotOnline", args[1]));
            return;
        }
        PlayerData data = plugin.getPlayerDataManager().get(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(c(m(), "err.noData", target.getName()));
            return;
        }
        boolean watching = data.toggleDebugWatcher(player.getUniqueId());
        player.sendMessage(c(m(), watching ? "msg.debugOn" : "msg.debugOff", target.getName()));
    }

    private void handleAlerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(c(m(), "err.consoleAlerts"));
            return;
        }
        boolean enabled = plugin.getActionManager().toggleAlerts(player.getUniqueId());
        player.sendMessage(c(m(), enabled ? "msg.alertsOn" : "msg.alertsOff"));
    }

    private void handleRecord(CommandSender sender, String[] args) {
        dev.dcheckcloud.record.SessionRecorder recorder = plugin.getSessionRecorder();
        if (args.length < 2) {
            sender.sendMessage(c(m(), "err.usageRecord"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                List<String> active = recorder.describeActive();
                if (active.isEmpty()) {
                    sender.sendMessage(c(m(), "msg.recordListEmpty"));
                } else {
                    sender.sendMessage(c(m(), "msg.recordListHeader"));
                    for (String line : active) {
                        sender.sendMessage(ChatColor.YELLOW + "  " + line);
                    }
                }
            }
            case "stop" -> {
                Player target = resolveTarget(sender, args, 2);
                if (target == null) {
                    return;
                }
                if (!recorder.isRecording(target.getUniqueId())) {
                    sender.sendMessage(c(m(), "err.notRecording", target.getName()));
                    return;
                }
                sender.sendMessage(c(m(), "msg.recordUploading", target.getName()));
                plugin.runAsync(() -> {
                    String csv = recorder.buildCsv(target.getUniqueId());
                    dev.dcheckcloud.record.SessionRecorder.StopResult stopped =
                            recorder.stop(target.getUniqueId());
                    if (stopped == null || csv == null) {
                        plugin.runSync(() -> sender.sendMessage(
                                c(m(), "err.notRecording", target.getName())));
                        return;
                    }
                    try {
                        CloudClient.UploadResult result =
                                plugin.getCloudClient().uploadSession(stopped.remoteName(), csv);
                        plugin.runSync(() -> sender.sendMessage(
                                c(m(), "msg.recordSavedCloud", stopped.rows(), stopped.remoteName())));
                    } catch (Exception ex) {
                        java.io.File backup = recorder.savePending(stopped.remoteName(), csv);
                        plugin.runSync(() -> sender.sendMessage(c(m(), "msg.recordSavedPending",
                                ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                                backup == null ? "?" : backup.getName())));
                    }
                });
            }
            case "cancel" -> {
                Player target = resolveTarget(sender, args, 2);
                if (target == null) {
                    return;
                }
                int rows = recorder.cancel(target.getUniqueId());
                if (rows < 0) {
                    sender.sendMessage(c(m(), "err.notRecording", target.getName()));
                } else {
                    sender.sendMessage(c(m(), "msg.recordCancelled", target.getName(), rows));
                }
            }
            default -> {
                if (args.length > 2 && !args[2].equalsIgnoreCase("cheat")) {
                    sender.sendMessage(c(m(), "err.usageRecord"));
                    return;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(c(m(), "err.playerNotOnline", args[1]));
                    return;
                }
                if (recorder.isRecording(target.getUniqueId())) {
                    sender.sendMessage(c(m(), "err.alreadyRecording", target.getName(), target.getName()));
                    return;
                }
                dev.dcheckcloud.record.SessionRecorder.StartResult started =
                        recorder.start(target);
                sender.sendMessage(c(m(), "msg.recordStart", target.getName(),
                        started == null ? "?" : started.remoteName()));
            }
        }
    }

    private Player resolveTarget(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player target = Bukkit.getPlayerExact(args[index]);
            if (target == null) {
                sender.sendMessage(c(m(), "err.playerNotOnline", args[index]));
            }
            return target;
        }
        if (sender instanceof Player self) {
            return self;
        }
        sender.sendMessage(c(m(), "err.specifyPlayer"));
        return null;
    }

    private void handleVerify(CommandSender sender) {
        plugin.runAsync(() -> {
            try {
                CloudClient.VerifyResult vr = plugin.getCloudClient().verify();
                plugin.runSync(() -> {
                    if (vr.ok()) {
                        sender.sendMessage(c(m(), "msg.licenseOk", vr.name(),
                                String.valueOf(vr.daysLeft())));
                    } else {
                        sender.sendMessage(c(m(), "msg.licenseInactive",
                                m().translateError(plugin.hideUrl(vr.error()))));
                    }
                });
            } catch (Exception ex) {
                plugin.runSync(() -> sender.sendMessage(c(m(), "msg.licenseInactive",
                        m().translateError(plugin.hideUrl("cloud unreachable: " + ex.getMessage())))));
            }
        });
    }

    private static ChatColor stateColor(CheckState state) {
        return switch (state) {
            case FLAGGED -> ChatColor.DARK_RED;
            case SUSPICIOUS -> ChatColor.RED;
            case OBSERVATION -> ChatColor.YELLOW;
            case IMMUNE -> ChatColor.BLUE;
            case COLLECTING -> ChatColor.GRAY;
            case IDLE -> ChatColor.GREEN;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("status") || sub.equals("debug")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(player.getName());
                    }
                }
                return out;
            }
            if (sub.equals("record")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                for (String option : new String[]{"stop", "cancel", "list"}) {
                    if (option.startsWith(prefix)) {
                        out.add(option);
                    }
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(player.getName());
                    }
                }
                return out;
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("record")) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            String prefix = args[2].toLowerCase(Locale.ROOT);
            if (sub.equals("stop") || sub.equals("cancel")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(player.getName());
                    }
                }
                return out;
            }
            if ("cheat".startsWith(prefix)) {
                out.add("cheat");
            }
            return out;
        }
        return out;
    }
}
