package jp.mushitori.listener;

import jp.mushitori.Keys;
import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.CatchData;
import jp.mushitori.model.Creature;
import jp.mushitori.registry.GearRegistry;
import jp.mushitori.service.CatchService;
import jp.mushitori.service.EscapeEffects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 虫取り網（右クリック）でいきものを捕獲する処理。
 *
 * <p>左クリック（攻撃）による捕獲はやめ、右クリックで正面の狭い範囲を「振る」ことで
 * 範囲内にいる複数のいきものをまとめて巻き込みます。逃げられた個体は、煙のエフェクトと
 * ともに近くへ瞬間移動します（文言でのお知らせはしません）。
 * 何か1匹でも捕まえられたときだけ、チリンという経験値獲得音を鳴らします。</p>
 *
 * <p><b>タグ</b>：生物側に{@code required-tags}が設定されている場合、網側に焼き付けられた
 * タグが全て揃っていないと、その生物は最初から対象になりません（捕まえられも
 * 逃げられもせず、素通りします）。同様に、FISHカテゴリの生物も、網ではそもそも
 * 対象になりません（釣竿限定です）。</p>
 *
 * <p><b>耐久値</b>：振った回数ではなく、実際に「捕まえた（＋逃げられた）」回数ぶん
 * 減ります。何もいない場所へ振っても、何かに当たってすべて対象外だった場合も、
 * 耐久は減りません。</p>
 */
public final class NetListener implements Listener {

    private final MushitoriPlugin plugin;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    public NetListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** いきものエンティティは、殴っても矢を撃っても倒せないようにする（網でのみ捕獲できる）。
     *  ただし /kill コマンド（DamageCause.KILL）は、管理者が不要な個体を消せるよう素通りさせる。 */
    @EventHandler(ignoreCancelled = true)
    public void onProtect(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        if (plugin.spawnService().creatureIdOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    // ignoreCancelled は付けない：何らかの理由で他にイベントが打ち消されていても、
    // 網を持っているときの右クリックは必ず処理する（空中クリックが効かない問題への対策）。
    @EventHandler
    public void onSwing(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isNet(held)) return;

        Action action = event.getAction();
        if (plugin.netDebug()) {
            plugin.getLogger().info("[net-catching] raw event: player=" + player.getName()
                    + " action=" + action + " cancelledBefore=" + event.isCancelled());
        }
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        // ブロックに対する右クリック（ドアが開く等）だけを封じる。
        // 空中への右クリックまで毎回キャンセルすると、それ以降の「空中への右クリック」
        // 自体が発火しなくなることがあるため、あえてキャンセルしない。
        if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
        }

        if (onCooldown(player)) return;
        lastUse.put(player.getUniqueId(), System.currentTimeMillis());

        swing(player, held);
    }

    private boolean onCooldown(Player player) {
        Long last = lastUse.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last < plugin.netCooldownMs();
    }

    private void swing(Player player, ItemStack net) {
        player.swingHand(EquipmentSlot.HAND);

        // ステータスはこの瞬間の config.yml ではなく、アイテムに焼き付けられた値を使う
        double escapeModifier = plugin.gear().escapeModifierOf(net);
        double sizeBonus = plugin.gear().sizeBonusOf(net);
        Double itemRange = plugin.gear().rangeOf(net);
        var netTags = plugin.gear().tagsOf(net);

        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().normalize();

        // バシュッ、という演出（スイープ攻撃と同じエフェクト・音）
        Location fxLoc = eye.clone().add(look.clone().multiply(0.8));
        player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, fxLoc, 0);
        player.getWorld().playSound(player.getLocation(), plugin.netSwingSound(),
                plugin.netSwingVolume(), plugin.netSwingPitch());

        int caught = 0;
        // 耐久は「振った回数」ではなく「捕まえた（＋逃げられた）回数」ぶん減らす
        int interactions = 0;
        Creature lastCreature = null;
        CatchData lastData = null;

