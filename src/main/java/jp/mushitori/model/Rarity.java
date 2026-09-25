package jp.mushitori.model;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** レア度1件。 */
public record Rarity(String key, String name, double weight, double priceMult, double sizeBias, TextColor color) {

    /** config.yml の rarities セクションから読み込んだテーブル。 */
    public static final class Table {
        private final Map<String, Rarity> byKey = new LinkedHashMap<>();
        private final List<Rarity> ordered = new ArrayList<>();
        private double totalWeight;

        public static Table load(ConfigurationSection section) {
            Table table = new Table();
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    ConfigurationSection s = section.getConfigurationSection(key);
                    if (s == null) continue;
                    TextColor color = NamedTextColor.NAMES.value(
                            s.getString("color", "white").toLowerCase(Locale.ROOT));
                    Rarity r = new Rarity(
                            key,
                            s.getString("name", key),
                            s.getDouble("weight", 1.0),
                            s.getDouble("price-mult", 1.0),
                            s.getDouble("size-bias", 0.0),
                            color == null ? NamedTextColor.WHITE : color);
                    table.add(r);
                }
            }
            if (table.ordered.isEmpty()) {
                table.add(new Rarity("N", "ノーマル", 1.0, 1.0, 0.0, NamedTextColor.WHITE));
            }
            return table;
        }

        private void add(Rarity r) {
            byKey.put(r.key(), r);
            ordered.add(r);
            totalWeight += Math.max(0.0, r.weight());
        }

        public Rarity get(String key) {
            Rarity r = key == null ? null : byKey.get(key);
            return r != null ? r : ordered.get(0);
        }

        public List<Rarity> all() {
            return List.copyOf(ordered);
        }

        /** 重み付き抽選（道具ボーナスなし）。 */
        public Rarity rollBase() {
            double n = ThreadLocalRandom.current().nextDouble() * totalWeight;
            Rarity picked = ordered.get(0);
            for (Rarity r : ordered) {
                n -= Math.max(0.0, r.weight());
                if (n <= 0) {
                    picked = r;
                    break;
                }
            }
            return picked;
        }

        /** 道具ボーナスぶん、確率で1段階（configの並び順で次）引き上げる。 */
        public Rarity upgrade(Rarity current, double bonus) {
            if (current == null) return ordered.get(0);
            if (bonus <= 0 || ThreadLocalRandom.current().nextDouble() >= bonus) {
                return current;
            }
            int idx = ordered.indexOf(current);
            if (idx >= 0 && idx + 1 < ordered.size()) {
                return ordered.get(idx + 1);
            }
            return current;
        }

        /** 重み付き抽選＋道具ボーナスによる引き上げをまとめて行う便利メソッド。 */
        public Rarity roll(double bonus) {
            return upgrade(rollBase(), bonus);
        }
    }
}
