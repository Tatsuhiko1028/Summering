package jp.mushitori.service;

import jp.mushitori.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 所持金。スコアボードではなく、プレイヤーの持ち物（紙幣・硬貨アイテムの組み合わせ）で
 * 管理します。
 *
 * <p>以前は「コイン」アイテム1個のスタック数＝所持金、という方式でしたが、
 * バニラのアイテムスタック数には1〜99という上限があり（{@code setMaxStackSize}に
 * それを超える値を渡すとエラーになります）、大きな金額を渡そうとするとエラーで
 * 落ちる不具合がありました。今回、複数の額面（紙幣・硬貨）を組み合わせて渡す方式に
 * 作り直しました。</p>
 *
 * <p>お金を渡すときは、額面の大きいものから順に、できるだけ少ない枚数になるよう
 * 貪欲法で組み合わせます（例：1211円 → 1000円札1枚、100円玉2枚、10円玉1枚、1円玉1枚）。
 * 所持金の合計は、持ち物の中にある額面アイテムをすべて数えて求めます。</p>
 */
public final class MoneyService {

    /** 紙幣か硬貨か。小銭入れ・財布での判定に使う。 */
    public enum Type {
        BILL, COIN
    }

    /** 1種類ぶんの額面（紙幣・硬貨）。 */
    public record Denomination(int value, String name, Material material,
                               @Nullable Integer customModelData, Type type) {
    }

    private String unit = "円";
    /** 額面の大きい順。 */
    private final List<Denomination> denominations = new ArrayList<>();

