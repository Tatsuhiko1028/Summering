package jp.mushitori.listener;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.ApproachOverrides;
import jp.mushitori.model.Creature;
import jp.mushitori.model.CreatureOverride;
import jp.mushitori.model.Rarity;
import jp.mushitori.model.SpawnMarker;
import jp.mushitori.model.SpawnPreset;
import jp.mushitori.model.WeightedCreature;
import jp.mushitori.ui.CreatureDetailGui;
import jp.mushitori.ui.CreaturePickerGui;
import jp.mushitori.ui.MarkerGui;
import jp.mushitori.ui.MarkerListGui;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link MarkerGui} 内のクリックを処理する。
 * スロットをクリックするたびに値をその場で書き換え、GUIを閉じたときにまとめて確定します。
 *
 * <p>生物枠は、通常クリックで「割り当てる生物の切り替え」、右クリックで「ウェイト-1」、
 * シフト+右クリックで「枠を空にする」、シフト+左クリックで「ウェイト+1」を行います。</p>
 *
 * <p>名前の変更は、バニラの金床（アンビル）と同じ入力欄を持つ仮想インベントリを開いて行います。</p>
 */
public final class MarkerGuiListener implements Listener {

    private final MushitoriPlugin plugin;
    /** プレイヤーごとの「プリセット適用」ボタンの巡回位置。 */
    private final Map<UUID, Integer> presetCycle = new ConcurrentHashMap<>();
    /** 名前変更中のプレイヤー → 対象マーカーID。 */
    private final Map<UUID, Integer> renaming = new ConcurrentHashMap<>();
    /** 生物枠の詳細設定（1項目）を編集中のプレイヤー → 対象マーカーID・枠番号・項目名。 */
    private final Map<UUID, FieldEditRef> editingField = new ConcurrentHashMap<>();

    private record FieldEditRef(int markerId, int slotIndex, String field) {
    }

