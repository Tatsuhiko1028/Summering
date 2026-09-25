package jp.mushitori.ui;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.ApproachOverrides;
import jp.mushitori.model.Creature;
import jp.mushitori.model.CreatureOverride;
import jp.mushitori.model.Rarity;
import jp.mushitori.model.WeightedCreature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * マーカーの生物枠1つぶんの、詳細設定（見た目・動き・レア度・アプローチの上書き）画面。
 * creatures.ymlで設定できる項目のうち、マーカーの生物枠ごとに上書きできる
 * ものを、テーマ（行）ごとにグループ化して並べています。
 *
 * <ul>
 *   <li>1行目：基本（必要タグ・見た目のスケール・飛ぶ生物かどうか）</li>
 *   <li>2行目：逃げやすさ・捕獲まわり（escape-chance・escape-despawns）</li>
 *   <li>3行目：近づくと逃げる（flee-from-players・flee-radius・flee-speed・movement-speed-multiplier）</li>
 *   <li>4行目：その他の動き・サイズ/レア度（disable-nectar・サイズイベント・size-rarity-template・base-rarity）</li>
 *   <li>5行目：アプローチ（寄ってくる釣り）関連の6項目</li>
 *   <li>6行目：操作（全解除・戻る）</li>
 * </ul>
 *
 * <p>数値・タグ・テンプレート名などの「文字で入力する」項目はクリックしてからチャットに
 * 入力する形にし、アイコンは全て{@code WRITABLE_BOOK}（本と羽ペん）に統一、上書きが
 * 設定されているときだけエンチャント光沢を付けています。true/falseの項目はクリックで
 * そのまま切り替え（上書き無し → true → false → 上書き無し、の3段階で巡回）します。</p>
 */
public final class CreatureDetailGui implements InventoryHolder {

    // 1行目：基本
    public static final int SLOT_REQUIRED_TAGS = 0;
    public static final int SLOT_SCALE = 1;
    public static final int SLOT_FLYING = 2;

    // 2行目：逃げやすさ・捕獲
    public static final int SLOT_ESCAPE_CHANCE = 9;
    public static final int SLOT_ESCAPE_DESPAWNS = 10;

    // 3行目：近づくと逃げる
    public static final int SLOT_FLEE_FROM_PLAYERS = 18;
    public static final int SLOT_FLEE_RADIUS = 19;
    public static final int SLOT_FLEE_SPEED = 20;
    public static final int SLOT_MOVEMENT_SPEED_MULTIPLIER = 21;

    // 4行目：その他の動き・サイズ/レア度
    public static final int SLOT_DISABLE_NECTAR = 27;
    public static final int SLOT_SIZE_DISTRIBUTION_TEMPLATE = 28;
    public static final int SLOT_SIZE_RARITY_TEMPLATE = 29;
    public static final int SLOT_BASE_RARITY = 30;

    // 5行目：アプローチ（寄ってくる釣り）
    public static final int SLOT_APPROACH_PATIENCE = 36;
    public static final int SLOT_APPROACH_RETRY_INTERVAL = 37;
    public static final int SLOT_APPROACH_TRIGGER_CHANCE = 38;
    public static final int SLOT_APPROACH_MIN_SECONDS = 39;
    public static final int SLOT_APPROACH_MAX_SECONDS = 40;
    public static final int SLOT_APPROACH_WINDOW_SECONDS = 41;

    // 6行目：操作
    public static final int SLOT_CLEAR_ALL = 49;
    public static final int SLOT_BACK = 53;

    private static final int SIZE = 54;

    private final MushitoriPlugin plugin;
    private final int markerId;
    private final int slotIndex;
    private Inventory inventory;

    private CreatureDetailGui(MushitoriPlugin plugin, int markerId, int slotIndex) {
        this.plugin = plugin;
        this.markerId = markerId;
        this.slotIndex = slotIndex;
    }

    public int markerId() {
        return markerId;
    }

