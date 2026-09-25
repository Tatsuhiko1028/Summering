package jp.mushitori.model;

import org.bukkit.DyeColor;
import org.bukkit.entity.TropicalFish;
import org.jetbrains.annotations.Nullable;

/**
 * {@code entity: TROPICAL_FISH} の生物向けに、模様・体色・模様色を指定するための設定。
 * どの項目も省略可（null）で、省略した項目はバニラのランダムな見た目のままになります。
 */
public record TropicalFishVariant(
        @Nullable TropicalFish.Pattern pattern,
        @Nullable DyeColor bodyColor,
        @Nullable DyeColor patternColor
) {
    public boolean isEmpty() {
        return pattern == null && bodyColor == null && patternColor == null;
    }
}
