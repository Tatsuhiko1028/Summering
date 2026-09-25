package jp.mushitori.service;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import jp.mushitori.Keys;
import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.Creature;
import jp.mushitori.model.CreatureOverride;
import jp.mushitori.model.SpawnMarker;
import jp.mushitori.model.SpawnPreset;
import jp.mushitori.model.WeightedCreature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * 【試験実装】マップに設置したマーカーの周辺に、プレイヤーが近づくと自動でいきものが
 * 湧く仕組み（ずっと湧いているのではなく、必要なときだけ）。
 *
 * <p>マーカーは、設置した時点の値をすべて自前で持ちます（生物一覧・各種半径・最大数・
 * 頻度・カウント窓）。設定は {@code /mushitori give wand} で入手できる設定棒で、
 * 既存マーカーを右クリックすると開くGUI（{@link jp.mushitori.ui.MarkerGui}）から
 * その場で編集できます。</p>
 *
 * <p>最大数（{@code maxCount}）の判定方法：「現在その場所で生きている数」＋
 * 「直近 {@code catchWindowSeconds} 秒以内にそこで捕まえられた数」が maxCount を
 * 下回っている間、{@code spawnIntervalSeconds} 秒おきに1体ずつ補充されます。
 * つまり、プレイヤーが捕まえた分だけ、時間とともにまた湧いてくる形になります。</p>
 */
public final class AmbientSpawnService {

    private final MushitoriPlugin plugin;
    private final Map<Integer, SpawnMarker> markers = new ConcurrentHashMap<>();
    private final Map<Integer, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final Map<Integer, Set<UUID>> activeMobs = new ConcurrentHashMap<>();
    /** マーカーID → 直近の捕獲時刻一覧（catchWindowSeconds より古いものは巡回時に間引く）。 */
    private final Map<Integer, Deque<Long>> catchLog = new ConcurrentHashMap<>();
    private final Map<String, SpawnPreset> presets = new LinkedHashMap<>();
    private int nextId = 1;

    private boolean enabled = true;
    private double defaultTriggerRadius = 15.0;
    private double defaultDespawnRadius = 20.0;
    private double defaultSpawnRadius = 5.0;
    private double defaultSpawnIntervalSeconds = 5.0;
    private double defaultSpawnChance = 1.0;
    private double defaultCatchWindowSeconds = 300.0;
    private double leashWalkSpeed = 1.0;
    private int defaultMaxCount = 1;
    private int defaultSimultaneousMax = 1;
    private boolean debug = false;

