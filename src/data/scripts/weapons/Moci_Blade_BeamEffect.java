package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import org.magiclib.util.MagicRender;
import org.magiclib.plugins.MagicTrailPlugin;

import java.util.HashSet;
import java.util.Set;
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
 *    - 反向火花与目标表面火线
 *    - 光束沿线粒子
 *    - 星云粒子
 *    - 命中声音
 */
public class Moci_Blade_BeamEffect implements BeamEffectPlugin {

    private WeaponAPI weapon; // 武器实例
    // ========== 伤害累积跟踪系统 ==========
    private final java.util.Map<CombatEntityAPI, Float> damageDealtThisBurst = new java.util.HashMap<>();
    private boolean burstActive;

    // ========== 音效触发跟踪 ==========
    private final Set<CombatEntityAPI> soundPlayedThisBurst = new HashSet<>();

    // ========== 动画相关变量 ==========
    private float swingLevel = 0f; // 武器摆动级别
    private float animationSwingProgress;
    private boolean swinging = false; // 武器是否正在摆动
    private boolean cooldown = false; // 武器是否处于冷却状态

    private boolean charging = false; // 是否处于充能等待阶段
    private float chargeTimer = 0f; // 充能计时

    // ========== 特效间隔控制 ==========
    private IntervalUtil effectInterval = new IntervalUtil(0.15f, 0.15f); // 命中音效最短间隔
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

    /** 是否已开始粒子生成 */
    private boolean particleSystemActive = false;
    private float ambientParticleBudget;
    private final Moci_MeleeHitEffects hitEffects = new Moci_MeleeHitEffects();
    private final Moci_MeleeAttackPhases attackPhases = new Moci_MeleeAttackPhases();

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

        CombatEntityAPI target = beam.getDamageTarget();
        boolean charged = weapon.getChargeLevel() > 0f;
        if (charged && !burstActive) beginBurst();
        burstActive = charged;

        int collisionPhase = charged ? 1 : 0;
        int rayPhase = collisionPhase;
        float collisionProgress = charged ? 1f : 0f;
        if (weapon.getSlot().getId().endsWith("_L_SOURCE") || weapon.getSlot().getId().endsWith("_R_SOURCE")) {
            // 先读取当前旧光线的碰撞阶段，不能把归位/蓄势的碰撞当成挥砍命中。
            float swingAmount = hitEffects.advanceSlowdown(amount, engine, weapon, beam, charged && weapon.isFiring()
                    && attackPhases.getCollisionPhase() != 0 && beam.getBrightness() >= 0.7f);
            int nextAnimationPhase = handleWeaponAnimation(amount, swingAmount) ? 1 : 0;
            collisionPhase = attackPhases.advance(nextAnimationPhase, animationSwingProgress);
            collisionProgress = attackPhases.getLastCollisionProgress();
            rayPhase = attackPhases.getRayPhase();
        }
        boolean active = charged && weapon.isFiring() && collisionPhase != 0 && beam.getBrightness() >= 0.7f;
        Moci_BladeEffectPlugin.setAttackPhase(beam, charged ? rayPhase : 0);

