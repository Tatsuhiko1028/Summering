package jp.mushitori.model;

import org.jetbrains.annotations.Nullable;

/**
 * マーカーの生物枠ごとの、見た目・動き・レア度まわりの上書き。各フィールドがnullなら
 * 「上書きしない（creatures.ymlのその生物本来の設定のまま）」を意味します。
 *
 * <p>設定できる項目は、生物ごとの独特の動き（{@link CreatureBehavior}）、
 * 見た目のスケール基準値（{@link Creature#baseScale()}）、飛ぶ生物かどうか
 * （{@link Creature#flying()}）、基準の逃げやすさ（{@link Creature#escapeChance()}）、
 * サイズ→レア度繰り上げのテンプレート（{@link Creature#sizeRarityTemplate()}）、
 * 固定のベースレア度（{@link Creature#baseRarityKey()}）です。</p>
 *
 * <p>{@code flying}・{@code escapeChance}・{@code escapeDespawns}は捕獲判定の瞬間
 * （虫取り網・素手・寄ってくる釣り）に参照される値のため、湧いた個体のPDCに焼き付けて、
 * 実際に捕まえるときはそちらを見るようにしています（{@code AmbientSpawnService}の
 * {@code effectiveFlying}/{@code effectiveEscapeChance}/{@code effectiveEscapeDespawns}を参照）。
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
        @Nullable String baseRarityKey
) {
    public static final CreatureOverride EMPTY =
            new CreatureOverride(null, null, null, null, null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return scale == null && flying == null && escapeChance == null && fleeFromPlayers == null
                && fleeRadius == null && fleeSpeed == null && movementSpeedMultiplier == null
                && escapeDespawns == null && disableNectar == null && sizeRarityTemplate == null
                && baseRarityKey == null;
    }

    /** baseの動き設定に、nullでないフィールドだけ重ねて適用した結果を返す。 */
    public CreatureBehavior applyTo(CreatureBehavior base) {
        return new CreatureBehavior(
                disableNectar != null ? disableNectar : base.disableNectar(),
                fleeFromPlayers != null ? fleeFromPlayers : base.fleeFromPlayers(),
                fleeRadius != null ? fleeRadius : base.fleeRadius(),
                fleeSpeed != null ? fleeSpeed : base.fleeSpeed(),
                movementSpeedMultiplier != null ? movementSpeedMultiplier : base.movementSpeedMultiplier(),
                escapeDespawns != null ? escapeDespawns : base.escapeDespawns());
    }

    // ---- 1項目だけ差し替えたコピーを作るヘルパー（画面からの編集用） ----

    public CreatureOverride withScale(@Nullable Double v) {
        return new CreatureOverride(v, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey);
    }

    public CreatureOverride withFlying(@Nullable Boolean v) {
        return new CreatureOverride(scale, v, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey);
    }

    public CreatureOverride withEscapeChance(@Nullable Double v) {
        return new CreatureOverride(scale, flying, v, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey);
    }

    public CreatureOverride withFleeFromPlayers(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, v, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey);
    }

    public CreatureOverride withFleeRadius(@Nullable Double v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, v, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey);
    }

    public CreatureOverride withFleeSpeed(@Nullable Double v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, v,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey);
    }

    public CreatureOverride withMovementSpeedMultiplier(@Nullable Double v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                v, escapeDespawns, disableNectar, sizeRarityTemplate, baseRarityKey);
    }

    public CreatureOverride withEscapeDespawns(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, v, disableNectar, sizeRarityTemplate, baseRarityKey);
    }

    public CreatureOverride withDisableNectar(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, v, sizeRarityTemplate, baseRarityKey);
    }

    public CreatureOverride withSizeRarityTemplate(@Nullable String v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, v, baseRarityKey);
    }

    public CreatureOverride withBaseRarityKey(@Nullable String v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar, sizeRarityTemplate, v);
    }
}
