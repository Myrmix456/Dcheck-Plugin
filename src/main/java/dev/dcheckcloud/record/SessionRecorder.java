package dev.dcheckcloud.record;

import dev.dcheckcloud.cloud.CloudClient;
import dev.dcheckcloud.feature.FeatureSchema;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class SessionRecorder {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final int MAX_ROWS = 300_000;

    public record StartResult(String remoteName) {
    }

    public record StopResult(int rows, String remoteName) {
    }

    private static final class Session {
        final String playerName;
        final String sessionId;
        final String remoteName;
        final List<float[]> rows = new ArrayList<>();
        boolean closed;

        Session(String playerName, String sessionId, String remoteName) {
            this.playerName = playerName;
            this.sessionId = sessionId;
            this.remoteName = remoteName;
        }
    }

    private final Plugin plugin;
    private final Map<UUID, Session> active = new ConcurrentHashMap<>();

    public SessionRecorder(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRecording(UUID uuid) {
        Session s = active.get(uuid);
        return s != null && !s.closed;
    }

    public List<String> describeActive() {
        List<String> out = new ArrayList<>();
        for (Session s : active.values()) {
            if (!s.closed) {
                out.add(s.playerName + " [cheat] rows=" + s.rows.size());
            }
        }
        return out;
    }

    public StartResult start(Player target) {
        if (isRecording(target.getUniqueId())) {
            return null;
        }
        String stamp = LocalDateTime.now().format(STAMP);
        String sessionId = target.getUniqueId() + "_" + stamp;
        String remoteName = target.getName() + "_" + stamp + ".csv";
        active.put(target.getUniqueId(), new Session(target.getName(), sessionId, remoteName));
        return new StartResult(remoteName);
    }

    public String buildCsv(UUID uuid) {
        Session session = active.get(uuid);
        if (session == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(session.rows.size() * 200 + 512);
        sb.append("timestamp,player,session,label");
        for (String name : FeatureSchema.NAMES) {
            sb.append(',').append(name);
        }
        sb.append('\n');
        String now = LocalDateTime.now().format(TIMESTAMP);
        for (float[] row : session.rows) {
            sb.append(now).append(',')
                    .append(session.playerName).append(',')
                    .append(session.sessionId).append(',')
                    .append("cheat");
            for (float v : row) {
                sb.append(',').append(String.format(Locale.ROOT, "%.6f", v));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public StopResult stop(UUID uuid) {
        Session session = active.remove(uuid);
        if (session == null || session.closed) {
            return null;
        }
        session.closed = true;
        return new StopResult(session.rows.size(), session.remoteName);
    }

    public int cancel(UUID uuid) {
        Session session = active.remove(uuid);
        return session == null ? -1 : session.rows.size();
    }

    public void flushAllToPending() {
        for (java.util.UUID uuid : new java.util.ArrayList<>(active.keySet())) {
            String csv = buildCsv(uuid);
            StopResult stopped = stop(uuid);
            if (csv == null || stopped == null || stopped.rows() == 0) {
                continue;
            }
            java.io.File backup = savePending(stopped.remoteName(), csv);
            plugin.getLogger().info("[record] kept " + stopped.rows() + " rows locally"
                    + (backup == null ? "" : " -> " + backup.getName())
                    + " (upload with /dcheck upload)");
        }
    }

    public int uploadPending(dev.dcheckcloud.cloud.CloudClient client) {
        java.io.File[] files = pendingFiles();
        int uploaded = 0;
        for (java.io.File file : files) {
            try {
                client.uploadSession(file.getName(), readPending(file));
                deletePending(file);
                uploaded++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                plugin.getLogger().warning("[record] pending upload failed for "
                        + file.getName() + ": " + ex.getMessage());
                break;
            }
        }
        return uploaded;
    }

    public java.io.File pendingDir() {
        return new java.io.File(plugin.getDataFolder(), "pending");
    }

    public java.io.File savePending(String remoteName, String csv) {
        try {
            java.io.File dir = pendingDir();
            java.nio.file.Files.createDirectories(dir.toPath());
            java.io.File file = new java.io.File(dir, "cheat__" + remoteName);
            java.nio.file.Files.writeString(file.toPath(), csv, java.nio.charset.StandardCharsets.UTF_8);
            return file;
        } catch (java.io.IOException ex) {
            plugin.getLogger().severe("[record] cannot save pending session: " + ex.getMessage());
            return null;
        }
    }

    public java.io.File[] pendingFiles() {
        java.io.File dir = pendingDir();
        java.io.File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".csv"));
        return files == null ? new java.io.File[0] : files;
    }

    public String readPending(java.io.File file) throws java.io.IOException {
        return java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public void deletePending(java.io.File file) {
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    public void onSample(Player player, float[] sample) {
        Session session = active.get(player.getUniqueId());
        if (session == null || session.closed) {
            return;
        }
        if (session.rows.size() >= MAX_ROWS) {
            return;
        }
        session.rows.add(Arrays.copyOf(sample, sample.length));
    }

    public void amendAfterAttack(Player player, float[] sample) {
        Session session = active.get(player.getUniqueId());
        if (session == null || session.closed || session.rows.isEmpty()) {
            return;
        }
        int last = session.rows.size() - 1;
        float[] target = session.rows.get(last);
        System.arraycopy(sample, 0, target, 0, Math.min(sample.length, target.length));
    }
}
