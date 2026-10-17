package data.scripts.weapons;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

import org.json.JSONObject;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ProximityExplosionEffect;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;

/**
 * HWI 浮游炮效果类
 * 
 * 功能说明：
 * 1. 生成浮游炮无人机（通过导弹爆炸触发）
 * 2. 管理每个武器的浮游炮数量上限
 * 3. 超出上限时自动摧毁最早的浮游炮
 * 4. 提供玩家UI状态显示
 * 
 * 核心代码来自光环的小帮手
 */
public class Moci_HWIWingmanEffect implements ProximityExplosionEffect, EveryFrameWeaponEffectPlugin {
    
    // 默认浮游炮数量上限
    private static final int DEFAULT_MAX_DRONES = 4;
    private static final int MIN_MAX_DRONES = 1;
    private static final int MAX_MAX_DRONES = 20;
    
    // 全局存储：每个武器的浮游炮列表
    private static final Map<WeaponAPI, LinkedList<ShipAPI>> weaponDrones = new HashMap<>();
    private static final Map<WeaponAPI, Integer> weaponMaxDrones = new HashMap<>();
    private static final Map<WeaponAPI, Boolean> weaponInitialized = new HashMap<>();
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        cleanupDeadDrones(weapon);

        ShipAPI mothership = Moci_WingmanSupport.resolveWeaponMothership(weapon);
        LinkedList<ShipAPI> drones = weaponDrones.get(weapon);
        if (Moci_WingmanSupport.destroyAllDronesIfMothershipInvalid(engine, drones, mothership)) {
            cleanupWeaponState(weapon);
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            cleanupWeaponState(weapon);
            return;
        }
        
