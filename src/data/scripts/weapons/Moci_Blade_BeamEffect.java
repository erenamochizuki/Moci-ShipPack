package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import org.magiclib.util.MagicRender;
import org.magiclib.plugins.MagicTrailPlugin;

import java.util.List;
import java.util.ArrayList;
import com.fs.starfarer.api.graphics.SpriteAPI;

import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;

/**
 * Moci光剑光束效果插件
 * 
 * 功能：
 * 1. 伤害系统：优化的累积伤害计算
 * 2. 动画系统：挥剑动画控制
 * 3. 视觉特效：
 *    - 拖尾效果
 *    - 命中粒子（三层）
 *    - 光束沿线粒子
 *    - 星云粒子
 *    - 波形冲击
 */
public class Moci_Blade_BeamEffect implements BeamEffectPlugin {

    private WeaponAPI weapon; // 武器实例
    private List<CombatEntityAPI> targets = new ArrayList<CombatEntityAPI>(); // 目标列表

    // ========== 伤害累积跟踪系统 ==========
    private java.util.Map<CombatEntityAPI, Float> damageDealtThisBurst = new java.util.HashMap<>();
    private java.util.Map<CombatEntityAPI, Float> lastBurstStartTime = new java.util.HashMap<>();
    private float currentBurstStartTime = 0f;
    
    // ========== 音效触发跟踪 ==========
    private java.util.Map<CombatEntityAPI, Float> soundPlayedThisBurst = new java.util.HashMap<>(); // 记录本次攻击周期是否已播放音效

    // ========== 动画相关变量 ==========
    private float swingLevel = 0f; // 武器摆动级别
    private boolean swinging = false; // 武器是否正在摆动
    private boolean cooldown = false; // 武器是否处于冷却状态
    private float originalArmPos = 0f; // 武器的原始位置
    private boolean charging = false; // 是否处于充能等待阶段
    private float chargeTimer = 0f; // 充能计时

    // ========== 特效间隔控制 ==========
    private IntervalUtil effectInterval = new IntervalUtil(0.15f, 0.15f); // 波形特效间隔
    private IntervalUtil trailInterval = new IntervalUtil(0.05f, 0.05f); // 拖尾生成间隔
    
    // ========== 拖尾效果相关 ==========
    private float trailId; // 拖尾效果ID
    private float bladeEdgeTrailId; // 刀锋片状拖尾ID
    private boolean trailInitialized = false; // 拖尾是否已初始化
    private SpriteAPI trailSprite; // 复用拖尾贴图，避免每帧重复取资源
    private SpriteAPI bladeEdgeTrailSprite; // 刀锋片状拖尾专用贴图

    // ========== 刀锋片状拖尾参数 ==========
    private static final boolean ENABLE_BLADE_EDGE_TRAIL = true; // 是否启用刀锋片状拖尾
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
    
    // ========== 粒子效果参数 ==========
    /** 粒子颜色（金黄色） */
    private static final Color PARTICLE_COLOR = new Color(214, 156, 56, 200);
    
    /** 粒子基础尺寸 */
    private static final float PARTICLE_SIZE = 8f;
    
    /** 粒子亮度 */
    private static final float PARTICLE_BRIGHTNESS = 150f;
    
    /** 粒子持续时间（秒） */
    private static final float PARTICLE_DURATION = 0.8f;
    
    /** 每次生成的粒子数量 */
    private static final int PARTICLE_COUNT = 1;
    
    /** 粒子最小尺寸 */
    private static final float PARTICLE_SIZE_MIN = 1f;
    
    /** 粒子最大尺寸 */
    private static final float PARTICLE_SIZE_MAX = 3f;
    
    /** 粒子最短持续时间 */
    private static final float PARTICLE_DURATION_MIN = 0.4f;
    
    /** 粒子最长持续时间 */
    private static final float PARTICLE_DURATION_MAX = 0.9f;
    
    /** 粒子惯性系数（0=不继承速度，1=完全继承舰船速度） */
    private static final float PARTICLE_INERTIA_MULT = 0.5f;
    
