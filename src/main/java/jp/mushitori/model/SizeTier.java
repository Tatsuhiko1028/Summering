package jp.mushitori.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * サイズの段階（大きさランク）1件。
 *
 * <p>config.yml の {@code sizes} セクションで、小さい順に7段階を定義する想定です。
 * 例: 最もちいさい・とてもちいさい・ちいさい・ふつう・おおきい・とてもおおきい・最もおおきい。
 * 並び順が「小さい→大きい」になっている前提です。道具の大物ボーナス（{@code size-bonus}）は
 * 符号付きの値で、正なら「より大きい」方向へ、負なら「より小さい」方向へ、確率で1段階だけ
 * 寄せます（{@link Table#shift(SizeTier, double)}）。</p>
 *
 * <p>両端（最もちいさい／最もおおきい）の重みを極端に小さくしておけば、
 * 「ふつう ＜ ちいさい＝おおきい ＜ とてもちいさい＝とてもおおきい ＜ 最もちいさい＝最もおおきい」
 * のような出現確率のカーブは、config側の重み設定だけで表現できます。</p>
 */
public record SizeTier(String key, String name, double weight, double priceMult, String legendaryName) {

    /** {@code legendaryName} が未設定の場合の通常コンストラクタ（互換用）。 */
    public SizeTier(String key, String name, double weight, double priceMult) {
        this(key, name, weight, priceMult, null);
    }

    /** 表示名。{@code preferLegendary} が true かつ legendary-name が設定されていれば、そちらを使う。 */
    public String displayName(boolean preferLegendary) {
        if (preferLegendary && legendaryName != null && !legendaryName.isBlank()) {
            return legendaryName;
        }
        return name;
    }

    /** config.yml の sizes セクションから読み込んだテーブル。 */
    public static final class Table {
        private final Map<String, SizeTier> byKey = new LinkedHashMap<>();
        private final List<SizeTier> ordered = new ArrayList<>();
        private double totalWeight;

        public static Table load(ConfigurationSection section) {
            Table table = new Table();
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    ConfigurationSection s = section.getConfigurationSection(key);
                    if (s == null) continue;
                    String legendaryName = s.getString("legendary-name", null);
                    SizeTier t = new SizeTier(
                            key,
                            s.getString("name", key),
                            s.getDouble("weight", 1.0),
                            s.getDouble("price-mult", 1.0),
                            (legendaryName == null || legendaryName.isBlank()) ? null : legendaryName);
                    table.add(t);
                }
            }
            if (table.ordered.isEmpty()) {
                table.add(new SizeTier("normal", "ふつう", 1.0, 1.0));
            }
            return table;
        }

        /**
         * 既定のテーブル（{@code base}、sizesセクション）を土台に、tierキーごとの
         * weightだけをテンプレートの値で上書きした、名前付きサイズ分布テーブルを作る。
         * テンプレート側に無いtierキーは、baseのweightをそのまま使う（部分的な上書きも可）。
         * name・price-mult・legendary-nameはbase側からそのまま引き継ぐ（テンプレートでは変えない）。
         */
        public static Table loadTemplate(Table base, ConfigurationSection templateSection) {
            Table table = new Table();
            for (SizeTier baseTier : base.all()) {
                double weight = templateSection != null && templateSection.contains(baseTier.key())
                        ? templateSection.getDouble(baseTier.key())
                        : baseTier.weight();
                table.add(new SizeTier(baseTier.key(), baseTier.name(), weight,
                        baseTier.priceMult(), baseTier.legendaryName()));
            }
            if (table.ordered.isEmpty()) {
                table.add(new SizeTier("normal", "ふつう", 1.0, 1.0));
            }
            return table;
        }

        private void add(SizeTier t) {
            byKey.put(t.key(), t);
            ordered.add(t);
            totalWeight += Math.max(0.0, t.weight());
        }

        public SizeTier get(String key) {
            SizeTier t = key == null ? null : byKey.get(key);
            return t != null ? t : ordered.get(ordered.size() / 2);
        }

        public List<SizeTier> all() {
            return List.copyOf(ordered);
        }

        public int indexOf(SizeTier tier) {
            return ordered.indexOf(tier);
        }

        public int size() {
            return ordered.size();
        }

        public double totalWeight() {
            return totalWeight;
        }

        /** 「ふつう」（中央）からの距離。0が中央、離れるほど大きい値になる。 */
        public int distanceFromCenter(SizeTier tier) {
            int idx = indexOf(tier);
            if (idx < 0) return 0;
            int center = ordered.size() / 2;
            return Math.abs(idx - center);
        }

        /** 中央から最も離れた段階（最も〜）までの距離。 */
        public int maxDistance() {
            return ordered.size() / 2;
        }

        /** 重み付き抽選（道具ボーナスなし）。 */
        public SizeTier rollBase() {
            double n = ThreadLocalRandom.current().nextDouble() * totalWeight;
            SizeTier picked = ordered.get(0);
            for (SizeTier t : ordered) {
                n -= Math.max(0.0, t.weight());
                if (n <= 0) {
                    picked = t;
                    break;
                }
            }
            return picked;
        }

        /** 道具の大物ボーナスぶん、確率で1段階「大きい」方向へ引き上げる。 */
        public SizeTier upgrade(SizeTier current, double bonus) {
            if (current == null) return ordered.get(ordered.size() / 2);
            if (bonus <= 0 || ThreadLocalRandom.current().nextDouble() >= bonus) {
                return current;
            }
            int idx = ordered.indexOf(current);
            if (idx >= 0 && idx + 1 < ordered.size()) {
                return ordered.get(idx + 1);
            }
            return current;
        }

        /** 道具のボーナスぶん、確率で1段階「小さい」方向へ引き下げる。 */
        public SizeTier downgrade(SizeTier current, double bonus) {
            if (current == null) return ordered.get(ordered.size() / 2);
            if (bonus <= 0 || ThreadLocalRandom.current().nextDouble() >= bonus) {
                return current;
            }
            int idx = ordered.indexOf(current);
            if (idx > 0) {
                return ordered.get(idx - 1);
            }
            return current;
        }

        /**
         * 符号付きボーナスで、大きい方向にも小さい方向にも1段階寄せる。
         * 正なら「大きい」方向（{@link #upgrade}）、負なら「小さい」方向（{@link #downgrade}）。
         */
        public SizeTier shift(SizeTier current, double signedBonus) {
            if (signedBonus > 0) return upgrade(current, signedBonus);
            if (signedBonus < 0) return downgrade(current, -signedBonus);
            return current;
        }
    }
}
