package jp.mushitori.ui;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.CatchData;
import jp.mushitori.model.Category;
import jp.mushitori.service.DexService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 図鑑（閲覧・登録）GUIのクリック処理。 */
public final class GuiListener implements Listener {

    private final MushitoriPlugin plugin;

    public GuiListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof DexGui) {
            event.setCancelled(true);
            return;
        }
        if (holder instanceof DexRegisterGui gui) {
            if (!(event.getWhoClicked() instanceof Player player)) return;
            int topSize = event.getView().getTopInventory().getSize();
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < topSize && !DexRegisterGui.isOfferSlot(rawSlot)) {
                    event.setCancelled(true);
                    return;
                }
            }
            player.getScheduler().runDelayed(plugin, task -> validateRegisterOfferArea(player, gui), null, 1L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof DexRegisterGui gui) {
            onRegisterGuiClick(event, gui);
            return;
        }

        if (!(holder instanceof DexGui gui)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getInventory())) {
            return;
        }

        int slot = event.getRawSlot();
        switch (gui.type()) {
            case MAIN -> handleMain(player, slot);
            case CATEGORY -> handleCategory(player, gui, slot);
            case ACHIEVEMENTS -> {
                if (slot == 49) {
                    click(player);
                    DexGui.openMain(plugin, player);
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof DexRegisterGui gui)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        // 「登録するもの」に残っていたものは、登録せずそのまま持ち物へ返す
        // （登録はアイテムを消費しないので、これだけで元通りになる）
        Inventory top = gui.getInventory();
        for (int slot : DexRegisterGui.OFFER_SLOTS) {
            ItemStack item = top.getItem(slot);
            if (item == null || item.getType() == Material.AIR) continue;
            top.setItem(slot, null);
            giveOrDrop(player, item);
        }
    }

    // ==================== 図鑑登録画面（DexRegisterGui） ====================

    private void onRegisterGuiClick(InventoryClickEvent event, DexRegisterGui gui) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getTopInventory());

        if (clickedTop && !DexRegisterGui.isOfferSlot(event.getSlot())) {
            event.setCancelled(true);
            int slot = event.getSlot();
            if (slot == DexRegisterGui.SLOT_CONFIRM) {
                handleRegisterConfirm(player, gui);
            } else if (slot == DexRegisterGui.SLOT_SELECT_ALL) {
                toggleSelectAll(player, gui);
            } else if (slot == DexRegisterGui.SLOT_CLOSE) {
                player.closeInventory();
            }
            return;
        }

        player.getScheduler().runDelayed(plugin, task -> validateRegisterOfferArea(player, gui), null, 1L);
    }

    private void validateRegisterOfferArea(Player player, DexRegisterGui gui) {
        Inventory inv = gui.getInventory();
        for (int slot : DexRegisterGui.OFFER_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) continue;
            if (plugin.catchService().read(item) == null) {
                inv.setItem(slot, null);
                giveOrDrop(player, item);
            }
        }
        gui.refreshControls(plugin, player);
    }

    private void handleRegisterConfirm(Player player, DexRegisterGui gui) {
        Inventory inv = gui.getInventory();
        List<ItemStack> offered = new ArrayList<>();
        for (int slot : DexRegisterGui.OFFER_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item != null && plugin.catchService().read(item) != null) {
                offered.add(item);
            }
        }
        if (offered.isEmpty()) {
            player.sendActionBar(Component.text(
                    "「登録するもの」が空です。", NamedTextColor.RED));
            return;
        }

        if (!gui.isPendingConfirm() && hasUncaughtBySelf(player, offered)) {
            gui.setPendingConfirm(true);
            gui.refreshControls(plugin, player);
            player.sendActionBar(Component.text(
                    "自分で捕まえていないいきものが含まれています。もう一度押すと、そのまま登録します。",
                    NamedTextColor.YELLOW));
            return;
        }

        List<DexService.RegisterResult> allResults = new ArrayList<>();
        int skipped = 0;
        List<DexService.Achievement> unlocked = new ArrayList<>();
        for (int slot : DexRegisterGui.OFFER_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) continue;
            var summary = plugin.dexService().registerOne(player, item);
            allResults.addAll(summary.results());
            skipped += summary.skipped();
            unlocked.addAll(summary.unlocked());
        }
        plugin.dexService().announce(player, new DexService.RegisterSummary(allResults, skipped, unlocked));

        gui.setPendingConfirm(false);
        gui.refreshControls(plugin, player);
    }

    private void toggleSelectAll(Player player, DexRegisterGui gui) {
        Inventory inv = gui.getInventory();
        if (gui.isSelectedAll()) {
            // 解除：登録するものの中身を持ち物へ返す（入りきらない分は、その枠にそのまま残す）
            for (int slot : DexRegisterGui.OFFER_SLOTS) {
                ItemStack item = inv.getItem(slot);
                if (item == null || item.getType() == Material.AIR) continue;
                var leftover = player.getInventory().addItem(item);
                inv.setItem(slot, leftover.isEmpty() ? null : leftover.values().iterator().next());
            }
            gui.setSelectedAll(false);
        } else {
            // 選択：持ち物の登録できるアイテムを、空いている「登録するもの」の枠へ移す（最大36枠ぶん）
            var pInv = player.getInventory();
            ItemStack[] contents = pInv.getStorageContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack item = pInv.getItem(i);
                if (plugin.catchService().read(item) == null) continue;
                int emptySlot = findEmptyOfferSlot(inv);
                if (emptySlot < 0) break; // 枠が埋まった：残りは持ち物のまま
                inv.setItem(emptySlot, item);
                pInv.setItem(i, null);
            }
            gui.setSelectedAll(true);
        }
        gui.refreshControls(plugin, player);
    }

    private int findEmptyOfferSlot(Inventory inv) {
        for (int slot : DexRegisterGui.OFFER_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType() == Material.AIR) return slot;
        }
        return -1;
    }

    private boolean hasUncaughtBySelf(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            CatchData data = plugin.catchService().read(item);
            if (data == null) continue;
            if (!data.catcherUuid().equals(player.getUniqueId())) return true;
        }
        return false;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    // ==================== 図鑑の閲覧画面（DexGui） ====================

    private void handleMain(Player player, int slot) {
        Category[] categories = Category.values();
        if (slot >= 10 && slot < 10 + categories.length) {
            click(player);
            DexGui.openCategory(plugin, player, categories[slot - 10], 0);
            return;
        }
        switch (slot) {
            case 14 -> {
                click(player);
                DexRegisterGui.open(plugin, player);
            }
            case 15 -> {
                click(player);
                DexGui.openAchievements(plugin, player);
            }
            case 16 -> {
                click(player);
                SavingsGui.open(plugin, player);
            }
            case 26 -> player.closeInventory();
            default -> {
            }
        }
    }

    private void handleCategory(Player player, DexGui gui, int slot) {
        Category category = gui.category();
        if (category == null) return;
        switch (slot) {
            case 45 -> {
                click(player);
                DexGui.openCategory(plugin, player, category, gui.page() - 1);
            }
            case 49 -> {
                click(player);
                DexGui.openMain(plugin, player);
            }
            case 53 -> {
                click(player);
                DexGui.openCategory(plugin, player, category, gui.page() + 1);
            }
            default -> {
            }
        }
    }

    private void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }
}
