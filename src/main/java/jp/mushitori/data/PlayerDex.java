package jp.mushitori.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** プレイヤー1人ぶんの図鑑データ。 */
public final class PlayerDex {

    /** 1種ぶんの記録。 */
    public static final class Entry {
        public int count;
        public double minCm;
        public double maxCm;
        /** レア度キー → そのレア度で捕まえた回数。0回のものは入りません。 */
        public final Map<String, Integer> rarityCounts = new LinkedHashMap<>();
        /** サイズ段階キー → その段階で捕まえた回数。0回のものは入りません。 */
        public final Map<String, Integer> sizeTierCounts = new LinkedHashMap<>();
        public long firstAt;
        public long lastAt;
    }

    private final UUID uuid;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Long> achievements = new LinkedHashMap<>();
    private boolean dirty;

    public PlayerDex(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    public Entry entry(String creatureId) {
        return entries.get(creatureId);
    }

    public boolean has(String creatureId) {
        return entries.containsKey(creatureId);
    }

    public Map<String, Long> achievements() {
        return achievements;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    /**
     * 1匹ぶんを記録する。
     *
     * @return 記録の結果（新種 / 最大更新 / 最小更新）
     */
    public RecordResult record(String creatureId, double sizeCm, String sizeTierKey, String rarityKey, long at) {
        Entry e = entries.get(creatureId);
        boolean isNew = (e == null);
        if (isNew) {
            e = new Entry();
            e.minCm = sizeCm;
            e.maxCm = sizeCm;
            e.firstAt = at;
            entries.put(creatureId, e);
        }
        boolean biggest = !isNew && sizeCm > e.maxCm;
        boolean smallest = !isNew && sizeCm < e.minCm;

        e.count++;
        e.minCm = Math.min(e.minCm, sizeCm);
        e.maxCm = Math.max(e.maxCm, sizeCm);
        e.lastAt = at;
        e.rarityCounts.merge(rarityKey, 1, Integer::sum);
        e.sizeTierCounts.merge(sizeTierKey, 1, Integer::sum);
        dirty = true;
        return new RecordResult(isNew, biggest, smallest);
    }

    public record RecordResult(boolean isNew, boolean newBiggest, boolean newSmallest) {
    }

    public void unlockAchievement(String id, long at) {
        achievements.put(id, at);
        dirty = true;
    }

    public void reset() {
        entries.clear();
        achievements.clear();
        dirty = true;
    }

    /** 図鑑の記録（種ごとの捕獲実績）だけをリセットする。実績は残す。 */
    public void resetEntries() {
        entries.clear();
        dirty = true;
    }

    /** 実績の達成状況だけをリセットする。図鑑の記録は残す。 */
    public void resetAchievements() {
        achievements.clear();
        dirty = true;
    }

    // ---- YAML 変換 ----

    public static PlayerDex fromYaml(UUID uuid, YamlConfiguration yaml) {
        PlayerDex dex = new PlayerDex(uuid);
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        if (entries != null) {
            for (String id : entries.getKeys(false)) {
                ConfigurationSection s = entries.getConfigurationSection(id);
                if (s == null) continue;
                Entry e = new Entry();
                e.count = s.getInt("count", 0);
                e.minCm = s.getDouble("min", 0);
                e.maxCm = s.getDouble("max", 0);
                readCounts(s.getConfigurationSection("rarities"), e.rarityCounts);
                readCounts(s.getConfigurationSection("size-tiers"), e.sizeTierCounts);
                e.firstAt = s.getLong("first", 0);
                e.lastAt = s.getLong("last", 0);
                dex.entries.put(id, e);
            }
        }
        ConfigurationSection ach = yaml.getConfigurationSection("achievements");
        if (ach != null) {
            for (String id : ach.getKeys(false)) {
                dex.achievements.put(id, ach.getLong(id));
            }
        }
        dex.dirty = false;
        return dex;
    }

    private static void readCounts(ConfigurationSection section, Map<String, Integer> target) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            int n = section.getInt(key, 0);
            if (n > 0) target.put(key, n);
        }
    }

    public YamlConfiguration toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("uuid", uuid.toString());
        for (Map.Entry<String, Entry> me : entries.entrySet()) {
            String base = "entries." + me.getKey() + ".";
            Entry e = me.getValue();
            yaml.set(base + "count", e.count);
            yaml.set(base + "min", round(e.minCm));
            yaml.set(base + "max", round(e.maxCm));
            for (Map.Entry<String, Integer> r : e.rarityCounts.entrySet()) {
                yaml.set(base + "rarities." + r.getKey(), r.getValue());
            }
            for (Map.Entry<String, Integer> t : e.sizeTierCounts.entrySet()) {
                yaml.set(base + "size-tiers." + t.getKey(), t.getValue());
            }
            yaml.set(base + "first", e.firstAt);
            yaml.set(base + "last", e.lastAt);
        }
        for (Map.Entry<String, Long> me : achievements.entrySet()) {
            yaml.set("achievements." + me.getKey(), me.getValue());
        }
        return yaml;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public List<String> knownIds() {
        return new ArrayList<>(entries.keySet());
    }
}
