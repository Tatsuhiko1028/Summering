package jp.mushitori.ui;

import jp.mushitori.model.CreatureOverride;
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

import java.util.List;

/**
 * マーカーの生物枠1つぶんの、詳細設定（見た目・動きの上書き）画面。
 * creatures.ymlで設定できる項目のうち、マーカーの生物枠ごとに上書きできる
 * ものを、それぞれ1マスずつのボタンとして並べています。
 *
 * <p>true/falseの項目はクリックでそのまま切り替え（上書き無し → true → false →
 * 上書き無し、の3段階で巡回）、数値・タグの項目はクリックしてからチャットに
 * 入力する形にしています。</p>
 */
public final class CreatureDetailGui implements InventoryHolder {

    // 数値・タグ項目（クリックしてチャット入力）
    public static final int SLOT_REQUIRED_TAGS = 0;
    public static final int SLOT_SCALE = 1;
    public static final int SLOT_ESCAPE_CHANCE = 2;
    public static final int SLOT_FLEE_RADIUS = 3;
    public static final int SLOT_FLEE_SPEED = 4;
    public static final int SLOT_MOVEMENT_SPEED_MULTIPLIER = 5;

    // true/false項目（クリックで巡回）
    public static final int SLOT_FLYING = 9;
    public static final int SLOT_FLEE_FROM_PLAYERS = 10;
    public static final int SLOT_ESCAPE_DESPAWNS = 11;
    public static final int SLOT_DISABLE_NECTAR = 12;

    public static final int SLOT_CLEAR_ALL = 22;
    public static final int SLOT_BACK = 26;

    private final int markerId;
    private final int slotIndex;
    private Inventory inventory;

    private CreatureDetailGui(int markerId, int slotIndex) {
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

    public static CreatureDetailGui open(Player player, int markerId, int slotIndex, WeightedCreature wc) {
        CreatureDetailGui gui = new CreatureDetailGui(markerId, slotIndex);
        gui.inventory = Bukkit.createInventory(gui, 27,
                Component.text("枠 " + (slotIndex + 1) + " の詳細設定"));
        for (int i = 0; i < 27; i++) {
            gui.inventory.setItem(i, glass());
        }
        gui.redraw(wc);
        player.openInventory(gui.inventory);
        return gui;
    }

    public void redraw(WeightedCreature wc) {
        CreatureOverride ov = wc.override();

        inventory.setItem(SLOT_REQUIRED_TAGS, valueIcon(Material.NAME_TAG, "必要タグの上書き",
                wc.requiredTagsOverride().isEmpty() ? null : String.join(", ", wc.requiredTagsOverride()),
                "クリックしてチャットにカンマ区切りで入力（例: event,special）"));

        inventory.setItem(SLOT_SCALE, valueIcon(Material.SLIME_BALL, "見た目のスケール上書き（base-scale）",
                ov.scale() == null ? null : String.valueOf(ov.scale()),
                "クリックしてチャットに数値を入力（例: 0.8）"));

        inventory.setItem(SLOT_ESCAPE_CHANCE, valueIcon(Material.FEATHER, "基準の逃げやすさ上書き（escape-chance）",
                ov.escapeChance() == null ? null : String.valueOf(ov.escapeChance()),
                "クリックしてチャットに0.0〜1.0の数値を入力（例: 0.3）"));

        inventory.setItem(SLOT_FLEE_RADIUS, valueIcon(Material.SPYGLASS, "逃げ始める距離上書き（flee-radius）",
                ov.fleeRadius() == null ? null : String.valueOf(ov.fleeRadius()),
                "クリックしてチャットに数値（ブロック）を入力（例: 6）"));

        inventory.setItem(SLOT_FLEE_SPEED, valueIcon(Material.SUGAR, "逃げる速さ上書き（flee-speed）",
                ov.fleeSpeed() == null ? null : String.valueOf(ov.fleeSpeed()),
                "クリックしてチャットに数値を入力（例: 1.2）"));

        inventory.setItem(SLOT_MOVEMENT_SPEED_MULTIPLIER, valueIcon(Material.RABBIT_FOOT,
                "移動速度倍率上書き（movement-speed-multiplier）",
                ov.movementSpeedMultiplier() == null ? null : String.valueOf(ov.movementSpeedMultiplier()),
                "クリックしてチャットに数値を入力（例: 0.5でゆっくり）"));

        inventory.setItem(SLOT_FLYING, boolIcon("飛ぶ生物かどうか上書き（flying）", ov.flying()));
        inventory.setItem(SLOT_FLEE_FROM_PLAYERS, boolIcon("近づくと逃げる上書き（flee-from-players）", ov.fleeFromPlayers()));
        inventory.setItem(SLOT_ESCAPE_DESPAWNS, boolIcon("逃げると完全に消える上書き（escape-despawns）", ov.escapeDespawns()));
        inventory.setItem(SLOT_DISABLE_NECTAR, boolIcon("蜜集めを無効化上書き（disable-nectar）", ov.disableNectar()));

        inventory.setItem(SLOT_CLEAR_ALL, icon(Material.BARRIER,
                Component.text("この枠の上書きを全て解除", NamedTextColor.RED),
                List.of(gray("必要タグ・スケール・動きの上書きを、"), gray("まとめて解除します。"))));
        inventory.setItem(SLOT_BACK, icon(Material.ARROW,
                Component.text("マーカー画面へ戻る", NamedTextColor.YELLOW), List.of()));
    }

    private ItemStack valueIcon(Material material, String label, String currentValueOrNull, String hint) {
        boolean set = currentValueOrNull != null;
        return icon(material, set,
                Component.text(label, set ? NamedTextColor.GREEN : NamedTextColor.WHITE),
                List.of(
                        gray("現在: " + (set ? currentValueOrNull : "（上書き無し。creatures.yml本来の設定）")),
                        gray(hint),
                        gray("\"none\" と入力すると、この項目の上書きを解除します。")));
    }

    private ItemStack boolIcon(String label, Boolean current) {
        Material material = current == null ? Material.GRAY_DYE : (current ? Material.LIME_DYE : Material.RED_DYE);
        String currentText = current == null ? "（上書き無し。creatures.yml本来の設定）" : (current ? "true" : "false");
        return icon(material,
                Component.text(label, current == null ? NamedTextColor.WHITE
                        : (current ? NamedTextColor.GREEN : NamedTextColor.RED)),
                List.of(
                        gray("現在: " + currentText),
                        gray("クリックで切り替え（上書き無し → true → false → 上書き無し）")));
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
