package jp.mushitori.service;

import jp.mushitori.Keys;
import jp.mushitori.model.Creature;
import jp.mushitori.model.CreatureOverride;
import jp.mushitori.model.CreatureBehavior;
import jp.mushitori.model.TropicalFishVariant;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Salmon;
import org.bukkit.entity.TropicalFish;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Nullable;

/**
 * いきものエンティティの生成。
 * リソースパックでモデルを差し替える前提なので、ここでは
 * 土台エンティティを湧かせて creature_id のタグを付けるだけです。
 *
 * <p>湧かせた瞬間に、その個体固有の「素」のサイズ段階を1回だけ抽選し、
 * エンティティのPDCに焼き付けます（{@link CatchService#bakeTraits(Entity, Creature)}）。
 * 捕まえる瞬間には、これに道具のボーナスが上乗せされます。
 * 同じタイミングで、そのサイズ段階に応じた見た目のスケールも適用します
 * （{@link CatchService#applyScale(Entity, Creature, double, String)}）。
 * entity が TROPICAL_FISH で {@code tropical-fish} 設定がある場合は、
 * 指定された模様・色も適用します。</p>
 *
 * <p><b>SALMONのサイズ差について</b>：SALMON（イワナ・アジ等の土台）はバニラ側に、
 * 湧いた瞬間ランダムに決まる「サイズの種類」（SMALL / MEDIUM / LARGE）が別途あり、
 * これが見た目のscaleとは無関係に毎回ランダムに変わっていたことが分かりました
 * （見た目の大小が実測cmと食い違って見えていた主原因です）。これを{@code MEDIUM}に
 * 固定することで、以降は完全にこちらのscale計算だけがサイズを決めるようにしています
 * （SMALLだと影が不自然に大きく見えるため、MEDIUMにしています）。</p>
 *
 * <p><b>生物ごとの独特の動き</b>（creatures.yml の {@code behavior} セクション、
 * {@link CreatureBehavior}）もここで適用します。BEEの蜜集め無効化は
 * Paperの Mob Goals API（{@link Bukkit#getMobGoals()}）で該当のバニラAIそのものを
 * 取り除く方式です（単に {@code setHasNectar(false)} するだけでは、その後また
 * 蜜を集めに行ってしまうため）。</p>
 *
 * <p>名前（カスタムネーム）は表示させない前提です。{@code setCustomNameVisible(false)}に加えて、
 * あらかじめ用意されている {@code hidename} という名前のスコアボードチームが存在すれば、
 * そこへ自動的に参加させます（チーム側でネームタグ非表示等が設定されている想定）。</p>
 */
public final class SpawnService {

    private static final String HIDE_NAME_TEAM = "hidename";

    private final CatchService catchService;

    public SpawnService(CatchService catchService) {
        this.catchService = catchService;
    }

    @Nullable
    public Entity spawn(Creature creature, Location location) {
        return spawn(creature, location, null, null);
    }

    /** サイズ分布テンプレート指定版（マーカーの生物枠ごとの上書き用。nullなら既定のsizesセクションを使う）。 */
    @Nullable
    public Entity spawn(Creature creature, Location location, @Nullable String sizeDistributionTemplate) {
        return spawn(creature, location, sizeDistributionTemplate, null);
    }

    /**
     * サイズ分布テンプレート・見た目/動きの上書き、両方を指定できる版
     * （マーカーの生物枠ごとの上書き用）。
     *
     * @param override null または {@link CreatureOverride#isEmpty()} なら上書き無し。
     *                 null出ないフィールドだけ、その個体限定でcreatures.yml本来の設定を上書きします。
     */
    @Nullable
    public Entity spawn(Creature creature, Location location, @Nullable String sizeDistributionTemplate,
                        @Nullable CreatureOverride override) {
        EntityType type = creature.entityType();
        if (type == null || location.getWorld() == null) return null;

        Creature effective = creature;
        if (override != null && !override.isEmpty()) {
            if (override.scale() != null) effective = effective.withBaseScale(override.scale());
            if (override.sizeRarityTemplate() != null) {
                effective = effective.withSizeRarityTemplate(override.sizeRarityTemplate());
            }
            if (override.baseRarityKey() != null) {
                effective = effective.withBaseRarityKey(override.baseRarityKey());
            }
            effective = effective.withBehavior(override.applyTo(effective.behavior()));
        }
        Creature finalEffective = effective;

        return location.getWorld().spawnEntity(location, type, CreatureSpawnEvent.SpawnReason.CUSTOM, spawned -> {
            spawned.getPersistentDataContainer()
                    .set(Keys.CREATURE_ID, PersistentDataType.STRING, finalEffective.id());
            spawned.customName(Component.text(finalEffective.name()));
            spawned.setCustomNameVisible(false);
            spawned.setPersistent(true);
            spawned.setSilent(true);
            if (spawned instanceof LivingEntity living) {
                living.setRemoveWhenFarAway(false);
                living.setCanPickupItems(false);
            }
            if (spawned instanceof Salmon salmon) {
                // バニラ側のランダムなサイズ種類を固定し、見た目のサイズはこちらのscale計算だけで決まるようにする
                salmon.setVariant(Salmon.Variant.MEDIUM); // SMALLだと影が不自然に大きいためMEDIUMを採用
            }
            applyTropicalFishVariant(spawned, finalEffective);
            applyBehavior(spawned, finalEffective.behavior());
            joinHideNameTeam(spawned);
            var base = catchService.bakeTraits(spawned, finalEffective, sizeDistributionTemplate);
            Double bakedCm = catchService.bakedSizeCmOf(spawned);
            if (bakedCm != null) {
                catchService.applyScale(spawned, finalEffective, bakedCm, base.sizeTierKey());
            }
        });
    }

