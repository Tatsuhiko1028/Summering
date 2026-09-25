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
 * 虫かご。手持ちの状態で右クリックするとGUIが開き、虫のアイテムを出し入れできます。
 * 中身はアイテム自身（PersistentDataContainer）に保存されるため、他のプレイヤーに
 * そのまま渡すこともできます。虫（{@link jp.mushitori.model.Category#BUG}）以外は
 * 入れられません（実際の判定・容量の強制は {@link jp.mushitori.listener.CageListener} が
 * クリック後に中身を検証して行います）。
 *
 * <p>種類は tier番号ではなく、config.yml で付けた文字列ID（例: "small_cage",
 * "large_cage"）で管理します。</p>
 *
 * <p>中身は「入っているアイテムをそのままNBTごと1つずつバイト列にして、PDC内の
 * 入れ子コンテナに"slot_0", "slot_1", ... というキーで並べる」形で保存しています。</p>
 */
public final class CageService {

    /** 1種類ぶんの虫かご。 */
    public record Cage(String id, String name, Material material, @Nullable Integer customModelData,
                       int capacity, int price) {
    }

    private static final String NAMESPACE = "summering";

    private final Map<String, Cage> cages = new LinkedHashMap<>();

    public void load(ConfigurationSection root) {
        cages.clear();
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;

            Material m = Material.matchMaterial(s.getString("material", "GLASS_BOTTLE"));
            cages.put(id, new Cage(
                    id,
                    s.getString("name", "虫かご (" + id + ")"),
                    m == null ? Material.GLASS_BOTTLE : m,
                    s.contains("custom-model-data") ? s.getInt("custom-model-data") : null,
                    Math.max(1, s.getInt("capacity", 9)),
                    s.getInt("price", 0)));
        }
    }

    @Nullable
    public Cage cage(String id) {
        return cages.get(id);
    }

    public List<Cage> allCages() {
        return new ArrayList<>(cages.values());
    }

    public List<String> cageIds() {
        return new ArrayList<>(cages.keySet());
    }

    /** 新品の（空の）虫かごアイテムを作る。IDが存在しなければ null。 */
    @Nullable
    public ItemStack createCageItem(String id) {
        Cage cage = cages.get(id);
        if (cage == null) return null;

        ItemStack item = new ItemStack(cage.material());
        item.editMeta(meta -> {
            meta.displayName(Component.text(cage.name(), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            if (cage.customModelData() != null) meta.setCustomModelData(cage.customModelData());
            meta.getPersistentDataContainer().set(Keys.CAGE_ID, PersistentDataType.STRING, id);
        });
        setContents(item, List.of());
        return item;
    }

    public boolean isCage(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(Keys.CAGE_ID, PersistentDataType.STRING);
    }

    /** そのアイテムが虫かごなら、収納できる最大数（IDが不明なら0）。 */
    public int capacityOf(@Nullable ItemStack item) {
        Cage cage = cageOf(item);
        return cage == null ? 0 : cage.capacity();
    }

    @Nullable
    public String idOf(@Nullable ItemStack item) {
        if (!isCage(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(Keys.CAGE_ID, PersistentDataType.STRING);
    }

    @Nullable
    private Cage cageOf(@Nullable ItemStack item) {
        String id = idOf(item);
        return id == null ? null : cages.get(id);
    }

    /** 虫かごの中身を読み出す（NBTごと復元済みのItemStack一覧）。 */
    public List<ItemStack> contentsOf(@Nullable ItemStack cageItem) {
        List<ItemStack> result = new ArrayList<>();
        if (!isCage(cageItem)) return result;

        PersistentDataContainer pdc = cageItem.getItemMeta().getPersistentDataContainer();
        PersistentDataContainer contents = pdc.get(Keys.CAGE_CONTENTS, PersistentDataType.TAG_CONTAINER);
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

    /** 虫かごの中身を書き換える（表示・ロアも更新する）。呼び出し側で、事前に容量・種類の検証を済ませておくこと。 */
    public void setContents(ItemStack cageItem, List<ItemStack> contents) {
        cageItem.editMeta(meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            PersistentDataContainer sub = pdc.getAdapterContext().newPersistentDataContainer();

            int i = 0;
            for (ItemStack it : contents) {
                if (it == null || it.getType() == Material.AIR) continue;
                sub.set(slotKey(i), PersistentDataType.BYTE_ARRAY, it.serializeAsBytes());
                i++;
            }
            pdc.set(Keys.CAGE_CONTENTS, PersistentDataType.TAG_CONTAINER, sub);
            updateLore(meta, i);
        });
    }

    private void updateLore(ItemMeta meta, int count) {
        String id = meta.getPersistentDataContainer().get(Keys.CAGE_ID, PersistentDataType.STRING);
        Cage cage = id == null ? null : cages.get(id);
        int capacity = cage == null ? 0 : cage.capacity();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("虫だけを入れられます。", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
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
