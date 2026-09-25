package jp.mushitori.listener;

import jp.mushitori.Keys;
import jp.mushitori.MushitoriPlugin;
import jp.mushitori.ui.DexGui;
import jp.mushitori.ui.DexRegisterGui;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * 図鑑アイテムを持って右クリックすると、図鑑（閲覧・一覧）を開きます。
 * 登録は、図鑑メイン画面の「図鑑に登録する」ボタンから登録画面
 * （{@link DexRegisterGui}）へ進んでください。
 *
 * <p>以前は右クリックで直接登録画面へ行く形にしていましたが、まず図鑑画面を
 * 開き、そこから登録を選ぶ流れに変更しました。</p>
 */
public final class GuideListener implements Listener {

    private final MushitoriPlugin plugin;

    public GuideListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (!isGuide(item)) return;

        event.setCancelled(true);
        DexGui.openMain(plugin, event.getPlayer());
    }

    private boolean isGuide(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                .has(Keys.GUIDE, PersistentDataType.BYTE)) {
            return true;
        }
        return plugin.acceptVanillaBook() && item.getType() == Material.BOOK;
    }
}
