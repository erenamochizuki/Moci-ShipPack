package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;

/**
 * 枪口特效工具类
 * 
 * 提供静态方法用于生成各种枪口特效
 * 可以被其他武器脚本调用
 */
public class Moci_MuzzleFlashEffect {
    
    /**
     * 特效配置类
     * 用于定义一个粒子层的所有属性
     */
    public static class EffectConfig {
        public String particleType = "SMOKE";           // 粒子类型：SMOKE, SMOOTH, BRIGHT, NEBULA, NEGATIVE_NEBULA
        public Color particleColor = new Color(140, 130, 120, 125);
        public float particleSizeMin = 5f;
        public float particleSizeMax = 20f;
        public float particleVelocityMin = 0f;
        public float particleVelocityMax = 40f;
        public float particleDurationMin = 1.5f;
        public float particleDurationMax = 3f;
        public float particleOffsetMin = 0f;
        public float particleOffsetMax = 50f;
        public float particleArc = 10f;                 // 扩散角度
        public float particleArcFacing = 0f;            // 扩散方向偏移
        public int particleCount = 15;                  // 粒子数量
        
        // NEBULA 和 NEGATIVE_NEBULA 类型专用参数
        public float nebulaSizeMult = 1f;               // 星云烟雾大小倍率
        public float nebulaFadeIn = 0.1f;               // 星云烟雾淡入时间
        public float nebulaFadeOut = 0.1f;              // 星云烟雾淡出时间
        
        public EffectConfig() {}
        
        // 链式调用方法，方便配置
        public EffectConfig type(String type) { this.particleType = type; return this; }
        public EffectConfig color(Color color) { this.particleColor = color; return this; }
        public EffectConfig size(float min, float max) { 
            this.particleSizeMin = min; 
            this.particleSizeMax = max; 
            return this; 
        }
        public EffectConfig velocity(float min, float max) { 
            this.particleVelocityMin = min; 
            this.particleVelocityMax = max; 
            return this; 
        }
        public EffectConfig duration(float min, float max) { 
            this.particleDurationMin = min; 
            this.particleDurationMax = max; 
            return this; 
        }
        public EffectConfig offset(float min, float max) { 
            this.particleOffsetMin = min; 
            this.particleOffsetMax = max; 
            return this; 
        }
        public EffectConfig arc(float arc) { this.particleArc = arc; return this; }
        public EffectConfig arcFacing(float facing) { this.particleArcFacing = facing; return this; }
        public EffectConfig count(int count) { this.particleCount = count; return this; }
        
        // NEBULA 类型专用配置
        public EffectConfig nebulaSizeMult(float mult) { this.nebulaSizeMult = mult; return this; }
        public EffectConfig nebulaFade(float fadeIn, float fadeOut) { 
            this.nebulaFadeIn = fadeIn; 
            this.nebulaFadeOut = fadeOut; 
            return this; 
        }
    }
    
    /**
     * 生成简单的枪口闪光效果
     * 
     * @param engine 战斗引擎
     * @param weapon 武器
     * @param config 特效配置
     */
    public static void spawnMuzzleFlash(CombatEngineAPI engine, WeaponAPI weapon, EffectConfig config) {
        if (engine == null || weapon == null || config == null) {
            return;
        }
        
        // 屏幕剔除检查
        if (!engine.getViewport().isNearViewport(weapon.getLocation(), 600f)) {
            return;
        }
        
        // 获取武器位置和朝向
        Vector2f location = weapon.getLocation();
        float facing = weapon.getCurrAngle();
        Vector2f shipVelocity = weapon.getShip().getVelocity();
        
        // 生成粒子
        spawnParticlesSimple(engine, location, facing, shipVelocity, config);
    }
    
    /**
     * 生成带炮管偏移的枪口闪光效果（适合多管武器）
     * 
     * @param engine 战斗引擎
     * @param weapon 武器
     * @param config 特效配置
     * @param barrelIndex 炮管索引（从0开始）
     */
    public static void spawnMuzzleFlashWithBarrel(CombatEngineAPI engine, WeaponAPI weapon, 
                                                   EffectConfig config, int barrelIndex) {
        if (engine == null || weapon == null || config == null) {
            return;
        }
        
        // 屏幕剔除检查
        if (!engine.getViewport().isNearViewport(weapon.getLocation(), 600f)) {
            return;
        }
        
        // 获取炮管位置
        Vector2f barrelLocation = getBarrelLocation(weapon, barrelIndex);
        float facing = weapon.getCurrAngle();
        Vector2f shipVelocity = weapon.getShip().getVelocity();
        
        // 生成粒子
        spawnParticlesSimple(engine, barrelLocation, facing, shipVelocity, config);
    }
    
