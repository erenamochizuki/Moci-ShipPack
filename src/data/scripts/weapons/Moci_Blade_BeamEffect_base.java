package data.scripts.weapons;

import com.fs.starfarer.api.AnimationAPI;
import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;

import org.magiclib.util.MagicAnim;
import org.magiclib.util.MagicRender;

import java.util.List;
import java.util.ArrayList;
import com.fs.starfarer.api.graphics.SpriteAPI;

import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;

public class Moci_Blade_BeamEffect_base implements BeamEffectPlugin {

    private WeaponAPI weapon; // 武器实例
    private List<CombatEntityAPI> targets = new ArrayList<CombatEntityAPI>(); // 目标列表
    
    // 伤害累积跟踪系统
    private java.util.Map<CombatEntityAPI, Float> damageDealtThisBurst = new java.util.HashMap<>(); // 本次攻击周期已造成的伤害
    private java.util.Map<CombatEntityAPI, Float> lastBurstStartTime = new java.util.HashMap<>(); // 上次攻击周期开始时间
    private float currentBurstStartTime = 0f; // 当前攻击周期开始时间
    
    // 特效间隔控制
    private IntervalUtil effectInterval = new IntervalUtil(0.15f, 0.15f); // 特效最短间隔
    // 动画相关变量
    private float swingLevel = 0f; // 武器摆动级别
    private boolean swinging = false; // 武器是否正在摆动
    private boolean cooldown = false; // 武器是否处于冷却状态
    // 移除不必要的动画间隔计时器，直接使用amount计算时间
    private float originalArmPos = 0f; // 武器的原始位置
    // 新增变量
    private boolean charging = false; // 是否处于充能等待阶段
    private float chargeTimer = 0f;   // 充能计时
    // 移除固定的充能时间，改为动态获取
    // private final float CHARGE_DURATION = 0.33f; // 充能等待时间（可调）
    private boolean isLeftArm = false; // 当前是否是左手武器
    private boolean hasDeterminedArm = false; // 是否已经确定过左右手


    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused()) return;

        weapon = beam.getWeapon();

        handleWeaponAnimation(amount); // 调用动画处理方法

        beam.getDamage().setDamage(0); // 重置光束的伤害
        CombatEntityAPI target = beam.getDamageTarget(); // 获取光束的伤害目标

        // 如果目标不在目标列表中，则添加到目标列表
        if (!targets.contains(target)) {
            targets.add(target);
        }

        // 检测新的攻击周期开始
        if (weapon.getChargeLevel() > 0 && weapon.isFiring()) {
            if (currentBurstStartTime == 0f) {
                // 新攻击周期开始，重置伤害跟踪
                currentBurstStartTime = engine.getTotalElapsedTime(false);
                cleanupOldDamageRecords(engine.getTotalElapsedTime(false));
            }
        } else {
            // 攻击结束，重置计时器
            currentBurstStartTime = 0f;
        }

        // 如果武器正在开火且光束亮度大于等于0.7
        if (beam.getBrightness() >= 0.7f && target != null) {
            // 计算当前距离
            float currentRange = Vector2f.sub(beam.getTo(), beam.getFrom(), new Vector2f()).length();
            
            // 计算应该造成的伤害（使用优化的累积伤害系统）
            float damageToApply = calculateOptimalDamage(engine, target, currentRange);

            if (damageToApply > 0) {
                engine.applyDamage(target, beam.getTo(), damageToApply, weapon.getDamageType(), damageToApply, false, false, weapon.getShip());
                
                // 记录已造成的伤害
                recordDamageDealt(target, damageToApply, engine.getTotalElapsedTime(false));
            }
        }


        // 更新特效间隔计时器
        effectInterval.advance(amount);

        // 如果目标是 CombatEntityAPI 且光束亮度大于0.9
        if (target != null && beam.getBrightness() > .9f) {
            // 检查是否可以生成特效（间隔时间已过）
            if (effectInterval.intervalElapsed()) {
                Vector2f point = beam.getTo(); // 获取光束的终点

                Vector2f dir = Vector2f.sub(beam.getTo(), beam.getFrom(), new Vector2f()); // 计算光束方向
                if (dir.lengthSquared() > 0) dir.normalise(); // 归一化方向向量
                dir.scale(50f); // 缩放方向向量
                point = Vector2f.sub(beam.getTo(), dir, new Vector2f()); // 计算新的点

                // 如果点在屏幕范围内
                if (MagicRender.screenCheck(0.2f, point)) {
                    // 如果武器正在开火且充能大于0且小于等于1
                    if ((weapon.getChargeLevel() > 0f && weapon.getChargeLevel() < 1f || weapon.getChargeLevel() == 1f)) {
                        
                        // 计算从碰撞点到目标中心的角度（使波形垂直于舰船）
                        float waveAngle = 0f;
                        if (target != null) {
                            waveAngle = org.lazywizard.lazylib.VectorUtils.getAngle(beam.getTo(), target.getLocation());
                        }
                        
                        // 波形特效颜色（紫色系，基础光剑）
                        Color waveColor = new Color(179, 178, 178, 155);
                        
                        SpriteAPI waveSprite = Global.getSettings().getSprite("fx", "Moci_bullet_aura"); // 获取波形图形
                        if (waveSprite != null) {
                            // 渲染波形效果
                            MagicRender.battlespace(
                                    waveSprite,
                                    beam.getTo(),
                                    new Vector2f(),
                                    new Vector2f(128f, 4f),    // 初始大小（像素）
                                    new Vector2f(512f, 16f), // 最终大小（像素）
                                    waveAngle + 90f,                 // 使用计算的角度，垂直于舰船
                                    15f,
                                    waveColor,
                                    true,
                                    0f,
                                    0f,
                                    0.5f
                            );
                        }
                        
                        // 在同一位置添加撞击粒子特效，颜色与波形一致
                        Vector2f hitPoint = beam.getTo();
                        Vector2f vel = new Vector2f();
                        
                        // 主撞击粒子效果
                        for (int i = 0; i < 6; i++) {
                            vel.set(0, 0);
                            MathUtils.getRandomPointInCone(vel, 200, waveAngle - 30, waveAngle + 30);
                            Vector2f.add(vel, weapon.getShip().getVelocity(), vel);
                            
                            engine.addHitParticle(
                                hitPoint,
                                vel,
                                MathUtils.getRandomNumberInRange(3, 6),
                                1,
                                MathUtils.getRandomNumberInRange(0.1f, 0.3f),
                                new Color(179, 157, 192, 255) // 与波形颜色一致的不透明版本
                            );
                        }
                    }
                }
            }
        }
    }
    private void handleWeaponAnimation(float amount) {

        if (originalArmPos == 0f) {
            originalArmPos = weapon.getSprite().getCenterY();
        }

        float global = weapon.getShip().getFacing(); // 舰船朝向
        float currentAngle = weapon.getCurrAngle();
        
        // 只在武器开始充能且未确定左右手时判断
        if (weapon.getChargeLevel() > 0f && !hasDeterminedArm) {
            // 计算当前角度相对于舰船朝向的偏移
            float angleOffset = currentAngle - global;
            // 规范化角度到[-180,180]范围
            while (angleOffset > 180) angleOffset -= 360;
            while (angleOffset < -180) angleOffset += 360;
            
            // 根据角度偏移判断是左手还是右手
            isLeftArm = angleOffset < 0;
            hasDeterminedArm = true;
        }
        
        // 如果武器停止充能，重置左右手判断
        if (weapon.getChargeLevel() <= 0f) {
            hasDeterminedArm = false;
        }

        float arc = weapon.getArc();
        float baseAngle = global; // 基础角度就是舰船朝向
        float startAngle, targetRotation; // 起始角度和目标旋转角度

        // 计算起始角度和目标旋转角度
        if (isLeftArm) {
            startAngle = global - arc * 0.5f; // 左手武器起始角度
            targetRotation = arc; // 左手武器向右旋转整个arc
        } else {
            startAngle = global + arc * 0.5f; // 右手武器起始角度
            targetRotation = -arc; // 右手武器向左旋转整个arc
        }

        // 计算当前角度与起始角度的最短旋转
        float currentRotation = MathUtils.getShortestRotation(startAngle, currentAngle);

        // 1. 充能等待阶段
        if (!swinging && !cooldown && weapon.getChargeLevel() > 0f && !charging) {
            // 如果当前角度与起始角度重合，直接开始充能
            if (Math.abs(currentRotation) < 1f) {
                charging = true;
                chargeTimer = 0f;
                swingLevel = 0f;
            } else {
                // 否则先回到起始角度
                weapon.setCurrAngle(startAngle);
            }
        }

        // 2. 充能计时
        if (charging) {
            weapon.setCurrAngle(startAngle); // 保持在起始角度
            chargeTimer += amount;
            if (chargeTimer >= getChargeDuration()) { // 使用动态获取的充能时间
                charging = false;
                swinging = true;
                swingLevel = 0f; // 重置挥动进度
            }
        }

        // 3. 挥动阶段
        if (swinging) {
            // 挥动时长设置为充能时间+0.15秒
            float swingDuration = getChargeDuration() + 0.15f;
            float invSwingDuration = 1.0f / swingDuration; // 预计算倒数
            swingLevel += amount * invSwingDuration;
            float t = Math.min(swingLevel, 1f);
            
            // 使用tan曲线插值
            float tanNorm = (float) (Math.tan(Math.PI / 4 * t));
            float invTanQuarter = 1.0f / (float) Math.tan(Math.PI * 0.25); // 预计算倒数
            tanNorm = tanNorm * invTanQuarter; // 归一化

            // 计算新的角度：起始角度 + 目标旋转 * 效果级别
            float newRotation = targetRotation * tanNorm;
            weapon.setCurrAngle(startAngle + newRotation);

            // 挥动结束
            if (t >= 1f) {
                swinging = false;
                swingLevel = 0f;
                cooldown = true;
            }
        }

        // 4. 冷却/重置
        if (swinging && (weapon.getChargeLevel() <= 0f)) {
            swinging = false;
            swingLevel = 0f;
            cooldown = true;
        }
        if (!swinging && !charging) {
            swingLevel = 0f;
        }
        // 5. 充能被打断时重置
        if (weapon.getChargeLevel() <= 0f) {
            charging = false;
            chargeTimer = 0f;
        }
    }

    /**
     * 获取武器的充能时间，用于同步动画
     */
    private float getChargeDuration() {
        if (weapon != null && weapon.getSpec() != null) {
            // 对于光束武器，使用getBeamChargeupTime()方法
            if (weapon.getSpec() instanceof BeamWeaponSpecAPI) {
                return ((BeamWeaponSpecAPI) weapon.getSpec()).getBeamChargeupTime();
            }
        }
        return 0.33f; // 备用默认值
    }

    /**
     * 优化的伤害计算系统
     */
    private float calculateOptimalDamage(CombatEngineAPI engine, CombatEntityAPI target, float distance) {
        if (weapon == null || weapon.getShip() == null) return 0f;

        float currentTime = engine.getTotalElapsedTime(false);

        // 检查是否为新的攻击周期
        Float lastBurstTime = lastBurstStartTime.get(target);
        boolean isNewBurst = lastBurstTime == null
                || Math.abs(currentTime - currentBurstStartTime) < Math.abs(currentTime - lastBurstTime);

        if (isNewBurst) {
            // 新攻击周期，重置该目标的伤害记录
            damageDealtThisBurst.put(target, 0f);
            lastBurstStartTime.put(target, currentBurstStartTime);
        }

        // 计算基础满伤害（无衰减）
        float fullDamage = Math.max(
                weapon.getDamage().getDamage(),
                weapon.getDamage().getDamage() * 
                weapon.getSpec().getBurstDuration() * 
                (
                    weapon.getShip().getMutableStats().getEnergyWeaponDamageMult().computeMultMod() + 
                    weapon.getShip().getMutableStats().getBeamWeaponDamageMult().getPercentMod() * 0.01f
                )
        );

        // 计算当前距离的最大允许伤害（应用距离衰减）
        float baseRange = weapon.getSpec().getMaxRange();
        float distanceMultiplier = Moci_BladeEffectPlugin.calculateDistanceDamageMultiplier(distance, baseRange);
        float maxDamageAtCurrentDistance = fullDamage * distanceMultiplier;

        // 获取已造成的伤害
        Float damageDealtFloat = damageDealtThisBurst.get(target);
        float damageAlreadyDealt = (damageDealtFloat != null) ? damageDealtFloat : 0f;

        // 计算应该造成的伤害
        float damageToApply = Math.max(0f, maxDamageAtCurrentDistance - damageAlreadyDealt);

        // 确保总伤害不超过满伤害
        if (damageAlreadyDealt + damageToApply > fullDamage) {
            damageToApply = Math.max(0f, fullDamage - damageAlreadyDealt);
        }

        return damageToApply;
    }

    /**
     * 记录对目标造成的伤害
     */
    private void recordDamageDealt(CombatEntityAPI target, float damage, float currentTime) {
        Float currentDamageFloat = damageDealtThisBurst.get(target);
        float currentDamage = (currentDamageFloat != null) ? currentDamageFloat : 0f;
        damageDealtThisBurst.put(target, currentDamage + damage);
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
                damageDealtThisBurst.remove(entry.getKey());
            }
        }
    }
} 