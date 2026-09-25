package jp.mushitori.model;

import org.jetbrains.annotations.Nullable;

/**
 * マーカーの生物枠ごとの、見た目・動きの上書き。各フィールドがnullなら「上書きしない
 * （creatures.ymlのその生物本来の設定のまま）」を意味します。
 *
 * <p>設定できる項目は、生物ごとの独特の動き（{@link CreatureBehavior}）、
 * 見た目のスケール基準値（{@link Creature#baseScale()}）、飛ぶ生物かどうか
 * （{@link Creature#flying()}）、基準の逃げやすさ（{@link Creature#escapeChance()}）です。</p>
 *
 * <p>{@code flying}・{@code escapeChance}は捕獲判定の瞬間（虫取り網・素手・
 * 寄ってくる釣り）に参照される値のため、湧いた個体のPDCに焼き付けて、実際に
 * 捕まえるときはそちらを見るようにしています（{@code AmbientSpawnService}の
 * {@code effectiveFlying}/{@code effectiveEscapeChance}を参照）。</p>
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
        @Nullable Boolean disableNectar
) {
    public static final CreatureOverride EMPTY =
            new CreatureOverride(null, null, null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return scale == null && flying == null && escapeChance == null && fleeFromPlayers == null
                && fleeRadius == null && fleeSpeed == null && movementSpeedMultiplier == null
                && escapeDespawns == null && disableNectar == null;
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
                movementSpeedMultiplier, escapeDespawns, disableNectar);
    }

    public CreatureOverride withFlying(@Nullable Boolean v) {
        return new CreatureOverride(scale, v, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar);
    }

    public CreatureOverride withEscapeChance(@Nullable Double v) {
        return new CreatureOverride(scale, flying, v, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar);
    }

    public CreatureOverride withFleeFromPlayers(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, v, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar);
    }

    public CreatureOverride withFleeRadius(@Nullable Double v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, v, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, disableNectar);
    }

    public CreatureOverride withFleeSpeed(@Nullable Double v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, v,
                movementSpeedMultiplier, escapeDespawns, disableNectar);
    }

    public CreatureOverride withMovementSpeedMultiplier(@Nullable Double v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                v, escapeDespawns, disableNectar);
    }

    public CreatureOverride withEscapeDespawns(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, v, disableNectar);
    }

    public CreatureOverride withDisableNectar(@Nullable Boolean v) {
        return new CreatureOverride(scale, flying, escapeChance, fleeFromPlayers, fleeRadius, fleeSpeed,
                movementSpeedMultiplier, escapeDespawns, v);
    }
}
