package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.RippleDistortion;
import org.dark.shaders.distortion.WaveDistortion;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicLensFlare;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 重力波炮光束武器特效系统
 * 
 * 关联武器ID: "Moci_gravity_wave_cannon"
 * 
 * 功能特性：
 * 1. 充能阶段：从武器开火点到所有LIGHT_前缀武器槽创建重力场电弧
 * 2. 重力光晕：三层渐变光晕效果（外圈深紫色、中间蓝紫色、内核白色）
 * 3. 光束布雷：光束击中目标时围绕目标生成重力异常地雷
 * 4. 空间扭曲：沿光束路径创建波形扭曲效果
 * 5. 重力场：在光束源点生成负粒子效果
 */
public class Moci_GravityWaveCannonEffect implements EveryFrameWeaponEffectPlugin, BeamEffectPlugin {

    // 充能阶段参数
    private static final float CHARGE_ARC_INTERVAL = 0.06f; // 充能电弧间隔（更频繁）
    private static final float CHARGE_DURATION = 1.2f; // 充能持续时间（稍长）

    // 开火特效参数
    private static final int FIRE_ARC_COUNT = 16; // 开火时电弧数量（更多）
    private static final float FIRE_EFFECT_RANGE = 80f; // 开火特效范围（更大）

    // 电弧视觉参数
    private static final float ARC_THICKNESS_MIN = 3f;
    private static final float ARC_THICKNESS_MAX = 12f;

    // 重力波颜色定义 - 深紫色到蓝紫色渐变
    private static final Color GRAVITY_DARK = new Color(75, 0, 130, 200); // 深紫色
    private static final Color GRAVITY_LIGHT = new Color(138, 43, 226, 180); // 蓝紫色
    private static final Color GRAVITY_CORE_DARK = new Color(100, 20, 150, 240); // 深紫核心
    private static final Color GRAVITY_CORE_LIGHT = new Color(180, 100, 255, 220); // 亮紫核心

    // 重力光晕颜色定义 - 三层渐变光晕
    private static final Color FLARE_OUTER = new Color(75, 0, 130, 140); // 外圈深紫色
    private static final Color FLARE_MIDDLE = new Color(138, 43, 226, 180); // 中间蓝紫色
    private static final Color FLARE_INNER = new Color(255, 255, 255, 255); // 内核白色

    // 光晕尺寸参数
    private static final float FLARE_BASE_SIZE = 40f; // 基础光晕尺寸（更大）
    private static final float FLARE_MAX_SIZE = 120f; // 最大光晕尺寸（更大）

    // 扭曲效果参数
    private static final float DISTORTION_INTERVAL = 0.1f; // 扭曲效果生成间隔
    private static final float DISTORTION_INTENSITY = 25f; // 扭曲强度
    private static final float DISTORTION_SIZE = 60f; // 扭曲尺寸

