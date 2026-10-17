package data.scripts.weapons;

import java.util.Iterator;
import java.util.LinkedList;

import org.json.JSONObject;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import org.lwjgl.util.vector.Vector2f;

/**
 * NZ333 浮游炮效果类
 * 
 * 功能说明：
 * 1. 生成浮游炮无人机
 * 2. 管理每个武器的浮游炮数量上限
 * 3. 超出上限时自动摧毁最早的浮游炮
 * 4. 提供玩家UI状态显示
 * 
 * 核心代码来自光环的小帮手
 */
public class Moci_NZ333Wingman_Effect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

    // 默认浮游炮数量上限
    private static final int DEFAULT_MAX_DRONES = 4;
    // 最小上限
    private static final int MIN_MAX_DRONES = 1;
    // 最大上限
    private static final int MAX_MAX_DRONES = 20;
    
    // 存储该武器生成的浮游炮列表（FIFO队列）
    private final LinkedList<ShipAPI> activeDrones = new LinkedList<>();
    
    // 缓存的最大浮游炮数量
    private int maxDrones = DEFAULT_MAX_DRONES;
    // 是否已初始化配置
    private boolean configInitialized = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        ShipAPI mothership = Moci_WingmanSupport.resolveRootMothership(ship);
        
        // 清理已死亡的浮游炮
        cleanupDeadDrones();

        if (Moci_WingmanSupport.destroyAllDronesIfMothershipInvalid(engine, activeDrones, mothership)) {
            return;
        }

        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }
        
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
            // 移除已死亡或变成残骸的浮游炮
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
        // 检查是否为玩家舰船
        ShipAPI statusShip = Moci_WingmanSupport.resolveRootMothership(ship);
        boolean playerShip = Global.getCurrentState() == GameState.COMBAT &&
                engine.getPlayerShip() == statusShip;
        
        if (playerShip && isStatusDisplayEnabled()) {
            int activeCount = activeDrones.size();
            // 只有当有浮游炮时才显示状态
            if (activeCount > 0) {
                // 为玩家显示状态信息
                engine.maintainStatusForPlayerShip(
                    this,
                    "graphics/icons/hullsys/drone_pd_high.png", // 使用无人机图标
                    weapon.getDisplayName(), 
                    "浮游炮: " + activeCount + " / " + maxDrones,
                    false // 有浮游炮时显示为正常状态
                );
            }
        }
    }

    /**
     * 处理武器发射事件
     * @param weapon 武器实例
     * @param projectile 发射的射弹
     */
    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (!(projectile instanceof MissileAPI)) {
            return;
        }
        
        MissileAPI missile = (MissileAPI) projectile;
        // 获取导弹的行为JSON配置
        JSONObject json = missile.getSpec().getBehaviorJSON();
        
        // 初始化配置（只在第一次发射时执行）
        if (!configInitialized) {
            initializeConfig(json);
        }

        // 从配置中获取无人机ID、武器ID
        String hullId = json.optString("droneId", null);
        String weaponId = json.optString("weaponId", null);

        // 如果配置了无人机ID
        if (hullId != null) {
            // 检查是否超出数量上限，如果超出则移除最早的浮游炮
            enforceMaxDrones(engine);
            
            // 生成新的浮游炮
            ShipAPI drone = createDrone(projectile, engine, hullId, weaponId);
            
            if (drone != null) {
                // 添加到活跃列表
                activeDrones.add(drone);
            }
        }
        
        // 移除导弹
        engine.removeEntity(missile);
    }
    
    /**
     * 初始化配置参数
     */
    private void initializeConfig(JSONObject json) {
        try {
            // 读取最大浮游炮数量配置
            int configMaxDrones = json.optInt("maxDrones", DEFAULT_MAX_DRONES);
            
            // 限制在合理范围内
            maxDrones = Math.max(MIN_MAX_DRONES, Math.min(MAX_MAX_DRONES, configMaxDrones));
            
            configInitialized = true;
            
            Global.getLogger(this.getClass()).info(
                "NZ333 Wingman initialized: maxDrones=" + maxDrones
            );
        } catch (Exception e) {
            Global.getLogger(this.getClass()).warn(
                "Failed to initialize NZ333 Wingman config, using defaults", e
            );
            maxDrones = DEFAULT_MAX_DRONES;
            configInitialized = true;
        }
    }
    
    /**
     * 强制执行数量上限，超出时移除最早的浮游炮
     */
    private void enforceMaxDrones(CombatEngineAPI engine) {
        // 先清理死亡的浮游炮
        cleanupDeadDrones();
        
        // 如果超出上限，移除最早的浮游炮
        while (activeDrones.size() >= maxDrones) {
            ShipAPI oldestDrone = activeDrones.pollFirst();
            if (oldestDrone != null && oldestDrone.isAlive() && !oldestDrone.isHulk()) {
                Moci_WingmanSupport.destroyDrone(engine, oldestDrone);
            }
        }
    }
    
    /**
     * 创建浮游炮无人机
     */
    private ShipAPI createDrone(DamagingProjectileAPI projectile, CombatEngineAPI engine, 
                                String hullId, String weaponId) {
        try {
            ShipAPI sourceShip = projectile.getSource();
            ShipAPI mothership = Moci_WingmanSupport.resolveRootMothership(sourceShip);

            // 获取无人机船体规格
            ShipHullSpecAPI spec = Global.getSettings().getHullSpec(hullId);
            // 创建无人机变体
            ShipVariantAPI v = Global.getSettings().createEmptyVariant(hullId + "_Drone", spec);
            
            // 如果配置了武器ID
            if (weaponId != null) {
                // 添加武器到无人机
                v.addWeapon("WS0001", weaponId);
                // 创建武器组并设置为自动开火
                WeaponGroupSpec g = new WeaponGroupSpec(WeaponGroupType.LINKED);
                g.addSlot("WS0001");
                g.setAutofireOnByDefault(true);
                v.addWeaponGroup(g);
            }
            
            // 生成无人机实体
            ShipAPI drone = engine.createFXDrone(v);
            // 设置无人机在战斗中的层级
            drone.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
            // 设置无人机所有者
            drone.setOwner(projectile.getOwner());
            
            // 如果导弹来源是友方，设置无人机为友方
            if (sourceShip != null && sourceShip.isAlly()) {
                drone.setAlly(true);
            }
            
            // 标记为无人机
            drone.setDrone(true);
            Moci_WingmanSupport.linkDroneToMothership(drone, mothership);
            
            // 继承导弹来源的武器伤害加成
            if (sourceShip != null && sourceShip.getMutableStats() != null) {
                drone.getMutableStats().getEnergyWeaponDamageMult()
                        .applyMods(sourceShip.getMutableStats().getEnergyWeaponDamageMult());
            }
            
            // 设置碰撞类型为战斗机
            drone.setCollisionClass(CollisionClass.FIGHTER);
            // 设置无人机朝向与导弹一致
            drone.setFacing(projectile.getFacing());
            
            // 将无人机添加到战斗引擎
            engine.addEntity(drone);
            // 设置无人机位置为导弹位置
            drone.getLocation().set(projectile.getLocation());

            // 使用默认的无人机AI
            drone.setHullSize(ShipAPI.HullSize.FRIGATE);
            ShipAIConfig config = new ShipAIConfig();
            config.alwaysStrafeOffensively = true;
            drone.setShipAI(Global.getSettings().createDefaultShipAI(drone, config));
            drone.setHullSize(ShipAPI.HullSize.FIGHTER);
            
            // 给无人机一个向前的初始速度
            float facing = drone.getFacing();
            float speedAmount = 400f;
            Vector2f velocity = new Vector2f(
                    (float) Math.cos(Math.toRadians(facing)) * speedAmount,
                    (float) Math.sin(Math.toRadians(facing)) * speedAmount);
            drone.getVelocity().set(velocity);
            
            return drone;
            
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error(
                "Failed to create drone: " + hullId, e
            );
            return null;
        }
    }
} 
