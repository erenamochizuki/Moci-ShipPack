package data.scripts;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignPlugin;
import com.fs.starfarer.api.campaign.CampaignPlugin.PickPriority;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import data.hullmods.Moci_OpenMaintenanceGantryFleetEffect;
import data.scripts.impl.Moci_GachaMobManager;
import data.scripts.impl.Moci_GachaManager;
import data.scripts.impl.Moci_CombatLootListener;
import data.scripts.impl.Moci_NadesicoStoryRouteManager;
import data.scripts.impl.Moci_NergalHeavyIndustriesColonyManager;
import data.scripts.impl.Moci_TulipColonyManager;
import data.scripts.impl.Moci_YurikaStoryManager;
import data.scripts.impl.Moci_CampaignTime;
import data.scripts.ships.ai.Moci_MechTurretAI;
import data.scripts.weapons.render.Moci_GNShieldRendering_Shader;

import data.scripts.weapons.ai.Moci_Weapon1AI;
import data.scripts.weapons.ai.Moci_Aestivalis_Weapon1AI;
import data.scripts.weapons.ai.Moci_Weapon2AI;

import data.campaign.intel.Moci_CombatSquadManagerIntel;
import data.campaign.intel.Moci_ShipboardAIManagerIntel;
import data.campaign.MociRecoverableShipCampaignPlugin;
import org.apache.log4j.Logger;
import org.dark.shaders.light.LightData;
import org.dark.shaders.util.ShaderLib;
import org.dark.shaders.util.TextureData;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class Moci_bounty_plugin extends BaseModPlugin {
    private static final Logger log = Global.getLogger(Moci_bounty_plugin.class);
    private static final String MOD_ID_TO_CHECK = "lazyXmoci bountypack";
    private static final String MEMORY_KEY = "$Moci_BountyExtension_unable";
    private static final String AI_CORE_PLUGIN_REGISTERED = "$Moci_AICorePlugin_Registered";
    private static final String NXN_MOD_ID = "NXN_ShipPack";
    private static final String NXN_TEXTURE_DATA_CSV = "data/lights/NXN_texture_data.csv";

    // ==========================================
    // 功能开关配置区域
    // ==========================================

    /**
     * 动态部署点系统开关
     * true = 启用动态部署点计算（根据僚机配置调整部署点消耗）
     * false = 禁用动态部署点计算（始终使用固定1.5倍惩罚）
     */
    public static final boolean ENABLE_DYNAMIC_DEPLOYMENT = false;

    /**
     * 战后掉落系统开关
     * true = 启用原版势力珠子掉落和涡族舰队战后掉落
     * false = 禁用这些额外战斗掉落
     */
    public static final boolean ENABLE_COMBAT_LOOT_DROPS = true;

    public static boolean isDynamicDeploymentEnabled() {
        return ENABLE_DYNAMIC_DEPLOYMENT;
    }

    public static boolean isCombatLootDropsEnabled() {
        return ENABLE_COMBAT_LOOT_DROPS;
    }

    // CombatSquad机师系统
    private Moci_CombatSquadBattleListener battleListener;
    // 舰载AI系统
    private Moci_ShipboardAIBattleListener shipboardAIBattleListener;

    // 白骑士尾部纹理路径
    private static final String TAIL_S_SPRITE_PATH = "graphics/ships/Mobile_Suit/Moci_ReinWeissRitter/tail/ReinWeissRitter_tail_s.png";
    private static final String TAIL_M_SPRITE_PATH = "graphics/ships/Mobile_Suit/Moci_ReinWeissRitter/tail/ReinWeissRitter_tail_m.png";
    private static final String TAIL_L_SPRITE_PATH = "graphics/ships/Mobile_Suit/Moci_ReinWeissRitter/tail/ReinWeissRitter_tail_l.png";
    
    // 霧龙尾部纹理路径
    private static final String SHROUDED_DRAGON_TAIL_PATH_PREFIX = "graphics/ships/VIII/ShroudedDragon/tail/Moci_ShroudedDragon_tail_0";
    private static final String SHROUDED_DRAGON_TAIL_PATH_SUFFIX = ".png";
    private static final int SHROUDED_DRAGON_TAIL_SEGMENTS = 8;

    private static final String HOAR_SHROUD_DRAGON_TAIL_PATH_PREFIX = "graphics/ships/VIII/HoarShroudDragon/tail/Moci_HoarShroudDragon_tail_0";
    private static final String HOAR_SHROUD_DRAGON_TAIL_PATH_SUFFIX = ".png";
    private static final int HOAR_SHROUD_DRAGON_TAIL_SEGMENTS = 8;

    // 黑百合尾部纹理路径
    private static final String Black_Sarena_TAIL_START_SPRITE_PATH = "graphics/ships/Mobile_Suit/Moci_Black_Sarena/tail/Black_Sarena_tail_start.png";
    private static final String Black_Sarena_TAIL_MID_SPRITE_PATH = "graphics/ships/Mobile_Suit/Moci_Black_Sarena/tail/Black_Sarena_tail_mid.png";
    private static final String Black_Sarena_TAIL_END_SPRITE_PATH = "graphics/ships/Mobile_Suit/Moci_Black_Sarena/tail/Black_Sarena_tail_end.png";


    // ==========================================
    // BoxUtil可选依赖检查
    // ==========================================
    
    private static Boolean boxUtilAvailable = null;
    
    /**
     * 检查BoxUtil是否可用
     * 使用缓存避免重复检查
     * @return true如果BoxUtil可用，false如果不可用
     */
    public static boolean isBoxUtilAvailable() {
        if (boxUtilAvailable == null) {
            boxUtilAvailable = Global.getSettings().getModManager().isModEnabled("BoxUtil");
        }
        return boxUtilAvailable;
    }

    /**
     * 开放式维修龙门架管理器 - 统一处理舰队效果
     */
    private static class MaintenanceGantryManager implements EveryFrameScript {
        private static final String GANTRY_HULLMOD_ID = "Moci_open_maintenance_gantry";
        private static final String FLEET_EFFECT_HULLMOD_ID = "Moci_open_maintenance_gantry_fleet_effect";
        private IntervalUtil updateInterval = new IntervalUtil(1f, 1f);
        private int lastMaintenanceGantryCount = 0;
        // 不将舰队成员引用写入存档；读档后首次检查会刷新全部当前成员。
        private transient Set<FleetMemberAPI> lastFleetMembers;

        public boolean isDone() {
            return false;
        }

        public boolean runWhilePaused() {
            return true;
        }

        public void advance(float amount) {
            updateInterval.advance(amount);
            if (updateInterval.intervalElapsed()) {
                updateFleetEffects();
            }
        }

        private void updateFleetEffects() {
            if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
                return;
            }
            CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
            if (playerFleet.getFleetData() == null) {
                return;
            }

            List<FleetMemberAPI> members = playerFleet.getFleetData().getMembersListCopy();
            Set<FleetMemberAPI> currentMembers = new HashSet<>(members);
            int maintenanceGantryCount = 0;
            for (FleetMemberAPI member : members) {
                if (member != null && member.getVariant() != null && !member.isMothballed()
                        && member.getVariant().hasHullMod(GANTRY_HULLMOD_ID)) {
                    maintenanceGantryCount++;
                }
            }
            // 超出叠加上限的数量变化不会改变效果，无需重建所有舰船属性。
            maintenanceGantryCount = Math.min(maintenanceGantryCount,
                    Moci_OpenMaintenanceGantryFleetEffect.MAX_FLEET_GANTRY);
            boolean refreshAll = lastFleetMembers == null
                    || maintenanceGantryCount != lastMaintenanceGantryCount;
            boolean needSync = false;

            if (lastFleetMembers != null) {
                for (FleetMemberAPI member : lastFleetMembers) {
                    if (member == null || currentMembers.contains(member) || member.getVariant() == null
                            || !member.getVariant().hasHullMod(FLEET_EFFECT_HULLMOD_ID)) {
                        continue;
                    }
                    // 出售、存入仓库或移交后的舰船不再保留玩家舰队专属效果。
                    ShipVariantAPI variant = member.getVariant().clone();
                    variant.setSource(VariantSource.REFIT);
                    variant.setHullVariantId(Misc.genUID());
                    variant.removePermaMod(FLEET_EFFECT_HULLMOD_ID);
                    variant.removeMod(FLEET_EFFECT_HULLMOD_ID);
                    member.setVariant(variant, false, false);
                    member.updateStats();
                    needSync = true;
                }
            }

            for (FleetMemberAPI member : members) {
                if (member == null || member.getVariant() == null) {
                    continue;
                }
                boolean addedEffect = !member.getVariant().hasHullMod(FLEET_EFFECT_HULLMOD_ID);
                if (addedEffect) {
                    ShipVariantAPI variant = member.getVariant().clone();
                    variant.setSource(VariantSource.REFIT);
                    variant.setHullVariantId(Misc.genUID());
                    // 先完成变体修改，再交给舰队成员，确保属性重算能看到新增效果。
                    variant.addPermaMod(FLEET_EFFECT_HULLMOD_ID);
                    member.setVariant(variant, false, false);
                }
                if (refreshAll || addedEffect || !lastFleetMembers.contains(member)) {
                    member.updateStats();
                    needSync = true;
                }
            }

            lastFleetMembers = currentMembers;
            lastMaintenanceGantryCount = maintenanceGantryCount;
            if (needSync) {
                playerFleet.forceSync();
            }
        }
    }

    // 维修龙门架管理结束

    public static void initShaderLib() {
        ShaderLib.init();
        LightData.readLightDataCSV("data/lights/Moci_light_data.csv");
        TextureData.readTextureDataCSV("data/lights/Moci_texture_data.csv");
        Moci_GNShieldRendering_Shader.registerNormalTextureDataCSV("data/lights/Moci_texture_data.csv");
        registerOptionalNormalTextureDataCSV(NXN_MOD_ID, NXN_TEXTURE_DATA_CSV);
    }

    private static void registerOptionalNormalTextureDataCSV(String modId, String csvPath) {
        if (Global.getSettings() == null
                || Global.getSettings().getModManager() == null
                || !Global.getSettings().getModManager().isModEnabled(modId)) {
            return;
        }

        try {
            Moci_GNShieldRendering_Shader.registerNormalTextureDataCSV(csvPath);
            log.info("已为可选模组 " + modId + " 注册GN护盾法线贴图数据: " + csvPath);
        } catch (Exception e) {
            log.warn("为可选模组 " + modId + " 注册GN护盾法线贴图数据失败: " + csvPath, e);
        }
    }
    @Override
    public PluginPick<ShipAIPlugin> pickShipAI(FleetMemberAPI member, ShipAPI ship) {
        if (Moci_MechTurretAI.shouldAssignTurretAI(ship)) {
            return new PluginPick<>(new Moci_MechTurretAI(ship), CampaignPlugin.PickPriority.HIGHEST);
        }
        return null;
    }

    @Override
    public PluginPick<AutofireAIPlugin> pickWeaponAutofireAI(WeaponAPI weapon) {
        switch (weapon.getId()) {
            case "Moci_Weapon1":
                return new PluginPick<AutofireAIPlugin>(new Moci_Weapon1AI(weapon), PickPriority.HIGHEST);
            case "Moci_Aestivalis_Weapon1":
            case "Moci_Aestivalis_Gekiganflare":
            case "Moci_Black_Gekiganflare":
                return new PluginPick<AutofireAIPlugin>(new Moci_Aestivalis_Weapon1AI(weapon), PickPriority.HIGHEST);
            case "Moci_MS_06R_ammo_left":
            case "Moci_MS_06R_ammo_right":
                return new PluginPick<AutofireAIPlugin>(null, PickPriority.HIGHEST);
            // case "Moci_Weapon2":
            // return new PluginPick<AutofireAIPlugin>(new Moci_Weapon2AI(weapon),
            // PickPriority.HIGHEST);
        }
        return null;
    }
                    
    @Override
    public void onApplicationLoad() throws Exception {
        initShaderLib();
        Moci_MechTurretAI.loadSettings();

        // 初始化MagicLib子系统管理器（可选依赖，失败不影响主功能）
        try {
            org.magiclib.subsystems.MagicSubsystemsManager.initialize();
        } catch (Exception e) {
            // MagicLib未安装，跳过
        }

        // 初始化动态部署点系统配置
        initializeDynamicDeploymentSystem();

        // 预加载尾迹纹理
        Global.getSettings().loadTexture(TAIL_S_SPRITE_PATH);
        Global.getSettings().loadTexture(TAIL_M_SPRITE_PATH);
        Global.getSettings().loadTexture(TAIL_L_SPRITE_PATH);
        
        // 预加载尾迹纹理 - ShroudedDragon (8个体节)
        for (int i = 0; i < SHROUDED_DRAGON_TAIL_SEGMENTS; i++) {
            String path = SHROUDED_DRAGON_TAIL_PATH_PREFIX + i + SHROUDED_DRAGON_TAIL_PATH_SUFFIX;
            Global.getSettings().loadTexture(path);
        }
        for (int i = 0; i < HOAR_SHROUD_DRAGON_TAIL_SEGMENTS; i++) {
            String path = HOAR_SHROUD_DRAGON_TAIL_PATH_PREFIX + i + HOAR_SHROUD_DRAGON_TAIL_PATH_SUFFIX;
            Global.getSettings().loadTexture(path);
        }
        
        // 预加载尾迹纹理 - Black Selena (起始+中端+末端)
        Global.getSettings().loadTexture(Black_Sarena_TAIL_START_SPRITE_PATH);
        Global.getSettings().loadTexture(Black_Sarena_TAIL_MID_SPRITE_PATH);
        Global.getSettings().loadTexture(Black_Sarena_TAIL_END_SPRITE_PATH);
    }

    /**
     * 初始化动态部署点系统
     */
    private void initializeDynamicDeploymentSystem() {
        // 根据ModPlugin中的开关设置动态部署点系统状态
        data.hullmods.Moci_DynamicDeploymentConfig.setDynamicDeployment(ENABLE_DYNAMIC_DEPLOYMENT);

        if (ENABLE_DYNAMIC_DEPLOYMENT) {
            log.info("动态部署点系统已启用 - 将根据僚机配置动态调整部署点消耗");
        } else {
            log.info("动态部署点系统已禁用 - 将始终使用固定1.5倍部署点惩罚");
        }
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // 初始化Tulip殖民地（支持中途加入mod）
        Moci_TulipColonyManager.initializeTulipColony();
        // 尼尔加星系仅在缺失时创建，已存在时保留当前归属及市场状态。
        Moci_NergalHeavyIndustriesColonyManager.initializeNergalColony();
        if (!newGame) {
            Moci_NadesicoStoryRouteManager.ensureRouteState();
        }
        Moci_YurikaStoryManager.ensureLoaded();
        
        // 加载CombatSquad配置文件
        data.hullmods.Moci_CombatSquadConfigLoader.loadAllConfigs();
        // 加载舰载AI配置文件
        data.hullmods.Moci_ShipboardAIConfigLoader.loadAllConfigs();
        
        // 检查扩展mod状态
        boolean isExtensionModEnabled = Global.getSettings().getModManager().isModEnabled(MOD_ID_TO_CHECK);
        Global.getSector().getMemoryWithoutUpdate().set(MEMORY_KEY, !isExtensionModEnabled);

        // 注册AI核心插件
        registerAICorePlugin();
        registerRecoverableShipCampaignPlugin();

        // 加载抽卡数据
        loadGachaData();

        // 确保动态部署点系统配置正确应用
        initializeDynamicDeploymentSystem();

        // 初始化或生成扭蛋星系（大和号残骸会在星系生成时一起创建）
        initializeGachaMobSystem(newGame);
        registerCombatLootListener();

        // 初始化维护舱门管理器
        initializeMaintenanceGantryManager();

        // 初始化CombatSquad Intel管理
        initializeCombatSquadIntel();
        // 初始化舰载AI Intel管理
        initializeShipboardAIIntel();

        // 注册CombatSquad机师经验系统
        registerCombatSquadBattleListener();
        // 注册舰载AI经验系统
        registerShipboardAIBattleListener();
    }

    /**
     * 注册AI核心插件
     * 使用持久化标记确保只注册一次
     */
    private void registerAICorePlugin() {
        // 检查是否已经注册过
        boolean alreadyRegistered = false;
        if (Global.getSector().getPersistentData().containsKey(AI_CORE_PLUGIN_REGISTERED)) {
            alreadyRegistered = (Boolean) Global.getSector().getPersistentData().get(AI_CORE_PLUGIN_REGISTERED);
        }
        
        if (!alreadyRegistered) {
            Global.getSector().registerPlugin(new data.campaign.Moci_AICoreOfficerCampaignPlugin());
            Global.getSector().getPersistentData().put(AI_CORE_PLUGIN_REGISTERED, true);
            log.info("Moci AI核心插件已注册");
        }
    }

    /**
     * 加载抽卡数据
     */
    private void loadGachaData() {
        try {
            Moci_GachaManager.loadGachaData(Moci_GachaManager.getInstance());
        } catch (JSONException e) {
            log.error("读取Moci抽卡数据失败 - JSON格式错误: " + e.getMessage(), e);
            throw new RuntimeException("抽卡数据JSON格式错误", e);
        } catch (IOException e) {
            log.error("读取Moci抽卡数据失败 - 文件读取错误: " + e.getMessage(), e);
            throw new RuntimeException("抽卡数据文件读取失败", e);
        }
    }

    /**
     * 初始化扭蛋星系
     */
    private void initializeGachaMobSystem(boolean newGame) {
        Moci_GachaMobManager.initializeGachaMobSystem();
    }

    /**
     * 初始化维护舱门管理器
     */
    private void initializeMaintenanceGantryManager() {
        MaintenanceGantryManager manager = null;
        for (EveryFrameScript script : Global.getSector().getScripts()) {
            if (script instanceof MaintenanceGantryManager) {
                manager = (MaintenanceGantryManager) script;
                break;
            }
        }
        if (manager == null) {
            manager = new MaintenanceGantryManager();
            Global.getSector().addScript(manager);
            log.info("维修龙门架管理器已添加到游戏脚本中");
        }
        // 已有存档脚本也要检查当前成员，避免读档后漏掉新增舰船。
        manager.updateFleetEffects();
    }

    /**
     * 初始化CombatSquad Intel管理
     */
    private void initializeCombatSquadIntel() {
        log.info("=== CombatSquad Intel管理系统初始化开始 ===");

        // 检查是否已存在
        boolean hasIntel = Global.getSector().getIntelManager().hasIntelOfClass(Moci_CombatSquadManagerIntel.class);
        log.info("Intel存在检查结果: " + hasIntel);

        if (hasIntel) {
            int refreshed = 0;
            for (Object intelObj : Global.getSector().getIntelManager().getIntel()) {
                if (intelObj instanceof Moci_CombatSquadManagerIntel) {
                    ((Moci_CombatSquadManagerIntel) intelObj).setImportant(true);
                    refreshed++;
                }
            }
            log.info("CombatSquad机师管理Intel已存在，已刷新重要标记，数量: " + refreshed);
            log.info("CombatSquad机师管理Intel已存在，跳过创建");
            log.info("=== CombatSquad Intel管理系统初始化完成 ===");
            return;
        }

        // 创建新Intel
        log.info("正在创建新的CombatSquad Intel...");
        Moci_CombatSquadManagerIntel intel = new Moci_CombatSquadManagerIntel();
        log.info("CombatSquad Intel实例创建成功: " + intel.getClass().getName());

        // 验证是否成功添加
        boolean hasAfterCreation = Global.getSector().getIntelManager()
                .hasIntelOfClass(Moci_CombatSquadManagerIntel.class);
        log.info("Intel添加验证: " + hasAfterCreation);
        log.info("当前Intel总数: " + Global.getSector().getIntelManager().getIntel().size());

        log.info("CombatSquad机师管理Intel创建完成");
        log.info("=== CombatSquad Intel管理系统初始化完成 ===");
    }

    @Override
    public void onNewGame() {
        // 初始化Tulip殖民地
        Moci_TulipColonyManager.initializeTulipColony();
        Moci_NergalHeavyIndustriesColonyManager.initializeNergalColony();
        Moci_YurikaStoryManager.ensureLoaded();
        registerRecoverableShipCampaignPlugin();
        
        // 新游戏时也注册战斗监听器
        registerCombatLootListener();
        registerCombatSquadBattleListener();
        registerShipboardAIBattleListener();
    }

    @Override
    public void onNewGameAfterTimePass() {
        Moci_CampaignTime.recordNewGameStart();
        Moci_NadesicoStoryRouteManager.initializeForNewGame();
        Moci_YurikaStoryManager.ensureLoaded();
        registerRecoverableShipCampaignPlugin();
        registerCombatLootListener();
    }

    private void registerCombatLootListener() {
        removeListenersOfClass(Moci_CombatLootListener.class, "Moci战斗掉落");

        if (!ENABLE_COMBAT_LOOT_DROPS) {
            log.info("Moci战斗掉落监听器未注册：功能开关已关闭");
            return;
        }

        Global.getSector().addListener(new Moci_CombatLootListener());
        log.info("Moci战斗掉落监听器已注册");
    }

    /**
     * 注册CombatSquad战斗监听器
     */
    private void registerCombatSquadBattleListener() {
        log.info("=== CombatSquad机师经验系统初始化开始 ===");

        removeListenersOfClass(Moci_CombatSquadBattleListener.class, "CombatSquad经验");

        // 注册新的战斗监听器
        battleListener = new Moci_CombatSquadBattleListener();
        Global.getSector().addListener(battleListener);

        log.info("CombatSquad战斗监听器已注册");
        log.info("=== CombatSquad机师经验系统初始化完成 ===");
    }

    /**
     * 初始化舰载AI Intel管理
     */
    private void initializeShipboardAIIntel() {
        log.info("=== 舰载AI Intel管理系统初始化开始 ===");

        boolean hasIntel = Global.getSector().getIntelManager().hasIntelOfClass(Moci_ShipboardAIManagerIntel.class);
        log.info("舰载AI Intel存在检查结果: " + hasIntel);

        if (hasIntel) {
            log.info("舰载AI管理Intel已存在，跳过创建");
            log.info("=== 舰载AI Intel管理系统初始化完成 ===");
            return;
        }

        log.info("正在创建新的舰载AI Intel...");
        Moci_ShipboardAIManagerIntel intel = new Moci_ShipboardAIManagerIntel();
        log.info("舰载AI Intel实例创建成功: " + intel.getClass().getName());
        log.info("=== 舰载AI Intel管理系统初始化完成 ===");
    }

    /**
     * 注册舰载AI战斗监听器
     */
    private void registerShipboardAIBattleListener() {
        log.info("=== 舰载AI经验系统初始化开始 ===");

        removeListenersOfClass(Moci_ShipboardAIBattleListener.class, "舰载AI经验");

        shipboardAIBattleListener = new Moci_ShipboardAIBattleListener();
        Global.getSector().addListener(shipboardAIBattleListener);

        log.info("舰载AI战斗监听器已注册");
        log.info("=== 舰载AI经验系统初始化完成 ===");
    }

    private ListenerManagerAPI getListenerManager() {
        if (Global.getSector() == null) {
            return null;
        }
        return Global.getSector().getListenerManager();
    }

    private <T> void removeListenersOfClass(Class<T> cls, String tag) {
        ListenerManagerAPI manager = getListenerManager();
        if (manager == null) {
            return;
        }
        List<T> toRemove = new ArrayList<T>(manager.getListeners(cls));
        boolean removedAny = false;
        for (T listener : toRemove) {
            manager.removeListener(listener);
            removedAny = true;
            log.info("【" + tag + "】移除旧战斗监听器（按类）");
        }
        if (removedAny) {
            if (cls == Moci_CombatSquadBattleListener.class) {
                battleListener = null;
            } else if (cls == Moci_ShipboardAIBattleListener.class) {
                shipboardAIBattleListener = null;
            }
        }
    }

    private void registerRecoverableShipCampaignPlugin() {
        if (Global.getSector() == null) {
            return;
        }
        Global.getSector().unregisterPlugin(MociRecoverableShipCampaignPlugin.PLUGIN_ID);
        Global.getSector().registerPlugin(new MociRecoverableShipCampaignPlugin());
    }
}
