package data.scripts.weapons;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 光剑武器基础效果插件
 * 处理光剑的基础功能：
 * 1. 无射程限制（配合距离衰减系统）
 * 2. 斩切敌方弹丸（需要武器有MOCI_BLADE_CUT标签）
 * 3. 直接伤害导弹（使用真正的Grid遍历优化性能）
 * 4. 统一的距离衰减管理系统
 * 
 * 注意：射程限制已被完全移除，伤害衰减统一在此类中管理
 * 其他BeamEffect插件应调用calculateDistanceDamageMultiplier方法
 * 
 * 衰减参数配置：
 * - DAMAGE_FALLOFF_RANGE_MULTIPLIER: 衰减范围倍数
 * - MINIMUM_DAMAGE_RATIO: 保底伤害比例
 */
public class Moci_BladeEffectPlugin implements EveryFrameWeaponEffectPlugin {
    protected WeaponAPI weapon; // 武器实例
    protected BeamAPI beam = null;
    
    // 斩切弹丸相关参数
    protected final float WIDTH_MULTIPLIER = 1.0f; // 斩切宽度倍率（相对于光束宽度）
    protected final IntervalUtil checkInterval = new IntervalUtil(0.1f, 0.15f); // 检查间隔
    
    // ========== 统一距离衰减系统配置 ==========
    /** 衰减开始的射程倍数（超过基础射程的多少倍开始衰减到保底伤害） */
    public static final float DAMAGE_FALLOFF_RANGE_MULTIPLIER = 3.0f;
    
    /** 保底伤害比例（0.0 = 完全衰减到0, 0.3 = 保留30%伤害） */
    public static final float MINIMUM_DAMAGE_RATIO = 0.3f;
    
    // 是否能够斩切弹丸的标签
    protected final String CUT_TAG = "MOCI_BLADE_CUT";
    
    // 光剑武器标签（用于直接伤害导弹）
    protected final String BLADE_TAG = "MOCI_BLADE";

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        this.weapon = weapon;
        if (engine == null || engine.isPaused()) return;
        