        if (active && target != null) {
            float currentRange = Misc.getDistance(beam.getFrom(), beam.getTo());
            float damageToApply = calculateOptimalDamage(target, currentRange);
            if (damageToApply > 0f) {
                damageDealtThisBurst.put(target, damageDealtThisBurst.getOrDefault(target, 0f) + damageToApply);
                Moci_MeleeDamage.applyDamage(engine, beam, target, beam.getTo(), damageToApply, damageToApply);
            }
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

        // 反向甩出的火花与短暂附着目标表面的火线，由公共效果器限频生成。
        hitEffects.advance(amount, engine, beam, active, collisionProgress);
        if (active && target != null && beam.getBrightness() > 0.9f
                && effectInterval.intervalElapsed() && MagicRender.screenCheck(0.2f, beam.getTo())
                && soundPlayedThisBurst.add(target)) {
            Global.getSoundPlayer().playSound("Moci_BeamSabre_Hit", 1f, 0.5f, beam.getTo(), new Vector2f());
        }
        // ========== 光束沿线粒子效果 ==========
        Vector2f spawnPoint = MathUtils.getRandomPointOnLine(beam.getFrom(), beam.getTo());
        if (MagicRender.screenCheck(0.2f, spawnPoint)) {
            if (particleSystemActive) {
                float beamWidth = beam.getWidth();

                // 计算本帧应生成的粒子数量
                float particleRate = beamWidth * PARTICLE_SPAWN_WIDTH_MULT *
                                     MathUtils.getDistance(beam.getTo(), beam.getFrom()) *
                                     PARTICLE_DENSITY * weapon.getChargeLevel();
                ambientParticleBudget = Math.min(8f, ambientParticleBudget + Math.min(120f, particleRate) * amount);
                int particleCount = (int) ambientParticleBudget;
                ambientParticleBudget -= particleCount;

                // 生成粒子
                for (int i = 0; i < particleCount; i++) {
                    // 在光束宽度范围内随机生成位置
                    spawnPoint = MathUtils.getRandomPointInCircle(
                            MathUtils.getRandomPointOnLine(beam.getFrom(), beam.getTo()),
                            beamWidth * PARTICLE_SPAWN_WIDTH_MULT);

                    // 如果粒子位置离屏幕太远，跳过
                    if (!engine.getViewport().isNearViewport(spawnPoint, PARTICLE_SIZE_MAX * 3f)) {
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
                        Moci_MeleeHitEffects.emitSmoke(engine, spawnPoint, velocity, beam.getFringeColor());
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

    private void beginBurst() {
        damageDealtThisBurst.clear();
        soundPlayedThisBurst.clear();
        swinging = false;
        charging = false;
        cooldown = false;
        swingLevel = 0f;
        animationSwingProgress = 0f;
        chargeTimer = 0f;
        ambientParticleBudget = 0f;
        attackPhases.reset();
        hitEffects.resetSlowdown();
    }

    /** 返回本帧是否处于实际挥砍；归位、等待和收招不造成伤害。 */
    private boolean handleWeaponAnimation(float amount, float swingAmount) {
        if (weapon.getChargeLevel() <= 0f) {
            swinging = false;
            charging = false;
            cooldown = false;
            swingLevel = 0f;
            animationSwingProgress = 0f;
            chargeTimer = 0f;
            return false;
        }
        if (cooldown) return false;
        float slotCenterAngle = weapon.getShip().getFacing() + weapon.getSlot().getAngle();
        boolean isLeftArm = weapon.getSlot().getId().endsWith("_L_SOURCE");
        float arc = weapon.getArc();
        float startAngle = slotCenterAngle + (isLeftArm ? -arc : arc) * 0.5f;
        float targetRotation = isLeftArm ? arc : -arc;

        if (!swinging && !charging) {
            float rotation = MathUtils.getShortestRotation(startAngle, weapon.getCurrAngle());
            if (Math.abs(rotation) >= 1f) {
                // 与原60 FPS下每帧保留65%偏差一致，但不随帧率改变归位速度。
                weapon.setCurrAngle(startAngle + rotation * (float) Math.pow(0.65f, amount * 60f));
            } else {
                weapon.setCurrAngle(startAngle);
                charging = true;
                chargeTimer = 0f;
                animationSwingProgress = 0f;
            }
            return false;
        }
        if (charging) {
            weapon.setCurrAngle(startAngle);
            chargeTimer += amount;
            if (chargeTimer >= getChargeDuration()) {
                charging = false;
                swinging = true;
                swingLevel = 0f;
                animationSwingProgress = 0f;
            }
            return false;
        }
        if (swinging) {
            // 只减慢实际挥砍；归位和充能仍使用原始amount。
            swingLevel += swingAmount / Math.max(0.01f, getChargeDuration() + 0.15f);
            float progress = Math.min(swingLevel, 1f);
            animationSwingProgress = progress;
            float rotation = targetRotation * (float) Math.tan(Math.PI * 0.25 * progress);
            weapon.setCurrAngle(startAngle + rotation);
            if (progress >= 1f) {
                swinging = false;
                cooldown = true;
            }
            return true;
        }
        return false;
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

    /** 同一目标一刀最多满伤；更近距离命中只补足差额。 */
    private float calculateOptimalDamage(CombatEntityAPI target, float distance) {
        float fullDamage = Moci_MeleeDamage.calculateFullDamage(weapon);
        float multiplier = Moci_BladeEffectPlugin.calculateDistanceDamageMultiplier(
                distance, weapon.getSpec().getMaxRange());
        return Math.max(0f, fullDamage * multiplier - damageDealtThisBurst.getOrDefault(target, 0f));
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