    public int slotIndex() {
        return slotIndex;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static CreatureDetailGui open(MushitoriPlugin plugin, Player player, int markerId, int slotIndex,
                                         WeightedCreature wc) {
        CreatureDetailGui gui = new CreatureDetailGui(plugin, markerId, slotIndex);
        gui.inventory = Bukkit.createInventory(gui, SIZE,
                Component.text("枠 " + (slotIndex + 1) + " の詳細設定"));
        for (int i = 0; i < SIZE; i++) {
            gui.inventory.setItem(i, glass());
        }
        gui.redraw(wc);
        player.openInventory(gui.inventory);
        return gui;
    }

    public void redraw(WeightedCreature wc) {
        CreatureOverride ov = wc.override();
        ApproachOverrides ao = wc.approachOverride();
        Creature creature = plugin.creatures().get(wc.creatureId());

        inventory.setItem(SLOT_REQUIRED_TAGS, valueIcon("必要タグの上書き",
                wc.requiredTagsOverride().isEmpty() ? null : String.join(", ", wc.requiredTagsOverride()),
                "クリックしてチャットにカンマ区切りで入力（例: event,special）"));

        inventory.setItem(SLOT_SCALE, valueIcon("見た目のスケール上書き（base-scale）",
                ov.scale() == null ? null : String.valueOf(ov.scale()),
                "クリックしてチャットに数値を入力（例: 0.8）"));

        inventory.setItem(SLOT_FLYING, boolIcon("飛ぶ生物かどうか上書き（flying）", ov.flying()));

        inventory.setItem(SLOT_ESCAPE_CHANCE, valueIcon("基準の逃げやすさ上書き（escape-chance）",
                ov.escapeChance() == null ? null : String.valueOf(ov.escapeChance()),
                "クリックしてチャットに0.0〜1.0の数値を入力（例: 0.3）"));
        inventory.setItem(SLOT_ESCAPE_DESPAWNS, boolIcon("逃げると完全に消える上書き（escape-despawns）", ov.escapeDespawns()));

        inventory.setItem(SLOT_FLEE_FROM_PLAYERS, boolIcon("近づくと逃げる上書き（flee-from-players）", ov.fleeFromPlayers()));
        inventory.setItem(SLOT_FLEE_RADIUS, valueIcon("逃げ始める距離上書き（flee-radius）",
                ov.fleeRadius() == null ? null : String.valueOf(ov.fleeRadius()),
                "クリックしてチャットに数値（ブロック）を入力（例: 6）"));
        inventory.setItem(SLOT_FLEE_SPEED, valueIcon("逃げる速さ上書き（flee-speed）",
                ov.fleeSpeed() == null ? null : String.valueOf(ov.fleeSpeed()),
                "クリックしてチャットに数値を入力（例: 1.2）"));
        inventory.setItem(SLOT_MOVEMENT_SPEED_MULTIPLIER, valueIcon("移動速度倍率上書き（movement-speed-multiplier）",
                ov.movementSpeedMultiplier() == null ? null : String.valueOf(ov.movementSpeedMultiplier()),
                "クリックしてチャットに数値を入力（例: 0.5でゆっくり）"));

        inventory.setItem(SLOT_DISABLE_NECTAR, boolIcon("蜜集めを無効化上書き（disable-nectar）", ov.disableNectar()));
        inventory.setItem(SLOT_SIZE_DISTRIBUTION_TEMPLATE, cycleIcon(Material.SLIME_BALL,
                "サイズイベント上書き（size-distribution-template）", wc.sizeDistributionTemplate(),
                "無し（既定：config.ymlのsizesセクション）"));
        inventory.setItem(SLOT_SIZE_RARITY_TEMPLATE, cycleIcon(Material.BOOK,
                "サイズ→レア度テンプレート上書き（size-rarity-template）", ov.sizeRarityTemplate(),
                "無し（生物本来: " + (creature == null || creature.sizeRarityTemplate() == null
                        ? "既定" : creature.sizeRarityTemplate()) + "）"));
        inventory.setItem(SLOT_BASE_RARITY, baseRarityIcon(creature, ov.baseRarityKey()));

        inventory.setItem(SLOT_APPROACH_PATIENCE, valueIcon("アプローチ：反応判定までの待ち時間上書き（patience-seconds）",
                ao.patienceSeconds() == null ? null : String.valueOf(ao.patienceSeconds()),
                "クリックしてチャットに秒数を入力（例: 10）"));
        inventory.setItem(SLOT_APPROACH_RETRY_INTERVAL, valueIcon("アプローチ：再判定間隔上書き（retry-interval-seconds）",
                ao.retryIntervalSeconds() == null ? null : String.valueOf(ao.retryIntervalSeconds()),
                "クリックしてチャットに秒数を入力（例: 3）"));
        inventory.setItem(SLOT_APPROACH_TRIGGER_CHANCE, valueIcon("アプローチ：反応確率上書き（trigger-chance）",
                ao.triggerChance() == null ? null : String.valueOf(ao.triggerChance()),
                "クリックしてチャットに0.0〜1.0の数値を入力（例: 0.4）"));
        inventory.setItem(SLOT_APPROACH_MIN_SECONDS, valueIcon("アプローチ：誘導時間（最短）上書き（min-approach-seconds）",
                ao.minApproachSeconds() == null ? null : String.valueOf(ao.minApproachSeconds()),
                "クリックしてチャットに秒数を入力（例: 2）"));
        inventory.setItem(SLOT_APPROACH_MAX_SECONDS, valueIcon("アプローチ：誘導時間（最長）上書き（max-approach-seconds）",
                ao.maxApproachSeconds() == null ? null : String.valueOf(ao.maxApproachSeconds()),
                "クリックしてチャットに秒数を入力（例: 4）"));
        inventory.setItem(SLOT_APPROACH_WINDOW_SECONDS, valueIcon("アプローチ：竿を振るタイミング上書き（window-seconds）",
                ao.windowSeconds() == null ? null : String.valueOf(ao.windowSeconds()),
                "クリックしてチャットに秒数を入力（例: 1.5）"));

        inventory.setItem(SLOT_CLEAR_ALL, icon(Material.BARRIER,
                Component.text("この枠の上書きを全て解除", NamedTextColor.RED),
                List.of(gray("必要タグ・スケール・動き・レア度・"), gray("アプローチの上書きを、まとめて解除します。"))));
        inventory.setItem(SLOT_BACK, icon(Material.ARROW,
                Component.text("マーカー画面へ戻る", NamedTextColor.YELLOW), List.of()));
    }

    /** 数値・タグ・秒数などの「チャット入力」項目共通のアイコン。全て本と羽ペン（WRITABLE_BOOK）に統一する。 */
    private ItemStack valueIcon(String label, @Nullable String currentValueOrNull, String hint) {
        boolean set = currentValueOrNull != null;
        return icon(Material.WRITABLE_BOOK, set,
                Component.text(label, set ? NamedTextColor.GREEN : NamedTextColor.WHITE),
                List.of(
                        gray("現在: " + (set ? currentValueOrNull : "（上書き無し。creatures.yml本来の設定）")),
                        gray(hint),
                        gray("\"none\" と入力すると、この項目の上書きを解除します。")));
    }

    private ItemStack boolIcon(String label, @Nullable Boolean current) {
        Material material = current == null ? Material.GRAY_DYE : (current ? Material.LIME_DYE : Material.RED_DYE);
        String currentText = current == null ? "（上書き無し。creatures.yml本来の設定）" : (current ? "true" : "false");
        return icon(material,
                Component.text(label, current == null ? NamedTextColor.WHITE
                        : (current ? NamedTextColor.GREEN : NamedTextColor.RED)),
                List.of(
                        gray("現在: " + currentText),
                        gray("クリックで切り替え（上書き無し → true → false → 上書き無し）")));
    }

    /** テンプレート名などを、クリックで次の候補へ巡回させる項目共通のアイコン。 */
    private ItemStack cycleIcon(Material material, String label, @Nullable String current, String defaultLabel) {
        boolean set = current != null;
        return icon(material, set,
                Component.text(label, set ? NamedTextColor.GREEN : NamedTextColor.WHITE),
                List.of(
                        gray("現在: " + (set ? current : defaultLabel)),
                        gray("クリックで次の候補へ切り替え（一周すると既定に戻ります）")));
    }

    /** base-rarityの上書き用アイコン。ユーザーが実際に見て分かるよう、レア度の名前・色を添える。 */
    private ItemStack baseRarityIcon(@Nullable Creature creature, @Nullable String overrideKey) {
        boolean set = overrideKey != null;
        String effectiveKey = set ? overrideKey : (creature == null ? null : creature.baseRarityKey());
        Rarity rarity = plugin.catchService().rarities().get(effectiveKey);
        List<Component> lore = new ArrayList<>();
        lore.add(gray("現在: " + (set ? overrideKey
                : "（上書き無し。生物本来: " + (creature == null ? "?" : creature.baseRarityKey()) + "）")));
        lore.add(Component.text("実際のレア度：", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(rarity.name(), rarity.color()).decoration(TextDecoration.ITALIC, false)));
        lore.add(gray("クリックで次のレア度へ切り替え（一周すると既定に戻ります）"));
        return icon(Material.NETHER_STAR, set,
                Component.text("ベースレア度の上書き（base-rarity）", set ? NamedTextColor.GREEN : NamedTextColor.WHITE),
                lore);
    }

    private static ItemStack glass() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        return icon(material, false, name, lore);
    }

    /**
     * glint=true のとき、実際にはエンチャントせず、見た目のキラキラ（エンチャント光沢）だけを
     * 付ける（Enchantment.LUCKをダミーで付与し、HIDE_ENCHANTSでツールチップのテキストだけ隠す、
     * という昔からある定番の手法）。「上書きが設定されている」ことを、素材を変えずに
     * ひと目で分かるようにするため。
     */
    private static ItemStack icon(Material material, boolean glint, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            if (glint) {
                meta.addEnchant(Enchantment.LUCK, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        });
        return item;
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
