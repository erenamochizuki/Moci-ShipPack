package data.scripts.weapons;

import java.awt.Color;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.Misc;

public class Moci_HeatSinkEffect implements EveryFrameWeaponEffectPlugin {

    // ===== 配置参数 ===== //
    private static final int MAX_LEVEL = 24;           // 最大等级
    private static final int LEVEL_PER_SHOT = 3;       // 每次开火增加的等级
    private static final float LEVEL_DECAY_PER_SEC = 1f; // 每秒降低的等级
    private static final int MAX_ANIMATION_FRAME = 7;   // 最大动画帧数（0-7）
    private static final String MAIN_SLOT_PREFIX = "MAIN_"; // 主武器槽位前缀
    private static final String LIGHT_SLOT_PREFIX = "LIGHT_"; // 灯光槽位前缀
    
    // 粒子效果参数
    private static final float PARTICLE_BASE_SIZE = 8f;     // 基础粒子大小
    private static final float PARTICLE_SIZE_MULT = 2f;     // 粒子大小倍数
    private static final float PARTICLE_SPEED = 60f;        // 粒子速度
    private static final float PARTICLE_DURATION = 1.2f;    // 粒子持续时间
    private static final Color PARTICLE_COLOR = new Color(255, 200, 100, 180); // 粒子颜色（橙黄色）
    
    // ===== 优化后的内部状态 ===== //
    private float currentLevel = 0f;
    private boolean initialized = false;
    
    // 武器引用 - 初始化后存储，避免每帧查找
    private WeaponAPI nearestMainWeapon = null;
    private WeaponAPI lightWeapon = null;
    
