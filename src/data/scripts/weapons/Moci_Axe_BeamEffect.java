package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.util.Misc;

import org.magiclib.util.MagicRender;
import org.magiclib.plugins.MagicTrailPlugin;

import java.util.List;
import java.util.ArrayList;
import com.fs.starfarer.api.graphics.SpriteAPI;

import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;

public class Moci_Axe_BeamEffect implements BeamEffectPlugin {

    private WeaponAPI weapon; // 武器实例
    private List<CombatEntityAPI> targets = new ArrayList<CombatEntityAPI>(); // 目标列表

    // 伤害累积跟踪系统（斧子特殊版本 - 支持两次挥砍）
    private java.util.Map<CombatEntityAPI, Float> firstSwingDamageDealt = new java.util.HashMap<>(); // 第一次挥砍已造成的伤害
    private java.util.Map<CombatEntityAPI, Float> secondSwingDamageDealt = new java.util.HashMap<>(); // 第二次挥砍已造成的伤害
    private java.util.Map<CombatEntityAPI, Float> lastBurstStartTime = new java.util.HashMap<>(); // 上次攻击周期开始时间
    private float currentBurstStartTime = 0f; // 当前攻击周期开始时间
    
    // 音效触发跟踪
    private java.util.Map<CombatEntityAPI, Float> soundPlayedThisBurst = new java.util.HashMap<>(); // 记录本次攻击周期是否已播放音效

    // 特效间隔控制
    private IntervalUtil effectInterval = new IntervalUtil(0.15f, 0.15f); // 特效最短间隔
    private IntervalUtil trailInterval = new IntervalUtil(0.05f, 0.05f); // 拖尾生成间隔

    // 拖尾效果相关
    private float bladeEdgeTrailId; // 刀锋片状拖尾ID
    private boolean trailInitialized = false; // 拖尾是否已初始化
    private SpriteAPI bladeEdgeTrailSprite; // 刀锋片状拖尾专用贴图

    // 动画相关变量
    private float swingLevel = 0f; // 武器摆动级别
    private boolean swinging = false; // 武器是否正在摆动
    private boolean cooldown = false; // 武器是否处于冷却状态
    // 移除不必要的动画间隔计时器，直接使用amount计算时间

    // 新增斧子特有的变量
    private float axePhase = 0f; // 斧子动画阶段：0为准备，1为第一次劈砍，2为第二次劈砍
    private boolean firstSwingDone = false; // 第一次劈砍是否完成
    private boolean secondSwingDone = false; // 第二次劈砍是否完成
    private float swingTimer = 0f; // 控制劈砍的计时器

    // 动态左右手判断变量（借鉴base版本的逻辑）
    private boolean isLeftArm = false; // 当前是否是左手武器
    private boolean hasDeterminedArm = false; // 是否已经确定过左右手

    // 斧子动画配置参数
    // 移除固定的挥砍时间，改为动态获取
    // private final float FIRST_SWING_DURATION = 0.4f; // 第一次劈砍持续时间
    // private final float SECOND_SWING_DURATION = 0.4f; // 第二次劈砍持续时间
    private final float PHASE_TRANSITION_PAUSE = 0.2f; // 两次劈砍之间的短暂停顿（保持不变）
    
