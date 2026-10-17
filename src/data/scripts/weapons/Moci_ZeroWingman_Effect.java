package data.scripts.weapons;

import java.awt.Color;
import java.util.Iterator;
import java.util.LinkedList;

import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;

import data.scripts.util.Moci_ColorData;

/**
 * Zero 浮游炮效果类（动态武器配置版）
 * 
 * 功能说明：
 * 1. 生成携带动态武器的浮游炮无人机
 * 2. 管理每个武器的浮游炮数量上限
 * 3. 超出上限时自动摧毁最早的浮游炮
 * 4. 提供玩家UI状态显示
 * 5. 支持智能AI管理
 * 
 * 使用方法：
 * 在武器的proj文件中配置：
 * "behaviorJSON":{
 * "droneId":"你的无人机hull_id",
 * "maxDrones":4
 * }
 * 
 * 系统会自动查找与该武器坐标完全相同的其他武器，并将其作为无人机的武器配置
 */
public class Moci_ZeroWingman_Effect implements OnFireEffectPlugin, EveryFrameWeaponEffectPlugin {

    // 兜底武器ID
    private static final String FALLBACK_WEAPON_ID = "Moci_Zero_vulcan";
    
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
    private WeaponAPI matchedWeapon1 = null;
    private WeaponAPI matchedWeapon2 = null;

