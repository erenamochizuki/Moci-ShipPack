package data.scripts.weapons;

import java.util.Iterator;
import java.util.LinkedList;

import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Zero 浮游炮效果类2（模块装配复制版）
 * 
 * 功能说明：
 * 1. 检测对应槽位上的模块
 * 2. 复制模块的装配进行部署
 * 3. 管理每个武器的浮游炮数量上限
 * 4. 超出上限时自动摧毁最早的浮游炮
 * 5. 提供玩家UI状态显示
 * 6. 支持智能AI管理
 * 
 * 使用方法：
 * 在武器的proj文件中配置：
 * "behaviorJSON":{
 * "droneId":"你的无人机hull_id",
 * "maxDrones":4
 * }
 * 
 * 系统会自动查找与该武器槽位对应的模块（WINGMAN_X -> WINGWEAPON_X），
 * 并复制该模块的完整装配来生成浮游炮
 */
public class Moci_ZeroWingman2_Effect implements OnFireEffectPlugin, EveryFrameWeaponEffectPlugin {

    // 默认浮游炮数量上限
    private static final int DEFAULT_MAX_DRONES = 4;
    private static final int MIN_MAX_DRONES = 1;
    private static final int MAX_MAX_DRONES = 20;
    
    // 存储该武器生成的浮游炮列表（FIFO队列）
    private final LinkedList<ShipAPI> activeDrones = new LinkedList<>();
    
    // 缓存的最大浮游炮数量
    private int maxDrones = DEFAULT_MAX_DRONES;
    private boolean configInitialized = false;

    private boolean initialized = false;
    private ShipAPI matchedModule = null;

    /**
     * 每帧更新 - 用于查找对应的模块
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        ShipAPI mothership = Moci_WingmanSupport.resolveRootMothership(ship);

        if (Moci_WingmanSupport.destroyAllDronesIfMothershipInvalid(engine, activeDrones, mothership)) {
            return;
        }

        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 初始化 - 根据槽位名称匹配模块
        if (!initialized) {
            initialized = true;
            String currentSlotId = weapon.getSlot().getId();

            String targetSlotId = null;
            if (currentSlotId.startsWith("WINGMAN_")) {
                String suffix = currentSlotId.substring("WINGMAN_".length());
                targetSlotId = "WINGWEAPON_" + suffix;
            }

            if (targetSlotId != null) {
                // 查找对应的模块
                for (ShipAPI child : ship.getChildModulesCopy()) {
                    if (child != null && child.getStationSlot() != null) {
                        String moduleSlotId = child.getStationSlot().getId();
                        if (targetSlotId.equals(moduleSlotId)) {
                            matchedModule = child;
                            break;
                        }
                    }
                }
            }
        }
        
        // 清理已死亡的浮游炮
        cleanupDeadDrones();
        
        // 显示玩家UI状态
        updatePlayerUI(engine, weapon, ship);
    }
    
    /**
     * 清理已死亡或变成残骸的浮游炮
     */
    private void cleanupDeadDrones() {
        if (activeDrones.isEmpty()) {
            return;
        }
        
        Iterator<ShipAPI> iterator = activeDrones.iterator();
        while (iterator.hasNext()) {
            ShipAPI drone = iterator.next();
            if (drone == null || !drone.isAlive() || drone.isHulk()) {
                iterator.remove();
            }
        }
    }
    
