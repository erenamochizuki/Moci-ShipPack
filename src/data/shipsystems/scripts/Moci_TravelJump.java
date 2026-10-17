package data.shipsystems.scripts;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicLensFlare;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.RippleDistortion;
import org.dark.shaders.distortion.WaveDistortion;
import org.lazywizard.lazylib.MathUtils;
import data.scripts.util.Moci_BoxBasedUtil;
import org.boxutil.units.standard.entity.FlareEntity;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import java.awt.*;

/**
 * HSI旅行跳跃系统 - 实现舰船的高速移动和相位穿越效果
 * 
 * 主要功能：
 * 1. 在系统激活时大幅提升舰船速度和加速度
 * 2. 在OUT状态时创建残影效果，模拟高速移动
 * 3. 在系统结束时播放视觉和音效
 * 4. 支持模块化舰船的同步效果
 */
public class Moci_TravelJump extends BaseShipSystemScript {
    // 状态控制变量
    private boolean once = true; // 控制系统是否首次激活
    private boolean landed = false; // 着陆状态标记（未使用）
    private boolean onceFx = false; // 控制特效是否已播放

    // 核心引用
    public CombatEngineAPI engine = Global.getCombatEngine();
    private ShipAPI ship; // 当前舰船引用

    // 位置相关变量
    private Vector2f endLoc = null; // 目标位置
    private Vector2f orLoc = null; // 原始位置（未使用）
    private Vector2f lastFrameLoc = null; // 上一帧位置
    private float elpased = 0; // 经过时间（未使用）

    // 残影效果控制
    private IntervalUtil afterImage = new IntervalUtil(0.05f, 0.05f); // 残影生成间隔
    protected static final Color AFTER_IMAGE = new Color(175, 175, 255, 175); // 残影颜色（淡蓝紫色）

    // 目标位置视觉效果相关
    // 目标位置光晕颜色（与Moci_PhaseCrossing相同强度）
    public static final Color TARGET_GLOW_1 = new Color(191, 221, 255, 255); // 浅紫色
    public static final Color TARGET_GLOW_2 = new Color(120, 180, 255, 150); // 浅蓝色
    public static final Color TARGET_CORE = new Color(200, 180, 255, 185); // 浅色内核

    // 目标位置烟雾颜色（与Moci_PhaseCrossing相同强度）
    public static final Color TARGET_SMOKE_1 = new Color(180, 150, 255, 60); // 浅紫色烟雾
    public static final Color TARGET_SMOKE_2 = new Color(150, 200, 255, 50); // 浅蓝色烟雾

    private WaveDistortion targetWave = null; // 目标位置扭曲效果
    private boolean targetCalculated = false; // 标记是否已计算目标位置

    // BoxUtil光斑实体
    private FlareEntity outerFlare = null; // 外圈光晕
    private FlareEntity innerFlare = null; // 内核光晕

    // === 视觉效果开关 ===
    private static final boolean ENABLE_SMOKE_EFFECTS = false; // 是否启用烟雾效果（true=启用烟雾，false=仅光晕和扭曲）

