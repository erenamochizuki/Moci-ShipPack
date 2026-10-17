package data.scripts.weapons;

import java.awt.Color;

import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.DistortionEntity;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 增强光束武器 - 光束效果插件
 * 
 * 功能：
 * 1. 硬幅能转换 - 光束伤害全部转化为硬幅能
 * 2. 光束路径粒子 - 沿光束路径生成粒子特效
 * 3. 命中点粒子爆发 - 光束命中点的粒子效果
 */
public class Moci_Magnum_BeamEffect implements BeamEffectPlugin {

    /** 通用零向量（保留给可能的零速度/零偏移使用）。 */
    private static final Vector2f ZERO = new Vector2f();
    
    // ========== 硬幅能转换开关 ==========
    private static final boolean ENABLE_HARD_FLUX = true;
    
    // ========== 光束路径粒子参数 ==========
    /** 是否启用路径粒子。 */
    private static final boolean ENABLE_PATH_PARTICLES = true;
    /** 路径粒子尺寸下限。 */
    private static final float PATH_PARTICLE_SIZE_MIN = 5f;
    /** 路径粒子尺寸上限。 */
    private static final float PATH_PARTICLE_SIZE_MAX = 10f;
    /** 路径粒子持续时间下限。 */
    private static final float PATH_PARTICLE_DURATION_MIN = 0.4f;
    /** 路径粒子持续时间上限。 */
    private static final float PATH_PARTICLE_DURATION_MAX = 0.9f;
    private static final float PATH_PARTICLE_INERTIA_MULT = 0.5f; // 粒子保留舰船速度的比例
    private static final float PATH_PARTICLE_DRIFT = 50f; // 粒子随机漂移速度
    private static final float PATH_PARTICLE_DENSITY = 0.05f; // 粒子密度
    private static final float PATH_PARTICLE_SPAWN_WIDTH_MULT = 0.5f; // 粒子生成宽度倍数
    private static final float PATH_PARTICLE_SPAWN_CHANCE = 0.7f; // 粒子生成概率
    
    // ========== 命中点粒子参数 ==========
    private static final boolean ENABLE_HIT_BURST = false; // 命中点粒子爆发开关
    /** 命中点爆发扇形角度范围。 */
    private static final float HIT_PARTICLE_ANGLE_SPREAD = 150f;
    /** 命中点粒子基础亮度。 */
    private static final float HIT_PARTICLE_BRIGHTNESS = 1f;
    /** 命中点粒子最远扩散距离。 */
    private static final float HIT_PARTICLE_DISTANCE_MAX = 150f;
    /** 命中点粒子最近扩散距离。 */
    private static final float HIT_PARTICLE_DISTANCE_MIN = 100f;
    /** 命中点粒子持续时间。 */
    private static final float HIT_PARTICLE_DURATION = 0.5f;
    /** 命中点粒子尺寸上限。 */
    private static final float HIT_PARTICLE_SIZE_MAX = 5f;
    /** 命中点粒子尺寸下限。 */
    private static final float HIT_PARTICLE_SIZE_MIN = 1f;
    /** 命中点粒子速度系数下限。 */
    private static final float HIT_PARTICLE_VEL_MIN = 1f;
    /** 命中点粒子速度系数上限。 */
    private static final float HIT_PARTICLE_VEL_MAX = 1.5f;
    private static final float HIT_BURST_CHANCE = 0.5f; // 50%概率生成爆发
    /** 命中点爆发的基础粒子数量。 */
    private static final int HIT_BURST_COUNT_BASE = 1;
    /** 命中点爆发随蓄力额外增加的粒子数量倍率。 */
    private static final int HIT_BURST_COUNT_MULT = 3;
    private static final float HIT_BURST_FRINGE_COLOR_CHANCE = 0.75f; // 75%概率使用边缘色
    /** 命中点爆发主色（偏红）。 */
    private static final Color RED_COLOR = new Color(143, 9, 58, 255);
    /** 命中点爆发辅色（偏紫）。 */
    private static final Color PURPLE_COLOR = new Color(111, 76, 144, 255);
    
