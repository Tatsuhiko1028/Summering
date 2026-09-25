package jp.mushitori.model;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/** いきものの分類。 */
public enum Category {
    BUG("むし", "とった虫", Material.LIME_DYE, NamedTextColor.GREEN),
    FISH("さかな", "釣った魚", Material.LIGHT_BLUE_DYE, NamedTextColor.AQUA);

    private final String displayName;
    private final String caughtLabel;
    private final Material icon;
    private final TextColor color;

    Category(String displayName, String caughtLabel, Material icon, TextColor color) {
        this.displayName = displayName;
        this.caughtLabel = caughtLabel;
        this.icon = icon;
        this.color = color;
    }

    /** 図鑑メニューに出す名前。 */
    public String displayName() {
        return displayName;
    }

    /** 「○○が とった虫」の「とった虫」部分。 */
    public String caughtLabel() {
        return caughtLabel;
    }

    public Material icon() {
        return icon;
    }

    public TextColor color() {
        return color;
    }

    public boolean isFish() {
        return this != BUG;
    }

    public static Category parse(String raw, Category fallback) {
        if (raw == null) return fallback;
        try {
            return Category.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
