package jp.mushitori.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 「捕まえた1匹」の情報。アイテムの PersistentDataContainer に保存されます。
 *
 * @param sizeTierKey 最終決定されたサイズ段階（道具ボーナス適用後）
 * @param registeredBy 図鑑に登録した人（未登録なら null）
 */
public record CatchData(
        String creatureId,
        double sizeCm,
        String sizeTierKey,
        String rarityKey,
        long caughtAt,
        UUID catcherUuid,
        String catcherName,
        @Nullable UUID registeredBy,
        @Nullable String registeredByName
) {
    public boolean isRegistered() {
        return registeredBy != null;
    }

    public CatchData withRegistration(UUID uuid, String name) {
        return new CatchData(creatureId, sizeCm, sizeTierKey, rarityKey, caughtAt, catcherUuid, catcherName, uuid, name);
    }
}
