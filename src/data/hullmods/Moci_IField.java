package data.hullmods;

import java.awt.Color;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * I力场发生器 - "IField"船体插件
 * 功能：在低幅能时主动推开来袭光束，以幅能换取防护
 * 特性：前方180度防护弧，幅能过高时系统过载失效
 */
public class Moci_IField extends BaseHullMod {
    private static final String TEXT_ID = "Moci_IField";
    
    // 每1点光束伤害产生的幅能消耗
    private static final float FLUX_PER_DAMAGE = 0.5f;
    
    // 防护范围（碰撞半径的倍数）
    private static final float EFFECT_RADIUS_MULT = 3f;
    
    // I力场生效的幅能阈值（低于此值才生效）
    private static final float FLUX_THRESHOLD = 0.9f;
    
    // 光束推开距离（像素）
    private static final float BEAM_PUSH_DISTANCE = 25f;
    
    // 防护角度范围（舰船朝向前方180度）
    private static final float PROTECTION_ARC = 180f;
    
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 注册I力场防护监听器
        ship.addListener(new IFieldScript(ship));
    }
    
    // I力场防护监听器，负责光束推开和幅能消耗
    public static class IFieldScript implements AdvanceableListener, DamageTakenModifier {
        
        private final ShipAPI ship;
        private final float effectRadius;
        private final float fluxThreshold;
        private boolean isSystemBreakdown = false;
        private float repairProgress = 0f;
        
        public IFieldScript(ShipAPI ship) {
            this.ship = ship;
            this.effectRadius = ship.getCollisionRadius() * EFFECT_RADIUS_MULT;
            this.fluxThreshold = FLUX_THRESHOLD;
        }
        
        @Override
        public void advance(float amount) {
            // 安全检查：确保舰船和关键组件不为null
            if (ship == null || ship.getLocation() == null || ship.getFluxTracker() == null) {
                return;
            }
            
            // 死亡时移除监听器
            if (!ship.isAlive() || ship.isHulk()) {
                ship.removeListener(this);
                return;
            }
            
            // 处理系统状态（过载检测和修复）
            handleSystemStatus(amount);
            
            // 只有在系统正常时才处理光束防护
            if (isSystemActive()) {
                processBeamProtection(amount);
                renderIFieldEffect();
            }
        }
        
        // 处理系统状态：过载检测和自动修复
        private void handleSystemStatus(float amount) {
            // 幅能过高时系统过载
            if (ship.getFluxLevel() > fluxThreshold) {
                if (!isSystemBreakdown) {
                    isSystemBreakdown = true;
                    repairProgress = 0f;
                    // 播放系统过载音效
                    if (ship.getLocation() != null) {
                        try {
                            Global.getSoundPlayer().playSound("shield_burnout", 1f, 1f, 
                                ship.getLocation(), ship.getVelocity());
                        } catch (Exception e) {
                            // 音效播放失败时静默处理
                        }
                    }
                }
                return;
            }
            
            // 超载/通风时系统失效
            if (ship.getFluxTracker().isOverloadedOrVenting()) {
                if (!isSystemBreakdown) {
                    isSystemBreakdown = true;
                    repairProgress = 0f;
                }
                return;
            }
            
            // 系统修复逻辑
            if (isSystemBreakdown) {
                repairProgress += 0.5f * amount * ship.getMutableStats().getTimeMult().getMult();
                if (repairProgress >= 1f) {
                    isSystemBreakdown = false;
                    repairProgress = 0f;
                    // 播放系统恢复音效
                    if (ship.getLocation() != null) {
                        try {
                            Global.getSoundPlayer().playSound("shield_raise", 0.8f, 1.2f, 
                                ship.getLocation(), ship.getVelocity());
                        } catch (Exception e) {
                            // 音效播放失败时静默处理
                        }
                    }
                }
            }
        }
        
        // 判断系统是否处于活跃状态
        private boolean isSystemActive() {
            try {
                return ship != null && 
                       ship.getFluxTracker() != null &&
                       !ship.isPhased() && 
                       !ship.getFluxTracker().isOverloadedOrVenting() && 
                       ship.getFluxLevel() <= fluxThreshold && 
                       !isSystemBreakdown;
            } catch (Exception e) {
                return false;
            }
        }
        
        // 处理光束防护：检测、筛选和推开来袭光束
        private void processBeamProtection(float amount) {
            CombatEngineAPI engine = Global.getCombatEngine();
            float shipTimeFactor = 1f / ship.getMutableStats().getTimeMult().getMult();
            
            // 遍历所有光束进行防护处理
            for (BeamAPI beam : engine.getBeams()) {
                // 基础筛选：跳过己方光束和无效光束
                if (beam.getSource() != null && beam.getSource().getOwner() == ship.getOwner()) continue;
                if (beam.getBrightness() <= 0.1f) continue;
                
                // 距离筛选：检查光束是否在防护范围内
                float distToBeam = MathUtils.getDistance(beam.getTo(), ship.getLocation());
                if (distToBeam > effectRadius + ship.getCollisionRadius()) continue;
                
                // 碰撞检测：计算光束与舰船的碰撞点
                Vector2f collisionPoint = calculateBeamCollisionPoint(beam);
                if (collisionPoint == null) continue;
                
                // 角度筛选：检查敌人是否在舰船前方防护弧内
                float enemyToShipAngle = Misc.getAngleInDegrees(ship.getLocation(), beam.getFrom());
                float shipFacing = ship.getFacing();
                float angleDiff = Misc.getAngleDiff(shipFacing, enemyToShipAngle);
                if (Math.abs(angleDiff) > PROTECTION_ARC * 0.5f) continue;
                
                // 推开光束并消耗幅能
                pushBeamAway(beam, collisionPoint, amount, shipTimeFactor);
            }
        }
        
        // 计算光束与舰船的碰撞点
        private Vector2f calculateBeamCollisionPoint(BeamAPI beam) {
            float angle = Misc.getAngleInDegrees(beam.getFrom(), beam.getTo());
            float radian = (float) Math.toRadians(angle);
            
            // 延长光束线段
            Vector2f extendedEnd = new Vector2f(
                beam.getFrom().x + 10000f * (float) Math.cos(radian),
                beam.getFrom().y + 10000f * (float) Math.sin(radian)
            );
            
            // 检查与舰船碰撞边界的交点
            return getLineShipIntersection(beam.getFrom(), extendedEnd, ship);
        }
        
        // 线段与舰船碰撞检测
        private Vector2f getLineShipIntersection(Vector2f start, Vector2f end, ShipAPI ship) {
            Vector2f shipPos = ship.getLocation();
            float shipRadius = ship.getCollisionRadius();
            
            // 计算点到线段的最近点
            Vector2f lineDir = Vector2f.sub(end, start, null);
            Vector2f toShip = Vector2f.sub(shipPos, start, null);
            
            float lineLengthSq = lineDir.lengthSquared();
            if (lineLengthSq == 0) return null;
            
            float t = Vector2f.dot(toShip, lineDir) / lineLengthSq;
            t = Math.max(0, Math.min(1, t));
            
            Vector2f closestPoint = new Vector2f(
                start.x + t * lineDir.x,
                start.y + t * lineDir.y
            );
            
            // 检查是否在舰船半径内
            float distToShip = MathUtils.getDistance(closestPoint, shipPos);
            return distToShip <= shipRadius ? closestPoint : null;
        }
        
        // 推开光束并消耗幅能
        private void pushBeamAway(BeamAPI beam, Vector2f collisionPoint, float amount, float timeFactor) {
            // 计算光束伤害并消耗幅能
            float damage = beam.getDamage().computeDamageDealt(amount);
            float fluxCost = FLUX_PER_DAMAGE * damage * timeFactor;
            ship.getFluxTracker().increaseFlux(fluxCost, false);
            
            // 计算推开方向（从舰船指向碰撞点）
            float pushAngle = Misc.getAngleInDegrees(ship.getLocation(), collisionPoint);
            Vector2f pushDirection = Misc.getUnitVectorAtDegreeAngle(pushAngle);
            
            // 记录推开前的光束长度
            float originalLength = MathUtils.getDistance(beam.getFrom(), beam.getTo());
            
            // 方法1：直接移动光束终点
            beam.getTo().x += pushDirection.x * BEAM_PUSH_DISTANCE;
            beam.getTo().y += pushDirection.y * BEAM_PUSH_DISTANCE;
            
            // 方法2：缩短光束长度作为备用
            float newLength = Math.max(10f, originalLength - BEAM_PUSH_DISTANCE);
            if (newLength < originalLength) {
                float ratio = newLength / originalLength;
                Vector2f beamDir = Vector2f.sub(beam.getTo(), beam.getFrom(), null);
                beamDir.scale(ratio);
                beam.getTo().set(beam.getFrom().x + beamDir.x, beam.getFrom().y + beamDir.y);
            }
        }
        
        // 显示I力场视觉效果
        private void renderIFieldEffect() {
            if (!isSystemActive()) return;
            
            // 舰船底部蓝色发光抖动特效
            Color glowColor = new Color(100, 150, 255);
            // ship.setJitterUnder("moci_ifield", glowColor, 1f, 15, 0, 8f);
        }
        
        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, 
                                       Vector2f point, boolean shieldHit) {
            // 只有在系统正常且是光束伤害时才提供额外防护
            if (!isSystemActive()) return null;
            if (!(param instanceof BeamAPI)) return null;
            
            // 轻微减少光束伤害
            damage.getModifier().modifyMult("moci_ifield", 0.9f);
            return "moci_ifield";
        }
    }
    
    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        switch (index) {
            case 0: return Moci_TextLoader.getText(TEXT_ID, "description.param_protection_arc");
            case 1: return (int)(FLUX_THRESHOLD * 100) + "%";
            case 2: return Moci_TextLoader.getText(TEXT_ID, "description.param_damage_reduction");
            case 3: return Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.param_push_distance",
                    Moci_TextLoader.mapOf("distance", String.valueOf((int) BEAM_PUSH_DISTANCE)));
            default: return null;
        }
    }
    
    @Override
    public Color getBorderColor() {
        return new Color(100, 150, 255);
    }
    
    @Override
    public Color getNameColor() {
        return new Color(120, 170, 255);
    }
    
    @Override
    public int getDisplaySortOrder() {
        return -1500;
    }
} 