    private void applyTropicalFishVariant(Entity spawned, Creature creature) {
        TropicalFishVariant variant = creature.tropicalFishVariant();
        if (variant == null || !(spawned instanceof TropicalFish fish)) return;
        if (variant.pattern() != null) fish.setPattern(variant.pattern());
        if (variant.bodyColor() != null) fish.setBodyColor(variant.bodyColor());
        if (variant.patternColor() != null) fish.setPatternColor(variant.patternColor());
    }

    /** creatures.yml の behavior セクションで指定された、生物ごとの独特の動きを適用する。 */
    private void applyBehavior(Entity spawned, CreatureBehavior behavior) {
        if (behavior.disableNectar() && spawned instanceof Bee bee) {
            bee.setHasNectar(false);
            var mobGoals = Bukkit.getMobGoals();
            // 花を探す→蜜を集める→巣（無ければ作物）へ運ぶ、という一連のバニラAIを取り除く。
            // これをしないと、setHasNectar(false) だけではまたすぐ蜜を集めに行ってしまう。
            mobGoals.removeGoal(bee, VanillaGoal.BEE_LOCATE_HIVE);
            mobGoals.removeGoal(bee, VanillaGoal.BEE_GO_TO_KNOWN_FLOWER);
            mobGoals.removeGoal(bee, VanillaGoal.BEE_POLLINATE);
            mobGoals.removeGoal(bee, VanillaGoal.BEE_GROW_CROP);
            mobGoals.removeGoal(bee, VanillaGoal.BEE_GO_TO_HIVE);
            mobGoals.removeGoal(bee, VanillaGoal.BEE_ENTER_HIVE);
        }

        if (behavior.movementSpeedMultiplier() != 1.0 && spawned instanceof LivingEntity living) {
            AttributeInstance attr = living.getAttribute(Attribute.MOVEMENT_SPEED);
            if (attr != null) {
                attr.setBaseValue(attr.getBaseValue() * behavior.movementSpeedMultiplier());
            }
            // Bee・Fish系はAttribute.MOVEMENT_SPEED（FLYING_SPEEDも同様）がバニラ側で
            // 反映されない既知の問題があるため（MovementSpeedGoalのクラスコメント参照）、
            // これらの種別だけは毎tick直接velocityを補正するGoalで倍率を効かせる。
            if ((spawned instanceof Bee || spawned instanceof Fish) && spawned instanceof Mob speedMob) {
                Bukkit.getMobGoals().addGoal(speedMob, 2,
                        new MovementSpeedGoal(speedMob, behavior.movementSpeedMultiplier()));
            }
        }

        if (behavior.fleeFromPlayers() && spawned instanceof Mob mob) {
            Bukkit.getMobGoals().addGoal(mob, 1,
                    new FleeFromPlayersGoal(mob, behavior.fleeRadius(), behavior.fleeSpeed()));
        }

        if (behavior.schooling() && spawned instanceof Mob schoolMob) {
            // 優先度はFlee(1)・MovementSpeed(2)の次。同じ生物にmovement-speed-multiplierも
            // 設定されている場合、PaperのGoalType.MOVEは同時に1つしか動かないため、
            // 群れ行動が実際には発動しないことがある（既知の制限）。
            Bukkit.getMobGoals().addGoal(schoolMob, 3,
                    new SchoolingGoal(schoolMob, behavior.schoolingRadius(), behavior.schoolingSpeed()));
        }
    }

    /** 既存の "hidename" チームがあれば、そこへ参加させる（無ければ何もしない）。 */
    private void joinHideNameTeam(Entity spawned) {
        var scoreboardManager = Bukkit.getScoreboardManager();
        if (scoreboardManager == null) return;
        Team team = scoreboardManager.getMainScoreboard().getTeam(HIDE_NAME_TEAM);
        if (team == null) return;
        team.addEntry(spawned.getUniqueId().toString());
    }

    /** エンティティに付いている creature_id を読む。 */
    @Nullable
    public String creatureIdOf(Entity entity) {
        return entity.getPersistentDataContainer().get(Keys.CREATURE_ID, PersistentDataType.STRING);
    }
}
