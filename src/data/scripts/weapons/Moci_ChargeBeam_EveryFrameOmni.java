package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import org.lwjgl.util.vector.Vector2f;
import org.lazywizard.lazylib.MathUtils;
import java.awt.Color;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 蓄力光束武器 - 蓄力阶段特效（360度全向版）
 * 负责武器蓄力时的视觉效果：全向蓄力光环、360度吸入粒子、光球效果
 * 粒子扩散角度：360度全向
 * 配色：从武器配置读取
 * 
 * 职责：
 * - 蓄力阶段的吸入粒子效果（360度全向，从外向内）
 * - 蓄力时的光球效果
 * - 蓄力音效播放
 */
public class Moci_ChargeBeam_EveryFrameOmni implements EveryFrameWeaponEffectPlugin {
    
    private static final Vector2f ZERO = new Vector2f();
    
    // 粒子参数
    private static final float CHARGEUP_PARTICLE_ANGLE_SPREAD = 360f; // 360度全向
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
        
        // 获取武器开火点（枪口位置）
        Vector2f firePoint = weapon.getFirePoint(0);
        float weaponAngle = weapon.getCurrAngle();
        Vector2f shipVelocity = weapon.getShip().getVelocity();
        
        // 从武器配置获取颜色
        Color glowColor = weapon.getSpec().getGlowColor();
        if (glowColor == null) {
            glowColor = new Color(200, 0, 200, 255);
        }
        
        // 使用glow颜色
        Color outerColor = glowColor;
        
        // 蓄力中且未满蓄力时的光球效果
        if (weapon.isFiring() && weapon.getChargeLevel() > 0f && weapon.getChargeLevel() < 1f) {
            float radius = 30f + (weapon.getChargeLevel() * weapon.getChargeLevel() * MathUtils.getRandomNumberInRange(25f, 75f));
            
            // 单层光球效果
            engine.addSmoothParticle(
                firePoint,
                ZERO,
                radius,
                0.1f + weapon.getChargeLevel() * 0.3f,
                0.2f,
                outerColor
            );
        }
        
        if (charging) {
            float radius = 30f + (weapon.getChargeLevel() * weapon.getChargeLevel() * MathUtils.getRandomNumberInRange(25f, 75f));
            
            if (firing && (weapon.getChargeLevel() < 1f)) {
                // 蓄力被打断
                charging = false;
                cooling = true;
                firing = false;
            } else if (weapon.getChargeLevel() < 1f) {
                // 蓄力中
                interval.advance(amount);
                if (interval.intervalElapsed()) {
                    // 单层光球效果
                    engine.addSmoothParticle(
                        firePoint,
                        ZERO,
                        radius * weapon.getChargeLevel(),
                        0.1f + weapon.getChargeLevel() * 0.3f,
                        0.2f,
                        outerColor
                    );

                    // 360度全向吸入粒子
                    int count = 2 + (int) (weapon.getChargeLevel() * 25);
                    for (int i = 0; i < count; i++) {
                        float distance = MathUtils.getRandomNumberInRange(
                            CHARGEUP_PARTICLE_DISTANCE_MIN,
                            CHARGEUP_PARTICLE_DISTANCE_MAX
                        ) * weapon.getChargeLevel();
                        
                        // 距离太小时跳过（避免零向量）
                        if (distance < 1f) {
                            continue;
                        }
                        
                        float size = MathUtils.getRandomNumberInRange(
                            CHARGEUP_PARTICLE_SIZE_MIN,
                            CHARGEUP_PARTICLE_SIZE_MAX
                        ) * weapon.getChargeLevel();
                        
                        // 360度随机角度
                        float angle = MathUtils.getRandomNumberInRange(0f, 360f);
                        
                        // 计算粒子起始位置（在枪口周围一圈）
                        Vector2f particleStart = MathUtils.getPointOnCircumference(firePoint, distance, angle);
                        
                        // 计算从起始位置指向枪口的速度（吸入效果）
                        float speed = distance / CHARGEUP_PARTICLE_DURATION;
                        Vector2f toCenter = Vector2f.sub(firePoint, particleStart, new Vector2f());
                        
                        // 检查向量长度，避免零向量归一化
                        float length = toCenter.length();
                        if (length < 0.1f) {
                            continue;
                        }
                        
                        toCenter.normalise();
                        toCenter.scale(speed);
                        
                        // 叠加舰船速度
                        Vector2f particleVelocity = Vector2f.add(toCenter, shipVelocity, new Vector2f());
                        
                        engine.addSmoothParticle(
                            particleStart,
                            particleVelocity,
                            size,
                            CHARGEUP_PARTICLE_BRIGHTNESS * Math.min(weapon.getChargeLevel() + 0.5f, 1f) * MathUtils.getRandomNumberInRange(0.75f, 1.25f),
                            CHARGEUP_PARTICLE_DURATION,
                            outerColor
                        );
                    }
                }
            } else {
                // 满蓄力状态
                firing = true;
                
                // 单层光球效果
                engine.addSmoothParticle(
                    firePoint,
                    ZERO,
                    radius,
                    0.1f + weapon.getChargeLevel() * 0.3f,
                    0.2f,
                    outerColor
                );
            }
        } else {
            if (cooling) {
                if (weapon.getChargeLevel() <= 0f) {
                    cooling = false;
                }
            } else if (weapon.getChargeLevel() > level) {
                // 开始蓄力 - 播放音效（只播放一次）
                Global.getSoundPlayer().playSound(
                    "Moci_BeamCharge",
                    1f,
                    1f,
                    firePoint,
                    weapon.getShip().getVelocity()
                );
                charging = true;
            }
        }
        
        level = weapon.getChargeLevel();
    }
}
