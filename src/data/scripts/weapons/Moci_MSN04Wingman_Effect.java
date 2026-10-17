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
 * MSN04 浮游炮效果类
 * 
 * 功能说明：
 * 1. 生成浮游炮无人机
 * 2. 管理每个武器的浮游炮数量上限
 * 3. 超出上限时自动摧毁最早的浮游炮
 * 4. 提供玩家UI状态显示
 * 5. 支持发射动画
 * 
 * 核心代码来自光环的小帮手
 */
public class Moci_MSN04Wingman_Effect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    
    // 默认浮游炮数量上限
    private static final int DEFAULT_MAX_DRONES = 4;
    private static final int MIN_MAX_DRONES = 1;
    private static final int MAX_MAX_DRONES = 20;
    
    // 存储该武器生成的浮游炮列表（FIFO队列）
    private final LinkedList<ShipAPI> activeDrones = new LinkedList<>();
    
    // 缓存的最大浮游炮数量
    private int maxDrones = DEFAULT_MAX_DRONES;
    private boolean configInitialized = false;
    
    // 动画相关变量
    private static final float LAUNCH_ANIM_SPEED = 8f;
    private static final int LAUNCH_START_FRAME = 6;
    private static final int LAUNCH_END_FRAME = 9;
    
    private static final int[] AMMO_FRAMES = {
            1, 2, 3, 4, 5, 0
    };
    
    private boolean isLaunching = false;

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

        if (weapon.getChargeLevel() >= 1 && !isLaunching) {
            isLaunching = true;
        }
        
        handleWeaponAnimation(weapon);
        
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

    /**
     * 处理武器动画
     * @param weapon 武器实例
     */
    private void handleWeaponAnimation(WeaponAPI weapon) {
        if (weapon == null) return;

        // 如果正在发射，处理发射动画
        if (isLaunching) {
            if (weapon.getAnimation().getFrame() < LAUNCH_START_FRAME ||
                    weapon.getAnimation().getFrame() > LAUNCH_END_FRAME) {
                weapon.getAnimation().setFrame(LAUNCH_START_FRAME);
            }
            weapon.getAnimation().setFrameRate(LAUNCH_ANIM_SPEED); // 设置动画速度
            weapon.getAnimation().play();
            
            // 如果动画播放到结束帧，重置发射状态
            if (weapon.getAnimation().getFrame() >= LAUNCH_END_FRAME) {
                isLaunching = false;
            }
        } else {
            // 根据剩余弹药数量设置动画帧
            int ammoCount = weapon.getAmmo();
            int frameIndex = Math.min(ammoCount, AMMO_FRAMES.length - 1);
            weapon.getAnimation().setFrame(AMMO_FRAMES[frameIndex]);
            weapon.getAnimation().pause();
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
        String weaponId = json.optString("weaponId", null);

        if (hullId != null) {
            // 检查并强制执行数量上限
            enforceMaxDrones(engine);
            
            // 生成新的浮游炮
            ShipAPI drone = createDrone(projectile, engine, hullId, weaponId);
            
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
                "MSN04 Wingman initialized: maxDrones=" + maxDrones
            );
        } catch (Exception e) {
            Global.getLogger(this.getClass()).warn(
                "Failed to initialize MSN04 Wingman config, using defaults", e
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
     * 创建浮游炮无人机
     */
    private ShipAPI createDrone(DamagingProjectileAPI projectile, CombatEngineAPI engine, 
                                String hullId, String weaponId) {
        try {
            ShipAPI sourceShip = projectile.getSource();
            ShipAPI mothership = Moci_WingmanSupport.resolveRootMothership(sourceShip);

            ShipHullSpecAPI spec = Global.getSettings().getHullSpec(hullId);
            ShipVariantAPI v = Global.getSettings().createEmptyVariant(hullId + "_Drone", spec);
            
            if (weaponId != null) {
                v.addWeapon("WS0001", weaponId);
                WeaponGroupSpec g = new WeaponGroupSpec(WeaponGroupType.LINKED);
                g.addSlot("WS0001");
                g.setAutofireOnByDefault(true);
                v.addWeaponGroup(g);
            }
            
            ShipAPI drone = engine.createFXDrone(v);
            drone.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
            drone.setOwner(projectile.getOwner());
            
            if (sourceShip != null && sourceShip.isAlly()) {
                drone.setAlly(true);
            }
            
            drone.setDrone(true);
            Moci_WingmanSupport.linkDroneToMothership(drone, mothership);
            
            if (sourceShip != null && sourceShip.getMutableStats() != null) {
                drone.getMutableStats().getEnergyWeaponDamageMult()
                        .applyMods(sourceShip.getMutableStats().getEnergyWeaponDamageMult());
            }
            
            drone.setCollisionClass(CollisionClass.FIGHTER);
            drone.setFacing(projectile.getFacing());
            
            engine.addEntity(drone);
            drone.getLocation().set(projectile.getLocation());

            drone.setHullSize(ShipAPI.HullSize.FRIGATE);
            ShipAIConfig config = new ShipAIConfig();
            config.alwaysStrafeOffensively = true;
            drone.setShipAI(Global.getSettings().createDefaultShipAI(drone, config));
            drone.setHullSize(ShipAPI.HullSize.FIGHTER);
            
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
