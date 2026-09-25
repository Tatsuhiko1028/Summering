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
 * 図鑑への登録画面。トレーダーの売却画面と同じ考え方で、9列×6段（54マス）の
 * うち中央4段ぶん（9列×4段＝36マス）が「登録するもの」エリア
 * （{@link #OFFER_SLOTS}）です。ここへ、自分の持ち物から未登録のいきものアイテムを
 * ドラッグ＆ドロップで入れると、それが登録対象になります。「確定」ボタンを押すまでは、
 * 何も登録されません。
 *
 * <p>「全て選択」を押すと、持ち物にある登録できるいきものアイテムを、空いている
 * 「登録するもの」の枠へまとめて移動します（最大36枠ぶん）。もう一度押すと
 * 「全て解除」に変わり、「登録するもの」の中身を持ち物へ戻します。</p>
 *
 * <p>登録は（売却と違って）アイテムを消費しないため、「登録するもの」に置いたまま
 * 画面を閉じても、そのアイテムは登録されずそのまま持ち物へ戻るだけです。</p>
 */
public final class DexRegisterGui implements InventoryHolder {

    public static final int SLOT_INFO = 4;
    public static final int SLOT_SELECT_ALL = 47;
    public static final int SLOT_CONFIRM = 49;
    public static final int SLOT_CLOSE = 51;

    /** 「登録するもの」エリア（アイテムを置ける枠。9列×4段＝36マス）。 */
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
    /** 自分で捕まえていないいきものが含まれる警告を経て、もう一押しで確定される状態かどうか。 */
    private boolean pendingConfirm;
    /** 「全て選択」を押して、登録するものエリアが選択済み状態になっているかどうか（ボタンの表示切り替え用）。 */
    private boolean selectedAll;

    private DexRegisterGui() {
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

    public static DexRegisterGui open(MushitoriPlugin plugin, Player player) {
        DexRegisterGui gui = new DexRegisterGui();
        gui.inventory = Bukkit.createInventory(gui, 54, Component.text("むしとり図鑑への登録"));
        for (int slot = 0; slot < 54; slot++) {
            if (!isOfferSlot(slot) && slot != SLOT_INFO && slot != SLOT_SELECT_ALL
                    && slot != SLOT_CONFIRM && slot != SLOT_CLOSE) {
                gui.inventory.setItem(slot, glass());
            }
        }
        gui.refreshControls(plugin, player);
        player.openInventory(gui.inventory);
        return gui;
    }

    /** 「登録するもの」エリアの中身から件数を求め、案内・確定ボタンの表示を更新する。 */
    public void refreshControls(MushitoriPlugin plugin, Player player) {
        int count = 0;
        for (int slot : OFFER_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (plugin.catchService().read(item) != null) {
                count += Math.max(1, item.getAmount());
            }
        }

        inventory.setItem(SLOT_INFO, icon(Material.WRITABLE_BOOK,
                Component.text("むしとり図鑑への登録", NamedTextColor.GOLD),
                List.of(gray("「登録するもの」の枠に、登録したい"),
                        gray("いきものを置いてください（持ち物からドラッグ）。"),
                        gray("未登録の持ち物: " + DexGui.countCarrying(plugin, player) + " 個"))));

        if (count > 0) {
            inventory.setItem(SLOT_CONFIRM, icon(pendingConfirm ? Material.RED_WOOL : Material.LIME_DYE,
                    pendingConfirm
                            ? Component.text("本当に登録しますか？（自分の捕獲でないものあり）", NamedTextColor.RED)
                            : Component.text("確定：" + count + "個を登録", NamedTextColor.GREEN),
                    pendingConfirm
                            ? List.of(gray("自分で捕まえていないいきものが含まれています。"),
                                    gray("もう一度クリックすると、そのまま登録します。"))
                            : List.of(gray("クリックで、「登録するもの」を登録します。"))));
        } else {
            inventory.setItem(SLOT_CONFIRM, icon(Material.GRAY_DYE,
                    Component.text("「登録するもの」が空です", NamedTextColor.GRAY),
                    List.of(gray("登録したいいきものを枠に置いてください。"))));
        }

        inventory.setItem(SLOT_SELECT_ALL, selectedAll
                ? icon(Material.RED_DYE,
                        Component.text("全て解除", NamedTextColor.RED),
                        List.of(gray("「登録するもの」の中身を、持ち物へ戻します。")))
                : icon(Material.CYAN_DYE,
                        Component.text("全て選択", NamedTextColor.AQUA),
                        List.of(gray("持ち物にある登録できるいきものを、"),
                                gray("空いている「登録するもの」の枠へまとめて置きます。"))));

        inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER,
                Component.text("閉じる", NamedTextColor.RED),
                List.of(gray("「登録するもの」に置いたアイテムは、"), gray("登録されずに持ち物へ戻ります。"))));
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
