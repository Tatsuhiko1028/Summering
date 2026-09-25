package jp.mushitori.model;

import org.jetbrains.annotations.Nullable;

/**
 * マーカーの生物枠ごとの、見た目・動き・レア度・捕獲可否まわりの上書き。各フィールドがnullなら
 * 「上書きしない（creatures.ymlのその生物本来の設定のまま）」を意味します。
 *
 * <p>設定できる項目は、生物ごとの独特の動き（{@link CreatureBehavior}）、
 * 見た目のスケール基準値（{@link Creature#baseScale()}）、飛ぶ生物かどうか
 * （{@link Creature#flying()}）、基準の逃げやすさ（{@link Creature#escapeChance()}）、
 * サイズ→レア度繰り上げのテンプレート（{@link Creature#sizeRarityTemplate()}）、
 * 固定のベースレア度（{@link Creature#baseRarityKey()}）、バニラの敵対AIを無効化するか
 * （{@link Creature#suppressHostility()}）、素手・虫取り網で捕まえられるか
 * （{@link Creature#allowBareHand()}/{@link Creature#allowNet()}）です。</p>
 *
 * <p>{@code flying}・{@code escapeChance}・{@code escapeDespawns}・{@code suppressHostility}・
 * {@code allowBareHand}・{@code allowNet}は捕獲判定・敵対判定の瞬間に参照される値のため、
 * 湧いた個体のPDCに焼き付けて、実際に捕まえるときはそちらを見るようにしています
 * （{@code AmbientSpawnService}の{@code effectiveXxx}系メソッドを参照）。
 * {@code sizeRarityTemplate}・{@code baseRarityKey}は、湧く瞬間に「実効生物」
 * （{@link Creature#withSizeRarityTemplate}/{@link Creature#withBaseRarityKey}）へ
 * まとめて反映してから抽選するため、PDCへの個別の焼き付けはしていません。</p>
 */
public record CreatureOverride(
        @Nullable Double scale,
        @Nullable Boolean flying,
        @Nullable Double escapeChance,
        @Nullable Boolean fleeFromPlayers,
        @Nullable Double fleeRadius,
        @Nullable Double fleeSpeed,
        @Nullable Double movementSpeedMultiplier,
        @Nullable Boolean escapeDespawns,
        @Nullable Boolean disableNectar,
        @Nullable String sizeRarityTemplate,
        @Nullable String baseRarityKey,
        @Nullable Boolean suppressHostility,
        @Nullable Boolean allowBareHand,
        @Nullable Boolean allowNet,
        @Nullable Boolean schooling
) {
    public static final CreatureOverride EMPTY =
            new CreatureOverride(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);

    public boolean isEmpty() {
        return scale == null && flying == null && escapeChance == null && fleeFromPlayers == null
                && fleeRadius == null && fleeSpeed == null && movementSpeedMultiplier == null
                && escapeDespawns == null && disableNectar == null && sizeRarityTemplate == null
                && baseRarityKey == null && suppressHostility == null && allowBareHand == null
                && allowNet == null && schooling == null;
    }

    /** baseの動き設定に、nullでないフィールドだけ重ねて適用した結果を返す。 */
    public CreatureBehavior applyTo(CreatureBehavior base) {
        return new CreatureBehavior(
                disableNectar != null ? disableNectar : base.disableNectar(),
                fleeFromPlayers != null ? fleeFromPlayers : base.fleeFromPlayers(),
                fleeRadius != null ? fleeRadius : base.fleeRadius(),
                fleeSpeed != null ? fleeSpeed : base.fleeSpeed(),
                movementSpeedMultiplier != null ? movementSpeedMultiplier : base.movementSpeedMultiplier(),
                escapeDespawns != null ? escapeDespawns : base.escapeDespawns(),
                schooling != null ? schooling : base.schooling(),
                base.schoolingRadius(),
                base.schoolingSpeed());
    }

    // ---- 1項目だけ差し替えたコピーを作るヘルパー（画面からの編集用） ----

    public CreatureOverride withScale(@Nullable Double v) {
        return new CreatureOverride(v, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withFlying(@Nullable Boolean v) {
        return new CreatureOverride(scale, v, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withEscapeChance(@Nullable Double v) {
        return new CreatureOverride(scale, flying, v, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withFleeFromPlayers(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, v, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withFleeRadius(@Nullable Double v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, v, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withFleeSpeed(@Nullable Double v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, v,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withMovementSpeedMultiplier(@Nullable Double v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                v, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withEscapeDespawns(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, v, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withDisableNectar(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, v, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withSizeRarityTemplate(@Nullable String v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, v, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withBaseRarityKey(@Nullable String v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, v,
                suppressHostility, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withSuppressHostility(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                v, allowBareHand, allowNet, schooling);
    }

    public CreatureOverride withAllowBareHand(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, v, allowNet, schooling);
    }

    public CreatureOverride withAllowNet(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, v, schooling);
    }

    public CreatureOverride withSchooling(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey,
                suppressHostility, allowBareHand, allowNet, v);
    }
}