    public MarkerGuiListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof MarkerListGui listGui) {
            handleListClick(event, listGui);
            return;
        }

        if (event.getInventory().getHolder() instanceof CreaturePickerGui pickerGui) {
            handlePickerClick(event, pickerGui);
            return;
        }

        if (event.getInventory().getHolder() instanceof CreatureDetailGui detailGui) {
            handleDetailClick(event, detailGui);
            return;
        }

        if (!(event.getInventory().getHolder() instanceof MarkerGui gui)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        int slot = event.getSlot();
        SpawnMarker w = gui.working();
        ClickType click = event.getClick();
        boolean shift = click.isShiftClick();
        boolean right = click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT;
        double step = shift ? 10 : 1;

        if (contains(MarkerGui.CREATURE_SLOTS, slot)) {
            int index = indexOf(MarkerGui.CREATURE_SLOTS, slot);
            if (shift && !right) {
                // シフト＋左クリック：この枠の詳細設定画面を開く
                // （以前は「枠を空にする」動作でしたが、そちらは一覧画面側に移しました）
                WeightedCreature current = index < w.creatures().size() ? w.creatures().get(index) : null;
                if (current == null) {
                    player.sendActionBar(Component.text("先に生物を割り当ててください。", NamedTextColor.RED));
                    return;
                }
                CreatureDetailGui.open(plugin, player, gui.markerId(), index, current);
                return;
            }
            if (!shift && !right) {
                // 左クリック：生物の一覧画面を開いて、そこから選ぶ
                // （以前の「順々に切り替わる」方式は、種類が増えると選びづらいため廃止）
                CreaturePickerGui.open(plugin, player, gui.markerId(), index, 0);
                return;
            }
            gui.setWorking(updateCreatureSlot(player, w, index, shift, right));
        } else if (slot == MarkerGui.SLOT_TRIGGER_RADIUS) {
            gui.setWorking(withTriggerRadius(w, clampMin(w.triggerRadius() + (right ? -step : step), 1)));
        } else if (slot == MarkerGui.SLOT_DESPAWN_RADIUS) {
            gui.setWorking(withDespawnRadius(w, clampMin(w.despawnRadius() + (right ? -step : step), 1)));
        } else if (slot == MarkerGui.SLOT_SPAWN_RADIUS) {
            gui.setWorking(withSpawnRadius(w, clampMin(w.spawnRadius() + (right ? -step : step), 0)));
        } else if (slot == MarkerGui.SLOT_MAX_COUNT) {
            gui.setWorking(withMaxCount(w, (int) clampMin(w.maxCount() + (right ? -step : step), 1)));
        } else if (slot == MarkerGui.SLOT_SIMULTANEOUS_MAX) {
            gui.setWorking(withSimultaneousMax(w, (int) clampMin(w.simultaneousMax() + (right ? -step : step), 1)));
        } else if (slot == MarkerGui.SLOT_SPAWN_INTERVAL) {
            gui.setWorking(withSpawnInterval(w, clampMin(w.spawnIntervalSeconds() + (right ? -step : step), 1)));
        } else if (slot == MarkerGui.SLOT_CATCH_WINDOW) {
            gui.setWorking(withCatchWindow(w, clampMin(w.catchWindowSeconds() + (right ? -step : step), 1)));
        } else if (slot == MarkerGui.SLOT_SPAWN_CHANCE) {
            double percentStep = shift ? 0.01 : 0.10;
            gui.setWorking(withSpawnChance(w, w.spawnChance() + (right ? -percentStep : percentStep)));
        } else if (slot == MarkerGui.SLOT_PRESET) {
            applyNextPreset(player, gui);
        } else if (slot == MarkerGui.SLOT_RENAME) {
            // 先にここまでの変更を確定させてから、チャットでの名前入力を待つ
            plugin.ambientSpawnService().updateMarker(w);
            renaming.put(player.getUniqueId(), gui.markerId());
            player.closeInventory();
            player.sendMessage(Component.text(
                    "チャットに新しい名前を入力してください（\"cancel\" でキャンセル）。", NamedTextColor.YELLOW));
            return;
        } else if (slot == MarkerGui.SLOT_DELETE) {
            plugin.ambientSpawnService().removeMarker(gui.markerId());
            player.sendActionBar(Component.text("マーカー #" + gui.markerId() + " を削除しました。", NamedTextColor.GREEN));
            player.closeInventory();
            return;
        } else if (slot == MarkerGui.SLOT_RESET_MOBS) {
            int removed = plugin.ambientSpawnService().resetMarkerMobs(gui.markerId());
            player.sendActionBar(Component.text(
                    removed + "体をデスポーンさせました。", NamedTextColor.GREEN));
        } else {
            return;
        }

        gui.redraw();
    }

    /**
     * マーカー一覧画面内でのクリック処理。
     *  左クリック: 設定画面を開く
     *  右クリック: そのマーカーの場所へテレポート
     *  シフト＋右クリック: そのマーカーを、今自分がいる場所へ移動
     */
    /** {@link CreaturePickerGui}（生物の一覧選択画面）のクリック処理。 */
    private void handlePickerClick(InventoryClickEvent event, CreaturePickerGui pickerGui) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        int slot = event.getSlot();
        if (slot == CreaturePickerGui.SLOT_PREV) {
            CreaturePickerGui.open(plugin, player, pickerGui.markerId(), pickerGui.slotIndex(), pickerGui.page() - 1);
            return;
        }
        if (slot == CreaturePickerGui.SLOT_NEXT) {
            CreaturePickerGui.open(plugin, player, pickerGui.markerId(), pickerGui.slotIndex(), pickerGui.page() + 1);
            return;
        }
        if (slot == CreaturePickerGui.SLOT_CANCEL) {
            MarkerGui.open(plugin, player, pickerGui.markerId());
            return;
        }
        if (slot == CreaturePickerGui.SLOT_CLEAR) {
            SpawnMarker markerToClear = plugin.ambientSpawnService().marker(pickerGui.markerId());
            if (markerToClear == null) {
                player.sendMessage(Component.text(
                        "マーカー #" + pickerGui.markerId() + " はもう存在しません。", NamedTextColor.RED));
                return;
            }
            List<WeightedCreature> clearedList = new ArrayList<>(markerToClear.creatures());
            int clearIndex = pickerGui.slotIndex();
            if (clearIndex < clearedList.size()) {
                clearedList.remove(clearIndex);
            }
            plugin.ambientSpawnService().updateMarker(withCreatures(markerToClear, clearedList));
            player.sendMessage(Component.text("枠 " + (clearIndex + 1) + " を空にしました。", NamedTextColor.GREEN));
            MarkerGui.open(plugin, player, pickerGui.markerId());
            return;
        }
        if (slot >= CreaturePickerGui.PER_PAGE) return; // 装飾・空欄

        String creatureId = CreaturePickerGui.creatureIdOf(event.getCurrentItem());
        if (creatureId == null) return;

        SpawnMarker marker = plugin.ambientSpawnService().marker(pickerGui.markerId());
        if (marker == null) {
            player.sendMessage(Component.text(
                    "マーカー #" + pickerGui.markerId() + " はもう存在しません。", NamedTextColor.RED));
            return;
        }

        int index = pickerGui.slotIndex();
        List<WeightedCreature> list = new ArrayList<>(marker.creatures());
        while (list.size() <= index) list.add(null);
        WeightedCreature current = list.get(index);
        // 既存の枠があれば、ウェイト・各種上書きはそのまま引き継ぎ、生物IDだけ差し替える
        int weight = current == null ? 1 : current.weight();
        var requiredTagsOverride = current == null ? Set.<String>of() : current.requiredTagsOverride();
        var override = current == null ? CreatureOverride.EMPTY : current.override();
        var sizeDistributionTemplate = current == null ? null : current.sizeDistributionTemplate();
        var approachOverride = current == null ? ApproachOverrides.EMPTY : current.approachOverride();
        list.set(index, new WeightedCreature(creatureId, weight, requiredTagsOverride, override,
                sizeDistributionTemplate, approachOverride));

        plugin.ambientSpawnService().updateMarker(withCreatures(marker, list));

        Creature picked = plugin.creatures().get(creatureId);
        player.sendMessage(Component.text(
                "枠 " + (index + 1) + " に「" + (picked != null ? picked.name() : creatureId) + "」を割り当てました。",
                NamedTextColor.GREEN));
        MarkerGui.open(plugin, player, pickerGui.markerId());
    }

    /** {@link CreatureDetailGui}（生物枠1つの詳細設定画面）のクリック処理。 */
    private void handleDetailClick(InventoryClickEvent event, CreatureDetailGui detailGui) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        int markerId = detailGui.markerId();
        int index = detailGui.slotIndex();

        if (event.getSlot() == CreatureDetailGui.SLOT_BACK) {
            MarkerGui.open(plugin, player, markerId);
            return;
        }

        SpawnMarker marker = plugin.ambientSpawnService().marker(markerId);
        if (marker == null) {
            player.sendMessage(Component.text("マーカー #" + markerId + " はもう存在しません。", NamedTextColor.RED));
            return;
        }
        List<WeightedCreature> list = new ArrayList<>(marker.creatures());
        if (index >= list.size() || list.get(index) == null) {
            player.sendActionBar(Component.text("この枠は空です。", NamedTextColor.RED));
            player.closeInventory();
            return;
        }
        WeightedCreature current = list.get(index);

        if (event.getSlot() == CreatureDetailGui.SLOT_CLEAR_ALL) {
            list.set(index, current.withRequiredTagsOverride(Set.of()).withOverride(CreatureOverride.EMPTY)
                    .withSizeDistributionTemplate(null).withApproachOverride(ApproachOverrides.EMPTY));
            plugin.ambientSpawnService().updateMarker(withCreatures(marker, list));
            player.sendMessage(Component.text("この枠の上書きを全て解除しました。", NamedTextColor.GREEN));
            WeightedCreature updated = list.get(index);
            detailGui.redraw(updated);
            return;
        }

        // 数値・タグ・秒数項目：クリックしてチャット入力を開始する
        switch (event.getSlot()) {
            case CreatureDetailGui.SLOT_REQUIRED_TAGS -> startFieldEdit(player, markerId, index, "tags",
                    "必要タグの上書き", "チャットにカンマ区切りで入力してください（例: event,special）。");
            case CreatureDetailGui.SLOT_SCALE -> startFieldEdit(player, markerId, index, "scale",
                    "見た目のスケール上書き（base-scale）", "チャットに数値を入力してください（例: 0.8）。");
            case CreatureDetailGui.SLOT_ESCAPE_CHANCE -> startFieldEdit(player, markerId, index, "escape-chance",
                    "基準の逃げやすさ上書き（escape-chance）", "チャットに0.0〜1.0の数値を入力してください（例: 0.3）。");
            case CreatureDetailGui.SLOT_FLEE_RADIUS -> startFieldEdit(player, markerId, index, "flee-radius",
                    "逃げ始める距離上書き（flee-radius）", "チャットに数値（ブロック）を入力してください（例: 6）。");
            case CreatureDetailGui.SLOT_FLEE_SPEED -> startFieldEdit(player, markerId, index, "flee-speed",
                    "逃げる速さ上書き（flee-speed）", "チャットに数値を入力してください（例: 1.2）。");
            case CreatureDetailGui.SLOT_MOVEMENT_SPEED_MULTIPLIER -> startFieldEdit(player, markerId, index,
                    "movement-speed-multiplier", "移動速度倍率上書き（movement-speed-multiplier）",
                    "チャットに数値を入力してください（例: 0.5でゆっくり）。");
            case CreatureDetailGui.SLOT_APPROACH_PATIENCE -> startFieldEdit(player, markerId, index,
                    "approach-patience", "アプローチ：反応判定までの待ち時間上書き（patience-seconds）",
                    "チャットに秒数を入力してください（例: 10）。");
            case CreatureDetailGui.SLOT_APPROACH_RETRY_INTERVAL -> startFieldEdit(player, markerId, index,
                    "approach-retry-interval", "アプローチ：再判定間隔上書き（retry-interval-seconds）",
                    "チャットに秒数を入力してください（例: 3）。");
            case CreatureDetailGui.SLOT_APPROACH_TRIGGER_CHANCE -> startFieldEdit(player, markerId, index,
                    "approach-trigger-chance", "アプローチ：反応確率上書き（trigger-chance）",
                    "チャットに0.0〜1.0の数値を入力してください（例: 0.4）。");
            case CreatureDetailGui.SLOT_APPROACH_MIN_SECONDS -> startFieldEdit(player, markerId, index,
                    "approach-min-seconds", "アプローチ：誘導時間（最短）上書き（min-approach-seconds）",
                    "チャットに秒数を入力してください（例: 2）。");
            case CreatureDetailGui.SLOT_APPROACH_MAX_SECONDS -> startFieldEdit(player, markerId, index,
                    "approach-max-seconds", "アプローチ：誘導時間（最長）上書き（max-approach-seconds）",
                    "チャットに秒数を入力してください（例: 4）。");
            case CreatureDetailGui.SLOT_APPROACH_WINDOW_SECONDS -> startFieldEdit(player, markerId, index,
                    "approach-window-seconds", "アプローチ：竿を振るタイミング上書き（window-seconds）",
                    "チャットに秒数を入力してください（例: 1.5）。");
            case CreatureDetailGui.SLOT_FLYING -> {
                WeightedCreature updated = current.withOverride(
                        current.override().withFlying(cycleBoolean(current.override().flying())));
                list.set(index, updated);
                plugin.ambientSpawnService().updateMarker(withCreatures(marker, list));
                detailGui.redraw(updated);
            }
            case CreatureDetailGui.SLOT_FLEE_FROM_PLAYERS -> {
                WeightedCreature updated = current.withOverride(
                        current.override().withFleeFromPlayers(cycleBoolean(current.override().fleeFromPlayers())));
                list.set(index, updated);
                plugin.ambientSpawnService().updateMarker(withCreatures(marker, list));
                detailGui.redraw(updated);
            }
            case CreatureDetailGui.SLOT_ESCAPE_DESPAWNS -> {
                WeightedCreature updated = current.withOverride(
                        current.override().withEscapeDespawns(cycleBoolean(current.override().escapeDespawns())));
                list.set(index, updated);
                plugin.ambientSpawnService().updateMarker(withCreatures(marker, list));
                detailGui.redraw(updated);
            }
            case CreatureDetailGui.SLOT_DISABLE_NECTAR -> {
                WeightedCreature updated = current.withOverride(
                        current.override().withDisableNectar(cycleBoolean(current.override().disableNectar())));
                list.set(index, updated);
                plugin.ambientSpawnService().updateMarker(withCreatures(marker, list));
                detailGui.redraw(updated);
            }
            case CreatureDetailGui.SLOT_SIZE_DISTRIBUTION_TEMPLATE -> {
                String next = nextInCycle(plugin.catchService().sizeDistributionTemplateNames(),
                        current.sizeDistributionTemplate());
                WeightedCreature updated = current.withSizeDistributionTemplate(next);
                list.set(index, updated);
                plugin.ambientSpawnService().updateMarker(withCreatures(marker, list));
                detailGui.redraw(updated);
            }
            case CreatureDetailGui.SLOT_SIZE_RARITY_TEMPLATE -> {
                String next = nextInCycle(plugin.catchService().sizeRarityTemplateNames(),
                        current.override().sizeRarityTemplate());
                WeightedCreature updated = current.withOverride(current.override().withSizeRarityTemplate(next));
                list.set(index, updated);
                plugin.ambientSpawnService().updateMarker(withCreatures(marker, list));
                detailGui.redraw(updated);
            }
            case CreatureDetailGui.SLOT_BASE_RARITY -> {
                List<String> rarityKeys = plugin.catchService().rarities().all().stream()
                        .map(Rarity::key).collect(Collectors.toList());
                String next = nextInCycle(rarityKeys, current.override().baseRarityKey());
                WeightedCreature updated = current.withOverride(current.override().withBaseRarityKey(next));
                list.set(index, updated);
                plugin.ambientSpawnService().updateMarker(withCreatures(marker, list));
                detailGui.redraw(updated);
            }
            default -> {
                // 装飾スロット等：何もしない
            }
        }
    }

    /** 上書き無し(null) → true → false → 上書き無し、と巡回させる。 */
    @Nullable
    private Boolean cycleBoolean(@Nullable Boolean current) {
        if (current == null) return true;
        if (current) return false;
        return null;
    }

    private void handleListClick(InventoryClickEvent event, MarkerListGui listGui) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        Integer markerId = listGui.markerIdAt(event.getSlot());
        if (markerId == null) return;
        SpawnMarker marker = plugin.ambientSpawnService().marker(markerId);
        if (marker == null) return;

        ClickType click = event.getClick();
        if (click == ClickType.SHIFT_RIGHT) {
            plugin.ambientSpawnService().moveMarkerTo(markerId, player.getLocation());
            player.sendActionBar(Component.text(
                    "マーカー #" + markerId + "（" + marker.displayName() + "）をこの場所へ移動しました。",
                    NamedTextColor.GREEN));
            MarkerListGui.refresh(plugin, player);
        } else if (click == ClickType.RIGHT) {
            var loc = MarkerListGui.locationOf(marker);
            if (loc == null) {
                player.sendActionBar(Component.text("そのワールドが見つかりません。", NamedTextColor.RED));
                return;
            }
            player.closeInventory();
            player.teleport(loc);
            player.sendActionBar(Component.text(
                    "マーカー #" + markerId + "（" + marker.displayName() + "）へテレポートしました。", NamedTextColor.GREEN));
        } else {
            // 左クリック（およびその他）：設定画面を開く
            MarkerGui.open(plugin, player, markerId);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MarkerGui gui)) return;
        // マーカーがすでに削除されていれば何もしない（削除ボタンで閉じたケース）
        if (plugin.ambientSpawnService().marker(gui.markerId()) == null) return;
        plugin.ambientSpawnService().updateMarker(gui.working());
    }

    /** 名前入力待ち・詳細設定入力待ちのプレイヤーからのチャットを、1回だけ捕まえて反映する。 */
    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (editingField.containsKey(uuid)) {
            event.setCancelled(true);
            FieldEditRef ref = editingField.remove(uuid);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

            player.getScheduler().run(plugin, task -> applyFieldEdit(player, ref, text), null);
            return;
        }

        Integer markerId = renaming.get(uuid);
        if (markerId == null) return;

        event.setCancelled(true);
        renaming.remove(uuid);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // Bukkit APIを触るので、プレイヤーのスケジューラ上で処理する
        player.getScheduler().run(plugin, task -> {
            if (text.isEmpty() || text.equalsIgnoreCase("cancel")) {
                player.sendMessage(Component.text("名前の変更をキャンセルしました。", NamedTextColor.GRAY));
                return;
            }
            SpawnMarker marker = plugin.ambientSpawnService().marker(markerId);
            if (marker == null) {
                player.sendMessage(Component.text("マーカー #" + markerId + " はもう存在しません。", NamedTextColor.RED));
                return;
            }
            plugin.ambientSpawnService().updateMarker(withName(marker, text));
            player.sendMessage(Component.text(
                    "マーカー #" + markerId + " の名前を '" + text + "' に変更しました。", NamedTextColor.GREEN));
        }, null);
    }

    /**
     * 現在の値から、次の候補（既定＝nullを含めて巡回）を求める。
     * {@link CreatureDetailGui}のサイズイベント・size-rarity-template・base-rarity
     * の各上書きボタンで共通して使う。
     */
    @Nullable
    private String nextInCycle(List<String> names, @Nullable String current) {
        if (names.isEmpty()) return null; // 候補が定義されていなければ常に既定のまま
        List<String> cycle = new ArrayList<>();
        cycle.add(null); // 既定（未選択）も巡回に含める
        cycle.addAll(names);
        int idx = cycle.indexOf(current);
        int next = (idx + 1) % cycle.size();
        return cycle.get(next);
    }

    /** {@link CreatureDetailGui}の数値・タグ項目クリックで、チャット入力を開始する。 */
    private void startFieldEdit(Player player, int markerId, int slotIndex, String field, String label, String hint) {
        editingField.put(player.getUniqueId(), new FieldEditRef(markerId, slotIndex, field));
        player.closeInventory();
        player.sendMessage(Component.text(label + " を編集します。", NamedTextColor.YELLOW));
        player.sendMessage(Component.text(hint, NamedTextColor.GRAY));
        player.sendMessage(Component.text(
                "\"none\" でこの項目の上書きを解除、\"cancel\" でキャンセル。", NamedTextColor.YELLOW));
    }

    /** チャットで受け取った1項目ぶんの値を、実際のマーカーへ反映する。 */
    private void applyFieldEdit(Player player, FieldEditRef ref, String text) {
        if (text.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("変更をキャンセルしました。", NamedTextColor.GRAY));
            return;
        }
        SpawnMarker marker = plugin.ambientSpawnService().marker(ref.markerId());
        if (marker == null) {
            player.sendMessage(Component.text("マーカー #" + ref.markerId() + " はもう存在しません。", NamedTextColor.RED));
            return;
        }
        List<WeightedCreature> list = new ArrayList<>(marker.creatures());
        if (ref.slotIndex() >= list.size() || list.get(ref.slotIndex()) == null) {
            player.sendMessage(Component.text("その枠は既に空になっています。", NamedTextColor.RED));
            return;
        }
        WeightedCreature current = list.get(ref.slotIndex());
        boolean clear = text.equalsIgnoreCase("none");

        if (ref.field().equals("tags")) {
            Set<String> tags = clear ? Set.of() : Arrays.stream(text.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            list.set(ref.slotIndex(), current.withRequiredTagsOverride(tags));
        } else {
            Double value = null;
            if (!clear) {
                try {
                    value = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    player.sendMessage(Component.text("数値として読み取れません: \"" + text + "\"", NamedTextColor.RED));
                    return;
                }
            }
            if (ref.field().startsWith("approach-")) {
                ApproachOverrides ao = current.approachOverride();
                ApproachOverrides newAo = switch (ref.field()) {
                    case "approach-patience" -> new ApproachOverrides(value, ao.retryIntervalSeconds(),
                            ao.triggerChance(), ao.minApproachSeconds(), ao.maxApproachSeconds(), ao.windowSeconds());
                    case "approach-retry-interval" -> new ApproachOverrides(ao.patienceSeconds(), value,
                            ao.triggerChance(), ao.minApproachSeconds(), ao.maxApproachSeconds(), ao.windowSeconds());
                    case "approach-trigger-chance" -> new ApproachOverrides(ao.patienceSeconds(),
                            ao.retryIntervalSeconds(), value, ao.minApproachSeconds(), ao.maxApproachSeconds(),
                            ao.windowSeconds());
                    case "approach-min-seconds" -> new ApproachOverrides(ao.patienceSeconds(),
                            ao.retryIntervalSeconds(), ao.triggerChance(), value, ao.maxApproachSeconds(),
                            ao.windowSeconds());
                    case "approach-max-seconds" -> new ApproachOverrides(ao.patienceSeconds(),
                            ao.retryIntervalSeconds(), ao.triggerChance(), ao.minApproachSeconds(), value,
                            ao.windowSeconds());
                    case "approach-window-seconds" -> new ApproachOverrides(ao.patienceSeconds(),
                            ao.retryIntervalSeconds(), ao.triggerChance(), ao.minApproachSeconds(),
                            ao.maxApproachSeconds(), value);
                    default -> ao;
                };
                list.set(ref.slotIndex(), current.withApproachOverride(newAo));
            } else {
                CreatureOverride ov = current.override();
                CreatureOverride newOv = switch (ref.field()) {
                    case "scale" -> ov.withScale(value);
                    case "escape-chance" -> ov.withEscapeChance(value);
                    case "flee-radius" -> ov.withFleeRadius(value);
                    case "flee-speed" -> ov.withFleeSpeed(value);
                    case "movement-speed-multiplier" -> ov.withMovementSpeedMultiplier(value);
                    default -> ov;
                };
                list.set(ref.slotIndex(), current.withOverride(newOv));
            }
        }

        plugin.ambientSpawnService().updateMarker(withCreatures(marker, list));
        player.sendMessage(Component.text(
                clear ? "この項目の上書きを解除しました（creatures.yml本来の設定に戻ります）。" : "この項目の上書きを更新しました。",
                NamedTextColor.GREEN));
    }

    private void applyNextPreset(Player player, MarkerGui gui) {
        List<String> names = plugin.ambientSpawnService().presetNames();
        if (names.isEmpty()) {
            player.sendActionBar(Component.text("プリセットが定義されていません。", NamedTextColor.RED));
            return;
        }
        int idx = presetCycle.merge(player.getUniqueId(), 1, Integer::sum) - 1;
        String name = names.get(idx % names.size());
        SpawnPreset preset = plugin.ambientSpawnService().preset(name);
        if (preset == null) return;
        gui.setWorking(withCreaturesAndMax(gui.working(), preset.creatures(), preset.maxCount()));
        player.sendActionBar(Component.text("プリセット '" + name + "' を適用しました。", NamedTextColor.LIGHT_PURPLE));
    }

    /**
     * 生物枠のクリック処理。
     *  通常クリック: 割り当てる生物を次へ切り替え（ウェイトは維持、空欄ならウェイト1で新規追加）
     *  右クリック（シフト無し）: ウェイト -1
     *  シフト＋左クリック: ウェイト +1
     *  シフト＋右クリック: 枠を空にする
     */
    /**
     * 生物枠のクリック処理。左クリック（生物の切り替え）は、呼び出し元で
     * {@link CreaturePickerGui} を開く形に変わったため、ここには来ません
     * （shift・rightのどちらかは必ずtrueの状態で呼ばれます）。
     */
    /**
     * ウェイト増減のクリック処理。生物の切り替え（左クリック）は{@link CreaturePickerGui}へ、
     * 枠を空にする操作は{@link CreaturePickerGui}の専用ボタンへ、詳細設定
     * （シフト＋左クリック）は{@link CreatureDetailGui}へ、それぞれ移したため、
     * ここに来るのは shift+right（ウェイト+1）・right のみ（must be right==true）です。
     */
    private SpawnMarker updateCreatureSlot(Player player, SpawnMarker w, int index, boolean shift, boolean right) {
        List<WeightedCreature> list = new ArrayList<>(w.creatures());
        while (list.size() <= index) list.add(null);
        WeightedCreature current = list.get(index);

        if (shift && right) {
            // シフト＋右クリック：ウェイト +1（他の上書きはそのまま維持する）
            if (current != null) {
                list.set(index, new WeightedCreature(current.creatureId(), current.weight() + 1,
                        current.requiredTagsOverride(), current.override(), current.sizeDistributionTemplate(),
                        current.approachOverride()));
            }
        } else if (right) {
            // 右クリック：ウェイト -1（他の上書きはそのまま維持する）
            if (current != null && current.weight() > 1) {
                list.set(index, new WeightedCreature(current.creatureId(), current.weight() - 1,
                        current.requiredTagsOverride(), current.override(), current.sizeDistributionTemplate(),
                        current.approachOverride()));
            } else if (current != null) {
                player.sendActionBar(Component.text("ウェイトは1未満にできません。", NamedTextColor.RED));
            }
        }

        list.removeIf(java.util.Objects::isNull);
        if (list.size() > SpawnMarker.MAX_SPECIES) {
            list = list.subList(0, SpawnMarker.MAX_SPECIES);
        }
        return withCreatures(w, list);
    }

    private double clampMin(double value, double min) {
        return Math.max(min, value);
    }

    private boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }

    private int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    // ---- SpawnMarker はレコードなので、フィールド1つ変えるたびに作り直す ----

    private SpawnMarker withCreatures(SpawnMarker w, List<WeightedCreature> creatures) {
        return new SpawnMarker(w.id(), w.name(), w.worldName(), w.x(), w.y(), w.z(), List.copyOf(creatures),
                w.triggerRadius(), w.despawnRadius(), w.spawnRadius(), w.maxCount(), w.simultaneousMax(),
                w.spawnIntervalSeconds(), w.spawnChance(), w.catchWindowSeconds());
    }

    private SpawnMarker withCreaturesAndMax(SpawnMarker w, List<WeightedCreature> creatures, int maxCount) {
        return new SpawnMarker(w.id(), w.name(), w.worldName(), w.x(), w.y(), w.z(), List.copyOf(creatures),
                w.triggerRadius(), w.despawnRadius(), w.spawnRadius(), Math.max(1, maxCount), w.simultaneousMax(),
                w.spawnIntervalSeconds(), w.spawnChance(), w.catchWindowSeconds());
    }

    private SpawnMarker withTriggerRadius(SpawnMarker w, double v) {
        return new SpawnMarker(w.id(), w.name(), w.worldName(), w.x(), w.y(), w.z(), w.creatures(),
                v, w.despawnRadius(), w.spawnRadius(), w.maxCount(), w.simultaneousMax(),
                w.spawnIntervalSeconds(), w.spawnChance(), w.catchWindowSeconds());
    }

    private SpawnMarker withDespawnRadius(SpawnMarker w, double v) {
        return new SpawnMarker(w.id(), w.name(), w.worldName(), w.x(), w.y(), w.z(), w.creatures(),
                w.triggerRadius(), v, w.spawnRadius(), w.maxCount(), w.simultaneousMax(),
                w.spawnIntervalSeconds(), w.spawnChance(), w.catchWindowSeconds());
    }

    private SpawnMarker withSpawnRadius(SpawnMarker w, double v) {
        return new SpawnMarker(w.id(), w.name(), w.worldName(), w.x(), w.y(), w.z(), w.creatures(),
                w.triggerRadius(), w.despawnRadius(), v, w.maxCount(), w.simultaneousMax(),
                w.spawnIntervalSeconds(), w.spawnChance(), w.catchWindowSeconds());
    }

    private SpawnMarker withMaxCount(SpawnMarker w, int v) {
        return new SpawnMarker(w.id(), w.name(), w.worldName(), w.x(), w.y(), w.z(), w.creatures(),
                w.triggerRadius(), w.despawnRadius(), w.spawnRadius(), v, w.simultaneousMax(),
                w.spawnIntervalSeconds(), w.spawnChance(), w.catchWindowSeconds());
    }

    private SpawnMarker withSimultaneousMax(SpawnMarker w, int v) {
        return new SpawnMarker(w.id(), w.name(), w.worldName(), w.x(), w.y(), w.z(), w.creatures(),
                w.triggerRadius(), w.despawnRadius(), w.spawnRadius(), w.maxCount(), Math.max(1, v),
                w.spawnIntervalSeconds(), w.spawnChance(), w.catchWindowSeconds());
    }

    private SpawnMarker withSpawnInterval(SpawnMarker w, double v) {
        return new SpawnMarker(w.id(), w.name(), w.worldName(), w.x(), w.y(), w.z(), w.creatures(),
                w.triggerRadius(), w.despawnRadius(), w.spawnRadius(), w.maxCount(), w.simultaneousMax(),
                v, w.spawnChance(), w.catchWindowSeconds());
    }

    private SpawnMarker withCatchWindow(SpawnMarker w, double v) {
        return new SpawnMarker(w.id(), w.name(), w.worldName(), w.x(), w.y(), w.z(), w.creatures(),
                w.triggerRadius(), w.despawnRadius(), w.spawnRadius(), w.maxCount(), w.simultaneousMax(),
                w.spawnIntervalSeconds(), w.spawnChance(), v);
    }

    private SpawnMarker withSpawnChance(SpawnMarker w, double v) {
        return new SpawnMarker(w.id(), w.name(), w.worldName(), w.x(), w.y(), w.z(), w.creatures(),
                w.triggerRadius(), w.despawnRadius(), w.spawnRadius(), w.maxCount(), w.simultaneousMax(),
                w.spawnIntervalSeconds(), Math.max(0.0, Math.min(1.0, v)), w.catchWindowSeconds());
    }

    private SpawnMarker withName(SpawnMarker w, String newName) {
        return new SpawnMarker(w.id(), newName, w.worldName(), w.x(), w.y(), w.z(), w.creatures(),
                w.triggerRadius(), w.despawnRadius(), w.spawnRadius(), w.maxCount(), w.simultaneousMax(),
                w.spawnIntervalSeconds(), w.spawnChance(), w.catchWindowSeconds());
    }
}
