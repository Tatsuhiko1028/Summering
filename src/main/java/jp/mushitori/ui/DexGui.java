package jp.mushitori.ui;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.data.PlayerDex;
import jp.mushitori.model.Category;
import jp.mushitori.model.Creature;
import jp.mushitori.model.Rarity;
import jp.mushitori.model.SizeTier;
import jp.mushitori.service.CatchService;
import jp.mushitori.service.DexService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 図鑑のチェストGUI。 */
public final class DexGui implements InventoryHolder {

    public enum Type {MAIN, CATEGORY, ACHIEVEMENTS}

    public static final int PER_PAGE = 45;

    private final Type type;
    private final Category category;
    private final int page;
    private Inventory inventory;

    private DexGui(Type type, @Nullable Category category, int page) {
        this.type = type;
        this.category = category;
        this.page = page;
    }

    public Type type() {
        return type;
    }

    @Nullable
    public Category category() {
        return category;
    }

    public int page() {
        return page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    // =========================== メインメニュー ===========================

    public static void openMain(MushitoriPlugin plugin, Player player) {
        DexGui gui = new DexGui(Type.MAIN, null, 0);
        gui.inventory = Bukkit.createInventory(gui, 27, Component.text("むしとり図鑑"));

        PlayerDex dex = plugin.dexManager().get(player.getUniqueId());
        Map<Category, int[]> progress = plugin.dexService().progress(dex);

        int slot = 10;
        for (Category cat : Category.values()) {
            int[] p = progress.getOrDefault(cat, new int[]{0, 0});
            gui.inventory.setItem(slot++, icon(cat.icon(),
                    Component.text(cat.displayName(), cat.color()),
                    List.of(
                            gray(p[0] + " / " + p[1] + " 種"),
                            gray(percent(p[0], p[1]) + "%"),
                            Component.empty(),
                            gray("クリックで一覧"))));
        }

        int carrying = countCarrying(plugin, player);
        gui.inventory.setItem(14, icon(Material.WRITABLE_BOOK,
                Component.text("図鑑に登録する", NamedTextColor.YELLOW),
                List.of(
                        gray("未登録の持ち物: " + carrying + " 個"),
                        gray("クリックで登録画面を開きます"))));

        int done = (int) plugin.dexService().achievements().stream()
                .filter(a -> dex.achievements().containsKey(a.id())).count();
        gui.inventory.setItem(15, icon(Material.NETHER_STAR,
                Component.text("実績", NamedTextColor.LIGHT_PURPLE),
                List.of(gray(done + " / " + plugin.dexService().achievements().size() + " 達成"))));

        gui.inventory.setItem(16, icon(Material.GOLD_INGOT,
                Component.text("所持金", NamedTextColor.GOLD),
                List.of(gray(plugin.money().format(plugin.money().get(player))),
                        gray("クリックで貯金箱を開く"))));

        gui.inventory.setItem(26, icon(Material.BARRIER,
                Component.text("とじる", NamedTextColor.RED), List.of()));

        player.openInventory(gui.inventory);
    }

    // =========================== カテゴリ一覧 ===========================

    public static void openCategory(MushitoriPlugin plugin, Player player, Category category, int page) {
        List<Creature> list = plugin.creatures().byCategory(category);
        int maxPage = Math.max(0, (list.size() - 1) / PER_PAGE);
        int safePage = Math.max(0, Math.min(page, maxPage));

        DexGui gui = new DexGui(Type.CATEGORY, category, safePage);
        gui.inventory = Bukkit.createInventory(gui, 54,
                Component.text(category.displayName() + " 図鑑"));

        PlayerDex dex = plugin.dexManager().get(player.getUniqueId());

        for (int i = 0; i < PER_PAGE; i++) {
            int index = safePage * PER_PAGE + i;
            if (index >= list.size()) break;
            Creature c = list.get(index);
            PlayerDex.Entry e = dex.entry(c.id());
            gui.inventory.setItem(i, e == null ? unknownIcon() : creatureIcon(plugin, c, e));
        }

        if (safePage > 0) {
            gui.inventory.setItem(45, icon(Material.ARROW,
                    Component.text("前のページ", NamedTextColor.YELLOW), List.of()));
        }
        gui.inventory.setItem(49, icon(Material.BOOK,
                Component.text("メニューへもどる", NamedTextColor.YELLOW), List.of()));
        if (safePage < maxPage) {
            gui.inventory.setItem(53, icon(Material.ARROW,
                    Component.text("次のページ", NamedTextColor.YELLOW), List.of()));
        }

        player.openInventory(gui.inventory);
    }

    private static ItemStack unknownIcon() {
        return icon(Material.GRAY_DYE, Component.text("??????", NamedTextColor.DARK_GRAY),
                List.of(gray("まだ捕まえていません")));
    }

    private static ItemStack creatureIcon(MushitoriPlugin plugin, Creature c, PlayerDex.Entry e) {
        Rarity.Table rarities = plugin.catchService().rarities(c);
        SizeTier.Table sizeTiers = plugin.catchService().sizeTiers();

        List<Component> lore = new ArrayList<>();
        for (String d : c.description()) {
            lore.add(gray(d));
        }
        if (!c.habitat().isBlank()) {
            lore.add(gray("出現: " + c.habitat()));
        }
        lore.add(Component.empty());
        lore.add(white("捕獲数  " + e.count + " 匹"));
        lore.add(white("最大サイズ  " + CatchService.formatSize(e.maxCm)));
        lore.add(white("最小サイズ  " + CatchService.formatSize(e.minCm)));

        lore.add(Component.empty());
        lore.add(white("サイズ段階ごとの捕獲数"));
        for (SizeTier t : sizeTiers.all()) {
            Integer n = e.sizeTierCounts.get(t.key());
            if (n == null || n <= 0) continue;
            lore.add(Component.text("  " + t.name() + " ×" + n, NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
        }

        lore.add(Component.empty());
        lore.add(white("レア度ごとの捕獲数"));
        for (Rarity r : rarities.all()) {
            Integer n = e.rarityCounts.get(r.key());
            if (n == null || n <= 0) continue;
            lore.add(Component.text("  " + r.name() + " ×" + n, r.color())
                    .decoration(TextDecoration.ITALIC, false));
        }

        lore.add(Component.empty());
        lore.add(darkGray("はじめて  " + CatchService.formatDate(e.firstAt)));
        lore.add(darkGray("さいきん  " + CatchService.formatDate(e.lastAt)));
        lore.add(darkGray("標準の売値  " + plugin.money().format(c.basePrice())));

        ItemStack item = icon(c.material(), Component.text(c.name(), NamedTextColor.WHITE), lore);
        if (c.customModelData() != null) {
            item.editMeta(meta -> meta.setCustomModelData(c.customModelData()));
        }
        return item;
    }

    // =========================== 実績 ===========================

    public static void openAchievements(MushitoriPlugin plugin, Player player) {
        DexGui gui = new DexGui(Type.ACHIEVEMENTS, null, 0);
        gui.inventory = Bukkit.createInventory(gui, 54, Component.text("実績"));

        PlayerDex dex = plugin.dexManager().get(player.getUniqueId());
        List<DexService.Achievement> list = plugin.dexService().achievements();

        for (int i = 0; i < list.size() && i < PER_PAGE; i++) {
            DexService.Achievement a = list.get(i);
            Long at = dex.achievements().get(a.id());
            if (at != null) {
                gui.inventory.setItem(i, icon(Material.NETHER_STAR,
                        Component.text(a.name(), NamedTextColor.GOLD),
                        List.of(gray(a.description()), darkGray("達成: " + CatchService.formatDate(at)))));
            } else {
                gui.inventory.setItem(i, icon(Material.GRAY_DYE,
                        Component.text(a.name(), NamedTextColor.DARK_GRAY),
                        List.of(gray(a.description()), darkGray("未達成"))));
            }
        }
        gui.inventory.setItem(49, icon(Material.BOOK,
                Component.text("メニューへもどる", NamedTextColor.YELLOW), List.of()));

        player.openInventory(gui.inventory);
    }

    // =========================== 共通 ===========================

    public static int countCarrying(MushitoriPlugin plugin, Player player) {
        int n = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            var data = plugin.catchService().read(item);
            if (data != null && !data.isRegistered()) {
                n += Math.max(1, item.getAmount());
            }
        }
        return n;
    }

    private static int percent(int found, int total) {
        return total <= 0 ? 0 : (int) Math.floor(found * 100.0 / total);
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) meta.lore(lore);
        });
        return item;
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component darkGray(String text) {
        return Component.text(text, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component white(String text) {
        return Component.text(text, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
    }
}
