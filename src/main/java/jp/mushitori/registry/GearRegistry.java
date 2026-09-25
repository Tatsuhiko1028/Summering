package jp.mushitori.registry;

import jp.mushitori.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 虫取り網・釣竿・図鑑アイテムの定義。
 *
 * <p>虫取り網・釣竿は、tier番号（1, 2, 3...）ではなく、それぞれ専用のyml
 * （{@code nets.yml} / {@code rods.yml}）で付けた文字列ID（例: "my_net", "master_rod"）で
 * 管理します（設定が複雑になってきたため、config.ymlから分離しています。creatures.ymlと
 * 同じ考え方です）。見た目は custom_model_data（リソースパック側の overrides 方式）で
 * 切り替える想定です。</p>
 *
 * <p><b>ステータスはアイテム側に焼き付けます</b>：ymlは「/mushitori give で
 * 何を渡すか」を決めるテンプレートであり、実際に渡した瞬間の捕まえやすさ補正・
 * 大物ボーナス・射程・タグは、その場でアイテムのPDCに焼き付けられます。以降、その
 * アイテムを実際に使うときの効果は、ymlの現在値ではなく、アイテムに
 * 焼き付けられた値をそのまま参照します。そのため、あとからymlの数値を
 * 変えても、既に配布済みのアイテムの効果は変わりません（次に給付したものから
 * 新しい数値が適用されます）。耐久値は、もともとバニラのDamageableコンポーネント
 * 自体がアイテムに焼き付く仕組みなので、同じ考え方がそのまま成り立っています。</p>
 */
public final class GearRegistry {

    public record Gear(String id, String name, List<String> description, Material material,
                       @Nullable Integer customModelData, double escapeModifier, double sizeBonus,
                       int price, int durability, @Nullable Double range, Set<String> tags) {
        /** 耐久値の設定があるか（0以下は無限耐久扱い）。 */
        public boolean hasDurability() {
            return durability > 0;
        }
    }

    private final Plugin plugin;
    private final Map<String, Gear> nets = new LinkedHashMap<>();
    private final Map<String, Gear> rods = new LinkedHashMap<>();

    private String guideName = "むしとり図鑑";
    private Material guideMaterial = Material.KNOWLEDGE_BOOK;
    private Integer guideCustomModelData;

    public GearRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    /** nets.yml / rods.yml を読み直す。guide の設定だけは、引き続き config.yml から受け取る。 */
    public void load(@Nullable ConfigurationSection guideSection) {
        nets.clear();
        rods.clear();
        readGearFile("nets.yml", "nets", nets, true);
        readGearFile("rods.yml", "rods", rods, false);

        if (guideSection != null) {
            guideName = guideSection.getString("name", guideName);
            Material m = Material.matchMaterial(guideSection.getString("material", "KNOWLEDGE_BOOK"));
            if (m != null) guideMaterial = m;
            guideCustomModelData = guideSection.contains("custom-model-data")
                    ? guideSection.getInt("custom-model-data") : null;
        }
    }

