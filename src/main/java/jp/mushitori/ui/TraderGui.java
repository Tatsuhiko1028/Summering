package jp.mushitori.ui;

import jp.mushitori.MushitoriPlugin;
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

import java.util.List;

/**
 * トレーダーNPCを右クリックすると開く、売却画面。
 *
 * <p>9列×6段（54マス）のうち、中央4段ぶん（9列×4段＝36マス）が「渡すもの」エリア
 * （{@link #OFFER_SLOTS}）です。ここへ、自分の持ち物からいきものアイテムを
 * ドラッグ＆ドロップで入れると、それが売却対象になります（実際の売却は
 * 「確定」ボタンを押すまで行われません）。それ以外の枠は、入れられないことが
 * ひと目で分かるよう板ガラスで塞いでいます。</p>
 *
 * <p>「全て選択」を押すと、持ち物にある売れるいきものアイテムを、空いている
 * 「渡すもの」の枠へまとめて移動します（最大36枠ぶん）。もう一度押すと
 * 「全て解除」に変わり、「渡すもの」の中身を持ち物へ戻します。</p>
 *
 * <p>この画面を開いている間だけ、自分の持ち物（下段）にあるいきものアイテムにも
 * 「売値」の行が一時的に追加されます（画面を閉じると元に戻ります。
 * 実際の付け外しは {@link jp.mushitori.listener.TraderListener} が行います）。</p>
 */
public final class TraderGui implements InventoryHolder {

    public static final int SLOT_INFO = 4;
    public static final int SLOT_SELECT_ALL = 47;
    public static final int SLOT_CONFIRM = 49;
    public static final int SLOT_CLOSE = 51;

    /** 「渡すもの」エリア（アイテムを置ける枠。9列×4段＝36マス）。 */
    public static final int[] OFFER_SLOTS;

    static {
        OFFER_SLOTS = new int[36];
        int i = 0;
        for (int row = 1; row <= 4; row++) {
            for (int col = 0; col < 9; col++) {
                OFFER_SLOTS[i++] = row * 9 + col;
            }
        }
    }

    private Inventory inventory;
    /** レア度到達未登録の警告を経て、もう一押しで確定される状態かどうか。 */
    private boolean pendingConfirm;
    /** 「全て選択」を押して、渡すものエリアが選択済み状態になっているかどうか（ボタンの表示切り替え用）。 */
    private boolean selectedAll;

    private TraderGui() {
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public boolean isPendingConfirm() {
        return pendingConfirm;
    }

    public void setPendingConfirm(boolean pendingConfirm) {
        this.pendingConfirm = pendingConfirm;
    }

    public boolean isSelectedAll() {
        return selectedAll;
    }

    public void setSelectedAll(boolean selectedAll) {
        this.selectedAll = selectedAll;
    }

    public static boolean isOfferSlot(int slot) {
        for (int s : OFFER_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    public static TraderGui open(MushitoriPlugin plugin, Player player) {
        TraderGui gui = new TraderGui();
        gui.inventory = Bukkit.createInventory(gui, 54, Component.text("いきものトレーダー"));
        for (int slot = 0; slot < 54; slot++) {
            if (!isOfferSlot(slot) && slot != SLOT_INFO && slot != SLOT_SELECT_ALL
                    && slot != SLOT_CONFIRM && slot != SLOT_CLOSE) {
                gui.inventory.setItem(slot, glass());
            }
        }
        gui.refreshControls(plugin);
        player.openInventory(gui.inventory);
        return gui;
    }

    /** 「渡すもの」エリアの中身から合計額を求め、案内・確定ボタンの表示を更新する。 */
    public void refreshControls(MushitoriPlugin plugin) {
        int total = 0;
        for (int slot : OFFER_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            int price = plugin.catchService().priceOf(item);
            if (price > 0) total += price * Math.max(1, item.getAmount());
        }

        inventory.setItem(SLOT_INFO, icon(Material.PAPER,
                Component.text("いきものトレーダー", NamedTextColor.GOLD),
                List.of(gray("「渡すもの」の枠に、売りたいいきものを"),
                        gray("置いてください（持ち物からドラッグ）。"),
                        gray("下段の持ち物には、売値が一時的に表示されます。"))));

        if (total > 0) {
            inventory.setItem(SLOT_CONFIRM, icon(pendingConfirm ? Material.RED_WOOL : Material.LIME_DYE,
                    pendingConfirm
                            ? Component.text("本当に売りますか？（未登録あり）", NamedTextColor.RED)
                            : Component.text("確定：" + plugin.money().format(total) + "で売る", NamedTextColor.GREEN),
                    pendingConfirm
                            ? List.of(gray("図鑑に未登録のいきものが含まれています。"),
                                    gray("もう一度クリックすると、そのまま売ります。"))
                            : List.of(gray("クリックで、「渡すもの」を売ります。"))));
        } else {
            inventory.setItem(SLOT_CONFIRM, icon(Material.GRAY_DYE,
                    Component.text("「渡すもの」が空です", NamedTextColor.GRAY),
                    List.of(gray("売りたいいきものを枠に置いてください。"))));
        }

        inventory.setItem(SLOT_SELECT_ALL, selectedAll
                ? icon(Material.RED_DYE,
                        Component.text("全て解除", NamedTextColor.RED),
                        List.of(gray("「渡すもの」の中身を、持ち物へ戻します。")))
                : icon(Material.CYAN_DYE,
                        Component.text("全て選択", NamedTextColor.AQUA),
                        List.of(gray("持ち物にある売れるいきものを、"),
                                gray("空いている「渡すもの」の枠へまとめて置きます。"))));

        inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER,
                Component.text("閉じる", NamedTextColor.RED),
                List.of(gray("「渡すもの」に置いたアイテムは、"), gray("持ち物へ戻ります。"))));
    }

    private static ItemStack glass() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        });
        return item;
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
