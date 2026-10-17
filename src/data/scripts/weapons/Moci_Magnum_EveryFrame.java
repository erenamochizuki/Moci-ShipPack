package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

/**
 * Moci 马格南武器每帧特效插件。
 *
 * 功能概览：
 * 1. 枪口散热烟雾（开火阶段 + 冷却衰减阶段）。
 * 2. 枪口粒子（使用预设颜色，不读取光束颜色）。
 * 3. 三层蓄力贴图（底层闪烁、中层旋转、顶层旋转+呼吸）。
 */
public class Moci_Magnum_EveryFrame implements EveryFrameWeaponEffectPlugin {
    /** 马格南开火音效。 */
    private static final String FIRE_SOUND_ID = "Moci_Magnum_03";

    // ==================== 散热烟雾参数 ====================
    /** 是否启用散热烟雾。 */
    private static final boolean ENABLE_HEAT_SMOKE = true;
    /** 烟雾生成间隔（秒）。 */
    private static final float SMOKE_INTERVAL = 0.05f;
    /** 烟雾初速度。 */
    private static final float SMOKE_SPEED = 25f;
    /** 烟雾基础尺寸。 */
    private static final float SMOKE_SIZE = 15f;
    /** 烟雾尺寸增长倍率（nebula 粒子参数）。 */
    private static final float SMOKE_SIZE_MULT = 2.5f;
    /** 烟雾存活时间（秒）。 */
    private static final float SMOKE_DURATION = 3f;
    /** 烟雾颜色。 */
    private static final Color SMOKE_COLOR = new Color(120, 120, 120, 100);
    /** 烟雾计时器：用于按固定间隔触发生成。 */
    private float smokeTimer = 0f;

    // ==================== 枪口粒子参数 ====================
    /** 是否启用枪口粒子。 */
    private static final boolean ENABLE_MUZZLE_PARTICLES = true;
    /** 粒子生成间隔（秒）。 */
    private static final float MUZZLE_PARTICLE_INTERVAL = 0.05f;
    /** 粒子尺寸下限。 */
    private static final float MUZZLE_PARTICLE_SIZE_MIN = 15f;
    /** 粒子尺寸上限。 */
    private static final float MUZZLE_PARTICLE_SIZE_MAX = 25f;
    /** 粒子持续时间下限（秒）。 */
    private static final float MUZZLE_PARTICLE_DURATION_MIN = 0.2f;
    /** 粒子持续时间上限（秒）。 */
    private static final float MUZZLE_PARTICLE_DURATION_MAX = 0.25f;
    /** 粒子亮度。 */
    private static final float MUZZLE_PARTICLE_BRIGHTNESS = 20f;
    /** 枪口粒子核心色（预设色）。 */
    private static final Color MUZZLE_PARTICLE_CORE_COLOR = new Color(255, 255, 255, 220);
    /** 枪口粒子外层色（预设色）。 */
    private static final Color MUZZLE_PARTICLE_FRINGE_COLOR = new Color(53, 208, 255, 180);
    /** 粒子计时器：用于按固定间隔触发生成。 */
    private float muzzleParticleTimer = 0f;

    // ==================== 蓄力贴图参数 ====================
    /** 底层闪烁触发间隔：每 0.1 chargeLevel 触发一次。 */
    private static final float BLINK_LEVEL_INTERVAL = 0.1f;
    /** 单次底层闪烁显示时长（秒）。 */
    private static final float BLINK_DURATION = 0.05f;
    /** 上次闪烁驱动值（用于计算是否跨过 0.1 桶）。 */
    private float lastBlinkLevel = 0f;
    /** 闪烁剩余显示时间；<0 表示不显示。 */
    private float blinkTimer = -1f;
    /** 当前这次闪烁的随机尺寸倍率。 */
    private float blinkSizeMult = 1f;

    /** 中层贴图当前旋转角。 */
    private float coreRotation = 0f;
    /** 中层贴图每 0.1 chargeLevel 的旋转角度。 */
    private static final float CORE_ROTATE_PER_LEVEL = 90f;

    /** 顶层贴图当前旋转角。 */
    private float fringeRotation = 0f;
    /** 顶层贴图每 0.1 chargeLevel 的旋转角度。 */
    private static final float FRINGE_ROTATE_PER_LEVEL = 60f;
    /** 呼吸动画相位（用于 sin 波）。 */
    private float breathePhase = 0f;
    /** 呼吸相位每 0.1 chargeLevel 推进量。 */
    private static final float BREATHE_PER_LEVEL = (float) (Math.PI);

