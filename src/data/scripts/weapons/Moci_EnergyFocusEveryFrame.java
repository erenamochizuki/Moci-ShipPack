package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.boxutil.define.BoxEnum;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.CurveEntity;
import org.magiclib.util.MagicLensFlare;
import org.magiclib.util.MagicRender;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 能量聚焦炮 - 每帧效果控制器
 * 结合II_WaveMotion的蓄力机制和BoxUtil的曲线渲染系统
 * 
 * 特点:
 * 1. 蓄力阶段: 使用BoxUtil的CurveEntity创建能量聚集曲线
 * 2. 开火阶段: 能量曲线汇聚到炮口
 * 3. 冷却阶段: 残余能量扩散效果
 */
public class Moci_EnergyFocusEveryFrame implements EveryFrameWeaponEffectPlugin {

    // 蓄力粒子参数
    private static final float CHARGE_PARTICLE_DISTANCE_MIN = 80.0f;
    private static final float CHARGE_PARTICLE_DISTANCE_MAX = 300.0f;
    private static final float CHARGE_PARTICLE_SIZE_MIN = 5.0f;
    private static final float CHARGE_PARTICLE_SIZE_MAX = 15.0f;
    private static final float CHARGE_PARTICLE_DURATION = 0.8f;
    private static final float CHARGE_PARTICLE_BRIGHTNESS = 1.0f;

    // 蓄力曲线参数
    private static final int CHARGE_CURVE_COUNT = 8; // 能量曲线数量
    private static final float CHARGE_CURVE_WIDTH = 3.0f;

    // 光晕参数
    private static final float FLARE_BASE_SIZE = 20.0f;
    private static final float FLARE_MAX_SIZE = 100.0f;
    private static final Color FLARE_OUTER = new Color(100, 200, 255, 80);
    private static final Color FLARE_MIDDLE = new Color(150, 220, 255, 100);
    private static final Color FLARE_INNER = new Color(255, 255, 255, 200);

    // fx贴图参数
    private static final float FX_SCALE = 1.5f;

    // 状态字段
    private boolean charging = false;
    private boolean firing = false;
    private boolean cooling = false;
    private float level = 0.0f; // 能量等级 0-1

    // BoxUtil曲线实体列表
    private final List<CurveEntity> chargeCurves = new ArrayList<CurveEntity>();

    // 时间控制
    private float chargeTimer = 0.0f;
    private float effectTimer = 0.0f;
    private final IntervalUtil particleTimer = new IntervalUtil(0.05f, 0.08f);
    private final IntervalUtil flareTimer = new IntervalUtil(0.1f, 0.1f);

    // fx特效状态
    private float fx1Size = 0.0f;
    private float fx3Size = 0.0f;
    private float fx2Rotation = 0.0f;
    private float fx4Rotation = 0.0f;
    private float fx2Alpha = 0.0f;
    private float fx4Alpha = 0.0f;
    private final IntervalUtil fxBlinkTimer = new IntervalUtil(0.15f, 0.25f);