    private float effectLevel = 0f; // 充能进度 (0-1)
    private final IntervalUtil chargeArcTimer = new IntervalUtil(CHARGE_ARC_INTERVAL, CHARGE_ARC_INTERVAL);
    private final IntervalUtil distortionTimer = new IntervalUtil(DISTORTION_INTERVAL, DISTORTION_INTERVAL);
    private boolean wasCharging = false; // 用于检测充能状态变化

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused() || weapon == null || weapon.getShip() == null)
            return;

        ShipAPI ship = weapon.getShip();
        boolean isCharging = weapon.isFiring() && weapon.getCooldownRemaining() == 0;

        // 处理充能阶段
        if (isCharging) {
            // 更新充能进度
            effectLevel = Math.min(1f, effectLevel + amount / CHARGE_DURATION);

            // 创建重力充能光晕效果
            createGravityChargingFlare(engine, weapon, effectLevel);

            chargeArcTimer.advance(amount);
            if (chargeArcTimer.intervalElapsed()) {
                createGravityChargingArcs(engine, weapon, ship);
            }

            wasCharging = true;
        } else {
            // 重置充能状态
            if (wasCharging) {
                effectLevel = 0f;
                wasCharging = false;
            }
            chargeArcTimer.setElapsed(0f);
        }


    }

    /**
     * 光束效果处理 - 沿光束路径创建重力扭曲
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused() || beam == null || beam.getBrightness() < 0.8f)
            return;

        distortionTimer.advance(amount);
        if (distortionTimer.intervalElapsed()) {
            createBeamDistortionEffect(beam, engine);
        }
    }

    /**
     * 创建沿光束路径的扭曲效果
     */
    private void createBeamDistortionEffect(BeamAPI beam, CombatEngineAPI engine) {
        Vector2f from = beam.getFrom();
        Vector2f to = beam.getRayEndPrevFrame();
        float length = beam.getLengthPrevFrame();

        if (length < 20f)
            return; // 光束太短时不创建扭曲

        // 沿光束路径创建多个扭曲点
        int distortionCount = (int) (length / 100f) + 1; // 每100单位一个扭曲点

        for (int i = 0; i < distortionCount; i++) {
            float ratio = (float) i / Math.max(1, distortionCount - 1);

            // 计算扭曲点位置
            Vector2f distortionPos = new Vector2f(
                    from.x + (to.x - from.x) * ratio,
                    from.y + (to.y - from.y) * ratio);

            // 添加随机偏移
            Vector2f randomOffset = MathUtils.getRandomPointInCircle(new Vector2f(0, 0), 20f);
            Vector2f.add(distortionPos, randomOffset, distortionPos);

            // 创建涟漪扭曲效果
            RippleDistortion ripple = new RippleDistortion();
            ripple.setLocation(distortionPos);
            ripple.setIntensity(DISTORTION_INTENSITY * (0.5f + 0.5f * (float) Math.random()));
            ripple.setLifetime(0.8f);
            ripple.setFrameRate(60f / 0.8f);
            ripple.setCurrentFrame(0f);
            ripple.setSize(DISTORTION_SIZE * (0.7f + 0.6f * (float) Math.random()));
            ripple.fadeInSize(0.2f);
            ripple.fadeOutIntensity(0.8f);
            ripple.flip(Math.random() > 0.5f);
            DistortionShader.addDistortion(ripple);
        }
    }

    /**
     * 创建充能阶段的重力光晕效果
     * 在武器开火点渲染三层渐变光晕：外圈深紫色、中间蓝紫色、内核白色
     */
    private void createGravityChargingFlare(CombatEngineAPI engine, WeaponAPI weapon, float chargeLevel) {
        Vector2f firePoint = weapon.getFirePoint(0);
        if (firePoint == null) {
            firePoint = weapon.getLocation();
        }

        // 计算光晕尺寸（基于充能等级）
        float flareSize = FLARE_BASE_SIZE + (FLARE_MAX_SIZE - FLARE_BASE_SIZE) * chargeLevel;

        // 计算光晕亮度（基于充能等级）
        float outerAlpha = Math.min(255f, 140f + 100f * chargeLevel);
        float middleAlpha = Math.min(255f, 180f + 75f * chargeLevel);

        // 动态调整颜色透明度
        Color outerColor = new Color(
                FLARE_OUTER.getRed(),
                FLARE_OUTER.getGreen(),
                FLARE_OUTER.getBlue(),
                (int) outerAlpha);
        Color middleColor = new Color(
                FLARE_MIDDLE.getRed(),
                FLARE_MIDDLE.getGreen(),
                FLARE_MIDDLE.getBlue(),
                (int) middleAlpha);
        // 内核颜色保持纯白色满透明度，只改变大小
        Color innerColor = FLARE_INNER;

        // 创建外层光晕（深紫色）
        MagicLensFlare.createSharpFlare(
                engine, weapon.getShip(), firePoint,
                10f, // 光晕尺寸基数
                flareSize * 1.3f, // 外层最大
                weapon.getCurrAngle() + 90f, // 垂直于武器朝向
                outerColor, outerColor);

        // 创建中层光晕（蓝紫色）
        MagicLensFlare.createSharpFlare(
                engine, weapon.getShip(), firePoint,
                8f, // 光晕尺寸基数
                flareSize * 0.9f, // 中层尺寸
                weapon.getCurrAngle() + 90f,
                middleColor, middleColor);

        // 创建内核光晕（白色）
        MagicLensFlare.createSharpFlare(
                engine, weapon.getShip(), firePoint,
                6f, // 光晕尺寸基数
                flareSize * 0.5f, // 内核最小
                weapon.getCurrAngle() + 90f,
                innerColor, innerColor);

        // 充能达到80%时创建额外的重力波扭曲
        if (chargeLevel > 0.8f) {
            createChargingDistortion(firePoint, chargeLevel);
        }
    }

    /**
     * 创建充能阶段的重力扭曲效果
     */
    private void createChargingDistortion(Vector2f firePoint, float chargeLevel) {
        WaveDistortion wave = new WaveDistortion(firePoint, new Vector2f(0, 0));
        wave.setSize(FLARE_MAX_SIZE * chargeLevel);
        wave.setIntensity(30f * chargeLevel);
        wave.setArc(0f, 360f);
        wave.flip(true);
        DistortionShader.addDistortion(wave);
    }

    /**
     * 创建充能阶段的重力电弧效果
     * 从武器开火点连接到所有LIGHT_前缀的武器槽
     */
    private void createGravityChargingArcs(CombatEngineAPI engine, WeaponAPI sourceWeapon, ShipAPI ship) {
        Vector2f firePoint = sourceWeapon.getFirePoint(0);
        if (firePoint == null) {
            firePoint = sourceWeapon.getLocation();
        }

        // 查找所有LIGHT_前缀的武器槽
        List<WeaponAPI> lightWeapons = findLightWeapons(ship);

        if (lightWeapons.isEmpty()) {
            // 如果没有LIGHT_武器，在武器周围创建环形重力电弧
            createFallbackGravityEffect(engine, sourceWeapon, firePoint);
            return;
        }

        // 为每个LIGHT_武器创建重力电弧
        for (WeaponAPI lightWeapon : lightWeapons) {
            Vector2f targetPoint = lightWeapon.getLocation();

            // 计算颜色渐变 (基于充能进度和随机因子)
            float colorRatio = (effectLevel + MathUtils.getRandomNumberInRange(-0.3f, 0.3f));
            colorRatio = Math.max(0f, Math.min(1f, colorRatio));

            Color arcColor = interpolateColor(GRAVITY_DARK, GRAVITY_LIGHT, colorRatio);
            Color coreColor = interpolateColor(GRAVITY_CORE_DARK, GRAVITY_CORE_LIGHT, colorRatio);

            // 计算电弧粗细 (基于充能进度)
            float thickness = ARC_THICKNESS_MIN + (ARC_THICKNESS_MAX - ARC_THICKNESS_MIN) * effectLevel;

            // 创建主重力电弧
            engine.spawnEmpArcVisual(
                    firePoint, sourceWeapon.getShip(),
                    targetPoint, ship,
                    thickness,
                    arcColor,
                    coreColor);

            // 添加次级重力电弧增强效果
            if (effectLevel > 0.6f) {
                Vector2f midPoint = getMidPointWithOffset(firePoint, targetPoint,
                        MathUtils.getRandomNumberInRange(-40f, 40f));

                engine.spawnEmpArcVisual(
                        firePoint, sourceWeapon.getShip(),
                        midPoint, null,
                        thickness * 0.7f,
                        new Color(arcColor.getRed(), arcColor.getGreen(), arcColor.getBlue(), 140),
                        new Color(coreColor.getRed(), coreColor.getGreen(), coreColor.getBlue(), 170));
            }
        }
    }

    /**
     * 查找舰船上所有前缀为LIGHT_的武器
     */
    private List<WeaponAPI> findLightWeapons(ShipAPI ship) {
        List<WeaponAPI> lightWeapons = new ArrayList<WeaponAPI>();

        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot() != null && weapon.getSlot().getId() != null) {
                String slotId = weapon.getSlot().getId();
                if (slotId.startsWith("LIGHT_")) {
                    lightWeapons.add(weapon);
                }
            }
        }

        return lightWeapons;
    }

    /**
     * 备用重力充能效果 - 当没有LIGHT_武器时使用
     */
    private void createFallbackGravityEffect(CombatEngineAPI engine, WeaponAPI weapon, Vector2f firePoint) {
        int arcCount = (int) (6 + effectLevel * 8); // 6-14个电弧

        for (int i = 0; i < arcCount; i++) {
            float angle = (360f / arcCount) * i + MathUtils.getRandomNumberInRange(-25f, 25f);
            float distance = 50f + effectLevel * 30f;

            Vector2f arcEnd = MathUtils.getPointOnCircumference(firePoint, distance, angle);

            float colorRatio = MathUtils.getRandomNumberInRange(0f, 1f);
            Color arcColor = interpolateColor(GRAVITY_DARK, GRAVITY_LIGHT, colorRatio);
            Color coreColor = interpolateColor(GRAVITY_CORE_DARK, GRAVITY_CORE_LIGHT, colorRatio);

            engine.spawnEmpArcVisual(
                    firePoint, weapon.getShip(),
                    arcEnd, weapon.getShip(),
                    ARC_THICKNESS_MIN + effectLevel * 4f,
                    arcColor,
                    coreColor);
        }
    }





    /**
     * 颜色插值函数
     */
    private Color interpolateColor(Color color1, Color color2, float ratio) {
        ratio = Math.max(0f, Math.min(1f, ratio));

        int r = (int) (color1.getRed() + ratio * (color2.getRed() - color1.getRed()));
        int g = (int) (color1.getGreen() + ratio * (color2.getGreen() - color1.getGreen()));
        int b = (int) (color1.getBlue() + ratio * (color2.getBlue() - color1.getBlue()));
        int a = (int) (color1.getAlpha() + ratio * (color2.getAlpha() - color1.getAlpha()));

        return new Color(r, g, b, a);
    }

    /**
     * 获取两点间的中点，并添加垂直偏移
     */
    private Vector2f getMidPointWithOffset(Vector2f start, Vector2f end, float offset) {
        Vector2f midPoint = new Vector2f(
                (start.x + end.x) / 2f,
                (start.y + end.y) / 2f);

        // 计算垂直于连线的偏移向量
        Vector2f direction = Vector2f.sub(end, start, null);
        Vector2f perpendicular = new Vector2f(-direction.y, direction.x);
        perpendicular.normalise();
        perpendicular.scale(offset);

        return Vector2f.add(midPoint, perpendicular, null);
    }
}