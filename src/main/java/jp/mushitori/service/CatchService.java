package jp.mushitori.service;

import jp.mushitori.Keys;
import jp.mushitori.model.CatchData;
import jp.mushitori.model.Creature;
import jp.mushitori.model.Rarity;
import jp.mushitori.model.SizeRarityTemplate;
import jp.mushitori.model.SizeTier;
import jp.mushitori.registry.CreatureRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * 「捕まえた」処理の中心。
 * 虫取り網・釣り・コマンドなど、どこから捕まえてもここを通します。
 *
 * <p>レア度・サイズ段階・実測cmの決め方：</p>
 * <ol>
 *   <li>生物には固定の「ベースレア度」（{@link Creature#baseRarityKey()}）があります。
 *       ランダム抽選ではありません</li>
 *   <li>個体が生まれた（湧いた）時点で「素」のサイズ段階と、見た目のscale計算にも使う
 *       実測cmを1回だけ抽選し、エンティティのPDCに焼き付けます
 *       （{@link #rollBaseTraits(Creature)} / {@link #bakeTraits(Entity, Creature)}）</li>
 *   <li>実際に捕まえる瞬間、道具（網・竿）の大物ボーナスに応じて最終サイズ・最終レア度を
 *       まとめて確定します（{@link #resolveCatch(Entity, Creature, RolledTraits, double, boolean)}）。
 *       大物ボーナスは、サイズ段階を丸ごとずらす方式でも、実測cmに倍率を掛ける方式でもなく、
 *       <b>「size-max - size-min」の幅に、ボーナス÷2を掛けた分だけ実測cmに加算する</b>方式
 *       です。生物ごとの幅（蝶のように幅が狭い虫、魚のように幅が広い生物）に関わらず、
 *       同じボーナス値なら常に同じ「割合」だけ動きます（幅の狭い生物ほど、倍率方式だと
 *       段階が一気に飛びやすくなってしまう問題があったための変更です）。ベースの個体が
 *       どの段階（ちいさい側／おおきい側）であっても、常にボーナスの方向へ加算されます
 *       （正のボーナスなら常に大きく、負のボーナスなら常に小さく。「ふつう」より
 *       小さい個体に大物ボーナスをかけて、結果的にレア度が下がることもあり得ます）。
 *       ただし、ベースの時点で既に最高レア度（でんせつサイズ。素の時点で
 *       最もちいさい／最もおおきいだったもの）に達している場合は、これ以上動かしません。
 *       加算した結果がsize-max／size-minを超える場合は、size-max×1.0〜1.05倍
 *       （size-minなら×0.95〜1.0倍）のランダムな値に丸めます。「最もおおきい」
 *       （素の抽選だけで届いた、でんせつ級のもの）はsize-max×1.05〜1.2倍になるため、
 *       道具の力だけではその範囲に並ばないよう、あえて控えめな範囲にしています。
 *       加算したあとの段階・レア度は、その実測cmから逆算して決め直します。</li>
 *   <li>その最終サイズ段階が「ふつう」からどれだけ離れているかに応じて、ベースレア度から
 *       何段階か繰り上げ、最終レア度を決めます（サイズ→レア度の「テンプレート」、
 *       {@link SizeRarityTemplate}）。ただし、ベースの時点で既に最高レア度に達している
 *       場合はそれ以上大きくせず、大物ボーナスが「きっかけ」で最高レア度に到達することも
 *       ありません（最高レア度は、ベースの抽選だけで届いた場合にのみ到達します）</li>
 * </ol>
 *
 * <p>バニラの釣りのように「捕まえる瞬間まで個体そのものが存在しない」場合は、
 * {@link #createCatchItem(Creature, Player, double)} が上記をまとめてその場で行います。</p>
 */
public final class CatchService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault());

    /** 決定した（または途中経過の）レア度・サイズ段階のペア。 */
    public record RolledTraits(String rarityKey, String sizeTierKey) {
    }

    private final CreatureRegistry creatures;
    private Rarity.Table rarities;
    private SizeTier.Table sizeTiers;
    /** マーカーで選べる、名前付きのサイズ分布テンプレート（weightだけをtierごとに上書きしたテーブル）。 */
    private final Map<String, SizeTier.Table> sizeDistributionTemplates = new LinkedHashMap<>();
    private Map<String, SizeRarityTemplate> sizeRarityTemplates = Map.of();
    private String defaultSizeRarityTemplate = "default";
    private boolean scaleEnabled = true;
    private double scaleExtremeMaxMultiplierMin = 1.05;
    private double scaleExtremeMaxMultiplierMax = 1.2;
    private double scaleExtremeMinMultiplierMin = 0.8;
    private double scaleExtremeMinMultiplierMax = 0.95;
    /** 「比率」（同じ種の中での個体差）だけにかけるクランプ。base-scaleを掛ける前の値。 */
    private double ratioClampMin = 0.5;
    private double ratioClampMax = 1.6;
    /** base-scaleを掛けた最終結果に対する、ごく緩い安全策としてのクランプ（壊れた値の防止用）。 */
    private static final double FINAL_SCALE_SAFETY_MIN = 0.0625;
    private static final double FINAL_SCALE_SAFETY_MAX = 16.0;
    private boolean strictVisualConsistency = false;
    private double sizeContinuousPriceBonus = 0.5;
    private boolean debug = false;

    private final Logger logger;

    public CatchService(Logger logger, CreatureRegistry creatures, Rarity.Table rarities, SizeTier.Table sizeTiers) {
        this.logger = logger;
        this.creatures = creatures;
        this.rarities = rarities;
        this.sizeTiers = sizeTiers;
    }

    public void setRarities(Rarity.Table rarities) {
        this.rarities = rarities;
    }

    public void setSizeTiers(SizeTier.Table sizeTiers) {
        this.sizeTiers = sizeTiers;
    }

    /**
     * config.yml の size-distribution-templates セクションを読み込む。各テンプレートは
     * tierキーごとのweightだけを上書きしたテーブルで、name・price-mult・legendary-nameは
     * 既定のsizeTiers（sizesセクション）からそのまま引き継ぎます。
     */
    public void setSizeDistributionTemplates(@Nullable org.bukkit.configuration.ConfigurationSection section) {
        sizeDistributionTemplates.clear();
        if (section == null || sizeTiers == null) return;
        for (String name : section.getKeys(false)) {
            var templateSection = section.getConfigurationSection(name);
            sizeDistributionTemplates.put(name, SizeTier.Table.loadTemplate(sizeTiers, templateSection));
        }
    }

    /** そのテンプレート名のサイズ分布テーブルを返す。名前が無い／見つからなければ既定（sizesセクション）。 */
    public SizeTier.Table sizeTiersFor(@Nullable String templateName) {
        if (templateName == null || templateName.isBlank()) return sizeTiers;
        SizeTier.Table t = sizeDistributionTemplates.get(templateName);
        return t != null ? t : sizeTiers;
    }

    public List<String> sizeDistributionTemplateNames() {
        return new ArrayList<>(sizeDistributionTemplates.keySet());
    }

    /** サイズ→レア度繰り上げテンプレート（size-rarity-templates）の名前一覧。マーカーの上書きUIで使う。 */
    public List<String> sizeRarityTemplateNames() {
        return new ArrayList<>(sizeRarityTemplates.keySet());
    }

    /** サイズ→レア度繰り上げのテンプレート一式と、既定で使うテンプレート名。 */
    public void setSizeRarityTemplates(Map<String, SizeRarityTemplate> templates, String defaultTemplateName) {
        this.sizeRarityTemplates = templates == null ? Map.of() : Map.copyOf(templates);
        this.defaultSizeRarityTemplate = defaultTemplateName == null || defaultTemplateName.isBlank()
                ? "default" : defaultTemplateName;
    }

    /** true にすると、scale適用などの内部処理をコンソールログに出す（動作確認用）。 */
    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    /**
     * true にすると、個体が実在する捕獲経路（虫取り網・寄ってくる釣り）では、
     * 道具の大物ボーナスを一切効かせなくなります（見た目のサイズ＝捕まえたときの数値を
     * 厳密に一致させたい場合に使います）。バニラ釣り（個体が存在しない通常釣り）は、
     * この設定に関係なく引き続き大物ボーナスが効きます（そもそも「見た目」が無いため）。
     * 既定は false（従来通り、実在する個体にも大物ボーナスが効きます）。
     */
    public void setStrictVisualConsistency(boolean strictVisualConsistency) {
        this.strictVisualConsistency = strictVisualConsistency;
    }

    /**
     * 売値の「段階内での連続補正」の強さ。0なら無効（従来通り、段階のprice-multだけで
     * 価格が決まります）。0.5なら、段階内で最も極端な個体（size-min/size-maxちょうど）は
     * 中心（ふつう寄り）の個体よりおよそ1.5倍高く売れます。
     */
    public void setSizeContinuousPriceBonus(double sizeContinuousPriceBonus) {
        this.sizeContinuousPriceBonus = Math.max(0.0, sizeContinuousPriceBonus);
    }

    /**
     * 見た目のスケール（Attribute.SCALE）の設定。
     *
     * @param extremeMaxMultiplierMin 「最もおおきい」段階のとき、最後に掛ける倍率の下限（既定1.05）
     * @param extremeMaxMultiplierMax 同上限（既定1.2）。この範囲でその都度ランダムに1つ選びます
     * @param extremeMinMultiplierMin 「最もちいさい」段階のとき、最後に掛ける倍率の下限（既定0.8）
     * @param extremeMinMultiplierMax 同上限（既定0.95）。この範囲でその都度ランダムに1つ選びます
     * @param clampMin 「比率」（個体差）の計算結果がこれを下回っても、この値に丸める（既定0.5）
     * @param clampMax 同上限（既定1.6）。この2つは、base-scaleを掛ける前の「個体差」部分だけに
     *                 かかります（base-scaleを掛けた後の最終結果はここでは丸めません）
     */
    public void setScaleConfig(boolean enabled,
                               double extremeMaxMultiplierMin, double extremeMaxMultiplierMax,
                               double extremeMinMultiplierMin, double extremeMinMultiplierMax,
                               double clampMin, double clampMax) {
        this.scaleEnabled = enabled;
        this.scaleExtremeMaxMultiplierMin = extremeMaxMultiplierMin;
        this.scaleExtremeMaxMultiplierMax = Math.max(extremeMaxMultiplierMin, extremeMaxMultiplierMax);
        this.scaleExtremeMinMultiplierMin = Math.min(extremeMinMultiplierMin, extremeMinMultiplierMax);
        this.scaleExtremeMinMultiplierMax = extremeMinMultiplierMax;
        this.ratioClampMin = Math.max(0.01, clampMin);
        this.ratioClampMax = Math.max(this.ratioClampMin, clampMax);
    }

    /**
     * 「最もおおきい」段階に、その都度ランダムに選んで掛ける倍率。
     * config側でmin/maxの幅がほぼ無い（同じ値・逆転している等）場合でも、常に細かい
     * 小数点まで変動する値になるよう、最低限の幅（±1%）を保証しています。
     */
    private double rollExtremeMaxMultiplier() {
        double lo = scaleExtremeMaxMultiplierMin;
        double hi = Math.max(scaleExtremeMaxMultiplierMax, lo + 0.02);
        return lo + ThreadLocalRandom.current().nextDouble() * (hi - lo);
    }

    /** 「最もちいさい」段階に、その都度ランダムに選んで掛ける倍率。考え方は{@link #rollExtremeMaxMultiplier()}と同じ。 */
    private double rollExtremeMinMultiplier() {
        double hi = scaleExtremeMinMultiplierMax;
        double lo = Math.min(scaleExtremeMinMultiplierMin, hi - 0.02);
        return lo + ThreadLocalRandom.current().nextDouble() * (hi - lo);
    }

    /**
     * 個体のサイズ段階に応じて、見た目のスケール（Attribute.SCALE）を適用する。
     *
     * <p>計算式：与えられた実測cmと、生物の平均サイズ（(size-min + size-max) / 2）との比を
     * 取り（これを「比率」と呼びます）、まずこの比率だけを{@code ratioClampMin}〜
     * {@code ratioClampMax}の範囲に丸めます（size-min/size-maxの幅が広い生物で、
     * 個体差が極端になりすぎるのを防ぐため）。そのあとで{@code creature.baseScale()}を
     * 掛けます。<b>base-scaleを掛けたあとの最終結果は、ここでは丸めません</b>
     * （以前は最終結果まで丸めてしまっていたため、base-scaleを極端な値にしても
     * 反映されなかったり、base-scaleが小さい生物で個体差が完全に消えてしまったりする
     * 不具合がありました）。最終結果には、明らかに壊れた値だけを防ぐ、ごく緩い
     * 安全策のクランプのみをかけます。実測cm自体に、最も極端な段階での補正
     * （{@link #rollSizeCm(Creature, SizeTier)}）が既に反映されているため、
     * ここで改めて倍率を掛けることはしません（cm側とscale側での二重補正を防ぐため）。</p>
     *
     * <p><b>重要</b>：ここで渡す {@code sizeCm} は、捕まえたときに表示する実測cmと
     * 同じ値を使ってください（{@link #bakeTraits(Entity, Creature)} で焼き付けた値を
     * そのまま使うのが基本です）。ここだけ別に乱数を振ってしまうと、見た目のサイズと
     * 捕まえたときの表示サイズが食い違う原因になります。</p>
     *
     * <p>entityの種類によっては、そもそも Attribute.SCALE を持たず、何も起きないことがある。</p>
     */
    public void applyScale(Entity entity, Creature creature, double sizeCm, String sizeTierKey) {
        if (!scaleEnabled) return;
        if (!(entity instanceof LivingEntity living)) {
            if (debug) logger.info("[catching] scale未適用（LivingEntityではない）: " + creature.id());
            return;
        }
        AttributeInstance attr = living.getAttribute(Attribute.SCALE);
        if (attr == null) {
            if (debug) {
                logger.info("[catching] scale未適用（このentity種別はAttribute.SCALEを持っていません）: "
                        + creature.id() + " (" + entity.getType() + ")");
            }
            return;
        }

        double avgSize = (creature.sizeMin() + creature.sizeMax()) / 2.0;
        double ratio = avgSize > 0 ? sizeCm / avgSize : 1.0;
        // クランプは「比率」（同じ種の中での個体差）だけにかける。base-scaleを掛けた
        // 後の最終結果をクランプしてしまうと、base-scaleそのものの意図（生物ごとに
        // 見た目の基準サイズを変える）が飲み込まれてしまうため（以前の不具合）。
        double clampedRatio = Math.max(ratioClampMin, Math.min(ratioClampMax, ratio));
        double scale = clampedRatio * creature.baseScale();
        // 最終結果には、明らかに壊れた値（0・負数・極端に巨大）だけを防ぐ、ごく緩い
        // 安全策としてのクランプのみをかける（Attribute.SCALE自体がバニラ側で
        // 取り得る範囲に合わせた、十分広い範囲）。
        double clamped = Math.max(FINAL_SCALE_SAFETY_MIN, Math.min(FINAL_SCALE_SAFETY_MAX, scale));

        attr.setBaseValue(clamped);
        double readBack = attr.getBaseValue();
        if (debug) {
            logger.info("[catching] scale適用: " + creature.id() + " (" + entity.getType()
                    + " " + entity.getUniqueId() + ") "
                    + "tier=" + sizeTierKey + " sizeCm=" + sizeCm + " avgSize=" + avgSize + " ratio=" + ratio
                    + (ratio != clampedRatio ? "（比率クランプ後 " + clampedRatio + "）" : "")
                    + " base-scale=" + creature.baseScale()
                    + " -> " + scale + (scale != clamped ? "（安全策クランプ後 " + clamped + "）" : "")
                    + " / 設定直後の読み戻し値=" + readBack
                    + (Math.abs(readBack - clamped) > 0.0001 ? "  ★一致していません！他の何かが直後に上書きした可能性" : ""));
        }
    }

    /** 生物を問わない一覧表示用の共通レア度テーブル（レア度は生物ごとの個別テーブルを持ちません）。 */
    public Rarity.Table rarities(Creature creature) {
        return rarities;
    }

    public Rarity.Table rarities() {
        return rarities;
    }

    public SizeTier.Table sizeTiers() {
        return sizeTiers;
    }

    // ---- 抽選 ----

    /** 個体が生まれた時点の「素」の抽選。レア度は生物固定なので、ここではサイズ段階だけ抽選する。 */
    public RolledTraits rollBaseTraits(Creature creature) {
        return rollBaseTraits(creature, null);
    }

    /** サイズ分布テンプレート指定版。テンプレート名がnull・未定義なら、既定のsizesセクションを使う。 */
    public RolledTraits rollBaseTraits(Creature creature, @Nullable String sizeDistributionTemplate) {
        SizeTier.Table table = sizeTiersFor(sizeDistributionTemplate);
        SizeTier tier = table.rollBase();
        if (debug) {
            logger.info("[catching] 素のサイズ段階抽選: " + creature.id() + " -> " + tier.key()
                    + " (weight=" + tier.weight() + " / 全tierの合計weight=" + table.totalWeight()
                    + (sizeDistributionTemplate != null ? " / テンプレート=" + sizeDistributionTemplate : "") + ")");
        }
        return new RolledTraits(creature.baseRarityKey(), tier.key());
    }

    /** 最終的に確定した、レア度・サイズ段階・実測cmのセット。 */
    public record FinalCatch(String rarityKey, String sizeTierKey, double sizeCm) {
    }

    /**
     * 捕まえる瞬間、道具の大物ボーナスに応じて最終的な実測cm・サイズ段階・レア度を確定する。
     *
     * <p>大物ボーナスは、サイズ段階を問答無用でずらす方式でも、実測cmに倍率を掛ける方式でもなく、
     * <b>「size-max - size-min」の幅に、ボーナス÷2を掛けた分だけ実測cmに加算する</b>方式に
     * しています（例: size-min=6.5, size-max=12.0 の生物に size-bonus 1.0 なら、
     * 常に (12.0-6.5)×1.0÷2 ＝ +2.75cm）。倍率方式だと、size-min/size-maxの幅が狭い
     * 生物（蝶等）ほど、同じボーナス値でもサイズ段階が一気に何段も飛んでレア度が
     * 急変しやすいという問題があったため、生物ごとの幅に比例した「同じ割合」の変化に
     * なるよう変更しました。ボーナス1.0のとき、ちょうど平均サイズの個体が
     * size-max（またはsize-min）ちょうどに届くよう、÷2で調整してあります。
     * 加算したあとの段階・レア度は、その実測cmから逆算して決め直します。</p>
     *
     * <ul>
     *   <li>正（大物ボーナス）・負（小物ボーナス）とも、ベースの個体がどの段階（ちいさい側／
     *       おおきい側どちらでも）であっても、常にボーナスの方向へ加算されます
     *       （以前は「ベースが既にふつう以上／以下のときだけ」という制限がありましたが、
     *       幅に比例した加算方式になり変動量が予測しやすくなったため、撤廃しました）。</li>
     *   <li>実測cmは、生物の size-min〜size-max の範囲を超えません（範囲内に丸めます）。</li>
     *   <li>ベースの時点ですでに最高レア度（テーブルの最後の項目。でんせつサイズ＝素の時点で
     *       最もちいさい／最もおおきいだったもの）に達している場合は、それ以上大きく／
     *       小さくしません（最高レア度のものが、さらに極端になることはない）。</li>
     *   <li>サイズボーナスが「きっかけ」で最高レア度に到達するのは避けます（最高レア度は、
     *       道具の効果に関係なく、ベースの抽選だけで届いた場合にのみ到達を許可します）。</li>
     * </ul>
     *
     * @param entity    個体が実在するなら、そのエンティティ（湧いた時点の実測cmを読み出すため）。
     *                  個体が存在しない経路（バニラ釣り等）では null
     * @param base      {@link #rollBaseTraits(Creature)} で決めた「素」の特性
     * @param sizeBonus 道具の大物ボーナス（符号付き。正で大きく、負で小さく）
     * @param hasVisual その個体が実在するエンティティとして湧いていた（＝見た目のscaleが
     *                  すでに決まっている）かどうか。true かつ
     *                  {@link #setStrictVisualConsistency(boolean)} が有効な場合、
     *                  大物ボーナスは効きません（見た目と数値を一致させるため）。
     */
    public FinalCatch resolveCatch(@Nullable Entity entity, Creature creature, RolledTraits base,
                                   double sizeBonus, boolean hasVisual) {
        double effectiveSizeBonus = (strictVisualConsistency && hasVisual) ? 0.0 : sizeBonus;

        SizeTier baseTier = sizeTiers.get(base.sizeTierKey());
        String baseRarity = deriveRarity(creature, baseTier);
        boolean baseIsTop = isTopRarity(baseRarity);

        double baseCm;
        boolean usedBaked = false;
        if (entity != null) {
            Double baked = bakedSizeCmOf(entity);
            if (baked != null) {
                baseCm = baked;
                usedBaked = true;
            } else {
                baseCm = rollSizeCm(creature, baseTier);
            }
        } else {
            baseCm = rollSizeCm(creature, baseTier);
        }

        double finalCm = baseCm;
        SizeTier finalTier = baseTier;
        boolean bonusApplied = false;

        // 「ベースがふつう以上／以下のときだけ効く」という制限は撤廃しました（幅に比例した
        // 加算方式になり、変動量が予測しやすくなったため）。ベースの時点で既に最高レア度
        // （＝でんせつサイズ。素の時点で最もちいさい／最もおおきいだったもの）でなければ、
        // 常にボーナスの方向へ加算します。でんせつサイズのものだけは、これ以上動かしません。
        if (!baseIsTop && effectiveSizeBonus != 0) {
            // 実測cmに (1+bonus) 倍…ではなく、「size-max - size-min」の幅に bonus/2 を掛けた分だけ
            // 加算する方式にしている。生物ごとの幅（狭い虫・広い魚）に関わらず、同じ bonus 値なら
            // 常に同じ「割合」だけ動くようにするため（幅が狭い生物ほど、掛け算方式では段階が
            // 一気に飛びやすくなってしまう問題があった）。bonus=1.0 のとき、ちょうど平均サイズの
            // 個体が size-max（または size-min）ちょうどに届くよう、/2 で調整してある。
            double span = creature.sizeMax() - creature.sizeMin();
            double shift = span * effectiveSizeBonus / 2.0;
            double boosted = baseCm + shift;

            if (boosted > creature.sizeMax()) {
                // 道具の効果でsize-maxを超える場合、常に同じ数値にならないよう乱数を入れる。
                // 「最もおおきい」（素の抽選だけで届いた、でんせつ級のもの）は
                // size-max × 1.05〜1.2 の範囲になるため、それより必ず控えめになるよう、
                // ここは size-max × 1.0〜1.05 の範囲にとどめる（道具の力だけで、でんせつ級の
                // サイズに並んでしまわないようにするため）。
                finalCm = creature.sizeMax() * (1.0 + ThreadLocalRandom.current().nextDouble() * 0.05);
            } else if (boosted < creature.sizeMin()) {
                finalCm = creature.sizeMin() * (1.0 - ThreadLocalRandom.current().nextDouble() * 0.05);
            } else {
                finalCm = boosted;
            }
            finalTier = tierForCm(creature, finalCm);
            bonusApplied = true;
        }

        String finalRarity = deriveRarity(creature, finalTier);
        boolean cappedForTopRarity = false;
        if (!baseIsTop && isTopRarity(finalRarity)) {
            // サイズボーナスがきっかけで最高レア度に到達するのは避ける
            finalRarity = secondTopRarityKey();
            cappedForTopRarity = true;
        }

        if (debug) {
            logger.info("[catching] resolveCatch: " + creature.id()
                    + " base(tier=" + baseTier.key() + ", cm=" + baseCm + (usedBaked ? "[焼付]" : "[新規抽選]")
                    + ", rarity=" + baseRarity + (baseIsTop ? "[既に最高]" : "") + ")"
                    + " sizeBonus=" + sizeBonus + (effectiveSizeBonus != sizeBonus ? "(厳密一致モードで無効化)" : "")
                    + (bonusApplied ? " ボーナス適用あり" : " ボーナス適用なし")
                    + " -> final(tier=" + finalTier.key() + ", cm=" + round(finalCm)
                    + ", rarity=" + finalRarity + (cappedForTopRarity ? "[最高レア度到達を抑制]" : "") + ")");
        }

        return new FinalCatch(finalRarity, finalTier.key(), round(finalCm));
    }

    /** 実測cmが、生物の size-min〜size-max の中のどのサイズ段階に属するかを逆算する。 */
    private SizeTier tierForCm(Creature creature, double cm) {
        List<SizeTier> ordered = sizeTiers.all();
        int n = ordered.size();
        if (n <= 1) return ordered.isEmpty() ? sizeTiers.get(null) : ordered.get(0);
        double t = creature.sizeRatio(cm);
        int idx = (int) Math.floor(t * n);
        idx = Math.max(0, Math.min(n - 1, idx));
        return ordered.get(idx);
    }

    /** そのレア度キーが、レア度テーブルの一番最後（最高レア度）かどうか。 */
    private boolean isTopRarity(String rarityKey) {
        List<Rarity> ordered = rarities.all();
        if (ordered.isEmpty() || rarityKey == null) return false;
        return ordered.get(ordered.size() - 1).key().equals(rarityKey);
    }

    /** 最高レア度のひとつ下のキー（最高レア度への到達を見送るときに使う）。 */
    private String secondTopRarityKey() {
        List<Rarity> ordered = rarities.all();
        if (ordered.isEmpty()) return "";
        int idx = Math.max(0, ordered.size() - 2);
        return ordered.get(idx).key();
    }

    /** そのサイズ段階に応じて、生物のベースレア度から最終レア度を導く。 */
    private String deriveRarity(Creature creature, SizeTier finalTier) {
        List<Rarity> ordered = rarities.all();
        if (ordered.isEmpty()) return creature.baseRarityKey();

        int baseIdx = indexOfKey(ordered, creature.baseRarityKey());
        if (baseIdx < 0) baseIdx = 0;

        SizeRarityTemplate template = templateFor(creature);
        int distance = sizeTiers.distanceFromCenter(finalTier);
        int steps = template.stepsFor(distance);

        int topIdx = ordered.size() - 1;
        int naiveIdx = Math.min(baseIdx + steps, topIdx);

        if (naiveIdx >= topIdx && topIdx > 0) {
            int minBaseIdx = indexOfKey(ordered, template.topTierMinBaseKey());
            boolean baseIsHighEnough = minBaseIdx >= 0 && baseIdx >= minBaseIdx;
            boolean distanceOk = !template.topTierRequiresMaxDistance() || distance >= sizeTiers.maxDistance();
            if (!(baseIsHighEnough && distanceOk)) {
                naiveIdx = Math.max(0, topIdx - 1);
            }
        }
        return ordered.get(naiveIdx).key();
    }

    private SizeRarityTemplate templateFor(Creature creature) {
        String name = creature.sizeRarityTemplate();
        SizeRarityTemplate t = (name == null || name.isBlank()) ? null : sizeRarityTemplates.get(name);
        if (t != null) return t;
        t = sizeRarityTemplates.get(defaultSizeRarityTemplate);
        return t != null ? t : SizeRarityTemplate.FALLBACK;
    }

    private static int indexOfKey(List<Rarity> ordered, String key) {
        if (key == null) return -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).key().equals(key)) return i;
        }
        return -1;
    }

    // ---- 逃走判定 ----

    /**
     * 逃げられたかどうかを判定する。
     * 最終確率 = 生物の基準の逃げやすさ（{@link Creature#escapeChance()}） + 道具の escape-modifier
     * （0〜1の範囲にクランプ）。
     */
    public boolean rollEscape(Creature creature, double toolEscapeModifier) {
        return rollEscape(creature.escapeChance(), toolEscapeModifier);
    }

    /** マーカーの上書きなど、生物本来のescape-chanceとは別の値を使いたい場合のオーバーロード。 */
    public boolean rollEscape(double baseEscapeChance, double toolEscapeModifier) {
        double chance = Math.max(0.0, Math.min(1.0, baseEscapeChance + toolEscapeModifier));
        if (chance <= 0) return false;
        if (chance >= 1) return true;
        return ThreadLocalRandom.current().nextDouble() < chance;
    }

    /**
     * サイズ段階に応じた実測cmを抽選する。
     *
     * <p>最も極端な段階（一覧の最初＝最もちいさい／最後＝最もおおきい）のときだけ、
     * {@code size-min}／{@code size-max}そのものではなく、そこに
     * {@code catching.scale.extreme-min/max-multiplier-min/max} の範囲からその都度
     * ランダムに選んだ倍率を掛けた値を使います（既定でおおよそ0.80〜0.95倍／1.05〜1.2倍。
     * 「最も〜」は定義上のふつうの最大・最小を少し超える、特別な記録級の個体として
     * 扱う考え方です）。見た目のscaleは、この実測cmと平均サイズの比からそのまま
     * 計算されるため、ここで補正すればscale側にも自然に反映されます
     * （scale側で別途倍率を掛ける処理は廃止しました）。</p>
     */
    public double rollSizeCm(Creature creature, SizeTier tier) {
        List<SizeTier> ordered = sizeTiers.all();
        int index = Math.max(0, sizeTiers.indexOf(tier));
        int n = Math.max(1, ordered.size());

        double result;
        double appliedMultiplier = 1.0;
        if (index == 0) {
            appliedMultiplier = rollExtremeMinMultiplier();
            result = round(creature.sizeMin() * appliedMultiplier);
        } else if (index == n - 1) {
            appliedMultiplier = rollExtremeMaxMultiplier();
            result = round(creature.sizeMax() * appliedMultiplier);
        } else {
            double bandWidth = 1.0 / n;
            double bandStart = index * bandWidth;
            double t = bandStart + ThreadLocalRandom.current().nextDouble() * bandWidth;
            double size = creature.sizeMin() + (creature.sizeMax() - creature.sizeMin()) * t;
            result = round(size);
        }

        if (debug) {
            logger.info("[catching] 実測cm抽選: " + creature.id() + " tier=" + tier.key()
                    + " (index=" + index + "/" + (n - 1) + ", weight=" + tier.weight() + ") -> " + result + "cm"
                    + (appliedMultiplier != 1.0 ? " (極端倍率=" + appliedMultiplier + ")" : "")
                    + " (size-min=" + creature.sizeMin() + ", size-max=" + creature.sizeMax() + ")");
        }
        return result;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ---- エンティティへの焼き付け（/mushitori spawn 時） ----

    /**
     * 個体の「素」のサイズ段階と、見た目のscale計算にも使う実測cmを抽選し、
     * エンティティのPDCに焼き付ける。ここで焼き付けたcmは、道具ボーナスでサイズ段階が
     * 変わらなかった場合、捕まえたときの表示サイズにもそのまま使われます
     * （見た目と表示サイズを一致させるため）。
     */
    public RolledTraits bakeTraits(Entity entity, Creature creature) {
        return bakeTraits(entity, creature, null);
    }

    /** サイズ分布テンプレート指定版（マーカーの生物枠ごとの上書き用）。 */
    public RolledTraits bakeTraits(Entity entity, Creature creature, @Nullable String sizeDistributionTemplate) {
        RolledTraits base = rollBaseTraits(creature, sizeDistributionTemplate);
        SizeTier.Table table = sizeTiersFor(sizeDistributionTemplate);
        double baseSizeCm = rollSizeCm(creature, table.get(base.sizeTierKey()));
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(Keys.BASE_SIZE_TIER, PersistentDataType.STRING, base.sizeTierKey());
        pdc.set(Keys.BASE_SIZE_CM, PersistentDataType.DOUBLE, baseSizeCm);
        return base;
    }

    /** エンティティに焼き付けられた「素」のサイズ段階を読む（未設定なら null）。 */
    @Nullable
    public RolledTraits baseTraitsOfEntity(Entity entity, Creature creature) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        String sizeTierKey = pdc.get(Keys.BASE_SIZE_TIER, PersistentDataType.STRING);
        if (sizeTierKey == null) return null;
        return new RolledTraits(creature.baseRarityKey(), sizeTierKey);
    }

    /** エンティティに焼き付けられた実測cmを読む（未設定なら null＝古いデータ等への保険）。 */
    @Nullable
    public Double bakedSizeCmOf(Entity entity) {
        return entity.getPersistentDataContainer().get(Keys.BASE_SIZE_CM, PersistentDataType.DOUBLE);
    }

    // ---- 価格 ----

    /**
     * 売値。レア度・サイズ段階それぞれの price-mult に加え、実測cmによる連続的な補正も
     * かけ合わせる。
     *
     * <p>同じサイズ段階の中でも、実測cmが「ふつう」（中心）から離れているほど
     * （＝段階内でもより極端な個体ほど）価値が上がるようにしています。例えば同じ
     * 「おおきい」でも、その段階の中でより大きい個体のほうが高く売れ、同じ「ちいさい」
     * でも、その段階の中でより小さい個体のほうが高く売れます。段階そのものが変わる
     * ほどの差ではないため、既存のサイズ段階のprice-mult（段差）とは別に、なめらかに
     * 効きます。</p>
     */
    public int price(Creature creature, String sizeTierKey, String rarityKey, double sizeCm) {
        Rarity r = rarities.get(rarityKey);
        SizeTier t = sizeTiers.get(sizeTierKey);
        double base = creature.basePrice() * r.priceMult() * t.priceMult();

        // 0.0（size-min）〜1.0（size-max）の位置から、中心（0.5）でのずれを求める。
        // 「最も〜」段階は実測cmがsize-min/size-maxを少し超えることがあるため、
        // ここでは Creature#sizeRatio() のように 0〜1 にクランプしない
        // （クランプしてしまうと、「最もおおきい」同士の細かい大小差が価格に
        // 反映されなくなってしまうため）。
        double span = Math.max(0.0001, creature.sizeMax() - creature.sizeMin());
        double unclampedRatio = (sizeCm - creature.sizeMin()) / span;
        double extremity = Math.abs(unclampedRatio - 0.5) * 2.0;
        double continuousMult = 1.0 + extremity * sizeContinuousPriceBonus;

        return Math.max(1, (int) Math.round(base * continuousMult));
    }

    /** そのアイテムがいきものの捕獲アイテムなら、いくらで売れるかを返す（それ以外は0）。 */
    public int priceOf(@Nullable ItemStack item) {
        CatchData data = read(item);
        if (data == null) return 0;
        Creature creature = creatures.get(data.creatureId());
        if (creature == null) return 0;
        return price(creature, data.sizeTierKey(), data.rarityKey(), data.sizeCm());
    }

    // ---- アイテム生成 ----

    /**
     * 捕まえたいきものをアイテムにする（便利メソッド）。
     * バニラ釣りや管理者コマンドなど、個体そのものが存在しない場合に、
     * 「素の抽選」と「道具ボーナスによる最終決定」をまとめてその場で行います。
     *
     * @param sizeBonus 道具の大物ボーナス
     */
    public ItemStack createCatchItem(Creature creature, Player catcher, double sizeBonus) {
        RolledTraits base = rollBaseTraits(creature);
        FinalCatch resolved = resolveCatch(null, creature, base, sizeBonus, false);
        return createCatchItem(creature, catcher,
                new RolledTraits(resolved.rarityKey(), resolved.sizeTierKey()), resolved.sizeCm());
    }

    /** すでに決定した最終特性（{@link RolledTraits}）から、捕獲アイテムを作る（実測cmはその場で新規抽選）。 */
    public ItemStack createCatchItem(Creature creature, Player catcher, RolledTraits result) {
        double sizeCm = rollSizeCm(creature, sizeTiers.get(result.sizeTierKey()));
        return createCatchItem(creature, catcher, result, sizeCm);
    }

    /**
     * 実測cmを指定して、捕獲アイテムを作る。
     *
     * <p>個体（エンティティ）がすでに存在する捕獲経路（虫取り網・寄ってくる釣り）では、
     * {@link #resolveCatch(Entity, Creature, RolledTraits, double, boolean)} の結果を
     * そのままこちらに渡すのが基本です。</p>
     */
    public ItemStack createCatchItem(Creature creature, Player catcher, RolledTraits result, double sizeCm) {
        CatchData data = new CatchData(creature.id(), sizeCm, result.sizeTierKey(), result.rarityKey(),
                System.currentTimeMillis(), catcher.getUniqueId(), catcher.getName(), null, null);
        return createCatchItem(creature, data);
    }

    public ItemStack createCatchItem(Creature creature, CatchData data) {
        ItemStack item = new ItemStack(creature.material());
        item.editMeta(meta -> applyMeta(meta, creature, data));
        return item;
    }

    private void applyMeta(ItemMeta meta, Creature creature, CatchData data) {
        Rarity rarity = rarities.get(data.rarityKey());
        SizeTier tier = sizeTiers.get(data.sizeTierKey());
        String sizeDisplayName = tier.displayName(isTopRarity(data.rarityKey()));

        meta.displayName(Component.text(creature.name(), rarity.color())
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(line("サイズ  " + sizeDisplayName + "（" + formatSize(data.sizeCm()) + "）", NamedTextColor.WHITE));
        lore.add(line("めずらしさ：" + rarity.name(), rarity.color()));
        lore.add(Component.empty());
        lore.add(line(data.catcherName() + " が " + creature.category().caughtLabel(), NamedTextColor.GRAY));
        lore.add(line(DATE_FMT.format(Instant.ofEpochMilli(data.caughtAt())), NamedTextColor.DARK_GRAY));
        if (data.isRegistered()) {
            lore.add(Component.empty());
            lore.add(line("図鑑登録済み（" + data.registeredByName() + "）", NamedTextColor.GREEN));
        }
        meta.lore(lore);

        if (creature.customModelData() != null) {
            meta.setCustomModelData(creature.customModelData());
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.CREATURE_ID, PersistentDataType.STRING, data.creatureId());
        pdc.set(Keys.SIZE_CM, PersistentDataType.DOUBLE, data.sizeCm());
        pdc.set(Keys.SIZE_TIER, PersistentDataType.STRING, data.sizeTierKey());
        pdc.set(Keys.RARITY, PersistentDataType.STRING, data.rarityKey());
        pdc.set(Keys.CAUGHT_AT, PersistentDataType.LONG, data.caughtAt());
        pdc.set(Keys.CATCHER_UUID, PersistentDataType.STRING, data.catcherUuid().toString());
        pdc.set(Keys.CATCHER_NAME, PersistentDataType.STRING, data.catcherName());
        if (data.isRegistered()) {
            pdc.set(Keys.REGISTERED_BY, PersistentDataType.STRING, data.registeredBy().toString());
            pdc.set(Keys.REGISTERED_NAME, PersistentDataType.STRING, data.registeredByName());
        } else {
            pdc.remove(Keys.REGISTERED_BY);
            pdc.remove(Keys.REGISTERED_NAME);
        }
    }

    /** 既存アイテムに「〇〇が登録した」印を付け直す。 */
    public void stampRegistration(ItemStack item, Creature creature, CatchData data) {
        item.editMeta(meta -> applyMeta(meta, creature, data));
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    // ---- アイテムからの読み出し ----

    @Nullable
    public CatchData read(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(Keys.CREATURE_ID, PersistentDataType.STRING);
        if (id == null) return null;

        Double size = pdc.get(Keys.SIZE_CM, PersistentDataType.DOUBLE);
        String sizeTier = pdc.get(Keys.SIZE_TIER, PersistentDataType.STRING);
        String rarity = pdc.get(Keys.RARITY, PersistentDataType.STRING);
        Long at = pdc.get(Keys.CAUGHT_AT, PersistentDataType.LONG);
        String catcherUuid = pdc.get(Keys.CATCHER_UUID, PersistentDataType.STRING);
        String catcherName = pdc.get(Keys.CATCHER_NAME, PersistentDataType.STRING);
        String regBy = pdc.get(Keys.REGISTERED_BY, PersistentDataType.STRING);
        String regName = pdc.get(Keys.REGISTERED_NAME, PersistentDataType.STRING);

        return new CatchData(
                id,
                size == null ? 0.0 : size,
                sizeTier == null ? sizeTiers.all().get(sizeTiers.all().size() / 2).key() : sizeTier,
                rarity == null ? "N" : rarity,
                at == null ? 0L : at,
                parseUuid(catcherUuid),
                catcherName == null ? "???" : catcherName,
                regBy == null ? null : parseUuid(regBy),
                regName);
    }

    public boolean isCatchItem(@Nullable ItemStack item) {
        return read(item) != null;
    }

    @Nullable
    public Creature creatureOf(CatchData data) {
        return creatures.get(data.creatureId());
    }

    private static UUID parseUuid(@Nullable String raw) {
        try {
            return raw == null ? new UUID(0, 0) : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return new UUID(0, 0);
        }
    }

    // ---- 表示 ----

    public static String formatSize(double cm) {
        return String.format("%.3f cm", cm);
    }

    public static String formatDate(long epochMillis) {
        if (epochMillis <= 0) return "-";
        return DATE_FMT.format(Instant.ofEpochMilli(epochMillis));
    }
}