    // 开火检测优化 - 存储上一帧状态
    private int lastAmmo = -1;              // 上一帧弹药数（用于有弹药武器）
    private float lastChargeLevel = -1f;    // 上一帧充能等级（用于能量武器）
    private boolean lastFiringState = false; // 上一帧开火状态（备用方案）

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基本检查
        if (engine == null || engine.isPaused() || weapon == null) return;
        
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            resetState();
            return;
        }

        // 一次性初始化
        if (!initialized) {
            initializeWeaponReferences(ship, weapon);
            initialized = true;
        }

        // 高效开火检测
        detectFiring();
        
        // 更新等级
        updateLevel(amount);
        
        // 更新视觉效果
        updateAnimationFrame(weapon);
        updateLightWeaponAlpha();
        
        // 生成粒子效果
        generateParticles(engine, weapon, amount);
    }

    /**
     * 一次性初始化武器引用 - 避免每帧查找
     */
    private void initializeWeaponReferences(ShipAPI ship, WeaponAPI currentWeapon) {
        // 找到距离当前武器最近的MAIN武器
        float minDistance = Float.MAX_VALUE;
        Vector2f currentLoc = currentWeapon.getLocation();
        
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot().getId().startsWith(MAIN_SLOT_PREFIX)) {
                float distance = Misc.getDistance(currentLoc, weapon.getLocation());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearestMainWeapon = weapon;
                }
            }
        }
        
        // 找到位置重合的LIGHT武器
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot().getId().startsWith(LIGHT_SLOT_PREFIX)) {
                float distance = Misc.getDistance(currentLoc, weapon.getLocation());
                if (distance < 0f) {
                    lightWeapon = weapon;
                    break;
                }
            }
        }
        
        // 初始化检测状态
        if (nearestMainWeapon != null) {
            lastAmmo = nearestMainWeapon.getAmmo();
            lastChargeLevel = nearestMainWeapon.getChargeLevel();
            lastFiringState = nearestMainWeapon.isFiring();
        }
    }

    /**
     * 优化的开火检测 - 使用弹药/充能变化判断
     */
    private void detectFiring() {
        if (nearestMainWeapon == null) return;
        
        boolean fired = false;
        
        // 方案1：弹药武器 - 检测弹药减少
        if (nearestMainWeapon.getMaxAmmo() > 0) {
            int currentAmmo = nearestMainWeapon.getAmmo();
            if (lastAmmo > currentAmmo) {
                fired = true;
            }
            lastAmmo = currentAmmo;
        }
        // 方案2：能量武器 - 检测充能变化或开火状态
        else {
            float currentCharge = nearestMainWeapon.getChargeLevel();
            boolean currentFiring = nearestMainWeapon.isFiring();
            
            // 检测从充能到放电的变化
            if (lastChargeLevel > 0.8f && currentCharge < 0.2f) {
                fired = true;
            }
            // 备用方案：检测开火状态变化
            else if (currentFiring && !lastFiringState) {
                fired = true;
            }
            
            lastChargeLevel = currentCharge;
            lastFiringState = currentFiring;
        }
        
        // 应用等级变化
        if (fired) {
            currentLevel = Math.min(MAX_LEVEL, currentLevel + LEVEL_PER_SHOT);
        }
    }

    /**
     * 更新等级系统 - 仅处理衰减
     */
    private void updateLevel(float amount) {
        // 每秒降级
        if (currentLevel > 0) {
            currentLevel = Math.max(0, currentLevel - LEVEL_DECAY_PER_SEC * amount);
        }
    }

    /**
     * 更新动画帧
     */
    private void updateAnimationFrame(WeaponAPI weapon) {
        if (weapon.getAnimation() == null) return;
        
        // 计算动画帧：每3级对应1帧，0-24级对应0-7帧
        int frameIndex = Math.min(MAX_ANIMATION_FRAME, (int)(currentLevel / 3));
        weapon.getAnimation().setFrame(frameIndex);
    }

    /**
     * 更新灯光武器透明度 - 使用缓存的武器引用
     */
    private void updateLightWeaponAlpha() {
        if (lightWeapon == null) return;
        
        // 线性计算透明度：0级=0，24级=255
        float alphaRatio = currentLevel / MAX_LEVEL;
        int alpha = (int)(alphaRatio * 255);
        alpha = Math.max(0, Math.min(255, alpha));
        
        // 应用透明度，保持原有颜色
        Color currentColor = lightWeapon.getSprite().getColor();
        if (currentColor != null) {
            Color newColor = new Color(
                currentColor.getRed(),
                currentColor.getGreen(),
                currentColor.getBlue(),
                alpha
            );
            lightWeapon.getSprite().setColor(newColor);
        } else {
            // 如果没有颜色，使用白色
            lightWeapon.getSprite().setColor(new Color(255, 255, 255, alpha));
        }
    }

    /**
     * 生成粒子效果
     */
    private void generateParticles(CombatEngineAPI engine, WeaponAPI weapon, float amount) {
        if (currentLevel <= 0) return;
        
        // 根据等级计算粒子数量（0级=0个，24级=最多）
        float particleRate = (currentLevel / MAX_LEVEL) * 20f; // 最多每秒20个粒子
        float particlesThisFrame = particleRate * amount;
        
        // 使用概率来决定是否生成粒子
        if (Math.random() < particlesThisFrame) {
            generateSingleParticle(engine, weapon);
        }
    }

    /**
     * 生成单个粒子
     */
    private void generateSingleParticle(CombatEngineAPI engine, WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        if (ship == null) return;
        
        // 获取武器位置
        Vector2f weaponLoc = weapon.getLocation();
        
        // 在武器周围随机生成粒子位置
        float randomAngle = (float)(Math.random() * 360);
        float randomDistance = (float)(Math.random() * 15f); // 15单位半径内
        Vector2f particleLoc = MathUtils.getPointOnCircumference(weaponLoc, randomDistance, randomAngle);
        
        // 计算粒子速度（向外扩散）
        Vector2f particleVel = VectorUtils.getDirectionalVector(weaponLoc, particleLoc);
        particleVel.scale(PARTICLE_SPEED + (float)(Math.random() * 20f)); // 添加随机速度
        
        // 添加舰船速度
        Vector2f.add(particleVel, ship.getVelocity(), particleVel);
        
        // 根据等级调整粒子大小
        float sizeMultiplier = 1f + (currentLevel / MAX_LEVEL) * 0.5f; // 最大1.5倍大小
        float particleSize = PARTICLE_BASE_SIZE * sizeMultiplier;
        
        // 生成粒子
        engine.addNebulaSmokeParticle(
            particleLoc,
            particleVel,
            particleSize,
            PARTICLE_SIZE_MULT,
            0.1f,
            0.3f,
            PARTICLE_DURATION,
            PARTICLE_COLOR
        );
    }

    /**
     * 重置状态
     */
    private void resetState() {
        currentLevel = 0f;
        nearestMainWeapon = null;
        lightWeapon = null;
        lastAmmo = -1;
        lastChargeLevel = -1f;
        lastFiringState = false;
        initialized = false;
    }
} 