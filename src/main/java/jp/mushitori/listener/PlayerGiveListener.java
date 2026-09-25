package jp.mushitori.listener;

import jp.mushitori.MushitoriPlugin;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 「アイテムを渡す」機能。スニーク（Shift）＋右クリックでプレイヤーにアイテムを
 * 使用すると、相手のメインハンドが空いている場合、そのまま手渡せます
 * （バニラのアイテムも含めて、どんなアイテムでも対象です）。
 *
 * <p>相手が既に何か持っている場合は、失敗の効果音（「ポロン」のような、軽い
 * 音を想定）を鳴らします。</p>
 *
 * <p>「自慢」機能（{@link ShowcaseListener}）と同じ「シフト」がきっかけになるため、
 * 自慢の構え中・表示中は、こちらは何もしません（自慢を優先します）。</p>
 */
public final class PlayerGiveListener implements Listener {

    private final MushitoriPlugin plugin;
    private Sound successSound = Sound.ENTITY_ITEM_PICKUP;
    private Sound failSound = Sound.BLOCK_NOTE_BLOCK_PLING;

    public PlayerGiveListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(@Nullable ConfigurationSection section) {
        if (section == null) return;
        successSound = parseSound(section.getString("sound-success"), Sound.ENTITY_ITEM_PICKUP);
        failSound = parseSound(section.getString("sound-fail"), Sound.BLOCK_NOTE_BLOCK_PLING);
    }

    private Sound parseSound(@Nullable String raw, Sound fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Sound.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("効果音名が不正です: " + raw);
            return fallback;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Player target)) return;
        Player giver = event.getPlayer();
        if (!giver.isSneaking()) return;

        // 自慢の構え中・表示中は、こちらより自慢を優先する
        if (plugin.showcaseListener().isChargingOrShowing(giver.getUniqueId())) return;

        ItemStack held = giver.getInventory().getItemInMainHand();
        if (held.getType().isAir()) return; // 何も持っていなければ、渡す物が無い（通常の相互作用に任せる）

        event.setCancelled(true);

        ItemStack targetHand = target.getInventory().getItemInMainHand();
        if (!targetHand.getType().isAir()) {
            // 相手が既に何か持っている：渡せない
            giver.playSound(giver.getLocation(), failSound, 0.8f, 1.4f);
            return;
        }

        ItemStack handed = held.clone();
        giver.getInventory().setItemInMainHand(null);
        target.getInventory().setItemInMainHand(handed);

        Location fxLoc = target.getEyeLocation();
        target.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, fxLoc, 6, 0.3, 0.3, 0.3, 0.0);
        giver.playSound(giver.getLocation(), successSound, 0.8f, 1.2f);
        target.playSound(target.getLocation(), successSound, 0.8f, 1.2f);
    }
}
