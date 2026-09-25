package jp.mushitori;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * PersistentDataContainer で使うキー。
 *
 * <p>名前空間は "summering"（リソースパックと同じ名前空間）に統一しています。
 * プラグイン自体の名前（mushitori）ではなく固定の名前空間を使うため、
 * {@link org.bukkit.NamespacedKey#NamespacedKey(String, String)} で組み立てています。</p>
 */
public final class Keys {

    private static final String NAMESPACE = "summering";

    public static NamespacedKey CREATURE_ID;
    public static NamespacedKey SIZE_CM;
    /** 最終決定後のサイズ段階（ちいさい・ふつう・おおきい 等）のキー。 */
    public static NamespacedKey SIZE_TIER;
    /** 個体が生成された時点の「素の」サイズ段階（道具ボーナス適用前）。 */
    public static NamespacedKey BASE_SIZE_TIER;
    /** 個体が生成された時点で焼き付ける実測cm（見た目のscale計算にも使う）。
     *  道具ボーナスでサイズ段階が変わらなければ、捕まえたときの表示にもこの値を
     *  そのまま使い、見た目と表示サイズが食い違わないようにする。 */
    public static NamespacedKey BASE_SIZE_CM;
    public static NamespacedKey RARITY;
    public static NamespacedKey CAUGHT_AT;
    public static NamespacedKey CATCHER_UUID;
    public static NamespacedKey CATCHER_NAME;
    public static NamespacedKey REGISTERED_BY;
    public static NamespacedKey REGISTERED_NAME;

    public static NamespacedKey NET_ID;
    public static NamespacedKey ROD_ID;
    public static NamespacedKey GEAR_ESCAPE_MODIFIER;
    public static NamespacedKey GEAR_SIZE_BONUS;
    public static NamespacedKey GEAR_RANGE;
    public static NamespacedKey GEAR_TAGS;
    public static NamespacedKey GEAR_BYPASS_CATCH_RESTRICTION;
    public static NamespacedKey GUIDE;
    public static NamespacedKey MONEY_DENOMINATION;
    public static NamespacedKey MONEY_TYPE;
    public static NamespacedKey CAGE_ID;
    public static NamespacedKey CAGE_CONTENTS;
    public static NamespacedKey CAGE_PLACEHOLDER;
    public static NamespacedKey PURSE_ID;
    public static NamespacedKey PURSE_CONTENTS;
    public static NamespacedKey PURSE_PLACEHOLDER;
    public static NamespacedKey MARKER_ID;
    public static NamespacedKey REQUIRED_TAGS_OVERRIDE;
    public static NamespacedKey FLYING_OVERRIDE;
    public static NamespacedKey ESCAPE_CHANCE_OVERRIDE;
    public static NamespacedKey ESCAPE_DESPAWNS_OVERRIDE;
    public static NamespacedKey APPROACH_OVERRIDE;
    public static NamespacedKey SUPPRESS_HOSTILITY_OVERRIDE;
    public static NamespacedKey ALLOW_BARE_HAND_OVERRIDE;
    public static NamespacedKey ALLOW_NET_OVERRIDE;
    public static NamespacedKey MARKER_WAND;
    public static NamespacedKey TRADER;
    public static NamespacedKey TRADE_PRICE_PREVIEW;
    public static NamespacedKey SHOWCASE;

    private Keys() {
    }

    static void init(Plugin plugin) {
        CREATURE_ID = key("creature_id");
        SIZE_CM = key("size_cm");
        SIZE_TIER = key("size_tier");
        BASE_SIZE_TIER = key("base_size_tier");
        BASE_SIZE_CM = key("base_size_cm");
        RARITY = key("rarity");
        CAUGHT_AT = key("caught_at");
        CATCHER_UUID = key("catcher_uuid");
        CATCHER_NAME = key("catcher_name");
        REGISTERED_BY = key("registered_by");
        REGISTERED_NAME = key("registered_name");
        NET_ID = key("net_id");
        ROD_ID = key("rod_id");
        GEAR_ESCAPE_MODIFIER = key("gear_escape_modifier");
        GEAR_SIZE_BONUS = key("gear_size_bonus");
        GEAR_RANGE = key("gear_range");
        GEAR_TAGS = key("gear_tags");
        GEAR_BYPASS_CATCH_RESTRICTION = key("gear_bypass_catch_restriction");
        GUIDE = key("guide");
        MONEY_DENOMINATION = key("money_denomination");
        MONEY_TYPE = key("money_type");
        CAGE_ID = key("cage_id");
        CAGE_CONTENTS = key("cage_contents");
        CAGE_PLACEHOLDER = key("cage_placeholder");
        PURSE_ID = key("purse_id");
        PURSE_CONTENTS = key("purse_contents");
        PURSE_PLACEHOLDER = key("purse_placeholder");
        MARKER_ID = key("marker_id");
        REQUIRED_TAGS_OVERRIDE = key("required_tags_override");
        FLYING_OVERRIDE = key("flying_override");
        ESCAPE_CHANCE_OVERRIDE = key("escape_chance_override");
        ESCAPE_DESPAWNS_OVERRIDE = key("escape_despawns_override");
        APPROACH_OVERRIDE = key("approach_override");
        SUPPRESS_HOSTILITY_OVERRIDE = key("suppress_hostility_override");
        ALLOW_BARE_HAND_OVERRIDE = key("allow_bare_hand_override");
        ALLOW_NET_OVERRIDE = key("allow_net_override");
        MARKER_WAND = key("marker_wand");
        TRADER = key("trader");
        TRADE_PRICE_PREVIEW = key("trade_price_preview");
        SHOWCASE = key("showcase");
    }

    private static NamespacedKey key(String key) {
        return new NamespacedKey(NAMESPACE, key);
    }
}