    /** 峰值持续开火时，旋转/呼吸使用的虚拟推进速率。 */
    private static final float PEAK_ROTATE_BUCKETS_PER_SECOND = 4f;
    /** 峰值持续开火时，底层闪烁触发频率（次/秒）。10 次/秒即 0.1 秒一次。 */
    private static final float PEAK_BLINKS_PER_SECOND = 10f;

    private static final float CHARGE_EPSILON = 0.0001f;
    /** 上一帧 chargeLevel，用于计算增量驱动。 */
    private float lastChargeLevel = 0f;

    // ==================== 冷却状态 ====================
    /** 上一帧是否处于“峰值开火”（chargeLevel>=1 且 isFiring）状态。 */
    private boolean wasFiringAtPeak = false;
    /** 冷却烟雾强度（1 -> 0 线性衰减）。 */
    private float cooldownSmokeIntensity = 0f;

    // ==================== 炮口轮换状态 ====================
    /** 本轮蓄力是否已经达到开火判定。 */
    private boolean hasFiredThisCharge = false;
    /** 当前炮口索引（用于轮换）。 */
    private int currentBarrel = 0;

    // ==================== 光束环绕电弧参数（EveryFrame） ====================
    /** 是否启用光束环绕电弧。 */
    private static final boolean ENABLE_SURROUND_ARCS = false;
    /** 每帧“允许生成新电弧”的概率（0.5 = 50%）。 */
    private static final float SURROUND_ARC_FRAME_SPAWN_CHANCE = 0.1f;
    /** 每帧每条有效光束生成的新电弧数量。 */
    private static final int SURROUND_ARCS_PER_FRAME = 1;
    /** 电弧生成扇形的半角（以光束方向为中线）。 */
    private static final float SURROUND_ARC_SECTOR_HALF_ANGLE = 15f;
    /** 电弧长度下限。 */
    private static final float SURROUND_ARC_LENGTH_MIN = 30f;
    /** 电弧长度上限。 */
    private static final float SURROUND_ARC_LENGTH_MAX = 45f;
    /** 电弧切线方向的随机偏转（越小越规则）。 */
    private static final float SURROUND_ARC_TANGENT_ANGLE_JITTER = 8f;
    /** 电弧端点外扩速度下限。 */
    private static final float SURROUND_ARC_ENDPOINT_SPEED_MIN = 300f;
    /** 电弧端点外扩速度上限。 */
    private static final float SURROUND_ARC_ENDPOINT_SPEED_MAX = 500f;
    /** 电弧距开火点的最小半径。 */
    private static final float SURROUND_ARC_RADIUS_MIN = 30f;
    /** 电弧距开火点的末端安全边距（避免越过光束终点太多）。 */
    private static final float SURROUND_ARC_RADIUS_END_MARGIN = 10f;
    /** 两端点半径允许的微小抖动（保证“差不多等距”但不死板）。 */
    private static final float SURROUND_ARC_RADIUS_JITTER = 4f;
    /** 单条电弧生命周期下限（秒）。 */
    private static final float SURROUND_ARC_LIFETIME_MIN = 0.2f;
    /** 单条电弧生命周期上限（秒）。 */
    private static final float SURROUND_ARC_LIFETIME_MAX = 0.35f;
    /** 电弧粗细下限。 */
    private static final float SURROUND_ARC_THICKNESS_MIN = 1f;
    /** 电弧粗细上限。 */
    private static final float SURROUND_ARC_THICKNESS_MAX = 3f;
    /** 电弧边缘颜色（蓝色）。 */
    private static final Color SURROUND_ARC_FRINGE_COLOR = new Color(55, 140, 255, 100);
    /** 电弧核心颜色（亮蓝）。 */
    private static final Color SURROUND_ARC_CORE_COLOR = new Color(165, 225, 255, 150);

    /** 当前存活中的“可移动端点电弧”。 */
    private final List<MovingArcData> activeSurroundArcs = new ArrayList<MovingArcData>();

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础有效性检查
        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 读取本帧武器状态
        float chargeLevel = weapon.getChargeLevel();
        float deltaCharge = chargeLevel - lastChargeLevel;
        boolean isFiring = weapon.isFiring();

