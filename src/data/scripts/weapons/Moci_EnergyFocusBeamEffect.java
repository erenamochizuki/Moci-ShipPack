package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.CurveEntity;
import org.boxutil.util.TransformUtil;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 能量聚焦炮 - 光束效果
 * 结合II_WaveMotion的伤害机制和BoxUtil的视觉效果
 * 
 * 特点:
 * 1. 主光束: 使用BoxUtil的CurveEntity渲染
 * 2. 电弧效果: 向周围目标发射能量弧
 * 3. 冲击波: 命中点产生扩散冲击波
 * 4. 区域伤害: 光束周围的范围伤害
 */
public class Moci_EnergyFocusBeamEffect implements BeamEffectPlugin {
    
    // 效果参数
    private static final float AREA_EFFECT_RANGE = 150.0f; // 区域效果范围
    private static final float ARC_CHANCE = 0.3f; // 电弧生成概率
    private static final int MAX_ARC_TARGETS = 3; // 最大电弧目标数
    private static final float BEAM_CORE_WIDTH = 20.0f; // 光束核心宽度
    private static final float BEAM_GLOW_WIDTH = 40.0f; // 光束光晕宽度
    
    // 颜色定义
    private static final Color BEAM_CORE_COLOR = new Color(150, 220, 255, 255);
    private static final Color BEAM_GLOW_COLOR = new Color(100, 180, 255, 150);
    private static final Color ARC_COLOR = new Color(200, 230, 255, 200);
    private static final Color IMPACT_COLOR = new Color(255, 255, 255, 255);
    
    // 状态字段
    private boolean firing = false;
    private boolean impacted = false;
    private float level = 1.0f;
    private float sinceLast = 0.0f;
    private float sinceLastImpact = 0.0f;
    
    // 时间间隔控制
    private final IntervalUtil arcInterval = new IntervalUtil(0.1f, 0.15f);
    private final IntervalUtil impactInterval = new IntervalUtil(0.05f, 0.08f);
    
