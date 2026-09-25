package jp.mushitori.listener;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.ui.SavingsGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** {@link SavingsGui}（貯金箱）のクリック処理。 */
public final class SavingsGuiListener implements Listener {

    private final MushitoriPlugin plugin;

    public SavingsGuiListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof SavingsGui) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SavingsGui gui)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        int slot = event.getSlot();
        boolean right = event.isRightClick();

        if (slot == SavingsGui.SLOT_DEPOSIT_ALL) {
            depositAll(player);
            gui.redraw(plugin, player);
            return;
        }
        if (slot == SavingsGui.SLOT_WITHDRAW_ALL) {
            withdrawAll(player);
            gui.redraw(plugin, player);
            return;
        }
        if (slot == SavingsGui.SLOT_WITHDRAW_CANDIDATE) {
            withdrawCandidate(player, gui);
            gui.redraw(plugin, player);
            return;
        }
        if (slot == SavingsGui.SLOT_WITHDRAW_RESET) {
            gui.setCandidate(0);
            gui.redraw(plugin, player);
            return;
        }
        if (slot == SavingsGui.SLOT_WITHDRAW_MAX) {
            gui.setCandidate(plugin.savingsService().get(player));
            gui.redraw(plugin, player);
            return;
        }
        if (slot == SavingsGui.SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        for (int i = 0; i < SavingsGui.STEP_SLOTS.length; i++) {
            if (slot == SavingsGui.STEP_SLOTS[i]) {
                int amount = SavingsGui.STEP_AMOUNTS[i];
                gui.adjustCandidate(plugin, player, right ? -amount : amount);
                gui.redraw(plugin, player);
                return;
            }
        }
    }

    private void depositAll(Player player) {
        int held = plugin.money().get(player);
        if (held <= 0) return;
        plugin.money().set(player, 0);
        plugin.savingsService().add(player, held);
        player.sendMessage(Component.text(
                plugin.money().format(held) + " を貯金しました。", NamedTextColor.GREEN));
    }

    private void withdrawAll(Player player) {
        int saved = plugin.savingsService().get(player);
        if (saved <= 0) return;
        plugin.savingsService().set(player, 0);
        plugin.money().add(player, saved);
        player.sendMessage(Component.text(
                plugin.money().format(saved) + " を貯金箱から引き出しました。", NamedTextColor.GREEN));
    }

    private void withdrawCandidate(Player player, SavingsGui gui) {
        int amount = gui.withdrawCandidate();
        int saved = plugin.savingsService().get(player);
        amount = Math.max(0, Math.min(saved, amount));
        if (amount <= 0) return;

        plugin.savingsService().add(player, -amount);
        plugin.money().add(player, amount);
        gui.setCandidate(0);
        player.sendMessage(Component.text(
                plugin.money().format(amount) + " を貯金箱から引き出しました。", NamedTextColor.GREEN));
    }
}
