package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_MathPersonal;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;

/**
 * 爆炸性命中效果
 */
public class Moci_ExplosiveOnHitEffect implements OnHitEffectPlugin {
    
    // 颜色配置
    private static final Color PARTICLE_COLOR = new Color(255, 47, 21, 150);
    private static final Color BLAST_COLOR = new Color(255, 16, 16, 0);
    private static final Color CORE_COLOR = new Color(255, 67, 34);
    private static final Color FLASH_COLOR = new Color(255, 234, 212);
    
    // 伤害配置
    private static final float DAMAGE_BONUS = 0.25f;    // AOE伤害倍率（相对弹丸伤害）
    private static final float EMP_RATIO = 0.5f;        // EMP伤害比例（相对AOE伤害）
    
    // 爆炸配置
    private static final float EXPLOSION_OUTER_RADIUS = 300f;   // 外层爆炸半径
    private static final float EXPLOSION_INNER_RADIUS = 150f;   // 内层爆炸半径
    private static final float EXPLOSION_DURATION = 1f;         // 爆炸持续时间
    
    // AOE爆炸配置
    private static final float AOE_RADIUS = 300f;               // AOE伤害半径
    private static final float AOE_CORE_RADIUS = 150f;          // AOE核心半径
    private static final float AOE_DURATION = 0.1f;             // AOE持续时间
    
    // 闪光配置
    private static final float FLASH_LARGE_SIZE = 600f;         // 大闪光尺寸
    private static final float FLASH_LARGE_DURATION = 0.05f;    // 大闪光持续时间
    private static final float FLASH_MEDIUM_SIZE = 200f;        // 中等闪光尺寸
    private static final float FLASH_MEDIUM_DURATION = 0.25f;   // 中等闪光持续时间
    private static final float GLOW_SIZE = 400f;                // 光晕尺寸
    private static final float GLOW_DURATION = 0.1f;            // 光晕持续时间
    
    // 粒子配置
    private static final int NUM_PARTICLES = 50;                // 飞溅粒子数量
    private static final float PARTICLE_SPEED_MIN = 100f;       // 粒子最小速度
    private static final float PARTICLE_SPEED_MAX = 500f;       // 粒子最大速度
    private static final float PARTICLE_SIZE = 10f;             // 粒子尺寸
    private static final float PARTICLE_DURATION_MIN = 0.3f;    // 粒子最小持续时间
    private static final float PARTICLE_DURATION_MAX = 0.6f;    // 粒子最大持续时间
    
    @Override
    public void onHit(DamagingProjectileAPI projectile, 
                      CombatEntityAPI target, 
                      Vector2f point, 
                      boolean shieldHit, 
                      ApplyDamageResultAPI damageResult, 
                      CombatEngineAPI engine) {
        
        // 1. 双层爆炸效果
        engine.spawnExplosion(point, Misc.ZERO, PARTICLE_COLOR, EXPLOSION_OUTER_RADIUS, EXPLOSION_DURATION);
        engine.spawnExplosion(point, Misc.ZERO, CORE_COLOR, EXPLOSION_INNER_RADIUS, EXPLOSION_DURATION);
        
        // 2. 多层闪光粒子
        // 大闪光 - 极短持续时间
        engine.addHitParticle(point, Misc.ZERO, FLASH_LARGE_SIZE, 1f, FLASH_LARGE_DURATION, FLASH_COLOR);
        // 平滑光晕
        engine.addSmoothParticle(point, Misc.ZERO, GLOW_SIZE, 0.5f, GLOW_DURATION, PARTICLE_COLOR);
        // 中等闪光
        engine.addHitParticle(point, Misc.ZERO, FLASH_MEDIUM_SIZE, 0.5f, FLASH_MEDIUM_DURATION, FLASH_COLOR);
        
        // 3. 飞溅粒子 - 高速粒子向四周飞溅
        for (int i = 0; i < NUM_PARTICLES; i++) {
            // 随机速度和角度
            float speed = Moci_MathPersonal.randBetween(PARTICLE_SPEED_MIN, PARTICLE_SPEED_MAX);
            float angle = Moci_MathPersonal.getRandomAngle();
            
            // 计算速度向量（从原点出发，然后转换为速度）
            Vector2f velocity = Moci_MathPersonal.getVector(Moci_MathPersonal.ZERO, angle, speed);
            
            engine.addHitParticle(
                point,
                velocity,
                PARTICLE_SIZE,
                1f,   // 亮度
                Moci_MathPersonal.randBetween(PARTICLE_DURATION_MIN, PARTICLE_DURATION_MAX),
                PARTICLE_COLOR
            );
        }
        
        // 4. AOE伤害爆炸
        float bonusDamage = projectile.getDamageAmount() * DAMAGE_BONUS;
        
        DamagingExplosionSpec blast = new DamagingExplosionSpec(
            AOE_DURATION,                   // 持续时间
            AOE_RADIUS,                     // 半径
            AOE_CORE_RADIUS,                // 核心半径
            bonusDamage,                    // 伤害
            bonusDamage * EMP_RATIO,        // EMP伤害
            CollisionClass.PROJECTILE_FF,   // 碰撞类型
            CollisionClass.PROJECTILE_FIGHTER,
            10f,                            // 粒子尺寸最小值
            10f,                            // 粒子尺寸最大值
            0f,                             // 粒子持续时间
            0,                              // 粒子数量
            BLAST_COLOR,                    // 颜色
            null                            // 核心颜色
        );
        
        blast.setDamageType(DamageType.HIGH_EXPLOSIVE);
        blast.setShowGraphic(false);  // 不显示默认图形（已有自定义效果）
        
        engine.spawnDamagingExplosion(blast, projectile.getSource(), point, false);
        
        // 5. 音效
        Global.getSoundPlayer().playSound("mine_explosion", 1f, 1f, point, Misc.ZERO);
    }
}
