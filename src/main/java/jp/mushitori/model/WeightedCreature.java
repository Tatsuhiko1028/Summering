package jp.mushitori.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * マーカーが複数種類を持てるようにするための、生物ID＋相対ウェイト＋この枠だけの上書き一式。
 * ウェイトは合計に対する比率で決まります（合計しても100である必要はありません）。
 * 例：{@code (yellow_fly, 7), (fly, 2), (kabutomushi, 1)} なら、7:2:1の比率で選ばれます。
 *
 * @param requiredTagsOverride この枠から湧く個体だけに適用する、必要タグの上書き。
 *                  空なら上書き無し（creatures.ymlのその生物本来のrequired-tagsを使う）。
 *                  同じ生物でも、マーカーごとに違う道具でしか捕まえられないようにしたい
 *                  場合に使います（例：このマーカーのカブトムシだけ特別なタグが要る、等）。
 * @param override  この枠から湧く個体だけに適用する、見た目のスケール・独特の動き・
 *                  レア度まわりの上書き。{@link CreatureOverride#isEmpty()}なら上書き無し。
 * @param sizeDistributionTemplate この枠から湧く個体のサイズ段階抽選に使う、
 *                  config.yml size-distribution-templates の名前（「サイズイベント」）。
 *                  nullまたは未定義の名前なら、既定のsizesセクションの重みをそのまま使います。
 * @param approachOverride この枠から湧く個体（魚）だけに適用する、「寄ってくる釣り」
 *                  （{@code fishing.approach}）関連設定の上書き。creatures.yml側の
 *                  approachセクションより優先されます。{@link ApproachOverrides#EMPTY}なら上書き無し。
 */
public record WeightedCreature(String creatureId, int weight, Set<String> requiredTagsOverride,
                               CreatureOverride override, String sizeDistributionTemplate,
                               ApproachOverrides approachOverride) {
    public WeightedCreature {
        if (weight < 1) weight = 1;
        requiredTagsOverride = requiredTagsOverride == null || requiredTagsOverride.isEmpty()
                ? Set.of() : new LinkedHashSet<>(requiredTagsOverride);
        if (override == null) override = CreatureOverride.EMPTY;
        if (sizeDistributionTemplate != null && sizeDistributionTemplate.isBlank()) sizeDistributionTemplate = null;
        if (approachOverride == null) approachOverride = ApproachOverrides.EMPTY;
    }

    /** 上書き無しの簡易コンストラクタ。 */
    public WeightedCreature(String creatureId, int weight) {
        this(creatureId, weight, Set.of(), CreatureOverride.EMPTY, null, ApproachOverrides.EMPTY);
    }

    /** タグ上書きのみ指定する簡易コンストラクタ（互換用）。 */
    public WeightedCreature(String creatureId, int weight, Set<String> requiredTagsOverride) {
        this(creatureId, weight, requiredTagsOverride, CreatureOverride.EMPTY, null, ApproachOverrides.EMPTY);
    }

    /** タグ・見た目/動き上書きのみ指定するコンストラクタ（互換用）。 */
    public WeightedCreature(String creatureId, int weight, Set<String> requiredTagsOverride,
                            CreatureOverride override) {
        this(creatureId, weight, requiredTagsOverride, override, null, ApproachOverrides.EMPTY);
    }

    public WeightedCreature withRequiredTagsOverride(Set<String> tags) {
        return new WeightedCreature(creatureId, weight, tags, override, sizeDistributionTemplate, approachOverride);
    }

    public WeightedCreature withOverride(CreatureOverride newOverride) {
        return new WeightedCreature(creatureId, weight, requiredTagsOverride, newOverride,
                sizeDistributionTemplate, approachOverride);
    }

    public WeightedCreature withSizeDistributionTemplate(String newSizeDistributionTemplate) {
        return new WeightedCreature(creatureId, weight, requiredTagsOverride, override,
                newSizeDistributionTemplate, approachOverride);
    }

    public WeightedCreature withApproachOverride(ApproachOverrides newApproachOverride) {
        return new WeightedCreature(creatureId, weight, requiredTagsOverride, override,
                sizeDistributionTemplate, newApproachOverride);
    }
}
