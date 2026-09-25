package jp.mushitori.ui;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.ApproachOverrides;
import jp.mushitori.model.Creature;
import jp.mushitori.model.SpawnMarker;
import jp.mushitori.model.WeightedCreature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * マーカー1つぶんの設定画面。設定棒で既存マーカーを右クリックすると開きます。
 * クリックのたびにその場で数値を書き換え、閉じたときにまとめて確定します
 * （削除は即時反映）。
 */
public final class MarkerGui implements InventoryHolder {

    /** 生物枠のスロット（最大 {@link SpawnMarker#MAX_SPECIES} 種類まで＝GUIの1行ぶん）。 */
    public static final int[] CREATURE_SLOTS = {9, 10, 11, 12, 13, 14, 15, 16, 17};
    public static final int SLOT_TRIGGER_RADIUS = 19;
    public static final int SLOT_DESPAWN_RADIUS = 20;
    public static final int SLOT_SPAWN_RADIUS = 21;
    public static final int SLOT_MAX_COUNT = 22;
    public static final int SLOT_SIMULTANEOUS_MAX = 18;
    public static final int SLOT_SPAWN_INTERVAL = 23;
    public static final int SLOT_CATCH_WINDOW = 24;
    public static final int SLOT_SPAWN_CHANCE = 25;
    public static final int SLOT_PRESET = 31;
    public static final int SLOT_RENAME = 30;
    public static final int SLOT_DELETE = 26;
    public static final int SLOT_RESET_MOBS = 27;
    private static final int SLOT_INFO = 4;

    private final MushitoriPlugin plugin;
    private final int markerId;
    private Inventory inventory;
    private SpawnMarker working;

    private MarkerGui(MushitoriPlugin plugin, int markerId, SpawnMarker working) {
        this.plugin = plugin;
        this.markerId = markerId;
        this.working = working;
    }

    public int markerId() {
        return markerId;
    }

    public SpawnMarker working() {
        return working;
    }

