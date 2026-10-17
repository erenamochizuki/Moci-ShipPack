package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.lazywizard.lazylib.MathUtils;
import org.magiclib.util.MagicRender;
import java.awt.Color;

/**
 * 蓄力光束武器 - 光束发射特效
 * 负责光束发射时的视觉效果：命中点粒子爆发、光束路径粒子、命中闪光
 * 
 * 职责：
 * - 光束命中点的粒子爆发效果
 * - 光束命中点的闪光效果
 * - 光束路径上的粒子效果
 */
public class Moci_ChargeBeam_BeamEffect implements BeamEffectPlugin {

    private static final Vector2f ZERO = new Vector2f();
    
    // 命中点粒子参数
    private static final float HIT_PARTICLE_ANGLE_SPREAD = 150f;
    private static final float HIT_PARTICLE_BRIGHTNESS = 1f;
    private static final float HIT_PARTICLE_DISTANCE_MAX = 150f;
    private static final float HIT_PARTICLE_DISTANCE_MIN = 100f;
    private static final float HIT_PARTICLE_DURATION = 0.5f;
    private static final float HIT_PARTICLE_SIZE_MAX = 5f;
    private static final float HIT_PARTICLE_SIZE_MIN = 1f;
    private static final float HIT_PARTICLE_VEL_MIN = 1f;
    private static final float HIT_PARTICLE_VEL_MAX = 1.5f;
    
    // 光束路径粒子参数
    private static final float PATH_PARTICLE_SIZE_MIN = 5f;
    private static final float PATH_PARTICLE_SIZE_MAX = 10f;
    private static final float PATH_PARTICLE_DURATION_MIN = 0.4f;
    private static final float PATH_PARTICLE_DURATION_MAX = 0.9f;
    private static final float PATH_PARTICLE_INERTIA_MULT = 0.5f; // 粒子保留舰船速度的比例
    private static final float PATH_PARTICLE_DRIFT = 50f; // 粒子随机漂移速度
    private static final float PATH_PARTICLE_DENSITY = 0.05f; // 粒子密度（每平方单位每秒）
    private static final float PATH_PARTICLE_SPAWN_WIDTH_MULT = 0.5f; // 粒子生成宽度倍数
    
    // 命中点闪光参数
    private static final float HIT_FLASH_CHANCE = 0.05f; // 5%概率生成闪光
    private static final float HIT_FLASH_SIZE_MULT = 1.75f;
    private static final float HIT_FLASH_BRIGHTNESS_BASE = 0.1f;
    private static final float HIT_FLASH_BRIGHTNESS_MULT = 0.3f;
    private static final float HIT_FLASH_DURATION = 0.2f;
    
    // 命中点粒子爆发参数
    private static final float HIT_BURST_CHANCE = 0.5f; // 50%概率生成爆发
    private static final int HIT_BURST_COUNT_BASE = 1;
    private static final int HIT_BURST_COUNT_MULT = 3;
    private static final float HIT_BURST_FRINGE_COLOR_CHANCE = 0.75f; // 75%概率使用边缘色
    
