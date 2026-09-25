package jp.mushitori.service;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.EnumSet;

/**
 * 近くのプレイヤーから、物理的に逃げていく動き（{@code behavior.flee-from-players}）。
 *
 * <p>バニラのうさぎ・猫のように、瞬間移動したり消えたりするのではなく、その場から
 * 走って離れていく。Paperの Mob Goals API（{@link com.destroystokyo.paper.entity.ai.MobGoals}）
 * を使い、個体ごとにこのGoalを追加する。</p>
 *
 * <p><b>速度の与え方を2通りに分けている理由</b>：地上を歩くMobは
 * {@code Pathfinder#moveTo(Location, double)} の速度指定で問題なく制御できますが、
 * 飛ぶ生物（{@link Bee}）・泳ぐ生物（{@link Fish}系）は、バニラ側の仕様として
 * 移動速度の指定（Attribute.MOVEMENT_SPEEDはもちろん、飛行専用のAttribute.FLYING_SPEEDすら）
 * が反映されないことが公式のバグ報告でも確認されています（例: MC-172801
 * 「Bee's do not use the flying speed attribute」）。そのため、これらの種別に限っては、
 * Pathfinderに任せず、毎tick直接 {@link org.bukkit.entity.Entity#setVelocity(Vector)} で
 * 速度を与える方式に切り替えることで、確実に速度を反映させています。</p>
 */
public final class FleeFromPlayersGoal implements Goal<Mob> {

    private static final GoalKey<Mob> KEY = GoalKey.of(Mob.class,
            new NamespacedKey("mushitori", "flee_from_players"));
    private static final int RECALC_INTERVAL_TICKS = 10;
    /** velocity方式のときの、speed=1.0が意味するブロック/tickの基準値。 */
    private static final double VELOCITY_BASE_SPEED = 0.22;

    private final Mob mob;
    private final double radius;
    private final double speed;
    private final boolean useVelocity;
    private int cooldown = 0;

    public FleeFromPlayersGoal(Mob mob, double radius, double speed) {
        this.mob = mob;
        this.radius = Math.max(0.5, radius);
        this.speed = speed;
        this.useVelocity = mob instanceof Bee || mob instanceof Fish;
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
        // 一本釣りで誘導中の個体は、釣り人から逃げてしまわないよう止める
        return mob.isValid() && !ApproachFishingService.isControlled(mob) && nearestPlayer() != null;
    }

    @Override
    public void start() {
        cooldown = 0;
    }

    @Override
    public void tick() {
        if (useVelocity) {
            // 飛ぶ・泳ぐ生物：速度の指定がバニラ側で反映されないため、毎tick直接velocityを与える
            Player player = nearestPlayer();
            if (player == null) return;
            Vector dir = fleeDirection(player);
            mob.setVelocity(dir.multiply(VELOCITY_BASE_SPEED * Math.max(0.05, speed)));
            return;
        }

        if (cooldown-- > 0) return;
        cooldown = RECALC_INTERVAL_TICKS;

        Player player = nearestPlayer();
        if (player == null) return;

        Location mobLoc = mob.getLocation();
        Vector dir = fleeDirection(player);
        double fleeDistance = Math.max(3.0, radius);
        Location target = mobLoc.clone().add(dir.getX() * fleeDistance, 0, dir.getZ() * fleeDistance);
        mob.getPathfinder().moveTo(target, speed);
    }

    /** プレイヤーと反対方向（水平のみ）への、正規化された向き。 */
    private Vector fleeDirection(Player player) {
        Location mobLoc = mob.getLocation();
        double dx = mobLoc.getX() - player.getLocation().getX();
        double dz = mobLoc.getZ() - player.getLocation().getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            return new Vector(1, 0, 0);
        }
        return new Vector(dx / len, 0, dz / len);
    }

    private Player nearestPlayer() {
        var world = mob.getWorld();
        if (world == null) return null;
        Location loc = mob.getLocation();
        double bestDistSq = radius * radius;
        Player nearest = null;
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            double d2 = p.getLocation().distanceSquared(loc);
            if (d2 < bestDistSq) {
                bestDistSq = d2;
                nearest = p;
            }
        }
        return nearest;
    }
}
