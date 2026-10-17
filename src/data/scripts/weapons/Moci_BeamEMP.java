package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.entities.SimpleEntity;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

/**
 * EMP离子光束武器效果
 */
public class Moci_BeamEMP implements BeamEffectPlugin {
    
    // ========== 效果参数配置 ==========
    
    /** EMP触发间隔控制 (0.2-0.3秒随机间隔) */
    private final IntervalUtil fireInterval = new IntervalUtil(0.2f, 0.3f);
    
    /** 视觉效果电弧间隔控制 (0.2-0.25秒随机间隔) */
    private final IntervalUtil arcIntervalUtil = new IntervalUtil(0.2f, 0.25f);
    
    /** 光束亮度阈值 - 只有达到此亮度才触发EMP效果 */
    private static final float EMP_BRIGHTNESS_THRESHOLD = 0.9f;
    
    /** 视觉效果亮度阈值 - 达到此亮度开始生成装饰电弧 */
    private static final float VISUAL_BRIGHTNESS_THRESHOLD = 0.9f;
    
    /** EMP伤害倍数 - 相对于光束幅能伤害 */
    private static final float EMP_DAMAGE_MULT = 0.5f;
    
    /** 实体伤害倍数 - 相对于光束基础伤害 */
    private static final float PHYSICAL_DAMAGE_MULT = 0.25f;
    
    /** 扩散电弧伤害倍数 - 相对于武器基础伤害 */
    private static final float SPREAD_DAMAGE_MULT = 0.1f;
    
    /** 扩散电弧EMP倍数 - 相对于武器幅能伤害 */
    private static final float SPREAD_EMP_MULT = 0.25f;
    
    /** 护盾穿透基础阈值 - 硬辐能水平需超过此值才有穿透可能 */
    private static final float PIERCE_BASE_THRESHOLD = 0.5f;
    
    /** 扩散电弧范围 */
    private static final float SPREAD_RANGE = 250f;
    
    /** 扩散电弧数量 */
    private static final int SPREAD_ARC_COUNT = 5;
    
    // ========== 颜色配置 ==========
    
    /** 电弧边缘颜色 */
    private static final Color ARC_FRINGE_COLOR = new Color(20, 102, 255, 255);
    
    /** 电弧核心颜色 */
    private static final Color ARC_CORE_COLOR = new Color(211, 211, 255, 255);
    
    // ========== 状态跟踪变量 ==========
    
    /** 标记上一帧DPS持续时间是否为零 - 用于处理时间加速情况 */
    private boolean wasZero = true;
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        // 基础检查：引擎暂停时不处理
        if (engine.isPaused()) return;
        
        // 获取光束当前伤害目标
        CombatEntityAPI target = beam.getDamageTarget();
        
        // ========== 主要EMP效果处理 ==========
        handleEMPEffects(engine, beam, target);
        
