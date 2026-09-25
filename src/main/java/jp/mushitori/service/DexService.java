package jp.mushitori.service;

import jp.mushitori.data.DexManager;
import jp.mushitori.data.PlayerDex;
import jp.mushitori.model.CatchData;
import jp.mushitori.model.Category;
import jp.mushitori.model.Creature;
import jp.mushitori.registry.CreatureRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** 図鑑への一括登録・実績・売却。 */
public final class DexService {

    /** 実績定義。 */
    public record Achievement(String id, String name, String description, Predicate<PlayerDex> condition) {
    }

    /** 1匹ぶんの登録結果。 */
    public record RegisterResult(Creature creature, CatchData data, boolean isNew,
                                 boolean newBiggest, boolean newSmallest) {
    }

    /** 登録処理全体の結果。 */
    public record RegisterSummary(List<RegisterResult> results, int skipped,
                                  List<Achievement> unlocked) {
        public boolean isEmpty() {
            return results.isEmpty();
        }

        public long newSpecies() {
            return results.stream().filter(RegisterResult::isNew).count();
        }
    }

    private final CreatureRegistry creatures;
    private final CatchService catchService;
    private final DexManager dexManager;
    private final MoneyService money;
    private final AdvancementService advancementService;

    private boolean allowOthersCatch = true;
    private boolean oncePerItem = true;
    private List<Achievement> achievements = List.of();

    public DexService(CreatureRegistry creatures, CatchService catchService,
                      DexManager dexManager, MoneyService money, AdvancementService advancementService) {
        this.creatures = creatures;
        this.catchService = catchService;
        this.dexManager = dexManager;
        this.money = money;
        this.advancementService = advancementService;
    }

    public void load(ConfigurationSection registerSection) {
        if (registerSection != null) {
            allowOthersCatch = registerSection.getBoolean("allow-others-catch", true);
            oncePerItem = registerSection.getBoolean("once-per-item", true);
        }
        rebuildAchievements();
    }

    /** いきものリストが変わるたびに実績も組み直す。 */
    public void rebuildAchievements() {
        List<Achievement> list = new ArrayList<>();
        list.add(new Achievement("first_catch", "はじめての一匹",
                "なにか1種類を図鑑に登録する",
                dex -> !dex.entries().isEmpty()));

        for (Category cat : Category.values()) {
            List<Creature> inCat = creatures.byCategory(cat);
            list.add(new Achievement("complete_" + cat.name().toLowerCase(java.util.Locale.ROOT),
                    cat.displayName() + " コンプリート",
                    cat.displayName() + " をすべて登録する",
                    dex -> !inCat.isEmpty() && inCat.stream().allMatch(c -> dex.has(c.id()))));
        }

        list.add(new Achievement("complete_all", "図鑑マスター",
                "すべてのいきものを登録する",
                dex -> creatures.size() > 0 && creatures.all().stream().allMatch(c -> dex.has(c.id()))));

        list.add(new Achievement("ur_hunter", "ウルトラレアハンター",
                "最高レア度のいきものを捕まえる",
                dex -> dex.entries().entrySet().stream().anyMatch(en -> {
                    Creature c = creatures.get(en.getKey());
                    if (c == null) return false;
                    var table = catchService.rarities(c).all();
                    if (table.isEmpty()) return false;
                    String topKey = table.get(table.size() - 1).key();
                    return en.getValue().rarityCounts.containsKey(topKey);
                })));

        list.add(new Achievement("catch_100", "むしとり名人",
                "のべ100匹を図鑑に登録する",
                dex -> dex.entries().values().stream().mapToInt(e -> e.count).sum() >= 100));

        achievements = List.copyOf(list);
    }

    public List<Achievement> achievements() {
        return achievements;
    }

    // ---- 一括登録 ----

    /**
     * インベントリ内の未登録のいきものアイテムを、まとめて図鑑に登録する。
     * 登録したアイテムには「〇〇が とった虫 / 釣った魚」の判定（PDC + lore）が付きます。
     */
    public RegisterSummary registerInventory(Player player) {
        PlayerDex dex = dexManager.get(player.getUniqueId());
        List<RegisterResult> results = new ArrayList<>();
        int skipped = 0;

        for (ItemStack item : player.getInventory().getStorageContents()) {
            CatchData data = catchService.read(item);
            if (data == null) continue;

            Creature creature = creatures.get(data.creatureId());
            if (creature == null) continue;

            if (oncePerItem && data.isRegistered()) {
                skipped++;
                continue;
            }
            if (!allowOthersCatch && !data.catcherUuid().equals(player.getUniqueId())) {
                skipped++;
                continue;
            }

            int amount = Math.max(1, item.getAmount());
            PlayerDex.RecordResult first = null;
            for (int i = 0; i < amount; i++) {
                PlayerDex.RecordResult rr = dex.record(creature.id(), data.sizeCm(), data.sizeTierKey(), data.rarityKey(), data.caughtAt());
                if (first == null) first = rr;
            }

            CatchData stamped = data.withRegistration(player.getUniqueId(), player.getName());
            catchService.stampRegistration(item, creature, stamped);

            results.add(new RegisterResult(creature, stamped, first.isNew(), first.newBiggest(), first.newSmallest()));
        }

        List<Achievement> unlocked = checkAchievements(player, dex);
        if (!results.isEmpty() || !unlocked.isEmpty()) {
            dexManager.saveAsync(player.getUniqueId());
        }
        return new RegisterSummary(results, skipped, unlocked);
    }

