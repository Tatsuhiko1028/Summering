package jp.mushitori.ui;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.Keys;
import jp.mushitori.model.Creature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * マーカーの生物枠に割り当てる生物を選ぶための一覧画面。図鑑と同じ考え方で
 * ページ送りに対応しており、生物の種類が増えても一覧から選べます
 * （それまでの「紙をクリックして名前が順々に変わっていく」方式の代わり）。
 */
public final class CreaturePickerGui implements InventoryHolder {

    public static final int PER_PAGE = 45;
    public static final int SLOT_PREV = 45;
    public static final int SLOT_CLEAR = 48;
    public static final int SLOT_CANCEL = 49;
    public static final int SLOT_NEXT = 53;

    private final int markerId;
    private final int slotIndex;
    private final int page;
    private Inventory inventory;

    private CreaturePickerGui(int markerId, int slotIndex, int page) {
        this.markerId = markerId;
        this.slotIndex = slotIndex;
        this.page = page;
    }

    public int markerId() {
        return markerId;
    }

    public int slotIndex() {
        return slotIndex;
    }

    public int page() {
        return page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /**
     * @param markerId  割り当て先のマーカーID
     * @param slotIndex 割り当て先の生物枠（{@link MarkerGui#CREATURE_SLOTS}の何番目か）
     * @param page      表示するページ（0始まり。範囲外なら自動的に丸める）
     */
    public static void open(MushitoriPlugin plugin, Player player, int markerId, int slotIndex, int page) {
        List<Creature> list = new ArrayList<>(plugin.creatures().all());
        list.sort(Comparator.comparingInt(Creature::order).thenComparing(Creature::id));

        int maxPage = Math.max(0, (list.size() - 1) / PER_PAGE);
        int safePage = Math.max(0, Math.min(page, maxPage));

        CreaturePickerGui gui = new CreaturePickerGui(markerId, slotIndex, safePage);
        gui.inventory = Bukkit.createInventory(gui, 54,
                Component.text("生物を選択（マーカー #" + markerId + " ・枠 " + (slotIndex + 1) + "）"));

        for (int i = 0; i < PER_PAGE; i++) {
            int index = safePage * PER_PAGE + i;
            if (index >= list.size()) break;
            gui.inventory.setItem(i, creatureIcon(list.get(index)));
        }

        if (safePage > 0) {
            gui.inventory.setItem(SLOT_PREV, navIcon(Material.ARROW, "前のページ"));
        }
        gui.inventory.setItem(SLOT_CLEAR, navIcon(Material.BARRIER, "この枠を空にする"));
        gui.inventory.setItem(SLOT_CANCEL, navIcon(Material.ARROW, "キャンセル（マーカー画面へ戻る）"));
        if (safePage < maxPage) {
            gui.inventory.setItem(SLOT_NEXT, navIcon(Material.ARROW, "次のページ"));
        }

        player.openInventory(gui.inventory);
    }

    private static ItemStack creatureIcon(Creature c) {
        ItemStack item = new ItemStack(c.material());
        item.editMeta(meta -> {
            meta.displayName(Component.text(c.name(), NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("ID: " + c.id(), NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text(c.category().displayName(), NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("クリックで、この枠に割り当てます", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)));
            if (c.customModelData() != null) meta.setCustomModelData(c.customModelData());
            meta.getPersistentDataContainer().set(Keys.CREATURE_ID, PersistentDataType.STRING, c.id());
        });
        return item;
    }

    /** クリックされたアイテムから、割り当てる生物IDを読む（一覧のアイテムでなければ null）。 */
    public static String creatureIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(Keys.CREATURE_ID, PersistentDataType.STRING);
    }

    private static ItemStack navIcon(Material material, String name) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Component.text(name, NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false)));
        return item;
    }
}
