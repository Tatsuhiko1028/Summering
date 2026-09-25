package jp.mushitori;

import jp.mushitori.command.MushitoriCommand;
import jp.mushitori.data.DexManager;
import jp.mushitori.listener.FishingListener;
import jp.mushitori.listener.GuideListener;
import jp.mushitori.listener.NetListener;
import jp.mushitori.listener.PlayerDataListener;
import jp.mushitori.listener.CageListener;
import jp.mushitori.listener.PurseListener;
import jp.mushitori.listener.BareHandCatchListener;
import jp.mushitori.listener.MarkerGuiListener;
import jp.mushitori.listener.PlayerGiveListener;
import jp.mushitori.listener.SavingsGuiListener;
import jp.mushitori.listener.ShowcaseListener;
import jp.mushitori.listener.TraderListener;
import jp.mushitori.listener.MarkerWandListener;
import jp.mushitori.model.Rarity;
import jp.mushitori.model.SizeRarityTemplate;
import jp.mushitori.model.SizeTier;
import jp.mushitori.registry.CreatureRegistry;
import jp.mushitori.registry.GearRegistry;
import jp.mushitori.service.AdvancementService;
import jp.mushitori.service.AmbientSpawnService;
import jp.mushitori.service.ApproachFishingService;
import jp.mushitori.service.CageService;
import jp.mushitori.service.PurseService;
import jp.mushitori.service.CatchService;
import jp.mushitori.service.DexService;
import jp.mushitori.service.MoneyService;
import jp.mushitori.service.MessageService;
import jp.mushitori.service.SavingsService;
import jp.mushitori.service.SpawnService;
import jp.mushitori.service.TraderService;
import jp.mushitori.ui.GuiListener;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class MushitoriPlugin extends JavaPlugin {

    private CreatureRegistry creatures;
    private GearRegistry gear;
    private DexManager dexManager;
    private MoneyService money;
    private SavingsService savingsService;
    private CatchService catchService;
    private DexService dexService;
    private SpawnService spawnService;
    private AdvancementService advancementService;
    private ApproachFishingService approachFishingService;
    private MessageService messages;
    private CageService cageService;
    private PurseService purseService;
    private AmbientSpawnService ambientSpawnService;
    private TraderService traderService;
    private ShowcaseListener showcaseListener;
    private PlayerGiveListener playerGiveListener;

    private boolean acceptVanillaBook = true;
    private boolean fishingEnabled = true;
    private boolean replaceVanillaCatch = true;
    private Sound fishEscapeSound = Sound.ENTITY_GENERIC_SPLASH;

    // ---- 虫取り網の右クリック捕獲まわりの設定 ----
    private double netRange = 3.0;
    private double netAngleDegrees = 50.0;
    private int netMaxTargets = 8;
    private Sound netSwingSound = Sound.ENTITY_PLAYER_ATTACK_SWEEP;
    private float netSwingVolume = 0.8f;
    private float netSwingPitch = 1.3f;
    private Sound netCatchSound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
    private float netCatchVolume = 0.7f;
    private float netCatchPitch = 1.2f;
    private Sound netBreakSound = Sound.ENTITY_ITEM_BREAK;
    private Sound netEscapeSound = Sound.ENTITY_PLAYER_ATTACK_NODAMAGE;
    private long netCooldownMs = 250;
    private double netEscapeTeleportRadius = 7.0;
    private boolean netDebug = false;
    private boolean catchingDebug = false;

    @Override
    public void onEnable() {
        Keys.init(this);
        saveDefaultConfig();
        applyConfigDefaults();

        creatures = new CreatureRegistry(this);
        gear = new GearRegistry(this);
        dexManager = new DexManager(this);
        money = new MoneyService();
        savingsService = new SavingsService(this);
        catchService = new CatchService(getLogger(), creatures,
                Rarity.Table.load(getConfig().getConfigurationSection("rarities")),
                SizeTier.Table.load(getConfig().getConfigurationSection("sizes")));
        spawnService = new SpawnService(catchService);
        advancementService = new AdvancementService(this);
        dexService = new DexService(creatures, catchService, dexManager, money, advancementService);
        approachFishingService = new ApproachFishingService(this);
        messages = new MessageService(this);
        messages.load();
        cageService = new CageService();
        purseService = new PurseService();
        ambientSpawnService = new AmbientSpawnService(this);
        traderService = new TraderService();
        showcaseListener = new ShowcaseListener(this);
        playerGiveListener = new PlayerGiveListener(this);

        reloadAll();
        advancementService.install();
        // ワールドの読み込みが落ち着いてから、マーカーの巡回タスクを開始する
        getServer().getGlobalRegionScheduler().runDelayed(this, task -> ambientSpawnService.loadMarkers(), 60L);

        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new GuideListener(this), this);
        getServer().getPluginManager().registerEvents(new NetListener(this), this);
        getServer().getPluginManager().registerEvents(new BareHandCatchListener(this), this);
        getServer().getPluginManager().registerEvents(new SavingsGuiListener(this), this);
        getServer().getPluginManager().registerEvents(new FishingListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerDataListener(this), this);
        getServer().getPluginManager().registerEvents(new CageListener(this), this);
        getServer().getPluginManager().registerEvents(new PurseListener(this), this);
        getServer().getPluginManager().registerEvents(new MarkerWandListener(this), this);
        getServer().getPluginManager().registerEvents(new MarkerGuiListener(this), this);
        getServer().getPluginManager().registerEvents(new TraderListener(this), this);
        getServer().getPluginManager().registerEvents(showcaseListener, this);
        getServer().getPluginManager().registerEvents(playerGiveListener, this);

        // マーカー設定棒を持っている間、近くのマーカーをパーティクルで見せる（軽量な定期処理）
        getServer().getGlobalRegionScheduler().runAtFixedRate(this,
                task -> ambientSpawnService.tickVisualization(), 20L, 20L);

        PluginCommand command = getCommand("mushitori");
        if (command != null) {
            MushitoriCommand executor = new MushitoriCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("Mushitori を有効にしました。");
    }

    @Override
    public void onDisable() {
        if (showcaseListener != null) {
            showcaseListener.clearAll();
        }
        if (dexManager != null) {
            dexManager.saveAllSync();
        }
        if (approachFishingService != null) {
            approachFishingService.cancelAll();
        }
        if (ambientSpawnService != null) {
            ambientSpawnService.shutdown();
        }
    }

    /**
     * ユーザーのconfig.ymlに、同梱のconfig.ymlにしかない項目（アップデートで増えた設定）が
     * 無い場合、その項目だけ jar 内の既定値で補う（ユーザーのファイル自体は書き換えない）。
     * {@code saveDefaultConfig()} はファイルがすでに存在すると何もしないため、
     * 古いバージョンから使い続けている config.yml に新項目が反映されないのを防ぐための保険。
     *
     * <p>{@code setDefaults()} だけだと、丸ごと欠けているトップレベルのセクション
     * （例: 新しく追加した {@code cages} セクションがユーザーのファイルに全く無い場合）を
     * {@code getConfigurationSection()} が正しく拾えないことがあるため、そういったセクションは
     * メモリ上の設定に直接コピーしておく（ファイル自体への書き込みはしない）。</p>
     */
    private void applyConfigDefaults() {
        try (InputStream in = getResource("config.yml")) {
            if (in == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            getConfig().setDefaults(defaults);

            for (String key : defaults.getKeys(false)) {
                if (!getConfig().contains(key, true)) {
                    getConfig().set(key, defaults.get(key));
                }
            }
        } catch (Exception e) {
            getLogger().warning("config.yml の既定値の読み込みに失敗しました: " + e.getMessage());
        }
    }

    /** config.yml と creatures.yml を読み直す。 */
    public void reloadAll() {
        reloadConfig();
        applyConfigDefaults();
        catchingDebug = getConfig().getBoolean("catching.debug", false);
        creatures.setDefaultEscapeChance(getConfig().getDouble("catching.default-escape-chance", 0.2));
        creatures.setDefaultBaseRarity(getConfig().getString("catching.default-base-rarity", "N"));
        creatures.load();
        gear.load(getConfig().getConfigurationSection("guide"));
        money.load(getConfig().getConfigurationSection("money"));
        catchService.setRarities(Rarity.Table.load(getConfig().getConfigurationSection("rarities")));
        catchService.setSizeTiers(SizeTier.Table.load(getConfig().getConfigurationSection("sizes")));
        catchService.setSizeDistributionTemplates(getConfig().getConfigurationSection("size-distribution-templates"));
        catchService.setSizeRarityTemplates(loadSizeRarityTemplates(),
                getConfig().getString("catching.default-size-rarity-template", "default"));
        catchService.setDebug(catchingDebug);
        catchService.setStrictVisualConsistency(
                getConfig().getBoolean("catching.strict-visual-consistency", false));
        catchService.setSizeContinuousPriceBonus(
                getConfig().getDouble("catching.size-continuous-price-bonus", 0.5));
        var scaleSection = getConfig().getConfigurationSection("catching.scale");
        catchService.setScaleConfig(
                scaleSection == null || scaleSection.getBoolean("enabled", true),
                scaleSection == null ? 1.05 : scaleSection.getDouble("extreme-max-multiplier-min", 1.05),
                scaleSection == null ? 1.2 : scaleSection.getDouble("extreme-max-multiplier-max", 1.2),
                scaleSection == null ? 0.8 : scaleSection.getDouble("extreme-min-multiplier-min", 0.8),
                scaleSection == null ? 0.95 : scaleSection.getDouble("extreme-min-multiplier-max", 0.95),
                scaleSection == null ? 0.5 : scaleSection.getDouble("clamp-min", 0.5),
                scaleSection == null ? 1.6 : scaleSection.getDouble("clamp-max", 1.6));
        if (messages != null) {
            messages.load();
        }
        cageService.load(getConfig().getConfigurationSection("cages"));
        purseService.load(getConfig().getConfigurationSection("purses"));
        ambientSpawnService.load(getConfig().getConfigurationSection("ambient-spawn"));
        traderService.load(getConfig().getConfigurationSection("trader"));
        if (showcaseListener != null) {
            showcaseListener.load(getConfig().getConfigurationSection("showcase"));
        }
        if (playerGiveListener != null) {
            playerGiveListener.load(getConfig().getConfigurationSection("player-give"));
        }
        ambientSpawnService.loadWandConfig(getConfig().getConfigurationSection("ambient-spawn.wand"));
        dexService.load(getConfig().getConfigurationSection("register"));
        approachFishingService.load(getConfig().getConfigurationSection("fishing.approach"));
        advancementService.load(getConfig().getConfigurationSection("advancements"));

        acceptVanillaBook = getConfig().getBoolean("guide.accept-vanilla-book", true);
        fishingEnabled = getConfig().getBoolean("fishing.enabled", true);
        replaceVanillaCatch = getConfig().getBoolean("fishing.replace-vanilla-catch", true);
        fishEscapeSound = parseSound(getConfig().getString("fishing.sound-escape"), Sound.ENTITY_GENERIC_SPLASH);

        var netCatching = getConfig().getConfigurationSection("net-catching");
        if (netCatching != null) {
            netRange = netCatching.getDouble("range", 3.0);
            netAngleDegrees = netCatching.getDouble("angle-degrees", 50.0);
            netMaxTargets = netCatching.getInt("max-targets", 8);
            netSwingSound = parseSound(netCatching.getString("sound-swing"), Sound.ENTITY_PLAYER_ATTACK_SWEEP);
            netSwingVolume = (float) netCatching.getDouble("sound-swing-volume", 0.8);
            netSwingPitch = (float) netCatching.getDouble("sound-swing-pitch", 1.3);
            netCatchSound = parseSound(netCatching.getString("sound-catch"), Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
            netCatchVolume = (float) netCatching.getDouble("sound-catch-volume", 0.7);
            netCatchPitch = (float) netCatching.getDouble("sound-catch-pitch", 1.2);
            netBreakSound = parseSound(netCatching.getString("sound-break"), Sound.ENTITY_ITEM_BREAK);
            netEscapeSound = parseSound(netCatching.getString("sound-escape"), Sound.ENTITY_PLAYER_ATTACK_NODAMAGE);
            netCooldownMs = netCatching.getLong("cooldown-ms", 250);
            netEscapeTeleportRadius = netCatching.getDouble("escape-teleport-radius", 7.0);
            netDebug = netCatching.getBoolean("debug", false);
        }
    }

    /** size-rarity-templates: {テンプレート名: SizeRarityTemplate} をパースする。 */
    private Map<String, SizeRarityTemplate> loadSizeRarityTemplates() {
        var section = getConfig().getConfigurationSection("size-rarity-templates");
        if (section == null) return Map.of();
        Map<String, SizeRarityTemplate> map = new LinkedHashMap<>();
        for (String name : section.getKeys(false)) {
            var s = section.getConfigurationSection(name);
            if (s == null) continue;
            map.put(name, SizeRarityTemplate.load(name, s));
        }
        return map;
    }

    private static Sound parseSound(String raw, Sound fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ---- アクセサ ----

    public CreatureRegistry creatures() {
        return creatures;
    }

    public GearRegistry gear() {
        return gear;
    }

    public DexManager dexManager() {
        return dexManager;
    }

    public MoneyService money() {
        return money;
    }

    public SavingsService savingsService() {
        return savingsService;
    }

    public CatchService catchService() {
        return catchService;
    }

    public DexService dexService() {
        return dexService;
    }

    public SpawnService spawnService() {
        return spawnService;
    }

    public AdvancementService advancementService() {
        return advancementService;
    }

    public ApproachFishingService approachFishingService() {
        return approachFishingService;
    }

    public MessageService messages() {
        return messages;
    }

    public CageService cageService() {
        return cageService;
    }

    public PurseService purseService() {
        return purseService;
    }

    public AmbientSpawnService ambientSpawnService() {
        return ambientSpawnService;
    }

    public TraderService traderService() {
        return traderService;
    }

    public ShowcaseListener showcaseListener() {
        return showcaseListener;
    }

    public boolean catchingDebug() {
        return catchingDebug;
    }

    public boolean acceptVanillaBook() {
        return acceptVanillaBook;
    }

    public boolean fishingEnabled() {
        return fishingEnabled;
    }

    public boolean replaceVanillaCatch() {
        return replaceVanillaCatch;
    }

    // ---- 虫取り網 右クリック捕獲の設定値 ----

    public double netRange() {
        return netRange;
    }

    public double netAngleDegrees() {
        return netAngleDegrees;
    }

    public int netMaxTargets() {
        return netMaxTargets;
    }

    public Sound netSwingSound() {
        return netSwingSound;
    }

    public float netSwingVolume() {
        return netSwingVolume;
    }

    public float netSwingPitch() {
        return netSwingPitch;
    }

    public Sound netCatchSound() {
        return netCatchSound;
    }

    public float netCatchVolume() {
        return netCatchVolume;
    }

    public float netCatchPitch() {
        return netCatchPitch;
    }

    public Sound netBreakSound() {
        return netBreakSound;
    }

    public Sound netEscapeSound() {
        return netEscapeSound;
    }

    public Sound fishEscapeSound() {
        return fishEscapeSound;
    }

    public long netCooldownMs() {
        return netCooldownMs;
    }

    public double netEscapeTeleportRadius() {
        return netEscapeTeleportRadius;
    }

    public boolean netDebug() {
        return netDebug;
    }
}
