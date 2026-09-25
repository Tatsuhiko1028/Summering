package jp.mushitori.registry;

import jp.mushitori.model.ApproachOverrides;
import jp.mushitori.model.Category;
import jp.mushitori.model.Creature;
import jp.mushitori.model.CreatureBehavior;
import jp.mushitori.model.TropicalFishVariant;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TropicalFish;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * creatures.yml を読み込んで保持する。
 *
 * <p>各生物は、任意で以下を個別に持てます（省略時はグローバル設定を使用）：</p>
 * <ul>
 *   <li>{@code base-rarity} … 固定のベースレア度（省略時は config.yml の
 *       catching.default-base-rarity）。最終レア度はここから、捕まえた瞬間の
 *       サイズ段階に応じて繰り上がります（ランダム抽選ではありません）</li>
 *   <li>{@code size-rarity-template} … サイズ→レア度繰り上げのテンプレート名
 *       （省略時は config.yml の catching.default-size-rarity-template）</li>
 *   <li>{@code escape-chance} … 基準の逃げやすさ（省略時は
 *       config.yml の catching.default-escape-chance）</li>
 *   <li>{@code approach} セクション … 「待つ/寄ってくる」挙動の個別設定（FISHのどの生物にも指定可）
 *       （patience-seconds, retry-interval-seconds, trigger-chance,
 *        min-approach-seconds, max-approach-seconds, window-seconds）</li>
 * </ul>
 */
public final class CreatureRegistry {

    private final Plugin plugin;
    private final Map<String, Creature> byId = new LinkedHashMap<>();
    private final Map<Category, List<Creature>> byCategory = new EnumMap<>(Category.class);
    private final Map<String, ApproachOverrides> approachOverrides = new LinkedHashMap<>();
    private double defaultEscapeChance = 0.2;
    private String defaultBaseRarity = "N";

    public CreatureRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    /** creatures.yml で escape-chance を省略した生物に使う既定値。load() より前に呼んでください。 */
    public void setDefaultEscapeChance(double defaultEscapeChance) {
        this.defaultEscapeChance = defaultEscapeChance;
    }

    /** creatures.yml で base-rarity を省略した生物に使う既定値。load() より前に呼んでください。 */
    public void setDefaultBaseRarity(String defaultBaseRarity) {
        this.defaultBaseRarity = defaultBaseRarity == null || defaultBaseRarity.isBlank() ? "N" : defaultBaseRarity;
    }

