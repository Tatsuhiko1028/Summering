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
 * 貯金箱画面。図鑑メイン画面の「所持金」（スロット16）をクリックすると開きます
 * （仮の導線です。あとで変わるかもしれません）。
 *
 * <p>「全部預ける」で、今持っているお金（紙幣・硬貨）を全て貯金に回せます。
 * 引き出しは、「引き出す金額」を段階ボタン（±1〜±10000）で自由に決めてから
 * 「この金額を引き出す」で確定するか、「全部引き出す」でまとめて引き出すかを
 * 選べます。実際の預入・引出処理は {@link jp.mushitori.listener.SavingsGuiListener} が
 * 行います。</p>
 */
public final class SavingsGui implements InventoryHolder {

    public static final int SLOT_INFO = 4;
    public static final int SLOT_DEPOSIT_ALL = 8;

    // 引き出す金額の段階ボタン（左クリックで+、右クリックで-）
    public static final int SLOT_STEP_1 = 19;
    public static final int SLOT_STEP_10 = 20;
    public static final int SLOT_STEP_100 = 21;
    public static final int SLOT_STEP_1000 = 22;
    public static final int SLOT_STEP_10000 = 23;
    public static final int[] STEP_SLOTS = {SLOT_STEP_1, SLOT_STEP_10, SLOT_STEP_100, SLOT_STEP_1000, SLOT_STEP_10000};
    public static final int[] STEP_AMOUNTS = {1, 10, 100, 1000, 10000};

    public static final int SLOT_WITHDRAW_RESET = 28;
    public static final int SLOT_WITHDRAW_MAX = 29;
    public static final int SLOT_WITHDRAW_CANDIDATE = 31;
    public static final int SLOT_WITHDRAW_ALL = 33;
    public static final int SLOT_CLOSE = 35;

    private Inventory inventory;
    /** 今から引き出そうとしている金額（確定するまでは、まだ何も動きません）。 */
    private int withdrawCandidate = 0;

    private SavingsGui() {
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public int withdrawCandidate() {
        return withdrawCandidate;
    }

    /** 貯金額の範囲内に丸めつつ、引き出す候補金額を変える。 */
    public void adjustCandidate(MushitoriPlugin plugin, Player player, int delta) {
        int saved = plugin.savingsService().get(player);
        withdrawCandidate = Math.max(0, Math.min(saved, withdrawCandidate + delta));
    }

    public void setCandidate(int amount) {
        withdrawCandidate = Math.max(0, amount);
    }

    public static void open(MushitoriPlugin plugin, Player player) {
        SavingsGui gui = new SavingsGui();
        gui.inventory = Bukkit.createInventory(gui, 36, Component.text("むしとり貯金箱"));
        gui.redraw(plugin, player);
        player.openInventory(gui.inventory);
    }

    public void redraw(MushitoriPlugin plugin, Player player) {
        int held = plugin.money().get(player);
        int saved = plugin.savingsService().get(player);
        withdrawCandidate = Math.max(0, Math.min(saved, withdrawCandidate));

        inventory.setItem(SLOT_INFO, icon(Material.CHEST,
                Component.text("むしとり貯金箱", NamedTextColor.GOLD),
                List.of(
                        gray("今持っているお金  " + plugin.money().format(held)),
                        gray("貯金額  " + plugin.money().format(saved)))));

        if (held > 0) {
            inventory.setItem(SLOT_DEPOSIT_ALL, icon(Material.LIME_DYE,
                    Component.text("全部預ける", NamedTextColor.GREEN),
                    List.of(gray(plugin.money().format(held) + " を貯金します。"))));
        } else {
            inventory.setItem(SLOT_DEPOSIT_ALL, icon(Material.GRAY_DYE,
                    Component.text("預けるお金がありません", NamedTextColor.GRAY), List.of()));
        }

        for (int i = 0; i < STEP_SLOTS.length; i++) {
            int amount = STEP_AMOUNTS[i];
            inventory.setItem(STEP_SLOTS[i], icon(Material.PAPER,
                    Component.text("±" + amount, NamedTextColor.AQUA),
                    List.of(gray("左クリック: +" + amount), gray("右クリック: -" + amount))));
        }

        inventory.setItem(SLOT_WITHDRAW_RESET, icon(Material.GRAY_DYE,
                Component.text("引き出す金額をリセット", NamedTextColor.GRAY), List.of()));
        inventory.setItem(SLOT_WITHDRAW_MAX, icon(Material.YELLOW_DYE,
                Component.text("引き出す金額を貯金額いっぱいに", NamedTextColor.GRAY), List.of()));

        if (withdrawCandidate > 0) {
            inventory.setItem(SLOT_WITHDRAW_CANDIDATE, icon(Material.LIME_DYE,
                    Component.text("この金額を引き出す", NamedTextColor.GREEN),
                    List.of(gray("引き出す金額  " + plugin.money().format(withdrawCandidate)))));
        } else {
            inventory.setItem(SLOT_WITHDRAW_CANDIDATE, icon(Material.GRAY_DYE,
                    Component.text("引き出す金額を選んでください", NamedTextColor.GRAY),
                    List.of(gray("段階ボタンで金額を決めてから押してください。"))));
        }

        if (saved > 0) {
            inventory.setItem(SLOT_WITHDRAW_ALL, icon(Material.YELLOW_DYE,
                    Component.text("全部引き出す", NamedTextColor.YELLOW),
                    List.of(gray(plugin.money().format(saved) + " を持ち物へ渡します。"))));
        } else {
            inventory.setItem(SLOT_WITHDRAW_ALL, icon(Material.GRAY_DYE,
                    Component.text("引き出せる貯金がありません", NamedTextColor.GRAY), List.of()));
        }

        inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER,
                Component.text("閉じる", NamedTextColor.RED), List.of()));
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

