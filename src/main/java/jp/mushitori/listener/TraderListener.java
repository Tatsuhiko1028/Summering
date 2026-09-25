package jp.mushitori.listener;

import jp.mushitori.Keys;
import jp.mushitori.MushitoriPlugin;
import jp.mushitori.service.PurseService;
import jp.mushitori.ui.TraderGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * トレーダーNPC（{@link jp.mushitori.service.TraderService}）の操作。
 *
 * <p>右クリックで売却画面（{@link TraderGui}）を開きます。「渡すもの」エリアへ
 * ドラッグしたいきものアイテムが売却対象になり、「確定」を押すと実際に売れます。
 * 画面を開いている間、自分の持ち物にあるいきものアイテムには「売値」の行が
 * 一時的に追加されます（画面を閉じると元に戻します）。</p>
 *
 * <p>図鑑に未登録のいきものを売ろうとした場合、確定ボタンを2回押す必要が
 * あります（1回目で警告表示に切り替わり、2回目で実際に売れます）。</p>
 */
public final class TraderListener implements Listener {

    private final MushitoriPlugin plugin;

    public TraderListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!plugin.traderService().isTrader(event.getRightClicked())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        applyPricePreviews(player);
        TraderGui.open(plugin, player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TraderGui gui)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getTopInventory());

        if (clickedTop && !TraderGui.isOfferSlot(event.getSlot())) {
            event.setCancelled(true);
            int slot = event.getSlot();
            if (slot == TraderGui.SLOT_CONFIRM) {
                handleConfirm(player, gui);
            } else if (slot == TraderGui.SLOT_SELECT_ALL) {
                toggleSelectAll(player, gui);
            } else if (slot == TraderGui.SLOT_CLOSE) {
                player.closeInventory();
            }
            // それ以外（案内表示・装飾ガラス）はクリックしても何も起きない
            return;
        }

        // 「渡すもの」エリアへの出し入れ、または自分の持ち物側のクリックはバニラに任せ、
        // 1tick後に中身を検証する（誤って売れないものが紛れ込んだ場合に押し戻すため）
        player.getScheduler().runDelayed(plugin, task -> validateOfferArea(player, gui), null, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof TraderGui gui)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize && !TraderGui.isOfferSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
        player.getScheduler().runDelayed(plugin, task -> validateOfferArea(player, gui), null, 1L);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TraderGui gui)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        // 「渡すもの」に残っていたものは、プレビューを剥がしてから持ち物へ返す
        Inventory top = gui.getInventory();
        for (int slot : TraderGui.OFFER_SLOTS) {
            ItemStack item = top.getItem(slot);
            if (item == null || item.getType() == Material.AIR) continue;
            top.setItem(slot, null);
            ItemStack stripped = stripPricePreview(item);
            giveOrDrop(player, stripped);
        }

        revertPricePreviews(player);
    }

    // ==================== 「渡すもの」エリアの検証・確定 ====================

    private void validateOfferArea(Player player, TraderGui gui) {
        Inventory inv = gui.getInventory();
        for (int slot : TraderGui.OFFER_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) continue;
            if (plugin.catchService().priceOf(item) <= 0) {
                inv.setItem(slot, null);
                giveOrDrop(player, item);
            }
        }
        gui.refreshControls(plugin);
    }

    private void handleConfirm(Player player, TraderGui gui) {
        Inventory inv = gui.getInventory();
        List<ItemStack> offered = new ArrayList<>();
        int total = 0;
        for (int slot : TraderGui.OFFER_SLOTS) {
            ItemStack item = inv.getItem(slot);
            int price = plugin.catchService().priceOf(item);
            if (price <= 0) continue;
            offered.add(item);
            total += price * Math.max(1, item.getAmount());
        }
        if (total <= 0) {
            player.sendActionBar(Component.text("「渡すもの」が空です。", NamedTextColor.RED));
            return;
        }

        if (!gui.isPendingConfirm() && hasUnregistered(offered)) {
            gui.setPendingConfirm(true);
            gui.refreshControls(plugin);
            player.sendActionBar(Component.text(
                    "図鑑未登録のいきものが含まれています。もう一度押すと、そのまま売ります。", NamedTextColor.YELLOW));
            return;
        }

        for (int slot : TraderGui.OFFER_SLOTS) {
            inv.setItem(slot, null);
        }
        depositMoney(player, total);
        gui.setPendingConfirm(false);
        player.sendMessage(Component.text(
                "いきものを売って " + plugin.money().format(total) + " 手に入りました。", NamedTextColor.GREEN));
        gui.refreshControls(plugin);
    }

    private void toggleSelectAll(Player player, TraderGui gui) {
        Inventory inv = gui.getInventory();
        if (gui.isSelectedAll()) {
            // 解除：渡すものの中身を持ち物へ返す（入りきらない分は、その枠にそのまま残す）
            for (int slot : TraderGui.OFFER_SLOTS) {
                ItemStack item = inv.getItem(slot);
                if (item == null || item.getType() == Material.AIR) continue;
                var leftover = player.getInventory().addItem(item);
                inv.setItem(slot, leftover.isEmpty() ? null : leftover.values().iterator().next());
            }
            gui.setSelectedAll(false);
        } else {
            // 選択：持ち物の売れるアイテムを、空いている「渡すもの」の枠へ移す（最大36枠ぶん）
            PlayerInventory pInv = player.getInventory();
            ItemStack[] contents = pInv.getStorageContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack item = pInv.getItem(i);
                if (plugin.catchService().priceOf(item) <= 0) continue;
                int emptySlot = findEmptyOfferSlot(inv);
                if (emptySlot < 0) break; // 枠が埋まった：残りは持ち物のまま
                inv.setItem(emptySlot, item);
                pInv.setItem(i, null);
            }
            gui.setSelectedAll(true);
        }
        gui.refreshControls(plugin);
    }

    private int findEmptyOfferSlot(Inventory inv) {
        for (int slot : TraderGui.OFFER_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) return slot;
        }
        return -1;
    }

    /**
     * 引数の中に、「まだ登録されていない」いきものアイテムが含まれるか。
     * 種として図鑑に載っているかどうか（{@code dex.has}）ではなく、
     * そのアイテム自身に登録スタンプ（{@link CatchData#isRegistered()}）が
     * 付いているかどうかで判定する。同じ種を以前に別の個体で登録済みでも、
     * この個体自身が未登録なら「未登録」として扱う。
     */
    /**
     * 売上（total）を、持ち物にある財布・小銭入れへ自動的に入金する。
     * 財布（お金なら何でも入る）を優先して探し、無ければ小銭入れ（硬貨のみ）を探す。
     * 財布・小銭入れを持っていない、または入りきらなかった・条件に合わなかった分は、
     * これまで通り直接持ち物へ渡す（入らなければ足元にドロップ）。
     */
    private void depositMoney(Player player, int total) {
        List<ItemStack> denomination = plugin.money().denominate(total);
        if (denomination.isEmpty()) return;

        int slot = findPurseSlot(player, PurseService.Filter.ANY_MONEY);
        if (slot < 0) slot = findPurseSlot(player, PurseService.Filter.COIN_ONLY);

        if (slot < 0) {
            for (ItemStack money : denomination) {
                giveOrDrop(player, money);
            }
            return;
        }

        ItemStack purseItem = player.getInventory().getItem(slot);
        var filter = plugin.purseService().filterOf(purseItem);
        int capacity = plugin.purseService().capacityOf(purseItem);
        List<ItemStack> existing = new ArrayList<>(plugin.purseService().contentsOf(purseItem));

        for (ItemStack money : denomination) {
            boolean matches = filter != PurseService.Filter.COIN_ONLY
                    || plugin.money().isCoin(money);
            if (matches && existing.size() < capacity) {
                existing.add(money);
            } else {
                giveOrDrop(player, money);
            }
        }
        plugin.purseService().setContents(purseItem, existing);
        player.getInventory().setItem(slot, purseItem);
    }

    private int findPurseSlot(Player player, PurseService.Filter filter) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (plugin.purseService().isPurse(item) && plugin.purseService().filterOf(item) == filter) return i;
        }
        return -1;
    }

    private boolean hasUnregistered(List<ItemStack> items) {
        for (ItemStack item : items) {
            var data = plugin.catchService().read(item);
            if (data == null) continue;
            if (!data.isRegistered()) return true;
        }
        return false;
    }

    // ==================== 持ち物側の「売値」プレビュー ====================

    /** 自分の持ち物にある、売れるいきものアイテムに、一時的に売値の行を追加する。 */
    private void applyPricePreviews(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = inv.getItem(i);
            int price = plugin.catchService().priceOf(item);
            if (price <= 0) continue;
            if (item.getItemMeta().getPersistentDataContainer().has(Keys.TRADE_PRICE_PREVIEW, PersistentDataType.BYTE)) {
                continue; // 既に付いている（保険）
            }
            item.editMeta(meta -> {
                List<Component> lore = new ArrayList<>();
                if (meta.hasLore() && meta.lore() != null) lore.addAll(meta.lore());
                lore.add(Component.empty());
                lore.add(Component.text("売値  " + plugin.money().format(price)
                        + (item.getAmount() > 1 ? "（1個あたり）" : ""), NamedTextColor.GOLD));
                meta.lore(lore);
                meta.getPersistentDataContainer().set(Keys.TRADE_PRICE_PREVIEW, PersistentDataType.BYTE, (byte) 1);
            });
            inv.setItem(i, item);
        }
    }

    /** 持ち物全体を見直し、売値プレビューが付いているものを元に戻す。 */
    private void revertPricePreviews(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = inv.getItem(i);
            ItemStack stripped = stripPricePreview(item);
            if (stripped != item) {
                inv.setItem(i, stripped);
            }
        }
    }

    /** そのアイテムに売値プレビューが付いていれば、剥がしたコピーを返す（付いていなければそのまま）。 */
    private ItemStack stripPricePreview(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return item;
        if (!item.getItemMeta().getPersistentDataContainer().has(Keys.TRADE_PRICE_PREVIEW, PersistentDataType.BYTE)) {
            return item;
        }
        ItemStack copy = item.clone();
        copy.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            if (meta.hasLore() && meta.lore() != null) lore.addAll(meta.lore());
            if (lore.size() >= 2) {
                lore.remove(lore.size() - 1);
                lore.remove(lore.size() - 1);
            }
            meta.lore(lore.isEmpty() ? null : lore);
            meta.getPersistentDataContainer().remove(Keys.TRADE_PRICE_PREVIEW);
        });
        return copy;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }
}
