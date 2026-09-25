package jp.mushitori.service;

import jp.mushitori.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小銭入れ・財布。虫かご（{@link CageService}）と同じ考え方の、専用の入れ物です。
 * 小銭入れには硬貨だけ、財布には紙幣・硬貨どちらのお金も入れられます
 * （実際の判定・容量の強制は {@link jp.mushitori.listener.PurseListener} が
 * クリック後に中身を検証して行います）。
 *
 * <p>種類は config.yml で付けた文字列ID（例: "coin_purse", "wallet"）で管理します。
 * 中身の保存方式は虫かごと同じです（アイテムをNBTごとバイト列にして、PDC内の
 * 入れ子コンテナに並べる）。</p>
 */
public final class PurseService {

    /** 何を入れられるか。 */
    public enum Filter {
        /** 硬貨のみ（小銭入れ）。 */
        COIN_ONLY,
        /** 紙幣・硬貨どちらでも（財布）。 */
        ANY_MONEY
    }

    /** 1種類ぶんの小銭入れ・財布。 */
    public record Purse(String id, String name, Material material, @Nullable Integer customModelData,
                        int capacity, int price, Filter filter) {
    }

    private static final String NAMESPACE = "summering";

    private final Map<String, Purse> purses = new LinkedHashMap<>();

    public void load(@Nullable ConfigurationSection root) {
        purses.clear();
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;

            Material m = Material.matchMaterial(s.getString("material", "LEATHER"));
            Filter filter = "coins-only".equalsIgnoreCase(s.getString("filter", "any-money"))
                    ? Filter.COIN_ONLY : Filter.ANY_MONEY;
            purses.put(id, new Purse(
                    id,
                    s.getString("name", id),
                    m == null ? Material.LEATHER : m,
                    s.contains("custom-model-data") ? s.getInt("custom-model-data") : null,
                    Math.max(1, s.getInt("capacity", 9)),
                    s.getInt("price", 0),
                    filter));
        }
    }

    @Nullable
    public Purse purse(String id) {
        return purses.get(id);
    }

    public List<Purse> allPurses() {
        return new ArrayList<>(purses.values());
    }

    public List<String> purseIds() {
        return new ArrayList<>(purses.keySet());
    }

    /** 新品の（空の）小銭入れ・財布アイテムを作る。IDが存在しなければ null。 */
    @Nullable
    public ItemStack createPurseItem(String id) {
        Purse purse = purses.get(id);
        if (purse == null) return null;

        ItemStack item = new ItemStack(purse.material());
        item.editMeta(meta -> {
            meta.displayName(Component.text(purse.name(), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            if (purse.customModelData() != null) meta.setCustomModelData(purse.customModelData());
            meta.getPersistentDataContainer().set(Keys.PURSE_ID, PersistentDataType.STRING, id);
        });
        setContents(item, List.of());
        return item;
    }

    public boolean isPurse(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(Keys.PURSE_ID, PersistentDataType.STRING);
    }

    /** そのアイテムが小銭入れ・財布なら、収納できる最大数（IDが不明なら0）。 */
    public int capacityOf(@Nullable ItemStack item) {
        Purse purse = purseOf(item);
        return purse == null ? 0 : purse.capacity();
    }

    /** そのアイテムが小銭入れ・財布なら、何を入れられるか（IDが不明なら null）。 */
    @Nullable
    public Filter filterOf(@Nullable ItemStack item) {
        Purse purse = purseOf(item);
        return purse == null ? null : purse.filter();
    }

    @Nullable
    public String idOf(@Nullable ItemStack item) {
        if (!isPurse(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(Keys.PURSE_ID, PersistentDataType.STRING);
    }

    @Nullable
    private Purse purseOf(@Nullable ItemStack item) {
        String id = idOf(item);
        return id == null ? null : purses.get(id);
    }

    /** 中身を読み出す（NBTごと復元済みのItemStack一覧）。 */
    public List<ItemStack> contentsOf(@Nullable ItemStack purseItem) {
        List<ItemStack> result = new ArrayList<>();
        if (!isPurse(purseItem)) return result;

        PersistentDataContainer pdc = purseItem.getItemMeta().getPersistentDataContainer();
        PersistentDataContainer contents = pdc.get(Keys.PURSE_CONTENTS, PersistentDataType.TAG_CONTAINER);
        if (contents == null) return result;

        for (int i = 0; ; i++) {
            byte[] bytes = contents.get(slotKey(i), PersistentDataType.BYTE_ARRAY);
            if (bytes == null) break;
            try {
                result.add(ItemStack.deserializeBytes(bytes));
            } catch (Exception ignored) {
                // 壊れたデータはスキップ
            }
        }
        return result;
    }

    /** 中身を書き換える（表示・ロアも更新する）。呼び出し側で、事前に容量・種類の検証を済ませておくこと。 */
    public void setContents(ItemStack purseItem, List<ItemStack> contents) {
        purseItem.editMeta(meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            PersistentDataContainer sub = pdc.getAdapterContext().newPersistentDataContainer();

            int i = 0;
            for (ItemStack it : contents) {
                if (it == null || it.getType() == Material.AIR) continue;
                sub.set(slotKey(i), PersistentDataType.BYTE_ARRAY, it.serializeAsBytes());
                i++;
            }
            pdc.set(Keys.PURSE_CONTENTS, PersistentDataType.TAG_CONTAINER, sub);
            updateLore(meta, i);
        });
    }

    private void updateLore(ItemMeta meta, int count) {
        String id = meta.getPersistentDataContainer().get(Keys.PURSE_ID, PersistentDataType.STRING);
        Purse purse = id == null ? null : purses.get(id);
        int capacity = purse == null ? 0 : purse.capacity();
        Filter filter = purse == null ? Filter.ANY_MONEY : purse.filter();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(filter == Filter.COIN_ONLY ? "硬貨だけを入れられます。" : "紙幣・硬貨を入れられます。",
                NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("他のプレイヤーにそのまま渡せます。", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("収納数  " + count + " / " + capacity, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
    }

    private NamespacedKey slotKey(int index) {
        return new NamespacedKey(NAMESPACE, "slot_" + index);
    }
}