    // BoxUtil实体列表
    private final List<CurveEntity> beamCurves = new ArrayList<CurveEntity>();
    private final List<CurveEntity> arcCurves = new ArrayList<CurveEntity>();
    private CurveEntity mainBeam = null;
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine == null || beam == null) return;
        if (engine.isPaused()) return;
        
        sinceLast += amount;
        arcInterval.advance(amount);
        impactInterval.advance(amount);
        
        // 获取光束信息
        Vector2f from = beam.getFrom();
        Vector2f to = beam.getTo();
        float beamLength = MathUtils.getDistance(from, to);
        float beamAngle = VectorUtils.getAngle(from, to);
        
        // 更新主光束
        updateMainBeam(from, to, beamLength, beamAngle);
        
        // 检测命中
        CombatEntityAPI target = beam.getDamageTarget();
        if (target != null) {
            impacted = true;
            sinceLastImpact = 0.0f;
            
            // 命中效果
            if (impactInterval.intervalElapsed()) {
                createImpactEffect(to, target, engine);
            }
            
            // 区域伤害
            applyAreaDamage(to, beam, engine, amount);
            
            // 电弧效果
            if (arcInterval.intervalElapsed()) {
                createArcEffect(to, target, engine);
            }
        } else {
            impacted = false;
            sinceLastImpact += amount;
        }
        
        // 清理过期实体
        cleanupEntities();
    }
    
    /**
     * 更新主光束
     */
    private void updateMainBeam(Vector2f from, Vector2f to, float length, float angle) {
        // 如果BoxUtil不可用，跳过光束渲染
        if (!data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
            return;
        }

        // 如果主光束不存在，创建它
        if (mainBeam == null || mainBeam.hasDelete()) {
            mainBeam = new CurveEntity();
            mainBeam.initLine(
                new Vector2f(0, 0),
                length,
                BEAM_CORE_COLOR,
                BEAM_GLOW_COLOR,
                BEAM_CORE_WIDTH
            );
            
            // 设置光束纹理
            try {
                mainBeam.getMaterialData().setDiffuse(
                    Global.getSettings().getSprite("graphics/fx/beam_core.png")
                );
            } catch (Exception e) {
                // 纹理加载失败，使用纯色
            }
            
            // 设置纹理动画
            mainBeam.setTexturePixels(64.0f);
            mainBeam.setTextureSpeed(200.0f);
            mainBeam.setInterpolation((short) 16);
            
            // 设置渐变
            mainBeam.setFillStartAlpha(0.0f);
            mainBeam.setFillEndAlpha(0.0f);
            mainBeam.setFillStartFactor(0.05f);
            mainBeam.setFillEndFactor(0.05f);
            
            // 添加到渲染系统
            CombatRenderingManager.addEntity(mainBeam);
            
            beamCurves.add(mainBeam);
        }
        
        // 更新光束位置和长度
        mainBeam.setModelMatrix(TransformUtil.createModelMatrixVanilla(
            from,
            angle,
            null
        ));
        
        // 如果长度变化，需要重新初始化
        // 注意: 这里简化处理，实际应该动态更新节点
    }
    
    /**
     * 创建命中效果
     */
    private void createImpactEffect(Vector2f location, CombatEntityAPI target, CombatEngineAPI engine) {
        // 爆炸效果（始终显示）
        engine.spawnExplosion(
            location,
            new Vector2f(0, 0),
            IMPACT_COLOR,
            30.0f + MathUtils.getRandomNumberInRange(0, 20.0f),
            0.3f
        );
        
        // 创建冲击波曲线（仅在BoxUtil可用时）
        if (data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
            CurveEntity shockwave = new CurveEntity();
            shockwave.initCircle(
                new Vector2f(0, 0),
                20.0f,
                new Color(200, 230, 255, 200),
                new Color(255, 255, 255, 255),
                3.0f
            );
            
            shockwave.setModelMatrix(TransformUtil.createModelMatrixVanilla(
                location,
                0.0f,
                null
            ));
            
            // 设置扩散动画 (通过缩放实现)
            shockwave.setGlobalTimer(0.0f, 0.0f, 0.3f);
            
            CombatRenderingManager.addEntity(shockwave);
            
            beamCurves.add(shockwave);
        }
    }
    
    /**
     * 创建电弧效果
     */
    private void createArcEffect(Vector2f origin, CombatEntityAPI primaryTarget, CombatEngineAPI engine) {
        if (Math.random() > ARC_CHANCE) return;
        
        // 查找范围内的目标
        List<CombatEntityAPI> targets = new ArrayList<CombatEntityAPI>();
        
        for (ShipAPI ship : CombatUtils.getShipsWithinRange(origin, AREA_EFFECT_RANGE)) {
            if (ship == primaryTarget) continue;
            if (ship.getOwner() == primaryTarget.getOwner()) continue;
            if (!ship.isAlive() || ship.isHulk()) continue;
            
            targets.add(ship);
            if (targets.size() >= MAX_ARC_TARGETS) break;
        }
        
        // 创建电弧效果
        for (CombatEntityAPI target : targets) {
            Vector2f targetLoc = target.getLocation();
            float distance = MathUtils.getDistance(origin, targetLoc);
            float angle = VectorUtils.getAngle(origin, targetLoc);
            
            // 创建弯曲的电弧曲线（仅在BoxUtil可用时）
            if (data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
                CurveEntity arc = new CurveEntity();
                arc.initSmoothLine(
                    new Vector2f(0, 0),
                    distance,
                    ARC_COLOR,
                    new Color(255, 255, 255, 255),
                    2.0f + MathUtils.getRandomNumberInRange(0, 1.0f),
                    0.5f // 较大的平滑因子，产生弯曲效果
                );
                
                arc.setModelMatrix(TransformUtil.createModelMatrixVanilla(
                    origin,
                    angle + MathUtils.getRandomNumberInRange(-15.0f, 15.0f), // 随机偏移
                    null
                ));
                
                arc.setGlobalTimer(0.05f, 0.1f, 0.1f);
                
                CombatRenderingManager.addEntity(arc);
                
                arcCurves.add(arc);
            }
            
            // 对目标造成少量伤害（始终执行）
            engine.applyDamage(
                target,
                targetLoc,
                50.0f, // 电弧伤害
                DamageType.ENERGY,
                0.0f, // 无EMP伤害
                false,
                false,
                null
            );
            
            // 生成EMP电弧视觉效果（始终显示）
            engine.spawnEmpArc(
                null,
                origin,
                null,
                target,
                DamageType.ENERGY,
                0.0f,
                0.0f,
                100000.0f, // 范围
                "tachyon_lance_emp_impact",
                15.0f,
                ARC_COLOR,
                Color.WHITE
            );
        }
    }
    
    /**
     * 应用区域伤害
     */
    private void applyAreaDamage(Vector2f location, BeamAPI beam, CombatEngineAPI engine, float amount) {
        // 对范围内的所有敌对目标造成持续伤害
        float damagePerSecond = 100.0f; // 区域DPS
        float damage = damagePerSecond * amount;
        
        for (ShipAPI ship : CombatUtils.getShipsWithinRange(location, AREA_EFFECT_RANGE)) {
            if (ship == beam.getDamageTarget()) continue; // 主目标已经受到光束伤害
            if (ship.getOwner() == beam.getSource().getOwner()) continue;
            if (!ship.isAlive()) continue;
            
            // 距离衰减
            float distance = MathUtils.getDistance(location, ship.getLocation());
            float damageMultiplier = 1.0f - (distance / AREA_EFFECT_RANGE);
            
            engine.applyDamage(
                ship,
                ship.getLocation(),
                damage * damageMultiplier,
                DamageType.ENERGY,
                0.0f,
                false,
                false,
                beam.getSource()
            );
        }
        
        // 对导弹造成伤害
        for (MissileAPI missile : CombatUtils.getMissilesWithinRange(location, AREA_EFFECT_RANGE)) {
            if (missile.getOwner() == beam.getSource().getOwner()) continue;
            if (missile.isFading() || missile.isExpired()) continue;
            
            float distance = MathUtils.getDistance(location, missile.getLocation());
            float damageMultiplier = 1.0f - (distance / AREA_EFFECT_RANGE);
            
            engine.applyDamage(
                missile,
                missile.getLocation(),
                damage * damageMultiplier * 2.0f, // 对导弹双倍伤害
                DamageType.ENERGY,
                0.0f,
                false,
                false,
                beam.getSource()
            );
        }
    }
    
    /**
     * 清理过期实体
     */
    private void cleanupEntities() {
        // 清理光束曲线
        Iterator<CurveEntity> beamIter = beamCurves.iterator();
        while (beamIter.hasNext()) {
            CurveEntity curve = beamIter.next();
            if (curve == null || curve.hasDelete()) {
                beamIter.remove();
            }
        }
        
        // 清理电弧曲线
        Iterator<CurveEntity> arcIter = arcCurves.iterator();
        while (arcIter.hasNext()) {
            CurveEntity curve = arcIter.next();
            if (curve == null || curve.hasDelete()) {
                arcIter.remove();
            }
        }
    }
}
