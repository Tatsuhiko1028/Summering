package jp.mushitori.listener;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.service.PurseService;
import jp.mushitori.ui.PurseGui;
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
 * 小銭入れ・財布（{@link PurseService}）の操作。{@link CageListener}と同じ考え方で、
 * クリック・ドラッグが実際に反映された直後に中身全体を検証する方式にしています。
 */
public final class PurseListener implements Listener {

    private final MushitoriPlugin plugin;

    public PurseListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!plugin.purseService().isPurse(player.getInventory().getItemInMainHand())) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
        }

        PurseGui.open(plugin, player);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PurseGui gui)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        scheduleValidate(player, gui);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof PurseGui gui)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        scheduleValidate(player, gui);
    }

    private void scheduleValidate(Player player, PurseGui gui) {
        player.getScheduler().runDelayed(plugin, task -> validateContents(player, gui), null, 1L);
    }

    private void validateContents(Player player, PurseGui gui) {
        Inventory inv = gui.getInventory();
        ItemStack sourceItem = currentPurseItem(player, gui);

        if (sourceItem == null) {
            // 編集中に、対象の財布アイテム自体が持ち物の別の場所へ動かされてしまった。
            // ここまでの内容は、直前のクリック・ドラッグのたびに、既にその財布アイテム
            // （今は別の場所にある）へ同期済みなので、ここで中身をもう一度持ち物へ
            // 返してしまうと、アイテムが分裂してしまう（財布の中にも、持ち物にも
            // 同じものが存在することになる）。何もせず、画面だけを閉じる。
            closeWithoutDumping(player, gui);
            return;
        }

        int capacity = plugin.purseService().capacityOf(sourceItem);
        PurseService.Filter filter = plugin.purseService().filterOf(sourceItem);
        if (filter == null) filter = PurseService.Filter.ANY_MONEY;

        boolean[] isUsable = new boolean[inv.getSize()];
        for (int slot : PurseGui.usableSlots(capacity)) {
            if (slot < isUsable.length) isUsable[slot] = true;
        }

        List<ItemStack> rejected = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);

            if (!isUsable[i]) {
                if (item == null || !PurseGui.isUnusableIcon(item)) {
                    if (item != null && item.getType() != Material.AIR) {
                        rejected.add(item);
                    }
                    inv.setItem(i, PurseGui.unusableIcon());
                }
                continue;
            }

            if (item == null || item.getType() == Material.AIR) continue;
            if (PurseGui.isUnusableIcon(item)) {
                inv.setItem(i, null);
                continue;
            }

            if (!allowed(item, filter)) {
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
            String message = filter == PurseService.Filter.COIN_ONLY
                    ? "小銭入れには、硬貨（小銭）だけを容量（" + capacity + "）までしか入れられません。"
                    : "財布には、お金だけを容量（" + capacity + "）までしか入れられません。";
            player.sendActionBar(Component.text(message, NamedTextColor.RED));
        }

        // クリック・ドラッグのたびに、その時点の中身を実際のアイテムへ同期する
        // （途中で財布アイテム自体を動かされても、直前までの中身が失われないようにするため）。
        List<ItemStack> contents = collectContents(inv, capacity);
        plugin.purseService().setContents(sourceItem, contents);
        player.getInventory().setItem(gui.sourceSlot(), sourceItem);
    }

    private List<ItemStack> collectContents(Inventory inv, int capacity) {
        List<ItemStack> contents = new ArrayList<>();
        for (int slot : PurseGui.usableSlots(capacity)) {
            if (slot >= inv.getSize()) continue;
            ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) contents.add(item);
        }
        return contents;
    }

    /**
     * 対象の財布アイテムを見失ったときの後始末：中身は既に（動かされた先の）実際の
     * アイテムへ同期済みのはずなので、持ち物へ重ねて渡すことはせず、画面だけを閉じる
     * （閉じたときの{@link #onClose}も、同じ理由で二重に渡さないようにしてある）。
     */
    private void closeWithoutDumping(Player player, PurseGui gui) {
        player.closeInventory();
    }

    private boolean allowed(ItemStack item, PurseService.Filter filter) {
        return filter == PurseService.Filter.COIN_ONLY
                ? plugin.money().isCoin(item)
                : plugin.money().isMoney(item);
    }

    private ItemStack currentPurseItem(Player player, PurseGui gui) {
        ItemStack slotItem = player.getInventory().getItem(gui.sourceSlot());
        if (slotItem != null && plugin.purseService().isPurse(slotItem)) return slotItem;
        return null;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof PurseGui gui)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR && !PurseGui.isUnusableIcon(item)) {
                contents.add(item);
            }
        }

        ItemStack slotItem = player.getInventory().getItem(gui.sourceSlot());
        if (slotItem != null && plugin.purseService().isPurse(slotItem)) {
            plugin.purseService().setContents(slotItem, contents);
            player.getInventory().setItem(gui.sourceSlot(), slotItem);
        }
        // 元の場所に財布が見つからない場合は、何もしない（分裂防止）。中身は、
        // 編集中のクリック・ドラッグのたびに、既に（動かされた先の）実際のアイテムへ
        // 同期済みのはずなので、ここで持ち物へ重ねて渡すと分裂の原因になる。
    }
}