    private boolean hasStartedFiring = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused()) {
            return;
        }
        
        WeaponAPI weapon = beam.getWeapon();
        ShipAPI ship = weapon.getShip();
        
        // 标记已开始发射（用于路径粒子）
        if (weapon.getChargeLevel() >= 1f) {
            hasStartedFiring = true;
        }
        
        // 屏幕剔除优化
        if (!MagicRender.screenCheck(0.2f, weapon.getLocation())) {
            return;
        }
        
        // 1. 命中点粒子爆发效果
        generateHitBurstParticles(engine, beam, weapon);
        
        // 2. 命中点闪光效果
        generateHitFlash(engine, beam, weapon);
        
        // 3. 光束路径粒子效果
        if (hasStartedFiring) {
            generatePathParticles(engine, beam, weapon, ship, amount);
        }
    }
    
    /**
     * 生成命中点粒子爆发效果
     */
    private void generateHitBurstParticles(CombatEngineAPI engine, BeamAPI beam, WeaponAPI weapon) {
        // 只在蓄力或开火时生成
        if (!weapon.isFiring() && weapon.getChargeLevel() <= 0f) {
            return;
        }
        
        // 50%概率生成
        if (Math.random() > HIT_BURST_CHANCE) {
            return;
        }
        
        float facing = weapon.getCurrAngle();
        float halfSpread = HIT_PARTICLE_ANGLE_SPREAD / 2f;
        int count = HIT_BURST_COUNT_BASE + (int) (weapon.getChargeLevel() * HIT_BURST_COUNT_MULT);
        
        for (int i = 0; i < count; i++) {
            // 75%概率使用边缘色，25%概率使用黄白色
            Color color = Math.random() <= HIT_BURST_FRINGE_COLOR_CHANCE 
                ? beam.getFringeColor() 
                : new Color(255, 255, 200, 255);
            
            float distance = MathUtils.getRandomNumberInRange(
                HIT_PARTICLE_DISTANCE_MIN + 1f,
                HIT_PARTICLE_DISTANCE_MAX + 1f
            ) * weapon.getChargeLevel();
            
            float speed = 0.75f * distance / HIT_PARTICLE_DURATION * weapon.getChargeLevel();
            float angle = MathUtils.getRandomNumberInRange(facing - halfSpread, facing + halfSpread);
            float vel = MathUtils.getRandomNumberInRange(speed * -HIT_PARTICLE_VEL_MIN, speed * -HIT_PARTICLE_VEL_MAX);
            Vector2f velocity = MathUtils.getPointOnCircumference(null, vel, angle);

            float size = MathUtils.getRandomNumberInRange(
                HIT_PARTICLE_SIZE_MIN + 1f,
                HIT_PARTICLE_SIZE_MAX + 5f
            ) * weapon.getChargeLevel();
            
            float brightness = HIT_PARTICLE_BRIGHTNESS 
                * Math.min(weapon.getChargeLevel() + 0.5f, 1f) 
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
                    (color.getAlpha() / 255f) * weapon.getChargeLevel()
                )
            );
        }
    }
    
    /**
     * 生成命中点闪光效果
     */
    private void generateHitFlash(CombatEngineAPI engine, BeamAPI beam, WeaponAPI weapon) {
        // 只在蓄力或开火时生成
        if (!weapon.isFiring() && weapon.getChargeLevel() <= 0f) {
            return;
        }
        
        // 5%概率生成
        if (Math.random() > HIT_FLASH_CHANCE) {
            return;
        }
        
        float radius = 30f + (weapon.getChargeLevel() * weapon.getChargeLevel() 
            * MathUtils.getRandomNumberInRange(25f, 75f));
        
        float brightness = HIT_FLASH_BRIGHTNESS_BASE 
            + weapon.getChargeLevel() * HIT_FLASH_BRIGHTNESS_MULT;
        
        engine.addSmoothParticle(
            beam.getTo(),
            ZERO,
            radius * HIT_FLASH_SIZE_MULT,
            brightness,
            HIT_FLASH_DURATION,
            beam.getCoreColor()
        );
    }
    
    /**
     * 生成光束路径粒子效果
     */
    private void generatePathParticles(CombatEngineAPI engine, BeamAPI beam, WeaponAPI weapon, 
                                      ShipAPI ship, float amount) {
        float beamWidth = beam.getWidth();
        
        // 计算本帧应生成的粒子数量
        float beamLength = MathUtils.getDistance(beam.getTo(), beam.getFrom());
        float particleCount = beamWidth * PATH_PARTICLE_SPAWN_WIDTH_MULT 
            * beamLength * amount * PATH_PARTICLE_DENSITY * weapon.getChargeLevel();

        // 50%概率生成粒子（降低密度）
        if (Math.random() >= 0.5f) {
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

            // 生成粒子 - 使用光束边缘色和核心色的混合
            Color particleColor = blendColors(
                beam.getFringeColor(),
                beam.getCoreColor(),
                0.7f
            );
            
            engine.addSmoothParticle(
                spawnPoint,
                velocity,
                MathUtils.getRandomNumberInRange(PATH_PARTICLE_SIZE_MIN, PATH_PARTICLE_SIZE_MAX),
                weapon.getChargeLevel(),
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
