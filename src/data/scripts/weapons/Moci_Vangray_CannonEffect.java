package data.scripts.weapons;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams;
// import data.scripts.util.Moci_BoxBasedUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.FlareEntity;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.boxutil.units.standard.attribute.Instance2Data;
import org.boxutil.base.api.InstanceDataAPI;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Moci_Vangray_CannonEffect  implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

    // 电弧生成间隔(秒) - 0.1到0.2秒随机
    private static final float ARC_INTERVAL_MIN = 0.2f;
    private static final float ARC_INTERVAL_MAX = 0.25f;
    // 电弧距离范围（从弹丸中心到终点）
    private static final float ARC_DISTANCE_MIN = 35f;
    private static final float ARC_DISTANCE_MAX = 75f;
    // 弹丸电弧中心偏移（相对于弹丸位置）
    private static final float PROJECTILE_ARC_CENTER_OFFSET_X = 30f; // X轴偏移（沿弹丸飞行方向）
    private static final float PROJECTILE_ARC_CENTER_OFFSET_Y = 0f; // Y轴偏移（垂直于弹丸飞行方向）

    // 充能阶段参数
    private static final float CHARGE_LEVEL_STEP = 0.1f; // 每次生成电弧的充能等级间隔
    private static final float CHARGE_ARC_BASE_OFFSET_X = -35f; // 电弧原点X偏移（负值表示向左）
    private static final float CHARGE_ARC_OFFSET_Y = 9f; // 电弧原点Y轴偏移
    private static final float CHARGE_ARC_SIDE_DISTANCE_MIN = 35f; // 电弧另一端距离最小值
    private static final float CHARGE_ARC_SIDE_DISTANCE_MAX = 65f; // 电弧另一端距离最大值
    private static final float CHARGE_ARC_ANGLE_VARIATION = 25f; // 电弧角度随机偏移范围（正负度数）
    
    // 充能电弧颜色参数（通过Alpha控制亮度）
    private static final Color CHARGE_ARC_START_CORE_COLOR = new Color(225, 244, 250, 100); // 起始核心颜色（低亮度）
    private static final Color CHARGE_ARC_START_FRINGE_COLOR = new Color(161, 180, 255, 120); // 起始边缘颜色（低亮度）
    private static final Color CHARGE_ARC_MAX_CORE_COLOR = new Color(144, 158, 255, 255); // 最大充能核心颜色（高亮度）
    private static final Color CHARGE_ARC_MAX_FRINGE_COLOR = new Color(25, 25, 255, 255); // 最大充能边缘颜色（高亮度）
    
    // 充能电弧尺寸参数
    private static final float CHARGE_ARC_MIN_THICKNESS = 1f; // 最小电弧粗细
    private static final float CHARGE_ARC_MAX_THICKNESS = 10f; // 最大电弧粗细
    
    // 开火特效参数
    private static final float MUZZLE_FLASH_SIZE = 120f; // 闪光大小
    private static final float MUZZLE_FLASH_DURATION = 0.25f; // 闪光持续时间
    private static final Color MUZZLE_FLASH_COLOR = new Color(255, 255, 255, 255); // 纯白闪光
    
    // 充能原点闪光参数
    private static final float CHARGE_ORIGIN_FLASH_SIZE = 100f; // 充能原点闪光大小
    private static final float CHARGE_ORIGIN_FLASH_DURATION = 0.25f; // 充能原点闪光持续时间
    private static final Color CHARGE_ORIGIN_FLASH_COLOR = new Color(255, 255, 255, 255); // 纯白闪光
    
    // 充能原点粒子参数
    private static final int CHARGE_ORIGIN_PARTICLE_COUNT = 45; // 粒子数量
    private static final float CHARGE_ORIGIN_PARTICLE_SIZE_MIN = 2f; // 粒子大小最小值
    private static final float CHARGE_ORIGIN_PARTICLE_SIZE_MAX = 6f; // 粒子大小最大值
    private static final float CHARGE_ORIGIN_PARTICLE_SPEED_MIN = 125f; // 粒子速度最小值
    private static final float CHARGE_ORIGIN_PARTICLE_SPEED_MAX = 165f; // 粒子速度最大值
    private static final float CHARGE_ORIGIN_PARTICLE_DURATION_MIN = 1.2f; // 粒子持续时间最小值
    private static final float CHARGE_ORIGIN_PARTICLE_DURATION_MAX = 1.5f; // 粒子持续时间最大值
    private static final Color CHARGE_ORIGIN_PARTICLE_COLOR = new Color(255, 255, 255, 255); // 纯白粒子
    
    // 开火后坐力参数
    private static final float RECOIL_MOMENTUM = 800f; // 后坐力动量强度（正值表示向后推）
    private static final float RECOIL_MOMENTUM_MULTIPLIER = 100f; // 动量放大系数
    
    private static final int MUZZLE_SMOKE_COUNT = 12; // 烟雾数量
    private static final float MUZZLE_SMOKE_SIZE_MIN = 35f; // 烟雾大小最小值
    private static final float MUZZLE_SMOKE_SIZE_MAX = 50f; // 烟雾大小最大值
    private static final float MUZZLE_SMOKE_SIZE_MULT = 2.5f; // 烟雾大小倍数
    private static final float MUZZLE_SMOKE_DURATION_MIN = 1.75f; // 烟雾持续时间最小值
    private static final float MUZZLE_SMOKE_DURATION_MAX = 2f; // 烟雾持续时间最大值
    private static final float MUZZLE_SMOKE_ANGLE_SPREAD = 25f; // 烟雾角度扩散
    private static final float MUZZLE_SMOKE_SPEED_MIN = 10f; // 烟雾速度最小值
    private static final float MUZZLE_SMOKE_SPEED_MAX = 55f; // 烟雾速度最大值
    private static final Color MUZZLE_SMOKE_COLOR = new Color(255, 255, 255, 200); // 纯白烟雾
    
    private static final float MUZZLE_FLARE_WIDTH = 240f; // 光晕宽度
    private static final float MUZZLE_FLARE_HEIGHT = 30f; // 光晕高度（宽度的1/10）
    private static final Color MUZZLE_FLARE_CORE_COLOR = new Color(255, 255, 255, 255); // 白色核心
    private static final Color MUZZLE_FLARE_FRINGE_COLOR = new Color(255, 100, 100, 250); // 红色外圈

    // 存储弹体和它们的下次电弧生成时间
    private final Map<DamagingProjectileAPI, Float> projectileArcTimers = new HashMap<DamagingProjectileAPI, Float>();
    
    private float lastArcLevel = 0f; // 上次生成电弧的充能等级


    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused() || weapon == null || weapon.getShip() == null) return;


        // 处理充能阶段的电弧
        // 使用武器自带的充能等级（0.0 到 1.0）
        float chargeLevel = weapon.getChargeLevel();
        
        if (chargeLevel > 0f && chargeLevel < 1f) {
            // 检查是否达到下一个电弧生成等级（每0.05充能等级生成一次）
            if (chargeLevel - lastArcLevel >= CHARGE_LEVEL_STEP) {
                lastArcLevel = chargeLevel;
                
                // 获取武器朝向向量
                Vector2f weaponDir = VectorUtils.rotate(new Vector2f(1, 0), weapon.getCurrAngle());
                
                // 计算两个固定点：原点和开火点
                // 原点（保留偏移参数）
                float originOffsetX = CHARGE_ARC_BASE_OFFSET_X;
                float originOffsetY = CHARGE_ARC_OFFSET_Y;
                Vector2f weaponLoc = new Vector2f(weapon.getLocation());
                Vector2f originPoint = Vector2f.add(weaponLoc,
                        new Vector2f(weaponDir.x * originOffsetX - weaponDir.y * originOffsetY,
                                weaponDir.y * originOffsetX + weaponDir.x * originOffsetY), null);
                
                // 开火点（武器的实际开火位置）
                Vector2f firePoint = new Vector2f(weapon.getFirePoint(0));
                
                // 随机选择从哪个点生成电弧（原点或开火点）
                boolean fromOrigin = Math.random() < 0.5f;
                Vector2f arcStartPoint = fromOrigin ? originPoint : firePoint;
                
                // 确定电弧方向
                float baseAngle;
                if (fromOrigin) {
                    // 原点：只向右（武器朝向 + 90度）
                    baseAngle = weapon.getCurrAngle() - 90f;
                } else {
                    // 开火点：随机左或右
                    baseAngle = weapon.getCurrAngle() + (Math.random() < 0.5f ? -90f : 90f);
                }
                
                // 添加正负15度的随机偏移
                float angleVariation = MathUtils.getRandomNumberInRange(-CHARGE_ARC_ANGLE_VARIATION, CHARGE_ARC_ANGLE_VARIATION);
                float finalAngle = baseAngle + angleVariation;
                
                // 随机距离（25-50范围）
                float distance = MathUtils.getRandomNumberInRange(CHARGE_ARC_SIDE_DISTANCE_MIN, CHARGE_ARC_SIDE_DISTANCE_MAX);
                
                // 计算电弧终点
                Vector2f arcEndPoint = MathUtils.getPointOnCircumference(arcStartPoint, distance, finalAngle);
                
                // 根据充能等级插值颜色和尺寸
                Color coreColor = interpolateColor(CHARGE_ARC_START_CORE_COLOR, CHARGE_ARC_MAX_CORE_COLOR, chargeLevel);
                Color fringeColor = interpolateColor(CHARGE_ARC_START_FRINGE_COLOR, CHARGE_ARC_MAX_FRINGE_COLOR, chargeLevel);
                float thickness = CHARGE_ARC_MIN_THICKNESS + (CHARGE_ARC_MAX_THICKNESS - CHARGE_ARC_MIN_THICKNESS) * chargeLevel;
                EmpArcParams chargeArcParams = createChargeArcParams(chargeLevel);
                
                // 生成电弧
                engine.spawnEmpArcVisual(
                        arcStartPoint, weapon.getShip(),
                        arcEndPoint, weapon.getShip(),
                        thickness,
                        coreColor,
                        fringeColor,
                        chargeArcParams
                );
            }
        } else {
            // 充能结束或未开始，重置计数器
            lastArcLevel = 0f;
        }

        // 获取所有属于该武器的弹体
        List<DamagingProjectileAPI> projectiles = new ArrayList<DamagingProjectileAPI>();
        for (DamagingProjectileAPI proj : CombatUtils.getProjectilesWithinRange(weapon.getLocation(), 1000f)) {
            if (weapon == proj.getWeapon() && engine.isEntityInPlay(proj) && !proj.isExpired()) {
                projectiles.add(proj);
            }
        }

        // 为每个弹丸生成电弧效果
        for (DamagingProjectileAPI proj : projectiles) {
            // 获取或初始化该弹丸的计时器
            Float nextArcTime = projectileArcTimers.get(proj);
            if (nextArcTime == null) {
                // 新弹丸，设置随机初始延迟
                nextArcTime = MathUtils.getRandomNumberInRange(ARC_INTERVAL_MIN, ARC_INTERVAL_MAX);
                projectileArcTimers.put(proj, nextArcTime);
            }
            
            // 更新计时器
            nextArcTime -= amount;
            
            // 如果时间到了，生成电弧
            if (nextArcTime <= 0f) {
                // 计算电弧中心位置（弹丸位置 + 偏移）
                Vector2f projLoc = new Vector2f(proj.getLocation());
                Vector2f projDir = VectorUtils.rotate(new Vector2f(1, 0), proj.getFacing());
                
                // 应用偏移：X沿飞行方向，Y垂直于飞行方向
                Vector2f arcCenter = Vector2f.add(projLoc,
                        new Vector2f(projDir.x * PROJECTILE_ARC_CENTER_OFFSET_X - projDir.y * PROJECTILE_ARC_CENTER_OFFSET_Y,
                                projDir.y * PROJECTILE_ARC_CENTER_OFFSET_X + projDir.x * PROJECTILE_ARC_CENTER_OFFSET_Y), null);
                
                // 随机一个角度作为第一个电弧的方向
                float baseAngle = MathUtils.getRandomNumberInRange(0f, 360f);
                // 第二个电弧在180度对面
                float oppositeAngle = baseAngle + 180f;
                EmpArcParams projectileArcParams = createProjectileArcParams();
                
                // 生成第一个电弧
                float distance1 = MathUtils.getRandomNumberInRange(ARC_DISTANCE_MIN, ARC_DISTANCE_MAX);
                Vector2f arcEnd1 = MathUtils.getPointOnCircumference(arcCenter, distance1, baseAngle);
                
                engine.spawnEmpArcVisual(
                        arcCenter, proj,   // 起点：偏移后的中心，绑定到弹丸
                        arcEnd1, proj,     // 终点：相对位置，绑定到弹丸
                        MathUtils.getRandomNumberInRange(5f, 15f),
                        new Color(255, 100, 100, 100),
                        new Color(255, 255, 255, 150),
                        projectileArcParams
                );
                
                // 生成第二个电弧（180度对面）
                float distance2 = MathUtils.getRandomNumberInRange(ARC_DISTANCE_MIN, ARC_DISTANCE_MAX);
                Vector2f arcEnd2 = MathUtils.getPointOnCircumference(arcCenter, distance2, oppositeAngle);
                
                engine.spawnEmpArcVisual(
                        arcCenter, proj,   // 起点：偏移后的中心，绑定到弹丸
                        arcEnd2, proj,     // 终点：相对位置，绑定到弹丸
                        MathUtils.getRandomNumberInRange(5f, 15f),
                        new Color(255, 100, 100, 100),
                        new Color(255, 255, 255, 150),
                        projectileArcParams
                );
                
                // 重置计时器为下一个随机间隔
                nextArcTime = MathUtils.getRandomNumberInRange(ARC_INTERVAL_MIN, ARC_INTERVAL_MAX);
            }
            
            projectileArcTimers.put(proj, nextArcTime);
        }

        // 清理已销毁的弹体记录
        List<DamagingProjectileAPI> toRemove = new ArrayList<DamagingProjectileAPI>();
        for (DamagingProjectileAPI proj : projectileArcTimers.keySet()) {
            if (!engine.isEntityInPlay(proj) || proj.isExpired()) {
                toRemove.add(proj);
            }
        }
        for (DamagingProjectileAPI proj : toRemove) {
            projectileArcTimers.remove(proj);
        }
    }
    
    /**
     * 生成开火特效（闪光、烟雾、光晕）
     */
    private void spawnMuzzleEffects(CombatEngineAPI engine, WeaponAPI weapon) {
        Vector2f muzzleLocation = weapon.getFirePoint(0);
        Vector2f shipVelocity = weapon.getShip().getVelocity();
        float weaponAngle = weapon.getCurrAngle();
        
        // 1. 闪光粒子（开火点）- 使用addHitParticle
        engine.addHitParticle(
                muzzleLocation,
                shipVelocity,
                MUZZLE_FLASH_SIZE,
                1.0f,  // brightness
                MUZZLE_FLASH_DURATION,
                MUZZLE_FLASH_COLOR
        );
        
        // 2. 烟雾粒子（左右随机扩散，上下10度随机，速度100-200，尺寸15-30，持续时间1-2秒）
        // 使用原版API的addNebulaSmokeParticle
        for (int i = 0; i < MUZZLE_SMOKE_COUNT; i++) {
            Vector2f smokeLoc = new Vector2f(muzzleLocation);
            smokeLoc.x += MathUtils.getRandomNumberInRange(-5f, 5f);
            smokeLoc.y += MathUtils.getRandomNumberInRange(-5f, 5f);
            
            // 向后180度 + 左右10度随机
            float smokeAngle = weaponAngle + (Math.random() < 0.5f ? -90f : 90f) + MathUtils.getRandomNumberInRange(-MUZZLE_SMOKE_ANGLE_SPREAD, MUZZLE_SMOKE_ANGLE_SPREAD);
            
            // 随机速度 100-200
            float smokeSpeed = MathUtils.getRandomNumberInRange(MUZZLE_SMOKE_SPEED_MIN, MUZZLE_SMOKE_SPEED_MAX);
            
            Vector2f smokeVel = VectorUtils.getDirectionalVector(smokeLoc,
                    MathUtils.getPointOnCircumference(smokeLoc, 10f, smokeAngle));
            smokeVel.scale(smokeSpeed);
            Vector2f.add(shipVelocity, smokeVel, smokeVel);
            
            // 随机尺寸 15-30
            float smokeSize = MathUtils.getRandomNumberInRange(MUZZLE_SMOKE_SIZE_MIN, MUZZLE_SMOKE_SIZE_MAX);
            // 随机持续时间 1-2秒
            float smokeDuration = MathUtils.getRandomNumberInRange(MUZZLE_SMOKE_DURATION_MIN, MUZZLE_SMOKE_DURATION_MAX);
            
            // 使用原版API
            engine.addNebulaSmokeParticle(
                    smokeLoc,
                    smokeVel,
                    smokeSize,
                    MUZZLE_SMOKE_SIZE_MULT,
                    0.1f,  // rampUpFraction
                    0.1f,  // fullBrightnessFraction
                    smokeDuration,
                    MUZZLE_SMOKE_COLOR
            );
        }
        
        // 3. BoxUtil光晕（FlareEntity）- 宽度是高度的10倍
        try {
            FlareEntity flare = new FlareEntity();
            flare.setSize(MUZZLE_FLARE_WIDTH, MUZZLE_FLARE_HEIGHT);
            flare.setSmoothDisc();
            flare.setCoreColor(MUZZLE_FLARE_CORE_COLOR);
            flare.setFringeColor(MUZZLE_FLARE_FRINGE_COLOR);
            flare.setCoreAlpha(1.0f);
            flare.setFringeAlpha(1.0f);
            flare.setAdditiveBlend();
            flare.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
            flare.setGlobalTimer(0.0f, 0.1f, 0.2f); // 淡入0秒，持续0.1秒，淡出0.2秒
            flare.setLocation(muzzleLocation);
            flare.setFacingScale(weaponAngle + 90f, 1f, 1f); // 旋转光晕使其横向对齐武器
            
            CombatRenderingManager.addEntity(flare);
        } catch (Exception e) {
            // BoxUtil不可用时静默失败
        }
        
        // 4. 充能原点闪光和粒子效果
        spawnChargeOriginEffects(engine, weapon);
    }
    
    /**
     * 生成充能原点的闪光和粒子效果
     */
    private void spawnChargeOriginEffects(CombatEngineAPI engine, WeaponAPI weapon) {
        // 计算充能原点位置（与充能电弧的原点相同）
        Vector2f weaponDir = VectorUtils.rotate(new Vector2f(1, 0), weapon.getCurrAngle());
        float originOffsetX = CHARGE_ARC_BASE_OFFSET_X;
        float originOffsetY = CHARGE_ARC_OFFSET_Y;
        Vector2f weaponLoc = new Vector2f(weapon.getLocation());
        Vector2f originPoint = Vector2f.add(weaponLoc,
                new Vector2f(weaponDir.x * originOffsetX - weaponDir.y * originOffsetY,
                        weaponDir.y * originOffsetX + weaponDir.x * originOffsetY), null);
        
        Vector2f shipVelocity = weapon.getShip().getVelocity();
        float weaponAngle = weapon.getCurrAngle();
        
        // 1. 充能原点闪光 - 使用addHitParticle
        engine.addHitParticle(
                originPoint,
                shipVelocity,
                CHARGE_ORIGIN_FLASH_SIZE,
                1.0f,  // brightness
                CHARGE_ORIGIN_FLASH_DURATION,
                CHARGE_ORIGIN_FLASH_COLOR
        );
        
        // 2. 向后喷射粒子（180度范围，即武器朝向的反方向±90度）
        float backwardAngle = weaponAngle + 180f; // 武器反方向
        
        // 使用BoxUtil的SpriteEntity直接创建粒子
        try {
            SpriteEntity particleEntity = new SpriteEntity("graphics/fx/hit_glow.png");
            particleEntity.setBaseSizePerTiles(CHARGE_ORIGIN_PARTICLE_SIZE_MIN, CHARGE_ORIGIN_PARTICLE_SIZE_MIN);
            particleEntity.getMaterialData().setColor(CHARGE_ORIGIN_PARTICLE_COLOR);
            particleEntity.setAdditiveBlend();
            particleEntity.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
            
            List<InstanceDataAPI> particleInstances = new ArrayList<>();
            
            for (int i = 0; i < CHARGE_ORIGIN_PARTICLE_COUNT; i++) {
                // 在180度范围内随机角度（反方向±90度）
                float particleAngle = backwardAngle + MathUtils.getRandomNumberInRange(-90f, 90f);
                
                // 随机速度
                float particleSpeed = MathUtils.getRandomNumberInRange(CHARGE_ORIGIN_PARTICLE_SPEED_MIN, CHARGE_ORIGIN_PARTICLE_SPEED_MAX);
                
                // 计算粒子速度向量
                Vector2f particleVel = MathUtils.getPointOnCircumference(new Vector2f(0, 0), particleSpeed, particleAngle);
                Vector2f.add(shipVelocity, particleVel, particleVel);
                
                // 随机大小
                float particleSize = MathUtils.getRandomNumberInRange(CHARGE_ORIGIN_PARTICLE_SIZE_MIN, CHARGE_ORIGIN_PARTICLE_SIZE_MAX);
                
                // 随机持续时间
                float particleDuration = MathUtils.getRandomNumberInRange(CHARGE_ORIGIN_PARTICLE_DURATION_MIN, CHARGE_ORIGIN_PARTICLE_DURATION_MAX);
                
                Instance2Data instance = new Instance2Data();
                instance.setLocation(originPoint);
                instance.setVelocity(particleVel);
                instance.setScaleAll(particleSize / CHARGE_ORIGIN_PARTICLE_SIZE_MIN);
                instance.setFacing(MathUtils.getRandomNumberInRange(0f, 360f));
                instance.setTimer(0f, 0.15f, particleDuration - 0.15f);
                
                particleInstances.add(instance);
            }
            
            particleEntity.setInstanceData(particleInstances, 0.05f, 0.1f, CHARGE_ORIGIN_PARTICLE_DURATION_MAX);
            particleEntity.setInstanceDataRefreshAllFromCurrentIndex();
            particleEntity.submitInstanceData();
            particleEntity.setRenderingCount((short) CHARGE_ORIGIN_PARTICLE_COUNT);
            particleEntity.setAlwaysRefreshInstanceData(true);
            
            CombatRenderingManager.addEntity(particleEntity);
        } catch (Exception e) {
            // BoxUtil不可用时静默失败
        }
    }

    /**
     * 构建充能阶段使用的新版电弧参数
     * 充能越高，电弧会越亮、越稳定，整体更像能量逐步积蓄的感觉
     */
    private EmpArcParams createChargeArcParams(float chargeLevel) {
        EmpArcParams params = new EmpArcParams();
        chargeLevel = Math.max(0f, Math.min(1f, chargeLevel));

        params.segmentLengthMult = 0.85f + 0.45f * chargeLevel;
        // 分段长度倍率：控制电弧每一小段的平均长度，值越大越接近长折线和直线感。

        params.zigZagReductionFactor = 0.12f + 0.18f * chargeLevel;
        // 锯齿衰减系数：抑制电弧左右乱跳的幅度，数值越大越平滑，越小越躁动。

        params.maxZigZagMult = 0.90f + 0.15f * chargeLevel;
        // 最大锯齿倍率：限制单次偏折的上限，避免短电弧在高亮时拐得过于夸张。

        params.fadeOutDist = 80f;
        // 尾端淡出距离：只有在后续调用 arc.setFadedOutAtStart(true) 时才会明显生效，用来控制淡入/淡出的长度。

        params.minFadeOutMult = 1.25f;
        // 最小淡出倍率：同样主要配合 setFadedOutAtStart(true) 使用，值越小淡出段越长。

        params.flickerRateMult = 0.95f + 0.45f * chargeLevel;
        // 闪烁速率倍率：控制电弧内部 flicker 动画推进速度，充能越高闪烁节奏越快。

        params.glowSizeMult = 0.85f + 0.35f * chargeLevel;
        // 光晕尺寸倍率：控制电弧外围发光的体积，充能越高外层电光越饱满。

        params.glowAlphaMult = 0.80f + 0.60f * chargeLevel;
        // 光晕透明度倍率：控制外层发光亮度，数值越高整体越亮、更有蓄能感。

        params.movementDurOverride = -1f;
        // 移动持续时间强制覆盖：只有在后续调用 arc.setSingleFlickerMode(true) 时才会生效，-1f 表示继续使用引擎自动计算。

        params.movementDurMax = 0.10f;
        // 移动持续时间上限：单次闪烁模式下允许的最长移动时长，当前保留默认上限。

        params.movementDurMin = 0.00f;
        // 移动持续时间下限：单次闪烁模式下允许的最小时长，当前保留默认下限。

        params.brightSpotFullFraction = 0.25f;
        // 亮斑满亮占比：单次闪烁模式下最亮区域完整保持满亮的长度比例。

        params.brightSpotFadeFraction = 0.45f;
        // 亮斑淡出占比：单次闪烁模式下热点从最亮过渡到普通亮度的长度比例。

        params.nonBrightSpotMinBrightness = 0.05f + 0.10f * chargeLevel;
        // 非亮斑最低亮度：即使不是热点区域也至少保留的亮度，避免充能电弧暗到看不见。

        params.glowColorOverride = null;
        // 光晕颜色覆盖：设为 null 时继续使用 spawnEmpArcVisual 传入的颜色，不额外替换发光颜色。

        params.flamesOutMissiles = true;
        // 导弹熄火开关：这个字段主要给带伤害的 EMP 电弧用，纯视觉电弧里基本不会触发，这里显式保留方便以后迁移。

        return params;
    }

    /**
     * 构建弹丸飞行阶段使用的新版电弧参数
     * 这组参数保留更多跳跃感和放电感，让弹丸周围的电弧看起来更像持续泄放
     */
    private EmpArcParams createProjectileArcParams() {
        EmpArcParams params = new EmpArcParams();

        params.segmentLengthMult = 0.75f;
        // 分段长度倍率：略微拉长每一段折线，让弹丸周围的电弧看起来更有“撕裂出去”的感觉。

        params.zigZagReductionFactor = 0.10f;
        // 锯齿衰减系数：只做轻度平滑，保留飞行电弧应有的躁动和不稳定感。

        params.maxZigZagMult = 0.9f;
        // 最大锯齿倍率：允许弹丸周围电弧保留稍强一点的拐折幅度，增强放电张力。

        params.fadeOutDist = 50f;
        // 尾端淡出距离：只有在后续调用 arc.setFadedOutAtStart(true) 时才会明显生效，这里先把范围预留出来。

        params.minFadeOutMult = 1.50f;
        // 最小淡出倍率：配合淡出模式时控制收尾长度，较低数值会让电弧头尾显得更柔一些。

        params.flickerRateMult = 1.25f;
        // 闪烁速率倍率：让弹丸周围的电弧刷新得更快，视觉上更像高频放电。

        params.glowSizeMult = 0.50f;
        // 光晕尺寸倍率：略微压缩外层光晕，避免弹丸附近的电弧糊成一团白光。

        params.glowAlphaMult = 0.90f;
        // 光晕透明度倍率：略微压低整体发光强度，让电弧主体纹理更容易看清。

        params.movementDurOverride = -1f;
        // 移动持续时间强制覆盖：只在单次闪烁模式下生效，-1f 表示继续让引擎自动决定动画时长。

        params.movementDurMax = 0.10f;
        // 移动持续时间上限：单次闪烁模式下的最长移动时长，当前保持默认值。

        params.movementDurMin = 0.00f;
        // 移动持续时间下限：单次闪烁模式下的最短移动时长，当前保持默认值。

        params.brightSpotFullFraction = 0.25f;
        // 亮斑满亮占比：单次闪烁模式下最亮热点完整存在的比例，保持接近默认视觉节奏。

        params.brightSpotFadeFraction = 0.50f;
        // 亮斑淡出占比：单次闪烁模式下热点向普通亮度过渡的比例，略增一点让衰减更顺。

        params.nonBrightSpotMinBrightness = 0.05f;
        // 非亮斑最低亮度：给非热点区域留一点基础亮度，避免短电弧忽明忽暗得太突兀。

        params.glowColorOverride = null;
        // 光晕颜色覆盖：保持为 null，继续沿用调用时传入的红白电弧配色。

        params.flamesOutMissiles = true;
        // 导弹熄火开关：这项主要给带伤害的 EMP 电弧准备，纯视觉电弧中写出来是为了把新版字段完整展开。

        return params;
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (projectile == null || weapon == null || engine == null) return;
        
        // 初始化弹体的电弧计时器
        projectileArcTimers.put(projectile, MathUtils.getRandomNumberInRange(ARC_INTERVAL_MIN, ARC_INTERVAL_MAX));
        
        // 生成开火特效（每次开火都触发）
        spawnMuzzleEffects(engine, weapon);
        
        // 应用开火后坐力
        applyRecoilMomentum(weapon);
    }
    
    /**
     * 颜色插值辅助方法
     * @param start 起始颜色
     * @param end 结束颜色
     * @param t 插值参数 (0.0 到 1.0)
     * @return 插值后的颜色
     */
    private Color interpolateColor(Color start, Color end, float t) {
        t = Math.max(0f, Math.min(1f, t)); // 限制在 0-1 范围
        
        int r = (int) (start.getRed() + (end.getRed() - start.getRed()) * t);
        int g = (int) (start.getGreen() + (end.getGreen() - start.getGreen()) * t);
        int b = (int) (start.getBlue() + (end.getBlue() - start.getBlue()) * t);
        int a = (int) (start.getAlpha() + (end.getAlpha() - start.getAlpha()) * t);
        
        return new Color(r, g, b, a);
    }
    
    /**
     * 应用开火后坐力
     * 根据武器朝向的反方向施加推力到舰船
     * @param weapon 开火的武器
     */
    private void applyRecoilMomentum(WeaponAPI weapon) {
        if (weapon == null || weapon.getShip() == null) return;
        
        ShipAPI ship = weapon.getShip();
        
        // 计算后坐力方向（武器朝向的反方向）
        float weaponAngle = weapon.getCurrAngle();
        float recoilAngle = weaponAngle + 180f; // 反方向
        
        // 计算后坐力向量
        Vector2f recoilDirection = MathUtils.getPointOnCircumference(new Vector2f(0, 0), 1f, recoilAngle);
        
        // 应用动量
        float momentum = RECOIL_MOMENTUM * RECOIL_MOMENTUM_MULTIPLIER;
        float mass = Math.max(1f, ship.getMass());
        
        // 计算速度变化
        Vector2f velocityChange = new Vector2f(recoilDirection);
        velocityChange.scale(momentum / mass);
        
        // 应用到舰船速度
        Vector2f.add(ship.getVelocity(), velocityChange, ship.getVelocity());
    }
}