        for (Entity target : findTargets(player, eye, look, itemRange)) {
            String creatureId = plugin.spawnService().creatureIdOf(target);
            if (creatureId == null) continue;
            Creature creature = plugin.creatures().get(creatureId);
            if (creature == null) continue;
            if (creature.category().isFish()) continue; // 魚は網では捕まえられない（釣竿限定）
            // マーカーの生物枠に専用タグの上書きがあればそちらを、無ければcreature本来の
            // required-tagsを使う（AmbientSpawnService#effectiveRequiredTags）
            var effectiveTags = plugin.ambientSpawnService().effectiveRequiredTags(target, creature);
            if (!effectiveTags.isEmpty() && !netTags.containsAll(effectiveTags)) {
                continue; // この網では捕まえられない生物：素通り
            }

            double effectiveEscapeChance = plugin.ambientSpawnService().effectiveEscapeChance(target, creature);
            if (plugin.catchService().rollEscape(effectiveEscapeChance, escapeModifier)) {
                interactions++;
                if (plugin.ambientSpawnService().effectiveEscapeDespawns(target, creature)) {
                    // 完全に消える設定：近くへ瞬間移動させず、その場で消す
                    EscapeEffects.puff(target.getLocation(), plugin.netEscapeSound());
                    target.remove();
                } else {
                    // 通常：煙とともに、近くへ瞬間移動する
                    EscapeEffects.escapeNearby(plugin, target, plugin.netEscapeTeleportRadius(), plugin.netEscapeSound());
                }
                continue;
            }

            var baseTraits = plugin.catchService().baseTraitsOfEntity(target, creature);
            if (baseTraits == null) {
                // 焼き付けが無い個体（古いデータ等）への保険：その場で素の抽選から行う
                baseTraits = plugin.catchService().rollBaseTraits(creature);
            }
            var resolved = plugin.catchService().resolveCatch(target, creature, baseTraits, sizeBonus, true);
            ItemStack caughtItem = plugin.catchService().createCatchItem(creature, player,
                    new CatchService.RolledTraits(resolved.rarityKey(), resolved.sizeTierKey()), resolved.sizeCm());
            CatchData data = plugin.catchService().read(caughtItem);

            Integer markerId = plugin.ambientSpawnService().markerIdOf(target);
            if (markerId != null) {
                plugin.ambientSpawnService().recordCatch(markerId, player);
            }

            target.remove();
            giveOrDrop(player, caughtItem);

            caught++;
            interactions++;
            lastCreature = creature;
            lastData = data;
        }

        if (caught > 0) {
            player.playSound(player.getLocation(), plugin.netCatchSound(),
                    plugin.netCatchVolume(), plugin.netCatchPitch());
            announce(player, caught, lastCreature, lastData);
        }

        if (interactions > 0) {
            consumeDurability(player, interactions);
        }
    }

    /** 正面の狭い範囲（距離・角度）にいる、いきものエンティティを近い順に返す。
     *  目線（プレイヤーの足元ではなく eye 位置）を中心にした立方体で探すことで、
     *  上や下を向いたときも同じように捕獲できるようにしている。
     *  itemRange が指定されていれば（そのアイテムに焼き付けられた射程があれば）そちらを、
     *  無ければ net-catching.range の全体既定値を使う。 */
    private List<Entity> findTargets(Player player, Location eye, Vector look, @Nullable Double itemRange) {
        double range = itemRange != null ? itemRange : plugin.netRange();
        double cosLimit = Math.cos(Math.toRadians(plugin.netAngleDegrees()));

        var world = eye.getWorld();
        if (world == null) return List.of();

        BoundingBox box = new BoundingBox(
                eye.getX() - range, eye.getY() - range, eye.getZ() - range,
                eye.getX() + range, eye.getY() + range, eye.getZ() + range);

        List<Entity> found = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(box)) {
            if (e.equals(player)) continue;
            if (plugin.spawnService().creatureIdOf(e) == null) continue;
            Vector to = e.getLocation().toVector().subtract(eye.toVector());
            double distance = to.length();
            if (distance > range || distance < 1.0E-6) continue;
            double cos = to.normalize().dot(look);
            if (cos < cosLimit) continue;
            found.add(e);
        }
        found.sort((a, b) -> Double.compare(
                a.getLocation().distanceSquared(eye), b.getLocation().distanceSquared(eye)));

        int max = plugin.netMaxTargets();
        return found.size() > max ? found.subList(0, max) : found;
    }

    /** amount：この振りで捕まえた（＋逃げられた）回数ぶん、耐久を減らす。 */
    private void consumeDurability(Player player, int amount) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isNet(held)) return; // 捕獲中にアイテムが手放された等
        boolean broken = GearRegistry.damage(held, amount);
        if (broken) {
            held.setAmount(0);
            player.getWorld().playSound(player.getLocation(), plugin.netBreakSound(), 1.0f, 1.0f);
        }
    }

    private void announce(Player player, int caught, @Nullable Creature last, @Nullable CatchData data) {
        // レア度はあえて伏せる（アイテムの説明文や図鑑で確認してもらう）
        if (caught == 1 && last != null && data != null) {
            String text = plugin.messages().get("net.catch.single", "{creature} を捕まえた！  {size}", Map.of(
                    "creature", last.name(),
                    "size", jp.mushitori.service.CatchService.formatSize(data.sizeCm())));
            player.sendActionBar(Component.text(text, NamedTextColor.GREEN));
        } else {
            String text = plugin.messages().get("net.catch.multiple", "{count} 匹のいきものを捕まえた！",
                    Map.of("count", String.valueOf(caught)));
            player.sendActionBar(Component.text(text, NamedTextColor.GREEN));
        }
    }

    private boolean isNet(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(Keys.NET_ID, PersistentDataType.STRING);
    }

    /** 網・釣り（試験実装含む）共通の「持たせる or 落とす」ヘルパー。 */
    public static void giveOrDrop(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }
}
