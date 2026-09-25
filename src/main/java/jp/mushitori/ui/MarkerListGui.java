package jp.mushitori.ui;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.SpawnMarker;
import jp.mushitori.model.WeightedCreature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 設置済みのマーカーを一覧表示するGUI。設定棒を持って右クリックすると開きます。
 *
 * <p>クリック操作：</p>
 * <ul>
 *   <li>左クリック：そのマーカーの設定画面（{@link MarkerGui}）を開く</li>
 *   <li>右クリック：そのマーカーの場所へテレポートする</li>
 *   <li>シフト＋右クリック：そのマーカーを、今自分がいる場所へ移動させる</li>
 * </ul>
 *
 * <p>1画面（最大45件）で収まらない場合は、先頭から45件だけ表示します
 * （試験実装のため、ページ送りは今のところありません）。</p>
 */
public final class MarkerListGui implements InventoryHolder {

    private static final int MAX_SHOWN = 45;

    private Inventory inventory;
    /** スロット → マーカーID。 */
    private final Map<Integer, Integer> slotToMarkerId = new LinkedHashMap<>();

    private MarkerListGui() {
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    @Nullable
    public Integer markerIdAt(int slot) {
        return slotToMarkerId.get(slot);
    }

    public static void open(MushitoriPlugin plugin, Player player) {
        List<SpawnMarker> markers = plugin.ambientSpawnService().allMarkers();
        MarkerListGui gui = new MarkerListGui();

        int size = Math.max(9, ((Math.min(markers.size(), MAX_SHOWN) + 8) / 9) * 9);
        gui.inventory = Bukkit.createInventory(gui, size,
                Component.text("マーカー一覧（" + markers.size() + "件）"));

        int slot = 0;
        for (SpawnMarker marker : markers) {
            if (slot >= MAX_SHOWN) break;
            gui.inventory.setItem(slot, icon(plugin, marker));
            gui.slotToMarkerId.put(slot, marker.id());
            slot++;
        }

        player.openInventory(gui.inventory);
    }

    /** GUIを開き直し、マーカーの並びを最新の状態に更新する（移動後の再描画用）。 */
    public static void refresh(MushitoriPlugin plugin, Player player) {
        open(plugin, player);
    }

    private static ItemStack icon(MushitoriPlugin plugin, SpawnMarker marker) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> {
            meta.displayName(Component.text("#" + marker.id() + " " + marker.displayName(), NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(gray(String.format("(%s, %.0f, %.0f, %.0f)",
                    marker.worldName(), marker.x(), marker.y(), marker.z())));
            if (marker.creatures().isEmpty()) {
                lore.add(gray("種類: 未設定"));
            } else {
                StringBuilder sb = new StringBuilder("種類: ");
                boolean first = true;
                for (WeightedCreature wc : marker.creatures()) {
                    if (!first) sb.append(", ");
                    var c = plugin.creatures().get(wc.creatureId());
                    sb.append(c != null ? c.name() : wc.creatureId());
                    first = false;
                }
                lore.add(gray(sb.toString()));
            }
            lore.add(gray("最大" + marker.maxCount() + "体"));
            lore.add(Component.empty());
            lore.add(gray("左クリック: 設定画面を開く"));
            lore.add(gray("右クリック: この場所へテレポート"));
            lore.add(gray("シフト＋右クリック: このマーカーを今の場所へ移動"));
            meta.lore(lore);
        });
        return item;
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    @Nullable
    public static Location locationOf(SpawnMarker marker) {
        World world = Bukkit.getWorld(marker.worldName());
        if (world == null) return null;
        return new Location(world, marker.x() + 0.5, marker.y() + 1.0, marker.z() + 0.5);
    }
}