    /**
     * 每帧更新 - 用于隐藏匹配的武器并禁止开火
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

        // 初始化 - 根据槽位名称匹配武器
        if (!initialized) {
            initialized = true;
            String currentSlotId = weapon.getSlot().getId();

            // 根据槽位名称生成目标槽位名称
            String targetSlotId = null;
            if (currentSlotId.startsWith("WINGMAN_")) {
                String suffix = currentSlotId.substring("WINGMAN_".length());
                targetSlotId = "WINGWEAPON_" + suffix;
            }

            // 遍历所有武器，查找目标槽位的武器
            if (targetSlotId != null) {
                for (WeaponAPI w : ship.getAllWeapons()) {
                    String slotId = w.getSlot().getId();

                    // 找到目标槽位
                    if (targetSlotId.equals(slotId)) {
                        matchedWeapon1 = w;
                        
                        // 安全地处理武器移除
                        // 检查是否可以安全移除（避免导致武器组为空）
                        if (canSafelyRemoveWeapon(ship, w)) {
                            // 将匹配的武器从武器组中移除
                            ship.removeWeaponFromGroups(w);
                        }
                        
                        // 永久禁用武器（视觉上显示为损坏状态）
                        w.disable(true);
                        break;
                    }
                }
            }
        }

        // 每帧隐藏匹配的武器并禁止开火
        if (matchedWeapon1 != null) {
            hideAndDisableWeapon(matchedWeapon1);
        }
        if (matchedWeapon2 != null) {
            hideAndDisableWeapon(matchedWeapon2);
        }
        
        // 清理已死亡的浮游炮
        cleanupDeadDrones();
        
        // 显示玩家UI状态
        updatePlayerUI(engine, weapon, ship);
    }
    
    /**
     * 检查是否可以安全地从武器组中移除武器
     * 
     * 安全条件：
     * - 武器不在第一个武器组（索引0）
     * 
     * 原因：第一个武器组如果变空会导致游戏引擎访问空列表时崩溃
     * 即使组内有多个武器，如果它们都使用这个效果，会被逐个移除最终导致组为空
     * 
     * @param ship 舰船
     * @param weapon 要移除的武器
     * @return 如果可以安全移除返回true，否则返回false
     */
    private boolean canSafelyRemoveWeapon(ShipAPI ship, WeaponAPI weapon) {
        try {
            var weaponGroups = ship.getWeaponGroupsCopy();
            if (weaponGroups == null || weaponGroups.isEmpty()) {
                return false;
            }
            
            // 遍历所有武器组，找到包含该武器的组
            for (int groupIndex = 0; groupIndex < weaponGroups.size(); groupIndex++) {
                var group = weaponGroups.get(groupIndex);
                var weaponsInGroup = group.getWeaponsCopy();
                
                if (weaponsInGroup != null && weaponsInGroup.contains(weapon)) {
                    // 如果是第一个武器组（索引0），不能移除
                    if (groupIndex == 0) {
                        Global.getLogger(this.getClass()).info(
                            "Cannot remove weapon from group 0 (safety check): " + weapon.getId()
                        );
                        return false;
                    }
                    
                    // 其他武器组可以安全移除
                    return true;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error(
                "Error checking if weapon can be safely removed: " + weapon.getId(), e
            );
            // 出错时保守处理，不移除
            return false;
        }
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

    /**
     * 隐藏武器并禁止开火
     */
    private void hideAndDisableWeapon(WeaponAPI weapon) {
        // 禁止开火
        weapon.setForceNoFireOneFrame(true);

        // 移除剩余弹药（对导弹武器）
        if (weapon.usesAmmo() && weapon.getAmmo() > 0) {
            weapon.setAmmo(0);
        }

        // 透明颜色
        Color transparent = Moci_ColorData.NONE;

        // 隐藏所有贴图组件
        if (weapon.getSprite() != null) {
            weapon.getSprite().setColor(transparent);
        }
        if (weapon.getBarrelSpriteAPI() != null) {
            weapon.getBarrelSpriteAPI().setColor(transparent);
        }
        if (weapon.getUnderSpriteAPI() != null) {
            weapon.getUnderSpriteAPI().setColor(transparent);
        }
        if (weapon.getGlowSpriteAPI() != null) {
            weapon.getGlowSpriteAPI().setColor(transparent);
        }

        // 隐藏导弹渲染数据
        if (weapon.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : weapon.getMissileRenderData()) {
                render.getSprite().setWidth(0);
                render.getSprite().setHeight(0);
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

        if (hullId != null) {
            // 检查并强制执行数量上限
            enforceMaxDrones(engine);
            
            // 使用匹配到的武器ID
            String weaponId = null;
            if (matchedWeapon1 != null && matchedWeapon1.getSpec() != null) {
                weaponId = matchedWeapon1.getSpec().getWeaponId();
            } else {
                weaponId = FALLBACK_WEAPON_ID;
            }
            
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
                "Zero Wingman initialized: maxDrones=" + maxDrones
            );
        } catch (Exception e) {
            Global.getLogger(this.getClass()).warn(
                "Failed to initialize Zero Wingman config, using defaults", e
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
            ShipVariantAPI v = Global.getSettings().createEmptyVariant(hullId + "_ZeroDrone", spec);

            WeaponGroupSpec g = new WeaponGroupSpec(WeaponGroupType.LINKED);

            // 两个槽位都安装同一个武器
            v.addWeapon("WS0001", weaponId);
            v.addWeapon("WS0002", weaponId);
            g.addSlot("WS0001");
            g.addSlot("WS0002");
            g.setAutofireOnByDefault(true);
            v.addWeaponGroup(g);

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

            float facing = drone.getFacing();
            float speedAmount = 400f;
            Vector2f velocity = new Vector2f(
                    (float) Math.cos(Math.toRadians(facing)) * speedAmount,
                    (float) Math.sin(Math.toRadians(facing)) * speedAmount);
            drone.getVelocity().set(velocity);

            drone.setHullSize(ShipAPI.HullSize.FRIGATE);
            ShipAIConfig config = new ShipAIConfig();
            config.alwaysStrafeOffensively = true;
            drone.setShipAI(Global.getSettings().createDefaultShipAI(drone, config));
            drone.setHullSize(ShipAPI.HullSize.FIGHTER);

            // 标记为Zero系列无人机，启用智能AI管理
            drone.setCustomData("Moci_Zero_Wingman", true);
            drone.setCustomData("Moci_Zero_OriginalAI", drone.getShipAI());
            drone.setCustomData("Moci_Zero_AIConfig", config);

            // 添加无人机AI管理器
            data.hullmods.Moci_WingmanAIManager aiManager = new data.hullmods.Moci_WingmanAIManager();
            aiManager.setManagedShip(drone);
            drone.addListener(aiManager);
            
            return drone;
            
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error(
                "Failed to create drone: " + hullId, e
            );
            return null;
        }
    }
}