    /**
     * 系统效果应用方法 - 核心逻辑处理
     * 
     * @param stats       舰船属性统计
     * @param id          系统ID
     * @param state       系统状态（IN/ACTIVE/OUT）
     * @param effectLevel 效果强度（0-1）
     */
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        // 获取舰船实体引用
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        } else {
            return; // 如果不是舰船实体则退出
        }

        // 计算目标位置（只在首次激活时计算一次）
        if (once && !targetCalculated) {
            endLoc = evaluateLandLocationForStart(ship);
            targetCalculated = true;
        }

        if (once) {
            // 处理系统结束状态（OUT）
            if (state == ShipSystemStatsScript.State.OUT) {
                stats.getMaxSpeed().unmodify(id); // 移除速度修正
            } else {
                // 系统激活时的属性增强
                stats.getMaxSpeed().modifyFlat(id, 600f * effectLevel); // 最大速度+600
                stats.getAcceleration().modifyFlat(id, 600f * effectLevel); // 加速度+600
                stats.getDeceleration().modifyFlat(id, 300f * effectLevel); // 减速度+300
            }

            // OUT状态的特殊处理（系统结束阶段）
            if (state == State.OUT) {
                // 恢复舰船透明度
                ship.setExtraAlphaMult2(1);
                if (!ship.getChildModulesCopy().isEmpty()) {
                    for (ShipAPI module : ship.getChildModulesCopy()) {
                        module.setExtraAlphaMult2(1f); // 同步恢复模块透明度
                    }
                }

                // 计算当前位置和速度差值用于残影效果
                Vector2f currLoc = ship.getLocation();
                Vector2f diff = new Vector2f(ship.getVelocity());

                // 强制舰船加速（保持前进动力）
                ship.giveCommand(ShipCommand.ACCELERATE, null, 0);

                // 更新位置记录
                lastFrameLoc = new Vector2f(currLoc);
                ship.getLocation().set(currLoc);

                // 残影效果生成
                afterImage.advance(engine.getElapsedInLastFrame());
                if (afterImage.intervalElapsed()) {
                    // 根据效果强度调整残影透明度
                    Color c = new Color(AFTER_IMAGE.getRed(), AFTER_IMAGE.getGreen(),
                            AFTER_IMAGE.getBlue(), (int) (AFTER_IMAGE.getAlpha() * effectLevel));

                    // 为主舰船添加残影（反向速度方向）
                    ship.addAfterimage(c, 0, 0, -diff.getX(), -diff.getY(),
                            0, 0.1f, 0f, 0.1f, false, true, true);

                    // 为子模块添加残影
                    if (!ship.getChildModulesCopy().isEmpty()) {
                        for (ShipAPI module : ship.getChildModulesCopy()) {
                            module.addAfterimage(c, 0, 0, diff.getX(), diff.getY(),
                                    0, 0.1f, 0f, 0.1f, false, true, true);
                        }
                    }
                }

                // OUT状态也创建舰船位置视觉效果（跟随舰船移动）（仅在BoxUtil可用时）
                if (data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
                    createTargetLocationEffects(state, effectLevel);
                    // 更新OUT状态的扭曲效果
                    updateTargetWaveDistortion(state, effectLevel);
                }

                // 播放结束特效（仅一次）
                if (!onceFx) {
                    onceFx = true;

                    // 在OUT阶段开始时创建一个MagicLensFlare，与FlareEntity相同的大小和颜色
                    float flareWidth = ship.getCollisionRadius() * 6.0F;
                    MagicLensFlare.createSharpFlare(engine, ship, ship.getLocation(), 9f,
                            flareWidth, 0f, // 朝向0度
                            TARGET_GLOW_1, TARGET_GLOW_2);

                    // 播放音效和创建涟漪效果
                    Global.getSoundPlayer().playSound("Moci_WarpSound2", 1f, 2f, ship.getLocation(), ship.getVelocity());
                    createOutPhaseRipple(ship.getLocation());
                }
            } else {
                // 非OUT状态时隐藏舰船（相位状态）
                ship.setExtraAlphaMult2(0f);
                if (!ship.getChildModulesCopy().isEmpty()) {
                    for (ShipAPI module : ship.getChildModulesCopy()) {
                        module.setExtraAlphaMult2(0f); // 同步隐藏模块
                    }
                }

                // IN和ACTIVE状态创建舰船位置视觉效果（跟随舰船移动）（仅在BoxUtil可用时）
                if (data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
                    createTargetLocationEffects(state, effectLevel);

                    // 创建和更新舰船位置的扭曲效果
                    if (targetWave == null) {
                        targetWave = new WaveDistortion(ship.getLocation(), new Vector2f(0, 0));
                        targetWave.setSize(ship.getCollisionRadius() * 1.5F);
                        targetWave.setIntensity(10.0F);
                        targetWave.setArc(0.0F, 360.0F);
                        targetWave.flip(true); // IN阶段正向扭曲
                        DistortionShader.addDistortion(targetWave);
                    } else {
                        // 根据状态更新扭曲效果
                        updateTargetWaveDistortion(state, effectLevel);
                    }
                }
            }
        } else {
            // 简化版本（当once为false时）
            if (state == ShipSystemStatsScript.State.OUT) {
                stats.getMaxSpeed().unmodify(id); // 移除速度修正
            } else {
                // 仅提供基础速度增强，无视觉效果
                stats.getMaxSpeed().modifyFlat(id, 600f * effectLevel);
                stats.getAcceleration().modifyFlat(id, 600f * effectLevel);
            }
        }
    }

    /**
     * 创建目标位置的视觉效果
     * 使用BoxUtil的FlareEntity实现真正跟随舰船移动的光斑
     */
    private void createTargetLocationEffects(State state, float effectLevel) {
        // 如果BoxUtil不可用，跳过所有视觉效果
        if (!data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
            return;
        }

        // 使用舰船当前位置，让特效跟随舰船移动
        Vector2f currentLoc = ship.getLocation();

        // 计算系统持续时间
        float in = ship.getSystem().getChargeUpDur();
        float full = ship.getSystem().getChargeActiveDur();
        float out = ship.getSystem().getChargeDownDur();

        // 定义最大和最小尺寸
        float maxWidth = ship.getCollisionRadius() * 3.0F;
        float minWidth = ship.getCollisionRadius() * 0F;
        
        // 根据effectLevel计算当前尺寸（从最小到最大）
        float currentWidth = minWidth + (maxWidth - minWidth) * effectLevel;
        float currentHeight = currentWidth * 0.1F;

        // 创建或更新外圈光晕
        if (outerFlare == null || outerFlare.hasDelete()) {
            Color targetGlow1 = new Color(TARGET_GLOW_1.getRed(), TARGET_GLOW_1.getGreen(),
                    TARGET_GLOW_1.getBlue(), TARGET_GLOW_1.getAlpha());
            Color targetGlow2 = new Color(TARGET_GLOW_2.getRed(), TARGET_GLOW_2.getGreen(),
                    TARGET_GLOW_2.getBlue(), TARGET_GLOW_2.getAlpha());

            float totalTime = in + full + out;
            outerFlare = Moci_BoxBasedUtil.addSharpFlare(currentLoc, 0f,
                    currentWidth, currentHeight, targetGlow1, targetGlow2, 0f, totalTime, 0f, 1.0F, 0.5F);
            outerFlare.setFlickMixValue(0.8F); // 增强闪烁效果
        } else {
            // 每帧更新位置和尺寸
            outerFlare.setLocation(currentLoc);
            outerFlare.setSize(currentWidth, currentHeight);
        }

        // 内核光晕的尺寸参数
        float innerMaxWidth = ship.getCollisionRadius() * 3F * 0.20F;
        float innerMinWidth = ship.getCollisionRadius() * 0F * 0.20F;
        float innerCurrentWidth = innerMinWidth + (innerMaxWidth - innerMinWidth) * effectLevel;
        float innerCurrentHeight = innerCurrentWidth * 0.1F;

        // 创建或更新内核光晕
        if (innerFlare == null || innerFlare.hasDelete()) {
            Color targetCore = new Color(TARGET_CORE.getRed(), TARGET_CORE.getGreen(),
                    TARGET_CORE.getBlue(), 255);

            float totalTime = in + full + out;
            innerFlare = Moci_BoxBasedUtil.addSharpFlare(currentLoc, 0f,
                    innerCurrentWidth, innerCurrentHeight, targetCore, targetCore, 0f, totalTime, 0f, 1.0F, 0.5F);
            innerFlare.setFlickMixValue(0.8F); // 增强闪烁效果
        } else {
            // 每帧更新位置和尺寸
            innerFlare.setLocation(currentLoc);
            innerFlare.setSize(innerCurrentWidth, innerCurrentHeight);
        }

        // 舰船位置烟雾效果（可选）
        if (ENABLE_SMOKE_EFFECTS) {
            createTargetSmokeRing(currentLoc, ship.getCollisionRadius() * 1.0F * effectLevel, effectLevel);
        }
    }

    /**
     * 创建目标位置的烟雾环效果
     * 浅紫浅蓝色调的柔和烟雾
     */
    private void createTargetSmokeRing(Vector2f center, float radius, float intensity) {
        int particleCount = 8; // 与Moci_PhaseCrossing相同的粒子数量

        for (int i = 0; i < particleCount; i++) {
            // 在圆周上随机分布粒子
            float angle = (360.0f / particleCount) * i + MathUtils.getRandomNumberInRange(-30f, 30f);
            float distance = radius + MathUtils.getRandomNumberInRange(-radius * 0.5f, radius * 0.5f);

            Vector2f particlePos = MathUtils.getPointOnCircumference(center, distance, angle);

            // 粒子向外缓慢扩散的速度
            Vector2f velocity = Vector2f.sub(particlePos, center, null);
            velocity.normalise();
            velocity.scale(MathUtils.getRandomNumberInRange(8f, 20f)); // 与Moci_PhaseCrossing相同的速度

            // 粒子持续时间
            float duration = MathUtils.getRandomNumberInRange(1.2f, 2.0f); // 与Moci_PhaseCrossing相同的持续时间

            // 随机选择浅紫或浅蓝色
            Color particleColor = MathUtils.getRandomNumberInRange(0f, 1f) < 0.5f
                    ? new Color(TARGET_SMOKE_1.getRed(), TARGET_SMOKE_1.getGreen(),
                            TARGET_SMOKE_1.getBlue(), (int) (TARGET_SMOKE_1.getAlpha() * intensity))
                    : new Color(TARGET_SMOKE_2.getRed(), TARGET_SMOKE_2.getGreen(),
                            TARGET_SMOKE_2.getBlue(), (int) (TARGET_SMOKE_2.getAlpha() * intensity));

            // 添加柔和粒子
            engine.addSmoothParticle(
                    particlePos, // 位置
                    velocity, // 速度
                    MathUtils.getRandomNumberInRange(radius * 0.4f, radius * 0.8f), // 与Moci_PhaseCrossing相同的尺寸
                    MathUtils.getRandomNumberInRange(0.5f, 0.8f), // 与Moci_PhaseCrossing相同的亮度
                    duration, // 持续时间
                    particleColor // 颜色
            );
        }
    }

    /**
     * 系统效果移除方法 - 清理所有修正和重置状态
     * 
     * @param stats 舰船属性统计
     * @param id    系统ID
     */
    public void unapply(MutableShipStatsAPI stats, String id) {
        // 移除所有属性修正
        stats.getMaxSpeed().unmodify(id); // 移除最大速度修正
        stats.getMaxTurnRate().unmodify(id); // 移除转向速度修正
        stats.getTurnAcceleration().unmodify(id); // 移除转向加速度修正
        stats.getAcceleration().unmodify(id); // 移除加速度修正
        stats.getDeceleration().unmodify(id); // 移除减速度修正

        // 重置状态变量
        once = false; // 标记系统已使用过
        elpased = 0; // 重置经过时间
        targetCalculated = false;
        onceFx = false;

        // 清理扭曲效果
        if (targetWave != null) {
            targetWave.fadeOutSize(0.2F);
            targetWave = null;
        }

        // 清理光斑效果（FlareEntity会自动淡出，不需要手动清理）
        outerFlare = null;
        innerFlare = null;
    }

    /**
     * 计算舰船跳跃的着陆位置
     * 根据舰船当前位置、朝向和速度预测最佳着陆点
     * 
     * @param ship 目标舰船
     * @return 预测的着陆位置
     */
    protected Vector2f evaluateLandLocationForStart(ShipAPI ship) {
        float dist = 0; // 跳跃距离
        float frac = 0.5f; // 地图高度比例因子

        // 战斗开始时使用较小的比例因子
        if (Global.getCombatEngine().getTotalElapsedTime(false) < 0.1f) {
            frac = 0.25f;
        }

        // 计算跳跃距离（非模拟模式下）
        if (!Global.getCombatEngine().isSimulation()) {
            // 距离 = min(地图高度*比例, min(舰船最大速度+400, 600) * 部署持续时间)
            dist = Math.min(Global.getCombatEngine().getMapHeight() * frac,
                    Math.min(ship.getMaxSpeedWithoutBoost() + 400f, 600f)
                            * (engine.getContext().getInitialDeploymentBurnDuration()));
        }

        // 计算基础位置和延伸向量
        Vector2f base = ship.getLocation(); // 当前位置
        Vector2f extend = (Vector2f) Misc.getUnitVectorAtDegreeAngle(ship.getFacing()).scale(dist); // 朝向延伸
        Vector2f predictedLandLocation = Vector2f.add(extend, base, null); // 预测位置

        // 寻找无碰撞的清晰位置
        predictedLandLocation = findClearLocation(predictedLandLocation, ship.getCollisionRadius());

        return predictedLandLocation;
    }

    /**
     * 寻找无碰撞的清晰位置
     * 从目标位置开始，以螺旋方式搜索可用的安全位置
     * 
     * @param dest      期望的目标位置
     * @param checkDist 检查距离（通常是舰船碰撞半径）
     * @return 安全的位置坐标
     */
    private static Vector2f findClearLocation(Vector2f dest, float checkDist) {
        if (isLocationClear(dest, checkDist))
            return dest;

        float incr = 50f;

        WeightedRandomPicker<Vector2f> tested = new WeightedRandomPicker<Vector2f>();
        for (float distIndex = 1; distIndex <= 32f; distIndex *= 2f) {
            float start = (float) Math.random() * 360f;
            for (float angle = start; angle < start + 360; angle += (30f + (float) Math.random() * 60f)) {
                Vector2f loc = Misc.getUnitVectorAtDegreeAngle(angle);
                loc.scale(incr * distIndex);
                Vector2f.add(dest, loc, loc);
                tested.add(loc);
                if (isLocationClear(loc, checkDist)) {
                    return loc;
                }
            }
        }

        if (tested.isEmpty())
            return dest; // shouldn't happen

        return tested.pick();
    }

    /**
     * 检查指定位置是否无碰撞
     * 检查与其他舰船和小行星的碰撞
     * 
     * @param loc       要检查的位置
     * @param checkDist 检查距离
     * @return true如果位置安全，false如果有碰撞
     */
    private static boolean isLocationClear(Vector2f loc, float checkDist) {
        // 检查与其他舰船的碰撞
        for (ShipAPI other : Global.getCombatEngine().getShips()) {
            if (other.isShuttlePod())
                continue;
            if (other.isFighter())
                continue;

            Vector2f otherLoc = other.getShieldCenterEvenIfNoShield();
            float otherR = other.getShieldRadiusEvenIfNoShield();
            if (other.isPiece()) {
                otherLoc = other.getLocation();
                otherR = other.getCollisionRadius();
            }

            float dist = Misc.getDistance(loc, otherLoc);
            float r = otherR;
            if (dist < r + checkDist) {
                return false;
            }
        }

        // 检查与小行星的碰撞
        for (CombatEntityAPI other : Global.getCombatEngine().getAsteroids()) {
            float dist = Misc.getDistance(loc, other.getLocation());
            if (dist < other.getCollisionRadius() + checkDist) {
                return false;
            }
        }

        return true;
    }



    /**
     * 更新目标位置的扭曲效果
     * IN阶段正向扭曲逐渐增强，OUT阶段反向扭曲逐渐减弱
     */
    private void updateTargetWaveDistortion(State state, float effectLevel) {
        if (targetWave == null)
            return;

        switch (state) {
            case IN:
                targetWave.flip(true);
                float inIntensity = effectLevel * 40.0F + 10.0F;
                targetWave.setLocation(ship.getLocation());
                targetWave.setSize(ship.getCollisionRadius() * (1.5F + effectLevel * 0.5F));
                targetWave.setIntensity(inIntensity);
                break;

            case ACTIVE:
                targetWave.flip(true);
                targetWave.setLocation(ship.getLocation());
                targetWave.setSize(ship.getCollisionRadius() * 2.0F);
                targetWave.setIntensity(50.0F);
                break;

            case OUT:
                targetWave.flip(false);
                float outIntensity = effectLevel * 50.0F;
                targetWave.setLocation(ship.getLocation());
                targetWave.setSize(ship.getCollisionRadius() * (2.0F - effectLevel * 0.5F));
                targetWave.setIntensity(outIntensity);

                if (effectLevel < 0.1f) {
                    targetWave.fadeOutSize(0.2F);
                    targetWave = null;
                }
                break;
        }
    }

    /**
     * 创建OUT阶段的涟漪特效
     * 参考Moci_PhaseCrossing的涟漪实现，与音效同时触发
     * 
     * @param location 特效生成位置
     */
    private void createOutPhaseRipple(Vector2f location) {
        // === 创建涟漪扭曲效果 ===
        float lifetime = 0.6F; // 涟漪持续时间
        float radius = ship.getCollisionRadius() * 4.0F; // 涟漪范围基于舰船大小

        RippleDistortion ripple = new RippleDistortion();
        ripple.setLocation(location); // 设置扭曲中心位置
        ripple.setIntensity(120.0F); // 扭曲强度
        ripple.setLifetime(lifetime); // 效果持续时间
        ripple.setFrameRate(60.0F / lifetime); // 动画帧率
        ripple.setCurrentFrame(0.0F); // 起始帧
        ripple.setSize(radius); // 扭曲范围
        ripple.fadeInSize(0.2F * lifetime); // 淡入动画时间
        ripple.fadeOutIntensity(lifetime); // 淡出动画时间
        ripple.flip(true); // 反转扭曲效果
        DistortionShader.addDistortion(ripple); // 添加到渲染管线
    }
}