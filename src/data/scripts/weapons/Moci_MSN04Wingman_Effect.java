package data.scripts.weapons;


/* MSN-04-2 双翼浮游炮武器效果 —— 我方本体代码。
 *
 * 历史：这段逻辑原本就是我们的 Moci_MSN04Wingman_Effect；合并版把它 fork 成
 * xi.arsenal.NightWingmanEffect 并让 Moci_MSN_04_2_wing_left/_right.wpn 改指 fork。
 * 本轮把 fork 完整收编回本类：原逻辑（生成、BasicShipAI、来源链接、数量上限、
 * 死亡清理、双翼交替）保持不变，同时保留合并版新增的创建来源记账、真实落地才返还
 * 1 发弹药、以及更严格的失效无人机清理。
 */

import data.scripts.weapons.Moci_WingmanSupport;
import java.util.*;
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

public final class Moci_MSN04Wingman_Effect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    
    // 默认浮游炮数量上限


    // 合并版在本类之上追加的能力：创建来源记账（Origin）与「真实落地才返还 1 发弹药」。
    // 原逻辑（生成、BasicShipAI、来源链接、数量上限、死亡清理）保持不变；本类即唯一的武器回调。
    public static final String KEY="xi.arsenal.nightLaunch";
    private static final Set<String> IDS=Set.of("Moci_MSN_04_2_wing_left","Moci_MSN_04_2_wing_right");
    private static final class Origin {
        final CombatEngineAPI engine;final ShipAPI drone,mother,launchShip;final WeaponAPI launcher;final int owner;
        boolean returned;
        Origin(CombatEngineAPI engine,ShipAPI drone,ShipAPI mother,WeaponAPI launcher){
            this.engine=engine;this.drone=drone;this.mother=mother;this.launcher=launcher;launchShip=launcher.getShip();owner=drone.getOwner();
        }
    }
    private static final class Counters {
        long created,returned,refunded,rejected;
        final Map<String,Object> facts=new LinkedHashMap<>();
        void publish(){facts.put("schema",1);facts.put("created",created);facts.put("returned",returned);facts.put("refunded",refunded);facts.put("rejected",rejected);}
    }
    private static Counters counters(CombatEngineAPI engine){
        Object found=engine.getCustomData().get(KEY);
        if(found instanceof Counters count)return count;
        if(found!=null)throw new IllegalStateException("Night launch provenance already owned");
        Counters count=new Counters();engine.getCustomData().put(KEY,count);engine.getCustomData().put(KEY+".status",Collections.unmodifiableMap(count.facts));count.publish();return count;
    }
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
        cleanupDeadDrones(engine);

        // 翼浮游炮的自有 AI：谁发射谁负责把 coordinator 装上（不依赖任何船插）。
        try {
            xi.arsenal.NightFunnelHost.ensure(engine);
        } catch (RuntimeException | LinkageError ignored) {
        }

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
    private void cleanupDeadDrones(CombatEngineAPI engine) {
        if (activeDrones.isEmpty()) {
            return;
        }
        
        Iterator<ShipAPI> iterator = activeDrones.iterator();
        while (iterator.hasNext()) {
            ShipAPI drone = iterator.next();
            if (drone == null || !drone.isAlive() || drone.isHulk() || drone.wasRemoved() || !engine.isEntityInPlay(drone)) {
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
                ShipAPI mother=Moci_WingmanSupport.resolveRootMothership(weapon.getShip());
                if(IDS.contains(weapon.getId())&&drone.getOwner()==mother.getOwner()
                        &&Moci_WingmanSupport.getTrackedMothership(drone)==mother&&!drone.getCustomData().containsKey(KEY)){
                    drone.setCustomData(KEY,new Origin(engine,drone,mother,weapon));
                    Counters count=counters(engine);count.created++;count.publish();
                }
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
        cleanupDeadDrones(engine);
        
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
    /** Refund only an actual completed native landing, to the exact still-installed launch weapon, once. */
    public static boolean completeReturn(CombatEngineAPI engine,ShipAPI drone,ShipAPI mother){
        if(engine==null||drone==null||mother==null)return false;
        Object value=drone.getCustomData().get(KEY);
        if(!(value instanceof Origin origin)||origin.engine!=engine||origin.drone!=drone||origin.mother!=mother||origin.returned)return false;
        if(!drone.isFinishedLanding())return false;
        Counters count=counters(engine);
        boolean launcherPresent=origin.launchShip!=null&&origin.launchShip.getAllWeapons().stream().anyMatch(w->w==origin.launcher);
        if(!mother.isAlive()||mother.isHulk()||!engine.isEntityInPlay(mother)||!drone.isAlive()||drone.isHulk()
                ||drone.getOwner()!=origin.owner||mother.getOwner()!=origin.owner||!launcherPresent
                ||origin.launchShip.getOwner()!=origin.owner||!origin.launchShip.isAlive()||origin.launchShip.isHulk()
                ||Moci_WingmanSupport.resolveRootMothership(origin.launchShip)!=mother||!origin.launcher.usesAmmo()){
            origin.returned=true;count.rejected++;count.publish();return false;
        }
        origin.returned=true;count.returned++;
        int old=origin.launcher.getAmmo(),next=Math.min(origin.launcher.getMaxAmmo(),old+1);
        if(next>old){origin.launcher.setAmmo(next);count.refunded+=next-old;}
        count.publish();return true;
    }
}
