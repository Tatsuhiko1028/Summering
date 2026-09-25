package jp.mushitori.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jp.mushitori.MushitoriPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Level;

/**
 * 捕獲・釣りの通知文言（"◯◯を捕まえた！" 等）を {@code messages.json} から読み込む。
 * プラグインのデータフォルダに {@code messages.json} を置いておけば、再ビルドなしに
 * 文言だけ編集できる（{@code /mushitori reload} で反映）。
 *
 * <p>プレースホルダーは {@code {name}} の形式で、{@link #get(String, String, Map)} に渡した
 * マップの値に単純置換されます。</p>
 */
public final class MessageService {

    private final MushitoriPlugin plugin;
    private Map<String, String> messages = Map.of();

    public MessageService(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.json");
        if (!file.exists()) {
            plugin.saveResource("messages.json", false);
        }
        try (var reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> loaded = new Gson().fromJson(reader, type);
            messages = loaded == null ? Map.of() : Map.copyOf(loaded);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "messages.json の読み込みに失敗しました。既定の文言を使います。", e);
            messages = Map.of();
        }
    }

    /**
     * 文言を取得し、プレースホルダーを置換して返す。
     *
     * @param key          messages.json のキー
     * @param fallback     キーが見つからなかったときの既定文言（プレースホルダーはそのまま置換される）
     * @param placeholders {@code {name}} → 差し込む値。無ければ null でよい
     */
    public String get(String key, String fallback, Map<String, String> placeholders) {
        String template = messages.getOrDefault(key, fallback);
        if (placeholders == null || placeholders.isEmpty()) return template;
        String result = template;
        for (var entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
