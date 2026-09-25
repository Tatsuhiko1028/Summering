package jp.mushitori.listener;

import jp.mushitori.MushitoriPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** ログイン時に図鑑を読み込み、ログアウト時に保存する。 */
public final class PlayerDataListener implements Listener {

    private final MushitoriPlugin plugin;

    public PlayerDataListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.dexManager().get(event.getPlayer().getUniqueId());
        // 図鑑側ですでに達成済みの実績を、Minecraft本体の進捗にも反映し直す
        // （データパックを後から入れた場合や、付与に失敗していた場合の取りこぼし対策）
        plugin.dexService().syncAdvancements(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.dexManager().unload(event.getPlayer().getUniqueId());
    }
}