    public void load() {
        byId.clear();
        byCategory.clear();
        approachOverrides.clear();
        for (Category c : Category.values()) {
            byCategory.put(c, new ArrayList<>());
        }

        File file = new File(plugin.getDataFolder(), "creatures.yml");
        if (!file.exists()) {
            plugin.saveResource("creatures.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("creatures");
        if (root == null) {
            plugin.getLogger().warning("creatures.yml に creatures セクションがありません。");
            return;
        }

        int order = 0;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                byId.put(id, read(id, s, order++));
                readApproachOverride(id, s);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "いきもの '" + id + "' の読み込みに失敗しました: " + e.getMessage());
            }
        }
        for (Creature c : byId.values()) {
            byCategory.get(c.category()).add(c);
        }
        plugin.getLogger().info("いきもの " + byId.size() + " 種を読み込みました。");
    }

    private Creature read(String id, ConfigurationSection s, int order) {
        if (!id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("IDは半角小文字・数字・_ のみ使えます");
        }
        String name = s.getString("name", id);
        Category category = Category.parse(s.getString("category"), Category.BUG);
        double min = s.getDouble("size-min", 1.0);
        double max = s.getDouble("size-max", Math.max(1.1, min + 1.0));
        if (max <= min) {
            throw new IllegalArgumentException("size-max は size-min より大きくしてください");
        }
        int price = s.getInt("base-price", 100);
        String habitat = s.getString("habitat", "");
        List<String> desc = s.getStringList("description");

        Material material = Material.PAPER;
        Integer customModelData = null;
        ConfigurationSection item = s.getConfigurationSection("item");
        if (item != null) {
            Material m = Material.matchMaterial(item.getString("material", "PAPER"));
            if (m != null) material = m;
            if (item.contains("custom-model-data")) {
                customModelData = item.getInt("custom-model-data");
            }
        }

        EntityType entity = null;
        String entityRaw = s.getString("entity");
        if (entityRaw != null && !entityRaw.isBlank()) {
            try {
                entity = EntityType.valueOf(entityRaw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning(id + ": entity '" + entityRaw + "' は不明なエンティティです");
            }
        }

        String baseRarity = s.getString("base-rarity", defaultBaseRarity);
        String sizeRarityTemplate = s.getString("size-rarity-template", "");
        TropicalFishVariant tropicalFishVariant = readTropicalFishVariant(id, s);
        double baseScale = s.getDouble("base-scale", 1.0);
        CreatureBehavior behavior = readBehavior(s.getConfigurationSection("behavior"));
        java.util.Set<String> requiredTags = new java.util.LinkedHashSet<>(s.getStringList("required-tags"));
        boolean flying = s.contains("flying") ? s.getBoolean("flying") : (entity == EntityType.BEE);

        return new Creature(id, name, category, min, max, price, habitat, desc, material, customModelData, entity, order,
                s.getDouble("escape-chance", defaultEscapeChance),
                baseRarity,
                sizeRarityTemplate.isBlank() ? null : sizeRarityTemplate,
                tropicalFishVariant,
                baseScale,
                behavior,
                requiredTags,
                flying);
    }

    /** creatures.yml の behavior セクションを読み込む。省略されていれば既定値。 */
    private CreatureBehavior readBehavior(@Nullable ConfigurationSection s) {
        CreatureBehavior d = CreatureBehavior.defaults();
        if (s == null) return d;
        return new CreatureBehavior(
                s.getBoolean("disable-nectar", d.disableNectar()),
                s.getBoolean("flee-from-players", d.fleeFromPlayers()),
                s.getDouble("flee-radius", d.fleeRadius()),
                s.getDouble("flee-speed", d.fleeSpeed()),
                s.getDouble("movement-speed-multiplier", d.movementSpeedMultiplier()),
                s.getBoolean("escape-despawns", d.escapeDespawns()));
    }

    @Nullable
    private TropicalFishVariant readTropicalFishVariant(String id, ConfigurationSection s) {
        ConfigurationSection tf = s.getConfigurationSection("tropical-fish");
        if (tf == null) return null;

        TropicalFish.Pattern pattern = parseEnum(TropicalFish.Pattern.class, tf.getString("pattern"),
                id, "tropical-fish.pattern");
        DyeColor bodyColor = parseEnum(DyeColor.class, tf.getString("body-color"),
                id, "tropical-fish.body-color");
        DyeColor patternColor = parseEnum(DyeColor.class, tf.getString("pattern-color"),
                id, "tropical-fish.pattern-color");

        TropicalFishVariant variant = new TropicalFishVariant(pattern, bodyColor, patternColor);
        return variant.isEmpty() ? null : variant;
    }

    @Nullable
    private <E extends Enum<E>> E parseEnum(Class<E> type, @Nullable String raw, String id, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(id + ": " + field + " '" + raw + "' は不明な値です");
            return null;
        }
    }

    private void readApproachOverride(String id, ConfigurationSection s) {
        ConfigurationSection a = s.getConfigurationSection("approach");
        if (a == null) return;
        approachOverrides.put(id, new ApproachOverrides(
                a.contains("patience-seconds") ? a.getDouble("patience-seconds") : null,
                a.contains("retry-interval-seconds") ? a.getDouble("retry-interval-seconds") : null,
                a.contains("trigger-chance") ? a.getDouble("trigger-chance") : null,
                a.contains("min-approach-seconds") ? a.getDouble("min-approach-seconds") : null,
                a.contains("max-approach-seconds") ? a.getDouble("max-approach-seconds") : null,
                a.contains("window-seconds") ? a.getDouble("window-seconds") : null));
    }

    @Nullable
    public Creature get(String id) {
        return id == null ? null : byId.get(id);
    }

    public List<Creature> all() {
        return List.copyOf(byId.values());
    }

    public List<Creature> byCategory(Category category) {
        return List.copyOf(byCategory.getOrDefault(category, List.of()));
    }

    /**
     * 魚（{@link Category#FISH}）をすべて返す。通常釣り・寄ってくる釣りのどちらでも、
     * 種類を問わず両方の方法で狙える。以前あった FISH_ROD / FISH_APPROACH という
     * 2つのカテゴリの区別は廃止し、FISH の1つに統一しました。
     */
    public List<Creature> fishCreatures() {
        return byCategory(Category.FISH);
    }

    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }

    public int size() {
        return byId.size();
    }

    /** その生物専用の「寄ってくる釣り」難易度設定（未設定分はグローバル既定値）。 */
    public ApproachOverrides approachOverridesFor(String creatureId) {
        return approachOverrides.getOrDefault(creatureId, ApproachOverrides.EMPTY);
    }
}
