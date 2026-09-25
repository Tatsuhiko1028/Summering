package jp.mushitori.listener;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.ui.MarkerGui;
import jp.mushitori.ui.MarkerListGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【試験実装】マーカー設定棒の操作。
 *
 * <ul>
 *   <li><b>右クリック</b>：設置済みマーカーの一覧画面（{@link MarkerListGui}）を開く。
 *       一覧画面内での操作は {@link MarkerListGui} を参照</li>
 *   <li><b>スニーク＋右クリック</b>：狙った場所に「種類未設定」の新規マーカーを設置し、
 *       そのまま設定画面を開く（種類は設定画面の本棚、または生物枠クリックで選ぶ）</li>
 *   <li><b>左クリック</b>：視線の先（カーソルが合っている）マーカーの設定画面を開く。
 *       マーカーの削除は、この設定画面内の「削除」ボタンからのみ行えます
 *       （誤操作で消えてしまわないよう、ワンクリックでは削除できないようにしています）。</li>
 * </ul>
 *
 * <p>設定棒を持っている間、近くのマーカーがパーティクルで見えるようになります
 * （実際の表示は {@link jp.mushitori.service.AmbientSpawnService#tickVisualization()}）。</p>
 */
public final class MarkerWandListener implements Listener {

    private static final double TARGET_MAX_DISTANCE = 20.0;
    private static final double TARGET_MAX_OFF_RAY = 1.5;
    private static final long COOLDOWN_MS = 300;

    private final MushitoriPlugin plugin;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    public MarkerWandListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!plugin.ambientSpawnService().isWand(held)) return;

        Action action = event.getAction();
        boolean isRight = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean isLeft = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        if (!isRight && !isLeft) return;

        // ブロックに対する操作だけ、バニラ動作（ドアが開く・ブロックが壊れる等）を封じる
        if (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
        }

        if (onCooldown(player)) return;
        lastUse.put(player.getUniqueId(), System.currentTimeMillis());

        if (isRight && player.isSneaking()) {
            createMarkerAndEdit(player);
        } else if (isRight) {
            MarkerListGui.open(plugin, player);
        } else {
            openTargetedMarker(player);
        }
    }

    private boolean onCooldown(Player player) {
        Long last = lastUse.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last < COOLDOWN_MS;
    }

    /** 種類未設定のマーカーを設置し、そのまま設定画面を開く（種類は本棚のプリセット適用か、生物枠クリックで選ぶ）。 */
    private void createMarkerAndEdit(Player player) {
        Location target = targetLocation(player);
        var marker = plugin.ambientSpawnService().addEmptyMarker(target);
        player.sendActionBar(Component.text(
                "マーカー #" + marker.id() + " を設置しました。設定画面で種類を選んでください。", NamedTextColor.GREEN));
        MarkerGui.open(plugin, player, marker.id());
    }

    private Location targetLocation(Player player) {
        Block block = player.getTargetBlockExact(10);
        if (block != null) {
            return block.getLocation().add(0.5, 1.0, 0.5);
        }
        Location eye = player.getEyeLocation();
        return eye.clone().add(eye.getDirection().multiply(5));
    }

    /** 視線の先に一番近いマーカーの設定画面を開く。 */
    private void openTargetedMarker(Player player) {
        Location eye = player.getEyeLocation();
        var marker = plugin.ambientSpawnService()
                .markerAlongRay(eye, eye.getDirection(), TARGET_MAX_DISTANCE, TARGET_MAX_OFF_RAY);
        if (marker == null) {
            player.sendActionBar(Component.text("狙っている先にマーカーがありません。", NamedTextColor.RED));
            return;
        }
        MarkerGui.open(plugin, player, marker.id());
    }
}