    /**
     * 获取指定炮管的世界坐标位置
     */
    private static Vector2f getBarrelLocation(WeaponAPI weapon, int barrelIndex) {
        // 注意：spawn location 实际上旋转了90度，所以需要互换 x 和 y
        Vector2f trueCenterLocation = new Vector2f(0f, 0f);
        
        // 获取炮管偏移并添加到位置
        if (weapon.getSlot().isHardpoint()) {
            if (barrelIndex < weapon.getSpec().getHardpointFireOffsets().size()) {
                Vector2f barrelOffset = weapon.getSpec().getHardpointFireOffsets().get(barrelIndex);
                trueCenterLocation.x += barrelOffset.x;
                trueCenterLocation.y += barrelOffset.y;
            }
        } else if (weapon.getSlot().isTurret()) {
            if (barrelIndex < weapon.getSpec().getTurretFireOffsets().size()) {
                Vector2f barrelOffset = weapon.getSpec().getTurretFireOffsets().get(barrelIndex);
                trueCenterLocation.x += barrelOffset.x;
                trueCenterLocation.y += barrelOffset.y;
            }
        } else {
            if (barrelIndex < weapon.getSpec().getHiddenFireOffsets().size()) {
                Vector2f barrelOffset = weapon.getSpec().getHiddenFireOffsets().get(barrelIndex);
                trueCenterLocation.x += barrelOffset.x;
                trueCenterLocation.y += barrelOffset.y;
            }
        }
        
        // 旋转到武器朝向
        trueCenterLocation = VectorUtils.rotate(trueCenterLocation, weapon.getCurrAngle(), new Vector2f(0f, 0f));
        
        // 转换到世界坐标
        trueCenterLocation.x += weapon.getLocation().x;
        trueCenterLocation.y += weapon.getLocation().y;
        
        return trueCenterLocation;
    }
    
    /**
     * 简化的粒子生成函数
     */
    private static void spawnParticlesSimple(CombatEngineAPI engine, Vector2f location, 
                                            float facing, Vector2f shipVelocity, EffectConfig config) {
        
        float trueArcFacing = facing + config.particleArcFacing;
        
        // 生成粒子（使用概率处理小数部分）
        float counter = config.particleCount;
        while (Math.random() < counter) {
            counter--;
            
            // 计算粒子速度方向
            float arcPoint = MathUtils.getRandomNumberInRange(
                trueArcFacing - (config.particleArc / 2f), 
                trueArcFacing + (config.particleArc / 2f)
            );
            
            // 计算粒子速度
            Vector2f velocity = MathUtils.getPointOnCircumference(
                shipVelocity,
                MathUtils.getRandomNumberInRange(config.particleVelocityMin, config.particleVelocityMax),
                arcPoint
            );
            
            // 计算粒子生成位置
            Vector2f spawnLocation = MathUtils.getPointOnCircumference(
                location,
                MathUtils.getRandomNumberInRange(config.particleOffsetMin, config.particleOffsetMax),
                arcPoint
            );
            
            // 随机化其他属性
            float duration = MathUtils.getRandomNumberInRange(config.particleDurationMin, config.particleDurationMax);
            float size = MathUtils.getRandomNumberInRange(config.particleSizeMin, config.particleSizeMax);
            
            // 根据类型生成粒子
            switch (config.particleType) {
                case "SMOOTH":
                    engine.addSmoothParticle(spawnLocation, velocity, size, 1f, duration, config.particleColor);
                    break;
                case "SMOKE":
                    engine.addSmokeParticle(spawnLocation, velocity, size, 1f, duration, config.particleColor);
                    break;
                case "BRIGHT":
                    engine.addSmoothParticle(spawnLocation, velocity, size, 10f, duration, config.particleColor);
                    break;
                case "NEBULA":
                    // 星云烟雾效果（更真实的烟雾）
                    // 计算方向速度（沿武器朝向）
                    Vector2f directionalVel = VectorUtils.getDirectionalVector(
                        spawnLocation,
                        MathUtils.getPointOnCircumference(spawnLocation, 10f, trueArcFacing)
                    );
                    directionalVel.scale(MathUtils.getRandomNumberInRange(config.particleVelocityMin, config.particleVelocityMax));
                    Vector2f.add(shipVelocity, directionalVel, directionalVel);
                    
                    engine.addNebulaSmokeParticle(
                        spawnLocation,
                        directionalVel,
                        size,
                        config.nebulaSizeMult,
                        config.nebulaFadeIn,
                        config.nebulaFadeOut,
                        duration,
                        config.particleColor
                    );
                    break;
                case "NEGATIVE_NEBULA":
                    // 负面星云烟雾效果（黑色烟雾，吸光效果）
                    // 计算方向速度（沿武器朝向）
                    Vector2f negDirectionalVel = VectorUtils.getDirectionalVector(
                        spawnLocation,
                        MathUtils.getPointOnCircumference(spawnLocation, 10f, trueArcFacing)
                    );
                    negDirectionalVel.scale(MathUtils.getRandomNumberInRange(config.particleVelocityMin, config.particleVelocityMax));
                    Vector2f.add(shipVelocity, negDirectionalVel, negDirectionalVel);
                    
                    engine.addNegativeNebulaParticle(
                        spawnLocation,
                        negDirectionalVel,
                        size,
                        config.nebulaSizeMult,
                        config.nebulaFadeIn,
                        config.nebulaFadeOut,
                        duration,
                        config.particleColor
                    );
                    break;
            }
        }
    }
    
}
