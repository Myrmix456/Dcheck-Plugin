package dev.dcheckcloud.data;

import dev.dcheckcloud.config.DCheckConfig;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataManager {
    private final Map<UUID, PlayerData> dataMap = new ConcurrentHashMap<>();
    private final DCheckConfig config;

    public PlayerDataManager(DCheckConfig config) {
        this.config = config;
    }

    public PlayerData create(UUID uuid) {
        PlayerData data = new PlayerData(uuid, config);
        dataMap.put(uuid, data);
        return data;
    }

    public PlayerData get(UUID uuid) {
        return dataMap.get(uuid);
    }

    public PlayerData remove(UUID uuid) {
        return dataMap.remove(uuid);
    }

    public Collection<PlayerData> values() {
        return dataMap.values();
    }

    public void clear() {
        dataMap.clear();
    }

    public int size() {
        return dataMap.size();
    }
}
