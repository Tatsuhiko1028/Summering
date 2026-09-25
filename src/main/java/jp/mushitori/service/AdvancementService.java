package jp.mushitori.service;

import jp.mushitori.MushitoriPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;

/**
 * 図鑑の実績（{@link DexService.Achievement}）を、Minecraft本体の「進捗」タブでも
 * 確認できるようにする。
 *
 * <p>仕組み：</p>
 * <ol>
 *   <li>プラグインに同梱したデータパック（jar内の {@code summering_datapack/} 以下）を
 *       ワールドの {@code datapacks/} フォルダにコピーする（{@link #install()}、
 *       起動時に自動で行います）</li>
 *   <li>コンソールで {@code minecraft:reload} → {@code /datapack enable "file/<パック名>"}
 *       を実行して読み込ませる（<b>これは自動では行いません</b>。以前はプラグイン側で
 *       自動実行していましたが、サーバー全体のデータ再読み込みという重い処理を
 *       起動のたびに自動で走らせるのは望ましくないため、手動での実行に変更しました。
 *       一度有効化すれば、そのワールドでは以後も有効なままなので、初回だけの
 *       ひと手間です）</li>
 *   <li>実績を達成したタイミングで、対応する進捗の criteria を awardCriteria する</li>
 * </ol>
 *
 * <p>進捗側の criteria はすべて {@code minecraft:impossible} トリガーにしてあるため、
 * 通常のプレイでは絶対に達成されず、必ずこのプラグイン経由でのみ達成します。
 * 実績IDを追加・変更した場合は、対応するJSONファイル
 * （{@code src/main/resources/summering_datapack/data/summering/advancement/*.json}）も
 * 忘れずに用意してください。</p>
 *
 * <p><b>注意:</b> データパックの {@code pack_format} や advancement のJSONスキーマは
 * Minecraftのバージョンによって変わります。手元の Paper 26.2 で読み込みエラーが出る場合は、
 * サーバーログを確認のうえ {@code pack.mcmeta} の {@code pack_format} や各JSONの
 * {@code icon} の書式を調整してください。</p>
 */
public final class AdvancementService {

    /** データパック内に同梱しているファイル一覧（jarのリソースパス、拡張子込みの相対パス）。 */
    private static final List<String> PACKAGED_FILES = List.of(
            "pack.mcmeta",
            "data/summering/advancement/root.json",
            "data/summering/advancement/first_catch.json",
            "data/summering/advancement/complete_bug.json",
            "data/summering/advancement/complete_fish.json",
            "data/summering/advancement/complete_all.json",
            "data/summering/advancement/ur_hunter.json",
            "data/summering/advancement/catch_100.json"
    );

    private static final String RESOURCE_ROOT = "summering_datapack/";
    /** advancement JSON側の criteria キー名。全ファイル共通。 */
    private static final String CRITERIA_KEY = "unlocked";
    private static final String NAMESPACE = "summering";

    private final MushitoriPlugin plugin;
    private boolean enabled = true;
    private String packName = "summering_advancements";
    private boolean warnedMissingOnce = false;

    public AdvancementService(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(ConfigurationSection section) {
        if (section == null) return;
        enabled = section.getBoolean("enabled", true);
        packName = section.getString("pack-name", packName);
    }

    /** データパックをコピーする。onEnable から一度だけ呼びます。
     *  有効化（/datapack enable）はコンソールから手動で行ってください
     *  （以前は minecraft:reload → datapack enable を自動実行していましたが、
     *  サーバー全体のデータ再読み込みという重い処理を毎回自動で走らせるのは
     *  望ましくないため、この自動再読み込みの処理は廃止しました）。 */
    public void install() {
        if (!enabled) {
            plugin.getLogger().info("進捗連携は無効化されています（advancements.enabled: false）。");
            return;
        }

        File datapackDir = new File(new File(primaryWorldFolder(), "datapacks"), packName);
        try {
            for (String relative : PACKAGED_FILES) {
                copyResource(RESOURCE_ROOT + relative, new File(datapackDir, relative));
            }
            plugin.getLogger().info("進捗データパックを書き出しました: " + datapackDir);
            plugin.getLogger().info("まだ有効化されていない場合、コンソールで次の2つを順に実行してください: "
                    + "minecraft:reload → datapack enable \"file/" + packName + "\"");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "進捗データパックの書き出しに失敗しました。実績はGUIのみで管理されます。", e);
        }
    }

    private File primaryWorldFolder() {
        var worlds = plugin.getServer().getWorlds();
        if (!worlds.isEmpty()) {
            return worlds.get(0).getWorldFolder();
        }
        return new File(".");
    }

    private void copyResource(String resourcePath, File destination) throws IOException {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                plugin.getLogger().warning("同梱リソースが見つかりません（ビルド設定を確認してください）: " + resourcePath);
                return;
            }
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("フォルダを作成できません: " + parent);
            }
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- 付与 ----

    /** 実績IDに対応する進捗を達成させる（データパック未読み込みなら何もしない）。 */
    public void grant(Player player, String achievementId) {
        if (!enabled) return;
        Advancement advancement = Bukkit.getAdvancement(key(achievementId));
        if (advancement == null) {
            if (!warnedMissingOnce) {
                warnedMissingOnce = true;
                plugin.getLogger().warning("進捗 '" + achievementId + "' が見つかりません。"
                        + "データパック（" + packName + "）が読み込まれていない可能性があります。"
                        + "コンソールで /datapack list を確認し、無ければ"
                        + " /datapack enable \"file/" + packName + "\" を実行してください。"
                        + "（この警告は起動ごとに1回だけ表示します）");
            }
            return; // 次回ログイン時の syncAdvancements で再試行される
        }
        var progress = player.getAdvancementProgress(advancement);
        if (!progress.isDone()) {
            progress.awardCriteria(CRITERIA_KEY);
        }
    }

    /** ルート進捗（図鑑タブそのもの）を開放する。ログイン時に呼ぶ想定。 */
    public void grantRoot(Player player) {
        grant(player, "root");
    }

    /** 実績IDに対応する進捗を取り消す（未達成の場合は何もしない）。 */
    public void revoke(Player player, String achievementId) {
        Advancement advancement = Bukkit.getAdvancement(key(achievementId));
        if (advancement == null) return;
        var progress = player.getAdvancementProgress(advancement);
        for (String criterion : List.copyOf(progress.getAwardedCriteria())) {
            progress.revokeCriteria(criterion);
        }
    }

    /** 指定した実績ID（＋ルート進捗）すべてを取り消す。図鑑のリセットと合わせて使う想定。 */
    public void revokeAll(Player player, Collection<String> achievementIds) {
        revoke(player, "root");
        for (String id : achievementIds) {
            revoke(player, id);
        }
    }

    private NamespacedKey key(String id) {
        return new NamespacedKey(NAMESPACE, id);
    }
}
