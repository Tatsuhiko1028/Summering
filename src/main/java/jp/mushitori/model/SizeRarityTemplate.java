package jp.mushitori.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * サイズ段階が「ふつう」から何マス離れているか（distance）に応じて、
 * ベースレア度から何段階繰り上げるかを定義するテンプレート。
 *
 * <p>生物（creatures.yml）は {@code size-rarity-template} でこのテンプレートの名前を
 * 指定して使います。省略した場合は config.yml の
 * {@code catching.default-size-rarity-template} が使われます。</p>
 *
 * <p>最高レア度（レア度テーブルの一番最後の項目）への到達は特別扱いです。
 * {@code top-tier-min-base} で指定したレア度キー以上をベースに持つ生物が、
 * （{@code top-tier-requires-max-distance} が true なら）中心から最も離れたサイズ段階
 * （最も〜）になったときだけ、実際に最高レア度へ到達できます。それ以外は、
 * 計算上は最高レア度に届いても、ひとつ下の段階に留まります。</p>
 */
public record SizeRarityTemplate(
        String name,
        Map<Integer, Integer> stepsByDistance,
        String topTierMinBaseKey,
        boolean topTierRequiresMaxDistance
) {
    /** 汎用の既定テンプレート（config.ymlが読めなかった場合の保険）。 */
    public static final SizeRarityTemplate FALLBACK = new SizeRarityTemplate(
            "fallback",
            Map.of(0, 0, 1, 0, 2, 1, 3, 2),
            "R",
            true
    );

    public int stepsFor(int distance) {
        return stepsByDistance.getOrDefault(distance, 0);
    }

    public static SizeRarityTemplate load(String name, ConfigurationSection section) {
        Map<Integer, Integer> steps = new LinkedHashMap<>();
        ConfigurationSection stepsSection = section.getConfigurationSection("steps");
        if (stepsSection != null) {
            for (String key : stepsSection.getKeys(false)) {
                try {
                    steps.put(Integer.parseInt(key.trim()), stepsSection.getInt(key));
                } catch (NumberFormatException ignored) {
                    // 数値以外のキーは無視
                }
            }
        }
        if (steps.isEmpty()) {
            steps.putAll(FALLBACK.stepsByDistance());
        }
        String topMin = section.getString("top-tier-min-base", FALLBACK.topTierMinBaseKey());
        boolean requiresMax = section.getBoolean("top-tier-requires-max-distance", true);
        return new SizeRarityTemplate(name, Map.copyOf(steps), topMin, requiresMax);
    }
}
