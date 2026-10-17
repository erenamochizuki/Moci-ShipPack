package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import org.lwjgl.util.vector.Vector2f;
import org.lazywizard.lazylib.MathUtils;
import java.awt.Color;
import com.fs.starfarer.api.util.IntervalUtil;
import org.magiclib.util.MagicRender;

/**
 * 蓄力光束武器 - 蓄力阶段特效（标准版）
 * 负责武器蓄力时的视觉效果：蓄力光环、吸入粒子、颜色渐变、音效触发
 * 粒子扩散角度：150度扇形
 * 配色：从武器配置读取，带颜色渐变效果
 * 
 * 职责：
 * - 蓄力阶段的吸入粒子效果（150度扇形）
 * - 蓄力时的光环效果
 * - 颜色渐变（从暗色到glow颜色）
 * - 蓄力音效播放
 */
public class Moci_ChargeBeam_EveryFrame implements EveryFrameWeaponEffectPlugin {
    
    private static final Vector2f ZERO = new Vector2f();
    
    // 粒子参数
    private static final float CHARGEUP_PARTICLE_ANGLE_SPREAD = 150f;
    private static final float CHARGEUP_PARTICLE_BRIGHTNESS = 1f;
    private static final float CHARGEUP_PARTICLE_DISTANCE_MAX = 200f;
    private static final float CHARGEUP_PARTICLE_DISTANCE_MIN = 100f;
    private static final float CHARGEUP_PARTICLE_DURATION = 0.5f;
    private static final float CHARGEUP_PARTICLE_SIZE_MAX = 5f;
    private static final float CHARGEUP_PARTICLE_SIZE_MIN = 1f;
    
    // 状态标记
    private boolean charging = false;
    private boolean cooling = false;
    private boolean firing = false;
    
    // 时间间隔
    private final IntervalUtil interval = new IntervalUtil(0.015f, 0.015f);
    
    private float level = 0f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused() || weapon.getShip().getOriginalOwner() == -1) {
            return;
        }
        
        // 屏幕剔除优化
        if (!MagicRender.screenCheck(0.2f, weapon.getLocation())) {
            return;
        }
        
        // 获取武器开火点（枪口位置）
        Vector2f firePoint = weapon.getFirePoint(0);
        float weaponAngle = weapon.getCurrAngle();
        Vector2f shipVelocity = weapon.getShip().getVelocity();
        float chargeLevel = weapon.getChargeLevel();
        
        // 从武器配置获取颜色
        Color glowColor = weapon.getSpec().getGlowColor();
        if (glowColor == null) {
            glowColor = new Color(150, 150, 150, 100);
        }
        
        // 颜色渐变：从暗色到武器glow颜色
        Color darkColor = new Color(
            (int)(glowColor.getRed() * 0.3f),
            (int)(glowColor.getGreen() * 0.3f),
            (int)(glowColor.getBlue() * 0.3f),
            255
        );
        
        float r = (darkColor.getRed() / 255f) + chargeLevel * (glowColor.getRed() / 255f) - (darkColor.getRed() / 255f) * chargeLevel;
        float g = (darkColor.getGreen() / 255f) + chargeLevel * (glowColor.getGreen() / 255f) - (darkColor.getGreen() / 255f) * chargeLevel;
        float b = (darkColor.getBlue() / 255f) + chargeLevel * (glowColor.getBlue() / 255f) - (darkColor.getBlue() / 255f) * chargeLevel;
        
        Color particleColor = new Color(r, g, b, chargeLevel);
        
        // 蓄力光环效果
        if (charging || (!charging && chargeLevel < 1 && chargeLevel > 0)) {
            float radius = 25f + (chargeLevel * chargeLevel * MathUtils.getRandomNumberInRange(25f, 50f));
            engine.addSmoothParticle(
                firePoint,
                ZERO,
                radius,
                chargeLevel * 0.3f,
                0.2f,
                particleColor
            );
        }
        
        if (charging) {
            if (firing && (chargeLevel < 1f)) {
                // 蓄力被打断
                charging = false;
                cooling = true;
                firing = false;
            } else if (chargeLevel < 1f) {
                // 蓄力中 - 生成简单的聚集粒子
                if (Math.random() < 0.3f) {
                    float distance = MathUtils.getRandomNumberInRange(100f, 200f) * chargeLevel;
                    float size = MathUtils.getRandomNumberInRange(2f, 5f) * chargeLevel;
                    float angle = MathUtils.getRandomNumberInRange(-75f, 75f);
                    float speed = 0.5f * distance / 0.5f * chargeLevel;
                    Vector2f particleVelocity = MathUtils.getPointOnCircumference(
                        shipVelocity,
                        speed,
                        angle + weaponAngle + 180f
                    );
                    engine.addSmoothParticle(
                        firePoint,
                        particleVelocity,
                        size,
                        0.8f * chargeLevel,
                        0.5f,
                        particleColor
                    );
                }
            } else {
                // 满蓄力状态
                firing = true;
                interval.advance(amount);
                int count = 1 + (int) (chargeLevel * 2);
                
                if (interval.intervalElapsed()) {
                    float halfSpread = CHARGEUP_PARTICLE_ANGLE_SPREAD / 2f;
                    
                    for (int i = 0; i < count; i++) {
                        float distance = MathUtils.getRandomNumberInRange(
                            CHARGEUP_PARTICLE_DISTANCE_MIN + 1f,
                            CHARGEUP_PARTICLE_DISTANCE_MAX + 1f
                        ) * chargeLevel;
                        
                        float speed = 0.5f * distance / CHARGEUP_PARTICLE_DURATION * chargeLevel;
                        float angle = MathUtils.getRandomNumberInRange(weaponAngle - halfSpread, weaponAngle + halfSpread);
                        float vel = MathUtils.getRandomNumberInRange(speed * -0.5f, speed * -1f);
                        Vector2f vector = MathUtils.getPointOnCircumference(
                            shipVelocity,
                            vel,
                            angle
                        );

                        float size = MathUtils.getRandomNumberInRange(
                            CHARGEUP_PARTICLE_SIZE_MIN + 1f,
                            CHARGEUP_PARTICLE_SIZE_MAX + 3f
                        ) * chargeLevel;
                        
                        engine.addSmoothParticle(
                            firePoint,
                            vector,
                            size,
                            CHARGEUP_PARTICLE_BRIGHTNESS * Math.min(chargeLevel + 0.5f, 1f) * MathUtils.getRandomNumberInRange(0.75f, 1.25f),
                            CHARGEUP_PARTICLE_DURATION,
                            particleColor
                        );
                    }
                }
            }
        } else {
            if (cooling) {
                if (chargeLevel <= 0f) {
                    cooling = false;
                }
            } else if (chargeLevel > level) {
                // 开始蓄力 - 播放音效（只播放一次）
                Global.getSoundPlayer().playSound(
                    "Moci_BeamCharge",
                    0.95f,
                    1.05f,
                    firePoint,
                    weapon.getShip().getVelocity()
                );
                charging = true;
            }
        }
        
        level = chargeLevel;
    }
}