        // 屏幕外时直接跳过（同时更新 lastChargeLevel，避免回到屏幕时增量突变）
        if (!engine.getViewport().isNearViewport(weapon.getLocation(), 400f)) {
            activeSurroundArcs.clear();
            lastChargeLevel = chargeLevel;
            return;
        }

        // 光束环绕电弧：迁移到 EveryFrame 中统一更新
        if (ENABLE_SURROUND_ARCS) {
            updateSurroundArcs(engine, weapon, amount);
        }

        // 开火结束判定：上一帧在峰值开火，本帧 chargeLevel 已开始下降
        boolean atPeak = (chargeLevel >= 1f && isFiring);
        boolean justFinishedFiring = wasFiringAtPeak && chargeLevel < 1f;

        if (justFinishedFiring) {
            // 进入冷却：开启衰减烟雾，并立刻重置三层蓄力贴图
            cooldownSmokeIntensity = 1f;
            resetChargeSprites();
        }
        wasFiringAtPeak = atPeak;

        // 蓄力贴图显示条件：仅蓄力/开火上行阶段显示，下降冷却阶段立即隐藏
        boolean chargeDropping = deltaCharge < -CHARGE_EPSILON;
        boolean showSprites = chargeLevel > 0f && isFiring && !chargeDropping && !justFinishedFiring;
        if (showSprites) {
            Vector2f firePoint = weapon.getFirePoint(0);
            if (firePoint == null) {
                firePoint = weapon.getLocation();
            }
            handleChargeSprites(amount, firePoint, chargeLevel, deltaCharge);
        } else {
            resetChargeSprites();
        }

        // 开火主阶段：按统一计时触发，然后分发到所有炮口
        if (isFiring && chargeLevel >= 0.9f) {
            boolean spawnSmoke = ENABLE_HEAT_SMOKE && advanceTimer(amount, SMOKE_INTERVAL, true);
            boolean spawnParticles = ENABLE_MUZZLE_PARTICLES && advanceTimer(amount, MUZZLE_PARTICLE_INTERVAL, false);

            int firePointCount = getBarrelCount(weapon);
            for (int i = 0; i < firePointCount; i++) {
                Vector2f firePointLocation = calculateFirePointLocation(weapon, i);
                if (spawnSmoke) {
                    handleMuzzleSmokeEffects(engine, weapon, firePointLocation, 1f);
                }
                if (spawnParticles) {
                    handleMuzzleParticleEffects(engine, weapon, firePointLocation);
                }
            }
        } else {
            // 非开火主阶段时，枪口粒子停止
            muzzleParticleTimer = 0f;

            // 冷却阶段：烟雾继续生成，但强度随时间衰减
            if (ENABLE_HEAT_SMOKE && cooldownSmokeIntensity > 0f) {
                cooldownSmokeIntensity -= amount * 1.5f;
                if (cooldownSmokeIntensity < 0f) {
                    cooldownSmokeIntensity = 0f;
                }

                if (cooldownSmokeIntensity > 0f) {
                    boolean spawnSmoke = advanceTimer(amount, SMOKE_INTERVAL, true);
                    if (spawnSmoke) {
                        int firePointCount = getBarrelCount(weapon);
                        for (int i = 0; i < firePointCount; i++) {
                            Vector2f firePointLocation = calculateFirePointLocation(weapon, i);
                            handleMuzzleSmokeEffects(engine, weapon, firePointLocation, cooldownSmokeIntensity);
                        }
                    }
                }
            } else {
                // 冷却结束，重置烟雾计时
                smokeTimer = 0f;
            }
        }

