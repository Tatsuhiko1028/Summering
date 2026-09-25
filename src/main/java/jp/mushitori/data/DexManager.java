package jp.mushitori.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 図鑑データの読み書き。
 * plugins/Mushitori/playerdata/&lt;uuid&gt;.yml に1人1ファイルで保存します。
 */
public final class DexManager {

    private final Plugin plugin;
    private final File folder;
    private final Map<UUID, PlayerDex> cache = new ConcurrentHashMap<>();

    public DexManager(Plugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "playerdata");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("playerdata フォルダを作成できませんでした。");
        }
    }

    public PlayerDex get(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::loadFromDisk);
    }

    private PlayerDex loadFromDisk(UUID uuid) {
        File file = fileOf(uuid);
        if (!file.exists()) {
            return new PlayerDex(uuid);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return PlayerDex.fromYaml(uuid, yaml);
    }

    /** 非同期で保存。ログアウト時や登録直後に呼びます。 */
    public void saveAsync(UUID uuid) {
        PlayerDex dex = cache.get(uuid);
        if (dex == null || !dex.isDirty()) return;
        YamlConfiguration yaml = dex.toYaml();
        dex.markClean();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> write(uuid, yaml));
    }

    /** 同期保存（シャットダウン時用）。 */
    public void saveAllSync() {
        for (Map.Entry<UUID, PlayerDex> e : cache.entrySet()) {
            if (!e.getValue().isDirty()) continue;
            YamlConfiguration yaml = e.getValue().toYaml();
            e.getValue().markClean();
            write(e.getKey(), yaml);
        }
    }

    private void write(UUID uuid, YamlConfiguration yaml) {
        try {
            yaml.save(fileOf(uuid));
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "図鑑データの保存に失敗: " + uuid, ex);
        }
    }

    public void unload(UUID uuid) {
        saveAsync(uuid);
        cache.remove(uuid);
    }

    private File fileOf(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }
}