    private void readGearFile(String fileName, String rootKey, Map<String, Gear> target, boolean net) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection(rootKey);
        if (root == null) {
            plugin.getLogger().warning(fileName + " に " + rootKey + " セクションがありません。");
            return;
        }
        readGear(root, target, net);
    }

    private void readGear(@Nullable ConfigurationSection section, Map<String, Gear> target, boolean net) {
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;

            Material m = Material.matchMaterial(s.getString("material", net ? "WOODEN_SWORD" : "FISHING_ROD"));
            target.put(id, new Gear(
                    id,
                    s.getString("name", (net ? "虫取り網 " : "釣竿 ") + id),
                    s.getStringList("description"),
                    m == null ? (net ? Material.WOODEN_SWORD : Material.FISHING_ROD) : m,
                    s.contains("custom-model-data") ? s.getInt("custom-model-data") : null,
                    s.getDouble("escape-modifier", 0.0),
                    s.getDouble("size-bonus", 0.0),
                    s.getInt("price", 0),
                    s.getInt("durability", 0),
                    net && s.contains("range") ? s.getDouble("range") : null,
                    new LinkedHashSet<>(s.getStringList("tags"))));
        }
    }

    @Nullable
    public Gear net(String id) {
        return nets.get(id);
    }

    @Nullable
    public Gear rod(String id) {
        return rods.get(id);
    }

    public List<Gear> nets() {
        return new ArrayList<>(nets.values());
    }

    public List<Gear> rods() {
        return new ArrayList<>(rods.values());
    }

    public List<String> netIds() {
        return new ArrayList<>(nets.keySet());
    }

    public List<String> rodIds() {
        return new ArrayList<>(rods.keySet());
    }

    // ---- アイテム生成（この瞬間のyml設定の値を、アイテムに焼き付ける） ----

    @Nullable
    public ItemStack createNet(String id) {
        Gear gear = nets.get(id);
        if (gear == null) return null;
        return createGearItem(gear, Keys.NET_ID);
    }

    @Nullable
    public ItemStack createRod(String id) {
        Gear gear = rods.get(id);
        if (gear == null) return null;
        return createGearItem(gear, Keys.ROD_ID);
    }

    private ItemStack createGearItem(Gear gear, NamespacedKey idKey) {
        ItemStack item = new ItemStack(gear.material());
        item.editMeta(meta -> {
            meta.displayName(Component.text(gear.name(), NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            for (String line : gear.description()) {
                lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            if (!gear.description().isEmpty() && (gear.escapeModifier() != 0 || gear.sizeBonus() != 0)) {
                lore.add(Component.empty());
            }
            if (gear.escapeModifier() != 0) {
                // escape-modifier は「逃げやすさ」への補正（マイナスほど逃しにくい）。
                // 表示は分かりやすいよう「捕まえやすさ」として符号を反転させる。
                double catchability = -gear.escapeModifier();
                String sign = catchability > 0 ? "+" : "";
                lore.add(Component.text("捕まえやすさ " + sign + Math.round(catchability * 100) + "%", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            if (gear.sizeBonus() != 0) {
                String sign = gear.sizeBonus() > 0 ? "+" : "";
                lore.add(Component.text("大物ボーナス " + sign + Math.round(gear.sizeBonus() * 100) + "%", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            if (gear.range() != null) {
                lore.add(Component.text("射程 " + gear.range() + "ブロック", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            if (!gear.tags().isEmpty()) {
                lore.add(Component.text("対応タグ: " + String.join(", ", gear.tags()), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);

            if (gear.customModelData() != null) meta.setCustomModelData(gear.customModelData());
            if (gear.hasDurability() && meta instanceof Damageable damageable) {
                damageable.setMaxDamage(gear.durability());
                damageable.setDamage(0);
            }
            // 素材が剣・道具の場合、バニラの攻撃力・攻撃速度等が自動で表示されてしまうため隠す
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(idKey, PersistentDataType.STRING, gear.id());
            // ここが今回のポイント：現在のyml設定の値を、アイテム自身に焼き付ける
            pdc.set(Keys.GEAR_ESCAPE_MODIFIER, PersistentDataType.DOUBLE, gear.escapeModifier());
            pdc.set(Keys.GEAR_SIZE_BONUS, PersistentDataType.DOUBLE, gear.sizeBonus());
            if (gear.range() != null) {
                pdc.set(Keys.GEAR_RANGE, PersistentDataType.DOUBLE, gear.range());
            }
            if (!gear.tags().isEmpty()) {
                pdc.set(Keys.GEAR_TAGS, PersistentDataType.STRING, String.join(",", gear.tags()));
            }
        });
        return item;
    }

    // ---- 実際に使うときは、こちら（アイテムに焼き付けられた値）を参照する ----

    /** そのアイテムに焼き付けられた捕まえやすさ補正（escape-modifier）。焼き付けが無ければ0。 */
    public double escapeModifierOf(@Nullable ItemStack item) {
        return readDouble(item, Keys.GEAR_ESCAPE_MODIFIER, 0.0);
    }

    /** そのアイテムに焼き付けられた大物ボーナス（size-bonus）。焼き付けが無ければ0。 */
    public double sizeBonusOf(@Nullable ItemStack item) {
        return readDouble(item, Keys.GEAR_SIZE_BONUS, 0.0);
    }

    /** そのアイテム（網）に焼き付けられた射程。焼き付けが無ければ null（呼び出し側で全体既定値にフォールバックしてください）。 */
    @Nullable
    public Double rangeOf(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(Keys.GEAR_RANGE, PersistentDataType.DOUBLE);
    }

    /** そのアイテムに焼き付けられたタグ一覧。焼き付けが無ければ空集合。 */
    public Set<String> tagsOf(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return Set.of();
        String raw = item.getItemMeta().getPersistentDataContainer().get(Keys.GEAR_TAGS, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return Set.of();
        return new LinkedHashSet<>(List.of(raw.split(",")));
    }

    private double readDouble(@Nullable ItemStack item, NamespacedKey key, double fallback) {
        if (item == null || !item.hasItemMeta()) return fallback;
        Double v = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        return v != null ? v : fallback;
    }

    /**
     * 道具の耐久値を減らす。耐久値の判定自体は、アイテムに焼き付けられている
     * バニラのDamageableコンポーネント（{@code setMaxDamage}で設定した値）をそのまま
     * 使うため、ymlの現在の durability の値には影響されません。
     *
     * @param amount 減らす量。虫取り網は「捕まえた（＋逃げられた）回数」ぶん、
     *               釣竿は「1回の釣果（＋失敗）」ぶんを渡す想定
     * @return 耐久値が尽きて壊れたら true（呼び出し側でアイテムを消してください）。
     *         そもそも耐久値の設定が無いアイテムなら false（壊れません）
     */
    public static boolean damage(ItemStack item, int amount) {
        if (amount <= 0) return false;
        ItemMeta currentMeta = item.getItemMeta();
        if (!(currentMeta instanceof Damageable currentDamageable) || !currentDamageable.hasMaxDamage()) {
            return false;
        }
        int maxDamage = currentDamageable.getMaxDamage();
        boolean[] broken = {false};
        item.editMeta(meta -> {
            if (!(meta instanceof Damageable damageable)) return;
            int next = damageable.getDamage() + amount;
            if (next >= maxDamage) {
                broken[0] = true;
            } else {
                damageable.setDamage(next);
            }
        });
        return broken[0];
    }

    public ItemStack createGuide() {
        ItemStack item = new ItemStack(guideMaterial);
        item.editMeta(meta -> {
            meta.displayName(Component.text(guideName, NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("右クリック: 図鑑を開く", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("（図鑑画面の「図鑑に登録する」から登録できます）", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            if (guideCustomModelData != null) meta.setCustomModelData(guideCustomModelData);
            meta.getPersistentDataContainer().set(Keys.GUIDE, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }
}
