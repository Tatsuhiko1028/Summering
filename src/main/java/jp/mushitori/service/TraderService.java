package jp.mushitori.service;

import jp.mushitori.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

/**
 * いきものをお金に変えてくれる「トレーダー」NPC（村人ベース）。
 *
 * <p>{@code /mushitori trader spawn} で、その場に設置できます。右クリックすると
 * 売却確認画面（{@link jp.mushitori.ui.TraderGui}）が開きます（実際の売却処理・
 * 金額計算は {@link DexService#sellInventory(org.bukkit.entity.Player)} /
 * {@link CatchService#price} をそのまま使うため、価格の考え方は
 * {@code /mushitori sell} コマンドと完全に同じです）。</p>
 *
 * <p>AIを無効化して、その場から動かない・戦闘に参加しない・繁殖しない状態にしています。</p>
 */
public final class TraderService {

    private String name = "いきものトレーダー";
    private Villager.Profession profession = Villager.Profession.LIBRARIAN;

    public void load(@Nullable ConfigurationSection section) {
        if (section == null) return;
        name = section.getString("name", name);
        String rawProfession = section.getString("profession", "LIBRARIAN");
        try {
            profession = Villager.Profession.valueOf(rawProfession.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            profession = Villager.Profession.LIBRARIAN;
        }
    }

    /** その場にトレーダーを設置する。ワールドが取得できなければ null。 */
    @Nullable
    public Entity spawn(Location location) {
        if (location.getWorld() == null) return null;
        return location.getWorld().spawnEntity(location, org.bukkit.entity.EntityType.VILLAGER,
                CreatureSpawnEvent.SpawnReason.CUSTOM, spawned -> {
            spawned.getPersistentDataContainer().set(Keys.TRADER, PersistentDataType.BYTE, (byte) 1);
            spawned.customName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            spawned.setCustomNameVisible(true);
            spawned.setPersistent(true);
            spawned.setSilent(false);
            if (spawned instanceof Villager villager) {
                villager.setProfession(profession);
                villager.setVillagerType(Villager.Type.PLAINS);
                villager.setAI(false);
                villager.setInvulnerable(true);
                villager.setCollidable(false);
                villager.setCanPickupItems(false);
            }
        });
    }

    public boolean isTrader(@Nullable Entity entity) {
        if (entity == null) return false;
        return entity.getPersistentDataContainer().has(Keys.TRADER, PersistentDataType.BYTE);
    }
}
