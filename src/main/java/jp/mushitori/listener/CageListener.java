package jp.mushitori.listener;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.Category;
import jp.mushitori.model.Creature;
import jp.mushitori.ui.CageGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 虫かご（{@link jp.mushitori.service.CageService}）の操作。
 *
 * <p>虫かごを持って右クリックするとGUIが開き、エンダーチェストやシュルカーボックスと
 * 同じ感覚で虫のアイテムを出し入れできます。</p>
 *
 * <p>「虫以外は入れられない」「容量を超えられない」の強制は、クリック種別ごとに
 * 個別に判定・キャンセルするのではなく、<b>クリック・ドラッグが実際に反映された直後に
 * 中身全体を検証し、条件を満たさない分だけ取り除いてプレイヤーへ返す</b>方式にしています。
 * シフトクリック・数字キー入れ替え・ダブルクリック collect 等、クリック種別ごとに
 * 挙動が異なる部分を個別に想定し損ねて判定漏れが起きるのを避けるためです。</p>
 */
public final class CageListener implements Listener {

    private final MushitoriPlugin plugin;

    public CageListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!plugin.cageService().isCage(player.getInventory().getItemInMainHand())) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
        }

        CageGui.open(plugin, player);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CageGui gui)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        scheduleValidate(player, gui);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof CageGui gui)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        scheduleValidate(player, gui);
    }

    /** クリック・ドラッグの処理自体はバニラに任せ、1tick後に中身を検証して補正する。 */
    private void scheduleValidate(Player player, CageGui gui) {
        player.getScheduler().runDelayed(plugin, task -> validateContents(player, gui), null, 1L);
    }

    private void validateContents(Player player, CageGui gui) {
        Inventory inv = gui.getInventory();
        ItemStack sourceItem = currentCageItem(player, gui);

        if (sourceItem == null) {
            // 編集中に、対象の虫かご自体が持ち物の別の場所へ動かされてしまった。
            // ここまでの内容は、直前のクリック・ドラッグのたびに、既にその虫かごアイテム
            // （今は別の場所にある）へ同期済みなので、ここで中身をもう一度持ち物へ
            // 返してしまうと、アイテムが分裂してしまう。何もせず、画面だけを閉じる。
            closeWithoutDumping(player, gui);
            return;
        }

        int capacity = plugin.cageService().capacityOf(sourceItem);

        List<ItemStack> rejected = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);

            if (i >= capacity) {
                // 容量を超えたスロットは常に使用不可アイコンで固定する
                if (item == null || !CageGui.isUnusableIcon(item)) {
                    if (item != null && item.getType() != Material.AIR) {
                        rejected.add(item);
                    }
                    inv.setItem(i, CageGui.unusableIcon());
                }
                continue;
            }

            if (item == null || item.getType() == Material.AIR) continue;
            if (CageGui.isUnusableIcon(item)) {
                inv.setItem(i, null); // 容量内にプレースホルダーが紛れ込んだ場合の保険
                continue;
            }

            if (!isBug(item)) {
                rejected.add(item);
                inv.setItem(i, null);
            }
        }

        if (!rejected.isEmpty()) {
            for (ItemStack item : rejected) {
                var leftover = player.getInventory().addItem(item);
                for (ItemStack rest : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
            player.sendActionBar(Component.text(
                    "虫かごには、虫のアイテムを容量（" + capacity + "）までしか入れられません。",
                    NamedTextColor.RED));
        }

        // クリック・ドラッグのたびに、その時点の中身を実際のアイテムへ同期する
        // （途中で虫かご自体を動かされても、直前までの中身が失われないようにするため）。
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < capacity && i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) contents.add(item);
        }
        plugin.cageService().setContents(sourceItem, contents);
        player.getInventory().setItem(gui.sourceSlot(), sourceItem);
    }

    /**
     * 対象の虫かごを見失ったときの後始末：中身は既に（動かされた先の）実際の
     * アイテムへ同期済みのはずなので、持ち物へ重ねて渡すことはせず、画面だけを閉じる
     * （閉じたときの{@link #onClose}も、同じ理由で二重に渡さないようにしてある）。
     */
    private void closeWithoutDumping(Player player, CageGui gui) {
        player.closeInventory();
    }

    private ItemStack currentCageItem(Player player, CageGui gui) {
        ItemStack slotItem = player.getInventory().getItem(gui.sourceSlot());
        if (slotItem != null && plugin.cageService().isCage(slotItem)) return slotItem;
        return null;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CageGui gui)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR && !CageGui.isUnusableIcon(item)) {
                contents.add(item);
            }
        }

        ItemStack slotItem = player.getInventory().getItem(gui.sourceSlot());
        if (slotItem != null && plugin.cageService().isCage(slotItem)) {
            plugin.cageService().setContents(slotItem, contents);
            player.getInventory().setItem(gui.sourceSlot(), slotItem);
        }
        // 元の場所に虫かごが見つからない場合は、何もしない（分裂防止）。中身は、
        // 編集中のクリック・ドラッグのたびに、既に（動かされた先の）実際のアイテムへ
        // 同期済みのはずなので、ここで持ち物へ重ねて渡すと分裂の原因になる。
    }

    private boolean isBug(ItemStack item) {
        var data = plugin.catchService().read(item);
        if (data == null) return false;
        Creature creature = plugin.creatures().get(data.creatureId());
        return creature != null && creature.category() == Category.BUG;
    }
}