    public void load(@Nullable ConfigurationSection section) {
        denominations.clear();
        unit = section != null ? section.getString("unit", "円") : "円";

        ConfigurationSection denomSection = section != null ? section.getConfigurationSection("denominations") : null;
        if (denomSection != null) {
            for (String key : denomSection.getKeys(false)) {
                int value;
                try {
                    value = Integer.parseInt(key.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (value <= 0) continue;
                ConfigurationSection s = denomSection.getConfigurationSection(key);
                if (s == null) continue;
                Material m = Material.matchMaterial(s.getString("material", "PAPER"));
                Integer cmd = s.contains("custom-model-data") ? s.getInt("custom-model-data") : null;
                Type type = parseType(s.getString("type"), value);
                denominations.add(new Denomination(value, s.getString("name", value + unit),
                        m == null ? Material.PAPER : m, cmd, type));
            }
        }
        if (denominations.isEmpty()) {
            loadDefaults();
        }
        denominations.sort((a, b) -> Integer.compare(b.value(), a.value()));
    }

    /** type未指定の場合は、値が1000以上なら紙幣、未満なら硬貨とみなす（既定の額面と一致する分け方）。 */
    private Type parseType(@Nullable String raw, int value) {
        if (raw != null) {
            try {
                return Type.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // フォールバックへ
            }
        }
        return value >= 1000 ? Type.BILL : Type.COIN;
    }

    private void loadDefaults() {
        denominations.add(new Denomination(10000, "1万円札", Material.PAPER, null, Type.BILL));
        denominations.add(new Denomination(5000, "5000円札", Material.PAPER, null, Type.BILL));
        denominations.add(new Denomination(1000, "1000円札", Material.PAPER, null, Type.BILL));
        denominations.add(new Denomination(500, "500円玉", Material.GOLD_NUGGET, null, Type.COIN));
        denominations.add(new Denomination(100, "100円玉", Material.IRON_NUGGET, null, Type.COIN));
        denominations.add(new Denomination(50, "50円玉", Material.IRON_NUGGET, null, Type.COIN));
        denominations.add(new Denomination(10, "10円玉", Material.COPPER_INGOT, null, Type.COIN));
        denominations.add(new Denomination(5, "5円玉", Material.COPPER_INGOT, null, Type.COIN));
        denominations.add(new Denomination(1, "1円玉", Material.COPPER_INGOT, null, Type.COIN));
    }

    public String unit() {
        return unit;
    }

    /** プレイヤーの持ち物にある、お金の額面アイテムをすべて数えて合計する。 */
    public int get(Player player) {
        long total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            Integer value = denominationValueOf(item);
            if (value != null) {
                total += (long) value * item.getAmount();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** 所持金をちょうど amount にする（0以下なら持っているお金をすべて取り除く）。 */
    public void set(Player player, int amount) {
        removeAllMoney(player);
        giveAmount(player, Math.max(0, amount));
    }

    /** delta（負数も可）ぶん増減させ、増減後の所持金を返す。 */
    public int add(Player player, int delta) {
        if (delta > 0) {
            giveAmount(player, delta);
        } else if (delta < 0) {
            withdraw(player, -delta);
        }
        return get(player);
    }

    /** 足りていれば引いて true。 */
    public boolean withdraw(Player player, int amount) {
        if (amount <= 0) return true;
        int now = get(player);
        if (now < amount) return false;
        set(player, now - amount);
        return true;
    }

    public String format(int amount) {
        return String.format("%,d%s", amount, unit);
    }

    /** そのアイテムが、このプラグインのお金（紙幣・硬貨）かどうか。 */
    public boolean isMoney(@Nullable ItemStack item) {
        return denominationValueOf(item) != null;
    }

    /** そのアイテムが硬貨（小銭）かどうか。 */
    public boolean isCoin(@Nullable ItemStack item) {
        return typeOf(item) == Type.COIN;
    }

    /** そのアイテムが紙幣かどうか。 */
    public boolean isBill(@Nullable ItemStack item) {
        return typeOf(item) == Type.BILL;
    }

    @Nullable
    private Type typeOf(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer()
                .get(Keys.MONEY_TYPE, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return Type.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private Integer denominationValueOf(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(Keys.MONEY_DENOMINATION, PersistentDataType.INTEGER);
    }

    private void removeAllMoney(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isMoney(inv.getItem(i))) {
                inv.setItem(i, null);
            }
        }
    }

    /** amountぶんを、額面の大きい方から貪欲法で組み合わせ、できるだけ少ない枚数になるよう渡す。 */
    /**
     * amountぶんの紙幣・硬貨アイテムを、誰にも渡さず「内訳」として組み立てて返す
     * （財布・小銭入れへの入金など、直接インベントリへ渡さずに扱いたい場面で使う）。
     * 額面の大きい方から、できるだけ少ない枚数になるよう組み合わせる（{@link #giveAmount}と同じ考え方）。
     * 1種類あたり、そのアイテムの最大スタック数を超えないよう複数スタックに分ける。
     */
    public List<ItemStack> denominate(int amount) {
        List<ItemStack> result = new ArrayList<>();
        int remaining = amount;
        for (Denomination d : denominations) {
            if (remaining < d.value()) continue;
            int count = remaining / d.value();
            remaining -= count * d.value();

            int maxStack = Math.max(1, Math.min(64, maxStackSizeOf(d.material())));
            while (count > 0) {
                int stackAmount = Math.min(count, maxStack);
                result.add(createMoneyItem(d, stackAmount));
                count -= stackAmount;
            }
        }
        return result;
    }

    private void giveAmount(Player player, int amount) {
        int remaining = amount;
        for (Denomination d : denominations) {
            if (remaining < d.value()) continue;
            int count = remaining / d.value();
            remaining -= count * d.value();
            giveStacks(player, d, count);
        }
        // denominationsの中に額面1が無い等、割り切れない端数が残っても諦める
        // （額面1を用意しておけば、常にちょうど渡せます）
    }

    private void giveStacks(Player player, Denomination d, int count) {
        int maxStack = Math.max(1, Math.min(64, maxStackSizeOf(d.material())));
        while (count > 0) {
            int stackAmount = Math.min(count, maxStack);
            ItemStack item = createMoneyItem(d, stackAmount);
            var leftover = player.getInventory().addItem(item);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            count -= stackAmount;
        }
    }

    private int maxStackSizeOf(Material material) {
        return material.getMaxStackSize() > 0 ? material.getMaxStackSize() : 64;
    }

    private ItemStack createMoneyItem(Denomination d, int amount) {
        ItemStack item = new ItemStack(d.material(), amount);
        item.editMeta(meta -> {
            meta.displayName(Component.text(d.name(), NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(format(d.value()), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            if (d.customModelData() != null) meta.setCustomModelData(d.customModelData());
            meta.getPersistentDataContainer().set(Keys.MONEY_DENOMINATION, PersistentDataType.INTEGER, d.value());
            meta.getPersistentDataContainer().set(Keys.MONEY_TYPE, PersistentDataType.STRING, d.type().name());
        });
        return item;
    }
}