    /** 粒子随机漂移速度上限 */
    private static final float PARTICLE_DRIFT = 50f;
    
    /** 粒子密度（每平方单位每秒生成的粒子数） */
    private static final float PARTICLE_DENSITY = 0.15f;
    
    /** 粒子生成宽度系数（相对于光束宽度） */
    private static final float PARTICLE_SPAWN_WIDTH_MULT = 0.1f;
    
    // ========== 粒子喷射几何参数 ==========
    /** 粒子喷射锥角（度） */
    private static final float CONE_ANGLE = 150f;
    
    /** 粒子速度下限系数 */
    private static final float VEL_MIN = 0.1f;
    
    /** 粒子速度上限系数 */
    private static final float VEL_MAX = 0.3f;
    
    /** 半锥角（内部计算用） */
    private static final float A_2 = CONE_ANGLE / 2;
    
    /** 是否已开始粒子生成 */
    private boolean particleSystemActive = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused())
            return;

        weapon = beam.getWeapon();
        ShipAPI ship = weapon.getShip();

        // ========== 初始化拖尾效果ID ==========
        if (!trailInitialized) {
            trailId = MagicTrailPlugin.getUniqueID();
            bladeEdgeTrailId = MagicTrailPlugin.getUniqueID();
            trailSprite = Global.getSettings().getSprite("fx", "base_trail_smooth");
            bladeEdgeTrailSprite = Global.getSettings().getSprite("fx", "Moci_blade_base_trail");
            trailInitialized = true;
        }
        
        // ========== 充能完成后启动粒子生成 ==========
        if (weapon.getChargeLevel() >= 1f) {
            if (!particleSystemActive) {
                particleSystemActive = true;
            }
        }

        beam.getDamage().setDamage(0); // 重置光束的伤害

        CombatEntityAPI target = beam.getDamageTarget(); // 获取光束的伤害目标

        // ========== 检测新的攻击周期开始 ==========
        if (weapon.getChargeLevel() > 0 && weapon.isFiring()) {
            if (currentBurstStartTime == 0f) {
                currentBurstStartTime = engine.getTotalElapsedTime(false);
                cleanupOldDamageRecords(engine.getTotalElapsedTime(false));
            }
        } else {
            currentBurstStartTime = 0f;
        }

        // 记录所有接触过的目标
        if (!targets.contains(target)) {
            targets.add(target);
        }

        // ========== 伤害处理 ==========
        if (beam.getBrightness() >= 0.7f && target != null) {
            float currentRange = Vector2f.sub(beam.getTo(), beam.getFrom(), new Vector2f()).length();
            float damageToApply = calculateOptimalDamage(engine, target, currentRange);

            if (damageToApply > 0) {
                engine.applyDamage(target, beam.getTo(), damageToApply, weapon.getDamageType(), damageToApply, false,
                        false, weapon.getShip());
                recordDamageDealt(target, damageToApply, engine.getTotalElapsedTime(false));
            }
        }

        // ========== 处理武器动画 ==========
        if (weapon.getSlot().getId().endsWith("_L_SOURCE") || weapon.getSlot().getId().endsWith("_R_SOURCE")) {
            handleWeaponAnimation(amount);
        }

        // ========== 更新特效间隔计时器 ==========
        effectInterval.advance(amount);
        
        // ========== 武器开火时的额外粒子效果 ==========
        if (weapon.isFiring()) {
            // 25%概率在光束起点生成火花粒子
            if (Math.random() >= 0.75f && beam.getBrightness() >= 0.8f) {
                for (int x = 0; x < 2; x++) {
                    engine.addHitParticle(
                        beam.getFrom(),
                        MathUtils.getPointOnCircumference(
                            ship.getVelocity(),
                            MathUtils.getRandomNumberInRange(100f, 150f),
                            MathUtils.getRandomNumberInRange(weapon.getCurrAngle() - 30f, weapon.getCurrAngle() + 30f)
                        ),
                        5f,
                        1f,
                        MathUtils.getRandomNumberInRange(0.1f, 0.6f),
                        beam.getFringeColor()
                    );
                }
            }
            
            // ========== 拖尾效果 ==========
            trailInterval.advance(amount);
            
            if (trailInterval.intervalElapsed() && beam.getBrightness() == 1f) {
                float angle = weapon.getCurrAngle() - 90f;
                
                // 只在屏幕可见范围内生成拖尾
                if (MagicRender.screenCheck(0.2f, beam.getFrom())) {
                    Vector2f midpoint = new Vector2f(
                        (beam.getFrom().x + beam.getTo().x) / 2f,
                        (beam.getFrom().y + beam.getTo().y) / 2f
                    );
                    
                    MagicTrailPlugin.addTrailMemberAdvanced(
                        ship,
                        trailId,
                        trailSprite,
                        midpoint,
                        0f,
                        0f,
                        angle,
                        ship.getAngularVelocity(),
                        0f,
                        beam.getLength() * 2,
                        beam.getLength() * 2,
                        beam.getCoreColor(),
                        beam.getFringeColor(),
                        1f,
                        amount,
                        amount,
                        0.1f,
                        true,
                        256f,
                        0f,
                        0f,
                        null,
                        null,
                        null,
                        2f
                    );
                }

                // 你提供的“beam末端片状拖尾”效果（做成可调参数版）
                addBladeEdgeTrail(ship, beam);
            }
        }

        // ========== 命中目标时的效果 ==========
        if (target instanceof CombatEntityAPI) {
            Vector2f point = beam.getTo();
            
            if (MagicRender.screenCheck(0.2f, point)) {
                Color color = beam.getFringeColor();
                Color core = beam.getCoreColor();
                Color particolor = Math.random() > 0.50f ? color : PARTICLE_COLOR;
                
                // ========== 命中粒子效果（三层粒子） ==========
                if (beam.getBrightness() > 0.9f) {
                    for (int i = 0; i <= PARTICLE_COUNT; i++) {
                        point = beam.getTo();
                        float speed = 500f;
                        float facing = weapon.getCurrAngle();
                        
                        // 第一组粒子：命中火花
                        float radius = 10f + (weapon.getChargeLevel() * weapon.getChargeLevel() * 
                                             MathUtils.getRandomNumberInRange(25f, 75f));
                        float angle = MathUtils.getRandomNumberInRange(facing - A_2, facing + A_2);
                        float vel = MathUtils.getRandomNumberInRange(speed * -VEL_MIN, speed * -VEL_MAX);
                        Vector2f vector = MathUtils.getPointOnCircumference(null, vel, angle);
                        
                        engine.addHitParticle(
                            beam.getTo(),
                            vector,
                            PARTICLE_SIZE,
                            PARTICLE_BRIGHTNESS,
                            PARTICLE_DURATION,
                            particolor
                        );
                        
                        // 第二组粒子：平滑粒子
                        radius = 10f + (weapon.getChargeLevel() * weapon.getChargeLevel() * 
                                       MathUtils.getRandomNumberInRange(25f, 75f));
                        angle = MathUtils.getRandomNumberInRange(facing - A_2, facing + A_2);
                        vel = MathUtils.getRandomNumberInRange(speed * -VEL_MIN, speed * -VEL_MAX);
                        vector = MathUtils.getPointOnCircumference(null, vel, angle);
                        
                        engine.addSmoothParticle(
                            beam.getTo(),
                            vector,
                            radius,
                            0.1f + weapon.getChargeLevel() * 0.25f,
                            0.1f,
                            color
                        );
                        
                        // 第三组粒子：核心光晕
                        radius = 10f + (weapon.getChargeLevel() * weapon.getChargeLevel() * 
                                       MathUtils.getRandomNumberInRange(25f, 75f));
                        angle = MathUtils.getRandomNumberInRange(facing - A_2, facing + A_2);
                        vel = MathUtils.getRandomNumberInRange(speed * -VEL_MIN, speed * -VEL_MAX);
                        vector = MathUtils.getPointOnCircumference(null, vel, angle);
                        
                        engine.addHitParticle(
                            beam.getTo(),
                            vector,
                            radius * 0.70f,
                            0.1f + weapon.getChargeLevel() * 0.1f,
                            0.1f,
                            core
                        );
                    }
                }
                
                // ========== 原有的波形特效 ==========
                if (beam.getBrightness() > .9f && effectInterval.intervalElapsed()) {
                    Vector2f dir = Vector2f.sub(beam.getTo(), beam.getFrom(), new Vector2f());
                    if (dir.lengthSquared() > 0)
                        dir.normalise();
                    dir.scale(50f);
                    point = Vector2f.sub(beam.getTo(), dir, new Vector2f());

                    if ((weapon.getChargeLevel() > 0f && weapon.getChargeLevel() < 1f
                            || weapon.getChargeLevel() == 1f)) {

                        // 计算从碰撞点到目标中心的角度（使波形垂直于舰船）
                        float waveAngle = 0f;
                        if (target != null) {
                            waveAngle = org.lazywizard.lazylib.VectorUtils.getAngle(beam.getTo(), target.getLocation());
                        }

                        /*
                        // 波形特效颜色（紫色系，标准光剑）
                        Color waveColor = new Color(169, 147, 182, 155);

                        SpriteAPI waveSprite = Global.getSettings().getSprite("fx", "Moci_bullet_aura");
                        if (waveSprite != null) {
                            MagicRender.battlespace(
                                    waveSprite,
                                    beam.getTo(),
                                    new Vector2f(),
                                    new Vector2f(128f, 4f),
                                    new Vector2f(512f, 16f),
                                    waveAngle + 90f,
                                    15f,
                                    waveColor,
                                    true,
                                    0f,
                                    0f,
                                    0.5f);
                        } 
                        */

                        // 在同一位置添加撞击粒子特效
                        Vector2f hitPoint = beam.getTo();
                        Vector2f vel = new Vector2f();

                        for (int i = 0; i < 6; i++) {
                            vel.set(0, 0);
                            MathUtils.getRandomPointInCone(vel, 200, waveAngle - 30, waveAngle + 30);
                            Vector2f.add(vel, weapon.getShip().getVelocity(), vel);

                            engine.addHitParticle(
                                    hitPoint,
                                    vel,
                                    4,
                                    1,
                                    MathUtils.getRandomNumberInRange(0.1f, 0.3f),
                                    new Color(179, 157, 192, 255)
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
        
        // ========== 光束沿线粒子效果 ==========
        Vector2f spawnPoint = MathUtils.getRandomPointOnLine(beam.getFrom(), beam.getTo());
        if (MagicRender.screenCheck(0.2f, spawnPoint)) {
            if (particleSystemActive) {
                float beamWidth = beam.getWidth();
                
                // 计算本帧应生成的粒子数量
                float particleCount = beamWidth * PARTICLE_SPAWN_WIDTH_MULT * 
                                     MathUtils.getDistance(beam.getTo(), beam.getFrom()) * 
                                     amount * PARTICLE_DENSITY * weapon.getChargeLevel();
                
                // 生成粒子
                for (int i = 0; i < particleCount; i++) {
                    // 在光束宽度范围内随机生成位置
                    spawnPoint = MathUtils.getRandomPointInCircle(spawnPoint, beamWidth * PARTICLE_SPAWN_WIDTH_MULT);
                    
                    // 如果粒子位置离屏幕太远，跳过
                    if (!Global.getCombatEngine().getViewport().isNearViewport(spawnPoint, PARTICLE_SIZE_MAX * 3f)) {
                        continue;
                    }
                    
                    // 计算粒子速度（继承舰船速度 + 随机漂移）
                    Vector2f velocity = new Vector2f(
                        ship.getVelocity().x * PARTICLE_INERTIA_MULT,
                        ship.getVelocity().y * PARTICLE_INERTIA_MULT
                    );
                    velocity = MathUtils.getRandomPointInCircle(velocity, PARTICLE_DRIFT);
                    
                    // 5%概率生成星云粒子（大型粒子）
                    if ((float) Math.random() <= 0.05f) {
                        engine.addNebulaParticle(
                            spawnPoint,
                            velocity,
                            40f * (0.75f + (float) Math.random() * 0.5f),
                            MathUtils.getRandomNumberInRange(1.0f, 3f),
                            0f,
                            0f,
                            1f,
                            new Color(
                                beam.getFringeColor().getRed(),
                                beam.getFringeColor().getGreen(),
                                beam.getFringeColor().getBlue(),
                                100
                            ),
                            true
                        );
                    }
                    
                    // 生成平滑粒子
                    engine.addSmoothParticle(
                        spawnPoint,
                        velocity,
                        MathUtils.getRandomNumberInRange(PARTICLE_SIZE_MIN, PARTICLE_SIZE_MAX),
                        weapon.getChargeLevel(),
                        MathUtils.getRandomNumberInRange(PARTICLE_DURATION_MIN, PARTICLE_DURATION_MAX),
                        beam.getFringeColor()
                    );
                }
            }
        }
    }

    /**
     * 处理武器挥动动画
     */
    private void handleWeaponAnimation(float amount) {
        if (originalArmPos == 0f) {
            originalArmPos = weapon.getSprite().getCenterY();
        }

        float global = weapon.getShip().getFacing();
        float slotCenterAngle = global;
        if (weapon.getSlot() != null) {
            slotCenterAngle += weapon.getSlot().getAngle();
        }
        boolean isLeftArm = weapon.getSlot().getId().endsWith("_L_SOURCE");

        float arc = weapon.getArc();
        float startAngle, targetRotation;

        // 计算起始角度和目标旋转角度
        if (isLeftArm) {
            startAngle = slotCenterAngle - arc * 0.5f;
            targetRotation = arc;
        } else {
            startAngle = slotCenterAngle + arc * 0.5f;
            targetRotation = -arc;
        }

        // 计算当前角度与起始角度的最短旋转
        float currentRotation = MathUtils.getShortestRotation(startAngle, weapon.getCurrAngle());

        // 1. 充能等待阶段
        if (!swinging && !cooldown && weapon.getChargeLevel() > 0f && !charging) {
            if (Math.abs(currentRotation) < 1f) {
                charging = true;
                chargeTimer = 0f;
                swingLevel = 0f;
            } else {
                float newRotation = lerp(currentRotation, 0f, 0.35f);
                weapon.setCurrAngle(startAngle + newRotation);
            }
        }

        // 2. 充能计时
        if (charging) {
            weapon.setCurrAngle(startAngle);
            chargeTimer += amount;
            if (chargeTimer >= getChargeDuration()) {
                charging = false;
                swinging = true;
                swingLevel = 0f;
            }
        }

        // 3. 挥动阶段
        if (swinging) {
            float swingDuration = getChargeDuration() + 0.15f;
            float invSwingDuration = 1.0f / swingDuration;
            swingLevel += amount * invSwingDuration;
            float t = Math.min(swingLevel, 1f);

            // 使用tan曲线插值
            float tanNorm = (float) (Math.tan(Math.PI / 4 * t));
            float invTanQuarter = 1.0f / (float) Math.tan(Math.PI * 0.25);
            tanNorm = tanNorm * invTanQuarter;

            float newRotation = targetRotation * tanNorm;
            weapon.setCurrAngle(startAngle + newRotation);

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
     * 线性插值
     */
    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * 获取武器的充能时间，用于同步动画
     */
    private float getChargeDuration() {
        if (weapon != null && weapon.getSpec() != null) {
            if (weapon.getSpec() instanceof BeamWeaponSpecAPI) {
                return ((BeamWeaponSpecAPI) weapon.getSpec()).getBeamChargeupTime();
            }
        }
        return 0.33f;
    }

    /**
     * 优化的伤害计算系统
     * 确保在单次攻击周期内，目标受到的总伤害不超过无衰减伤害
     * 但允许在更近距离时补足伤害差额
     */
    private float calculateOptimalDamage(CombatEngineAPI engine, CombatEntityAPI target, float distance) {
        if (weapon == null || weapon.getShip() == null)
            return 0f;

        float currentTime = engine.getTotalElapsedTime(false);

        // 检查是否为新的攻击周期
        Float lastBurstTime = lastBurstStartTime.get(target);
        boolean isNewBurst = lastBurstTime == null
                || Math.abs(currentTime - currentBurstStartTime) < Math.abs(currentTime - lastBurstTime);

        if (isNewBurst) {
            if (lastBurstTime != null) {
                Global.getLogger(Moci_Blade_BeamEffect.class)
                        .info("RESET TARGET DMG - Target: " + target.getClass().getSimpleName());
            }
            damageDealtThisBurst.put(target, 0f);
            lastBurstStartTime.put(target, currentBurstStartTime);
        }

        // 计算基础满伤害（无衰减）
        float fullDamage = Math.max(
                weapon.getDamage().getDamage(),
                weapon.getDamage().getDamage() *
                        weapon.getSpec().getBurstDuration() *
                        (weapon.getShip().getMutableStats().getEnergyWeaponDamageMult().computeMultMod() +
                                weapon.getShip().getMutableStats().getBeamWeaponDamageMult().getPercentMod() * 0.01f));

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
        float cleanupThreshold = 3.0f;

        java.util.Iterator<java.util.Map.Entry<CombatEntityAPI, Float>> iter = lastBurstStartTime.entrySet().iterator();
        while (iter.hasNext()) {
            java.util.Map.Entry<CombatEntityAPI, Float> entry = iter.next();
            if (currentTime - entry.getValue() > cleanupThreshold) {
                iter.remove();
                damageDealtThisBurst.remove(entry.getKey());
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
     * 对应你给的 addTrailMemberAdvanced 方案，并抽成可调参数。
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

        MagicTrailPlugin.addTrailMemberAdvanced(
            ship,                                   // linkedEntity：绑定实体（用于切断/跟随轨迹）
            bladeEdgeTrailId,                       // ID：同ID片段会连接成同一条拖尾
            bladeEdgeTrailSprite,                   // sprite：拖尾贴图
            end,                                    // position：片段生成位置（光束末端）
            BLADE_EDGE_START_SPEED,                 // startSpeed：初始移动速度
            BLADE_EDGE_END_SPEED,                   // endSpeed：结束移动速度
            angle,                                  // angle：片段朝向角度
            BLADE_EDGE_START_ANGULAR_VEL,           // startAngularVelocity：初始角速度
            BLADE_EDGE_END_ANGULAR_VEL,             // endAngularVelocity：结束角速度
            startSize,                              // startSize：初始宽度（按光束长度计算）
            startSize * BLADE_EDGE_END_SIZE_MULT,   // endSize：结束宽度（收缩比例）
            beam.getCoreColor(),                    // startColor：起始颜色
            beam.getFringeColor(),                  // endColor：结束颜色
            BLADE_EDGE_OPACITY,                     // opacity：最大不透明度
            BLADE_EDGE_IN,                          // inDuration：淡入时长
            BLADE_EDGE_MAIN,                        // mainDuration：维持时长
            BLADE_EDGE_OUT,                         // outDuration：淡出时长
            BLADE_EDGE_ADDITIVE,                    // additive：是否加色混合
            BLADE_EDGE_TEXTURE_LOOP,                // textureLoopLength：纹理循环长度
            BLADE_EDGE_TEXTURE_SCROLL,              // textureScrollSpeed：纹理滚动速度
            BLADE_EDGE_TEXTURE_OFFSET,              // textureOffset：纹理偏移
            offsetVelocity,                         // offsetVelocity：额外速度偏移（继承舰船v2f）
            null,                                   // advancedOptions：高级选项（这里不用）
            BLADE_EDGE_LAYER,                       // layerToRenderOn：渲染层
            BLADE_EDGE_FRAME_OFFSET_MULT            // frameOffsetMult：每帧位移修正倍率
        );
    }
}