    /**
     * 检查是否启用状态显示
     */
    private boolean isStatusDisplayEnabled() {
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                return lunalib.lunaSettings.LunaSettings.getBoolean("moci_shippack", "Moci_showWingmanStatus");
            }
        } catch (Exception e) {
            // LunaLib not available, default to true
        }
        return true;
    }
    
    /**
     * 更新玩家UI状态显示
     */
    private void updatePlayerUI(CombatEngineAPI engine, WeaponAPI weapon, ShipAPI ship) {
        if (ship == null) return;

        ShipAPI statusShip = Moci_WingmanSupport.resolveRootMothership(ship);
        
        boolean playerShip = Global.getCurrentState() == GameState.COMBAT &&
                engine.getPlayerShip() == statusShip;
        
        if (playerShip && isStatusDisplayEnabled()) {
            int activeCount = activeDrones.size();
            // 只有当有浮游炮时才显示状态
            if (activeCount > 0) {
                engine.maintainStatusForPlayerShip(
                    this,
                    "graphics/icons/hullsys/drone_pd_high.png",
                    weapon.getDisplayName(), 
                    "浮游炮: " + activeCount + " / " + maxDrones,
                    false
                );
            }
        }
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (!(projectile instanceof MissileAPI)) {
            return;
        }
        
        MissileAPI missile = (MissileAPI) projectile;
        JSONObject json = missile.getSpec().getBehaviorJSON();
        
        // 初始化配置
        if (!configInitialized) {
            initializeConfig(json);
        }

        String hullId = json.optString("droneId", null);

        if (hullId != null && matchedModule != null) {
            // 检查并强制执行数量上限
            enforceMaxDrones(engine);
            
            // 使用匹配到的模块来生成浮游炮
            ShipAPI drone = createDroneFromModule(projectile, engine, matchedModule);
            
            if (drone != null) {
                activeDrones.add(drone);
            }
        }

        engine.removeEntity(missile);
    }
    
    /**
     * 初始化配置参数
     */
    private void initializeConfig(JSONObject json) {
        try {
            int configMaxDrones = json.optInt("maxDrones", DEFAULT_MAX_DRONES);
            maxDrones = Math.max(MIN_MAX_DRONES, Math.min(MAX_MAX_DRONES, configMaxDrones));
            configInitialized = true;
            
            Global.getLogger(this.getClass()).info(
                "Zero Wingman2 initialized: maxDrones=" + maxDrones
            );
        } catch (Exception e) {
            Global.getLogger(this.getClass()).warn(
                "Failed to initialize Zero Wingman2 config, using defaults", e
            );
            maxDrones = DEFAULT_MAX_DRONES;
            configInitialized = true;
        }
    }
    
    /**
     * 强制执行数量上限
     */
    private void enforceMaxDrones(CombatEngineAPI engine) {
        cleanupDeadDrones();
        
        while (activeDrones.size() >= maxDrones) {
            ShipAPI oldestDrone = activeDrones.pollFirst();
            if (oldestDrone != null && oldestDrone.isAlive() && !oldestDrone.isHulk()) {
                Moci_WingmanSupport.destroyDrone(engine, oldestDrone);
            }
        }
    }
    
    /**
     * 从模块创建浮游炮无人机（复制模块的装配）
     */
    private ShipAPI createDroneFromModule(DamagingProjectileAPI projectile, CombatEngineAPI engine, 
                                          ShipAPI moduleSource) {
        CombatFleetManagerAPI fleet = engine.getFleetManager(projectile.getSource().getOwner());
        boolean shouldSuppressBefore = fleet != null && fleet.isSuppressDeploymentMessages();
        if (fleet != null) {
            fleet.setSuppressDeploymentMessages(true);
        }

        try {
            ShipAPI sourceShip = projectile.getSource();
            ShipAPI mothership = Moci_WingmanSupport.resolveRootMothership(sourceShip);
            
            // 从模块的FleetMember创建新的FleetMember（复制装配）
            com.fs.starfarer.api.fleet.FleetMemberAPI toSpawn = Global.getFactory().createFleetMember(
                com.fs.starfarer.api.fleet.FleetMemberType.SHIP, 
                moduleSource.getFleetMember().getVariant()
            );
            
            // 设置CR和船员
            toSpawn.getRepairTracker().setCR(projectile.getSource().getCurrentCR());
            toSpawn.getRepairTracker().setMothballed(false);
            toSpawn.getRepairTracker().setCrashMothballed(false);
            toSpawn.getCrewComposition().addCrew(moduleSource.getHullSpec().getMinCrew());
            toSpawn.updateStats();
            
            // 生成舰船
            ShipAPI drone = fleet.spawnFleetMember(
                toSpawn, 
                projectile.getLocation(), 
                projectile.getFacing(), 
                0f
            );
            
            // 设置基本属性
            drone.setOriginalOwner(sourceShip != null ? sourceShip.getOriginalOwner() : projectile.getOwner());
            drone.setOwner(sourceShip != null ? sourceShip.getOwner() : projectile.getOwner());
            drone.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
            
            if (sourceShip != null && sourceShip.isAlly()) {
                drone.setAlly(true);
            }
            
            drone.setDrone(true);
            Moci_WingmanSupport.linkDroneToMothership(drone, mothership);
            
            // 继承母舰的伤害加成
            if (sourceShip != null && sourceShip.getMutableStats() != null) {
                drone.getMutableStats().getEnergyWeaponDamageMult()
                        .applyMods(sourceShip.getMutableStats().getEnergyWeaponDamageMult());
                drone.getMutableStats().getBallisticWeaponDamageMult()
                        .applyMods(sourceShip.getMutableStats().getBallisticWeaponDamageMult());
                drone.getMutableStats().getMissileWeaponDamageMult()
                        .applyMods(sourceShip.getMutableStats().getMissileWeaponDamageMult());
            }
            
            drone.setCollisionClass(CollisionClass.FIGHTER);
            
            // 设置速度
            float facing = drone.getFacing();
            float speedAmount = 400f;
            Vector2f velocity = new Vector2f(
                    (float) Math.cos(Math.toRadians(facing)) * speedAmount,
                    (float) Math.sin(Math.toRadians(facing)) * speedAmount);
            drone.getVelocity().set(velocity);
            
            // 设置AI
            ShipAIPlugin originalAI = drone.getShipAI();
            drone.setHullSize(ShipAPI.HullSize.FRIGATE);
            ShipAIConfig config = new ShipAIConfig();
            config.alwaysStrafeOffensively = true;
            drone.setShipAI(Global.getSettings().createDefaultShipAI(drone, config));
            drone.setHullSize(ShipAPI.HullSize.FIGHTER);
            
            // 标记为Zero系列无人机，启用智能AI管理
            drone.setCustomData("Moci_Zero_Wingman", true);
            drone.setCustomData("Moci_Zero_AIConfig", config);
            drone.setCustomData("Moci_Zero_OriginalAI", originalAI);
            
            // 添加无人机AI管理器
            data.hullmods.Moci_WingmanAIManager aiManager = new data.hullmods.Moci_WingmanAIManager();
            aiManager.setManagedShip(drone);
            drone.addListener(aiManager);

            return drone;
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error(
                "Failed to create drone from module", e
            );
            return null;
        } finally {
            if (fleet != null) {
                fleet.setSuppressDeploymentMessages(shouldSuppressBefore);
            }
        }
    }
}
