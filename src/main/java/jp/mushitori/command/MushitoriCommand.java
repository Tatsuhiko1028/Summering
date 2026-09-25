package jp.mushitori.command;

import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.Creature;
import jp.mushitori.ui.DexGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MushitoriCommand implements CommandExecutor, TabCompleter {

    private final MushitoriPlugin plugin;

    public MushitoriCommand(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "dex" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "dex" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("プレイヤーから実行してください。");
                    return true;
                }
                DexGui.openMain(plugin, player);
            }
            case "register" -> {
                if (!(sender instanceof Player player)) return true;
                var summary = plugin.dexService().registerInventory(player);
                plugin.dexService().announce(player, summary);
            }
            case "sell" -> {
                if (!(sender instanceof Player player)) return true;
                int total = plugin.dexService().sellInventory(player);
                if (total <= 0) {
                    player.sendMessage(Component.text("売れるいきものを持っていません。", NamedTextColor.GRAY));
                } else {
                    player.sendMessage(Component.text("いきものを売って " + plugin.money().format(total)
                            + " を手に入れた！  所持金: " + plugin.money().format(plugin.money().get(player)),
                            NamedTextColor.GOLD));
                }
            }
            case "give" -> {
                if (!checkAdmin(sender)) return true;
                if (!(sender instanceof Player player)) return true;
                if (args.length < 2) {
                    sender.sendMessage("/mushitori give <book|net|rod|cage|purse|wand> [ID]");
                    return true;
                }
                String arg2 = args.length >= 3 ? args[2] : null;
                ItemStack item = switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "book", "guide" -> plugin.gear().createGuide();
                    case "net" -> plugin.gear().createNet(arg2 != null ? arg2 : firstOrNull(plugin.gear().netIds()));
                    case "rod" -> plugin.gear().createRod(arg2 != null ? arg2 : firstOrNull(plugin.gear().rodIds()));
                    case "cage" -> plugin.cageService().createCageItem(
                            arg2 != null ? arg2 : firstOrNull(plugin.cageService().cageIds()));
                    case "purse" -> plugin.purseService().createPurseItem(
                            arg2 != null ? arg2 : firstOrNull(plugin.purseService().purseIds()));
                    case "wand" -> plugin.ambientSpawnService().createWandItem();
                    default -> null;
                };
                if (item == null) {
                    sender.sendMessage(Component.text("そのアイテムはありません。", NamedTextColor.RED));
                    return true;
                }
                player.getInventory().addItem(item);
            }
            case "spawn" -> {
                if (!checkAdmin(sender)) return true;
                if (!(sender instanceof Player player)) return true;
                if (args.length < 2) {
                    sender.sendMessage("/mushitori spawn <いきものID> [匹数]");
                    return true;
                }
                Creature creature = plugin.creatures().get(args[1]);
                if (creature == null) {
                    sender.sendMessage(Component.text("不明なID: " + args[1], NamedTextColor.RED));
                    return true;
                }
                int amount = args.length >= 3 ? Math.max(1, Math.min(20, parseInt(args[2], 1))) : 1;
                int spawned = 0;
                for (int i = 0; i < amount; i++) {
                    if (plugin.spawnService().spawn(creature, player.getLocation()) != null) spawned++;
                }
                if (spawned == 0) {
                    sender.sendMessage(Component.text(
                            creature.name() + " には entity が設定されていません（creatures.yml）。", NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text(creature.name() + " を " + spawned + " 匹湧かせました。",
                            NamedTextColor.GREEN));
                }
            }
            case "catch" -> {
                if (!checkAdmin(sender)) return true;
                if (!(sender instanceof Player player)) return true;
                if (args.length < 2) {
                    sender.sendMessage("/mushitori catch <いきものID>");
                    return true;
                }
                Creature creature = plugin.creatures().get(args[1]);
                if (creature == null) {
                    sender.sendMessage(Component.text("不明なID: " + args[1], NamedTextColor.RED));
                    return true;
                }
                ItemStack item = plugin.catchService().createCatchItem(creature, player, 0.0);
                player.getInventory().addItem(item);
                sender.sendMessage(Component.text(creature.name() + " を手に入れました。", NamedTextColor.GREEN));
            }
            case "money" -> {
                if (!checkAdmin(sender)) return true;
                if (args.length < 3) {
                    sender.sendMessage("/mushitori money <add|set> <金額> [プレイヤー]");
                    return true;
                }
                Player target = args.length >= 4 ? Bukkit.getPlayerExact(args[3])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage(Component.text("対象プレイヤーが見つかりません。", NamedTextColor.RED));
                    return true;
                }
                int amount = parseInt(args[2], 0);
                if (args[1].equalsIgnoreCase("set")) {
                    plugin.money().set(target, amount);
                } else {
                    plugin.money().add(target, amount);
                }
                sender.sendMessage(Component.text(target.getName() + " の所持金: "
                        + plugin.money().format(plugin.money().get(target)), NamedTextColor.GOLD));
            }
            case "reload" -> {
                if (!checkAdmin(sender)) return true;
                plugin.reloadAll();
                plugin.advancementService().install();
                plugin.ambientSpawnService().loadMarkers();
                sender.sendMessage(Component.text("設定といきものデータを再読み込みしました（"
                        + plugin.creatures().size() + " 種）。進捗データパックの再設置・有効化、"
                        + "マーカーの巡回タスクの再起動も試みました。",
                        NamedTextColor.GREEN));
            }
            case "reset" -> {
                if (!checkAdmin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage("/mushitori reset <プレイヤー> [dex|achievements|all]（既定: all）");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("対象プレイヤーが見つかりません（ログイン中である必要があります）。",
                            NamedTextColor.RED));
                    return true;
                }
                String scope = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "all";
                var dex = plugin.dexManager().get(target.getUniqueId());
                switch (scope) {
                    case "dex" -> dex.resetEntries();
                    case "achievements" -> {
                        dex.resetAchievements();
                        plugin.advancementService().revokeAll(target,
                                plugin.dexService().achievements().stream().map(a -> a.id()).toList());
                    }
                    default -> {
                        dex.reset();
                        plugin.advancementService().revokeAll(target,
                                plugin.dexService().achievements().stream().map(a -> a.id()).toList());
                    }
                }
                plugin.dexManager().saveAsync(target.getUniqueId());
                sender.sendMessage(Component.text(target.getName() + " の"
                        + (scope.equals("dex") ? "図鑑の記録" : scope.equals("achievements") ? "実績" : "図鑑・実績すべて")
                        + "をリセットしました。", NamedTextColor.GREEN));
            }
            case "marker" -> {
                if (!checkAdmin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage("/mushitori marker <add|remove|list> ...");
                    return true;
                }
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "add" -> {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage("プレイヤーとして実行してください（現在地にマーカーを設置します）。");
                            return true;
                        }
                        if (args.length < 3) {
                            sender.sendMessage("/mushitori marker add <いきものID>");
                            return true;
                        }
                        Creature creature = plugin.creatures().get(args[2]);
                        if (creature == null) {
                            sender.sendMessage(Component.text("不明なID: " + args[2], NamedTextColor.RED));
                            return true;
                        }
                        var marker = plugin.ambientSpawnService().addMarker(player.getLocation(), args[2]);
                        sender.sendMessage(Component.text("マーカー #" + marker.id() + "（" + creature.name()
                                + "）をこの場所に設置しました。", NamedTextColor.GREEN));
                    }
                    case "addpreset" -> {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage("プレイヤーとして実行してください（現在地にマーカーを設置します）。");
                            return true;
                        }
                        if (args.length < 3) {
                            sender.sendMessage("/mushitori marker addpreset <プリセット名>");
                            return true;
                        }
                        var preset = plugin.ambientSpawnService().preset(args[2]);
                        if (preset == null) {
                            sender.sendMessage(Component.text("不明なプリセット: " + args[2]
                                    + "（config.ymlの ambient-spawn.presets を確認してください）", NamedTextColor.RED));
                            return true;
                        }
                        var marker = plugin.ambientSpawnService().addMarkerWithPreset(player.getLocation(), args[2]);
                        sender.sendMessage(Component.text("マーカー #" + marker.id() + "（プリセット: " + args[2]
                                + "、最大" + preset.maxCount() + "体）をこの場所に設置しました。", NamedTextColor.GREEN));
                    }
                    case "remove" -> {
                        if (args.length < 3) {
                            sender.sendMessage("/mushitori marker remove <ID>");
                            return true;
                        }
                        int id = parseInt(args[2], -1);
                        if (plugin.ambientSpawnService().removeMarker(id)) {
                            sender.sendMessage(Component.text("マーカー #" + id + " を削除しました。", NamedTextColor.GREEN));
                        } else {
                            sender.sendMessage(Component.text("マーカー #" + id + " は見つかりません。", NamedTextColor.RED));
                        }
                    }
                    case "list" -> {
                        var markers = plugin.ambientSpawnService().allMarkers();
                        if (markers.isEmpty()) {
                            sender.sendMessage(Component.text("マーカーはまだありません。", NamedTextColor.GRAY));
                        } else {
                            for (var m : markers) {
                                String what = m.creatures().stream()
                                        .map(wc -> wc.creatureId() + "×" + wc.weight())
                                        .collect(java.util.stream.Collectors.joining(","));
                                sender.sendMessage(Component.text(String.format(
                                        "#%d  %s  (%s, %.0f, %.0f, %.0f)",
                                        m.id(), what, m.worldName(), m.x(), m.y(), m.z()),
                                        NamedTextColor.GRAY));
                            }
                        }
                    }
                    default -> sender.sendMessage("/mushitori marker <add|addpreset|remove|list> ...");
                }
            }
            case "trader" -> {
                if (!checkAdmin(sender)) return true;
                if (args.length < 2 || !args[1].equalsIgnoreCase("spawn")) {
                    sender.sendMessage("/mushitori trader spawn");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("プレイヤーとして実行してください（現在地に設置します）。");
                    return true;
                }
                var trader = plugin.traderService().spawn(player.getLocation());
                if (trader == null) {
                    sender.sendMessage(Component.text("設置に失敗しました。", NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("トレーダーをこの場所に設置しました。", NamedTextColor.GREEN));
                }
            }
            default -> sender.sendMessage(Component.text(
                    "/mushitori [dex|register|sell|give|spawn|catch|money|reload|reset|marker|trader]", NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean checkAdmin(CommandSender sender) {
        if (sender.hasPermission("mushitori.admin")) return true;
        sender.sendMessage(Component.text("権限がありません。", NamedTextColor.RED));
        return false;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Nullable
    private static String firstOrNull(List<String> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("dex", "register", "sell", "give", "spawn", "catch", "money", "reload", "reset", "marker", "trader"), args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> filter(List.of("book", "net", "rod", "cage", "purse", "wand"), args[1]);
                case "spawn", "catch" -> filter(plugin.creatures().ids(), args[1]);
                case "money" -> filter(List.of("add", "set"), args[1]);
                case "reset" -> filter(
                        Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
                case "marker" -> filter(List.of("add", "addpreset", "remove", "list"), args[1]);
                case "trader" -> filter(List.of("spawn"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "net" -> filter(plugin.gear().netIds(), args[2]);
                case "rod" -> filter(plugin.gear().rodIds(), args[2]);
                case "cage" -> filter(plugin.cageService().cageIds(), args[2]);
                case "purse" -> filter(plugin.purseService().purseIds(), args[2]);
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            return filter(List.of("dex", "achievements", "all"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("marker") && args[1].equalsIgnoreCase("add")) {
            return filter(plugin.creatures().ids(), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("marker") && args[1].equalsIgnoreCase("addpreset")) {
            return filter(plugin.ambientSpawnService().presetNames(), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(o);
        }
        return out;
    }
}
