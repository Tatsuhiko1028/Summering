package jp.mushitori.listener;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.CatchData;
import jp.mushitori.model.Creature;
import jp.mushitori.service.CatchService;
import jp.mushitori.service.EscapeEffects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 素手（メインハンドに何も持っていない状態）で、いきものを右クリックして
 * 捕まえる機能。道具を使う場合よりもずっと難しくしてあります。
 *
 * <p>確率の考え方：</p>
 * <ol>
 *   <li>まず、道具無しでの捕まえやすさ（{@code 1 - creature.escape-chance}）を求める</li>
 *   <li>そこから一律 -50%（例：本来100%で捕まえられる生物でも、素手では50%まで下がる）</li>
 *   <li>対象が空を飛ぶ生物（{@code creature.flying}）の場合は、そこからさらに×0.1
 *       （例：50% → 5%。9割方失敗するようになる）</li>
 *   <li>対象が魚（FISHカテゴリ）の場合は、そもそも対象外（釣竿でのみ捕まえられます）</li>
 * </ol>
 *
 * <p>タグ（{@code required-tags}。マーカーの生物枠ごとの上書きも含む）が設定されている
 * 生物は、素手（タグ無し）では最初から対象外です。</p>
 */
public final class BareHandCatchListener implements Listener {

    private static final long COOLDOWN_MS = 250;
    private static final double PENALTY = 0.5;
    private static final double FLYING_MULTIPLIER = 0.1;

    private final MushitoriPlugin plugin;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    public BareHandCatchListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();

        ItemStack held = player.getInventory().getItemInMainHand();
        if (!held.getType().isAir()) return; // 素手でなければ対象外（他の機能に任せる）

        Entity target = event.getRightClicked();
        String creatureId = plugin.spawnService().creatureIdOf(target);
        if (creatureId == null) return;
        Creature creature = plugin.creatures().get(creatureId);
        if (creature == null) return;
        // マーカーの生物枠に専用タグの上書きがあればそちらを、無ければcreature本来のタグを使う。
        // 素手にはタグが無いため、何らかのタグが必要な生物はそもそも対象外。
        var effectiveTags = plugin.ambientSpawnService().effectiveRequiredTags(target, creature);
        if (!effectiveTags.isEmpty()) return;

        event.setCancelled(true);

        if (creature.category().isFish()) {
            player.sendActionBar(Component.text("魚は素手では捕まえられません（釣竿が必要です）。", NamedTextColor.GRAY));
            return;
        }

        if (onCooldown(player)) return;
        lastUse.put(player.getUniqueId(), System.currentTimeMillis());

        double effectiveEscapeChance = plugin.ambientSpawnService().effectiveEscapeChance(target, creature);
        boolean effectiveFlying = plugin.ambientSpawnService().effectiveFlying(target, creature);

        double baseCatchChance = 1.0 - effectiveEscapeChance;
        double afterPenalty = Math.max(0.0, baseCatchChance - PENALTY);
        if (effectiveFlying) {
            afterPenalty *= FLYING_MULTIPLIER;
        }
        double finalCatchChance = Math.max(0.0, Math.min(1.0, afterPenalty));

        boolean escaped = ThreadLocalRandom.current().nextDouble() >= finalCatchChance;
        if (escaped) {
            if (plugin.ambientSpawnService().effectiveEscapeDespawns(target, creature)) {
                EscapeEffects.puff(target.getLocation(), plugin.netEscapeSound());
                target.remove();
            } else {
                EscapeEffects.escapeNearby(plugin, target, plugin.netEscapeTeleportRadius(), plugin.netEscapeSound());
            }
            return;
        }

        var baseTraits = plugin.catchService().baseTraitsOfEntity(target, creature);
        if (baseTraits == null) {
            baseTraits = plugin.catchService().rollBaseTraits(creature);
        }
        // 素手には道具のボーナスが無いため、sizeBonusは常に0
        var resolved = plugin.catchService().resolveCatch(target, creature, baseTraits, 0.0, true);
        ItemStack caughtItem = plugin.catchService().createCatchItem(creature, player,
                new CatchService.RolledTraits(resolved.rarityKey(), resolved.sizeTierKey()), resolved.sizeCm());
        CatchData data = plugin.catchService().read(caughtItem);

        Integer markerId = plugin.ambientSpawnService().markerIdOf(target);
        if (markerId != null) {
            plugin.ambientSpawnService().recordCatch(markerId, player);
        }

        target.remove();
        NetListener.giveOrDrop(player, caughtItem);

        player.playSound(player.getLocation(), plugin.netCatchSound(), plugin.netCatchVolume(), plugin.netCatchPitch());
        if (data != null) {
            String text = plugin.messages().get("net.catch.single", "{creature} を捕まえた！  {size}", Map.of(
                    "creature", creature.name(),
                    "size", CatchService.formatSize(data.sizeCm())));
            player.sendActionBar(Component.text(text, NamedTextColor.GREEN));
        }
    }

    private boolean onCooldown(Player player) {
        Long last = lastUse.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last < COOLDOWN_MS;
    }
}