    /**
     * 1つのアイテム（スタックまるごと）を図鑑に登録する。図鑑登録用GUIの
     * 「登録するもの」エリアなど、個別に登録したい場面で使う。
     * 登録できなければ（いきものアイテムでない、既に登録済み、他人の捕獲で
     * allow-others-catchがfalse、等）何もせず、結果は空になる。
     */
    public RegisterSummary registerOne(Player player, @Nullable ItemStack item) {
        CatchData data = catchService.read(item);
        if (data == null) return new RegisterSummary(List.of(), 0, List.of());

        Creature creature = creatures.get(data.creatureId());
        if (creature == null) return new RegisterSummary(List.of(), 0, List.of());

        if (oncePerItem && data.isRegistered()) {
            return new RegisterSummary(List.of(), 1, List.of());
        }
        if (!allowOthersCatch && !data.catcherUuid().equals(player.getUniqueId())) {
            return new RegisterSummary(List.of(), 1, List.of());
        }

        PlayerDex dex = dexManager.get(player.getUniqueId());
        int amount = Math.max(1, item.getAmount());
        PlayerDex.RecordResult first = null;
        for (int i = 0; i < amount; i++) {
            PlayerDex.RecordResult rr = dex.record(creature.id(), data.sizeCm(), data.sizeTierKey(), data.rarityKey(), data.caughtAt());
            if (first == null) first = rr;
        }

        CatchData stamped = data.withRegistration(player.getUniqueId(), player.getName());
        catchService.stampRegistration(item, creature, stamped);

        RegisterResult result = new RegisterResult(creature, stamped, first.isNew(), first.newBiggest(), first.newSmallest());
        List<Achievement> unlocked = checkAchievements(player, dex);
        dexManager.saveAsync(player.getUniqueId());
        return new RegisterSummary(List.of(result), 0, unlocked);
    }

    private List<Achievement> checkAchievements(Player player, PlayerDex dex) {
        List<Achievement> unlocked = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Achievement a : achievements) {
            if (dex.achievements().containsKey(a.id())) continue;
            boolean ok;
            try {
                ok = a.condition().test(dex);
            } catch (Exception e) {
                ok = false;
            }
            if (ok) {
                dex.unlockAchievement(a.id(), now);
                unlocked.add(a);
                advancementService.grant(player, a.id());
            }
        }
        return unlocked;
    }

    /**
     * すでに図鑑側で達成済みの実績を、Minecraft本体の進捗タブにも反映し直す。
     * ログイン時や、進捗データパックを後から追加したときの取りこぼし対策。
     */
    public void syncAdvancements(Player player) {
        PlayerDex dex = dexManager.get(player.getUniqueId());
        advancementService.grantRoot(player);
        for (String id : dex.achievements().keySet()) {
            advancementService.grant(player, id);
        }
    }

    /** 登録結果をチャットに出す。 */
    public void announce(Player player, RegisterSummary summary) {
        if (summary.isEmpty()) {
            player.sendMessage(Component.text("登録できるいきものを持っていません。", NamedTextColor.GRAY));
            if (summary.skipped() > 0) {
                player.sendMessage(Component.text("（登録済み " + summary.skipped() + " 個はとばしました）", NamedTextColor.DARK_GRAY));
            }
            return;
        }

        player.sendMessage(Component.text("── 図鑑に登録しました ──", NamedTextColor.GOLD));
        for (RegisterResult r : summary.results()) {
            var rarity = catchService.rarities(r.creature()).get(r.data().rarityKey());
            Component line = Component.text("・" + r.creature().name() + "  "
                            + CatchService.formatSize(r.data().sizeCm()) + "  ", NamedTextColor.WHITE)
                    .append(Component.text(rarity.name(), rarity.color()));
            if (r.isNew()) {
                line = line.append(Component.text("  新種！", NamedTextColor.AQUA));
            }
            if (r.newBiggest()) {
                line = line.append(Component.text("  最大記録更新！", NamedTextColor.GREEN));
            }
            if (r.newSmallest()) {
                line = line.append(Component.text("  最小記録更新！", NamedTextColor.GREEN));
            }
            player.sendMessage(line);
        }
        if (summary.skipped() > 0) {
            player.sendMessage(Component.text("登録済み " + summary.skipped() + " 個はとばしました。", NamedTextColor.DARK_GRAY));
        }
        for (Achievement a : summary.unlocked()) {
            player.getServer().broadcast(Component.text("★ " + player.getName()
                    + " が実績「" + a.name() + "」を達成しました！", NamedTextColor.GOLD));
        }
    }

    // ---- 売却 ----

    /** インベントリのいきものアイテムをすべて売る。 */
    public int sellInventory(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            int unitPrice = catchService.priceOf(item);
            if (unitPrice <= 0) continue;
            total += unitPrice * Math.max(1, item.getAmount());
            item.setAmount(0);
        }
        if (total > 0) {
            money.add(player, total);
        }
        return total;
    }

    /** {@link #sellInventory(Player)} と同じ計算を、実際には売らずに合計額だけ確認する。 */
    public int previewSellValue(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            int unitPrice = catchService.priceOf(item);
            if (unitPrice <= 0) continue;
            total += unitPrice * Math.max(1, item.getAmount());
        }
        return total;
    }

    /** カテゴリごとの登録状況。 */
    public Map<Category, int[]> progress(PlayerDex dex) {
        Map<Category, int[]> map = new LinkedHashMap<>();
        for (Category cat : Category.values()) {
            List<Creature> list = creatures.byCategory(cat);
            int found = (int) list.stream().filter(c -> dex.has(c.id())).count();
            map.put(cat, new int[]{found, list.size()});
        }
        return map;
    }
}
