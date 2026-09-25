package jp.mushitori.service;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/**
 * 「貯金箱」機能：所持金とは別に、スコアボード（{@code dummy}のObjective、
 * プレイヤー名をエントリーとして使用）で貯金額を管理する。
 *
 * <p>実際の「預ける／引き出す」処理（所持金との相互変換）は
 * {@link jp.mushitori.ui.SavingsGui} / {@link jp.mushitori.listener.SavingsGuiListener}
 * 側で、この貯金額と {@link MoneyService} を組み合わせて行います。ここでは
 * 貯金額そのものの読み書きだけを受け持ちます。</p>
 *
 * <p>表示スロット（サイドバー等）には固定していません。他のプラグイン・
 * サーバーの見た目の都合を邪魔しないよう、あくまで内部管理用の
 * Objectiveとして扱い、金額は図鑑の貯金箱画面で確認する想定です。
 * （{@code /scoreboard players list} 等、バニラのコマンドからも確認できます。）</p>
 */
public final class SavingsService {

    private static final String OBJECTIVE_NAME = "mushitori_savings";

    private final Plugin plugin;

    public SavingsService(Plugin plugin) {
        this.plugin = plugin;
    }

    private Objective objective() {
        Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY,
                    Component.text("むしとり貯金箱"));
        }
        return objective;
    }

    public int get(Player player) {
        return objective().getScore(player.getName()).getScore();
    }

    public void set(Player player, int amount) {
        objective().getScore(player.getName()).setScore(Math.max(0, amount));
    }

    public void add(Player player, int delta) {
        set(player, get(player) + delta);
    }
}