        // 处理光束最大长度和斩切弹丸
        for (BeamAPI currentBeam : weapon.getBeams()) {
            if (currentBeam != null) {
                // 保存当前光束引用供后续使用
                this.beam = currentBeam;
                
                // 检查是否为激活的光剑
                if (weapon.getChargeLevel() > 0) {
                    checkInterval.advance(amount);
                    if (checkInterval.intervalElapsed()) {
                        // 从光束获取宽度，并应用倍率
                        float deflectionWidth = currentBeam.getWidth() * WIDTH_MULTIPLIER;
                        
                        // 光剑直接伤害导弹处理（使用Grid遍历）
                        if (isBladeWeapon()) {
                            damageProjectilesDirectlyWithGrid(engine, currentBeam, deflectionWidth);
                        }
                        else if (canCutProjectiles()) {
                            deflectProjectiles(engine, currentBeam.getFrom(), currentBeam.getTo(), deflectionWidth);
                        }
                        
                    }
                }
            }
        }
    }
    
    /**
     * 检查武器是否具有斩切弹丸的能力
     */
    protected boolean canCutProjectiles() {
        if (weapon == null || weapon.getSpec() == null) return false;
        return weapon.getSpec().hasTag(CUT_TAG);
    }
    
    /**
     * 检查是否为光剑武器
     */
    protected boolean isBladeWeapon() {
        if (weapon == null || weapon.getSpec() == null) return false;
        return weapon.getSpec().hasTag(BLADE_TAG);
    }
    
    /**
     * 统一的光剑距离衰减计算方法
     * 供所有光剑BeamEffect插件调用，确保衰减逻辑一致
     * 
     * @param distance 实际攻击距离
     * @param baseRange 武器的基础射程
     * @return 伤害倍数 (MINIMUM_DAMAGE_RATIO 到 1.0)
     */
    public static float calculateDistanceDamageMultiplier(float distance, float baseRange) {
        if (distance <= baseRange) {
            // 在基础射程内，伤害不衰减
            return 1.0f;
        } else if (distance <= baseRange * DAMAGE_FALLOFF_RANGE_MULTIPLIER) {
            // 在基础射程到衰减终点之间，线性衰减到保底伤害
            float excessDistance = distance - baseRange;
            float maxExcessDistance = baseRange * (DAMAGE_FALLOFF_RANGE_MULTIPLIER - 1.0f);
            float invMaxExcessDistance = 1.0f / maxExcessDistance; // 预计算倒数
            float decayRatio = excessDistance * invMaxExcessDistance;
            // 从1.0线性衰减到MINIMUM_DAMAGE_RATIO
            return 1.0f - decayRatio * (1.0f - MINIMUM_DAMAGE_RATIO);
        } else {
            // 超过衰减范围，使用保底伤害
            return MINIMUM_DAMAGE_RATIO;
        }
    }

    /**
     * 检测并斩切光剑路径上的敌方弹丸（使用Grid遍历优化）
     */
    protected void deflectProjectiles(CombatEngineAPI engine, Vector2f bladeStart, Vector2f bladeEnd, float deflectionWidth) {
        ShipAPI ship = weapon.getShip();
        if (ship == null) return;
        
        // 计算光剑的方向向量
        Vector2f bladeVector = Vector2f.sub(bladeEnd, bladeStart, null);
        float bladeLength = bladeVector.length();
        
        // 预先计算检查范围
        float checkRadius = bladeLength + deflectionWidth + 100f; // 保守估算，覆盖整个光剑长度范围
        Vector2f bladeCenter = new Vector2f((bladeStart.x + bladeEnd.x)*0.5f, (bladeStart.y + bladeEnd.y)*0.5f);
        
        // ========== 使用Grid遍历优化 ==========
        // 使用全对象网格进行高效检索，可以检测到所有类型的弹丸
        Iterator<Object> objIter = engine.getAllObjectGrid().getCheckIterator(
            bladeCenter, 
            checkRadius * 2f,  // 宽度 
            checkRadius * 2f   // 高度
        );
        
        List<DamagingProjectileAPI> projectilesToRemove = new ArrayList<>();
        
        // 第一阶段：Grid遍历 + 粗筛选
        while (objIter.hasNext()) {
            Object obj = objIter.next();
            if (!(obj instanceof DamagingProjectileAPI)) continue;
            
            DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;
            
            // 基础筛选：敌方、存活的弹丸
            if (proj.getOwner() == ship.getOwner()) continue;
            if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;
            
            // 粗距离检查：快速排除明显超出范围的弹丸（使用平方距离避免开方）
            float roughDistSq = MathUtils.getDistanceSquared(bladeCenter, proj.getLocation());
            float checkRadiusSq = checkRadius * checkRadius;
            if (roughDistSq > checkRadiusSq) continue;
            
            // 第二阶段：精确距离计算
            // 计算弹丸到光剑线段的最短距离
            Vector2f v = Vector2f.sub(bladeEnd, bladeStart, null);
            Vector2f w = Vector2f.sub(proj.getLocation(), bladeStart, null);
            float c1 = Vector2f.dot(w, v);
            float distance;
            if (c1 <= 0) {
                // 投影点在起点之前，使用起点距离
                distance = MathUtils.getDistance(proj.getLocation(), bladeStart);
            } else {
                float c2 = Vector2f.dot(v, v);
                if (c2 <= c1) {
                    // 投影点在终点之后，使用终点距离
                    distance = MathUtils.getDistance(proj.getLocation(), bladeEnd);
                } else {
                    // 投影点在线段上，计算垂直距离
                    float invC2 = 1.0f / c2; // 预计算倒数
                    float b = c1 * invC2;
                    Vector2f pb = new Vector2f(bladeStart.x + b * v.x, bladeStart.y + b * v.y);
                    distance = MathUtils.getDistance(proj.getLocation(), pb);
                }
            }
            
            // 如果距离小于斩切宽度，则斩切该弹丸
            if (distance <= deflectionWidth + proj.getCollisionRadius()) {
                projectilesToRemove.add(proj);
            }
        }
        
        // 移除被斩切的弹丸
        for (DamagingProjectileAPI proj : projectilesToRemove) {
            engine.removeEntity(proj);
        }
    }
    
    /**
     * 计算投影点（弹丸在光剑上的最近点）
     */
    protected Vector2f getProjectionPoint(Vector2f bladeStart, Vector2f bladeVector, Vector2f projectileLocation) {
        Vector2f projToStart = Vector2f.sub(projectileLocation, bladeStart, null);
        float invLengthSquared = 1.0f / bladeVector.lengthSquared(); // 预计算倒数
        float dot = Vector2f.dot(projToStart, bladeVector) * invLengthSquared;
        dot = Math.max(0, Math.min(1, dot)); // 限制在光剑线段内
        
        Vector2f projectionPoint = new Vector2f(bladeVector);
        projectionPoint.scale(dot);
        Vector2f.add(bladeStart, projectionPoint, projectionPoint);
        
        return projectionPoint;
    }
    
    /**
     * 直接对导弹造成伤害（使用Grid遍历优化性能）
     * 解决光束伤害间隔问题，确保光剑能够有效对抗导弹
     */
    protected void damageProjectilesDirectlyWithGrid(CombatEngineAPI engine, BeamAPI beam, float damageWidth) {
        if (weapon == null || weapon.getShip() == null) return;
        
        ShipAPI ship = weapon.getShip();
        Vector2f bladeStart = beam.getFrom();
        Vector2f bladeEnd = beam.getTo();
        
        // 计算光剑的方向向量和长度
        Vector2f bladeVector = Vector2f.sub(bladeEnd, bladeStart, null);
        float bladeLength = bladeVector.length();
        
        // 预先计算检查范围
        float checkRadius = bladeLength + damageWidth + 100f; // 保守估算，覆盖整个光剑长度范围
        Vector2f bladeCenter = new Vector2f((bladeStart.x + bladeEnd.x)*0.5f, (bladeStart.y + bladeEnd.y)*0.5f);
        
        // ========== 使用真正的Grid遍历 ==========
        // 使用导弹专用网格进行高效检索
        Iterator<Object> missileIter = engine.getMissileGrid().getCheckIterator(
            bladeCenter, 
            checkRadius * 1.5f,  // 宽度 
            checkRadius * 3f   // 高度
        );
        
        List<MissileAPI> validTargets = new ArrayList<>();
        
        // 第一阶段：Grid遍历 + 粗筛选
        while (missileIter.hasNext()) {
            Object obj = missileIter.next();
            if (!(obj instanceof MissileAPI)) continue;
            
            MissileAPI missile = (MissileAPI) obj;
            
            // 基础筛选：敌方、存活的导弹
            if (missile.getOwner() == ship.getOwner()) continue;
            if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;
            // if (missile.isFlare()) continue; // 排除干扰弹
            
            // 粗距离检查：快速排除明显超出范围的导弹（使用平方距离避免开方）
            float roughDistSq = MathUtils.getDistanceSquared(bladeCenter, missile.getLocation());
            float checkRadiusSq = checkRadius * checkRadius;
            if (roughDistSq > checkRadiusSq) continue;
            
            validTargets.add(missile);
        }
        
        // 第二阶段：精确距离计算和伤害应用
        for (MissileAPI missile : validTargets) {
            // 计算导弹到光剑线段的精确最短距离
            float distance = calculateDistanceToBeam(bladeStart, bladeEnd, missile.getLocation());
            
            // 如果距离在伤害范围内，则造成伤害
            if (distance <= damageWidth + missile.getCollisionRadius()) {
                // 计算伤害值（包含对导弹伤害加成）
                float damage = calculateBladeDamageWithMissileBonus(beam, missile);
                
                // 对导弹不应用距离衰减（参考原版逻辑）
                if (damage > 0) {
                    engine.applyDamage(missile, missile.getLocation(), damage, 
                        weapon.getDamageType(), damage, false, false, ship);
                }
            }
        }
    }
    
    /**
     * 计算点到光束线段的最短距离
     */
    protected float calculateDistanceToBeam(Vector2f beamStart, Vector2f beamEnd, Vector2f point) {
        Vector2f v = Vector2f.sub(beamEnd, beamStart, null);
        Vector2f w = Vector2f.sub(point, beamStart, null);
        float c1 = Vector2f.dot(w, v);
        
        if (c1 <= 0) {
            // 投影点在起点之前，使用起点距离
            return MathUtils.getDistance(point, beamStart);
        } else {
            float c2 = Vector2f.dot(v, v);
            if (c2 <= c1) {
                // 投影点在终点之后，使用终点距离
                return MathUtils.getDistance(point, beamEnd);
            } else {
                // 投影点在线段上，计算垂直距离
                float invC2 = 1.0f / c2; // 预计算倒数
                float b = c1 * invC2;
                Vector2f pb = new Vector2f(beamStart.x + b * v.x, beamStart.y + b * v.y);
                return MathUtils.getDistance(point, pb);
            }
        }
    }
    
    /**
     * 计算光剑对导弹的伤害值
     */
    protected float calculateBladeDamageWithMissileBonus(BeamAPI beam, DamagingProjectileAPI target) {
        if (weapon == null || weapon.getShip() == null) return 0f;
        
        // 参考原版逻辑计算基础伤害
        float baseDamage = weapon.getDamage().getDamage();
        float burstDuration = weapon.getSpec().getBurstDuration();
        float energyMult = weapon.getShip().getMutableStats().getEnergyWeaponDamageMult().computeMultMod();
        float beamMult = weapon.getShip().getMutableStats().getBeamWeaponDamageMult().getPercentMod() * 0.01f;
        
        // 对导弹伤害加成
        float missileDamageMult = weapon.getShip().getMutableStats().getMissileWeaponDamageMult().computeMultMod();
        
        // 计算增强伤害
        float enhancedDamage = Math.max(
            baseDamage,
            baseDamage * burstDuration * (energyMult + beamMult)
        );
        
        // 应用对导弹伤害加成
        enhancedDamage *= missileDamageMult;
        
        return enhancedDamage;
    }
    
} 