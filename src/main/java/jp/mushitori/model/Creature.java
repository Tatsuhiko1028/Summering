package jp.mushitori.model;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * creatures.yml の1件ぶん。サイズはすべて cm。
 *
 * @param escapeChance 基準の「逃げやすさ」（0.0〜1.0。道具の escape-modifier が加算されて
 *                     最終的な逃走確率になります。0なら何をどう使っても絶対に逃げません。
 *                     1なら絶対に逃げます）
 * @param baseRarityKey 固定の「ベースレア度」（config.yml の rarities のキー）。
 *                     最終的なレア度は、捕まえた瞬間のサイズ段階に応じてここから
 *                     繰り上がります（ランダム抽選ではありません）
 * @param sizeRarityTemplate サイズ→レア度繰り上げの「テンプレート」名（省略可。
 *                     省略時は config.yml の catching.default-size-rarity-template を使用）
 * @param tropicalFishVariant entity が TROPICAL_FISH のときだけ使う、模様・体色の指定（省略可）
 * @param baseScale 見た目のスケールの基準値（例: 元の entity より小さく見せたい場合は1.0未満に）。
 *                  実際のスケールは、これにサイズ段階による倍率（config.yml の catching.scale）
 *                  を掛け合わせて決まります
 * @param customModelData リソースパック側の item.model.overrides に対応させる数値
 *                  （custom_model_data）。省略可
 * @param behavior 生物ごとの独特の動きの設定（省略時は{@link CreatureBehavior#defaults()}）
 * @param requiredTags この生物を捕まえるのに必要な道具のタグ（省略・空なら誰でも捕まえられる）。
 *                  道具（網・竿）側のタグが、ここで指定した全てを含んでいないと捕まえられません
 * @param flying 空を飛ぶ生物かどうか（素手での捕獲難易度に影響。省略時はentityがBEEなら
 *                  true、それ以外はfalse）
 */
public record Creature(
        String id,
        String name,
        Category category,
        double sizeMin,
        double sizeMax,
        int basePrice,
        String habitat,
        List<String> description,
        Material material,
        @Nullable Integer customModelData,
        @Nullable EntityType entityType,
        int order,
        double escapeChance,
        String baseRarityKey,
        @Nullable String sizeRarityTemplate,
        @Nullable TropicalFishVariant tropicalFishVariant,
        double baseScale,
        CreatureBehavior behavior,
        java.util.Set<String> requiredTags,
        boolean flying
) {
    /** サイズ 0.0〜1.0 の位置を返す（価格計算用）。 */
    public double sizeRatio(double sizeCm) {
        double span = Math.max(0.0001, sizeMax - sizeMin);
        return Math.max(0.0, Math.min(1.0, (sizeCm - sizeMin) / span));
    }

    /** その道具（網・竿）のタグで、この生物を捕まえられるか。 */
    public boolean isCatchableWith(java.util.Set<String> toolTags) {
        return requiredTags.isEmpty() || toolTags.containsAll(requiredTags);
    }

    /** baseScaleだけを差し替えたコピーを返す（マーカーの生物枠ごとの上書き用）。 */
    public Creature withBaseScale(double newBaseScale) {
        return new Creature(id, name, category, sizeMin, sizeMax, basePrice, habitat, description, material,
                customModelData, entityType, order, escapeChance, baseRarityKey, sizeRarityTemplate,
                tropicalFishVariant, newBaseScale, behavior, requiredTags, flying);
    }

    /** behaviorだけを差し替えたコピーを返す（マーカーの生物枠ごとの上書き用）。 */
    public Creature withBehavior(CreatureBehavior newBehavior) {
        return new Creature(id, name, category, sizeMin, sizeMax, basePrice, habitat, description, material,
                customModelData, entityType, order, escapeChance, baseRarityKey, sizeRarityTemplate,
                tropicalFishVariant, baseScale, newBehavior, requiredTags, flying);
    }
}
