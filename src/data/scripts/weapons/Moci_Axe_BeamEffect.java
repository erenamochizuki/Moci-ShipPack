package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.util.Misc;

import org.magiclib.util.MagicRender;
import org.magiclib.plugins.MagicTrailPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.fs.starfarer.api.graphics.SpriteAPI;

import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;

public class Moci_Axe_BeamEffect implements BeamEffectPlugin {

    private WeaponAPI weapon; // 武器实例
    // 伤害累积跟踪系统（斧子特殊版本 - 支持两次挥砍）
    private final Map<CombatEntityAPI, Float> firstSwingDamageDealt = new HashMap<>();
    private final Map<CombatEntityAPI, Float> secondSwingDamageDealt = new HashMap<>();
    private boolean burstActive = false;

    // 音效触发跟踪
    private final Set<CombatEntityAPI> soundPlayedThisBurst = new HashSet<>();
    private final Moci_MeleeHitEffects hitEffects = new Moci_MeleeHitEffects();
    private final Moci_MeleeAttackPhases attackPhases = new Moci_MeleeAttackPhases();

    // 特效间隔控制
    private IntervalUtil effectInterval = new IntervalUtil(0.15f, 0.15f); // 特效最短间隔
    private IntervalUtil trailInterval = new IntervalUtil(0.05f, 0.05f); // 拖尾生成间隔

    // 拖尾效果相关
    private float bladeEdgeTrailId; // 刀锋片状拖尾ID
    private boolean trailInitialized = false; // 拖尾是否已初始化
    private SpriteAPI bladeEdgeTrailSprite; // 刀锋片状拖尾专用贴图

    // 动画相关变量
    private boolean swinging = false; // 武器是否正在摆动
    private boolean cooldown = false; // 武器是否处于冷却状态
    // 移除不必要的动画间隔计时器，直接使用amount计算时间

    // 新增斧子特有的变量
    private boolean firstSwingDone = false; // 第一次劈砍是否完成
    private boolean secondSwingDone = false; // 第二次劈砍是否完成
    private float swingTimer = 0f; // 控制劈砍的计时器
    private float animationSwingProgress;

    // 斧子动画配置参数
    // 移除固定的挥砍时间，改为动态获取
    // private final float FIRST_SWING_DURATION = 0.4f; // 第一次劈砍持续时间
    // private final float SECOND_SWING_DURATION = 0.4f; // 第二次劈砍持续时间
    private final float PHASE_TRANSITION_PAUSE = 0.2f; // 两刀之间固定停顿0.2秒，挥空也发生；不是命中触发的“卡肉”。

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
        if (engine.isPaused()) return;
        weapon = beam.getWeapon();

        if (!trailInitialized) {
            bladeEdgeTrailId = MagicTrailPlugin.getUniqueID();
            bladeEdgeTrailSprite = Global.getSettings().getSprite("fx", "Moci_blade_base_trail");
            trailInitialized = true;
        }
        beam.getDamage().setDamage(0f);

        // 每次充能只初始化一次；归位结束或第一刀结束时都不能再清账。
        boolean charged = weapon.getChargeLevel() > 0f;
        if (charged && !burstActive) beginBurst();
        burstActive = charged;

        int attackPhase;
        int nextRayPhase;
        float collisionProgress;
        if (weapon.getSlot().getId().endsWith("_L_SOURCE")
                || weapon.getSlot().getId().endsWith("_R_SOURCE")) {
            float swingAmount = hitEffects.advanceSlowdown(amount, engine, weapon, beam, charged && weapon.isFiring()
                    && attackPhases.getCollisionPhase() != 0 && beam.getBrightness() >= 0.7f);
            int animationPhase = handleWeaponAnimation(amount, swingAmount);
            if (!charged) attackPhases.reset();
            attackPhase = attackPhases.advance(animationPhase, animationSwingProgress);
            collisionProgress = attackPhases.getLastCollisionProgress();
            nextRayPhase = attackPhases.getRayPhase();
        } else {
            attackPhase = charged ? 1 : 0;
            nextRayPhase = attackPhase;
            collisionProgress = charged ? 1f : 0f;
        }
        // EveryFrame插件先于Beam回调执行，因此发布即将生成的光线阶段；
        // 当前舰船命中则仍属于旧光线，不能直接使用刚推进的动画阶段。
        Moci_BladeEffectPlugin.setAttackPhase(beam, nextRayPhase);
        if (!weapon.isFiring() || beam.getBrightness() < 0.7f) attackPhase = 0;