    // 音效控制
    private boolean chargeLightPlayed = false;
    private boolean chargeHeavyPlayed = false;
    private boolean loopSoundPlayed = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || weapon == null)
            return;
        if (engine.isPaused())
            return;

        // 状态检测
        boolean wasCharging = charging;
        boolean wasFiring = firing;

        // 使用chargeLevel检测充能状态（0-1之间）
        float chargeLevel = weapon.getChargeLevel();
        charging = chargeLevel > 0.0f && chargeLevel < 1.0f;
        firing = weapon.isFiring();
        cooling = !charging && !firing && level > 0.0f;

        // 能量等级更新
        if (charging) {
            level = Math.min(1.0f, level + amount * 2.0f); // 0.5秒充满
            chargeTimer += amount;
        } else if (firing) {
            level = 1.0f;
        } else if (cooling) {
            level = Math.max(0.0f, level - amount * 3.0f); // 0.33秒冷却
        }

        effectTimer += amount;

        // 蓄力开始
        if (charging && !wasCharging) {
            onChargeStart(weapon);
        }

        // 蓄力中
        if (charging) {
            updateChargeEffect(amount, engine, weapon);
        }

        // 开火开始
        if (firing && !wasFiring) {
            onFireStart(weapon);
        }

        // 冷却开始
        if (cooling && (wasFiring || wasCharging)) {
            onCoolStart(weapon);
        }

        // 更新曲线效果
        updateCurves(amount, weapon);

        // 清理失效的实体
        cleanupEntities();
    }

    /**
     * 蓄力开始
     */
    private void onChargeStart(WeaponAPI weapon) {
        chargeTimer = 0.0f;
        chargeLightPlayed = false;
        chargeHeavyPlayed = false;
        loopSoundPlayed = false;
        resetFxEffects();

        // 播放轻度蓄力音效
        Global.getSoundPlayer().playSound(
                "Moci_WaveCannon_charge_light",
                1.0f,
                1.0f,
                weapon.getLocation(),
                new Vector2f(0, 0));
        chargeLightPlayed = true;
    }

    /**
     * 更新蓄力效果
     */
    private void updateChargeEffect(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        Vector2f firePoint = weapon.getFirePoint(0);
        if (firePoint == null) {
            firePoint = weapon.getLocation();
        }

        // 生成向开火点汇聚的粒子
        particleTimer.advance(amount);
        if (particleTimer.intervalElapsed()) {
            spawnConvergingParticles(engine, weapon, firePoint);
        }

        // 生成光晕效果
        flareTimer.advance(amount);
        if (flareTimer.intervalElapsed()) {
            createChargingFlare(engine, weapon, firePoint);
        }

        // 更新fx特效
        updateFxEffects(amount);

        // 渲染fx特效
        renderFxEffects(engine, weapon, firePoint);

        // 蓄力音效系统
        updateChargeSounds(weapon, firePoint);
    }

    /**
     * 生成向开火点汇聚的粒子
     */
    private void spawnConvergingParticles(CombatEngineAPI engine, WeaponAPI weapon, Vector2f firePoint) {
        // 粒子数量随蓄力程度增加
        int particleCount = (int) (3 + level * 12); // 3-15个粒子

        // 粒子生成范围随蓄力程度扩大
        float minDistance = CHARGE_PARTICLE_DISTANCE_MIN + level * 50.0f;
        float maxDistance = CHARGE_PARTICLE_DISTANCE_MAX + level * 100.0f;

        for (int i = 0; i < particleCount; i++) {
            // 在圆形范围内随机生成粒子起始位置
            float angle = (float) Math.random() * 360.0f;
            float distance = MathUtils.getRandomNumberInRange(minDistance, maxDistance);
            Vector2f startPos = MathUtils.getPointOnCircumference(firePoint, distance, angle);

            // 计算指向开火点的速度
            Vector2f velocity = Vector2f.sub(firePoint, startPos, null);
            float speed = distance / CHARGE_PARTICLE_DURATION; // 根据距离调整速度
            velocity.normalise();
            velocity.scale(speed);

            // 粒子大小随蓄力程度增加
            float size = MathUtils.getRandomNumberInRange(
                    CHARGE_PARTICLE_SIZE_MIN * (0.5f + level * 0.5f),
                    CHARGE_PARTICLE_SIZE_MAX * (0.5f + level * 0.5f));

            // 粒子颜色 - 青蓝色
            Color particleColor = new Color(100, 200, 255, (int) (150 + level * 105));

            // 生成粒子
            engine.addSmoothParticle(
                    startPos,
                    velocity,
                    size,
                    CHARGE_PARTICLE_BRIGHTNESS,
                    CHARGE_PARTICLE_DURATION,
                    particleColor);
        }
    }

    /**
     * 创建蓄力光晕效果
     */
    private void createChargingFlare(CombatEngineAPI engine, WeaponAPI weapon, Vector2f firePoint) {
        float baseSize = FLARE_BASE_SIZE + (FLARE_MAX_SIZE - FLARE_BASE_SIZE) * level;

        // 外圈光晕（充能10%以上）
        if (level > 0.1f) {
            float outerAlpha = Math.min(80f, 30f + 50f * level);
            Color outerColor = new Color(
                    FLARE_OUTER.getRed(),
                    FLARE_OUTER.getGreen(),
                    FLARE_OUTER.getBlue(),
                    (int) outerAlpha);

            float outerSize = baseSize * (0.4f + 0.8f * level);

            MagicLensFlare.createSharpFlare(
                    engine,
                    weapon.getShip(),
                    firePoint,
                    4.0f,
                    outerSize,
                    weapon.getCurrAngle() + 90.0f,
                    outerColor,
                    outerColor);
        }

        // 中间光晕（充能50%以上）
        if (level > 0.5f) {
            float middleProgress = (level - 0.5f) / 0.5f;
            float middleAlpha = Math.min(100f, 40f + 60f * middleProgress);
            Color middleColor = new Color(
                    FLARE_MIDDLE.getRed(),
                    FLARE_MIDDLE.getGreen(),
                    FLARE_MIDDLE.getBlue(),
                    (int) middleAlpha);

            float middleSize = baseSize * (0.2f + 0.3f * middleProgress);

            MagicLensFlare.createSharpFlare(
                    engine,
                    weapon.getShip(),
                    firePoint,
                    3.0f,
                    middleSize,
                    weapon.getCurrAngle() + 90.0f,
                    middleColor,
                    middleColor);
        }

        // 内核光晕（充能85%以上）
        if (level > 0.85f) {
            float innerProgress = (level - 0.85f) / 0.15f;
            Color innerColor = new Color(255, 255, 255, (int) (150f * innerProgress));

            float innerSize = baseSize * (0.05f + 0.1f * innerProgress);

            MagicLensFlare.createSharpFlare(
                    engine,
                    weapon.getShip(),
                    firePoint,
                    2.0f,
                    innerSize,
                    weapon.getCurrAngle() + 90.0f,
                    innerColor,
                    innerColor);
        }
    }

    /**
     * 更新fx特效状态
     */
    private void updateFxEffects(float amount) {
        // fx1和fx3从充能开始就增长
        if (level <= 0.5f) {
            fx1Size = level / 0.5f;
            fx3Size = level / 0.5f;
        } else {
            fx1Size = 1.0f;
            fx3Size = 1.0f;
        }

        // fx2和fx4在充能50%以上时显示
        if (level >= 0.5f) {
            fx2Rotation += amount * 120.0f;
            fx4Rotation += amount * 90.0f;

            if (fx2Rotation >= 360.0f)
                fx2Rotation -= 360.0f;
            if (fx4Rotation >= 360.0f)
                fx4Rotation -= 360.0f;

            fxBlinkTimer.advance(amount);
            if (fxBlinkTimer.intervalElapsed()) {
                fx2Alpha = 180.0f + 75.0f * (float) Math.random();
                fx4Alpha = 180.0f + 75.0f * (float) Math.random();
            }

            float fadeInProgress = (level - 0.5f) / 0.5f;
            fx2Alpha *= fadeInProgress;
            fx4Alpha *= fadeInProgress;
        } else {
            fx2Alpha = 0.0f;
            fx4Alpha = 0.0f;
        }
    }

    /**
     * 渲染fx特效
     */
    private void renderFxEffects(CombatEngineAPI engine, WeaponAPI weapon, Vector2f firePoint) {
        // 渲染fx1
        if (fx1Size > 0.0f) {
            float fx1Alpha = 150.0f + 105.0f * level;
            Color fx1Color = new Color(255, 255, 255, (int) fx1Alpha);
            float fx1Width = 294 * FX_SCALE * fx1Size;
            float fx1Height = 294 * FX_SCALE * fx1Size;

            MagicRender.singleframe(
                    Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_1"),
                    firePoint,
                    new Vector2f(fx1Width, fx1Height),
                    0.0f,
                    fx1Color,
                    true);
        }

        // 渲染fx3
        if (fx3Size > 0.0f) {
            float fx3Alpha = 150.0f + 105.0f * level;
            Color fx3Color = new Color(255, 255, 255, (int) fx3Alpha);
            float fx3Width = 44 * FX_SCALE * fx3Size;
            float fx3Height = 44 * FX_SCALE * fx3Size;

            MagicRender.singleframe(
                    Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_3"),
                    firePoint,
                    new Vector2f(fx3Width, fx3Height),
                    0.0f,
                    fx3Color,
                    true);
        }

        // 渲染fx2
        if (fx2Alpha > 0.0f) {
            Color fx2Color = new Color(255, 255, 255, (int) fx2Alpha);
            float fx2Width = 190 * FX_SCALE;
            float fx2Height = 190 * FX_SCALE;

            MagicRender.singleframe(
                    Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_2"),
                    firePoint,
                    new Vector2f(fx2Width, fx2Height),
                    fx2Rotation,
                    fx2Color,
                    true);
        }

        // 渲染fx4
        if (fx4Alpha > 0.0f) {
            Color fx4Color = new Color(255, 255, 255, (int) fx4Alpha);
            float fx4Width = 212 * FX_SCALE;
            float fx4Height = 212 * FX_SCALE;

            MagicRender.singleframe(
                    Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_4"),
                    firePoint,
                    new Vector2f(fx4Width, fx4Height),
                    fx4Rotation,
                    fx4Color,
                    true);
        }
    }

    /**
     * 重置fx特效
     */
    private void resetFxEffects() {
        fx1Size = 0.0f;
        fx3Size = 0.0f;
        fx2Rotation = 0.0f;
        fx4Rotation = 0.0f;
        fx2Alpha = 0.0f;
        fx4Alpha = 0.0f;
        fxBlinkTimer.setElapsed(0.0f);
    }

    /**
     * 更新蓄力音效系统
     */
    private void updateChargeSounds(WeaponAPI weapon, Vector2f firePoint) {
        // 充能50%时播放重度蓄力音效
        if (!chargeHeavyPlayed && level >= 0.5f) {
            chargeHeavyPlayed = true;
            Global.getSoundPlayer().playSound(
                    "Moci_WaveCannon_charge_heavy",
                    1.0f,
                    1.0f,
                    firePoint,
                    weapon.getShip().getVelocity());
        }

        // 充能70%以上时播放循环音效（只播放一次）
        if (!loopSoundPlayed && level >= 0.7f) {
            loopSoundPlayed = true;
            // 播放循环音效，音调根据当前充能等级
            Global.getSoundPlayer().playLoop(
                    "Moci_WaveCannon_loop",
                    weapon.getShip(),
                    1.0f,
                    1.0f + level * 0.3f,
                    firePoint,
                    weapon.getShip().getVelocity());
        }
    }

    /**
     * 开火开始
     */
    private void onFireStart(WeaponAPI weapon) {
        Vector2f firePoint = weapon.getFirePoint(0);
        if (firePoint == null) {
            firePoint = weapon.getLocation();
        }

        // 播放开火音效（根据蓄力等级选择）
        if (level >= 0.8f) {
            // 高蓄力 - 播放重型开火音效
            Global.getSoundPlayer().playSound(
                    "Moci_WaveCannon_shot_heavy",
                    1.0f,
                    0.9f + level * 0.2f,
                    firePoint,
                    weapon.getShip().getVelocity());
        } else {
            // 低蓄力 - 播放轻型开火音效
            Global.getSoundPlayer().playSound(
                    "Moci_WaveCannon_shot_light",
                    1.0f,
                    1.0f,
                    firePoint,
                    weapon.getShip().getVelocity());
        }

        // 重置fx特效
        resetFxEffects();
    }

    /**
     * 冷却开始
     */
    private void onCoolStart(WeaponAPI weapon) {
        Vector2f firePoint = weapon.getFirePoint(0);
        if (firePoint == null) {
            firePoint = weapon.getLocation();
        }

        // 播放结束音效
        Global.getSoundPlayer().playSound(
                "Moci_WaveCannon_end",
                1.0f,
                1.0f,
                firePoint,
                weapon.getShip().getVelocity());

        // 重置fx特效
        resetFxEffects();
    }

    /**
     * 更新曲线效果
     */
    private void updateCurves(float amount, WeaponAPI weapon) {
        // 不再使用曲线，保留方法以兼容
        // 如果需要使用BoxUtil曲线，请在此处添加BoxUtil可用性检查
    }

    /**
     * 清理失效的实体
     */
    private void cleanupEntities() {
        // 不再使用曲线，保留方法以兼容
        // 如果需要使用BoxUtil曲线，请在此处添加BoxUtil可用性检查
    }

    /**
     * 获取当前能量等级
     */
    public float getLevel() {
        return level;
    }
}