    // 刀锋片状拖尾参数
    private static final boolean ENABLE_BLADE_EDGE_TRAIL = false; // 是否启用刀锋片状拖尾
    private static final float BLADE_EDGE_START_SPEED = 20f; // 拖尾片段初始移动速度
    private static final float BLADE_EDGE_END_SPEED = 10f; // 拖尾片段结束移动速度
    private static final float BLADE_EDGE_ANGLE_OFFSET = 90f; // 相对光束方向的角度偏移
    private static final float BLADE_EDGE_START_ANGULAR_VEL = 1f; // 初始角速度
    private static final float BLADE_EDGE_END_ANGULAR_VEL = 1f; // 结束角速度
    private static final float BLADE_EDGE_SIZE_MULT = 2f; // 起始宽度倍率（乘以光束长度）
    private static final float BLADE_EDGE_END_SIZE_MULT = 0.1f; // 结束宽度倍率（相对起始宽度）
    private static final float BLADE_EDGE_OPACITY = 1f; // 最大不透明度（0~1）
    private static final float BLADE_EDGE_IN = 0f; // 淡入时长
    private static final float BLADE_EDGE_MAIN = 0f; // 保持时长
    private static final float BLADE_EDGE_OUT = 0.5f; // 淡出时长
    private static final boolean BLADE_EDGE_ADDITIVE = true; // 是否使用加色混合
    private static final float BLADE_EDGE_TEXTURE_LOOP = 128f; // 纹理循环长度
    private static final float BLADE_EDGE_TEXTURE_SCROLL = 10f; // 纹理滚动速度
    private static final float BLADE_EDGE_TEXTURE_OFFSET = 0f; // 纹理偏移
    private static final float BLADE_EDGE_FRAME_OFFSET_MULT = 1f; // 每帧位移修正倍率
    private static final float BLADE_EDGE_SHIP_VEL_MULT = 1f; // 继承舰船速度倍率
    private static final CombatEngineLayers BLADE_EDGE_LAYER = CombatEngineLayers.UNDER_SHIPS_LAYER; // 渲染层
    private static final Color DEFAULT_EFFECT_COLOR = new Color(236, 131, 75, 255); // glowColor 缺失时的保底色

    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused())
            return;

        weapon = beam.getWeapon();

        if (!trailInitialized) {
            bladeEdgeTrailId = MagicTrailPlugin.getUniqueID();
            bladeEdgeTrailSprite = Global.getSettings().getSprite("fx", "Moci_blade_base_trail");
            trailInitialized = true;
        }

        beam.getDamage().setDamage(0); // 重置光束的伤害

        CombatEntityAPI target = beam.getDamageTarget(); // 获取光束的伤害目标

        // 检测新的攻击周期开始
        if (weapon.getChargeLevel() > 0 && weapon.isFiring()) {
            if (currentBurstStartTime == 0f) {
                // 新攻击周期开始，重置伤害跟踪和音效跟踪
                currentBurstStartTime = engine.getTotalElapsedTime(false);
                cleanupOldDamageRecords(engine.getTotalElapsedTime(false));
            }
        } else {
            // 攻击结束，重置计时器
            currentBurstStartTime = 0f;
        }

        // 如果目标不在目标列表中，则添加到目标列表
        if (!targets.contains(target)) {
            targets.add(target);
        }

        // 如果武器正在开火且光束亮度大于等于0.7
        if (beam.getBrightness() >= 0.7f && target != null) {
            // 计算当前距离
            float currentRange = Vector2f.sub(beam.getTo(), beam.getFrom(), new Vector2f()).length();

            // 判断当前是第一次挥砍还是第二次挥砍
            boolean isFirstSwing = !firstSwingDone; // 第一次挥砍未完成
            boolean isSecondSwing = firstSwingDone && !secondSwingDone; // 第一次完成但第二次未完成

            float damageToApply = 0f;

            if (isFirstSwing) {
                // 第一次挥砍：使用优化的累积伤害系统
                damageToApply = calculateOptimalAxeDamage(engine, target, currentRange, true);
            } else if (isSecondSwing) {
                // 第二次挥砍：使用优化的累积伤害系统，但考虑第一次挥砍的影响
                damageToApply = calculateOptimalAxeDamage(engine, target, currentRange, false);
            }

            if (damageToApply > 0) {
                engine.applyDamage(target, beam.getTo(), damageToApply, weapon.getDamageType(), damageToApply, false,
                        false, weapon.getShip());

                // 记录已造成的伤害
                if (isFirstSwing) {
                    recordAxeDamageDealt(target, damageToApply, true, engine.getTotalElapsedTime(false));
                } else if (isSecondSwing) {
                    recordAxeDamageDealt(target, damageToApply, false, engine.getTotalElapsedTime(false));
                }
            }
        }

        // 处理武器动画
        if (weapon.getSlot().getId().endsWith("_L_SOURCE") || weapon.getSlot().getId().endsWith("_R_SOURCE")) {
            handleWeaponAnimation(amount); // 调用动画处理方法
        }

        // 更新特效间隔计时器
        effectInterval.advance(amount);

        if (weapon.isFiring()) {
            trailInterval.advance(amount);
            if (trailInterval.intervalElapsed() && beam.getBrightness() == 1f) {
                addBladeEdgeTrail(weapon.getShip(), beam);
            }
        }

        // 如果目标是 CombatEntityAPI 且光束亮度大于0.9
        if (target instanceof CombatEntityAPI && beam.getBrightness() > .9f) {
            // 检查是否可以生成特效（间隔时间已过）
            if (effectInterval.intervalElapsed()) {
                Vector2f point = beam.getTo(); // 获取光束的终点

                Vector2f dir = Vector2f.sub(beam.getTo(), beam.getFrom(), new Vector2f()); // 计算光束方向
                if (dir.lengthSquared() > 0)
                    dir.normalise(); // 归一化方向向量
                dir.scale(50f); // 缩放方向向量
                point = Vector2f.sub(beam.getTo(), dir, new Vector2f()); // 计算新的点

                // 如果点在屏幕范围内
                if (MagicRender.screenCheck(0.2f, point)) {
                    // 如果武器正在开火且充能大于0且小于等于1
                    if ((weapon.getChargeLevel() > 0f && weapon.getChargeLevel() < 1f
                            || weapon.getChargeLevel() == 1f)) {

                        // 计算从碰撞点到目标中心的角度（使波形垂直于舰船）
                        float waveAngle = 0f;
                        if (target != null) {
                            waveAngle = org.lazywizard.lazylib.VectorUtils.getAngle(beam.getTo(), target.getLocation());
                        }
                        
                        /* 
                        // 波形特效颜色
                        Color waveColor = new Color(255, 211, 146, 155);

                        SpriteAPI waveSprite = Global.getSettings().getSprite("fx", "Moci_bullet_aura"); // 获取波形精灵
                        if (waveSprite != null) {
                            // 渲染波形效果
                            MagicRender.battlespace(
                                    waveSprite,
                                    beam.getTo(),
                                    new Vector2f(),
                                    new Vector2f(128f, 4f), // 初始大小（像素）
                                    new Vector2f(512f, 16f), // 最终大小（像素）
                                    waveAngle, // 使用计算的角度，垂直于舰船
                                    15f,
                                    waveColor,
                                    true,
                                    0f,
                                    0f,
                                    0.5f);
                        }
                        */

                        // 在同一位置添加撞击粒子特效，颜色与波形一致
                        Vector2f hitPoint = beam.getTo();
                        Vector2f vel = new Vector2f();
                        Color effectColor = DEFAULT_EFFECT_COLOR;

                        // 主撞击粒子效果
                        for (int i = 0; i < 6; i++) {
                            vel.set(0, 0);
                            MathUtils.getRandomPointInCone(vel, 100, waveAngle - 30, waveAngle + 30);
                            Vector2f.add(vel, weapon.getShip().getVelocity(), vel);

                            engine.addHitParticle(
                                    hitPoint,
                                    vel,
                                    5,
                                    1,
                                    MathUtils.getRandomNumberInRange(0.3f, 6f),
                                    effectColor // 使用武器 glowColor，避免透明 beam 导致特效颜色丢失
                            );
                        }

                        // 播放斩切声音 - 每个目标每次攻击周期只播放一次
                        if (shouldPlaySound(target, engine.getTotalElapsedTime(false))) {
                            Global.getSoundPlayer().playSound("Moci_BeamSabre_Hit", 1.0f, 0.5f, point, new Vector2f());
                            recordSoundPlayed(target, engine.getTotalElapsedTime(false));
                        }
                    }
                }
            }
        }
    }

    private void handleWeaponAnimation(float amount) {
        float global = weapon.getShip().getFacing(); // 舰船朝向
        float currentAngle = weapon.getCurrAngle();

        // 动态判断左右手（借鉴base版本的逻辑）
        // 只在武器开始充能且未确定左右手时判断
        if (weapon.getChargeLevel() > 0f && !hasDeterminedArm) {
            // 计算当前角度相对于舰船朝向的偏移
            float angleOffset = currentAngle - global;
            // 规范化角度到[-180,180]范围
            while (angleOffset > 180)
                angleOffset -= 360;
            while (angleOffset < -180)
                angleOffset += 360;

            // 根据角度偏移判断是左手还是右手
            this.isLeftArm = angleOffset < 0;
            hasDeterminedArm = true;
        }

        // 如果武器停止充能，重置左右手判断
        if (weapon.getChargeLevel() <= 0f) {
            hasDeterminedArm = false;
        }

        float arc = weapon.getArc();
        float baseAngle = global; // 基础角度就是舰船朝向
        float leftmostAngle, rightmostAngle; // 最左和最右的角度

        // 计算最左和最右的角度边界
        leftmostAngle = global - arc * 0.5f;
        rightmostAngle = global + arc * 0.5f;

        // 武器充能状态判断
        if (weapon.getChargeLevel() > 0f) {
            // 如果武器刚刚开始充能，重置所有状态
            if (!swinging && !cooldown) {
                // 检查当前角度与起始角度的距离
                float currentRotation = MathUtils.getShortestRotation(leftmostAngle, currentAngle);

                // 如果当前角度与起始角度差距较大，先平滑移动到起始位置
                if (Math.abs(currentRotation) > 5f) {
                    // 平滑移动到起始角度
                    float newRotation = lerp(currentRotation, 0f, 0.15f);
                    weapon.setCurrAngle(leftmostAngle + newRotation);
                    return; // 等待下一帧继续移动
                }

                // 角度足够接近起始位置时，开始动画
                swinging = true;
                cooldown = false;
                axePhase = 0f;
                firstSwingDone = false;
                secondSwingDone = false;
                swingTimer = 0f;
                // 清空之前的伤害记录，开始新的攻击
                firstSwingDamageDealt.clear();
                secondSwingDamageDealt.clear();
                // 确保武器角度精确设置到最左侧
                weapon.setCurrAngle(leftmostAngle);
            }

            // 动画控制
            if (swinging) {
                swingTimer += amount;

                // 第一阶段：从左向右劈砍
                if (!firstSwingDone) {
                    float invSwingDuration = 1.0f / getSwingDuration(); // 预计算倒数
                    float progress = Math.min(swingTimer * invSwingDuration, 1.0f); // 使用动态时间
                    // 使用加速曲线 - 逐渐加速到最快，然后缓慢减速
                    float easedProgress = smoothStep(0f, 1f, progress);

                    // 计算当前角度 - 从最左侧向最右侧移动
                    float newAngle = leftmostAngle + easedProgress * (rightmostAngle - leftmostAngle);
                    weapon.setCurrAngle(newAngle);

                    // 检查是否完成第一次劈砍
                    if (progress >= 1.0f) {
                        firstSwingDone = true;
                        swingTimer = 0f; // 重置计时器为第二阶段
                    }
                }
                // 两次劈砍之间的过渡暂停
                else if (firstSwingDone && !secondSwingDone && swingTimer < PHASE_TRANSITION_PAUSE) {
                    // 保持在最右侧
                    weapon.setCurrAngle(rightmostAngle);
                }
                // 第二阶段：从右向左劈砍
                else if (firstSwingDone && !secondSwingDone) {
                    float invSwingDuration2 = 1.0f / getSwingDuration(); // 预计算倒数
                    float progress = Math.min((swingTimer - PHASE_TRANSITION_PAUSE) * invSwingDuration2, 1.0f); // 使用动态时间
                    if (progress >= 0) { // 确保已经过了暂停期
                        // 使用加速曲线
                        float easedProgress = smoothStep(0f, 1f, progress);

                        // 计算当前角度 - 从最右侧向最左侧移动
                        float newAngle2 = rightmostAngle - easedProgress * (rightmostAngle - leftmostAngle);
                        weapon.setCurrAngle(newAngle2);

                        // 检查是否完成第二次劈砍
                        if (progress >= 1.0f) {
                            secondSwingDone = true;
                            swingTimer = 0f;
                            cooldown = true;
                            swinging = false;
                        }
                    }
                }
            }
        } else {
            // 如果武器不在充能状态，逐渐恢复到空闲角度 (基础角度)
            float currentRotation = MathUtils.getShortestRotation(baseAngle, weapon.getCurrAngle());
            float newRotation = currentRotation * 0.9f; // 平滑恢复
            weapon.setCurrAngle(baseAngle + newRotation);

            // 重置状态
            swinging = false;
            cooldown = false;
            firstSwingDone = false;
            secondSwingDone = false;
            swingTimer = 0f;
            // 当武器完全停止时也清空伤害记录
            firstSwingDamageDealt.clear();
            secondSwingDamageDealt.clear();
        }
    }

    // 平滑过渡函数，使运动显得更自然
    private float smoothStep(float edge0, float edge1, float x) {
        // 限制x在[0,1]范围内
        x = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        // 应用平滑函数: 3x^2 - 2x^3
        return x * x * (3 - 2 * x);
    }

    // 线性插值函数
    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * 获取武器的充能时间，用于同步挥砍动画
     */
    private float getSwingDuration() {
        if (weapon != null && weapon.getSpec() != null) {
            // 对于光束武器，使用getBeamChargeupTime()方法
            if (weapon.getSpec() instanceof BeamWeaponSpecAPI) {
                float chargeTime = ((BeamWeaponSpecAPI) weapon.getSpec()).getBeamChargeupTime();
                return chargeTime;
            }
        }
        return 0.4f; // 备用默认值
    }

    /**
     * 斧子专用的优化伤害计算系统
     * 支持两次挥砍的累积伤害逻辑
     */
    private float calculateOptimalAxeDamage(CombatEngineAPI engine, CombatEntityAPI target, float distance,
            boolean isFirstSwing) {
        if (weapon == null || weapon.getShip() == null)
            return 0f;

        float currentTime = engine.getTotalElapsedTime(false);

        // 检查是否为新的攻击周期
        Float lastBurstTime = lastBurstStartTime.get(target);
        boolean isNewBurst = lastBurstTime == null
                || Math.abs(currentTime - currentBurstStartTime) < Math.abs(currentTime - lastBurstTime);

        if (isNewBurst) {
            // 新攻击周期，重置该目标的伤害记录
            firstSwingDamageDealt.put(target, 0f);
            secondSwingDamageDealt.put(target, 0f);
            lastBurstStartTime.put(target, currentBurstStartTime);
        }

        // 计算基础满伤害（无衰减）
        float baseDamage = weapon.getDamage().getDamage();
        float burstDuration = weapon.getSpec().getBurstDuration();
        float ballisticMult = weapon.getShip().getMutableStats().getBallisticWeaponDamageMult().computeMultMod() +
                weapon.getShip().getMutableStats().getBallisticWeaponDamageMult().getPercentMod() * 0.01f;
        float energyMult = weapon.getShip().getMutableStats().getEnergyWeaponDamageMult().computeMultMod() +
                weapon.getShip().getMutableStats().getBeamWeaponDamageMult().getPercentMod() * 0.01f;

        float fullDamage = Math.max(baseDamage, baseDamage * burstDuration * ballisticMult * energyMult);

        // 根据舰船的幅能伤害加成调整伤害
        float mag = weapon.getShip().getFluxBasedEnergyWeaponDamageMultiplier() - 1f;
        if (mag > 0)
            fullDamage = fullDamage * (1 + mag);

        // 计算当前距离的最大允许伤害（应用距离衰减）
        float baseRange = weapon.getSpec().getMaxRange();
        float distanceMultiplier = Moci_BladeEffectPlugin.calculateDistanceDamageMultiplier(distance, baseRange);
        float maxDamageAtCurrentDistance = fullDamage * distanceMultiplier;

        // 获取已造成的伤害
        Float firstSwingDealtFloat = firstSwingDamageDealt.get(target);
        Float secondSwingDealtFloat = secondSwingDamageDealt.get(target);
        float firstSwingDealt = (firstSwingDealtFloat != null) ? firstSwingDealtFloat : 0f;
        float secondSwingDealt = (secondSwingDealtFloat != null) ? secondSwingDealtFloat : 0f;

        float damageToApply = 0f;

        if (isFirstSwing) {
            // 第一次挥砍：计算应该造成的伤害
            damageToApply = Math.max(0f, maxDamageAtCurrentDistance - firstSwingDealt);

            // 确保第一次挥砍总伤害不超过满伤害
            if (firstSwingDealt + damageToApply > fullDamage) {
                damageToApply = Math.max(0f, fullDamage - firstSwingDealt);
            }
        } else {
            // 第二次挥砍：考虑第一次挥砍的影响
            float totalDealt = firstSwingDealt + secondSwingDealt;

            // 如果目标在第一次挥砍中被击中过，第二次挥砍伤害减半
            float secondSwingMaxDamage = maxDamageAtCurrentDistance;
            if (firstSwingDealt > 0) {
                secondSwingMaxDamage *= 0.5f; // 减半伤害
            }

            damageToApply = Math.max(0f, secondSwingMaxDamage - secondSwingDealt);

            // 确保总伤害不超过满伤害
            if (totalDealt + damageToApply > fullDamage) {
                damageToApply = Math.max(0f, fullDamage - totalDealt);
            }
        }

        return damageToApply;
    }

    /**
     * 记录斧子的伤害
     */
    private void recordAxeDamageDealt(CombatEntityAPI target, float damage, boolean isFirstSwing, float currentTime) {
        if (isFirstSwing) {
            Float currentDamageFloat = firstSwingDamageDealt.get(target);
            float currentDamage = (currentDamageFloat != null) ? currentDamageFloat : 0f;
            firstSwingDamageDealt.put(target, currentDamage + damage);
        } else {
            Float currentDamageFloat = secondSwingDamageDealt.get(target);
            float currentDamage = (currentDamageFloat != null) ? currentDamageFloat : 0f;
            secondSwingDamageDealt.put(target, currentDamage + damage);
        }
        lastBurstStartTime.put(target, currentBurstStartTime);
    }

    /**
     * 清理过期的伤害记录
     */
    private void cleanupOldDamageRecords(float currentTime) {
        // 清理超过3秒的旧记录，避免内存泄漏
        float cleanupThreshold = 3.0f;

        java.util.Iterator<java.util.Map.Entry<CombatEntityAPI, Float>> iter = lastBurstStartTime.entrySet().iterator();
        while (iter.hasNext()) {
            java.util.Map.Entry<CombatEntityAPI, Float> entry = iter.next();
            if (currentTime - entry.getValue() > cleanupThreshold) {
                iter.remove();
                firstSwingDamageDealt.remove(entry.getKey());
                secondSwingDamageDealt.remove(entry.getKey());
                soundPlayedThisBurst.remove(entry.getKey());
            }
        }
    }
    
    /**
     * 检查是否应该播放音效
     */
    private boolean shouldPlaySound(CombatEntityAPI target, float currentTime) {
        Float lastSoundTime = soundPlayedThisBurst.get(target);
        if (lastSoundTime == null) {
            return true; // 从未播放过
        }
        
        // 检查是否为新的攻击周期
        Float lastBurstTime = lastBurstStartTime.get(target);
        boolean isNewBurst = lastBurstTime == null
                || Math.abs(currentTime - currentBurstStartTime) < Math.abs(currentTime - lastBurstTime);
        
        return isNewBurst; // 新攻击周期才播放
    }
    
    /**
     * 记录音效已播放
     */
    private void recordSoundPlayed(CombatEntityAPI target, float currentTime) {
        soundPlayedThisBurst.put(target, currentTime);
    }

    /**
     * 在光束末端生成一片沿光束方向展开的“刀锋片状拖尾”。
     */
    private void addBladeEdgeTrail(ShipAPI ship, BeamAPI beam) {
        if (!ENABLE_BLADE_EDGE_TRAIL || ship == null || beam == null || bladeEdgeTrailSprite == null) {
            return;
        }

        Vector2f start = beam.getFrom();
        Vector2f end = beam.getTo();
        if (start == null || end == null || !MagicRender.screenCheck(0.2f, end)) {
            return;
        }

        float startSize = Misc.getDistance(start, end) * BLADE_EDGE_SIZE_MULT;
        if (startSize <= 1f) {
            return;
        }

        float angle = Misc.getAngleInDegrees(start, end) + BLADE_EDGE_ANGLE_OFFSET;
        Vector2f offsetVelocity = new Vector2f(
                ship.getVelocity().x * BLADE_EDGE_SHIP_VEL_MULT,
                ship.getVelocity().y * BLADE_EDGE_SHIP_VEL_MULT
        );
        Color startColor = DEFAULT_EFFECT_COLOR;
        Color endColor = new Color(
                DEFAULT_EFFECT_COLOR.getRed(),
                DEFAULT_EFFECT_COLOR.getGreen(),
                DEFAULT_EFFECT_COLOR.getBlue(),
                180
        );

        MagicTrailPlugin.addTrailMemberAdvanced(
                ship,
                bladeEdgeTrailId,
                bladeEdgeTrailSprite,
                end,
                BLADE_EDGE_START_SPEED,
                BLADE_EDGE_END_SPEED,
                angle,
                BLADE_EDGE_START_ANGULAR_VEL,
                BLADE_EDGE_END_ANGULAR_VEL,
                startSize,
                startSize * BLADE_EDGE_END_SIZE_MULT,
                startColor,
                endColor,
                BLADE_EDGE_OPACITY,
                BLADE_EDGE_IN,
                BLADE_EDGE_MAIN,
                BLADE_EDGE_OUT,
                BLADE_EDGE_ADDITIVE,
                BLADE_EDGE_TEXTURE_LOOP,
                BLADE_EDGE_TEXTURE_SCROLL,
                BLADE_EDGE_TEXTURE_OFFSET,
                offsetVelocity,
                null,
                BLADE_EDGE_LAYER,
                BLADE_EDGE_FRAME_OFFSET_MULT
        );
    }
}
