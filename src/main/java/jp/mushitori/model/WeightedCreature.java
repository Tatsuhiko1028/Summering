package jp.mushitori.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * マーカー・プリセットが複数種類を持てるようにするための、生物ID＋相対ウェイト。
 * ウェイトは合計に対する比率で決まります（合計しても100である必要はありません）。
 * 例：{@code (yellow_fly, 7), (fly, 2), (kabutomushi, 1)} なら、7:2:1の比率で選ばれます。
 *
 * @param requiredTagsOverride この枠から湧く個体だけに適用する、必要タグの上書き。
 *                  空なら上書き無し（creatures.ymlのその生物本来のrequired-tagsを使う）。
 *                  同じ生物でも、マーカーごとに違う道具でしか捕まえられないようにしたい
 *                  場合に使います（例：このマーカーのカブトムシだけ特別なタグが要る、等）。
 * @param override  この枠から湧く個体だけに適用する、見た目のスケール・独特の動きの上書き。
 *                  {@link CreatureOverride#isEmpty()}なら上書き無し。
 */
public record WeightedCreature(String creatureId, int weight, Set<String> requiredTagsOverride,
                               CreatureOverride override) {
    public WeightedCreature {
        if (weight < 1) weight = 1;
        requiredTagsOverride = requiredTagsOverride == null || requiredTagsOverride.isEmpty()
                ? Set.of() : new LinkedHashSet<>(requiredTagsOverride);
        if (override == null) override = CreatureOverride.EMPTY;
    }

    /** 上書き無しの簡易コンストラクタ。 */
    public WeightedCreature(String creatureId, int weight) {
        this(creatureId, weight, Set.of(), CreatureOverride.EMPTY);
    }

    /** タグ上書きのみ指定する簡易コンストラクタ（互換用）。 */
    public WeightedCreature(String creatureId, int weight, Set<String> requiredTagsOverride) {
        this(creatureId, weight, requiredTagsOverride, CreatureOverride.EMPTY);
    }

    public WeightedCreature withRequiredTagsOverride(Set<String> tags) {
        return new WeightedCreature(creatureId, weight, tags, override);
    }

    public WeightedCreature withOverride(CreatureOverride newOverride) {
        return new WeightedCreature(creatureId, weight, requiredTagsOverride, newOverride);
    }
}