    public AmbientSpawnService(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(ConfigurationSection section) {
        presets.clear();
        if (section == null) return;
        enabled = section.getBoolean("enabled", true);
        defaultTriggerRadius = Math.max(1.0, section.getDouble("trigger-radius", 15.0));
        defaultDespawnRadius = Math.max(defaultTriggerRadius, section.getDouble("despawn-radius", 20.0));
        defaultSpawnRadius = Math.max(0.0, section.getDouble("spawn-radius", 5.0));
        defaultSpawnIntervalSeconds = Math.max(1.0, section.getDouble("spawn-interval-seconds", 5.0));
        defaultSpawnChance = Math.max(0.0, Math.min(1.0, section.getDouble("spawn-chance", 1.0)));
        defaultCatchWindowSeconds = Math.max(1.0, section.getDouble("catch-window-seconds", 300.0));
        leashWalkSpeed = Math.max(0.1, section.getDouble("leash-walk-speed", 1.0));
        defaultMaxCount = Math.max(1, section.getInt("max-count", 1));
        defaultSimultaneousMax = Math.max(1, section.getInt("simultaneous-max", 1));
        debug = section.getBoolean("debug", false);

        ConfigurationSection presetsSection = section.getConfigurationSection("presets");
        if (presetsSection != null) {
            for (String name : presetsSection.getKeys(false)) {
                ConfigurationSection p = presetsSection.getConfigurationSection(name);
                if (p == null) continue;
                List<WeightedCreature> creatures = readWeightedCreatures(p.getMapList("creatures"));
                int maxCount = Math.max(1, p.getInt("max-count", 1));
                presets.put(name, new SpawnPreset(name, creatures, maxCount));
            }
        }
    }

    // ==================== マーカーの永続化 ====================

    /** config.yml / spawn_markers.yml 双方で使う、生物一覧の読み込み（[{id: x, weight: n}, ...] 形式）。 */
    private List<WeightedCreature> readWeightedCreatures(List<?> rawList) {
        List<WeightedCreature> result = new ArrayList<>();
        for (Object raw : rawList) {
            if (raw instanceof Map<?, ?> map) {
                Object idObj = map.get("id");
                if (idObj == null) continue;
                int weight = 1;
                Object weightObj = map.get("weight");
                if (weightObj instanceof Number n) weight = Math.max(1, n.intValue());
                result.add(new WeightedCreature(idObj.toString(), weight));
            } else if (raw instanceof String s && !s.isBlank()) {
                // 後方互換：文字列だけのリストも、ウェイト1として受け付ける
                result.add(new WeightedCreature(s, 1));
            }
        }
        return result.size() > SpawnMarker.MAX_SPECIES ? result.subList(0, SpawnMarker.MAX_SPECIES) : result;
    }

    private List<Map<String, Object>> writeWeightedCreatures(List<WeightedCreature> creatures) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WeightedCreature wc : creatures) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", wc.creatureId());
            m.put("weight", wc.weight());
            result.add(m);
        }
        return result;
    }

    public void loadMarkers() {
        stopAllTasks();
        markers.clear();
        activeMobs.clear();
        catchLog.clear();

        File file = markersFile();
        if (file.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = yaml.getConfigurationSection("markers");
            int maxId = 0;
            if (root != null) {
                for (String key : root.getKeys(false)) {
                    try {
                        int id = Integer.parseInt(key.trim());
                        ConfigurationSection s = root.getConfigurationSection(key);
                        if (s == null) continue;
                        List<WeightedCreature> creatures = readWeightedCreatures(s.getMapList("creatures"));
                        SpawnMarker marker = new SpawnMarker(id,
                                s.getString("name", ""),
                                s.getString("world", ""),
                                s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                                creatures,
                                s.getDouble("trigger-radius", defaultTriggerRadius),
                                s.getDouble("despawn-radius", defaultDespawnRadius),
                                s.getDouble("spawn-radius", defaultSpawnRadius),
                                s.getInt("max-count", defaultMaxCount),
                                s.getInt("simultaneous-max", defaultSimultaneousMax),
                                s.getDouble("spawn-interval-seconds", defaultSpawnIntervalSeconds),
                                s.getDouble("spawn-chance", defaultSpawnChance),
                                s.getDouble("catch-window-seconds", defaultCatchWindowSeconds),
                                s.contains("size-distribution-template")
                                        ? s.getString("size-distribution-template") : null);
                        markers.put(id, marker);
                        maxId = Math.max(maxId, id);
                    } catch (NumberFormatException ignored) {
                        // 数値以外のキーは無視
                    }
                }
            }
            nextId = maxId + 1;
        }

        if (!enabled) return;
        for (SpawnMarker marker : markers.values()) {
            reconcileExistingMobs(marker);
            startTask(marker);
        }
    }

    private void saveMarkers() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (SpawnMarker m : markers.values()) {
            String base = "markers." + m.id() + ".";
            if (m.name() != null && !m.name().isBlank()) yaml.set(base + "name", m.name());
            yaml.set(base + "world", m.worldName());
            yaml.set(base + "x", m.x());
            yaml.set(base + "y", m.y());
            yaml.set(base + "z", m.z());
            yaml.set(base + "creatures", writeWeightedCreatures(m.creatures()));
            yaml.set(base + "trigger-radius", m.triggerRadius());
            yaml.set(base + "despawn-radius", m.despawnRadius());
            yaml.set(base + "spawn-radius", m.spawnRadius());
            yaml.set(base + "max-count", m.maxCount());
            yaml.set(base + "simultaneous-max", m.simultaneousMax());
            yaml.set(base + "spawn-interval-seconds", m.spawnIntervalSeconds());
            yaml.set(base + "spawn-chance", m.spawnChance());
            yaml.set(base + "catch-window-seconds", m.catchWindowSeconds());
            if (m.sizeDistributionTemplate() != null) {
                yaml.set(base + "size-distribution-template", m.sizeDistributionTemplate());
            }
        }
        try {
            yaml.save(markersFile());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "spawn_markers.yml の保存に失敗しました。", e);
        }
    }

    private File markersFile() {
        return new File(plugin.getDataFolder(), "spawn_markers.yml");
    }

    // ==================== マーカーの追加・削除・更新 ====================

    public SpawnMarker addMarker(Location location, String creatureId) {
        return addMarkerInternal(location, "", List.of(new WeightedCreature(creatureId, 1)), defaultMaxCount);
    }

    public SpawnMarker addMarkerWithPreset(Location location, String presetName) {
        SpawnPreset preset = presets.get(presetName);
        if (preset == null) return addMarkerInternal(location, "", List.of(), defaultMaxCount);
        return addMarkerInternal(location, "", preset.creatures(), preset.maxCount());
    }

    /** 種類未設定の空のマーカーを作る（設定棒で新規設置した直後、GUIで設定してもらう用）。 */
    public SpawnMarker addEmptyMarker(Location location) {
        return addMarkerInternal(location, "", List.of(), defaultMaxCount);
    }

    private SpawnMarker addMarkerInternal(Location location, String name, List<WeightedCreature> creatures, int maxCount) {
        World world = location.getWorld();
        int id = nextId++;
        SpawnMarker marker = new SpawnMarker(id, name, world == null ? "world" : world.getName(),
                location.getX(), location.getY(), location.getZ(),
                List.copyOf(creatures),
                defaultTriggerRadius, defaultDespawnRadius, defaultSpawnRadius,
                Math.max(1, maxCount), defaultSimultaneousMax,
                defaultSpawnIntervalSeconds, defaultSpawnChance, defaultCatchWindowSeconds, null);
        markers.put(id, marker);
        saveMarkers();
        if (enabled) startTask(marker);
        return marker;
    }

    /** マーカーの設定を丸ごと差し替える（GUIでの編集の確定時に使う）。頻度が変われば巡回タスクを再起動する。 */
    public void updateMarker(SpawnMarker updated) {
        SpawnMarker previous = markers.get(updated.id());
        if (previous == null) return;
        markers.put(updated.id(), updated);
        saveMarkers();

        boolean intervalChanged = previous.spawnIntervalSeconds() != updated.spawnIntervalSeconds();
        boolean locationChanged = !previous.worldName().equals(updated.worldName())
                || previous.x() != updated.x() || previous.y() != updated.y() || previous.z() != updated.z();
        if (enabled && (intervalChanged || locationChanged)) {
            stopTask(updated.id());
            startTask(updated);
        }
    }

    /** マーカーを別の場所へ移動する（巡回タスクも新しい場所で再起動します）。 */
    public void moveMarkerTo(int markerId, Location newLocation) {
        SpawnMarker marker = markers.get(markerId);
        if (marker == null) return;
        var world = newLocation.getWorld();
        SpawnMarker updated = new SpawnMarker(marker.id(), marker.name(),
                world == null ? marker.worldName() : world.getName(),
                newLocation.getX(), newLocation.getY(), newLocation.getZ(),
                marker.creatures(), marker.triggerRadius(), marker.despawnRadius(), marker.spawnRadius(),
                marker.maxCount(), marker.simultaneousMax(),
                marker.spawnIntervalSeconds(), marker.spawnChance(), marker.catchWindowSeconds(),
                marker.sizeDistributionTemplate());
        updateMarker(updated);
    }

    public boolean removeMarker(int id) {
        SpawnMarker removed = markers.remove(id);
        if (removed == null) return false;
        stopTask(id);
        catchLog.remove(id);
        Set<UUID> mobs = activeMobs.remove(id);
        if (mobs != null) {
            for (UUID mobId : mobs) {
                Entity mob = Bukkit.getEntity(mobId);
                if (mob != null) mob.remove();
            }
        }
        saveMarkers();
        return true;
    }

    /** マーカー自体は残したまま、そこから現在湧いている個体だけを全てデスポーンさせる。
     *  デスポーンさせた数を返す（0以上）。 */
    public int resetMarkerMobs(int id) {
        Set<UUID> mobs = activeMobs.get(id);
        if (mobs == null || mobs.isEmpty()) return 0;
        int count = 0;
        for (UUID mobId : mobs) {
            Entity mob = Bukkit.getEntity(mobId);
            if (mob != null && mob.isValid()) {
                mob.remove();
                count++;
            }
        }
        mobs.clear();
        return count;
    }

    /** そのマーカーから現在何体湧いているか（表示用）。 */
    public int activeMobCount(int id) {
        Set<UUID> mobs = activeMobs.get(id);
        if (mobs == null) return 0;
        int count = 0;
        for (UUID mobId : mobs) {
            Entity mob = Bukkit.getEntity(mobId);
            if (mob != null && mob.isValid()) count++;
        }
        return count;
    }

    /**
     * そのエンティティを捕まえるのに実際に必要なタグ。マーカーの生物枠に
     * 上書き（{@link jp.mushitori.model.WeightedCreature#requiredTagsOverride()}）が
     * 焼き付けられていればそちらを、無ければ引数の生物本来（creatures.ymlの
     * required-tags）の設定をそのまま返す。
     */
    public Set<String> effectiveRequiredTags(Entity entity, Creature creature) {
        String raw = entity.getPersistentDataContainer()
                .get(Keys.REQUIRED_TAGS_OVERRIDE, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return creature.requiredTags();
        return new java.util.LinkedHashSet<>(List.of(raw.split(",")));
    }

    /** そのエンティティに実際に適用される「飛ぶ生物かどうか」。マーカーの上書きがあればそちら。 */
    public boolean effectiveFlying(Entity entity, Creature creature) {
        Byte raw = entity.getPersistentDataContainer().get(Keys.FLYING_OVERRIDE, PersistentDataType.BYTE);
        return raw != null ? raw != 0 : creature.flying();
    }

    /** そのエンティティに実際に適用される「基準の逃げやすさ」。マーカーの上書きがあればそちら。 */
    public double effectiveEscapeChance(Entity entity, Creature creature) {
        Double raw = entity.getPersistentDataContainer().get(Keys.ESCAPE_CHANCE_OVERRIDE, PersistentDataType.DOUBLE);
        return raw != null ? raw : creature.escapeChance();
    }

    /** そのエンティティに実際に適用される「逃げると完全に消えるか」。マーカーの上書きがあればそちら。 */
    public boolean effectiveEscapeDespawns(Entity entity, Creature creature) {
        Byte raw = entity.getPersistentDataContainer().get(Keys.ESCAPE_DESPAWNS_OVERRIDE, PersistentDataType.BYTE);
        return raw != null ? raw != 0 : creature.behavior().escapeDespawns();
    }

    @Nullable
    public SpawnMarker marker(int id) {
        return markers.get(id);
    }

    /** 指定した座標に最も近いマーカーを探す（範囲内に無ければ null）。 */
    @Nullable
    public SpawnMarker nearestMarker(Location location, double withinRadius) {
        SpawnMarker best = null;
        double bestDist = Double.MAX_VALUE;
        String worldName = location.getWorld() == null ? "" : location.getWorld().getName();
        for (SpawnMarker m : markers.values()) {
            if (!m.worldName().equals(worldName)) continue;
            double dx = m.x() - location.getX();
            double dy = m.y() - location.getY();
            double dz = m.z() - location.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist <= withinRadius && dist < bestDist) {
                bestDist = dist;
                best = m;
            }
        }
        return best;
    }

    /**
     * プレイヤーの視線（レイ）に最も近いマーカーを探す。マーカーは通常見えないため、
     * 「近くにある」ではなく「狙っている」もの、つまり視線の延長線上に一番近いものを
     * 選ぶことで、設定棒で直接狙って設定画面を開けるようにする。
     *
     * @param eye            視点（プレイヤーの目の位置）
     * @param direction      視線の向き（正規化されていなくてもよい）
     * @param maxDistance    視線方向にどこまで探すか（ブロック）
     * @param maxOffRayDist  視線からどれだけ外れていても許容するか（ブロック）
     */
    @Nullable
    public SpawnMarker markerAlongRay(Location eye, Vector direction, double maxDistance, double maxOffRayDist) {
        World world = eye.getWorld();
        if (world == null) return null;
        Vector dir = direction.clone();
        if (dir.lengthSquared() < 1.0E-6) return null;
        dir.normalize();

        SpawnMarker best = null;
        double bestOffRay = Double.MAX_VALUE;
        for (SpawnMarker m : markers.values()) {
            if (!m.worldName().equals(world.getName())) continue;
            Vector toMarker = new Vector(m.x() - eye.getX(), m.y() - eye.getY(), m.z() - eye.getZ());
            double alongRay = toMarker.dot(dir);
            if (alongRay < -0.5 || alongRay > maxDistance) continue; // 視線の後ろ・遠すぎるものは除外
            double offRay = toMarker.subtract(dir.clone().multiply(alongRay)).length();
            if (offRay > maxOffRayDist) continue;
            if (offRay < bestOffRay) {
                bestOffRay = offRay;
                best = m;
            }
        }
        return best;
    }

    public List<SpawnMarker> allMarkers() {
        return markers.values().stream().sorted(Comparator.comparingInt(SpawnMarker::id)).toList();
    }

    @Nullable
    public SpawnPreset preset(String name) {
        return presets.get(name);
    }

    public List<String> presetNames() {
        return List.copyOf(presets.keySet());
    }

    // ==================== 設定棒（マーカー設定用デバッグアイテム） ====================

    private Material wandMaterial = Material.BLAZE_ROD;
    private String wandName = "マーカー設定棒";

    public void loadWandConfig(@Nullable ConfigurationSection section) {
        if (section == null) return;
        Material m = Material.matchMaterial(section.getString("material", "BLAZE_ROD"));
        wandMaterial = m == null ? Material.BLAZE_ROD : m;
        wandName = section.getString("name", wandName);
    }

    public ItemStack createWandItem() {
        ItemStack item = new ItemStack(wandMaterial);
        item.editMeta(meta -> {
            meta.displayName(Component.text(wandName, NamedTextColor.LIGHT_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    line("右クリック: マーカー一覧を開く"),
                    line("スニーク＋右クリック: 狙った場所に新規マーカーを設置"),
                    line("左クリック: 近くのマーカーを削除"),
                    line("持っている間、近くのマーカーが見えます")));
            meta.getPersistentDataContainer().set(Keys.MARKER_WAND, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    public boolean isWand(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(Keys.MARKER_WAND, PersistentDataType.BYTE);
    }

    // ==================== 可視化（設定棒を持っている間、マーカーを見えるようにする） ====================

    private static final double VISUALIZE_RANGE = 32.0;
    private static final int CIRCLE_POINTS = 20;

    public void tickVisualization() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isWand(player.getInventory().getItemInMainHand())
                    && !isWand(player.getInventory().getItemInOffHand())) {
                continue;
            }
            Location eye = player.getEyeLocation();
            World world = eye.getWorld();
            if (world == null) continue;

            for (SpawnMarker marker : markers.values()) {
                if (!marker.worldName().equals(world.getName())) continue;
                Location center = new Location(world, marker.x(), marker.y(), marker.z());
                if (center.distanceSquared(eye) > VISUALIZE_RANGE * VISUALIZE_RANGE) continue;

                world.spawnParticle(Particle.END_ROD, center.clone().add(0, 0.5, 0), 3, 0.1, 0.3, 0.1, 0.01);
                drawCircle(world, center, marker.spawnRadius(), Particle.HAPPY_VILLAGER);
            }
        }
    }

    private void drawCircle(World world, Location center, double radius, Particle particle) {
        if (radius <= 0) return;
        for (int i = 0; i < CIRCLE_POINTS; i++) {
            double angle = (2 * Math.PI * i) / CIRCLE_POINTS;
            double dx = Math.cos(angle) * radius;
            double dz = Math.sin(angle) * radius;
            world.spawnParticle(particle, center.clone().add(dx, 0.2, dz), 1, 0, 0, 0, 0);
        }
    }

    // ==================== スケジューリング ====================

    private void startTask(SpawnMarker marker) {
        Location loc = resolveLocation(marker);
        if (loc == null) return;
        long periodTicks = Math.max(20L, Math.round(marker.spawnIntervalSeconds() * 20.0));
        ScheduledTask task = Bukkit.getServer().getRegionScheduler()
                .runAtFixedRate(plugin, loc, t -> tick(marker.id()), 20L, periodTicks);
        tasks.put(marker.id(), task);
    }

    private void stopTask(int id) {
        ScheduledTask task = tasks.remove(id);
        if (task != null) task.cancel();
    }

    private void stopAllTasks() {
        for (ScheduledTask t : tasks.values()) {
            t.cancel();
        }
        tasks.clear();
    }

    /** プラグイン無効化時に呼ぶ。湧いている個体自体は消さない（次回起動時に引き継ぐ）。 */
    public void shutdown() {
        stopAllTasks();
    }

    @Nullable
    private Location resolveLocation(SpawnMarker marker) {
        World world = Bukkit.getWorld(marker.worldName());
        if (world == null) return null;
        return new Location(world, marker.x(), marker.y(), marker.z());
    }

    private void reconcileExistingMobs(SpawnMarker marker) {
        Location loc = resolveLocation(marker);
        if (loc == null || loc.getWorld() == null) return;
        Set<UUID> found = ConcurrentHashMap.newKeySet();
        for (Entity e : loc.getWorld().getEntities()) {
            Integer markerId = e.getPersistentDataContainer().get(Keys.MARKER_ID, PersistentDataType.INTEGER);
            if (markerId != null && markerId == marker.id()) {
                found.add(e.getUniqueId());
            }
        }
        if (!found.isEmpty()) {
            activeMobs.put(marker.id(), found);
        }
    }

    // ==================== 本体のロジック ====================

    private void tick(int markerId) {
        SpawnMarker marker = markers.get(markerId);
        if (marker == null) {
            stopTask(markerId);
            return;
        }
        Location loc = resolveLocation(marker);
        if (loc == null) return;

        Set<UUID> mobs = activeMobs.computeIfAbsent(markerId, k -> ConcurrentHashMap.newKeySet());
        mobs.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });

        leashMobs(marker, loc, mobs);

        // 誰もマーカーの近くにいなければ、生きている個体をデスポーンさせる（捕獲記録はそのまま残す）。
        // ただし、マーカーからは離れていても、その個体自身の近くにプレイヤーがいる場合
        // （追いかけている最中と見なせる場合）は、その個体だけはデスポーンさせない。
        if (!mobs.isEmpty() && !anyPlayerNear(loc, marker.despawnRadius())) {
            int despawned = 0;
            for (Iterator<UUID> it = mobs.iterator(); it.hasNext(); ) {
                UUID mobId = it.next();
                Entity mob = Bukkit.getEntity(mobId);
                if (mob == null) {
                    it.remove();
                    continue;
                }
                if (anyPlayerNear(mob.getLocation(), marker.despawnRadius())) {
                    continue; // その個体自身の近くには誰かいる：追われている最中とみなし、残す
                }
                mob.remove();
                it.remove();
                despawned++;
            }
            if (despawned > 0) {
                log(marker, "マーカーから誰もいなくなったため、" + despawned + "体をデスポーンさせました"
                        + "（追われている個体は残しています）。");
            }
        }

        int recentCatches = pruneAndCountCatches(markerId, marker.catchWindowSeconds());
        log(marker, "巡回: 生存" + mobs.size() + "体、直近捕獲" + recentCatches + "件（カウント窓"
                + marker.catchWindowSeconds() + "秒）、上限" + marker.maxCount());
        int available = marker.maxCount() - (mobs.size() + recentCatches);
        if (available <= 0) {
            log(marker, "枠が埋まっているため湧きません。（生存" + mobs.size() + " ＋ 直近捕獲"
                    + recentCatches + " ／ 上限" + marker.maxCount() + "）");
            return; // この場所の「枠」がまだ埋まっている
        }

        List<Player> nearby = nearbyPlayers(loc, marker.triggerRadius());
        if (nearby.isEmpty()) return;

        if (marker.creatures().isEmpty()) {
            log(marker, "湧かせる生物が設定されていません。");
            return;
        }

        // 「一度に湧く最大数」と「残り枠」の小さい方を、湧かせる候補の枠数とする。
        // 候補の枠それぞれについて、独立に spawn-chance を判定する
        // （例：候補2枠・spawn-chance 50% なら、0〜2匹のどれかが湧く）。
        int candidateSlots = Math.min(available, Math.max(1, marker.simultaneousMax()));
        int spawnedThisTick = 0;
        for (int slot = 0; slot < candidateSlots; slot++) {
            if (marker.spawnChance() < 1.0
                    && ThreadLocalRandom.current().nextDouble() >= marker.spawnChance()) {
                continue; // この枠は確率判定に外れた（次の間隔でまた判定される）
            }

            WeightedCreature picked = pickWeighted(marker.creatures());
            Creature creature = plugin.creatures().get(picked.creatureId());
            if (creature == null) {
                log(marker, "生物ID '" + picked.creatureId() + "' が見つかりません。");
                continue;
            }

            Location spawnAt = findSafeSpawnPoint(loc, marker.spawnRadius());
            if (spawnAt == null) {
                log(marker, "範囲内に安全な地面・水面が見つからなかったため、この枠は見送ります。");
                continue;
            }
            Entity spawned = plugin.spawnService().spawn(creature, spawnAt, marker.sizeDistributionTemplate(),
                    picked.override());
            if (spawned == null) continue;
            spawned.getPersistentDataContainer().set(Keys.MARKER_ID, PersistentDataType.INTEGER, markerId);
            if (!picked.requiredTagsOverride().isEmpty()) {
                // このマーカーのこの枠だけの、必要タグの上書き
                spawned.getPersistentDataContainer().set(Keys.REQUIRED_TAGS_OVERRIDE, PersistentDataType.STRING,
                        String.join(",", picked.requiredTagsOverride()));
            }
            CreatureOverride ov = picked.override();
            if (ov.flying() != null) {
                spawned.getPersistentDataContainer().set(Keys.FLYING_OVERRIDE, PersistentDataType.BYTE,
                        (byte) (ov.flying() ? 1 : 0));
            }
            if (ov.escapeChance() != null) {
                spawned.getPersistentDataContainer().set(Keys.ESCAPE_CHANCE_OVERRIDE, PersistentDataType.DOUBLE,
                        ov.escapeChance());
            }
            if (ov.escapeDespawns() != null) {
                spawned.getPersistentDataContainer().set(Keys.ESCAPE_DESPAWNS_OVERRIDE, PersistentDataType.BYTE,
                        (byte) (ov.escapeDespawns() ? 1 : 0));
            }
            mobs.add(spawned.getUniqueId());
            spawnedThisTick++;
            log(marker, creature.name() + " が湧きました。（生存" + mobs.size() + " ＋ 直近捕獲" + recentCatches
                    + " ／ 上限" + marker.maxCount() + "、今回" + spawnedThisTick + "/" + candidateSlots + "枠）");
        }
    }

    /** ウェイトの比率でランダムに1種類選ぶ。 */
    private WeightedCreature pickWeighted(List<WeightedCreature> pool) {
        int total = 0;
        for (WeightedCreature wc : pool) total += wc.weight();
        if (total <= 0) return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));

        int r = ThreadLocalRandom.current().nextInt(total);
        int cumulative = 0;
        for (WeightedCreature wc : pool) {
            cumulative += wc.weight();
            if (r < cumulative) return wc;
        }
        return pool.get(pool.size() - 1);
    }

    /** 出現した範囲（spawn-radius）からあまり離れすぎないよう、外に出た個体を範囲内へ戻す。 */
    private static final double LEASH_MULTIPLIER = 1.3;
    private void leashMobs(SpawnMarker marker, Location center, Set<UUID> mobs) {
        double radius = marker.spawnRadius();
        if (radius <= 0) return; // 0（中心ちょうど固定）の場合は特に制限しない
        double leash2 = (radius * LEASH_MULTIPLIER) * (radius * LEASH_MULTIPLIER);

        for (UUID mobId : mobs) {
            Entity mob = Bukkit.getEntity(mobId);
            if (mob == null || !mob.isValid()) continue;
            Location mobLoc = mob.getLocation();
            World mobWorld = mobLoc.getWorld();
            if (mobWorld == null || center.getWorld() == null || !mobWorld.equals(center.getWorld())) continue;

            double dx = mobLoc.getX() - center.getX();
            double dz = mobLoc.getZ() - center.getZ();
            if (dx * dx + dz * dz <= leash2) continue;

            // 範囲内のランダムな地点（地面・水面を考慮して安全な高さを探したもの）へ、
            // テレポートではなく歩いて戻らせる
            Location back = findSafeSpawnPoint(center, radius * 0.5);
            if (back == null) {
                // 安全な地点が見つからなければ、保険として個体の現在の高さのまま戻す
                back = randomPointAround(center, radius * 0.5);
                back.setY(mobLoc.getY());
            }
            if (mob instanceof org.bukkit.entity.Mob pathableMob) {
                pathableMob.getPathfinder().moveTo(back, leashWalkSpeed);
            } else {
                // パスファインダーを持たないentity種別への保険：これまで通り瞬間移動する
                mob.teleport(back);
            }
        }
    }

    private Location randomPointAround(Location center, double radius) {
        if (radius <= 0) return center.clone();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble() * Math.PI * 2;
        double r = rnd.nextDouble() * radius;
        double dx = Math.cos(angle) * r;
        double dz = Math.sin(angle) * r;
        return center.clone().add(dx, 0, dz);
    }

    /** マーカーの何もない範囲の座標指定だけを頼りに湧かせていたところ、崖際や
     *  浮島の端では空中・void上に湧いてしまい、下へ落ち続けて地面に埋まったように
     *  見える不具合があった。X/Zはランダムに決めつつ、Yはマーカー付近を上下に探索し、
     *  実際に乗れる地面／浸れる水面を見つけてから、その上に湧かせるようにする。
     *  何回試しても見つからなければ null（今回は湧かせない）。 */
    @Nullable
    private Location findSafeSpawnPoint(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) return null;

        for (int attempt = 0; attempt < 6; attempt++) {
            Location candidate = randomPointAround(center, radius);
            Location grounded = groundedAt(world, candidate.getX(), candidate.getZ(), center.getY());
            if (grounded != null) return grounded;
        }
        // 何回か試して見つからなければ、マーカーの中心そのもの（元々設置された場所）を最後の保険にする
        return groundedAt(world, center.getX(), center.getZ(), center.getY());
    }

    /** 指定したX/Z付近を、基準Yから上下に探索し、乗れる地面／浸れる水面のすぐ上を返す。見つからなければ null。 */
    @Nullable
    private Location groundedAt(World world, double x, double z, double baseY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int base = (int) Math.round(baseY);
        int top = Math.min(world.getMaxHeight() - 1, base + 6);
        int bottom = Math.max(world.getMinHeight(), base - 10);

        for (int y = top; y >= bottom; y--) {
            Material type = world.getBlockAt(bx, y, bz).getType();
            if (type == Material.WATER) {
                // 水面：その水ブロックの中（浸った状態）に置く
                return new Location(world, x, y + 0.2, z);
            }
            if (type.isSolid()) {
                // 地面：ブロックのすぐ上に置く
                return new Location(world, x, y + 1.0, z);
            }
        }
        return null;
    }

    private boolean anyPlayerNear(Location loc, double radius) {
        World world = loc.getWorld();
        if (world == null) return false;
        double r2 = radius * radius;
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= r2) return true;
        }
        return false;
    }

    private List<Player> nearbyPlayers(Location loc, double radius) {
        World world = loc.getWorld();
        if (world == null) return List.of();
        double r2 = radius * radius;
        List<Player> result = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= r2) result.add(p);
        }
        return result;
    }

    /**
     * そのマーカーで（プレイヤーが）捕まえたことを記録する（最大数の判定に使う「直近の捕獲」ぶん）。
     * {@link jp.mushitori.listener.NetListener} / {@link ApproachFishingService} から呼ばれます。
     */
    public void recordCatch(int markerId, Player player) {
        catchLog.computeIfAbsent(markerId, k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
    }

    /** 古い捕獲記録を間引き、直近ぶんの件数を返す。 */
    private int pruneAndCountCatches(int markerId, double windowSeconds) {
        Deque<Long> log = catchLog.get(markerId);
        if (log == null) return 0;
        long cutoff = System.currentTimeMillis() - Math.round(windowSeconds * 1000.0);
        while (!log.isEmpty() && log.peekFirst() < cutoff) {
            log.pollFirst();
        }
        return log.size();
    }

    /** エンティティに付いているマーカーIDを読む（マーカー由来でなければ null）。 */
    @Nullable
    public Integer markerIdOf(Entity entity) {
        return entity.getPersistentDataContainer().get(Keys.MARKER_ID, PersistentDataType.INTEGER);
    }

    private void log(SpawnMarker marker, String message) {
        if (debug) {
            plugin.getLogger().info("[ambient-spawn] marker#" + marker.id()
                    + " (" + marker.creatures() + "): " + message);
        }
    }
}
