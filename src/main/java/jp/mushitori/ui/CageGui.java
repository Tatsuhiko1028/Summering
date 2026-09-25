package jp.mushitori.ui;

import jp.mushitori.Keys;
import jp.mushitori.MushitoriPlugin;
import jp.mushitori.service.CageService;
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
 * 虫かごの中身を見る・入れ替えるためのチェストGUI。
 *
 * <p>「どのプレイヤーが」「メインハンドの何番目のスロットから」開いたかを覚えておき、
 * 閉じたタイミングでそのスロットのアイテムへ内容を書き戻します
 * （{@link jp.mushitori.listener.CageListener} が実処理を担当）。</p>
 *
 * <p>容量が9の倍数でない場合、GUI自体は9の倍数のスロット数になりますが、
 * 容量を超えた分のスロットには「使用不可」の目印を置き、実際に使えるのは
 * 容量ぶんだけであることが見た目でも分かるようにしています
 * （実際の容量の強制は {@link jp.mushitori.listener.CageListener} 側で行います）。</p>
 */
public final class CageGui implements InventoryHolder {

    private final UUID playerId;
    private final int sourceSlot;
    private final String cageId;
    private Inventory inventory;

    private CageGui(UUID playerId, int sourceSlot, String cageId) {
        this.playerId = playerId;
        this.sourceSlot = sourceSlot;
        this.cageId = cageId;
    }

    public UUID playerId() {
        return playerId;
    }

    public int sourceSlot() {
        return sourceSlot;
    }

    public String cageId() {
        return cageId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** プレイヤーのメインハンドにある虫かごを開く。 */
    public static void open(MushitoriPlugin plugin, Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        CageService cages = plugin.cageService();
        if (!cages.isCage(held)) return;

        String id = cages.idOf(held);
        int capacity = cages.capacityOf(held);
        int size = Math.max(9, ((capacity + 8) / 9) * 9); // 9の倍数に切り上げ（最低9）

        CageGui gui = new CageGui(player.getUniqueId(), player.getInventory().getHeldItemSlot(), id);
        gui.inventory = Bukkit.createInventory(gui, size, Component.text("虫かご"));

        // 容量を超えるぶんのスロットは、見た目でも分かるように使用不可アイコンで埋める
        for (int i = capacity; i < size; i++) {
            gui.inventory.setItem(i, unusableIcon());
        }

        List<ItemStack> contents = cages.contentsOf(held);
        for (int i = 0; i < contents.size() && i < capacity; i++) {
            gui.inventory.setItem(i, contents.get(i));
        }

        player.openInventory(gui.inventory);
    }

    /** 容量を超えるぶんの、使用不可を示すプレースホルダーアイテムを作る。 */
    public static ItemStack unusableIcon() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> {
            meta.displayName(Component.text("使用不可（容量オーバー）", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(Keys.CAGE_PLACEHOLDER, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    /** そのアイテムが、容量オーバー用のプレースホルダーかどうか。 */
    public static boolean isUnusableIcon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(Keys.CAGE_PLACEHOLDER, PersistentDataType.BYTE);
    }
}
