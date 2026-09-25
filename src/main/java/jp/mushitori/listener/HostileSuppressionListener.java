package jp.mushitori.listener;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;

/**
 * {@code suppress-hostility} が有効な生物（例: シルバーフィッシュを見た目に使った虫）から、
 * バニラの敵対AI（プレイヤー等への攻撃対象の設定）を取り除く。
 *
 * <p>Paperの{@code MobGoals}APIで個別のバニラGoalを取り除く方式（{@code SpawnService}の
 * 蜜集め無効化と同様の考え方）も検討したが、種別ごとに異なる「対象を探す」Goalの名前を
 * 網羅する必要があり壊れやすいため、より汎用的な{@link EntityTargetEvent}のキャンセルで
 * 対応する（対象種別・バニラのバージョンに依存しない）。</p>
 */
public final class HostileSuppressionListener implements Listener {

    private final MushitoriPlugin plugin;

    public HostileSuppressionListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onTarget(EntityTargetEvent event) {
        Entity entity = event.getEntity();
        String creatureId = plugin.spawnService().creatureIdOf(entity);
        if (creatureId == null) return;
        Creature creature = plugin.creatures().get(creatureId);
        if (creature == null) return;
        if (!plugin.ambientSpawnService().effectiveSuppressHostility(entity, creature)) return;

        event.setCancelled(true);
        event.setTarget(null);
        if (entity instanceof Mob mob) {
            mob.setTarget(null);
        }
    }
}