        CombatEntityAPI target = beam.getDamageTarget();
        if (attackPhase != 0 && target != null) {
            float currentRange = Misc.getDistance(beam.getFrom(), beam.getTo());
            boolean isFirstSwing = attackPhase == 1;
            float damageToApply = calculateOptimalAxeDamage(target, currentRange, isFirstSwing);
            if (damageToApply > 0f) {
                recordAxeDamageDealt(target, damageToApply, isFirstSwing);
                Moci_MeleeDamage.applyDamage(engine, beam, target, beam.getTo(), damageToApply, damageToApply);
            }
        }

        hitEffects.advance(amount, engine, beam, attackPhase != 0, collisionProgress);
        effectInterval.advance(amount);
        if (weapon.isFiring()) {
            trailInterval.advance(amount);
            if (trailInterval.intervalElapsed() && beam.getBrightness() == 1f) {
                addBladeEdgeTrail(weapon.getShip(), beam);
            }
        }

        // 命中声音保留原来的最短间隔，每个目标每轮只播放一次。
        if (attackPhase != 0 && target != null && beam.getBrightness() > 0.9f
                && effectInterval.intervalElapsed() && MagicRender.screenCheck(0.2f, beam.getTo())
                && soundPlayedThisBurst.add(target)) {
            Global.getSoundPlayer().playSound("Moci_BeamSabre_Hit", 1f, 0.5f, beam.getTo(), new Vector2f());
        }
    }

    private void beginBurst() {
        attackPhases.reset();
        hitEffects.resetSlowdown();
        firstSwingDamageDealt.clear();
        secondSwingDamageDealt.clear();
        soundPlayedThisBurst.clear();
        swinging = false;
        cooldown = false;
        firstSwingDone = false;
        secondSwingDone = false;
        swingTimer = 0f;
    }

    /**
     * 返回本帧新武器角度的挥砍阶段；由attackPhases对齐到引擎实际光线。
     * 结束一刀的最后角度仍属于这一刀，避免被提前记到下一阶段。
     */
    private int handleWeaponAnimation(float amount, float swingAmount) {
        animationSwingProgress = 0f;
        float shipFacing = weapon.getShip().getFacing();
        float leftmostAngle = shipFacing - weapon.getArc() * 0.5f;
        float rightmostAngle = shipFacing + weapon.getArc() * 0.5f;

        if (weapon.getChargeLevel() <= 0f) {
            float rotation = MathUtils.getShortestRotation(shipFacing, weapon.getCurrAngle());
            weapon.setCurrAngle(shipFacing + rotation * 0.9f);
            swinging = false;
            cooldown = false;
            firstSwingDone = false;
            secondSwingDone = false;
            swingTimer = 0f;
            return 0;
        }
        if (cooldown) {
            animationSwingProgress = 1f;
            Moci_Axe_AnimationController.updateExtension(weapon, 5, 1f);
            return 0;
        }

        if (!swinging) {
            boolean retracted = Moci_Axe_AnimationController.retractBeforeSwing(weapon, amount);
            float rotation = MathUtils.getShortestRotation(leftmostAngle, weapon.getCurrAngle());
            if (Math.abs(rotation) > 5f) {
                weapon.setCurrAngle(leftmostAngle + rotation * (float) Math.pow(0.85f, amount * 60f));
                return 0;
            }
            weapon.setCurrAngle(leftmostAngle);
            if (!retracted) return 0; // 左侧收回完成后才开始第一刀，避免贴图瞬间跳回。
            swinging = true;
            // 归位帧始终不计伤，命中阶段随后与实际光线一起推进。
            return 0;
        }

        // 两刀挥砍与伸出共用减速时间；中间0.2秒停顿/收回仍按真实时间走。
        if (!firstSwingDone) {
            swingTimer += Math.max(0f, swingAmount);
        } else {
            float pauseAmount = Math.min(Math.max(0f, amount), Math.max(0f, PHASE_TRANSITION_PAUSE - swingTimer));
            float ratio = amount > 0f ? swingAmount / amount : 1f;
            swingTimer += pauseAmount + Math.max(0f, amount - pauseAmount) * ratio;
        }
        float duration = getSwingDuration();
        if (!firstSwingDone) {
            float progress = Math.min(swingTimer / duration, 1f);
            animationSwingProgress = smoothStep(0f, 1f, progress);
            Moci_Axe_AnimationController.updateExtension(weapon, 2, animationSwingProgress);
            weapon.setCurrAngle(leftmostAngle + animationSwingProgress
                    * (rightmostAngle - leftmostAngle));
            if (progress >= 1f) {
                firstSwingDone = true;
                swingTimer -= duration; // 保留跨帧余量，停顿长度不随帧率漂移。
            }
            return 1;
        }

        if (swingTimer <= PHASE_TRANSITION_PAUSE) {
            // 第一刀末端完全伸出，利用原有0.2秒停顿收回；不额外增加整轮攻击时间。
            float retract = 1f - smoothStep(0f, 1f, swingTimer / PHASE_TRANSITION_PAUSE);
            Moci_Axe_AnimationController.updateExtension(weapon, 3, retract);
            weapon.setCurrAngle(rightmostAngle);
            return 0;
        }
        if (!secondSwingDone) {
            float progress = Math.min((swingTimer - PHASE_TRANSITION_PAUSE) / duration, 1f);
            animationSwingProgress = smoothStep(0f, 1f, progress);
            Moci_Axe_AnimationController.updateExtension(weapon, 4, animationSwingProgress);
            weapon.setCurrAngle(rightmostAngle - animationSwingProgress
                    * (rightmostAngle - leftmostAngle));
            if (progress >= 1f) {
                secondSwingDone = true;
                cooldown = true;
                swinging = false;
            }
            return 2;
        }
        return 0;
    }

    // 平滑过渡函数，使运动显得更自然
    private float smoothStep(float edge0, float edge1, float x) {
        // 限制x在[0,1]范围内
        x = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        // 应用平滑函数: 3x^2 - 2x^3
        return x * x * (3 - 2 * x);
    }

    /**
     * 获取武器的充能时间，用于同步挥砍动画
     */
    private float getSwingDuration() {
        if (weapon != null && weapon.getSpec() != null) {
            // 对于光束武器，使用getBeamChargeupTime()方法
            if (weapon.getSpec() instanceof BeamWeaponSpecAPI) {
                float chargeTime = ((BeamWeaponSpecAPI) weapon.getSpec()).getBeamChargeupTime();
                return chargeTime > 0f ? chargeTime : 0.4f;
            }
        }
        return 0.4f; // 备用默认值
    }

    /**
     * 斧子专用的优化伤害计算系统
     * 支持两次挥砍的累积伤害逻辑
     */
    private float calculateOptimalAxeDamage(CombatEntityAPI target, float distance, boolean isFirstSwing) {
        if (weapon == null || weapon.getShip() == null) return 0f;
        float fullDamage = Moci_MeleeDamage.calculateFullDamage(weapon);
        float distanceMultiplier = Moci_BladeEffectPlugin.calculateDistanceDamageMultiplier(
                distance, weapon.getSpec().getMaxRange());
        Float firstRecord = firstSwingDamageDealt.get(target);
        float firstDealt = firstRecord == null ? 0f : firstRecord;

        if (isFirstSwing) {
            return Math.max(0f, fullDamage * distanceMultiplier - firstDealt);
        }

        // 第二刀独立记账：第一刀打中过则半伤，否则全伤；两刀最多合计1.5份满伤。
        Float secondRecord = secondSwingDamageDealt.get(target);
        float secondDealt = secondRecord == null ? 0f : secondRecord;
        float secondLimit = fullDamage * distanceMultiplier * (firstDealt > 0f ? 0.5f : 1f);
        return Math.max(0f, secondLimit - secondDealt);
    }

    private void recordAxeDamageDealt(CombatEntityAPI target, float damage, boolean isFirstSwing) {
        Map<CombatEntityAPI, Float> records = isFirstSwing ? firstSwingDamageDealt : secondSwingDamageDealt;
        Float previous = records.get(target);
        records.put(target, (previous == null ? 0f : previous) + damage);
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
