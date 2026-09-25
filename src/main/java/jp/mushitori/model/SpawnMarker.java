package jp.mushitori.model;

import java.util.List;

/**
 * アンビエントスポーン（放置しておくと勝手に虫・魚が湧く地点）の1マーカーぶん。
 *
 * <p>マーカーは作成時に全ての値を自前で持ちます（設置時に、その時点のグローバル既定値・
 * プリセットからコピーして持たせます）。これにより、マーカー設定画面（GUI）で
 * マーカーごとに個別に編集できます。</p>
 *
 * <p>サイズ段階抽選のテンプレート（「サイズイベント」）は、以前はマーカー全体の設定
 * でしたが、生物ごとに違う分布にしたいことが多かったため、{@link WeightedCreature#sizeDistributionTemplate()}
 * （生物枠ごとの上書き）に移しました。</p>
 *
 * @param id                  一意なID（連番）
 * @param name                マーカーの名前（管理用。省略時は "マーカー #id" のように表示）
 * @param worldName           ワールド名
 * @param x                   x座標（マーカーの中心）
 * @param y                   y座標
 * @param z                   z座標
 * @param creatures           湧く可能性のある生物のID＋ウェイト一覧（最大9種類。
 *                            ウェイトの比率でランダムに選ばれる）
 * @param triggerRadius       この範囲にプレイヤーが近づくと湧く判定が始まる（ブロック）
 * @param despawnRadius       この範囲に誰もいなくなるとデスポーンする（ブロック）
 * @param spawnRadius         実際に湧く位置を、中心からこの範囲内でランダムに決める（ブロック）。
 *                            湧いた個体は、原則としてこの範囲内にとどまるよう戻されます
 * @param maxCount            この場所から「引き出せる」上限数（下記の判定方法を参照）
 * @param simultaneousMax     一度の巡回でまとめて湧かせる最大数。maxCountとは別に、
 *                            「一気に何匹まで増えるか」を制限します（例：残り枠が2でも
 *                            simultaneousMaxが1なら、1匹ずつ増えます）。それぞれの枠は
 *                            independentlyにspawnChanceを判定するため、simultaneousMaxが
 *                            2・spawnChanceが50%なら、0〜2匹のどれかが湧きます
 * @param spawnIntervalSeconds 湧くかどうかを確認する間隔（秒）＝実質的な「湧く頻度」
 * @param spawnChance         上記の間隔ごとに、埋まっている枠それぞれに判定する、
 *                            実際に湧く確率（0.0〜1.0）。外れた枠は、次の間隔でもう一度判定します
 * @param catchWindowSeconds  直近何秒以内の捕獲を「まだ数えている最中」とみなすか
 *                            （maxCountの判定は「現在の生存数＋直近catchWindowSeconds以内に
 *                            捕まえられた数」がmaxCountを下回っているかどうかで行います）
 */
public record SpawnMarker(
        int id,
        String name,
        String worldName,
        double x,
        double y,
        double z,
        List<WeightedCreature> creatures,
        double triggerRadius,
        double despawnRadius,
        double spawnRadius,
        int maxCount,
        int simultaneousMax,
        double spawnIntervalSeconds,
        double spawnChance,
        double catchWindowSeconds
) {
    /** 最大何種類まで持てるか（GUIのスロット数と一致させています）。 */
    public static final int MAX_SPECIES = 9;

    /** 表示用の名前（未設定なら "マーカー #id"）。 */
    public String displayName() {
        return name == null || name.isBlank() ? ("マーカー #" + id) : name;
    }
}
