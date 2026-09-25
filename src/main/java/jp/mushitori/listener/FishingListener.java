package jp.mushitori.listener;

import jp.mushitori.Keys;
import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.Creature;
import jp.mushitori.registry.GearRegistry;
import jp.mushitori.service.EscapeEffects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 釣りまわりの処理。
 *  ・通常の釣り（バニラの釣り機構でアタリが来る、loot_table方式）
 *  ・浮きに直接寄ってくる釣り（試験実装、エンティティとして狙う方式）
 *    実際の演出・当落判定は {@link jp.mushitori.service.ApproachFishingService} が受け持ちます。
 *
 * <p>魚（FISHカテゴリ）は、種類を問わずこの2通りの
 * 方法のどちらでも獲得できます。カテゴリ分けは図鑑タブ・実績の整理のためのもので、
 * 獲得方法を制限するものではありません。ただし、生物側に{@code required-tags}が
 * 設定されている場合、竿側に焼き付けられたタグが全て揃っていないと、その生物は
 * 最初から候補に入りません。</p>
 *
 * <p>どちらも、生物の基準の逃げやすさ＋竿の escape-modifier で最終的に逃げられることがあります
 * （「正確に釣った」ように見えても、逃げられる可能性は残ります）。</p>
 *
 * <p><b>耐久値</b>：釣れたとき・逃げられたとき・釣るタイミングを逃したときに、竿の耐久を1減らします
 * （{@link #consumeRodDurability(Player)}）。早く上げすぎた場合などは減りません。</p>
 */
public final class FishingListener implements Listener {

    private final MushitoriPlugin plugin;

    public FishingListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (!plugin.fishingEnabled()) return;
        Player player = event.getPlayer();

        switch (event.getState()) {
            case FISHING -> {
                // キャストした瞬間。ここで近くの魚を誘導する演出を始める（試験実装）
                FishHook hook = event.getHook();
                if (hook != null) {
                    ItemStack rodItem = rodInUse(player);
                    double sizeBonus = plugin.gear().sizeBonusOf(rodItem);
                    double escapeModifier = plugin.gear().escapeModifierOf(rodItem);
                    plugin.approachFishingService().maybeStart(player, hook, sizeBonus, escapeModifier,
                            plugin.gear().tagsOf(rodItem));
                }
            }
            case CAUGHT_FISH -> {
                // 竿を振った（リールを巻いた）瞬間。ちょうど寄ってくる魚がいれば、
                // そちらを優先して捕獲し、バニラの釣果は打ち消す（二重取得防止）
                if (plugin.approachFishingService().onReelAttempt(player)) {
                    if (event.getCaught() instanceof Item itemEntity) {
                        itemEntity.remove();
                    }
                } else {
                    handleVanillaCatch(event, player);
                }
            }
            // FAILED_ATTEMPT はプレイヤーの操作ではなく、バニラのアタリを見逃したときにサーバー側で
            // 自動発火するため含めない（含めると、誘導中の魚が勝手に逃げてしまう）
            case CAUGHT_ENTITY, REEL_IN ->
                    plugin.approachFishingService().onReelAttempt(player);
            // バニラのアタリを見逃した（釣るタイミングを逃した）ときは、竿の耐久を1減らす
            case FAILED_ATTEMPT -> consumeRodDurability(player);
            case IN_GROUND -> plugin.approachFishingService().cancel(player);
            default -> {
            }
        }
    }

    /**
     * 独自の竿は、バニラ側の耐久消費（地面に刺さった・エンティティを引っ掛けた・釣れた等）を打ち消す。
     * 耐久の消費は {@link #consumeRodDurability(Player)} だけで行う。
     */
    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (isRod(event.getItem())) {
            event.setCancelled(true);
        }
    }

    private void handleVanillaCatch(PlayerFishEvent event, Player player) {
        ItemStack rodItem = rodInUse(player);
        double sizeBonus = plugin.gear().sizeBonusOf(rodItem);
        double escapeModifier = plugin.gear().escapeModifierOf(rodItem);
        var rodTags = plugin.gear().tagsOf(rodItem);

        List<Creature> fishes = plugin.creatures().fishCreatures().stream()
                .filter(c -> c.isCatchableWith(rodTags))
                .toList();
        if (fishes.isEmpty()) return;

        Creature creature = fishes.get(ThreadLocalRandom.current().nextInt(fishes.size()));

        if (plugin.catchService().rollEscape(creature, escapeModifier)) {
            // アタリはあったが、最後の最後で逃げられた（この場合も竿の耐久は減る）
            if (event.getCaught() instanceof Item itemEntity) {
                itemEntity.remove();
            }
            EscapeEffects.puff(event.getHook() != null ? event.getHook().getLocation() : player.getLocation(),
                    plugin.fishEscapeSound());
            consumeRodDurability(player);
            String text = plugin.messages().get("fishing.rod.escape", "あと一歩のところで、魚に逃げられた…", null);
            player.sendActionBar(Component.text(text, NamedTextColor.GRAY));
            return;
        }

        ItemStack caught = plugin.catchService().createCatchItem(creature, player, sizeBonus);

        if (plugin.replaceVanillaCatch() && event.getCaught() instanceof Item itemEntity) {
            itemEntity.setItemStack(caught);
        } else {
            NetListener.giveOrDrop(player, caught);
        }
        consumeRodDurability(player);

        var data = plugin.catchService().read(caught);
        if (data != null) {
            // レア度はあえて伏せる（アイテムの説明文や図鑑で確認してもらう）
            String text = plugin.messages().get("fishing.rod.catch", "{creature} が釣れた！  {size}", java.util.Map.of(
                    "creature", creature.name(),
                    "size", jp.mushitori.service.CatchService.formatSize(data.sizeCm())));
            player.sendActionBar(Component.text(text, NamedTextColor.AQUA));
        }
    }

    /**
     * 使用中の竿。メインハンドに竿があればそれを、無ければオフハンドの竿を返す
     * （両手に持っている場合はメインハンドのみ。竿の効果・耐久消費ともにこちらを対象にする）。
     */
    public static ItemStack rodInUse(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (isRod(main)) return main;
        ItemStack off = player.getInventory().getItemInOffHand();
        return isRod(off) ? off : main;
    }

    /** 竿の耐久を1減らす（釣れたとき・逃げられたとき・タイミングを逃したときに呼ぶ）。 */
    public static void consumeRodDurability(Player player) {
        ItemStack held = rodInUse(player);
        if (!isRod(held)) return;
        boolean broken = GearRegistry.damage(held, 1);
        if (broken) {
            held.setAmount(0);
        }
    }

    private static boolean isRod(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(Keys.ROD_ID, PersistentDataType.STRING);
    }
}
