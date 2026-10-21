package dev.dcheckcloud.engine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public final class LagCompensator {
    private static final int MAX_SAMPLES = 60;
    private static final long MAX_AGE_MS = 3000L;

    public record Pose(double cx, double cy, double cz, double hx, double hy, double hz, long time) {
    }

    private final Map<UUID, Deque<Pose>> history = new ConcurrentHashMap<>();

    public void record(Entity entity) {
        if (entity == null) {
            return;
        }
        BoundingBox box = entity.getBoundingBox();
        Pose pose = new Pose(box.getCenterX(), box.getCenterY(), box.getCenterZ(),
                box.getWidthX() / 2.0, box.getHeight() / 2.0, box.getWidthZ() / 2.0,
                System.currentTimeMillis());
        Deque<Pose> q = history.computeIfAbsent(entity.getUniqueId(), k -> new ArrayDeque<>());
        synchronized (q) {
            q.addFirst(pose);
            while (q.size() > MAX_SAMPLES) {
                q.removeLast();
            }
        }
    }

    public void forget(UUID id) {
        history.remove(id);
    }

    public double compensatedReach(Location eyeLoc, UUID victimId, int pingMs, double naiveReach) {
        Deque<Pose> q = history.get(victimId);
        if (q == null) {
            return naiveReach;
        }
        long now = System.currentTimeMillis();
        long targetAge = Math.max(0L, pingMs);
        long jitter = Math.max(60L, (long) (pingMs * 0.3));
        Vector eye = eyeLoc.toVector();
        double best = naiveReach;
        boolean matched = false;
        Pose[] snapshot;
        synchronized (q) {
            snapshot = q.toArray(new Pose[0]);
        }
        for (Pose p : snapshot) {
            long age = now - p.time();
            if (age > MAX_AGE_MS) {
                break;
            }
            if (Math.abs(age - targetAge) <= jitter) {
                double d = distanceToBox(eye, p);
                if (!matched || d < best) {
                    best = d;
                    matched = true;
                }
            }
        }
        return matched ? Math.min(best, naiveReach) : naiveReach;
    }

    public static double distanceToBox(Vector eye, BoundingBox box) {
        double dx = Math.max(0.0, Math.abs(eye.getX() - box.getCenterX()) - box.getWidthX() / 2.0);
        double dy = Math.max(0.0, Math.abs(eye.getY() - box.getCenterY()) - box.getHeight() / 2.0);
        double dz = Math.max(0.0, Math.abs(eye.getZ() - box.getCenterZ()) - box.getWidthZ() / 2.0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double distanceToBox(Vector eye, Pose p) {
        double dx = Math.max(0.0, Math.abs(eye.getX() - p.cx()) - p.hx());
        double dy = Math.max(0.0, Math.abs(eye.getY() - p.cy()) - p.hy());
        double dz = Math.max(0.0, Math.abs(eye.getZ() - p.cz()) - p.hz());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
