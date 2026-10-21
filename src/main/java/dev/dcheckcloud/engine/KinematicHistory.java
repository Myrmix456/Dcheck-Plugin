package dev.dcheckcloud.engine;

import java.util.LinkedList;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public final class KinematicHistory {
    private static final int MAX_HISTORY = 30;

    private final LinkedList<Snapshot> history = new LinkedList<>();
    private long lastRotationTimestamp;

    public record Snapshot(float yaw, float pitch, double x, double y, double z, long time) {
    }

    public record RaycastHitResult(boolean hit, float distance, float angleOffsetDeg, float hitboxEdgeProximity) {
    }

    public void push(Location loc) {
        if (loc == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!history.isEmpty()) {
            Snapshot last = history.peekFirst();
            if (last != null) {
                if (Math.abs(loc.getX() - last.x()) < 1.0E-4 && Math.abs(loc.getY() - last.y()) < 1.0E-4
                        && Math.abs(loc.getZ() - last.z()) < 1.0E-4
                        && Math.abs(loc.getYaw() - last.yaw()) < 0.01f
                        && Math.abs(loc.getPitch() - last.pitch()) < 0.01f) {
                    return;
                }
                if (Math.abs(loc.getYaw() - last.yaw()) > 0.01f || Math.abs(loc.getPitch() - last.pitch()) > 0.01f) {
                    lastRotationTimestamp = now;
                }
            }
        }
        history.addFirst(new Snapshot(loc.getYaw(), loc.getPitch(), loc.getX(), loc.getY(), loc.getZ(), now));
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
    }

    public void clear() {
        history.clear();
        lastRotationTimestamp = 0L;
    }

    public boolean isMovingDownwards() {
        if (history.size() < 2) {
            return false;
        }
        Snapshot curr = history.get(0);
        Snapshot prev = history.get(1);
        return curr.y() < prev.y() - 0.005;
    }

    public long getTimeSinceLastRotation() {
        return System.currentTimeMillis() - lastRotationTimestamp;
    }

    public static float wrapDegrees(float f) {
        f %= 360.0f;
        if (f >= 180.0f) {
            f -= 360.0f;
        }
        if (f < -180.0f) {
            f += 360.0f;
        }
        return f;
    }

    public static float angleDelta(float current, float prev) {
        return wrapDegrees(current - prev);
    }

    public float getYawDelta(int ticksAgo) {
        return history.size() <= ticksAgo ? 0.0f
                : angleDelta(history.get(0).yaw(), history.get(ticksAgo).yaw());
    }

    public float getYawAcceleration() {
        if (history.size() < 3) {
            return 0.0f;
        }
        float d1 = getYawDelta(1);
        float dPrev = angleDelta(history.get(1).yaw(), history.get(2).yaw());
        return d1 - dPrev;
    }

    public float getYawSpeedChange() {
        if (history.size() < 3) {
            return 0.0f;
        }
        float d1 = Math.abs(getYawDelta(1));
        float dPrev = Math.abs(angleDelta(history.get(1).yaw(), history.get(2).yaw()));
        return d1 - dPrev;
    }

    public float getPitchAcceleration() {
        if (history.size() < 3) {
            return 0.0f;
        }
        float d1 = angleDelta(history.get(0).pitch(), history.get(1).pitch());
        float dPrev = angleDelta(history.get(1).pitch(), history.get(2).pitch());
        return d1 - dPrev;
    }

    public float getRotationVariance10t() {
        int window = Math.min(10, history.size() - 1);
        if (window < 3) {
            return 5.0f;
        }
        float[] speeds = new float[window];
        float sum = 0.0f;
        for (int i = 0; i < window; i++) {
            float yd = Math.abs(angleDelta(history.get(i).yaw(), history.get(i + 1).yaw()));
            float pd = Math.abs(angleDelta(history.get(i).pitch(), history.get(i + 1).pitch()));
            speeds[i] = (float) Math.sqrt(yd * yd + pd * pd);
            sum += speeds[i];
        }
        float mean = sum / window;
        float sumSq = 0.0f;
        for (int i = 0; i < window; i++) {
            float diff = speeds[i] - mean;
            sumSq += diff * diff;
        }
        return (float) Math.sqrt(sumSq / window);
    }

    public float getAngularVelocity() {
        float yd = Math.abs(getYawDelta(1));
        float pd = Math.abs(angleDelta(history.get(0).pitch(),
                history.size() > 1 ? history.get(1).pitch() : history.get(0).pitch()));
        return (float) Math.sqrt(yd * yd + pd * pd);
    }

    public static float calculateAngleOffset(Location eyeLoc, Location targetCenterLoc) {
        Vector toTarget = targetCenterLoc.toVector().subtract(eyeLoc.toVector()).normalize();
        Vector lookDir = eyeLoc.getDirection().normalize();
        double dot = Math.max(-1.0, Math.min(1.0, lookDir.dot(toTarget)));
        return (float) Math.toDegrees(Math.acos(dot));
    }

    public static RaycastHitResult raycastTargetHitbox(Location eyeLoc, LivingEntity victim,
                                                       double latencyMargin, double maxDistance) {
        Vector origin = eyeLoc.toVector();
        Vector direction = eyeLoc.getDirection().normalize();
        BoundingBox box = victim.getBoundingBox().clone().expand(latencyMargin);
        Vector center = box.getCenter();
        float angleOffset = calculateAngleOffset(eyeLoc, center.toLocation(victim.getWorld()));
        RayTraceResult result = box.rayTrace(origin, direction, maxDistance);
        if (result != null && result.getHitPosition() != null) {
            Vector hitPos = result.getHitPosition();
            double hitDist = origin.distance(hitPos);
            double hx = Math.max(1.0E-4, box.getWidthX() / 2.0);
            double hy = Math.max(1.0E-4, box.getHeight() / 2.0);
            double hz = Math.max(1.0E-4, box.getWidthZ() / 2.0);
            double nx = Math.abs(hitPos.getX() - center.getX()) / hx;
            double ny = Math.abs(hitPos.getY() - center.getY()) / hy;
            double nz = Math.abs(hitPos.getZ() - center.getZ()) / hz;
            float edgeProximity = (float) Math.min(1.0, Math.max(nx, Math.max(ny, nz)));
            return new RaycastHitResult(true, (float) hitDist, angleOffset, edgeProximity);
        }
        double clampedX = Math.max(box.getMinX(), Math.min(box.getMaxX(), origin.getX()));
        double clampedY = Math.max(box.getMinY(), Math.min(box.getMaxY(), origin.getY()));
        double clampedZ = Math.max(box.getMinZ(), Math.min(box.getMaxZ(), origin.getZ()));
        double minDistanceToBox = origin.distance(new Vector(clampedX, clampedY, clampedZ));
        return new RaycastHitResult(false, (float) minDistanceToBox, angleOffset, 1.0f);
    }
}
