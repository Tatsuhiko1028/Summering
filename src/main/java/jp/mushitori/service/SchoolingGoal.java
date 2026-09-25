package jp.mushitori.service;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import jp.mushitori.Keys;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 近くにいる、同じ生物ID（{@code creature_id}。バニラのentity種別ではなく、プラグイン独自のIDが
 * 基準）の個体どうしで寄り集まる動き（{@code behavior.schooling}）。魚の群れをイメージしている。
 *
 * <p>バニラのentity型（例: SALMON）は複数の異なる生物IDで共有されることがあるため、
 * 「同じentity型」ではなく「同じ{@code creature_id}」を仲間の判定基準にしている。</p>
 */
public final class SchoolingGoal implements Goal<Mob> {

    private static final GoalKey<Mob> KEY = GoalKey.of(Mob.class, new NamespacedKey("mushitori", "schooling"));
    private static final int RECALC_INTERVAL_TICKS = 30;
    /** 仲間の重心にこれ以上近ければ、わざわざ動かない（常に密集しすぎるのを防ぐ）。 */
    private static final double COHESION_DISTANCE = 3.0;

    private final Mob mob;
    private final double radius;
    private final double speed;
    /** 自分自身のcreature_id（構築時に一度だけ読む。以降は変わらない前提）。 */
    private final String creatureId;
    private int cooldown = 0;

    public SchoolingGoal(Mob mob, double radius, double speed) {
        this.mob = mob;
        this.radius = Math.max(0.5, radius);
        this.speed = speed;
        this.creatureId = mob.getPersistentDataContainer().get(Keys.CREATURE_ID, PersistentDataType.STRING);
    }

    public static GoalKey<Mob> key() {
        return KEY;
    }

    @Override
    public GoalKey<Mob> getKey() {
        return KEY;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE);
    }

    @Override
    public boolean shouldActivate() {
        // 一本釣りで誘導中の個体は、群れに引っ張られないよう止める
        return mob.isValid() && creatureId != null && !ApproachFishingService.isControlled(mob);
    }

    @Override
    public void tick() {
        if (cooldown-- > 0) return;
        cooldown = RECALC_INTERVAL_TICKS;

        List<Location> mates = new ArrayList<>();
        for (Entity e : mob.getWorld().getNearbyEntities(mob.getLocation(), radius, radius, radius)) {
            if (e.getUniqueId().equals(mob.getUniqueId())) continue;
            if (creatureId.equals(e.getPersistentDataContainer().get(Keys.CREATURE_ID, PersistentDataType.STRING))) {
                mates.add(e.getLocation());
            }
        }
        if (mates.isEmpty()) return;

        double sx = 0, sy = 0, sz = 0;
        for (Location l : mates) {
            sx += l.getX();
            sy += l.getY();
            sz += l.getZ();
        }
        Location centroid = new Location(mob.getWorld(), sx / mates.size(), sy / mates.size(), sz / mates.size());

        if (centroid.distanceSquared(mob.getLocation()) < COHESION_DISTANCE * COHESION_DISTANCE) return;
        mob.getPathfinder().moveTo(centroid, speed);
    }
}
