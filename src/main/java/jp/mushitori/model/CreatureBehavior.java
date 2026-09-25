package jp.mushitori.model;

/**
 * 生物ごとの「独特の動き」の設定。creatures.yml の {@code behavior} セクションから読み込む。
 *
 * <p>今のところ用意しているのは以下の4つです。今後、生物ごとの新しい動きが必要になったら、
 * ここにフィールドを増やしていく想定です。</p>
 *
 * @param disableNectar     entity が BEE のとき、花の蜜を集める・巣に運ぶ一連の動き
 *                          （見た目が変わる・花粉が出る原因）を無効化するか
 * @param fleeFromPlayers   近くのプレイヤーから、物理的に逃げていく（バニラのうさぎ・猫のように、
 *                          瞬間移動や消滅ではなく、その場から走って離れる）動きを追加するか
 * @param fleeRadius        逃げ始めるプレイヤーとの距離（ブロック）
 * @param fleeSpeed         逃げるときの移動速度（バニラのMob移動速度に対する倍率）
 * @param movementSpeedMultiplier 通常の移動速度（Attribute.MOVEMENT_SPEED）に掛ける倍率。
 *                          1.0未満でゆっくり、1.0より大きいと素早く動く
 * @param escapeDespawns    捕まえそこねて逃げられたとき、近くへ瞬間移動する（既定の動き）のではなく、
 *                          その場で完全に消える（≒逃がしたら最後、もう追えない）ようにするか
 * @param schooling         近くにいる、同じ生物ID（creature_id。バニラのentity種別ではなく
 *                          プラグイン独自のID基準）の個体どうしで寄り集まる動きを追加するか
 * @param schoolingRadius   仲間を探す範囲（ブロック）
 * @param schoolingSpeed    群れの中心へ向かう速度（バニラのMob移動速度に対する倍率）
 */
public record CreatureBehavior(
        boolean disableNectar,
        boolean fleeFromPlayers,
        double fleeRadius,
        double fleeSpeed,
        double movementSpeedMultiplier,
        boolean escapeDespawns,
        boolean schooling,
        double schoolingRadius,
        double schoolingSpeed
) {
    private static final CreatureBehavior DEFAULT =
            new CreatureBehavior(true, false, 5.0, 1.0, 1.0, false, false, 8.0, 1.0);

    /** creatures.yml に behavior セクションが無い生物向けの既定値。 */
    public static CreatureBehavior defaults() {
        return DEFAULT;
    }
}
