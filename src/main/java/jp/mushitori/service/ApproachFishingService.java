package jp.mushitori.service;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import jp.mushitori.MushitoriPlugin;
import jp.mushitori.listener.FishingListener;
import jp.mushitori.listener.NetListener;
import jp.mushitori.model.ApproachOverrides;
import jp.mushitori.model.Category;
import jp.mushitori.model.Creature;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 【試験実装】魚が浮きに直接寄ってくる釣り（一本釣り）。
 *
 * <p>対象は{@link Category#isFish() 魚に分類される生物}です。通常の釣り
 * （バニラの釣り機構）とこちらの「寄ってくる釣り」は、種類を問わずどちらの方法でも
 * 狙えるようにしています（カテゴリ分けは図鑑タブ・実績の整理のためのもので、
 * 獲得方法を制限するものではありません）。</p>
 *
 * <p>通常の釣りは難易度が低く、いろいろな種類の
 * 低レア度の魚が出やすい「ベター」な選択肢。こちらの「モブ直接釣り」は難易度が高い代わりに
 * 良いもの・限定の魚が釣れる、という棲み分けを意図しています。</p>
 *
 * <p>対象になるのは <b>すでにワールドに実在する、{@code creature_id}タグ付きの個体</b>
 * （{@code /mushitori spawn} などで置いたもの）だけです。浮きを投げた瞬間に新しく
 * 自動で湧く、ということはしません。数が少ない・希少という点は、捕まえると個体自体が
 * いなくなる（管理者が置き直す必要がある）ことで自然に表現されます。</p>
 *
 * <p>動作の流れ：</p>
 * <ol>
 *   <li>釣竿をキャストした瞬間、浮きが水面に浮いていなければ何もしない。浮いていれば、
 *       浮きの近く（{@code notice-radius}、浮きを基準に探索）にいる対象個体を確認する。
 *       その場でいなくても諦めず、{@code retry-interval-seconds}おきに探し続ける
 *       （後から範囲内に入ってきた個体も、次の巡回で見つかります）</li>
 *   <li>いた場合、{@code patience-seconds}だけ待つ（じっと糸を垂らし続ける）と、
 *       範囲内の対象個体それぞれが独立に{@code trigger-chance}で判定される
 *       （個体が多いほど、誰かが反応しやすくなる）。誰も反応しなければ
 *       {@code retry-interval-seconds}おきに再判定し続ける（待てば待つほどチャンスがある）</li>
 *   <li>反応した個体（複数いれば1匹選ぶ）を、Paperのパスファインダー
 *       （{@link com.destroystokyo.paper.entity.Pathfinder}）で浮きへ誘導する。魚の
 *       ナビゲーションは水中限定のため、壁や陸地を突っ切ることはなく、水がつながって
 *       いない孤立した水たまりにいる場合はそもそも経路が見つからない（＝捕まえられない）。
 *       迂回すればつながっている場合は、自然にその迂回ルートを通る。浮きが水面から
 *       離れた、または魚が水中にいなくなった場合は、その時点で誘導を打ち切る</li>
 *   <li>浮きに完全に密着したら「振るタイミング」の合図（音・パーティクル）を出し、
 *       一定時間だけ受付ける。その間にプレイヤーが竿を振る（リールを巻く）と、
 *       生物の基準の逃げやすさ＋竿の補正で判定し、逃げられなければ釣れる</li>
 *   <li>タイミングを逃す、早すぎたタイミングで振った、またはタイミングが合っていても
 *       最後の判定で逃げられた場合は、煙のエフェクトとともに近くの水中へ一瞬で逃げていく
 *       （個体自体は消えない＝また誰かが挑戦できる。地面に埋まらないよう、水が
 *       見つからない場合は元の位置のままにする）</li>
 * </ol>
 *
 * <p>誘導中に、何らかの理由で魚が陸に上がってしまった場合は、近くの水を探し、
 * ぴちぴちと跳ねるような動き（横方向の速度＋少し上向きの速度）で少しずつ
 * そちらへ戻します。浮きへの接近は、水に戻るまでお預けになります。</p>
 *
 * <p>「竿を振る」の検知は {@link jp.mushitori.listener.FishingListener} 側で
 * {@code PlayerFishEvent} の状態変化を見て {@link #onReelAttempt(Player)} を呼ぶことで行います。</p>
 */
public final class ApproachFishingService {

    private enum Phase {APPROACHING, WINDOW}
    private enum EscapeMode {SMOKE_DESPAWN, RELOCATE}

    private static final class Session {
        final Entity fish;
        final Creature creature;
        final double sizeBonus;
        final double escapeModifier;
        volatile Phase phase = Phase.APPROACHING;
        volatile int windowTicksLeft;

        Session(Entity fish, Creature creature, double sizeBonus, double escapeModifier) {
            this.fish = fish;
            this.creature = creature;
            this.sizeBonus = sizeBonus;
            this.escapeModifier = escapeModifier;
        }
    }

    private final MushitoriPlugin plugin;
    /** 誘導（追跡フェーズ／振るタイミング待ち）に入っているセッション。 */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** まだ誘導は始まっていない「様子を見ている」待機タスク。 */
    private final Map<UUID, ScheduledTask> waitingTasks = new ConcurrentHashMap<>();
    /** 他のプレイヤーと同時に取り合われないよう、誘導・待機の対象になっている個体のUUID。 */
    private final Set<UUID> reservedFish = ConcurrentHashMap.newKeySet();
    /** 逃げた個体が、次に判定対象へ戻ってよくなる時刻（エンティティUUID → epoch millis）。 */
    private final Map<UUID, Long> fishCooldownUntil = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private boolean debug = false;
    private double noticeRadius = 8.0;
    private double patienceSeconds = 15.0;
    private double retryIntervalSeconds = 5.0;
    private double triggerChance = 0.25;
    private double minApproachSeconds = 2.5;
    private double maxApproachSeconds = 5.0;
    private double arrivalDistance = 1.2;
    private double approachDepthOffset = 0.4;
    private double windowSeconds = 1.2;
    private int intervalTicks = 4;
    private double extraSizeBonus = 0.10;
    private double escapeCooldownSeconds = 20.0;
    private EscapeMode escapeMode = EscapeMode.RELOCATE;
    private double relocateRadius = 9.0;
    private Sound windowStartSound = Sound.ENTITY_FISHING_BOBBER_SPLASH;
    private Sound catchSound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;

    public ApproachFishingService(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(ConfigurationSection section) {
        if (section == null) return;
        enabled = section.getBoolean("enabled", true);
        debug = section.getBoolean("debug", false);
        noticeRadius = Math.max(1.0, section.getDouble("notice-radius", 8.0));
        patienceSeconds = Math.max(0.0, section.getDouble("patience-seconds", 15.0));
        retryIntervalSeconds = Math.max(0.5, section.getDouble("retry-interval-seconds", 5.0));
        triggerChance = section.getDouble("trigger-chance", 0.25);
        minApproachSeconds = Math.max(0.5, section.getDouble("min-approach-seconds", 2.5));
        maxApproachSeconds = Math.max(minApproachSeconds, section.getDouble("max-approach-seconds", 5.0));
        arrivalDistance = Math.max(0.15, section.getDouble("arrival-distance", 0.35));
        approachDepthOffset = Math.max(0.0, section.getDouble("approach-depth-offset", 0.4));
        windowSeconds = Math.max(0.3, section.getDouble("window-seconds", 1.2));
        intervalTicks = Math.max(1, section.getInt("update-interval-ticks", 4));
        extraSizeBonus = Math.max(0.0, section.getDouble("extra-size-bonus", 0.10));
        escapeCooldownSeconds = Math.max(0.0, section.getDouble("escape-cooldown-seconds", 20.0));        windowStartSound = parseSound(section.getString("sound-window-start"), Sound.ENTITY_FISHING_BOBBER_SPLASH);
        catchSound = parseSound(section.getString("sound-catch"), Sound.ENTITY_EXPERIENCE_ORB_PICKUP);

        ConfigurationSection escape = section.getConfigurationSection("escape");
        if (escape != null) {
            relocateRadius = Math.max(1.0, escape.getDouble("relocate-radius", 9.0));
            try {
                escapeMode = EscapeMode.valueOf(escape.getString("mode", "RELOCATE").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                escapeMode = EscapeMode.RELOCATE;
            }
        }
    }

    private static Sound parseSound(String raw, Sound fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private void log(String message) {
        if (debug) {
            plugin.getLogger().info("[fishing.approach] " + message);
        }
    }

    /**
     * 釣竿をキャストしたタイミングで呼ぶ。近くに対象個体がいれば「待機」を開始する。
     *
     * @param rodSizeBonus 竿の大物ボーナス
     * @param rodEscapeModifier 竿の逃げやすさ補正
     * @param rodTags 竿に焼き付けられたタグ。required-tagsを満たさない生物は候補から除外する
     */
    public void maybeStart(Player player, FishHook hook, double rodSizeBonus, double rodEscapeModifier,
                           Set<String> rodTags) {
        if (!enabled) return;
        cancel(player); // 前回分の待機・誘導をリセット

        if (plugin.creatures().fishCreatures().isEmpty()) return;
        if (!hook.isInWater()) {
            log("浮きが水面に浮いていないため、寄ってくる釣りは働きません。");
            return;
        }

        List<Entity> nearby = findNearbyFishes(hook, rodTags);
        // 最初に見つからなくても、ここでは諦めない（一定間隔ごとに探し続ける。下のタスクを参照）。
        // 待ち時間（patience/retry）は、見つかっていれば一番近い個体の設定を、
        // まだ見つかっていなければ既定値を基準にする。
        String closestCreatureId = nearby.isEmpty() ? null : plugin.spawnService().creatureIdOf(nearby.get(0));
        ApproachOverrides baseOverrides = closestCreatureId == null
                ? ApproachOverrides.EMPTY
                : plugin.creatures().approachOverridesFor(closestCreatureId);

        double effectivePatience = baseOverrides.patienceSeconds(patienceSeconds);
        double effectiveRetry = baseOverrides.retryIntervalSeconds(retryIntervalSeconds);

        if (nearby.isEmpty()) {
            log("近くに設置済みの対象個体がまだいません。" + effectiveRetry + "秒おきに探し続けます。");
        } else {
            log(nearby.size() + "匹の対象個体を確認。" + effectivePatience + "秒待つと反応判定を始めます。");
        }

        long patienceTicks = Math.max(1L, Math.round(effectivePatience * 20.0));
        long retryTicks = Math.max(1L, Math.round(effectiveRetry * 20.0));
        UUID playerId = player.getUniqueId();

        ScheduledTask task = hook.getScheduler().runAtFixedRate(plugin, t -> {
            if (!hook.isValid() || !player.isOnline()) {
                t.cancel();
                waitingTasks.remove(playerId);
                return;
            }
            if (!hook.isInWater()) {
                log("浮きが水面から離れたため、待機を打ち切ります。");
                t.cancel();
                waitingTasks.remove(playerId);
                return;
            }
            List<Entity> candidates = findNearbyFishes(hook, rodTags);
            if (candidates.isEmpty()) {
                log("範囲内に対象個体がいません。引き続き探します。");
                return;
            }

            // それぞれの個体が「独立して」確率判定する（個体が多いほど、誰かが反応しやすい）
            List<Entity> succeeded = new ArrayList<>();
            for (Entity candidate : candidates) {
                String candidateCreatureId = plugin.spawnService().creatureIdOf(candidate);
                double candidateTrigger = candidateCreatureId == null
                        ? triggerChance
                        : plugin.creatures().approachOverridesFor(candidateCreatureId).triggerChance(triggerChance);
                if (ThreadLocalRandom.current().nextDouble() <= candidateTrigger) {
                    succeeded.add(candidate);
                }
            }
            if (succeeded.isEmpty()) {
                log(candidates.size() + "匹それぞれ判定しましたが、今回はどれもハズレでした。もうしばらく待ちます。");
                return;
            }

            Entity chosen = succeeded.get(ThreadLocalRandom.current().nextInt(succeeded.size()));
            // ここで初めて「取り合い」の確保を行う（Set#add の戻り値で判定することで、
            // 複数プレイヤーの待機タスクが同じ個体を同時に選んでしまっても、
            // 実際に誘導を始められるのは1人だけになるようにしている）。
            if (!reservedFish.add(chosen.getUniqueId())) {
                log("反応した個体は、ちょうど他のプレイヤーに取られてしまいました。もうしばらく待ちます。");
                return;
            }
            log(candidates.size() + "匹中" + succeeded.size() + "匹が反応。1匹が寄ってきます。");
            t.cancel();
            waitingTasks.remove(playerId);
            beginLure(player, hook, chosen, rodSizeBonus, rodEscapeModifier);
        }, () -> waitingTasks.remove(playerId), patienceTicks, retryTicks);

        waitingTasks.put(playerId, task);
    }

    private void beginLure(Player player, FishHook hook, Entity fish, double rodSizeBonus,
                           double rodEscapeModifier) {
        String creatureId = plugin.spawnService().creatureIdOf(fish);
        Creature creature = creatureId == null ? null : plugin.creatures().get(creatureId);
        if (creature == null) {
            // 通常はここに来ないはず（findNearbyFishes側で既に確認済み）だが、
            // 万一の保険として、確保だけ済んでいる状態を残さないようにする
            reservedFish.remove(fish.getUniqueId());
            return;
        }

        ApproachOverrides overrides = plugin.creatures().approachOverridesFor(creatureId);
        double effectiveMinApproach = overrides.minApproachSeconds(minApproachSeconds);
        double effectiveMaxApproach = Math.max(effectiveMinApproach, overrides.maxApproachSeconds(maxApproachSeconds));
        double effectiveWindow = overrides.windowSeconds(windowSeconds);

        // AIを止めるのではなく、パスファインダーで定期的に目的地を指示し続ける方式にしている
        // （バニラのAIを無効化してしまうと、その状態でPathfinderが正しく動く保証が無いため。
        // 既存のマーカー誘導処理（leashMobs）と同じ、AIを保ったままの制御方式）。
        // 確保（reservedFish への追加）は、呼び出し元（maybeStart の反応判定タイミング）で
        // 既に済んでいる（取り合いを避けるため、選んだ直後・最短で確保する必要があったため）。
        log(creature.name() + " が寄ってき始めました。");

        final Entity lureFish = fish;
        Session session = new Session(lureFish, creature, rodSizeBonus + extraSizeBonus, rodEscapeModifier);
        sessions.put(player.getUniqueId(), session);

        double seconds = effectiveMinApproach
                + ThreadLocalRandom.current().nextDouble() * (effectiveMaxApproach - effectiveMinApproach);
        int approachUpdates = Math.max(1, (int) Math.round(seconds * 20.0 / intervalTicks));
        int windowUpdates = Math.max(1, (int) Math.round(effectiveWindow * 20.0 / intervalTicks));
        int[] remaining = {approachUpdates};

        lureFish.getScheduler().runAtFixedRate(plugin, task -> {
            Session current = sessions.get(player.getUniqueId());
            if (current != session || !lureFish.isValid() || !hook.isValid() || !player.isOnline()) {
                task.cancel();
                return;
            }
            if (!hook.isInWater()) {
                log("浮きが水面から離れたため、誘導を打ち切ります。");
                task.cancel();
                cancel(player);
                return;
            }

            if (session.phase == Phase.APPROACHING) {
                remaining[0]--;

                if (!lureFish.isInWater()) {
                    // 何らかの理由で陸に上がってしまった：ぴちぴちと跳ねながら、
                    // 近くの水へ少しずつ戻す（浮きへの接近は、水に戻るまでお預け）
                    hopTowardWater(lureFish);
                    if (remaining[0] <= 0) {
                        log("陸から水へ戻れないまま時間切れになりました。あきらめました。");
                        task.cancel();
                        cancel(player);
                    }
                    return;
                }

                Location target = hook.getLocation().clone().subtract(0, approachDepthOffset, 0);
                boolean arrived = step(lureFish, target, remaining[0]);
                if (arrived) {
                    session.phase = Phase.WINDOW;
                    session.windowTicksLeft = windowUpdates;
                    player.playSound(hook.getLocation(), windowStartSound, 1.0f, 1.3f);
                    hook.getWorld().spawnParticle(Particle.SPLASH, hook.getLocation(), 14, 0.2, 0.1, 0.2, 0.02);
                    log("浮きに完全に密着。竿を振るタイミングです（" + windowSeconds + "秒間）。");
                } else if (remaining[0] <= 0) {
                    log("時間切れで浮きに到達できず、あきらめました。");
                    task.cancel();
                    cancel(player);
                }
            } else {
                session.windowTicksLeft--;
                if (session.windowTicksLeft <= 0) {
                    log("振るタイミングを逃し、魚は去っていきました。");
                    task.cancel();
                    cancel(player);
                }
            }
        }, () -> cleanupOnRetire(player.getUniqueId(), session), intervalTicks, intervalTicks);
    }

    /**
     * 残り更新回数ぶんで浮きに近づくよう1歩進める。到達したら true。
     *
     * <p>直線移動ではなく、Paperのパスファインダー（{@link com.destroystokyo.paper.entity.Pathfinder}）に
     * 経路探索を任せる。魚のナビゲーションは水中限定のため、これにより「壁や陸地を
     * 突っ切って近づく」ことがなくなり、水がつながっていない孤立した水たまりに
     * いる場合は、そもそも経路が見つからず {@code moveTo} が false を返す
     * （＝孤立していて捕まえられない）。逆に迂回すれば繋がっている場合は、
     * 経路探索が自然にその迂回ルートを通る。</p>
     */
    private boolean step(Entity fish, Location hookLocation, int remainingUpdates) {
        double distance = fish.getLocation().distance(hookLocation);
        if (distance < arrivalDistance) return true;

        if (fish instanceof Mob mob) {
            double speed = Math.max(0.15, Math.min(1.2, distance / Math.max(1, remainingUpdates) / 0.4));
            boolean pathing = mob.getPathfinder().moveTo(hookLocation, speed);
            if (!pathing) {
                // 経路が見つからない（浮きと隔離されている等）：直線移動にはフォールバックしない。
                // 呼び出し側が残り時間切れで自然にあきらめる形にする。
                log(fish.getName() + " は浮きへの経路が見つかりません（隔離されている可能性）。");
            }
            return false;
        }

        // パスファインダーを持たない種別への保険：これまで通りの直線移動
        Location current = fish.getLocation();
        Vector to = hookLocation.toVector().subtract(current.toVector());
        int steps = Math.max(1, remainingUpdates);
        Vector move = to.multiply(1.0 / steps);
        Location next = current.clone().add(move);
        next.setDirection(to);
        fish.teleport(next);
        return false;
    }

    /**
     * 魚が水中にいないとき、近くの水を探し、跳ねるような速度（横方向＋少し上向き）で
     * 少しずつそちらへ移動させる。見つからなければ何もしない。
     */
    private void hopTowardWater(Entity fish) {
        Location nearestWater = findNearestWaterBlock(fish.getLocation(), 6);
        if (nearestWater == null) return;

        Vector toward = nearestWater.toVector().subtract(fish.getLocation().toVector());
        toward.setY(0);
        if (toward.lengthSquared() < 0.0001) {
            toward = new Vector(ThreadLocalRandom.current().nextDouble(-1, 1), 0,
                    ThreadLocalRandom.current().nextDouble(-1, 1));
        }
        toward.normalize().multiply(0.35);
        toward.setY(0.4); // ぴちぴちと跳ねる、上向きの成分
        fish.setVelocity(toward);
    }

    /** 近くの水ブロックを、力任せに探す（見つからなければ null）。 */
    @Nullable
    private Location findNearestWaterBlock(Location from, int searchRadius) {
        World world = from.getWorld();
        if (world == null) return null;
        int cx = from.getBlockX();
        int cy = from.getBlockY();
        int cz = from.getBlockZ();
        Location best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (world.getBlockAt(x, y, z).getType() != Material.WATER) continue;
                    double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = new Location(world, x + 0.5, y + 0.5, z + 0.5);
                    }
                }
            }
        }
        return best;
    }

    /**
     * プレイヤーが竿を振った（リールを巻こうとした）ときに呼ぶ。
     *
     * @return タイミングが合って捕獲できたら true。それ以外（対象なし／タイミング外れ）は false
     */
    public boolean onReelAttempt(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return false;

        if (session.phase != Phase.WINDOW) {
            cancel(player); // 早すぎるタイミング：魚は逃げる（無言）
            return false;
        }

        sessions.remove(player.getUniqueId());

        // タイミングが合っていても、生物の基準の逃げやすさ＋竿の補正で、最後に逃げられることがある
        double effectiveEscapeChance = plugin.ambientSpawnService().effectiveEscapeChance(session.fish, session.creature);
        if (plugin.catchService().rollEscape(effectiveEscapeChance, session.escapeModifier)) {
            log("タイミングは合っていましたが、" + session.creature.name() + " に逃げられました。");
            handleEscape(session);
            FishingListener.consumeRodDurability(player); // 確率で失敗した場合も、竿の耐久は減る
            String text = plugin.messages().get("fishing.approach.escape",
                    "あと一歩のところで、{creature} に逃げられた…",
                    Map.of("creature", session.creature.name()));
            player.sendActionBar(Component.text(text, NamedTextColor.GRAY));
            return true; // 誘導としては処理済み（バニラ側の釣果は使わせない）
        }

        reservedFish.remove(session.fish.getUniqueId());
        catchFish(player, session);
        return true;
    }

    private void catchFish(Player player, Session session) {
        Location loc = session.fish.getLocation();

        var baseTraits = plugin.catchService().baseTraitsOfEntity(session.fish, session.creature);
        if (baseTraits == null) {
            // 焼き付けが無い個体（古いデータ等）への保険：その場で素の抽選から行う
            baseTraits = plugin.catchService().rollBaseTraits(session.creature);
        }
        var resolved = plugin.catchService()
                .resolveCatch(session.fish, session.creature, baseTraits, session.sizeBonus, true);
        ItemStack caughtItem = plugin.catchService().createCatchItem(session.creature, player,
                new CatchService.RolledTraits(resolved.rarityKey(), resolved.sizeTierKey()), resolved.sizeCm());
        var data = plugin.catchService().read(caughtItem);

        Integer markerId = plugin.ambientSpawnService().markerIdOf(session.fish);
        if (markerId != null) {
            plugin.ambientSpawnService().recordCatch(markerId, player);
        }

        if (session.fish.isValid()) {
            session.fish.remove();
        }
        NetListener.giveOrDrop(player, caughtItem);
        FishingListener.consumeRodDurability(player);

        if (loc.getWorld() != null) {
            loc.getWorld().spawnParticle(Particle.SPLASH, loc, 14, 0.25, 0.1, 0.25, 0.02);
        }
        player.playSound(player.getLocation(), catchSound, 0.8f, 1.1f);

        if (data != null) {
            // レア度はあえて伏せる（アイテムの説明文や図鑑で確認してもらう）
            String text = plugin.messages().get("fishing.approach.catch", "{creature} を釣り上げた！  {size}", Map.of(
                    "creature", session.creature.name(),
                    "size", CatchService.formatSize(data.sizeCm())));
            player.sendActionBar(Component.text(text, NamedTextColor.DARK_AQUA));
        }
    }

    /** バニラのアタリが先に来た・タイミングを逃した・リールを巻いた等、待機／誘導を打ち切るとき。 */
    public void cancel(Player player) {
        UUID uuid = player.getUniqueId();
        ScheduledTask waiting = waitingTasks.remove(uuid);
        if (waiting != null) {
            waiting.cancel();
        }
        Session session = sessions.remove(uuid);
        handleEscape(session);
    }

    /** プラグイン無効化時に、進行中の待機・誘導をすべて片付ける。 */
    public void cancelAll() {
        for (UUID uuid : Set.copyOf(waitingTasks.keySet())) {
            ScheduledTask waiting = waitingTasks.remove(uuid);
            if (waiting != null) waiting.cancel();
        }
        for (UUID uuid : Set.copyOf(sessions.keySet())) {
            handleEscape(sessions.remove(uuid));
        }
    }

    private void cleanupOnRetire(UUID uuid, Session expectedSession) {
        Session current = sessions.get(uuid);
        if (current == expectedSession) {
            sessions.remove(uuid);
            handleEscape(current);
        }
    }

    /**
     * 捕まえられずに終わった個体の後始末。config の {@code fishing.approach.escape.mode} に応じて、
     * 煙とともに消滅させる（{@code SMOKE_DESPAWN}）か、煙のエフェクトとともに近くの水中へ瞬間移動
     * させる（{@code RELOCATE}、既定）。焼き付けたレア度・サイズ段階は消滅時以外は保持されます。
     */
    private void handleEscape(Session session) {
        if (session == null) return;
        reservedFish.remove(session.fish.getUniqueId());
        Entity fish = session.fish;
        if (!fish.isValid()) return;

        if (escapeCooldownSeconds > 0) {
            fishCooldownUntil.put(fish.getUniqueId(),
                    System.currentTimeMillis() + Math.round(escapeCooldownSeconds * 1000.0));
        }

        boolean despawn = plugin.ambientSpawnService().effectiveEscapeDespawns(fish, session.creature)
                || escapeMode == EscapeMode.SMOKE_DESPAWN;
        if (despawn) {
            puff(fish.getLocation());
            fish.remove();
            log(session.creature.name() + " は煙とともに消えていきました（要: 管理者による再設置）。");
        } else {
            Location from = fish.getLocation();
            Location relocated = findNearbyWaterOrKeep(from);
            puff(from);
            setInvisible(fish, true);
            fish.teleport(relocated);
            if (!relocated.equals(from)) {
                puff(relocated);
            }
            scheduleReveal(fish);
            log(session.creature.name() + " は煙とともに、近くの水中へ一瞬で逃げていきました。");
        }
    }

    private void setInvisible(Entity entity, boolean invisible) {
        if (entity instanceof LivingEntity living) {
            living.setInvisible(invisible);
        }
    }

    private void scheduleReveal(Entity entity) {
        if (!(entity instanceof LivingEntity)) return;
        entity.getScheduler().runDelayed(plugin, task -> setInvisible(entity, false), null, 5L);
    }

    /** 煙のパーティクル＋テレポート音。逃走演出用。 */
    private void puff(Location loc) {
        if (loc.getWorld() == null) return;
        loc.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc, 18, 0.25, 0.25, 0.25, 0.02);
        loc.getWorld().playSound(loc, plugin.fishEscapeSound(), 0.6f, 0.8f);
    }

    /** 近くの水中を探す。見つからなければ現在地のまま（水の中にしかテレポートしない＝地面に埋まらない）。 */
    private Location findNearbyWaterOrKeep(Location from) {
        Location found = findNearestWaterBlock(from, (int) Math.ceil(relocateRadius));
        if (found != null) return found;
        // 見つからなければ、現在地に留まる。ただし現在地自体がブロックに埋まっている
        // （固体ブロック内）場合は、真上へ少しずつ抜け出す（水中への埋没はOKなので判定しない）
        return unstuckIfEmbedded(from);
    }

    /** その場所が固体ブロックの中に埋まっていたら、埋まっていない高さまで真上へ移動した地点を返す。 */
    private Location unstuckIfEmbedded(Location from) {
        var world = from.getWorld();
        if (world == null) return from;
        Location candidate = from.clone();
        for (int i = 0; i < 8; i++) {
            var block = candidate.getBlock();
            if (!block.getType().isSolid() || block.getType() == Material.WATER) {
                return candidate;
            }
            candidate.add(0, 1, 0);
        }
        return candidate;
    }

    /**
     * 浮きの近くにいる、{@code creature_id}タグ付きの魚（FISH カテゴリ）の個体を、
     * 近い順にすべて返す（誰にも取り合われていないもののみ）。
     */
    private List<Entity> findNearbyFishes(FishHook hook, Set<String> rodTags) {
        var world = hook.getWorld();
        if (world == null) return List.of();

        List<Entity> found = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Entity e : world.getNearbyEntities(hook.getLocation(), noticeRadius, noticeRadius, noticeRadius)) {
            if (!e.isValid() || reservedFish.contains(e.getUniqueId())) continue;
            if (!e.isInWater()) continue; // 水中にいない個体は対象外
            Long cooldownUntil = fishCooldownUntil.get(e.getUniqueId());
            if (cooldownUntil != null && cooldownUntil > now) continue;
            String creatureId = plugin.spawnService().creatureIdOf(e);
            if (creatureId == null) continue;
            Creature c = plugin.creatures().get(creatureId);
            if (c == null || !c.category().isFish()) continue;
            // マーカーの生物枠に専用タグの上書きがあればそちらを、無ければcreature本来のタグを使う
            var effectiveTags = plugin.ambientSpawnService().effectiveRequiredTags(e, c);
            if (!effectiveTags.isEmpty() && !rodTags.containsAll(effectiveTags)) continue; // この竿では対象外
            found.add(e);
        }
        found.sort((a, b) -> Double.compare(
                a.getLocation().distanceSquared(hook.getLocation()), b.getLocation().distanceSquared(hook.getLocation())));
        return found;
    }
}