        // 显示玩家UI状态
        updatePlayerUI(engine, weapon, ship);
    }
    
    private void cleanupWeaponState(WeaponAPI weapon) {
        weaponDrones.remove(weapon);
        weaponMaxDrones.remove(weapon);
        weaponInitialized.remove(weapon);
    }

    /**
     * 清理已死亡或变成残骸的浮游炮
     */
    private void cleanupDeadDrones(WeaponAPI weapon) {
        LinkedList<ShipAPI> drones = weaponDrones.get(weapon);
        if (drones == null || drones.isEmpty()) {
            cleanupWeaponState(weapon);
            return;
        }
        
        Iterator<ShipAPI> iterator = drones.iterator();
        while (iterator.hasNext()) {
            ShipAPI drone = iterator.next();
            if (drone == null || !drone.isAlive() || drone.isHulk()) {
                iterator.remove();
            }
        }
        if (drones.isEmpty()) {
            cleanupWeaponState(weapon);
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
            LinkedList<ShipAPI> drones = weaponDrones.get(weapon);
            int activeCount = (drones != null) ? drones.size() : 0;
            int maxDrones = weaponMaxDrones.getOrDefault(weapon, DEFAULT_MAX_DRONES);
            
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
     * 当导弹爆炸时触发
     * @param explosion 爆炸效果实体
     * @param originalProjectile 原始导弹实体
     */
    @Override
    public void onExplosion(DamagingProjectileAPI explosion, DamagingProjectileAPI originalProjectile) {
        CombatEngineAPI engine = Global.getCombatEngine();
        
        if (!(originalProjectile instanceof MissileAPI)) {
            return;
        }
        
        MissileAPI missile = (MissileAPI) originalProjectile;
        JSONObject json = missile.getSpec().getBehaviorJSON();
        
        // 获取发射武器
        WeaponAPI weapon = originalProjectile.getWeapon();
        if (weapon == null) {
            return;
        }
        
        // 初始化配置
        if (!weaponInitialized.getOrDefault(weapon, false)) {
            initializeConfig(weapon, json);
        }

        String hullId = json.optString("droneId", null);
        String weaponId = json.optString("weaponId", null);

        if (hullId != null) {
            // 检查并强制执行数量上限
            enforceMaxDrones(engine, weapon);
            
            // 生成新的浮游炮
            ShipAPI drone = createDrone(explosion, originalProjectile, engine, hullId, weaponId);
            
            if (drone != null) {
                LinkedList<ShipAPI> drones = weaponDrones.computeIfAbsent(weapon, k -> new LinkedList<>());
                drones.add(drone);
            }
        }
    }
    
    /**
     * 初始化配置参数
     */
    private void initializeConfig(WeaponAPI weapon, JSONObject json) {
        try {
            int configMaxDrones = json.optInt("maxDrones", DEFAULT_MAX_DRONES);
            int maxDrones = Math.max(MIN_MAX_DRONES, Math.min(MAX_MAX_DRONES, configMaxDrones));
            
            weaponMaxDrones.put(weapon, maxDrones);
            weaponInitialized.put(weapon, true);
            
            Global.getLogger(this.getClass()).info(
                "HWI Wingman initialized: maxDrones=" + maxDrones
            );
        } catch (Exception e) {
            Global.getLogger(this.getClass()).warn(
                "Failed to initialize HWI Wingman config, using defaults", e
            );
            weaponMaxDrones.put(weapon, DEFAULT_MAX_DRONES);
            weaponInitialized.put(weapon, true);
        }
    }
    
    /**
     * 强制执行数量上限
     */
    private void enforceMaxDrones(CombatEngineAPI engine, WeaponAPI weapon) {
        LinkedList<ShipAPI> drones = weaponDrones.get(weapon);
        if (drones == null) {
            return;
        }
        
        cleanupDeadDrones(weapon);
        
        int maxDrones = weaponMaxDrones.getOrDefault(weapon, DEFAULT_MAX_DRONES);
        
        while (drones.size() >= maxDrones) {
            ShipAPI oldestDrone = drones.pollFirst();
            if (oldestDrone != null && oldestDrone.isAlive() && !oldestDrone.isHulk()) {
                Moci_WingmanSupport.destroyDrone(engine, oldestDrone);
            }
        }
    }
    
    /**
     * 创建浮游炮无人机
     */
    private ShipAPI createDrone(DamagingProjectileAPI explosion, DamagingProjectileAPI originalProjectile,
                                CombatEngineAPI engine, String hullId, String weaponId) {
        try {
            ShipAPI sourceShip = originalProjectile.getSource();
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
            drone.setOwner(originalProjectile.getOwner());
            
            if (sourceShip != null && sourceShip.isAlly()) {
                drone.setAlly(true);
            }
            
            drone.setDrone(true);
            Moci_WingmanSupport.linkDroneToMothership(drone, mothership);
            
            if (sourceShip != null && sourceShip.getMutableStats() != null) {
                drone.getMutableStats().getEnergyWeaponDamageMult()
                        .applyMods(sourceShip.getMutableStats().getEnergyWeaponDamageMult());
                drone.getMutableStats().getMissileWeaponDamageMult()
                        .applyMods(sourceShip.getMutableStats().getMissileWeaponDamageMult());
                drone.getMutableStats().getBallisticWeaponDamageMult()
                        .applyMods(sourceShip.getMutableStats().getBallisticWeaponDamageMult());
            }
            
            drone.setCollisionClass(CollisionClass.FIGHTER);
            drone.setFacing(originalProjectile.getFacing());
            
            engine.addEntity(drone);
            drone.getLocation().set(explosion.getLocation());

            drone.setHullSize(ShipAPI.HullSize.FRIGATE);
            ShipAIConfig config = new ShipAIConfig();
            config.alwaysStrafeOffensively = true;
            drone.setShipAI(Global.getSettings().createDefaultShipAI(drone, config));
            drone.setHullSize(ShipAPI.HullSize.FIGHTER);
            
            return drone;
            
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error(
                "Failed to create drone: " + hullId, e
            );
            return null;
        }
    }
}