    public void setWorking(SpawnMarker updated) {
        this.working = updated;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static void open(MushitoriPlugin plugin, Player player, int markerId) {
        SpawnMarker marker = plugin.ambientSpawnService().marker(markerId);
        if (marker == null) {
            player.sendActionBar(Component.text("マーカー #" + markerId + " が見つかりません。", NamedTextColor.RED));
            return;
        }
        MarkerGui gui = new MarkerGui(plugin, markerId, marker);
        gui.inventory = Bukkit.createInventory(gui, 36, Component.text("マーカー #" + markerId + " の設定"));
        gui.redraw();
        player.openInventory(gui.inventory);
    }

    public void redraw() {
        for (int slot : new int[]{
                SLOT_INFO, SLOT_TRIGGER_RADIUS, SLOT_DESPAWN_RADIUS, SLOT_SPAWN_RADIUS,
                SLOT_MAX_COUNT, SLOT_SIMULTANEOUS_MAX, SLOT_SPAWN_INTERVAL, SLOT_CATCH_WINDOW, SLOT_SPAWN_CHANCE,
                SLOT_PRESET, SLOT_RENAME, SLOT_DELETE, SLOT_RESET_MOBS}) {
            inventory.setItem(slot, null);
        }
        for (int slot : CREATURE_SLOTS) {
            inventory.setItem(slot, null);
        }

        inventory.setItem(SLOT_INFO, icon(Material.PAPER,
                Component.text(working.displayName(), NamedTextColor.GOLD),
                List.of(gray(String.format("(%s, %.0f, %.0f, %.0f)",
                        working.worldName(), working.x(), working.y(), working.z())))));

        int totalWeight = working.creatures().stream().mapToInt(WeightedCreature::weight).sum();
        for (int i = 0; i < CREATURE_SLOTS.length; i++) {
            WeightedCreature wc = i < working.creatures().size() ? working.creatures().get(i) : null;
            inventory.setItem(CREATURE_SLOTS[i], creatureIcon(wc, i, totalWeight));
        }

        inventory.setItem(SLOT_TRIGGER_RADIUS, stepper(Material.REDSTONE, "感知範囲",
                working.triggerRadius(), "ブロック。プレイヤーがこの範囲に入ると判定が始まります"));
        inventory.setItem(SLOT_DESPAWN_RADIUS, stepper(Material.SOUL_SAND, "デスポーン範囲",
                working.despawnRadius(), "ブロック。誰もこの範囲にいなくなると消えます"));
        inventory.setItem(SLOT_SPAWN_RADIUS, stepper(Material.GRASS_BLOCK, "湧く範囲",
                working.spawnRadius(), "ブロック。中心からこの範囲内のランダムな位置に湧きます"));
        inventory.setItem(SLOT_MAX_COUNT, stepper(Material.CHEST, "最大数",
                working.maxCount(), "この場所から引き出せる上限数"));
        inventory.setItem(SLOT_SIMULTANEOUS_MAX, stepper(Material.ENDER_CHEST, "同時湧き最大数",
                working.simultaneousMax(), "一度の巡回でまとめて湧く最大数（枠それぞれ独立に確率判定）"));
        inventory.setItem(SLOT_SPAWN_INTERVAL, stepper(Material.CLOCK, "湧く頻度（秒）",
                working.spawnIntervalSeconds(), "この間隔で、湧かせるかどうかを確認します"));
        inventory.setItem(SLOT_CATCH_WINDOW, stepper(Material.HOPPER, "カウント窓（秒）",
                working.catchWindowSeconds(), "直近この秒数以内の捕獲を、最大数の判定に含めます"));
        inventory.setItem(SLOT_SPAWN_CHANCE, percentStepper(Material.GOLD_NUGGET, "湧く確率",
                working.spawnChance(), "湧く頻度のたびに、この確率を通過すれば湧きます。外れたら次の間隔でまた判定します"));

        inventory.setItem(SLOT_PRESET, icon(Material.BOOKSHELF,
                Component.text("プリセットを適用", NamedTextColor.LIGHT_PURPLE),
                List.of(gray("クリックで、生物一覧と最大数を"), gray("プリセットの内容で上書きします"))));

        inventory.setItem(SLOT_RENAME, icon(Material.NAME_TAG,
                Component.text("名前を変更: " + working.displayName(), NamedTextColor.YELLOW),
                List.of(gray("クリックで名前を入力する画面を開きます"))));

        inventory.setItem(SLOT_DELETE, icon(Material.BARRIER,
                Component.text("このマーカーを削除", NamedTextColor.RED),
                List.of(gray("クリックで即座に削除します"))));

        int currentMobs = plugin.ambientSpawnService().activeMobCount(markerId);
        inventory.setItem(SLOT_RESET_MOBS, icon(Material.MAGMA_CREAM,
                Component.text("湧いている個体をリセット", NamedTextColor.GOLD),
                List.of(gray("現在の生存数: " + currentMobs + "体"),
                        gray("クリックで、今いる個体を全てデスポーンさせます"),
                        gray("（マーカー自体は削除されません）"))));
    }

    private ItemStack creatureIcon(@Nullable WeightedCreature wc, int index, int totalWeight) {
        if (wc == null) {
            return icon(Material.GRAY_DYE,
                    Component.text("（空き枠 " + (index + 1) + "）", NamedTextColor.DARK_GRAY),
                    List.of(gray("左クリック: 生物の一覧を開いて割り当てる（ウェイト1で追加）")));
        }
        Creature c = plugin.creatures().get(wc.creatureId());
        String name = c != null ? c.name() : wc.creatureId();
        String percent = totalWeight > 0
                ? String.format("%.0f%%", wc.weight() * 100.0 / totalWeight)
                : "?";
        String tagInfo = wc.requiredTagsOverride().isEmpty()
                ? "無し（creatures.yml本来の設定）"
                : String.join(", ", wc.requiredTagsOverride());
        String overrideInfo = wc.override().isEmpty() ? "無し" : "あり（シフト+左クリックで確認）";
        String sizeEventInfo = wc.sizeDistributionTemplate() == null
                ? "無し（既定）" : wc.sizeDistributionTemplate();
        String approachInfo = wc.approachOverride().equals(ApproachOverrides.EMPTY)
                ? "無し" : "あり（シフト+左クリックで確認）";
        Material material = c != null ? c.material() : Material.RABBIT_HIDE;
        Integer customModelData = c != null ? c.customModelData() : null;
        return icon(material, customModelData,
                Component.text(name + "  (重み " + wc.weight() + " ≒ " + percent + ")", NamedTextColor.AQUA),
                List.of(gray("ID: " + wc.creatureId()),
                        gray("必要タグの上書き: " + tagInfo),
                        gray("スケール・動き・レア度の上書き: " + overrideInfo),
                        gray("サイズイベント: " + sizeEventInfo),
                        gray("アプローチ（寄ってくる釣り）の上書き: " + approachInfo),
                        gray("左クリック: 生物の一覧を開いて切り替える"),
                        gray("右クリック: ウェイト -1"),
                        gray("シフト+右クリック: ウェイト +1"),
                        gray("シフト+左クリック: 詳細設定を開く")));
    }

    private ItemStack stepper(Material material, String label, double value, String description) {
        String valueText = value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
        return icon(material,
                Component.text(label + "：" + valueText, NamedTextColor.YELLOW),
                List.of(gray(description),
                        gray("左クリック +1 ／ 右クリック -1"),
                        gray("シフト+クリックで ×10")));
    }

    private ItemStack stepper(Material material, String label, int value, String description) {
        return stepper(material, label, (double) value, description);
    }

    private ItemStack percentStepper(Material material, String label, double value01, String description) {
        return icon(material,
                Component.text(label + "：" + Math.round(value01 * 100) + "%", NamedTextColor.YELLOW),
                List.of(gray(description),
                        gray("左クリック +10% ／ 右クリック -10%"),
                        gray("シフト+クリックで ±1%")));
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        return icon(material, null, name, lore);
    }

    private static ItemStack icon(Material material, @Nullable Integer customModelData,
                                  Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            List<Component> styled = new ArrayList<>();
            for (Component line : lore) {
                styled.add(line.decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(styled);
            if (customModelData != null) meta.setCustomModelData(customModelData);
        });
        return item;
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }
}
