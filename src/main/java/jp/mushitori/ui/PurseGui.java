package jp.mushitori.ui;

import jp.mushitori.Keys;
import jp.mushitori.MushitoriPlugin;
import jp.mushitori.service.PurseService;
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

import java.util.List;
import java.util.UUID;

/**
 * 小銭入れ・財布の中身を見る・入れ替えるためのチェストGUI（{@link CageGui}と同じ考え方）。
 */
public final class PurseGui implements InventoryHolder {

    private final UUID playerId;
    private final int sourceSlot;
    private final String purseId;
    private Inventory inventory;

    private PurseGui(UUID playerId, int sourceSlot, String purseId) {
        this.playerId = playerId;
        this.sourceSlot = sourceSlot;
        this.purseId = purseId;
    }

    public UUID playerId() {
        return playerId;
    }

    public int sourceSlot() {
        return sourceSlot;
    }

    public String purseId() {
        return purseId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** プレイヤーのメインハンドにある小銭入れ・財布を開く。 */
    public static void open(MushitoriPlugin plugin, Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        PurseService purses = plugin.purseService();
        if (!purses.isPurse(held)) return;

        String id = purses.idOf(held);
        int capacity = purses.capacityOf(held);
        int size = guiSize(capacity);
        int[] usable = usableSlots(capacity);

        PurseGui gui = new PurseGui(player.getUniqueId(), player.getInventory().getHeldItemSlot(), id);
        PurseService.Purse purseDef = purses.purse(id);
        String title = purseDef != null ? purseDef.name() : "小銭入れ・財布";
        gui.inventory = Bukkit.createInventory(gui, size, Component.text(title));

        boolean[] isUsable = new boolean[size];
        for (int slot : usable) isUsable[slot] = true;
        for (int i = 0; i < size; i++) {
            if (!isUsable[i]) gui.inventory.setItem(i, unusableIcon());
        }

        List<ItemStack> contents = purses.contentsOf(held);
        for (int i = 0; i < contents.size() && i < usable.length; i++) {
            gui.inventory.setItem(usable[i], contents.get(i));
        }

        player.openInventory(gui.inventory);
    }

    /** そのcapacityに必要な、9の倍数に切り上げたインベントリの大きさ（最低9）。 */
    public static int guiSize(int capacity) {
        return rowsNeeded(capacity) * 9;
    }

    /**
     * 使用可能なスロットの一覧（capacity個ぶん）。左右対称になるよう、各行とも
     * 左端1マス・右端1マスを常に塞ぎ、中央7マスだけを使う（例：容量7なら1行、
     * 容量14なら2行＝7列×2段）。
     */
    public static int[] usableSlots(int capacity) {
        int rows = rowsNeeded(capacity);
        int[] slots = new int[capacity];
        int idx = 0;
        for (int row = 0; row < rows && idx < capacity; row++) {
            for (int col = LEFT_RIGHT_MARGIN; col < LEFT_RIGHT_MARGIN + USABLE_PER_ROW && idx < capacity; col++) {
                slots[idx++] = row * 9 + col;
            }
        }
        return slots;
    }

    /** 各行、左右1マスずつ塞いで使える数（9 - 1 - 1）。 */
    private static final int USABLE_PER_ROW = 7;
    private static final int LEFT_RIGHT_MARGIN = 1;

    private static int rowsNeeded(int capacity) {
        return Math.max(1, (capacity + USABLE_PER_ROW - 1) / USABLE_PER_ROW);
    }

    /** 容量を超えるぶんの、使用不可を示すプレースホルダーアイテムを作る。 */
    public static ItemStack unusableIcon() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> {
            meta.displayName(Component.text("使用不可（容量オーバー）", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(Keys.PURSE_PLACEHOLDER, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    /** そのアイテムが、容量オーバー用のプレースホルダーかどうか。 */
    public static boolean isUnusableIcon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(Keys.PURSE_PLACEHOLDER, PersistentDataType.BYTE);
    }
}
