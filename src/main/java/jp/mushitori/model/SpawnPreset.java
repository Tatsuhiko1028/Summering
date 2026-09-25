package jp.mushitori.model;

import java.util.List;

/**
 * アンビエントスポーンの「プリセット」。1つのマーカーが複数種類の生物を、ウェイトの
 * 比率でランダムに湧かせられるようにするための定義（config.yml の ambient-spawn.presets）。
 *
 * @param name      プリセット名（config.ymlのキー）
 * @param creatures 湧く可能性のある生物のID＋ウェイト一覧
 * @param maxCount  このマーカーで同時に存在できる個体数の上限
 */
public record SpawnPreset(String name, List<WeightedCreature> creatures, int maxCount) {
}
