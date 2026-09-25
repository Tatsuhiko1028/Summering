package jp.mushitori.service;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;

import java.util.EnumSet;

/**
 * {@code behavior.movement-speed-multiplier} を、{@code Attribute.MOVEMENT_SPEED}
 * が効かない種別（{@link org.bukkit.entity.Bee}・{@link org.bukkit.entity.Fish}系。
 * {@link FleeFromPlayersGoal}のクラスコメント、および MC-172801 を参照）にも効かせるための、
 * 毎tick直接velocityを補正するGoal。
 *
 * <p>バニラAIが決めた「その瞬間の速度」に倍率を掛け直すだけなので、バニラ側の目的地選び
 * （花を探す・泳ぎ回る等）そのものは邪魔しない。{@link FleeFromPlayersGoal}より優先度を
 * 低くしてあるため、逃走中はそちらが優先される。</p>
 */
public final class MovementSpeedGoal implements Goal<Mob> {

    private static final GoalKey<Mob> KEY = GoalKey.of(Mob.class,
            new NamespacedKey("mushitori", "movement_speed"));

    private final Mob mob;
    private final double multiplier;

    public MovementSpeedGoal(Mob mob, double multiplier) {
        this.mob = mob;
        this.multiplier = multiplier;
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
        return mob.isValid();
    }

    @Override
    public void tick() {
        mob.setVelocity(mob.getVelocity().multiply(multiplier));
    }
}
