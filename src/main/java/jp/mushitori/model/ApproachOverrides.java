package jp.mushitori.model;

import org.jetbrains.annotations.Nullable;

/**
 * creatures.yml の魚（FISH）個体が、{@code fishing.approach} のグローバル設定を
 * 個別に上書きするための値。null のフィールドはグローバル設定をそのまま使います。
 */
public record ApproachOverrides(
        @Nullable Double patienceSeconds,
        @Nullable Double retryIntervalSeconds,
        @Nullable Double triggerChance,
        @Nullable Double minApproachSeconds,
        @Nullable Double maxApproachSeconds,
        @Nullable Double windowSeconds
) {
    public static final ApproachOverrides EMPTY =
            new ApproachOverrides(null, null, null, null, null, null);

    public double patienceSeconds(double fallback) {
        return patienceSeconds != null ? patienceSeconds : fallback;
    }

    public double retryIntervalSeconds(double fallback) {
        return retryIntervalSeconds != null ? retryIntervalSeconds : fallback;
    }

    public double triggerChance(double fallback) {
        return triggerChance != null ? triggerChance : fallback;
    }

    public double minApproachSeconds(double fallback) {
        return minApproachSeconds != null ? minApproachSeconds : fallback;
    }

    public double maxApproachSeconds(double fallback) {
        return maxApproachSeconds != null ? maxApproachSeconds : fallback;
    }

    public double windowSeconds(double fallback) {
        return windowSeconds != null ? windowSeconds : fallback;
    }
}
