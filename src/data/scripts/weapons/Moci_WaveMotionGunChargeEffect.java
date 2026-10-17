package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.FlareEntity;
import org.boxutil.units.standard.entity.TrailEntity;

import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 汇聚蓄力特效系统
 * 
 * 特效阶段：
 * 1. 0-0.5: 橙黄色粒子+光线汇聚，亮度逐渐增加
 * 2. 0.5: 添加橙黄色光晕
 * 3. 0.5-0.8: 粒子/光线变粗，光晕变亮变大并变为蓝白色，开始产生蓝白色版本（0.65时一半一半，0.8时全蓝）
 * 4. 0.8-1.0: 光晕急剧变大，粒子/光线急剧减少并收束到枪口（生成范围和角度范围都收缩到0）
 * 5. 开火: 大型flash闪光
 */
public class Moci_WaveMotionGunChargeEffect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    
    // ==================== 阶段阈值 ====================
    private static final float STAGE_1_END = 0.5f;    // 第一阶段结束
    private static final float STAGE_2_END = 0.8f;    // 第二阶段结束（粒子/光线颜色过渡完成）
    private static final float ARC_START = 0.7f;      // 电弧开始阶段
    
    // ==================== 机动性Debuff参数 ====================
    private static final float DEBUFF_START = 0.1f;   // Debuff开始阶段
    private static final float DEBUFF_MAX = 0.5f;     // Debuff达到最大值的阶段
    private static final float DEBUFF_END = 0.8f;     // Debuff完全移除的阶段（开火后降到此值）
    private static final float DEBUFF_SPEED_MULT = 0.2f;      // 速度倍率
    private static final float DEBUFF_MANEUVER_MULT = 0.2f;   // 机动性倍率
    private static final float DEBUFF_DECEL_MULT = 2.0f;      // 减速性能倍率（100%加成）
    private static final float DEBUFF_MASS_MULT = 2.0f;       // 质量倍率（100%加成）
    private static final String DEBUFF_ID = "moci_wave_motion_gun_charge";  // Debuff唯一ID
    private static final String STATUS_KEY = "moci_wave_motion_gun_status";  // 状态显示Key
    
    // ==================== 粒子参数 ====================
    private static final float PARTICLE_ANGLE_SPREAD = 160f;
    private static final float PARTICLE_DISTANCE_MAX = 400f;
    private static final float PARTICLE_DISTANCE_MIN = 100f;
    private static final float PARTICLE_DURATION = 0.4f;
    
    // 蓝白色粒子
    private static final Color PARTICLE_COLOR_BLUE = new Color(150, 200, 255, 255);
    private static final float PARTICLE_SIZE_MIN_BLUE = 4f;
    private static final float PARTICLE_SIZE_MAX_BLUE = 12f;
    
    // 橙黄色粒子
    private static final Color PARTICLE_COLOR_ORANGE = new Color(255, 180, 80, 255);
    private static final float PARTICLE_SIZE_MIN_ORANGE = 6f;
    private static final float PARTICLE_SIZE_MAX_ORANGE = 16f;
    
    // ==================== 光线参数 ====================
    private static final float TRAIL_DISTANCE_MAX = 450f;
    private static final float TRAIL_DISTANCE_MIN = 80f;
    private static final float TRAIL_DURATION = 0.6f;
    
    // 蓝白色光线
    private static final Color TRAIL_COLOR_BLUE_CORE = new Color(200, 220, 255, 255);
    private static final Color TRAIL_COLOR_BLUE_START = new Color(150, 180, 255, 100);
    private static final Color TRAIL_COLOR_BLUE_END = new Color(180, 200, 255, 150);
    private static final float TRAIL_WIDTH_START_BLUE = 1.0f;
    private static final float TRAIL_WIDTH_END_BLUE = 5.0f;
    
    // 橙黄色光线
    private static final Color TRAIL_COLOR_ORANGE_CORE = new Color(255, 200, 100, 255);
    private static final Color TRAIL_COLOR_ORANGE_START = new Color(255, 180, 80, 100);
    private static final Color TRAIL_COLOR_ORANGE_END = new Color(255, 150, 50, 150);
    private static final float TRAIL_WIDTH_START_ORANGE = 2.0f;
    private static final float TRAIL_WIDTH_END_ORANGE = 8.0f;
    
    // ==================== 光晕参数 ====================
    private static final float FLARE_SIZE_MIN = 8f;
    private static final float FLARE_SIZE_MAX = 32;
    private static final Color FLARE_COLOR_BLUE = new Color(150, 200, 255, 200);
    private static final Color FLARE_COLOR_ORANGE = new Color(255, 180, 80, 255);
    private static final Color FLARE_COLOR_FINAL_BLUE = new Color(100, 150, 255, 255); // 0.9-1.0阶段的蓝色
    
    // ==================== 闪光参数 ====================
    private static final float FLASH_DURATION = 0.5f;
    private static final Color FLASH_COLOR = new Color(255, 255, 255, 255);
    private static final Vector2f ZERO = new Vector2f();  // 零向量，用于光球效果
    
    // ==================== 枪口偏移 ====================
    private static final float MUZZLE_OFFSET_HARDPOINT = 15f;
    private static final float MUZZLE_OFFSET_TURRET = 10f;
    
    // ==================== 状态变量 ====================
    private FlareEntity flareEntity = null;
    private final List<TrailEntityWrapper> activeTrails = new ArrayList<>();
    private boolean hasFired = false;  // 武器是否已发射
    private float currentFlareSize = 0f;  // 当前光晕尺寸（用于闪光）
    
    // 音效状态标志
    private boolean hasPlayedChargeSound = false;  // 是否已播放充能音效
    private boolean hasPlayedShotSound = false;    // 是否已播放发射音效
    private boolean hasPlayedEndSound = false;     // 是否已播放结束音效
    
    // 质量管理
    private Float originalMass = null;  // 原始质量（用于恢复）
    
    // ==================== 电弧参数 ====================
    private float arcTimer = 0f;  // 电弧生成计时器
    private static final float ARC_INTERVAL = 0.25f;  // 电弧生成间隔（秒）
    private static final float ARC_ANGLE_SPREAD = 300f;  // 电弧角度范围（度，360度为全向）
    private static final float ARC_THICKNESS_MIN = 10.0f;  // 电弧最小粗细（开火后衰减到此值）
    private static final float ARC_THICKNESS_MAX = 30.0f;  // 电弧最大粗细（蓄力阶段）
    
    // 电弧距离范围
    private static final float ARC_MIN_DISTANCE_MIN = 25f;   // 最小距离的最小值
    private static final float ARC_MIN_DISTANCE_MAX = 50f;   // 最小距离的最大值
    private static final float ARC_MAX_DISTANCE_MIN = 75f;  // 最大距离的最小值
    private static final float ARC_MAX_DISTANCE_MAX = 150f;  // 最大距离的最大值
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused()) {
            return;
        }
        
        // 更新光线
        updateTrailEntities(amount);
        
        // 获取当前状态
        float chargeLevel = weapon.getChargeLevel();
        boolean isFiring = weapon.isFiring();
        
        // ==================== 音效管理 ====================
        // 1. ChargeLevel达到0.1时播放充能音效
        if (chargeLevel >= 0.1f && !hasPlayedChargeSound) {
            hasPlayedChargeSound = true;
            Global.getSoundPlayer().playSound("Moci_WaveCannon_charge_heavy", 1.0f, 1.0f, weapon.getLocation(), weapon.getShip().getVelocity());
        }
        
        // 2. ChargeLevel达到1.0时播放发射音效
        if (chargeLevel >= 1.0f && !hasPlayedShotSound) {
            hasPlayedShotSound = true;
            Global.getSoundPlayer().playSound("Moci_WaveCannon_shot_heavy", 1.0f, 1.0f, weapon.getLocation(), weapon.getShip().getVelocity());
        }
        
        // 3. ChargeLevel从1.0降到0.99以下时播放结束音效（光束发射完毕）
        if (chargeLevel < 0.99f && hasPlayedShotSound && !hasPlayedEndSound) {
            hasPlayedEndSound = true;
            Global.getSoundPlayer().playSound("Moci_WaveCannon_end", 1.0f, 1.0f, weapon.getLocation(), weapon.getShip().getVelocity());
        }
        
        // 4. ChargeLevel归零时重置所有标志
        if (chargeLevel == 0f) {
            hasFired = false;
            hasPlayedChargeSound = false;
            hasPlayedShotSound = false;
            hasPlayedEndSound = false;
        }
        
        // ==================== 机动性Debuff管理 ====================
        ShipAPI ship = weapon.getShip();
        applyManeuverDebuff(ship, chargeLevel);
        
        // 检测开火：chargeLevel达到1.0且未标记为已发射
        if (chargeLevel >= 1.0f && !hasFired) {
            hasFired = true;
            
            // 计算枪口位置
            Vector2f weaponLocation = weapon.getLocation();
            float shipFacing = weapon.getCurrAngle();
            Vector2f shipVelocity = ship.getVelocity();
            Vector2f muzzleLocation = MathUtils.getPointOnCircumference(weaponLocation,
                    weapon.getSlot().isHardpoint() ? MUZZLE_OFFSET_HARDPOINT : MUZZLE_OFFSET_TURRET, shipFacing);
            
            // 生成枪口闪光
            spawnMuzzleFlash(muzzleLocation, shipVelocity, shipFacing, engine);
            
            // 清理光晕
            if (flareEntity != null && !flareEntity.hasDelete()) {
                flareEntity.delete();
                flareEntity = null;
            }
        }
        
        // 计算枪口位置
        Vector2f weaponLocation = weapon.getLocation();
        float shipFacing = weapon.getCurrAngle();
        Vector2f shipVelocity = ship.getVelocity();
        Vector2f muzzleLocation = MathUtils.getPointOnCircumference(weaponLocation,
                weapon.getSlot().isHardpoint() ? MUZZLE_OFFSET_HARDPOINT : MUZZLE_OFFSET_TURRET, shipFacing);
        
        // ==================== 蓄力特效 ====================
        // 只在未发射状态下生成蓄力特效
        if (isFiring && chargeLevel > 0 && !hasFired) {
            // 计算粒子和光线的生成数量
            int particleCount = calculateParticleCount(chargeLevel);
            int trailCount = calculateTrailCount(chargeLevel);
            
            // 生成粒子
            for (int i = 0; i < particleCount; i++) {
                spawnParticle(muzzleLocation, shipFacing, shipVelocity, chargeLevel, engine);
            }
            
            // 生成光线
            for (int i = 0; i < trailCount; i++) {
                spawnTrail(muzzleLocation, shipFacing, shipVelocity, chargeLevel, engine);
            }
            
            // ==================== 光球效果（0-0.5阶段）====================
            if (chargeLevel < STAGE_1_END) {
                spawnChargingGlow(muzzleLocation, chargeLevel, engine);
            }
            
            // ==================== 光晕管理 ====================
            if (chargeLevel >= STAGE_1_END) {
                if (flareEntity == null || flareEntity.hasDelete()) {
                    createFlareEntity(muzzleLocation, shipFacing);
                }
                updateFlareEntity(muzzleLocation, shipFacing, chargeLevel);
            }
        } else {
            // 停止蓄力时清理光晕
            if (flareEntity != null && !flareEntity.hasDelete()) {
                flareEntity.delete();
                flareEntity = null;
            }
        }
        
        // ==================== 电弧效果（0.8阶段开始，直到降到0.8以下停止）====================
        // 电弧效果独立于蓄力特效，在开火后继续生成
        if (chargeLevel >= ARC_START) {
            arcTimer += amount;
            
            // 每0.2秒生成一个电弧
            if (arcTimer >= ARC_INTERVAL) {
                arcTimer -= ARC_INTERVAL;
                spawnArc(muzzleLocation, shipFacing, shipVelocity, chargeLevel, engine);
            }
        } else {
            // ChargeLevel降到0.8以下时重置电弧计时器
            arcTimer = 0f;
        }
    }

    
    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 可以在这里添加弹丸特效
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 计算粒子/光线生成概率
     * 0.8-1.0阶段急剧减少
     */
    private float calculateSpawnChance(float chargeLevel) {
        if (chargeLevel < STAGE_2_END) {
            return 1.0f;
        }
        // 0.8-1.0: 从1.0降到0
        float progress = (chargeLevel - STAGE_2_END) / (1.0f - STAGE_2_END);
        return 1.0f - progress;
    }
    
    /**
     * 计算粒子生成数量
     */
    private int calculateParticleCount(float chargeLevel) {
        float spawnChance = calculateSpawnChance(chargeLevel);
        if (Math.random() > spawnChance) {
            return 0;
        }
        
        if (chargeLevel < STAGE_1_END) {
            return 3; // 0-0.5: 正常数量
        } else if (chargeLevel < STAGE_2_END) {
            return 4; // 0.5-0.8: 稍多
        } else {
            return 1; // 0.8-1.0: 急剧减少
        }
    }
    
    /**
     * 计算光线生成数量
     */
    private int calculateTrailCount(float chargeLevel) {
        float spawnChance = calculateSpawnChance(chargeLevel);
        if (Math.random() > spawnChance) {
            return 0;
        }
        
        if (chargeLevel < STAGE_1_END) {
            return 1; // 0-0.5: 正常数量
        } else if (chargeLevel < STAGE_2_END) {
            return 2; // 0.5-0.8: 更多
        } else {
            return 0; // 0.8-1.0: 几乎没有
        }
    }
    
    /**
     * 应用机动性Debuff
     * 
     * @param ship 舰船
     * @param chargeLevel 当前蓄力等级
     */
    private void applyManeuverDebuff(ShipAPI ship, float chargeLevel) {
        if (ship == null) {
            return;
        }
        
        // 计算Debuff强度
        float debuffStrength = 0f;
        
        if (chargeLevel >= DEBUFF_START) {
            if (!hasFired) {
                // 蓄力阶段：0.1-0.5从0增长到1.0
                if (chargeLevel < DEBUFF_MAX) {
                    debuffStrength = (chargeLevel - DEBUFF_START) / (DEBUFF_MAX - DEBUFF_START);
                } else {
                    // 0.5-1.0保持最大强度
                    debuffStrength = 1.0f;
                }
            } else {
                // 开火后：从1.0降低到0.8时从1.0降到0
                if (chargeLevel > DEBUFF_END) {
                    debuffStrength = 1.0f;
                } else {
                    debuffStrength = (chargeLevel - DEBUFF_END) / (1.0f - DEBUFF_END);
                }
            }
        }
        
        // 应用Debuff
        if (debuffStrength > 0f) {
            // 保存原始质量
            if (originalMass == null) {
                originalMass = ship.getMass();
            }
            
            // 计算实际的倍率：从1.0插值到目标倍率
            float speedMult = 1.0f - (1.0f - DEBUFF_SPEED_MULT) * debuffStrength;
            float maneuverMult = 1.0f - (1.0f - DEBUFF_MANEUVER_MULT) * debuffStrength;
            
            // 计算减速和质量加成：从1.0插值到目标倍率
            float decelMult = 1.0f + (DEBUFF_DECEL_MULT - 1.0f) * debuffStrength;  // 1.0 -> 2.0
            float massMult = 1.0f + (DEBUFF_MASS_MULT - 1.0f) * debuffStrength;    // 1.0 -> 2.0
            
            // 应用到舰船属性
            ship.getMutableStats().getMaxSpeed().modifyMult(DEBUFF_ID, speedMult);
            ship.getMutableStats().getAcceleration().modifyMult(DEBUFF_ID, maneuverMult);
            ship.getMutableStats().getDeceleration().modifyMult(DEBUFF_ID, decelMult);  // 增加减速能力
            ship.getMutableStats().getTurnAcceleration().modifyMult(DEBUFF_ID, maneuverMult);
            ship.getMutableStats().getMaxTurnRate().modifyMult(DEBUFF_ID, maneuverMult);
            
            // 直接设置质量
            ship.setMass(originalMass * massMult);
            
            // 强制关闭战术系统（如果正在激活）
            ShipSystemAPI system = ship.getSystem();
            if (system != null && system.isOn()) {
                // 强制将系统状态设置为OUT（关闭状态），effectLevel设为0
                system.forceState(ShipSystemAPI.SystemState.OUT, 0f);
            }
            
            // 阻止激活战术系统、防御系统和散热
            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
            ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);
            ship.blockCommandForOneFrame(ShipCommand.VENT_FLUX);
            
            // 显示状态（仅玩家舰船）
            if (ship == Global.getCombatEngine().getPlayerShip()) {
                // 主状态：机动性降低
                String statusText = String.format("机动性降低 %d%% - 质量 +%d%%",
                    (int)((1.0f - maneuverMult) * 100),
                    (int)((massMult - 1.0f) * 100));
                Global.getCombatEngine().maintainStatusForPlayerShip(
                    STATUS_KEY,
                    "graphics/icons/hullsys/darkened_gaze.png",
                    "波动炮 - 空间锚启动",
                    statusText,
                    true  // 负面效果，显示为红色
                );
                
                // 附加状态1：禁用战术系统
                Global.getCombatEngine().maintainStatusForPlayerShip(
                    STATUS_KEY + "_system",
                    "graphics/icons/hullsys/darkened_gaze.png",
                    "战术系统",
                    "已禁用",
                    true  // 负面效果
                );
                
                // 附加状态2：禁用散热
                Global.getCombatEngine().maintainStatusForPlayerShip(
                    STATUS_KEY + "_vent",
                    "graphics/icons/hullsys/darkened_gaze.png",
                    "强制排幅",
                    "已禁用",
                    true  // 负面效果
                );
            }
        } else {
            // 移除Debuff
            ship.getMutableStats().getMaxSpeed().unmodify(DEBUFF_ID);
            ship.getMutableStats().getAcceleration().unmodify(DEBUFF_ID);
            ship.getMutableStats().getDeceleration().unmodify(DEBUFF_ID);
            ship.getMutableStats().getTurnAcceleration().unmodify(DEBUFF_ID);
            ship.getMutableStats().getMaxTurnRate().unmodify(DEBUFF_ID);
            
            // 恢复原始质量
            if (originalMass != null) {
                ship.setMass(originalMass);
                originalMass = null;
            }
        }
    }
    
    /**
     * 生成蓄力光球（0-0.5阶段）
     */
    private void spawnChargingGlow(Vector2f location, float chargeLevel, CombatEngineAPI engine) {
        // 计算光球尺寸：从0增长到光晕最小值
        float progress = chargeLevel / STAGE_1_END;  // 0-0.5映射到0-1
        float glowSize = FLARE_SIZE_MIN * 2f * progress;
        
        // 使用橙黄色（与粒子颜色一致）
        Color glowColor = PARTICLE_COLOR_ORANGE;
        
        // 亮度从1f增长到5f
        float brightness = 1f + progress * 4f;
        
        // 生成光球（使用addSmoothParticle创建柔和光球）
        engine.addSmoothParticle(
            location,
            ZERO,  // 零速度，静止
            glowSize,
            brightness,
            0.1f,  // 短持续时间，每帧刷新
            glowColor
        );
    }
    
    /**
     * 生成粒子
     */
    private void spawnParticle(Vector2f muzzleLocation, float shipFacing, Vector2f shipVelocity, 
                               float chargeLevel, CombatEngineAPI engine) {
        // 计算距离范围和角度扩散
        float distanceMultiplier;
        float angleMultiplier;
        
        if (chargeLevel < STAGE_2_END) {
            // 0-0.8: 随chargeLevel增加而增加
            distanceMultiplier = 0.3f + chargeLevel * 0.7f; // 0.3-0.86
            angleMultiplier = 1.0f; // 保持完整角度范围
        } else {
            // 0.8-1.0: 急剧收束到0
            float progress = (chargeLevel - STAGE_2_END) / (1.0f - STAGE_2_END);
            distanceMultiplier = 0.86f * (1.0f - progress); // 0.86-0
            angleMultiplier = 1.0f - progress; // 1.0-0（角度范围也收束）
        }
        
        float maxDistance = PARTICLE_DISTANCE_MAX * distanceMultiplier;
        float minDistance = PARTICLE_DISTANCE_MIN * distanceMultiplier;
        
        // 随机距离
        float distance = MathUtils.getRandomNumberInRange(minDistance, maxDistance);
        
        // 随机角度（角度范围也随收束而减小）
        float angleSpread = PARTICLE_ANGLE_SPREAD * angleMultiplier;
        float angle = MathUtils.getRandomNumberInRange(-0.5f * angleSpread, 0.5f * angleSpread);
        
        // 计算生成位置
        Vector2f spawnLocation = MathUtils.getPointOnCircumference(muzzleLocation, distance, angle + shipFacing);
        
        // 计算速度
        float duration = PARTICLE_DURATION * MathUtils.getRandomNumberInRange(0.5f, 1.5f);
        float speed = distance / duration;
        Vector2f particleVelocity = MathUtils.getPointOnCircumference(shipVelocity, speed, 180.0f + angle + shipFacing);
        
        // 决定颜色和尺寸
        Color color;
        float sizeMin, sizeMax;
        
        if (chargeLevel < STAGE_1_END) {
            // 0-0.5: 只有橙黄色
            color = PARTICLE_COLOR_ORANGE;
            sizeMin = PARTICLE_SIZE_MIN_ORANGE;
            sizeMax = PARTICLE_SIZE_MAX_ORANGE;
        } else if (chargeLevel < STAGE_2_END) {
            // 0.5-0.8: 混合橙黄色和蓝白色
            // 蓝色比例从0%增长到100%
            float blueChance = (chargeLevel - STAGE_1_END) / (STAGE_2_END - STAGE_1_END);
            
            if (Math.random() < blueChance) {
                // 蓝白色粒子
                color = PARTICLE_COLOR_BLUE;
                sizeMin = PARTICLE_SIZE_MIN_BLUE;
                sizeMax = PARTICLE_SIZE_MAX_BLUE;
            } else {
                // 橙黄色粒子
                color = PARTICLE_COLOR_ORANGE;
                sizeMin = PARTICLE_SIZE_MIN_ORANGE;
                sizeMax = PARTICLE_SIZE_MAX_ORANGE;
            }
        } else {
            // 0.8-1.0: 只有蓝白色
            color = PARTICLE_COLOR_BLUE;
            sizeMin = PARTICLE_SIZE_MIN_BLUE;
            sizeMax = PARTICLE_SIZE_MAX_BLUE;
        }
        
        // 尺寸随蓄力等级增加
        float sizeMult = 0.5f + chargeLevel * 0.5f;
        float size = MathUtils.getRandomNumberInRange(sizeMin, sizeMax) * sizeMult;
        
        // 亮度随蓄力等级增加
        float brightness = 0.5f + chargeLevel * 0.5f;
        
        // 生成粒子
        engine.addHitParticle(spawnLocation, particleVelocity, size, brightness, duration, color);
    }
    
    /**
     * 生成光线
     */
    private void spawnTrail(Vector2f muzzleLocation, float shipFacing, Vector2f shipVelocity, float chargeLevel, CombatEngineAPI engine) {
        // 计算距离范围和角度扩散
        float distanceMultiplier;
        float angleMultiplier;
        
        if (chargeLevel < STAGE_2_END) {
            // 0-0.8: 随chargeLevel增加而增加
            distanceMultiplier = 0.3f + chargeLevel * 0.7f; // 0.3-0.86
            angleMultiplier = 1.0f; // 保持完整角度范围
        } else {
            // 0.8-1.0: 急剧收束到0
            float progress = (chargeLevel - STAGE_2_END) / (1.0f - STAGE_2_END);
            distanceMultiplier = 0.86f * (1.0f - progress); // 0.86-0
            angleMultiplier = 1.0f - progress; // 1.0-0（角度范围也收束）
        }
        
        float maxDistance = TRAIL_DISTANCE_MAX * distanceMultiplier;
        float minDistance = TRAIL_DISTANCE_MIN * distanceMultiplier;
        
        // 随机距离
        float distance = MathUtils.getRandomNumberInRange(minDistance, maxDistance);
        
        // 随机角度（角度范围也随收束而减小）
        float angleSpread = PARTICLE_ANGLE_SPREAD * 0.8f * angleMultiplier;
        float angle = MathUtils.getRandomNumberInRange(-0.5f * angleSpread, 0.5f * angleSpread);
        
        // 计算起始位置
        Vector2f startPos = MathUtils.getPointOnCircumference(muzzleLocation, distance, angle + shipFacing);
        
        // 计算方向向量
        Vector2f direction = new Vector2f(
            muzzleLocation.x - startPos.x,
            muzzleLocation.y - startPos.y
        );
        
        // 决定颜色和宽度
        Color coreColor, startColor, endColor;
        float startWidth, endWidth;
        
        if (chargeLevel < STAGE_1_END) {
            // 0-0.5: 只有橙黄色
            coreColor = TRAIL_COLOR_ORANGE_CORE;
            startColor = TRAIL_COLOR_ORANGE_START;
            endColor = TRAIL_COLOR_ORANGE_END;
            startWidth = TRAIL_WIDTH_START_ORANGE;
            endWidth = TRAIL_WIDTH_END_ORANGE;
        } else if (chargeLevel < STAGE_2_END) {
            // 0.5-0.8: 混合橙黄色和蓝白色
            // 蓝色比例从0%增长到100%
            float blueChance = (chargeLevel - STAGE_1_END) / (STAGE_2_END - STAGE_1_END);
            
            if (Math.random() < blueChance) {
                // 蓝白色光线
                coreColor = TRAIL_COLOR_BLUE_CORE;
                startColor = TRAIL_COLOR_BLUE_START;
                endColor = TRAIL_COLOR_BLUE_END;
                startWidth = TRAIL_WIDTH_START_BLUE;
                endWidth = TRAIL_WIDTH_END_BLUE;
            } else {
                // 橙黄色光线
                coreColor = TRAIL_COLOR_ORANGE_CORE;
                startColor = TRAIL_COLOR_ORANGE_START;
                endColor = TRAIL_COLOR_ORANGE_END;
                startWidth = TRAIL_WIDTH_START_ORANGE;
                endWidth = TRAIL_WIDTH_END_ORANGE;
            }
        } else {
            // 0.8-1.0: 只有蓝白色
            coreColor = TRAIL_COLOR_BLUE_CORE;
            startColor = TRAIL_COLOR_BLUE_START;
            endColor = TRAIL_COLOR_BLUE_END;
            startWidth = TRAIL_WIDTH_START_BLUE;
            endWidth = TRAIL_WIDTH_END_BLUE;
        }
        
        // 宽度随蓄力等级增加
        float widthMult = 0.7f + chargeLevel * 0.3f;
        startWidth *= widthMult;
        endWidth *= widthMult;
        
        // 创建TrailEntity
        TrailEntity trail = new TrailEntity();
        
        // 设置材质
        trail.getMaterialData().setDiffuse(Global.getSettings().getSprite("graphics/fx/beamfringeb.png"));
        trail.getMaterialData().setEmissive(Global.getSettings().getSprite("graphics/fx/beamcoreb.png"));
        trail.getMaterialData().setEmissiveColor(coreColor);
        trail.getMaterialData().setEmissiveColorAlpha(0.4f);
        
        // 设置颜色
        trail.setStartColor(startColor);
        trail.setEndColor(endColor);
        
        // 设置宽度
        trail.setStartWidth(startWidth);
        trail.setEndWidth(endWidth);
        
        // 效果设置
        trail.setJitterPower(0.0f);
        trail.setFlick(true);
        trail.setFlickMixValue(0.5f);
        
        // 纹理参数
        trail.setTexturePixels(256.0f);
        trail.setTextureSpeed(100.0f);
        
        // 填充设置
        trail.setFillStartAlpha(0.0f);
        trail.setFillEndAlpha(0.0f);
        trail.setFillStartFactor(0.9f);
        trail.setFillEndFactor(0.9f);
        
        // 渲染层级
        trail.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        
        // 混合模式
        trail.setAdditiveBlend();
        
        // 添加节点
        trail.addNode(new Vector2f(0, 0));
        trail.addNode(new Vector2f(direction.x * 0.01f, direction.y * 0.01f));
        
        // 设置位置
        trail.setLocation(startPos);
        
        // 线条模式
        trail.setStripLineMode(true);
        
        // 提交节点
        trail.setNodeRefreshAllFromCurrentIndex();
        trail.submitNodes();
        
        // 定时器
        trail.setGlobalTimer(0.0f, TRAIL_DURATION, 0.3f);
        
        // 添加到渲染系统
        CombatRenderingManager.addEntity(trail);
        
        // 添加到活跃列表（传入舰船速度）
        activeTrails.add(new TrailEntityWrapper(trail, TRAIL_DURATION, startPos, muzzleLocation, endColor, shipVelocity));
    }
    
    /**
     * 创建光晕实体
     */
    private void createFlareEntity(Vector2f location, float facing) {
        flareEntity = new FlareEntity();
        
        // 设置位置和角度（+90度使光晕横向对齐武器方向）
        flareEntity.setLocation(location);
        flareEntity.setFacingScale(MathUtils.clampAngle(facing + 90f), 1f, 1f);  // 设置光晕角度跟随武器朝向
        
        // 初始尺寸（10:1横向扁平光晕）
        flareEntity.setSize(FLARE_SIZE_MIN * 10, FLARE_SIZE_MIN);
        
        // 形状
        flareEntity.setSmoothDisc();
        
        // 初始颜色（橙黄色）
        flareEntity.setFringeColor(FLARE_COLOR_ORANGE);
        flareEntity.setCoreColor(Color.WHITE);
        flareEntity.setCoreAlpha(1.0f);
        flareEntity.setFringeAlpha(1.0f);
        
        // 混合模式
        flareEntity.setAdditiveBlend();
        
        // 效果参数（参考BoxUtil演示）
        flareEntity.setFlick(true);
        flareEntity.setFlickWhenPaused(false);
        flareEntity.setFlickMixValue(0.8f);  // 闪烁混合值（增强闪烁效果）
        flareEntity.setNoisePower(0.33f);    // 噪声强度（演示值）
        flareEntity.setGlowPower(1.0f);      // 发光强度（演示值）
        
        // 渲染层级
        flareEntity.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        
        // 定时器（淡入0秒，持续8192秒，淡出2秒 - 参考演示）
        flareEntity.setGlobalTimer(0.0f, 8192.0f, 2.0f);

        // 提交到渲染管理器
        CombatRenderingManager.addEntity(flareEntity);
    }
    
    /**
     * 更新光晕实体
     */
    private void updateFlareEntity(Vector2f location, float facing, float chargeLevel) {
        if (flareEntity == null || flareEntity.hasDelete()) {
            return;
        }
        
        // 计算尺寸
        float size;
        if (chargeLevel < STAGE_2_END) {
            // 0.5-0.8: 缓慢增长
            float progress = (chargeLevel - STAGE_1_END) / (STAGE_2_END - STAGE_1_END);
            size = FLARE_SIZE_MIN + (FLARE_SIZE_MAX - FLARE_SIZE_MIN) * progress * 0.5f;
        } else {
            // 0.8-1.0: 急剧增长
            float progress = (chargeLevel - STAGE_2_END) / (1.0f - STAGE_2_END);
            size = FLARE_SIZE_MIN + (FLARE_SIZE_MAX - FLARE_SIZE_MIN) * (0.5f + progress * 0.5f);
        }
        
        // 保存当前尺寸用于闪光
        currentFlareSize = size;
        
        // 更新尺寸（保持10:1宽高比）
        flareEntity.setSize(size * 10, size);
        
        // 计算颜色
        Color fringeColor;
        float alpha;
        
        if (chargeLevel < STAGE_2_END) {
            // 0.5-0.8: 从橙黄色渐变到蓝白色
            float progress = (chargeLevel - STAGE_1_END) / (STAGE_2_END - STAGE_1_END);
            
            int r = (int)(FLARE_COLOR_ORANGE.getRed() + (FLARE_COLOR_BLUE.getRed() - FLARE_COLOR_ORANGE.getRed()) * progress);
            int g = (int)(FLARE_COLOR_ORANGE.getGreen() + (FLARE_COLOR_BLUE.getGreen() - FLARE_COLOR_ORANGE.getGreen()) * progress);
            int b = (int)(FLARE_COLOR_ORANGE.getBlue() + (FLARE_COLOR_BLUE.getBlue() - FLARE_COLOR_ORANGE.getBlue()) * progress);
            
            fringeColor = new Color(r, g, b);
            alpha = 0.6f + progress * 0.3f; // 亮度增加
        } else {
            // 0.8-1.0: 保持蓝色
            float progress = (chargeLevel - STAGE_2_END) / (1.0f - STAGE_2_END);
            
            // 从蓝白色渐变到更深的蓝色
            int r = (int)(FLARE_COLOR_BLUE.getRed() + (FLARE_COLOR_FINAL_BLUE.getRed() - FLARE_COLOR_BLUE.getRed()) * progress);
            int g = (int)(FLARE_COLOR_BLUE.getGreen() + (FLARE_COLOR_FINAL_BLUE.getGreen() - FLARE_COLOR_BLUE.getGreen()) * progress);
            int b = (int)(FLARE_COLOR_BLUE.getBlue() + (FLARE_COLOR_FINAL_BLUE.getBlue() - FLARE_COLOR_BLUE.getBlue()) * progress);
            
            fringeColor = new Color(r, g, b);
            alpha = 0.9f + progress * 0.1f;
        }
        
        // 更新颜色
        flareEntity.setFringeColor(fringeColor);
        flareEntity.setFringeAlpha(alpha);
        flareEntity.setCoreAlpha(Math.min(1.0f, alpha + 0.2f));
        
        // 更新位置和角度（+90度使光晕横向对齐武器方向）
        flareEntity.setLocation(location);
        flareEntity.setFacingScale(MathUtils.clampAngle(facing + 90f), 1f, 1f);  // 更新光晕角度跟随武器朝向
    }
    
    /**
     * 生成枪口闪光
     */
    private void spawnMuzzleFlash(Vector2f location, Vector2f velocity, float facing, CombatEngineAPI engine) {
        // 使用光晕的当前尺寸（如果没有光晕则使用默认值）
        float flashSize = currentFlareSize > 0 ? currentFlareSize : FLARE_SIZE_MAX;
        
        // 使用ArmigerMuzzleFlashScript的BRIGHT粒子效果（addHitParticle，高亮度）
        
        // 1. 外层闪光（蓝色，类似FLASH_FRINGE_1）
        engine.addHitParticle(
            location,
            ZERO,  // 零速度，静止
            flashSize * 25f,
            20f,   // 高亮度
            FLASH_DURATION * 1.5f,
            FLARE_COLOR_FINAL_BLUE
        );
        
        // 2. 内层闪光（白色，类似FLASH_CORE_1）
        engine.addHitParticle(
            location,
            ZERO,  // 零速度，静止
            flashSize * 15f,
            20f,   // 更高亮度
            FLASH_DURATION * 1.3f,
            FLASH_COLOR
        );
        
        // 4. 飞溅粒子（360度全向，带速度）
        spawnSplashParticles(location, velocity, flashSize, engine);
        
        // 5. 创建一个带淡出效果的BoxUtil光晕（与chargeLevel=1.0时的光晕参数相同）
        createFadeOutFlare(location, flashSize, facing);
    }
    
    /**
     * 生成飞溅粒子
     */
    private void spawnSplashParticles(Vector2f location, Vector2f velocity, float baseSize, CombatEngineAPI engine) {
        int particleCount = 120; // 飞溅粒子数量
        
        for (int i = 0; i < particleCount; i++) {
            // 随机角度（360度全向）
            float angle = MathUtils.getRandomNumberInRange(0f, 360f);
            
            // 随机速度（向外飞溅）
            float speed = MathUtils.getRandomNumberInRange(250f, 500f);
            Vector2f particleVel = MathUtils.getPointOnCircumference(velocity, speed, angle);
            
            // 随机尺寸
            float size = MathUtils.getRandomNumberInRange(baseSize * 0.1f, baseSize * 0.2f);
            
            // 随机持续时间
            float duration = MathUtils.getRandomNumberInRange(0.3f, 0.6f);
            
            // 随机亮度
            float brightness = MathUtils.getRandomNumberInRange(0.8f, 1.5f);
            
            // 白色粒子
            engine.addSmoothParticle(location, particleVel, size, brightness, duration, FLASH_COLOR);
        }
    }
    
    /**
     * 创建带淡出效果的光晕（开火时）
     */
    private void createFadeOutFlare(Vector2f location, float size, float facing) {
        FlareEntity fadeFlare = new FlareEntity();
        
        // 设置位置和角度（+90度使光晕横向对齐武器方向）
        fadeFlare.setLocation(location);
        fadeFlare.setFacingScale(MathUtils.clampAngle(facing + 90f), 1f, 1f);  // 设置光晕角度跟随武器朝向
        
        // 尺寸（10:1宽高比，与chargeLevel=1.0时相同）
        fadeFlare.setSize(size * 10, size);
        
        // 形状
        fadeFlare.setSmoothDisc();
        
        // 颜色（使用chargeLevel=1.0时的蓝色）
        fadeFlare.setFringeColor(FLARE_COLOR_FINAL_BLUE);
        fadeFlare.setCoreColor(Color.WHITE);
        fadeFlare.setCoreAlpha(1.0f);
        fadeFlare.setFringeAlpha(1.0f);
        
        // 混合模式
        fadeFlare.setAdditiveBlend();
        
        // 效果参数（与主光晕相同）
        fadeFlare.setFlick(true);
        fadeFlare.setFlickWhenPaused(false);
        fadeFlare.setFlickMixValue(0.8f);
        fadeFlare.setNoisePower(0.33f);
        fadeFlare.setGlowPower(1.0f);
        
        // 渲染层级
        fadeFlare.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        
        // 定时器（无淡入，持续0秒，1秒淡出）
        fadeFlare.setGlobalTimer(0.0f, 0.0f, 1.0f);
        
        // 提交到渲染管理器（会自动在1秒后删除）
        CombatRenderingManager.addEntity(fadeFlare);
    }
    
    /**
     * 更新所有活跃的TrailEntity
     */
    private void updateTrailEntities(float amount) {
        if (activeTrails.isEmpty()) {
            return;
        }
        
        Iterator<TrailEntityWrapper> iterator = activeTrails.iterator();
        
        while (iterator.hasNext()) {
            TrailEntityWrapper wrapper = iterator.next();
            wrapper.age += amount;
            
            float progress = wrapper.age / wrapper.maxAge;
            
            if (progress >= 1.0f) {
                wrapper.trail.delete();
                iterator.remove();
            } else {
                // 更新位置（跟随舰船速度移动）
                wrapper.startPos.x += wrapper.shipVelocity.x * amount;
                wrapper.startPos.y += wrapper.shipVelocity.y * amount;
                wrapper.trail.setLocation(wrapper.startPos);
                
                Vector2f currentDirection = new Vector2f(
                    wrapper.fullDirection.x * progress,
                    wrapper.fullDirection.y * progress
                );
                
                List<Vector2f> nodes = wrapper.trail.getNodes();
                if (nodes != null && nodes.size() >= 2) {
                    nodes.get(0).set(0, 0);
                    nodes.get(1).set(currentDirection);
                    
                    wrapper.trail.setNodeRefreshIndex(0);
                    wrapper.trail.setNodeRefreshSize(2);
                    wrapper.trail.submitNodes();
                }
                
                // 淡出效果
                float alpha = 1.0f;
                if (progress > 0.7f) {
                    alpha = (1.0f - progress) / 0.3f;
                }
                
                Color originalEnd = wrapper.originalEndColor;
                Color endColor = new Color(
                    originalEnd.getRed(),
                    originalEnd.getGreen(),
                    originalEnd.getBlue(),
                    (int)(originalEnd.getAlpha() * alpha)
                );
                
                wrapper.trail.setEndColor(endColor);
            }
        }
    }
    
    /**
     * 生成电弧
     */
    private void spawnArc(Vector2f muzzleLocation, float shipFacing, Vector2f shipVelocity, float chargeLevel, CombatEngineAPI engine) {
        // 计算电弧参数强度
        float arcIntensity;
        
        // 判断是否处于降低阶段（开火后）
        // 如果hasFired为true，说明已经开火，chargeLevel正在从1.0降低
        if (hasFired) {
            // 开火后：chargeLevel从1.0降低到0.8，arcIntensity从1.0降到0
            arcIntensity = Math.max(0f, (chargeLevel - ARC_START) / (1.0f - ARC_START));
        } else {
            // 蓄力阶段：0.8-1.0，保持满强度
            arcIntensity = 1.0f;
        }
        
        // 如果强度为0，不生成电弧
        if (arcIntensity <= 0.01f) {
            return;
        }
        
        // 计算距离范围
        float minDistance, maxDistance;
        
        if (!hasFired) {
            // 蓄力阶段（0.8-1.0）：使用完整范围
            minDistance = MathUtils.getRandomNumberInRange(ARC_MIN_DISTANCE_MIN, ARC_MIN_DISTANCE_MAX);
            maxDistance = MathUtils.getRandomNumberInRange(ARC_MAX_DISTANCE_MIN, ARC_MAX_DISTANCE_MAX);
        } else {
            // 开火后（chargeLevel从1.0降低）：使用最小范围
            minDistance = MathUtils.getRandomNumberInRange(ARC_MIN_DISTANCE_MIN, ARC_MIN_DISTANCE_MAX);
            maxDistance = minDistance;  // 最大距离等于最小距离，只使用最小范围
        }
        
        // 随机终点位置（在枪口周围一定范围内）
        float distance = MathUtils.getRandomNumberInRange(minDistance, maxDistance);
        float angle = MathUtils.getRandomNumberInRange(-ARC_ANGLE_SPREAD * 0.5f, ARC_ANGLE_SPREAD * 0.5f);
        Vector2f endPos = MathUtils.getPointOnCircumference(muzzleLocation, distance, angle + shipFacing);
        
        // 获取电弧颜色
        Color arcColor;
        if (hasFired) {
            // 开火后：始终使用蓝色
            arcColor = FLARE_COLOR_FINAL_BLUE;
        } else {
            // 蓄力阶段：根据chargeLevel决定
            if (chargeLevel < STAGE_2_END) {
                // 0.7-0.8: 橙黄色
                arcColor = FLARE_COLOR_ORANGE;
            } else {
                // 0.8-1.0: 蓝色
                arcColor = FLARE_COLOR_FINAL_BLUE;
            }
        }
        
        // 计算电弧颜色（带透明度衰减）
        Color fringeColor = new Color(
            arcColor.getRed(),
            arcColor.getGreen(),
            arcColor.getBlue(),
            (int)(arcColor.getAlpha() * arcIntensity)
        );
        
        Color coreColor = new Color(
            255,
            255,
            255,
            (int)(255 * arcIntensity)
        );
        
        // 计算电弧粗细（从最大值到最小值，随强度变化）
        float thickness = ARC_THICKNESS_MIN + (ARC_THICKNESS_MAX - ARC_THICKNESS_MIN) * arcIntensity;
        
        // 使用原生API生成视觉电弧（无伤害）
        engine.spawnEmpArcVisual(
            muzzleLocation,  // 起点
            null,            // 起点锚点（null表示不跟随实体）
            endPos,          // 终点
            null,            // 终点锚点（null表示不跟随实体）
            thickness,       // 粗细
            fringeColor,     // 边缘颜色
            coreColor        // 核心颜色
        );
    }
    
    
    /**
     * TrailEntity包装类
     */
    private static class TrailEntityWrapper {
        public final TrailEntity trail;
        public final float maxAge;
        public float age;
        public final Vector2f startPos;
        public final Vector2f fullDirection;
        public final Color originalEndColor;
        public final Vector2f shipVelocity;  // 舰船速度
        
        public TrailEntityWrapper(TrailEntity trail, float maxAge, Vector2f startPos, Vector2f targetPos, Color endColor, Vector2f shipVelocity) {
            this.trail = trail;
            this.maxAge = maxAge;
            this.age = 0f;
            this.startPos = new Vector2f(startPos);
            this.fullDirection = new Vector2f(
                targetPos.x - startPos.x,
                targetPos.y - startPos.y
            );
            // 保存原始结束颜色用于淡出计算
            this.originalEndColor = endColor;
            // 保存舰船速度用于位置更新
            this.shipVelocity = new Vector2f(shipVelocity);
        }
    }
}
