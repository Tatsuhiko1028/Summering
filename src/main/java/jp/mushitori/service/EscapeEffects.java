package jp.mushitori.service;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 「捕まえそこねて逃げられた」ときの、煙のパーティクル＋瞬間移動の演出。
 * 虫取り網（{@link jp.mushitori.listener.NetListener}）と通常の釣り
 * （{@link jp.mushitori.listener.FishingListener}）で共通して使います。
 *
 * <p>テレポート中は一瞬だけ透明化することで、移動の軌跡（クライアント側の補間による
 * 滑るような見え方）が見えないようにし、「消えて別の場所に現れた」ように見せています。</p>
 *
 * <p>効果音は呼び出し側から指定します（虫は「スカッ」という空振り寄りの音、
 * 魚は「ジュポン」という水音、といった具合に使い分けられるようにするためです）。</p>
 *
 * <p>魚が浮きに寄ってくる釣り（{@link ApproachFishingService}）は、水中限定の
 * テレポートなど独自の演出を持っているため、こちらは使わず自前の仕組みのままです。</p>
 */
public final class EscapeEffects {

    /** 透明化してから、元に戻すまでの間隔（tick）。 */
    private static final long REVEAL_DELAY_TICKS = 5L;

    private EscapeEffects() {
    }

    /** その場に煙のパーティクルと、指定した効果音を鳴らす。 */
    public static void puff(Location loc, Sound sound) {
        if (loc.getWorld() == null) return;
        loc.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc, 18, 0.25, 0.25, 0.25, 0.02);
        loc.getWorld().playSound(loc, sound, 0.6f, 0.8f);
    }

    /**
     * 煙とともに、エンティティを近くのランダムな場所へ瞬間移動させる（水などの制約はなし）。
     * 移動の軌跡が見えないよう、テレポートの前後で一瞬だけ透明化する。
     */
    public static void escapeNearby(Plugin plugin, Entity entity, double radius, Sound sound) {
        Location from = entity.getLocation();
        Location to = randomNearby(from, radius);

        puff(from, sound);
        setInvisible(entity, true);
        entity.teleport(to);
        puff(to, sound);
        scheduleReveal(plugin, entity);
    }

    private static void setInvisible(Entity entity, boolean invisible) {
        if (entity instanceof LivingEntity living) {
            living.setInvisible(invisible);
        }
    }

    private static void scheduleReveal(Plugin plugin, Entity entity) {
        if (!(entity instanceof LivingEntity)) return;
        entity.getScheduler().runDelayed(plugin, task -> setInvisible(entity, false), null, REVEAL_DELAY_TICKS);
    }

    private static Location randomNearby(Location center, double radius) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble() * Math.PI * 2;
        double r = radius * (0.3 + rnd.nextDouble() * 0.7);
        double dx = Math.cos(angle) * r;
        double dz = Math.sin(angle) * r;
        return center.clone().add(dx, 0, dz);
    }
}