        // ========== 视觉效果处理 ==========
        handleVisualEffects(amount, beam);
    }
    
    /**
     * 处理主要的EMP效果逻辑
     * 包括护盾穿透判断和EMP电弧生成
     */
    private void handleEMPEffects(CombatEngineAPI engine, BeamAPI beam, CombatEntityAPI target) {
        // 只对舰船目标生效，且光束亮度需足够高
        if (!(target instanceof ShipAPI) || beam.getBrightness() < EMP_BRIGHTNESS_THRESHOLD) {
            return;
        }
        
        ShipAPI targetShip = (ShipAPI) target;
        
        // 获取并处理DPS持续时间
        float duration = beam.getDamage().getDpsDuration();
        
        // 处理时间加速情况：当游戏处于快进时，dpsDuration可能不会每帧重置
        if (!wasZero) {
            duration = 0f; // 强制重置为0，确保计时正确
        }
        wasZero = beam.getDamage().getDpsDuration() <= 0;
        
        // 更新EMP触发计时器
        fireInterval.advance(duration);
        
        // 检查是否到达触发间隔
        if (!fireInterval.intervalElapsed()) {
            return;
        }
        
        // ========== 护盾穿透计算 ==========
        boolean hitShield = isHittingShield(targetShip, beam);
        boolean piercedShield = false;
        
        if (hitShield) {
            // 计算护盾穿透概率
            float pierceChance = calculateShieldPierceChance(targetShip);
            piercedShield = Math.random() < pierceChance;
        }
        
        // ========== EMP电弧生成 ==========
        if (!hitShield || piercedShield) {
            // 生成主要EMP电弧
            generateEMPArc(engine, beam, targetShip);
            
            // 生成扩散电弧
            generateSpreadArcs(engine, beam, targetShip);
        }
    }
    
    /**
     * 检查光束是否命中目标护盾
     */
    private boolean isHittingShield(ShipAPI target, BeamAPI beam) {
        return target.getShield() != null && target.getShield().isWithinArc(beam.getTo());
    }
    
    /**
     * 计算护盾穿透概率
     * 基于目标的硬辐能水平和护盾穿透加成
     */
    private float calculateShieldPierceChance(ShipAPI target) {
        // 基础穿透概率 = 硬辐能水平 - 基础阈值
        float baseChance = target.getHardFluxLevel() - PIERCE_BASE_THRESHOLD;
        
        // 应用舰船的护盾穿透倍数加成
        float pierceMultiplier = target.getMutableStats().getDynamic().getValue(Stats.SHIELD_PIERCED_MULT);
        
        return baseChance * pierceMultiplier;
    }
    
    /**
     * 生成EMP电弧效果
     * 对目标造成EMP和少量实体伤害
     */
    private void generateEMPArc(CombatEngineAPI engine, BeamAPI beam, ShipAPI target) {
        Vector2f impactPoint = beam.getRayEndPrevFrame();
        
        // 计算EMP伤害值（基于光束的幅能伤害分量）
        float empDamage = beam.getDamage().getFluxComponent() * EMP_DAMAGE_MULT;
        
        // 计算实体伤害值（基于光束的基础伤害）
        float physicalDamage = beam.getDamage().getDamage() * PHYSICAL_DAMAGE_MULT;
        
        // 新版EMP电弧参数：即使保留默认手感，也显式写出方便后续调参
        EmpArcParams mainArcParams = createMainEmpArcParams();
        
        // 生成EMP电弧
        engine.spawnEmpArc(
            beam.getSource(),        // 电弧来源（发射舰船）
            impactPoint,             // 电弧起始点
            beam.getDamageTarget(),  // 电弧目标实体
            beam.getDamageTarget(),  // 电弧视觉目标
            DamageType.ENERGY,       // 伤害类型
            physicalDamage,          // 实体伤害
            empDamage,               // EMP伤害
            100000f,                 // 最大射程
            "tachyon_lance_emp_impact", // 音效ID
            beam.getWidth() * 0.5f,    // 电弧宽度
            ARC_FRINGE_COLOR,        // 电弧边缘颜色
            ARC_CORE_COLOR,          // 电弧核心颜色
            mainArcParams            // 新版电弧参数对象
        );
    }
    
    /**
     * 生成扩散电弧效果
     * 从命中点向周围目标扩散电弧
     */
    private void generateSpreadArcs(CombatEngineAPI engine, BeamAPI beam, ShipAPI mainTarget) {
        Vector2f impactPoint = beam.getRayEndPrevFrame();
        
        // 获取扩散范围内的所有有效目标
        List<CombatEntityAPI> validTargets = getValidSpreadTargets(impactPoint, beam);
        
        // 计算扩散电弧的伤害值
        float spreadDamage = beam.getDamage().getDamage() * SPREAD_DAMAGE_MULT;
        float spreadEMP = beam.getDamage().getFluxComponent() * SPREAD_EMP_MULT;
        
        // 生成指定数量的扩散电弧
        for (int i = 0; i < SPREAD_ARC_COUNT; i++) {
            CombatEntityAPI arcTarget;
            
            // 如果有有效目标，随机选择一个并移除避免重复攻击；否则创建随机点目标
            if (!validTargets.isEmpty()) {
                int randomIndex = MathUtils.getRandomNumberInRange(0, validTargets.size() - 1);
                arcTarget = validTargets.get(randomIndex);
                validTargets.remove(randomIndex); // 移除已选择的目标，避免重复攻击
            } else {
                // 创建随机点进行纯视觉效果
                Vector2f randomPoint = MathUtils.getRandomPointInCircle(impactPoint, SPREAD_RANGE);
                arcTarget = new SimpleEntity(randomPoint);
            }
            
            // 新版扩散电弧参数：与主电弧分开写，便于以后单独做“更细/更乱/更亮”的扩散风格
            EmpArcParams spreadArcParams = createSpreadEmpArcParams();
            
            // 生成扩散电弧
            engine.spawnEmpArc(
                beam.getSource(),        // 电弧来源
                impactPoint,             // 电弧起始点（主命中点）
                beam.getSource(),        // 起始绑定实体
                arcTarget,               // 电弧目标
                DamageType.ENERGY,       // 伤害类型
                spreadDamage,            // 实体伤害
                spreadEMP,               // EMP伤害
                100000f,                 // 最大射程
                "tachyon_lance_emp_impact", // 音效ID
                beam.getWidth() * 0.15f,                      // 电弧宽度
                ARC_FRINGE_COLOR,        // 电弧边缘颜色
                ARC_CORE_COLOR,          // 电弧核心颜色
                spreadArcParams          // 新版电弧参数对象
            );
        }
    }
    
    /**
     * 构建主命中EMP电弧的新版参数。
     * 这里把0.98+新增的所有字段都显式展开，即使与默认值一致也保留出来，方便理解新版API。
     */
    private EmpArcParams createMainEmpArcParams() {
        EmpArcParams params = new EmpArcParams();
        
        // 分段长度倍率：控制电弧每一小段的长度；越大越像长折线。1f 是新版默认值。
        float segmentLengthMult = 1f;
        
        // 锯齿衰减系数：越大电弧越平滑、越不尖锐。0f 是新版默认值，表示不额外抑制锯齿。
        float zigZagReductionFactor = 0f;
        
        // 最大锯齿倍率：限制电弧拐折幅度的上限。1f 是新版默认值。
        float maxZigZagMult = 1f;
        
        // 尾端淡出距离：电弧接近目标时开始收尾的距离。100f 是新版默认值。
        float fadeOutDist = 100.0f;
        
        // 最小淡出倍率：保证电弧尾端至少有一定长度的淡出。2f 是新版默认值。
        float minFadeOutMult = 1.25f;
        
        // 闪烁速率倍率：控制电弧内部 flicker 动画推进速度。1f 是新版默认值。
        float flickerRateMult = 1.25f;
        
        // 发光尺寸倍率：控制电弧外层光晕大小。1f 是新版默认值。
        float glowSizeMult = 1.0f;
        
        // 发光透明度倍率：控制电弧光晕亮度/透明度。1f 是新版默认值。
        float glowAlphaMult = 0.5f;
        
        // 移动持续时间强制覆盖：>= 0 时强行指定电弧动画时长；-1f 表示使用引擎自动时长。
        float movementDurOverride = -1.0f;
        
        // 移动持续时间上限：限制自动计算出来的最大时长。0.1f 是新版默认值。
        float movementDurMax = 0.1f;
        
        // 移动持续时间下限：限制自动计算出来的最小时长。0f 是新版默认值。
        float movementDurMin = 0.0f;
        
        // 亮斑满亮占比：电弧中最亮“热点”完全保持满亮的比例。0.33f 是新版默认值。
        float brightSpotFullFraction = 0.25f;
        
        // 亮斑淡出占比：亮斑从最亮过渡到普通亮度的比例。0.33f 是新版默认值。
        float brightSpotFadeFraction = 0.5f;
        
        // 非亮斑最低亮度：非热点区域至少保留多少亮度。0f 是新版默认值。
        float nonBrightSpotMinBrightness = 0.0f;
        
        // 光晕颜色覆盖：为 null 时使用调用时传入的 fringe/core 颜色，不额外覆盖。
        Color glowColorOverride = null;
        
        // 导弹熄火开关：命中导弹时是否附带“熄火/压制推进器”效果。true 是新版默认值。
        boolean flamesOutMissiles = true;
        
        params.segmentLengthMult = segmentLengthMult;
        params.zigZagReductionFactor = zigZagReductionFactor;
        params.maxZigZagMult = maxZigZagMult;
        params.fadeOutDist = fadeOutDist;
        params.minFadeOutMult = minFadeOutMult;
        params.flickerRateMult = flickerRateMult;
        params.glowSizeMult = glowSizeMult;
        params.glowAlphaMult = glowAlphaMult;
        params.movementDurOverride = movementDurOverride;
        params.movementDurMax = movementDurMax;
        params.movementDurMin = movementDurMin;
        params.brightSpotFullFraction = brightSpotFullFraction;
        params.brightSpotFadeFraction = brightSpotFadeFraction;
        params.nonBrightSpotMinBrightness = nonBrightSpotMinBrightness;
        params.glowColorOverride = glowColorOverride;
        params.flamesOutMissiles = flamesOutMissiles;
        
        return params;
    }
    
    /**
     * 构建扩散电弧的新版参数。
     * 这里同样把所有字段都拆开，后续如果你想把扩散弧做得更碎、更闪，可以直接从这组开始调。
     */
    private EmpArcParams createSpreadEmpArcParams() {
        EmpArcParams params = new EmpArcParams();
        
        // 分段长度倍率：扩散弧通常更短更碎，所以保持默认1f，避免看起来过长。
        float segmentLengthMult = 1.0f;
        
        // 锯齿衰减系数：0f 保留原始随机感，适合扩散放电效果。
        float zigZagReductionFactor = 0.15f;
        
        // 最大锯齿倍率：1f 保持默认拐折幅度。
        float maxZigZagMult = 1.0f;
        
        // 尾端淡出距离：100f 保持新版默认，让短电弧收尾自然。
        float fadeOutDist = 125.0f;
        
        // 最小淡出倍率：2f 保持默认，避免扩散弧尾部突然截断。
        float minFadeOutMult = 2.0f;
        
        // 闪烁速率倍率：1f 保持默认闪烁速度，不额外提高噪点感。
        float flickerRateMult = 1.25f;
        
        // 发光尺寸倍率：1f 保持默认光晕体积。
        float glowSizeMult = 1.0f;
        
        // 发光透明度倍率：1f 保持默认亮度。
        float glowAlphaMult = 1.0f;
        
        // 移动持续时间强制覆盖：-1f 表示不强行锁时长，交给引擎自动计算。
        float movementDurOverride = -1.0f;
        
        // 移动持续时间上限：0.1f 保持默认，短扩散弧不会拖得过长。
        float movementDurMax = 0.1f;
        
        // 移动持续时间下限：0f 保持默认。
        float movementDurMin = 0.0f;
        
        // 亮斑满亮占比：0.33f 保持默认热点比例。
        float brightSpotFullFraction = 0.5f;
        
        // 亮斑淡出占比：0.33f 保持默认热点淡出节奏。
        float brightSpotFadeFraction = 0.5f;
        
        // 非亮斑最低亮度：0f 表示普通区域可完全回落到基础亮度。
        float nonBrightSpotMinBrightness = 0.0f;
        
        // 光晕颜色覆盖：null 表示沿用调用时传入的电弧颜色。
        Color glowColorOverride = null;
        
        // 导弹熄火开关：true 保持默认，这样扩散弧命中导弹时仍然有EMP压制感。
        boolean flamesOutMissiles = true;
        
        params.segmentLengthMult = segmentLengthMult;
        params.zigZagReductionFactor = zigZagReductionFactor;
        params.maxZigZagMult = maxZigZagMult;
        params.fadeOutDist = fadeOutDist;
        params.minFadeOutMult = minFadeOutMult;
        params.flickerRateMult = flickerRateMult;
        params.glowSizeMult = glowSizeMult;
        params.glowAlphaMult = glowAlphaMult;
        params.movementDurOverride = movementDurOverride;
        params.movementDurMax = movementDurMax;
        params.movementDurMin = movementDurMin;
        params.brightSpotFullFraction = brightSpotFullFraction;
        params.brightSpotFadeFraction = brightSpotFadeFraction;
        params.nonBrightSpotMinBrightness = nonBrightSpotMinBrightness;
        params.glowColorOverride = glowColorOverride;
        params.flamesOutMissiles = flamesOutMissiles;
        
        return params;
    }
    
    /**
     * 获取扩散范围内的有效目标
     * 只攻击敌方目标，避免友伤
     * 使用Grid遍历优化性能
     */
    private List<CombatEntityAPI> getValidSpreadTargets(Vector2f centerPoint, BeamAPI beam) {
        List<CombatEntityAPI> validTargets = new ArrayList<CombatEntityAPI>();
        
        // 使用Grid遍历优化：获取范围内的所有对象
        CombatEngineAPI engine = Global.getCombatEngine();
        Iterator<Object> objectIter = engine.getAllObjectGrid().getCheckIterator(
            centerPoint,        // 检查中心点
            SPREAD_RANGE * 2f,  // 检查宽度
            SPREAD_RANGE * 2f   // 检查高度
        );
        
        // 遍历Grid返回的对象
        while (objectIter.hasNext()) {
            Object obj = objectIter.next();
            
            // 类型筛选：只考虑CombatEntityAPI
            if (!(obj instanceof CombatEntityAPI)) {
                continue;
            }
            
            CombatEntityAPI entity = (CombatEntityAPI) obj;
            
            // 只考虑舰船和导弹
            if (!(entity instanceof ShipAPI || entity instanceof MissileAPI)) {
                continue;
            }
            
            // 跳过友军，只攻击敌方目标
            if (entity.getOwner() == beam.getSource().getOwner()) {
                continue;
            }
            
            // 排除无碰撞的实体
            if (entity.getCollisionClass().equals(CollisionClass.NONE)) {
                continue;
            }
            
            // 精确距离检查：Grid遍历返回的是矩形区域，我们需要圆形范围
            float distance = MathUtils.getDistance(centerPoint, entity.getLocation());
            if (distance > SPREAD_RANGE) {
                continue;
            }
            
            validTargets.add(entity);
        }
        
        return validTargets;
    }
    
    /**
     * 处理视觉效果
     * 在光束路径上生成装饰性电弧
     */
    private void handleVisualEffects(float amount, BeamAPI beam) {
        // 只在光束亮度足够时生成视觉效果
        if (beam.getBrightness() <= VISUAL_BRIGHTNESS_THRESHOLD) {
            return;
        }
        
        arcIntervalUtil.advance(amount);
        
        if (arcIntervalUtil.intervalElapsed()) {
            createVisualArc(beam, beam.getWidth());
        }
    }
    
    /**
     * 创建视觉装饰电弧效果
     * 生成2条从发射点到目标点的完整电弧，增强视觉效果
     * 
     * @param beam 光束实例
     * @param arcWidth 电弧宽度参考值
     */
    private void createVisualArc(BeamAPI beam, float arcWidth) {
        Random random = new Random();
        
        // 生成2条电弧，从发射点直接到目标点
        for (int i = 0; i < 2; i++) {
            // 为每条电弧添加随机偏移，让它们看起来更自然
            Vector2f startPoint = Misc.getPointWithinRadiusUniform(
                beam.getFrom(),
                0f,                         // 最小偏移
                0f,            // 最大偏移
                random
            );
            
            Vector2f endPoint = Misc.getPointWithinRadiusUniform(
                beam.getRayEndPrevFrame(),
                0f,                         // 最小偏移
                0f,            // 最大偏移
                random
            );
            
            // 新版视觉电弧参数：这里依然保留原手感，但把所有新字段都显式暴露出来
            EmpArcParams visualArcParams = createVisualEmpArcParams();
            
            // 生成视觉电弧
            EmpArcEntityAPI visualArc = Global.getCombatEngine().spawnEmpArcVisual(
                startPoint,                   // 起始点
                beam.getSource(),             // 起始实体
                endPoint,                     // 结束点
                beam.getSource(),             // 结束实体
                // 电弧宽度：基础宽度 + 随机变化
                arcWidth * 0.4f + (arcWidth * 0.2f * random.nextFloat()),
                ARC_FRINGE_COLOR,             // 边缘颜色
                ARC_CORE_COLOR,               // 核心颜色
                visualArcParams               // 新版电弧参数对象
            );
            
            // 新版布尔重载：false 与旧版无参调用的行为一致，便于保留原效果但展示新版接口。
            visualArc.setSingleFlickerMode(false);
        }
    }
    
    /**
     * 构建纯视觉装饰电弧的新版参数。
     * 视觉弧不负责伤害，所以这组参数更偏向“看起来怎么闪、怎么发光”。
     */
    private EmpArcParams createVisualEmpArcParams() {
        EmpArcParams params = new EmpArcParams();
        
        // 分段长度倍率：1f 保持默认，电弧不会被拉得过于修长。
        float segmentLengthMult = 0.75f;
        
        // 锯齿衰减系数：0f 保留默认放电感；如果想更顺滑，可逐渐往上加。
        float zigZagReductionFactor = 0f;
        
        // 最大锯齿倍率：1f 保持默认拐折幅度。
        float maxZigZagMult = 0.85f;
        
        // 尾端淡出距离：100f 保持默认，让光束末端电弧收尾自然。
        float fadeOutDist = 100.0f;
        
        // 最小淡出倍率：2f 保持默认。
        float minFadeOutMult = 1.25f;
        
        // 闪烁速率倍率：1f 保持默认。如果你想让装饰弧更“神经质”，可以往上调。
        float flickerRateMult = 1.25f;
        
        // 发光尺寸倍率：1f 保持默认光晕大小；适合先稳定观察新版效果。
        float glowSizeMult = 1.0f;
        
        // 发光透明度倍率：1f 保持默认亮度。
        float glowAlphaMult = 1.0f;
        
        // 移动持续时间强制覆盖：-1f 表示继续使用引擎自动时长。
        float movementDurOverride = -1.0f;
        
        // 移动持续时间上限：0.1f 保持默认，避免视觉弧拖尾过长。
        float movementDurMax = 0.1f;
        
        // 移动持续时间下限：0f 保持默认。
        float movementDurMin = 0.0f;
        
        // 亮斑满亮占比：0.33f 保持默认亮点比例。
        float brightSpotFullFraction = 0.25f;
        
        // 亮斑淡出占比：0.33f 保持默认亮点过渡。
        float brightSpotFadeFraction = 0.25f;
        
        // 非亮斑最低亮度：0f 表示非热点区域可以显著变暗。
        float nonBrightSpotMinBrightness = 0.0f;
        
        // 光晕颜色覆盖：null 表示不替换颜色，继续沿用 ARC_FRINGE_COLOR / ARC_CORE_COLOR。
        Color glowColorOverride = null;
        
        // 导弹熄火开关：视觉弧本身不打伤害，但仍然显式写出这个新版字段帮助理解API。
        boolean flamesOutMissiles = true;
        
        params.segmentLengthMult = segmentLengthMult;
        params.zigZagReductionFactor = zigZagReductionFactor;
        params.maxZigZagMult = maxZigZagMult;
        params.fadeOutDist = fadeOutDist;
        params.minFadeOutMult = minFadeOutMult;
        params.flickerRateMult = flickerRateMult;
        params.glowSizeMult = glowSizeMult;
        params.glowAlphaMult = glowAlphaMult;
        params.movementDurOverride = movementDurOverride;
        params.movementDurMax = movementDurMax;
        params.movementDurMin = movementDurMin;
        params.brightSpotFullFraction = brightSpotFullFraction;
        params.brightSpotFadeFraction = brightSpotFadeFraction;
        params.nonBrightSpotMinBrightness = nonBrightSpotMinBrightness;
        params.glowColorOverride = glowColorOverride;
        params.flamesOutMissiles = flamesOutMissiles;
        
        return params;
    }
}
