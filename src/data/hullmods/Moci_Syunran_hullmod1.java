package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier;
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import data.scripts.util.Moci_TextLoader;

/**
 * 战术炮塔增强系统
 * 效果：
 * 1. 战术系统启动期间，炮塔旋转速度+50%
 * 2. 战术系统启动期间，弹丸速度+50%
 * 3. 近距离伤害加成：1000距离以上0%，500距离100%加成
 * 
 * 使用状态机管理战术系统的激活状态
 */
public class Moci_Syunran_hullmod1 extends BaseHullMod {
    private static final String TEXT_ID = "Moci_Syunran_hullmod1";

    // 旋转速度加成
    private static final float TURRET_ROTATION_BONUS = 0.5f;
    
    // 弹丸速度加成
    private static final float PROJECTILE_SPEED_BONUS = 0.5f;
    
    // 距离伤害加成参数
    private static final float MAX_DISTANCE = 1000f;  // 最大距离，超过此距离无加成
    private static final float MIN_DISTANCE = 500f;   // 最小距离，此距离达到最大加成
    private static final float MAX_DAMAGE_BONUS = 1.0f; // 最大伤害加成100%
    
    // 状态机相关
    private static final String MODIFIER_ID = "moci_Syunran_hullmod1";
    private static final String STATUS_KEY = "moci_Syunran_hullmod1_status";

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 添加伤害修改监听器
        if (!ship.hasListenerOfClass(TacticalTurretBoostDamageListener.class)) {
            ship.addListener(new TacticalTurretBoostDamageListener(ship));
        }
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null || ship.getSystem() == null) {
            return;
        }
        
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return;
        }
        
        // 检查战术系统是否激活
        boolean systemActive = ship.getSystem().isActive();
        MutableShipStatsAPI stats = ship.getMutableStats();
        
        if (systemActive) {
            // 战术系统激活：应用炮塔旋转速度加成和弹丸速度加成
            stats.getWeaponTurnRateBonus().modifyMult(MODIFIER_ID, 1f + TURRET_ROTATION_BONUS);
            stats.getProjectileSpeedMult().modifyMult(MODIFIER_ID, 1f + PROJECTILE_SPEED_BONUS);
            
            // 如果是玩家舰船，显示状态
            if (ship == engine.getPlayerShip()) {
                engine.maintainStatusForPlayerShip(
                    STATUS_KEY,
                    "graphics/icons/hullsys/targeting_feed.png",
                    Moci_TextLoader.getText(TEXT_ID, "status.title"),
                    Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.active",
                        Moci_TextLoader.mapOf(
                            "turret_rotation_bonus", String.valueOf((int)(TURRET_ROTATION_BONUS * 100f)),
                            "projectile_speed_bonus", String.valueOf((int)(PROJECTILE_SPEED_BONUS * 100f)))),
                    false
                );
            }
        } else {
            // 战术系统未激活：移除加成
            stats.getWeaponTurnRateBonus().unmodify(MODIFIER_ID);
            stats.getProjectileSpeedMult().unmodify(MODIFIER_ID);
        }
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) return "" + (int)(TURRET_ROTATION_BONUS * 100f) + "%";
        if (index == 1) return "" + (int)(PROJECTILE_SPEED_BONUS * 100f) + "%";
        if (index == 2) return "" + (int)MAX_DISTANCE;
        if (index == 3) return "" + (int)(MAX_DAMAGE_BONUS * 100f) + "%";
        if (index == 4) return "" + (int)MIN_DISTANCE;
        return null;
    }

    /**
     * 伤害修改监听器，处理近距离伤害加成
     * 独立于状态机，始终监听伤害事件
     */
    private static class TacticalTurretBoostDamageListener implements DamageDealtModifier {
        
        private ShipAPI ship;
        
        public TacticalTurretBoostDamageListener(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public String modifyDamageDealt(Object param, 
                                       CombatEntityAPI target, 
                                       DamageAPI damage,
                                       Vector2f point, 
                                       boolean shieldHit) {
            
            if (ship == null || !ship.isAlive() || target == null || point == null) {
                return null;
            }
            
            // 过滤特效伤害
            if (param == null) {
                return null;
            }
            
            // 获取攻击者船体边界上距离命中点最近的点（局部坐标）
            Vector2f nearestPointOnShipLocal = CollisionUtils.getNearestPointOnBounds(point, ship);
            
            float distance;
            if (nearestPointOnShipLocal != null) {
                // 将攻击者局部坐标转换为世界坐标
                float attackerAngle = ship.getFacing();
                float attackerCos = (float) Math.cos(Math.toRadians(attackerAngle));
                float attackerSin = (float) Math.sin(Math.toRadians(attackerAngle));
                float attackerRotatedX = nearestPointOnShipLocal.x * attackerCos - nearestPointOnShipLocal.y * attackerSin;
                float attackerRotatedY = nearestPointOnShipLocal.x * attackerSin + nearestPointOnShipLocal.y * attackerCos;
                
                Vector2f nearestPointWorld = new Vector2f(
                    ship.getLocation().x + attackerRotatedX,
                    ship.getLocation().y + attackerRotatedY
                );
                
                // 将命中点局部坐标转换为世界坐标
                Vector2f pointWorld = point;
                if (target instanceof ShipAPI) {
                    ShipAPI targetShip = (ShipAPI) target;
                    float targetAngle = targetShip.getFacing();
                    float targetCos = (float) Math.cos(Math.toRadians(targetAngle));
                    float targetSin = (float) Math.sin(Math.toRadians(targetAngle));
                    float targetRotatedX = point.x * targetCos - point.y * targetSin;
                    float targetRotatedY = point.x * targetSin + point.y * targetCos;
                    
                    pointWorld = new Vector2f(
                        targetShip.getLocation().x + targetRotatedX,
                        targetShip.getLocation().y + targetRotatedY
                    );
                }
                
                distance = MathUtils.getDistance(nearestPointWorld, pointWorld);
            } else {
                distance = MathUtils.getDistance(ship.getLocation(), point) - ship.getCollisionRadius();
            }
            distance = Math.max(0f, distance);
            
            // 计算伤害加成
            float damageBonus = calculateDamageBonus(distance);
            
            // 记录原始伤害
            float originalDamage = damage.getDamage();
            
            if (damageBonus > 0f) {
                damage.getModifier().modifyMult(MODIFIER_ID, 1f + damageBonus);

                // 调试日志
                /*
                float finalDamage;
                if (damage.isDps()) {
                    finalDamage = damage.computeDamageDealt(1f);
                } else {
                    finalDamage = damage.computeDamageDealt(0f);
                }
                
                float addedDamage = originalDamage * damageBonus;
                Vector2f attackerPos = ship.getLocation();
                Vector2f targetPos = target.getLocation();
                float centerDistance = MathUtils.getDistance(attackerPos, targetPos);
                Global.getLogger(Moci_Syunran_hullmod1.class).info(
                    String.format("[赴汤蹈火] 伤害计算 | 攻击者: %s (%.1f, %.1f) | 目标: %s (%.1f, %.1f) | 中心距离: %.1f | 击中护盾: %s | " +
                                "距离: %.1f | 原始伤害: %.1f | 加成倍率: %.2f%% | 增加伤害: +%.1f | 最终伤害: %.1f | " +
                                "计算公式: 距离 %.1f <= %.1f ? 100%% : (%.1f - %.1f) / (%.1f - %.1f) * 100%% = %.2f%%",
                        ship.getName(),
                        attackerPos.x,
                        attackerPos.y,
                        target instanceof ShipAPI ? ((ShipAPI)target).getName() : target.getClass().getSimpleName(),
                        targetPos.x,
                        targetPos.y,
                        centerDistance,
                        shieldHit,
                        distance,
                        originalDamage,
                        damageBonus * 100f,
                        addedDamage,
                        finalDamage,
                        distance,
                        MIN_DISTANCE,
                        MAX_DISTANCE,
                        distance,
                        MAX_DISTANCE,
                        MIN_DISTANCE,
                        damageBonus * 100f
                    )
                );*/
            }
            
            return null;
        }

        /**
         * 计算基于距离的伤害加成
         * @param distance 距离
         * @return 伤害加成倍率（0-1.0）
         */
        private float calculateDamageBonus(float distance) {
            if (distance >= MAX_DISTANCE) {
                // 超过最大距离，无加成
                return 0f;
            } else if (distance <= MIN_DISTANCE) {
                // 小于最小距离，最大加成
                return MAX_DAMAGE_BONUS;
            } else {
                // 线性插值
                float ratio = (MAX_DISTANCE - distance) / (MAX_DISTANCE - MIN_DISTANCE);
                return MAX_DAMAGE_BONUS * ratio;
            }
        }
    }
}