    // ========== 命中扭曲波参数 ==========
    /** 是否启用命中点扭曲波。 */
    private static final boolean ENABLE_HIT_DISTORTION_BURST = true;
    /** 扭曲波主体尺寸。 */
    private static final float HIT_DISTORTION_SIZE = 150f;
    /** 扭曲波淡入时间。 */
    private static final float HIT_DISTORTION_FADE_IN = 0f;
    /** 扭曲波满强度持续时间。 */
    private static final float HIT_DISTORTION_DURATION = 0f;
    /** 扭曲波淡出时间。 */
    private static final float HIT_DISTORTION_FADE_OUT = 0.25f;
    /** 扭曲波最大强度。 */
    private static final float HIT_DISTORTION_POWER = 1f;
    /** 扭曲波外圈尺寸倍率。 */
    private static final float HIT_DISTORTION_OUT_SIZE_MULT = 2f;
    
    // ========== 光束出现时宽度过渡 ==========
    /** 光束刚激活时的宽度倍率。 */
    private static final float BEAM_SPAWN_WIDTH_MULT = 5f;
    /** 光束宽度回落到正常值所需时间。 */
    private static final float BEAM_SPAWN_WIDTH_DURATION = 0.15f;

    /** 上一帧光束是否处于激活状态。 */
    private boolean wasBeamActive = false;
    /** 本次射击是否已经触发过“首次命中”特效。 */
    private boolean hasTriggeredHitEffectsThisShot = false;
    /** 光束正常宽度（用于出现时的宽度过渡）。 */
    private float baseBeamWidth = -1f;
    /** 光束宽度过渡计时器，<0 表示不在过渡中。 */
    private float beamSpawnScaleTimer = -1f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused()) {
            return;
        }
        
        WeaponAPI weapon = beam.getWeapon();
        if (weapon == null) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        boolean beamActive = isBeamActive(beam, weapon);
        if (!beamActive && beam.getWidth() > 0f) {
            baseBeamWidth = beam.getWidth();
        }

        if (beamActive && !wasBeamActive) {
            if (beam.getWidth() > 0f) {
                baseBeamWidth = beam.getWidth();
            }
            beamSpawnScaleTimer = 0f;
            hasTriggeredHitEffectsThisShot = false;
        }

        applyBeamSpawnScale(beam, amount, beamActive);
        wasBeamActive = beamActive;

        // 每次开火仅在“首次命中”时触发一次命中点特效
        if (!beamActive) {
            hasTriggeredHitEffectsThisShot = false;
        } else if (!hasTriggeredHitEffectsThisShot && isBeamActuallyHitting(beam)) {
            createHitDistortionBurst(beam);
            if (ENABLE_HIT_BURST) {
                generateHitBurstParticles(engine, beam, weapon);
            }
            hasTriggeredHitEffectsThisShot = true;
        }
        
        // 1. 硬幅能转换
        if (ENABLE_HARD_FLUX && beam.getDamage() != null) {
            beam.getDamage().setForceHardFlux(true);
        }
        
        // 屏幕剔除优化
        if (!MagicRender.screenCheck(0.2f, weapon.getLocation())) {
            return;
        }
        
        // 2. 光束路径粒子效果
        if (ENABLE_PATH_PARTICLES) {
            generatePathParticles(engine, beam, weapon, ship, amount);
        }
    }
    
    /** 判断光束是否处于“有效激活”状态。 */
    private boolean isBeamActive(BeamAPI beam, WeaponAPI weapon) {
        if (!weapon.isFiring()) {
            return false;
        }
        if (beam.getBrightness() <= 0.1f) {
            return false;
        }
        return MathUtils.getDistanceSquared(beam.getFrom(), beam.getTo()) > 1f;
    }

    /** 判断光束这一帧是否真的打在可受伤害目标上。 */
    private boolean isBeamActuallyHitting(BeamAPI beam) {
        return beam.getDamageTarget() != null && beam.didDamageThisFrame();
    }

    /**
     * 光束出现时：先放大宽度，再在短时间内回落至正常宽度。
     */
    private void applyBeamSpawnScale(BeamAPI beam, float amount, boolean beamActive) {
        if (baseBeamWidth <= 0f) {
            if (beam.getWidth() > 0f) {
                baseBeamWidth = beam.getWidth();
            } else {
                return;
            }
        }

        if (!beamActive) {
            beamSpawnScaleTimer = -1f;
            beam.setWidth(baseBeamWidth);
            return;
        }

        if (beamSpawnScaleTimer < 0f) {
            beam.setWidth(baseBeamWidth);
            return;
        }

        beamSpawnScaleTimer += amount;
        float progress = Math.min(beamSpawnScaleTimer / BEAM_SPAWN_WIDTH_DURATION, 1f);
        float widthMult = BEAM_SPAWN_WIDTH_MULT - (BEAM_SPAWN_WIDTH_MULT - 1f) * progress;
        beam.setWidth(baseBeamWidth * widthMult);

        if (progress >= 1f) {
            beamSpawnScaleTimer = -1f;
            beam.setWidth(baseBeamWidth);
        }
    }

    /**
     * 在光束命中点创建一次扭曲波视觉爆发。
     */
    private void createHitDistortionBurst(BeamAPI beam) {
        if (!ENABLE_HIT_DISTORTION_BURST) {
            return;
        }

        Vector2f hitPoint = beam.getTo();
        if (hitPoint == null) {
            return;
        }

        DistortionEntity distortion = new DistortionEntity();
        distortion.setGlobalTimer(HIT_DISTORTION_FADE_IN, HIT_DISTORTION_DURATION, HIT_DISTORTION_FADE_OUT);
        distortion.setInnerFull(0.8f, 0.8f);
        distortion.setInnerHardness(0.5f);
        distortion.setRingHardness(0.5f);
        distortion.setSizeIn(0f, 0f);
        distortion.setSizeFull(HIT_DISTORTION_SIZE, HIT_DISTORTION_SIZE);
        distortion.setSizeOut(HIT_DISTORTION_SIZE * HIT_DISTORTION_OUT_SIZE_MULT,
                HIT_DISTORTION_SIZE * HIT_DISTORTION_OUT_SIZE_MULT);
        distortion.setPowerIn(0f);
        distortion.setPowerFull(HIT_DISTORTION_POWER);
        distortion.setPowerOut(0f);
        distortion.setLocation(hitPoint);
        CombatRenderingManager.addEntity(distortion);
    }

    private void generateHitBurstParticles(CombatEngineAPI engine, BeamAPI beam, WeaponAPI weapon) {
        // 只在开火时生成
        if (!weapon.isFiring()) {
            return;
        }
        
        // 50%概率生成
        if (Math.random() > HIT_BURST_CHANCE) {
            return;
        }
        
        float facing = weapon.getCurrAngle();
        float halfSpread = HIT_PARTICLE_ANGLE_SPREAD / 2f;
        float chargeLevel = Math.max(weapon.getChargeLevel(), 0.5f);
        int count = HIT_BURST_COUNT_BASE + (int) (chargeLevel * HIT_BURST_COUNT_MULT);
        
        for (int i = 0; i < count; i++) {
            // 75%概率使用边缘色，25%概率使用指定色
            Color color = Math.random() <= HIT_BURST_FRINGE_COLOR_CHANCE 
                ? RED_COLOR
                : PURPLE_COLOR;
            
            float distance = MathUtils.getRandomNumberInRange(
                HIT_PARTICLE_DISTANCE_MIN + 1f,
                HIT_PARTICLE_DISTANCE_MAX + 1f
            ) * chargeLevel;
            
            float speed = 0.75f * distance / HIT_PARTICLE_DURATION * chargeLevel;
            float angle = MathUtils.getRandomNumberInRange(facing - halfSpread, facing + halfSpread);
            float vel = MathUtils.getRandomNumberInRange(speed * -HIT_PARTICLE_VEL_MIN, speed * -HIT_PARTICLE_VEL_MAX);
            Vector2f velocity = MathUtils.getPointOnCircumference(null, vel, angle);

            float size = MathUtils.getRandomNumberInRange(
                HIT_PARTICLE_SIZE_MIN + 1f,
                HIT_PARTICLE_SIZE_MAX + 5f
            ) * chargeLevel;
            
            float brightness = HIT_PARTICLE_BRIGHTNESS 
                * Math.min(chargeLevel + 0.5f, 1f) 
                * MathUtils.getRandomNumberInRange(0.75f, 1.25f);
            
            engine.addSmoothParticle(
                beam.getTo(),
                velocity,
                size,
                brightness,
                HIT_PARTICLE_DURATION,
                new Color(
                        color.getRed() / 255f,
                        color.getGreen() / 255f,
                        color.getBlue() / 255f,
                    (color.getAlpha() / 255f) * chargeLevel
                )
            );
        }
    }
    
    /**
     * 生成光束路径粒子效果
     */
    private void generatePathParticles(CombatEngineAPI engine, BeamAPI beam, WeaponAPI weapon, 
                                      ShipAPI ship, float amount) {
        float beamWidth = beam.getWidth();
        
        // 计算本帧应生成的粒子数量
        float beamLength = MathUtils.getDistance(beam.getTo(), beam.getFrom());
        float chargeLevel = Math.max(weapon.getChargeLevel(), 0.5f);
        float particleCount = beamWidth * PATH_PARTICLE_SPAWN_WIDTH_MULT 
            * beamLength * amount * PATH_PARTICLE_DENSITY * chargeLevel;

        // 概率控制生成密度
        if (Math.random() >= PATH_PARTICLE_SPAWN_CHANCE) {
            return;
        }
        
        for (int i = 0; i < particleCount; i++) {
            // 在光束路径上随机取点
            Vector2f spawnPoint = MathUtils.getRandomPointOnLine(beam.getFrom(), beam.getTo());
            
            // 根据宽度偏移
            spawnPoint = MathUtils.getRandomPointInCircle(spawnPoint, 
                beamWidth * PATH_PARTICLE_SPAWN_WIDTH_MULT);

            // 屏幕外剔除
            if (!Global.getCombatEngine().getViewport().isNearViewport(spawnPoint, 
                PATH_PARTICLE_SIZE_MAX * 3f)) {
                continue;
            }

            // 计算速度（保留部分舰船速度）
            Vector2f velocity = new Vector2f(
                ship.getVelocity().x * PATH_PARTICLE_INERTIA_MULT,
                ship.getVelocity().y * PATH_PARTICLE_INERTIA_MULT
            );
            velocity = MathUtils.getRandomPointInCircle(velocity, PATH_PARTICLE_DRIFT);

            // 生成粒子
            Color particleColor = blendColors(
                    PURPLE_COLOR, RED_COLOR, 0.7f
            );
            
            engine.addSmoothParticle(
                spawnPoint,
                velocity,
                MathUtils.getRandomNumberInRange(PATH_PARTICLE_SIZE_MIN, PATH_PARTICLE_SIZE_MAX),
                chargeLevel,
                MathUtils.getRandomNumberInRange(PATH_PARTICLE_DURATION_MIN, PATH_PARTICLE_DURATION_MAX),
                particleColor
            );
        }
    }

    /**
     * 混合两个颜色
     * @param color1 颜色1
     * @param color2 颜色2
     * @param ratio 颜色1的比例（0-1）
     * @return 混合后的颜色
     */
    private Color blendColors(Color color1, Color color2, float ratio) {
        float invRatio = 1f - ratio;
        int r = (int) (color1.getRed() * ratio + color2.getRed() * invRatio);
        int g = (int) (color1.getGreen() * ratio + color2.getGreen() * invRatio);
        int b = (int) (color1.getBlue() * ratio + color2.getBlue() * invRatio);
        int a = (int) (color1.getAlpha() * ratio + color2.getAlpha() * invRatio);
        return new Color(r, g, b, a);
    }
}
