package jp.mushitori.listener;

import jp.mushitori.Keys;
import jp.mushitori.MushitoriPlugin;
import jp.mushitori.model.CatchData;
import jp.mushitori.model.Creature;
import jp.mushitori.model.Rarity;
import jp.mushitori.model.SizeTier;
import jp.mushitori.service.CatchService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 「自慢」機能。スニーク（Shift）を一定時間押し続けたまま右クリックすると、
 * 実際には何も手放さずに、捕まえたそのままのサイズ・見た目で近くにいきものを
 * 表示します（名前・サイズ・レア度・捕まえた日時を、段組みの表示で見せます）。
 *
 * <p>右クリックのたびに1体表示します。表示した後もスニークを押し続けていれば、
 * そのまま連続で右クリックして次々と表示できます（1人あたり最大
 * {@link #MAX_PER_PLAYER}体まで。それ以上は表示できません）。</p>
 *
 * <p>表示位置：正面に地面や壁がある場合はその手前に、何もない（空中を見ている）
 * 場合は、体の向き（yaw）だけを使った水平方向・指定距離の位置に表示します
 * （見上げ／見下ろしすぎて真上・真下を向いていても、破綻しないようにするため）。
 * ただし、見上げている場合は、pitchの角度をそのまま反映すると高く出過ぎてしまう
 * ため、角度に比例させず「見上げていれば固定で少し（既定1ブロック）底上げする」
 * だけに留めています。向きは、表示したプレイヤー自身ではなく、プレイヤーが
 * 見ていたのと同じ方向（＝プレイヤーに背を向ける形）にしています。</p>
 *
 * <p>表示中のいきものは、虫取り網で捕まえられたり、攻撃で倒されたりすることは
 * ありません（{@link Keys#CREATURE_ID} タグを付けず、通常の捕獲対象の判定から
 * そもそも外れるようにしています。念のため無敵化・AI無効化もしています）。
 * 表示した個体それぞれから一定距離（既定2ブロック）以上離れると、その表示だけが
 * 消えます（他の表示には影響しません）。</p>
 *
 * <p>表示するエンティティには、生物名をカスタムネームとして設定しています
 * （表示自体は{@code setCustomNameVisible(false)}で隠していますが、名前を基準に
 * 見た目を切り替えるタイプのリソースパック（CEM等）がある場合に必要なためです）。</p>
 */
public final class ShowcaseListener implements Listener {

    /** 1人が同時に表示できる最大数。 */
    private static final int MAX_PER_PLAYER = 5;

    /** スニークを開始してから、これだけ経てば「自慢」の構えが成立したとみなす。 */
    private long holdMs = 1000L;
    /** 目の前、何もない場合にどれだけ離れた位置に表示するか（ブロック）。 */
    private double distance = 1.5;
    /** 正面に地面・壁が無いか探す距離（ブロック）。基本は distance と同じでよい。 */
    private double wallCheckDistance = 1.5;
    /** 壁・地面の手前に表示するときの、壁面からの余白（ブロック）。 */
    private static final double WALL_MARGIN = 0.3;
    /** 空中に表示するとき、見上げていたら底上げする高さ（ブロック）。角度には比例させない。 */
    private double lookUpLift = 1.0;
    /** これより上（pitchがこれより小さい＝見上げている）なら、底上げを適用する（バニラのpitchは上が負）。 */
    private static final double LOOK_UP_PITCH_THRESHOLD = -10.0;
    /** 表示した個体から、これ以上離れたら消す（ブロック）。 */
    private double despawnDistance = 2.0;
    /** 誰も動かなくても、これだけ経てば自動的に消す（tick）。 */
    private long maxDurationTicks = 20L * 30;

    private final MushitoriPlugin plugin;
    private final Map<UUID, Long> sneakStartedAt = new ConcurrentHashMap<>();
    /** プレイヤーごとの、表示中の自慢の一覧（1人最大{@link #MAX_PER_PLAYER}件）。 */
    private final Map<UUID, List<ShowcaseInstance>> active = new ConcurrentHashMap<>();

    /** 1回ぶんの自慢表示（本体＋名前表示のTextDisplay3枚＋自動消滅タスク）。 */
    private static final class ShowcaseInstance {
        final Entity main;
        final List<Entity> parts;
        @Nullable ScheduledTask expireTask;

        ShowcaseInstance(Entity main, List<Entity> parts) {
            this.main = main;
            this.parts = parts;
        }

        void removeAll() {
            if (expireTask != null) expireTask.cancel();
            for (Entity part : parts) {
                if (part != null && part.isValid()) part.remove();
            }
        }
    }

    public ShowcaseListener(MushitoriPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(@Nullable ConfigurationSection section) {
        if (section == null) return;
        holdMs = Math.round(section.getDouble("hold-seconds", 1.0) * 1000.0);
        distance = section.getDouble("distance", 1.5);
        wallCheckDistance = section.getDouble("wall-check-distance", distance);
        lookUpLift = section.getDouble("look-up-lift", 1.0);
        despawnDistance = section.getDouble("despawn-distance", 2.0);
        maxDurationTicks = Math.round(section.getDouble("max-duration-seconds", 30.0) * 20.0);
    }

    @EventHandler
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (event.isSneaking()) {
            sneakStartedAt.put(uuid, System.currentTimeMillis());
        } else {
            sneakStartedAt.remove(uuid);
        }
    }

    /**
     * 右クリック（構え成立後）で自慢を発動する。
     *
     * <p><b>{@code ignoreCancelled} を付けていない理由</b>：バニラ側で「何もしない」
     * 操作（例えば、特に使い道の無いアイテムを持って空中を右クリックする）は、
     * {@link PlayerInteractEvent} が最初からキャンセル済みの状態で発火する仕様に
     * なっています（Bukkit/Paperの公式ドキュメントに明記されています）。いきものの
     * アイテムには特別な使用アクションが無いため、空中でのクリックはまさにこれに
     * 該当し、{@code ignoreCancelled = true} を付けているとハンドラ自体が
     * 呼ばれず、自慢が発動しませんでした。</p>
     *
     * <p>構え成立後、スニークを離さない限り{@code sneakStartedAt}を消さないため、
     * 連続で右クリックすれば、そのまま連続で表示できます（上限まで）。</p>
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Long startedAt = sneakStartedAt.get(uuid);
        if (startedAt == null || !player.isSneaking()) return; // 構え（スニーク継続）が成立していない
        if (System.currentTimeMillis() - startedAt < holdMs) return; // まだ構えが足りない

        ItemStack item = player.getInventory().getItemInMainHand();
        CatchData data = plugin.catchService().read(item);
        if (data == null) return; // いきものアイテムでなければ、通常通りの右クリックに任せる

        Creature creature = plugin.creatures().get(data.creatureId());
        if (creature == null) return;

        event.setCancelled(true); // ドアが開く等、通常の右クリック動作を封じる

        List<ShowcaseInstance> list = active.computeIfAbsent(uuid, k -> new CopyOnWriteArrayList<>());
        if (list.size() >= MAX_PER_PLAYER) {
            player.sendActionBar(Component.text(
                    "これ以上は自慢できません（上限" + MAX_PER_PLAYER + "体）。", NamedTextColor.RED));
            return;
        }

        show(player, creature, data);
        // sneakStartedAtは消さない：スニークを押し続けていれば、そのまま次を表示できるようにする
    }

    /** いきものを近くに表示する（1体追加）。 */
    private void show(Player player, Creature creature, CatchData data) {
        EntityType type = creature.entityType();
        if (type == null) return;

        Location eye = player.getEyeLocation();
        World world = player.getWorld();

        Location spawnAt;
        Vector faceDirection;

        // まず、正面に地面・壁がすぐ近くにあるかを見る（pitch込みの本当の視線で判定）
        Vector lookDir = eye.getDirection();
        RayTraceResult hit = world.rayTraceBlocks(eye, lookDir, wallCheckDistance);
        if (hit != null && hit.getHitPosition() != null) {
            double hitDist = eye.toVector().distance(hit.getHitPosition());
            double placeDist = Math.max(0.2, hitDist - WALL_MARGIN);
            spawnAt = eye.clone().add(lookDir.clone().multiply(placeDist));
            faceDirection = lookDir;
        } else {
            // 何も無い（空中を見ている）場合：見上げ・見下ろしすぎて破綻しないよう、
            // 体の向き（yaw）だけを使った、水平方向・指定距離の位置にする。
            // 見上げている場合だけ、角度には比例させず固定で少し底上げする
            // （pitchをそのまま反映すると、急な角度のときに高く出過ぎてしまうため）。
            float yaw = player.getLocation().getYaw();
            double yawRad = Math.toRadians(yaw);
            Vector horizontal = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
            spawnAt = player.getLocation().clone().add(horizontal.clone().multiply(distance));
            if (eye.getPitch() < LOOK_UP_PITCH_THRESHOLD) {
                spawnAt.add(0, lookUpLift, 0);
            }
            faceDirection = horizontal;
        }
        // 向きは、表示したプレイヤー自身ではなく、プレイヤーが見ていたのと同じ方向にする
        // （＝プレイヤーに背を向ける形。「自慢」なので、自分ではなく向こう側を見せる）
        spawnAt.setDirection(faceDirection);

        Entity entity = world.spawnEntity(spawnAt, type, CreatureSpawnEvent.SpawnReason.CUSTOM,
                spawned -> {
                    spawned.getPersistentDataContainer().set(Keys.SHOWCASE, PersistentDataType.BYTE, (byte) 1);
                    spawned.setPersistent(false);
                    spawned.setSilent(true);
                    // 名前を基準に見た目を切り替えるタイプのリソースパック（CEM等）に対応するため、
                    // 表示はしない（setCustomNameVisible(false)）が、名前自体は設定しておく
                    // （実際のいきもの湧き＝SpawnServiceと同じ扱い）。
                    spawned.customName(Component.text(creature.name()));
                    spawned.setCustomNameVisible(false);
                    if (spawned instanceof LivingEntity living) {
                        living.setInvulnerable(true);
                        living.setAI(false);
                        living.setCollidable(false);
                        living.setRemoveWhenFarAway(false);
                    }
                });

        plugin.catchService().applyScale(entity, creature, data.sizeCm(), data.sizeTierKey());

        List<Entity> parts = new ArrayList<>();
        parts.add(entity);
        parts.addAll(spawnLabels(world, entity, creature, data));

        ShowcaseInstance instance = new ShowcaseInstance(entity, parts);
        active.computeIfAbsent(player.getUniqueId(), k -> new CopyOnWriteArrayList<>()).add(instance);

        world.spawnParticle(Particle.TOTEM_OF_UNDYING, spawnAt, 20, 0.3, 0.3, 0.3, 0.2);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);

        instance.expireTask = entity.getScheduler().runDelayed(plugin,
                t -> removeInstance(player.getUniqueId(), instance), null, maxDurationTicks);
    }

    /**
     * 名前・レア度・サイズ・捕まえた日時を、段組みで表示する。
     * <pre>
     *              名前
     *        めずらしさ　おおきさ
     *   捕まえた日にち（捕獲した名前）
     * </pre>
     * のように、名前を大きく、下2行を少し小さく表示する。1行ずつ独立した
     * TextDisplayを、いきものの真上に積み重ねる形にしている（バニラの
     * カスタムネームは1行しか表示できないため）。
     */
    private List<Entity> spawnLabels(World world, Entity base, Creature creature, CatchData data) {
        Rarity rarity = plugin.catchService().rarities().get(data.rarityKey());
        SizeTier tier = plugin.catchService().sizeTiers().get(data.sizeTierKey());
        boolean legendary = isTopRarity(data.rarityKey());
        String sizeName = tier.displayName(legendary);

        Component nameLine = Component.text(creature.name(), rarity.color())
                .decoration(TextDecoration.ITALIC, false);
        Component statsLine = Component.text()
                .append(Component.text(rarity.name() + "　", rarity.color()))
                .append(Component.text(sizeName + " " + CatchService.formatSize(data.sizeCm()), NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false)
                .build();
        Component metaLine = Component.text(
                        CatchService.formatDate(data.caughtAt()) + "（" + data.catcherName() + "）",
                        NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);

        double topY = base.getBoundingBox().getMaxY();
        Location origin = base.getLocation();

        List<Entity> labels = new ArrayList<>();
        labels.add(spawnLabel(world, withY(origin, topY + 0.65), nameLine, 1.0f));
        labels.add(spawnLabel(world, withY(origin, topY + 0.42), statsLine, 0.65f));
        labels.add(spawnLabel(world, withY(origin, topY + 0.25), metaLine, 0.65f));
        return labels;
    }

    private Location withY(Location base, double y) {
        Location loc = base.clone();
        loc.setY(y);
        return loc;
    }

    private Entity spawnLabel(World world, Location loc, Component text, float scale) {
        return world.spawn(loc, TextDisplay.class, td -> {
            td.getPersistentDataContainer().set(Keys.SHOWCASE, PersistentDataType.BYTE, (byte) 1);
            td.setPersistent(false);
            td.setBillboard(Display.Billboard.CENTER);
            td.text(text);
            td.setShadowed(false);
            td.setSeeThrough(false);
            td.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));
        });
    }

    private boolean isTopRarity(String rarityKey) {
        var ordered = plugin.catchService().rarities().all();
        if (ordered.isEmpty() || rarityKey == null) return false;
        return ordered.get(ordered.size() - 1).key().equals(rarityKey);
    }

    /** 表示中のいきもの・名前表示が、攻撃等で万一巻き込まれても、絶対にダメージを受けないようにする保険。 */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(Keys.SHOWCASE, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        List<ShowcaseInstance> list = active.get(uuid);
        if (list == null || list.isEmpty()) return;

        Location to = event.getTo();
        if (to == null) return;

        for (Iterator<ShowcaseInstance> it = list.iterator(); it.hasNext(); ) {
            ShowcaseInstance instance = it.next();
            if (!instance.main.isValid()) {
                instance.removeAll();
                list.remove(instance);
                continue;
            }
            Location showcaseLoc = instance.main.getLocation();
            boolean tooFar;
            if (showcaseLoc.getWorld() == null || to.getWorld() == null
                    || !showcaseLoc.getWorld().equals(to.getWorld())) {
                tooFar = true;
            } else {
                // 表示した個体それぞれからの距離で判定する（表示したときの自分の位置からではない）
                tooFar = showcaseLoc.distanceSquared(to) > despawnDistance * despawnDistance;
            }
            if (tooFar) {
                instance.removeAll();
                list.remove(instance);
            }
        }
        if (list.isEmpty()) active.remove(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sneakStartedAt.remove(uuid);
        clearShowcase(uuid);
    }

    /** そのプレイヤーが、今まさに「自慢」の構え中（スニーク保持中）か、既に何か表示中かどうか。
     *  アイテムを渡す機能（Shift＋右クリック）と同じ「シフト＋右クリック」がきっかけになるため、
     *  自慢のほうを優先させたい場面でこれを確認する。 */
    public boolean isChargingOrShowing(UUID uuid) {
        List<ShowcaseInstance> list = active.get(uuid);
        return sneakStartedAt.containsKey(uuid) || (list != null && !list.isEmpty());
    }

    private void removeInstance(UUID uuid, ShowcaseInstance instance) {
        List<ShowcaseInstance> list = active.get(uuid);
        if (list == null) return;
        if (list.remove(instance)) {
            instance.removeAll();
        }
        if (list.isEmpty()) active.remove(uuid);
    }

    /** そのプレイヤーの自慢表示を、表示中の分すべて片付ける（いなければ何もしない）。 */
    public void clearShowcase(UUID uuid) {
        List<ShowcaseInstance> list = active.remove(uuid);
        if (list == null) return;
        for (ShowcaseInstance instance : list) {
            instance.removeAll();
        }
    }

    /** プラグイン無効化時に、表示中のものをすべて片付ける。 */
    public void clearAll() {
        for (UUID uuid : Set.copyOf(active.keySet())) {
            clearShowcase(uuid);
        }
    }
}