        // 炮口轮换与状态收尾
        handleBarrelRotation(weapon, chargeLevel, isFiring);
        lastChargeLevel = chargeLevel;
    }

    /** 重置三层蓄力贴图的瞬时状态。 */
    private void resetChargeSprites() {
        lastBlinkLevel = 0f;
        blinkTimer = -1f;
        coreRotation = 0f;
        fringeRotation = 0f;
        breathePhase = 0f;
    }

    /**
     * 推进计时器并返回“本帧是否触发”。
     *
     * @param amount 本帧时长
     * @param interval 触发间隔
     * @param smoke true=烟雾计时器，false=粒子计时器
     */
    private boolean advanceTimer(float amount, float interval, boolean smoke) {
        if (smoke) {
            smokeTimer += amount;
            if (smokeTimer >= interval) {
                smokeTimer -= interval;
                return true;
            }
            return false;
        }

        muzzleParticleTimer += amount;
        if (muzzleParticleTimer >= interval) {
            muzzleParticleTimer -= interval;
            return true;
        }
        return false;
    }

    /**
     * EveryFrame 中更新环绕电弧：
     * 1) 收集该武器当前有效光束；
     * 2) 按概率生成新电弧（在开火方向扇形内，端点到开火点距离近似相等）；
     * 3) 逐帧推进电弧端点并渲染。
     */
    private void updateSurroundArcs(CombatEngineAPI engine, WeaponAPI weapon, float amount) {
        List<BeamAPI> activeBeams = getActiveBeams(weapon);
        if (activeBeams.isEmpty()) {
            activeSurroundArcs.clear();
            return;
        }

        if (Math.random() < SURROUND_ARC_FRAME_SPAWN_CHANCE) {
            for (BeamAPI beam : activeBeams) {
                float beamLength = MathUtils.getDistance(beam.getFrom(), beam.getTo());
                if (beamLength <= 1f) {
                    continue;
                }
                for (int i = 0; i < SURROUND_ARCS_PER_FRAME; i++) {
                    activeSurroundArcs.add(createSurroundArc(beam, beamLength));
                }
            }
        }

        for (int i = activeSurroundArcs.size() - 1; i >= 0; i--) {
            MovingArcData arcData = activeSurroundArcs.get(i);
            arcData.remainingLifetime -= amount;
            if (arcData.remainingLifetime <= 0f) {
                activeSurroundArcs.remove(i);
                continue;
            }

            arcData.start.x += arcData.startVelocity.x * amount;
            arcData.start.y += arcData.startVelocity.y * amount;
            arcData.end.x += arcData.endVelocity.x * amount;
            arcData.end.y += arcData.endVelocity.y * amount;

            if (!engine.getViewport().isNearViewport(arcData.start, 150f)
                    && !engine.getViewport().isNearViewport(arcData.end, 150f)) {
                continue;
            }

            engine.spawnEmpArcVisual(
                    arcData.start,              // 起点坐标（from）
                    null,                       // 起点绑定实体（sourceEntity），null=纯视觉
                    arcData.end,                // 终点坐标（to）
                    null,                       // 终点绑定实体（targetEntity），null=纯视觉
                    arcData.thickness,          // 电弧粗细（thickness）
                    SURROUND_ARC_FRINGE_COLOR,  // 电弧边缘色（fringeColor）
                    SURROUND_ARC_CORE_COLOR);   // 电弧核心色（coreColor）
        }
    }

    /** 获取当前武器的有效光束列表。 */
    private List<BeamAPI> getActiveBeams(WeaponAPI weapon) {
        List<BeamAPI> result = new ArrayList<BeamAPI>();
        for (BeamAPI beam : weapon.getBeams()) {
            if (beam == null) {
                continue;
            }
            if (!weapon.isFiring()) {
                continue;
            }
            if (beam.getBrightness() <= 0.1f) {
                continue;
            }
            if (MathUtils.getDistanceSquared(beam.getFrom(), beam.getTo()) <= 1f) {
                continue;
            }
            result.add(beam);
        }
        return result;
    }

    /**
     * 创建一条新电弧：
     * - 在“开火方向扇形”内随机一个极坐标中心；
     * - 两端点在近似同一半径上展开，保证到开火点距离接近；
     * - 整条电弧沿光束终点方向推进，形成向外扩散感。
     */
    private MovingArcData createSurroundArc(BeamAPI beam, float beamLength) {
        Vector2f beamFrom = beam.getFrom();
        Vector2f beamTo = beam.getTo();
        float beamFacing = (float) Math.toDegrees(Math.atan2(beamTo.y - beamFrom.y, beamTo.x - beamFrom.x));

        float arcLength = MathUtils.getRandomNumberInRange(SURROUND_ARC_LENGTH_MIN, SURROUND_ARC_LENGTH_MAX);
        float maxRadius = Math.max(SURROUND_ARC_RADIUS_MIN + 1f, beamLength - SURROUND_ARC_RADIUS_END_MARGIN);
        float centerRadius = MathUtils.getRandomNumberInRange(SURROUND_ARC_RADIUS_MIN, maxRadius);
        float centerAngle = beamFacing + MathUtils.getRandomNumberInRange(
                -SURROUND_ARC_SECTOR_HALF_ANGLE, SURROUND_ARC_SECTOR_HALF_ANGLE);

        // 弦长 L 与圆心角 theta 关系：L = 2R * sin(theta/2)
        float clampedArcLength = Math.min(arcLength, centerRadius * 1.95f);
        float halfThetaDeg = (float) Math.toDegrees(Math.asin(Math.min(1f, clampedArcLength / (2f * centerRadius))));
        halfThetaDeg += MathUtils.getRandomNumberInRange(-SURROUND_ARC_TANGENT_ANGLE_JITTER, SURROUND_ARC_TANGENT_ANGLE_JITTER);

        float startRadius = centerRadius + MathUtils.getRandomNumberInRange(-SURROUND_ARC_RADIUS_JITTER, SURROUND_ARC_RADIUS_JITTER);
        float endRadius = centerRadius + MathUtils.getRandomNumberInRange(-SURROUND_ARC_RADIUS_JITTER, SURROUND_ARC_RADIUS_JITTER);
        startRadius = Math.max(SURROUND_ARC_RADIUS_MIN, startRadius);
        endRadius = Math.max(SURROUND_ARC_RADIUS_MIN, endRadius);

        Vector2f start = MathUtils.getPointOnCircumference(beamFrom, startRadius, centerAngle - halfThetaDeg);
        Vector2f end = MathUtils.getPointOnCircumference(beamFrom, endRadius, centerAngle + halfThetaDeg);

        Vector2f startVelocity = createEndpointVelocity(beamFacing);
        Vector2f endVelocity = createEndpointVelocity(beamFacing);

        float lifetime = MathUtils.getRandomNumberInRange(SURROUND_ARC_LIFETIME_MIN, SURROUND_ARC_LIFETIME_MAX);
        float thickness = MathUtils.getRandomNumberInRange(SURROUND_ARC_THICKNESS_MIN, SURROUND_ARC_THICKNESS_MAX);
        return new MovingArcData(start, end, startVelocity, endVelocity, lifetime, thickness);
    }

    /** 生成沿光束终点方向的端点速度向量。 */
    private Vector2f createEndpointVelocity(float beamFacing) {
        float speed = MathUtils.getRandomNumberInRange(SURROUND_ARC_ENDPOINT_SPEED_MIN, SURROUND_ARC_ENDPOINT_SPEED_MAX);
        return MathUtils.getPointOnCircumference(new Vector2f(), speed, beamFacing);
    }

    /** 单条电弧的运行时数据。 */
    private static class MovingArcData {
        private final Vector2f start;
        private final Vector2f end;
        private final Vector2f startVelocity;
        private final Vector2f endVelocity;
        private float remainingLifetime;
        private final float thickness;

        private MovingArcData(Vector2f start, Vector2f end,
                Vector2f startVelocity, Vector2f endVelocity,
                float remainingLifetime, float thickness) {
            this.start = start;
            this.end = end;
            this.startVelocity = startVelocity;
            this.endVelocity = endVelocity;
            this.remainingLifetime = remainingLifetime;
            this.thickness = thickness;
        }
    }

    /** 生成枪口散热烟雾。 */
    private void handleMuzzleSmokeEffects(CombatEngineAPI engine, WeaponAPI weapon,
            Vector2f muzzleLocation, float intensity) {
        float weaponAngle = weapon.getCurrAngle();

        // 强度越低，粒子数量和尺寸越小
        int count = Math.max(1, Math.round(2f * intensity));
        float sizeScale = 0.5f + 0.5f * intensity;

        for (int i = 0; i < count; i++) {
            Vector2f smokeLoc = new Vector2f(muzzleLocation);
            smokeLoc.x += MathUtils.getRandomNumberInRange(-5f, 5f);
            smokeLoc.y += MathUtils.getRandomNumberInRange(-5f, 5f);

            float smokeAngle = weaponAngle + 180f + MathUtils.getRandomNumberInRange(-30f, 30f);
            Vector2f smokeVel = VectorUtils.getDirectionalVector(smokeLoc,
                    MathUtils.getPointOnCircumference(smokeLoc, 10f, smokeAngle));
            smokeVel.scale(SMOKE_SPEED);
            Vector2f.add(weapon.getShip().getVelocity(), smokeVel, smokeVel);

            engine.addNebulaSmokeParticle(
                    smokeLoc,
                    smokeVel,
                    SMOKE_SIZE * sizeScale,
                    SMOKE_SIZE_MULT,
                    0.1f,
                    0.1f,
                    SMOKE_DURATION,
                    SMOKE_COLOR);
        }
    }

    /** 生成枪口粒子（使用预设颜色）。 */
    private void handleMuzzleParticleEffects(CombatEngineAPI engine, WeaponAPI weapon,
            Vector2f muzzleLocation) {
        float size = MathUtils.getRandomNumberInRange(MUZZLE_PARTICLE_SIZE_MIN, MUZZLE_PARTICLE_SIZE_MAX);
        float coreSize = size * MathUtils.getRandomNumberInRange(0.5f, 0.7f);
        float duration = MathUtils.getRandomNumberInRange(MUZZLE_PARTICLE_DURATION_MIN, MUZZLE_PARTICLE_DURATION_MAX);

        engine.addSmoothParticle(
                muzzleLocation,
                weapon.getShip().getVelocity(),
                coreSize,
                MUZZLE_PARTICLE_BRIGHTNESS,
                duration,
                MUZZLE_PARTICLE_CORE_COLOR);

        engine.addSmoothParticle(
                muzzleLocation,
                weapon.getShip().getVelocity(),
                size,
                MUZZLE_PARTICLE_BRIGHTNESS,
                duration,
                MUZZLE_PARTICLE_FRINGE_COLOR);
    }

    /**
     * 处理三层蓄力贴图。
     *
     * @param amount 本帧时长
     * @param firePoint 绘制位置
     * @param chargeLevel 当前蓄力值
     * @param deltaCharge 本帧蓄力增量
     */
    private void handleChargeSprites(float amount, Vector2f firePoint, float chargeLevel, float deltaCharge) {

        // 1) 底层闪烁：每跨过 0.1 chargeLevel 触发一次
        float blinkDriverLevel = chargeLevel;
        if (chargeLevel >= 1f && Math.abs(deltaCharge) < CHARGE_EPSILON) {
            blinkDriverLevel = lastBlinkLevel + BLINK_LEVEL_INTERVAL * PEAK_BLINKS_PER_SECOND * amount;
        }
        float blinkLevelBucket = (float) Math.floor(blinkDriverLevel / BLINK_LEVEL_INTERVAL);
        float lastBucket = (float) Math.floor(lastBlinkLevel / BLINK_LEVEL_INTERVAL);
        if (blinkLevelBucket > lastBucket) {
            blinkTimer = BLINK_DURATION;
            blinkSizeMult = MathUtils.getRandomNumberInRange(0.8f, 1.0f);
        }
        lastBlinkLevel = blinkDriverLevel;

        blinkTimer -= amount;
        if (blinkTimer >= 0f) {
            try {
                SpriteAPI blinkSprite = Global.getSettings().getSprite("fx", "Moci_magnum_fx_blink");
                float blinkW = blinkSprite.getWidth() * blinkSizeMult;
                float blinkH = blinkSprite.getHeight() * blinkSizeMult;
                MagicRender.singleframe(
                        blinkSprite,
                        firePoint,
                        new Vector2f(blinkW, blinkH),
                        0f,
                        new Color(255, 255, 255, 255),
                        true);
            } catch (Exception ignored) {
            }
        }

        // 2) 中层旋转：按 charge 增量驱动；峰值持续开火时使用虚拟步进保持转动
        float chargeStep = deltaCharge / BLINK_LEVEL_INTERVAL;
        if (chargeLevel >= 1f && Math.abs(chargeStep) < CHARGE_EPSILON) {
            chargeStep = PEAK_ROTATE_BUCKETS_PER_SECOND * amount;
        }
        coreRotation -= CORE_ROTATE_PER_LEVEL * chargeStep;
        while (coreRotation < -360f) {
            coreRotation += 360f;
        }
        while (coreRotation > 360f) {
            coreRotation -= 360f;
        }

        // 中层尺寸：0.5~0.8 从 0 线性涨到 1
        float coreSizeFactor;
        if (chargeLevel <= 0.6f) {
            coreSizeFactor = 0f;
        } else if (chargeLevel >= 0.8f) {
            coreSizeFactor = 1f;
        } else {
            coreSizeFactor = (chargeLevel - 0.6f) / 0.3f;
        }

        if (coreSizeFactor > 0f) {
            try {
                SpriteAPI coreSprite = Global.getSettings().getSprite("fx", "Moci_magnum_fx_core");
                float coreW = coreSprite.getWidth() * coreSizeFactor;
                float coreH = coreSprite.getHeight() * coreSizeFactor;
                MagicRender.singleframe(
                        coreSprite,
                        firePoint,
                        new Vector2f(coreW, coreH),
                        coreRotation,
                        new Color(255, 255, 255, 255),
                        true);
            } catch (Exception ignored) {
            }
        }

        // 3) 顶层旋转 + 呼吸：与中层同源步进
        fringeRotation += FRINGE_ROTATE_PER_LEVEL * chargeStep;
        while (fringeRotation >= 360f) {
            fringeRotation -= 360f;
        }
        while (fringeRotation <= -360f) {
            fringeRotation += 360f;
        }

        breathePhase += BREATHE_PER_LEVEL * chargeStep;
        float breatheOffset = (float) Math.sin(breathePhase) * 0.1f;
        float fringeSizeFactor = coreSizeFactor + 0.2f + breatheOffset;

        if (coreSizeFactor > 0f) {
            try {
                SpriteAPI fringeSprite = Global.getSettings().getSprite("fx", "Moci_magnum_fx_fringe");
                float fringeW = fringeSprite.getWidth() * fringeSizeFactor;
                float fringeH = fringeSprite.getHeight() * fringeSizeFactor;
                MagicRender.singleframe(
                        fringeSprite,
                        firePoint,
                        new Vector2f(fringeW, fringeH),
                        fringeRotation,
                        new Color(255, 255, 255, 255),
                        true);
            } catch (Exception ignored) {
            }
        }
    }

    /** 处理炮口轮换状态。 */
    private void handleBarrelRotation(WeaponAPI weapon, float chargeLevel, boolean isFiring) {
        if (chargeLevel >= 1f && isFiring && !hasFiredThisCharge) {
            // 每轮蓄力只在真正开火瞬间播放一次音效
            Global.getSoundPlayer().playSound(
                    FIRE_SOUND_ID,
                    1f,
                    1f,
                    weapon.getLocation(),
                    weapon.getShip().getVelocity());
            hasFiredThisCharge = true;
        }

        if (hasFiredThisCharge && (chargeLevel <= 0f || !isFiring)) {
            hasFiredThisCharge = false;
            currentBarrel++;

            int barrelCount = getBarrelCount(weapon);
            if (currentBarrel >= barrelCount) {
                currentBarrel = 0;
            }
        }
    }

    /** 获取武器炮口数量（按挂点类型读取）。 */
    private int getBarrelCount(WeaponAPI weapon) {
        if (weapon.getSlot().isHardpoint()) {
            return weapon.getSpec().getHardpointAngleOffsets().size();
        } else if (weapon.getSlot().isHidden()) {
            return weapon.getSpec().getHiddenAngleOffsets().size();
        } else {
            return weapon.getSpec().getTurretAngleOffsets().size();
        }
    }

    /** 计算指定炮口在世界坐标中的开火点位置。 */
    private Vector2f calculateFirePointLocation(WeaponAPI weapon, int firePointIndex) {
        Vector2f firePointLocation = new Vector2f(0f, 0f);

        if (weapon.getSlot().isHardpoint()) {
            firePointLocation.x += weapon.getSpec().getHardpointFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getHardpointFireOffsets().get(firePointIndex).y;
        } else if (weapon.getSlot().isTurret()) {
            firePointLocation.x += weapon.getSpec().getTurretFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getTurretFireOffsets().get(firePointIndex).y;
        } else {
            firePointLocation.x += weapon.getSpec().getHiddenFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getHiddenFireOffsets().get(firePointIndex).y;
        }

        firePointLocation = VectorUtils.rotate(firePointLocation, weapon.getCurrAngle(), new Vector2f(0f, 0f));
        firePointLocation.x += weapon.getLocation().x;
        firePointLocation.y += weapon.getLocation().y;

        return firePointLocation;
    }
}
