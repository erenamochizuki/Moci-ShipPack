package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.boxutil.base.SimpleParticleControlData;
import org.boxutil.define.BoxDatabase;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.attribute.Instance2Data;
import org.boxutil.units.standard.entity.DistortionEntity;
import org.boxutil.units.standard.entity.FlareEntity;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.boxutil.units.standard.entity.TrailEntity;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 光束终点光晕和扭曲效果
 * 
 * 功能：
 * - 在光束终点周期性创建光晕效果（参数与Moci_ConvergenceChargeEffect的最终闪烁光晕相同）
 * - 在光束终点持续产生扭曲效果
 * - 光束消失后效果自动淡出（通过定时器控制）
 */
public class Moci_WaveMotionGunBeamEffect implements BeamEffectPlugin {
    
    // ==================== 光晕参数 ====================
    // FlareEntity.setSize(width, height) - 光晕的宽度和高度（像素）
    private static final float FLARE_SIZE = 50f;
    // 宽高比
    private static final float FLARE_ASPECT_RATIO = 6f;
    
    // FlareEntity.setFringeColor(Color) - 光晕边缘颜色
    private static final Color FLARE_COLOR_FINAL_BLUE = new Color(100, 150, 255, 255);  // 蓝色
    
    // FlareEntity.setFringeAlpha(float) - 边缘透明度

    private static final float FLARE_ALPHA = 1f;  //
    // 光晕核心颜色
    private static final Color FLARE_COLOR_CORE = new Color(195, 224, 255, 255);  // 蓝色
    
    // FlareEntity.setCoreAlpha(float) - 核心透明度
    private static final float FLARE_CORE_ALPHA = 1f;  //
    
    // FlareEntity.setFlickMixValue(float) - 闪烁混合值
    private static final float FLARE_FLICK_MIX = 0.8f;  // 0.8 = 强烈闪烁效果
    
    // FlareEntity.setNoisePower(float) - 噪声纹理强度
    private static final float FLARE_NOISE_POWER = 0.33f;  // 0.33 = 中等噪声，产生不规则明暗变化
    
    // FlareEntity.setGlowPower(float) - 发光强度 
    private static final float FLARE_GLOW_POWER = 5.0f;  // 2.0 = 双倍发光强度
    
    // FlareEntity.setGlobalTimer(fadeIn, duration, fadeOut) - 生命周期（秒）
    private static final float FLARE_FADE_IN = 0.0f;      // 淡入时间：0秒（立即出现）
    private static final float FLARE_DURATION = 0.8f;     // 持续时间：1秒
    private static final float FLARE_FADE_OUT = 0.4f;     // 淡出时间：0.5秒
    
    // ==================== 扭曲参数 ====================
    // DistortionEntity.setSizeIn/Full/Out(width, height) - 扭曲尺寸
    private static final float DISTORTION_SIZE = 100f;
    
    // DistortionEntity.setInnerFull(fullRatio, minRatio) - 内圈大小比例
    private static final float DISTORTION_INNER_FULL = 0.8f;
    private static final float DISTORTION_INNER_MIN = 0.8f;
    
    // DistortionEntity.setInnerHardness(float) - 内圈边缘硬度
    private static final float DISTORTION_INNER_HARDNESS = 0.5f;  // 0.8 = 较锐利的内圈边缘
    
    // DistortionEntity.setPowerIn/Full/Out(float) - 扭曲强度
    private static final float DISTORTION_POWER = 1.0f;  // 1.0 = 标准扭曲强度
    
    // DistortionEntity.setGlobalTimer(fadeIn, duration, fadeOut) - 生命周期（秒）
    private static final float DISTORTION_FADE_IN = 0.1f;   // 淡入时间：0.1秒（从无到有）
    private static final float DISTORTION_DURATION = 0f;    // 持续时间：0秒（直接进入淡出）
    private static final float DISTORTION_FADE_OUT = 0.5f;  // 淡出时间：0.5秒（扩散并消失）
    // 总生命周期 = 0.1 + 0 + 0.5 = 0.6秒
    
    // 扭曲创建间隔（秒）
    private static final float DISTORTION_SPAWN_INTERVAL = 0.25f;  // 每0.25秒创建一个新扭曲
    
    // ==================== 闪光粒子参数 ====================
    // Global.getCombatEngine().addHitParticle() - 击中粒子效果
    private static final float PARTICLE_SIZE = 512;           // 粒子大小
    private static final float PARTICLE_BRIGHTNESS = 20.0f;    // 粒子亮度
    private static final float PARTICLE_DURATION = 0.5f;      // 粒子持续时间
    private static final Color PARTICLE_COLOR = new Color(150, 200, 255, 255);  // 粒子颜色（蓝白色）
    
    // ==================== AOE伤害参数 ====================
    // 闪光粒子附带的AOE伤害
    private static final float AOE_DAMAGE_MULT = 0.5f;        // 伤害倍率（光束DPS的50%）
    private static final float AOE_CORE_RADIUS_MULT = 0.5f;   // 核心伤害半径倍率（总半径的50%）
    private static final DamageType AOE_DAMAGE_TYPE = DamageType.HIGH_EXPLOSIVE;  // 伤害类型：爆炸
    
    // ==================== 力场吸引参数 ====================
    // 对光束周围敌人施加的吸引效果
    private static final float FORCE_FIELD_RANGE = 500f;      // 力场作用范围
    private static final float FORCE_FIELD_STRENGTH = 300f;   // 力场吸引强度
    private static final float FORCE_FIELD_ENDPOINT_RANGE = 500f;  // 终点额外圆形范围
    
    // ==================== 飞溅粒子参数 ====================
    // 每次击中目标生成的飞溅粒子数量
    private static final int SPLASH_PARTICLE_COUNT = 24;
    // 飞溅角度范围（度）- 180度扇形（指向爆炸中心）
    private static final float SPLASH_ANGLE_SPREAD = 120.0f;
    // 飞溅速度范围（像素/秒）
    private static final float SPLASH_SPEED_MIN = 200.0f;
    private static final float SPLASH_SPEED_MAX = 300.0f;
    // 飞溅粒子尺寸范围（像素）
    private static final float SPLASH_SIZE_MIN = 6.0f;
    private static final float SPLASH_SIZE_MAX = 18.0f;
    // 飞溅粒子持续时间（秒）
    private static final float SPLASH_DURATION_MIN = 0.4f;
    private static final float SPLASH_DURATION_MAX = 0.6f;
    // 飞溅粒子淡出时间（秒）
    private static final float SPLASH_FADE_OUT = 0.3f;
    // 飞溅粒子颜色（起始和结束）
    private static final Color SPLASH_COLOR_START = new Color(150, 200, 255, 255);  // 蓝白色（起始）
    private static final Color SPLASH_COLOR_END = new Color(100, 150, 255, 255);    // 蓝色（结束）
    
    // ==================== 位置偏移参数 ====================
    // 所有特效向光束方向偏移的距离（像素）
    // 正值：向光束终点方向偏移（远离光束起点）
    // 负值：向光束起点方向偏移（远离光束终点）
    private static final float EFFECT_OFFSET = 64f;  // 向终点方向偏移64像素
    
    // ==================== 光束路径粒子参数 ====================
    // 粒子尺寸范围（像素）
    private static final float PATH_PARTICLE_SIZE_MIN = 2f;           // 最小粒子尺寸
    private static final float PATH_PARTICLE_SIZE_MAX = 8f;          // 最大粒子尺寸
    
    // 粒子持续时间范围（秒）
    private static final float PATH_PARTICLE_DURATION_MIN = 0.3f;     // 最短持续时间
    private static final float PATH_PARTICLE_DURATION_MAX = 0.6f;     // 最长持续时间
    
    // 粒子漂移速度（像素/秒）- 粒子随机漂移的速度范围
    private static final float PATH_PARTICLE_DRIFT = 30f;
    
    // 粒子密度系数 - 控制每帧生成的粒子数量，值越大粒子越密集
    private static final float PATH_PARTICLE_DENSITY = 0.02f;
    
    // 粒子生成宽度倍率 - 粒子在光束宽度方向上的偏移范围倍率
    private static final float PATH_PARTICLE_SPAWN_WIDTH_MULT = 0.6f;
    
    // 粒子生成概率 - 每帧有80%概率生成粒子，用于控制整体密度
    private static final float PATH_PARTICLE_SPAWN_CHANCE = 0.8f;
    
    // 粒子生成范围相对光束宽度的倍率 - 调整此值以匹配光束粗细（推荐0.5-1.5）
    private static final float PATH_PARTICLE_BEAM_WIDTH_MULT = 0.7f;
    
    // ==================== 终点喷射粒子参数 ====================
    // 粒子喷射距离范围（像素）- 粒子从终点向外飞行的距离
    private static final float SPRAY_PARTICLE_DISTANCE_MIN = 50f;     // 最小喷射距离
    private static final float SPRAY_PARTICLE_DISTANCE_MAX = 200f;    // 最大喷射距离
    
    // 粒子持续时间（秒）
    private static final float SPRAY_PARTICLE_DURATION = 0.5f;
    
    // 粒子颜色（蓝白色）
    private static final Color SPRAY_PARTICLE_COLOR = new Color(150, 200, 255, 255);
    
    // 粒子尺寸范围（像素）
    private static final float SPRAY_PARTICLE_SIZE_MIN = 6f;          // 最小粒子尺寸
    private static final float SPRAY_PARTICLE_SIZE_MAX = 14f;         // 最大粒子尺寸
    
    // ==================== 终点喷射光线参数 ====================
    // 光线喷射距离范围（像素）- 光线从终点向外延伸的距离
    private static final float SPRAY_TRAIL_DISTANCE_MIN = 80f;        // 最小喷射距离
    private static final float SPRAY_TRAIL_DISTANCE_MAX = 300f;       // 最大喷射距离
    
    // 光线持续时间（秒）- 光线从出现到完全淡出的时间
    private static final float SPRAY_TRAIL_DURATION = 0.7f;
    
    // 光线颜色
    private static final Color SPRAY_TRAIL_COLOR_CORE = new Color(200, 220, 255, 255);    // 核心颜色（发光）
    private static final Color SPRAY_TRAIL_COLOR_START = new Color(180, 200, 255, 150);   // 起始颜色
    private static final Color SPRAY_TRAIL_COLOR_END = new Color(150, 180, 255, 100);     // 结束颜色
    
    // 光线宽度（像素）
    private static final float SPRAY_TRAIL_WIDTH_START = 6.0f;        // 起始宽度
    private static final float SPRAY_TRAIL_WIDTH_END = 2.0f;          // 结束宽度
    
    // ==================== 状态变量 ====================
    private float effectTimer = 0f;
    private int distortionCount = 0;
    private FlareEntity currentFlare = null;
    private final List<DistortionEntity> activeDistortions = new ArrayList<>();
    private final List<TrailEntityWrapper> activeSprayTrails = new ArrayList<>();
    private boolean initialized = false;  // 是否已初始化（用于首次立即生成效果）
    private boolean sprayEffectsEnabled = false;  // 是否启用喷射效果（第一个光晕后启用）
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine == null || beam == null) return;
        if (engine.isPaused()) return;
        
        Vector2f endpoint = beam.getTo();
        if (endpoint == null) return;
        
        WeaponAPI weapon = beam.getWeapon();
        if (weapon == null) return;
        
        float beamFacing = weapon.getCurrAngle();
        
        // 计算偏移后的位置
        Vector2f offsetEndpoint = calculateOffsetPosition(endpoint, beamFacing);
        
        // 首次运行时立即生成所有效果
        if (!initialized) {
            createFlareEntity(offsetEndpoint, beamFacing);
            createDistortionEffect(offsetEndpoint);
            createHitParticle(engine, offsetEndpoint, beam);
            initialized = true;
            effectTimer = 0f;
            distortionCount = 0;
            return;  // 首次生成后直接返回，下一帧开始正常计时
        }
        
        // 更新现有实体的位置
        updateEntityPositions(offsetEndpoint, beamFacing);
        
        // 更新喷射光线
        updateSprayTrailEntities(amount);
        
        // 统一计时器管理
        effectTimer += amount;
        
        // 每0.25秒创建扭曲
        if (effectTimer >= DISTORTION_SPAWN_INTERVAL) {
            createDistortionEffect(offsetEndpoint);
            distortionCount++;
            
            // 每2个扭曲（0.5秒）创建闪光粒子
            if (distortionCount % 2 == 0) {
                createHitParticle(engine, offsetEndpoint, beam);
            }
            
            // 每4个扭曲（1秒）创建光晕
            if (distortionCount >= 4) {
                createFlareEntity(offsetEndpoint, beamFacing);
                distortionCount = 0;
                
                // 第一个光晕生成后，启用喷射效果
                if (!sprayEffectsEnabled) {
                    sprayEffectsEnabled = true;
                }
            }
            
            effectTimer -= DISTORTION_SPAWN_INTERVAL;
        }
        
        // 生成光束路径粒子
        generatePathParticles(engine, beam, weapon, amount);
        
        // 只有在启用喷射效果后才生成终点喷射粒子和光线
        if (sprayEffectsEnabled) {
            generateSprayParticles(engine, offsetEndpoint, beamFacing, weapon.getShip().getVelocity());
            generateSprayTrails(engine, offsetEndpoint, beamFacing, weapon.getShip().getVelocity());
        }
    }
    
    /**
     * 计算偏移后的位置
     * 根据光束朝向和偏移距离，计算特效应该出现的位置
     */
    private Vector2f calculateOffsetPosition(Vector2f endpoint, float beamFacing) {
        // 将角度转换为弧度
        float angleRad = (float) Math.toRadians(beamFacing);
        
        // 计算偏移向量（沿光束方向）
        float offsetX = (float) Math.cos(angleRad) * EFFECT_OFFSET;
        float offsetY = (float) Math.sin(angleRad) * EFFECT_OFFSET;
        
        // 返回偏移后的位置
        return new Vector2f(endpoint.x + offsetX, endpoint.y + offsetY);
    }
    
    private void updateEntityPositions(Vector2f endpoint, float beamFacing) {
        if (currentFlare != null) {
            if (currentFlare.hasDelete()) {
                currentFlare = null;
            } else {
                currentFlare.setLocation(endpoint);
                currentFlare.setFacingScale(MathUtils.clampAngle(beamFacing + 90f), 1f, 1f);
            }
        }
        
        Iterator<DistortionEntity> iterator = activeDistortions.iterator();
        while (iterator.hasNext()) {
            DistortionEntity distortion = iterator.next();
            if (distortion.hasDelete()) {
                iterator.remove();
            } else {
                distortion.setLocation(endpoint);
            }
        }
    }
    
    private void createFlareEntity(Vector2f location, float facing) {
            FlareEntity flareEntity = new FlareEntity();
            currentFlare = flareEntity;
            
            flareEntity.setLocation(location);
            flareEntity.setFacingScale(MathUtils.clampAngle(facing + 90f), 1f, 1f);
            flareEntity.setSize(FLARE_SIZE * FLARE_ASPECT_RATIO, FLARE_SIZE);
            flareEntity.setSmoothDisc();
            flareEntity.setFringeColor(FLARE_COLOR_FINAL_BLUE);
            flareEntity.setCoreColor(FLARE_COLOR_CORE);
            flareEntity.setCoreAlpha(FLARE_CORE_ALPHA);
            flareEntity.setFringeAlpha(FLARE_ALPHA);
            flareEntity.setAdditiveBlend();
            flareEntity.setFlick(true);
            flareEntity.setFlickWhenPaused(false);
            flareEntity.setFlickMixValue(FLARE_FLICK_MIX);
            flareEntity.setNoisePower(FLARE_NOISE_POWER);
            flareEntity.setGlowPower(FLARE_GLOW_POWER);
            flareEntity.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
            flareEntity.setGlobalTimer(FLARE_FADE_IN, FLARE_DURATION, FLARE_FADE_OUT);
            
            CombatRenderingManager.addEntity(flareEntity);

    }
    
    private void createDistortionEffect(Vector2f location) {
            DistortionEntity distortion = new DistortionEntity();
            activeDistortions.add(distortion);
            
            distortion.setGlobalTimer(DISTORTION_FADE_IN, DISTORTION_DURATION, DISTORTION_FADE_OUT);
            distortion.setInnerFull(DISTORTION_INNER_FULL, DISTORTION_INNER_MIN);
            distortion.setInnerHardness(DISTORTION_INNER_HARDNESS);
            distortion.setRingHardness(0.5f);
            distortion.setSizeIn(0f, 0f);
            distortion.setSizeFull(DISTORTION_SIZE, DISTORTION_SIZE);
            distortion.setSizeOut(DISTORTION_SIZE * 2.0f, DISTORTION_SIZE * 2.0f);
            distortion.setPowerIn(0);
            distortion.setPowerFull(DISTORTION_POWER);
            distortion.setPowerOut(0);
            distortion.setLocation(location);
            
            CombatRenderingManager.addEntity(distortion);

    }
    
    /**
     * 创建击中粒子效果
     * 使用 Global.getCombatEngine().addHitParticle() 创建闪光粒子
     * 同时造成AOE伤害
     */
    private void createHitParticle(CombatEngineAPI engine, Vector2f location, BeamAPI beam) {
            // addHitParticle(位置, 速度, 大小, 亮度, 持续时间, 颜色)
            engine.addHitParticle(
                location,                    // 位置
                new Vector2f(0, 0),         // 速度（静止）
                PARTICLE_SIZE,              // 大小
                PARTICLE_BRIGHTNESS,        // 亮度
                PARTICLE_DURATION,          // 持续时间
                PARTICLE_COLOR              // 颜色
            );
            
            // 造成AOE伤害
            createAOEDamage(engine, location, beam);
    }
    
    /**
     * 创建AOE伤害
     * 使用原版的 DamagingExplosionSpec API 来创建爆炸伤害
     * 这样可以自动处理击中点计算、伤害衰减、护盾交互等复杂逻辑
     */
    private void createAOEDamage(CombatEngineAPI engine, Vector2f location, BeamAPI beam) {
        WeaponAPI weapon = beam.getWeapon();
        if (weapon == null) return;
        
        ShipAPI sourceShip = weapon.getShip();
        if (sourceShip == null) return;
        
        // 计算伤害：光束每秒伤害 * 0.5
        float beamDPS = weapon.getDerivedStats().getDps();
        float aoeDamage = beamDPS * AOE_DAMAGE_MULT;
        
        // AOE范围等于闪光粒子大小
        float aoeRadius = PARTICLE_SIZE;
        
        // 核心伤害半径
        float coreRadius = aoeRadius * AOE_CORE_RADIUS_MULT;
        
        // EMP伤害等于主伤害
        float empDamage = aoeDamage;
        
        // 创建爆炸规格
        // DamagingExplosionSpec(持续时间, 外圈半径, 核心半径, 最大伤害, 最大EMP伤害, 
        //                      碰撞类型1, 碰撞类型2, 粒子最小尺寸, 粒子尺寸范围, 粒子持续时间, 
        //                      粒子数量, 粒子颜色, 核心颜色)
        com.fs.starfarer.api.loading.DamagingExplosionSpec explosionSpec = 
            new com.fs.starfarer.api.loading.DamagingExplosionSpec(
                0.1f,                                    // 持续时间（秒）
                aoeRadius,                               // 外圈半径
                coreRadius,                              // 核心半径（核心范围内100%伤害）
                aoeDamage,                               // 最大伤害
                empDamage,                               // 最大EMP伤害
                CollisionClass.PROJECTILE_FF,            // 碰撞类型1（友军伤害）
                CollisionClass.PROJECTILE_NO_FF,         // 碰撞类型2（敌军伤害）
                SPLASH_SIZE_MIN,                         // 粒子最小尺寸
                SPLASH_SIZE_MAX - SPLASH_SIZE_MIN,       // 粒子尺寸范围
                SPLASH_DURATION_MAX,                     // 粒子持续时间
                SPLASH_PARTICLE_COUNT,                   // 粒子数量
                SPLASH_COLOR_START,                      // 粒子颜色
                SPLASH_COLOR_END                         // 核心颜色
            );
        
        // 设置伤害类型
        explosionSpec.setDamageType(AOE_DAMAGE_TYPE);
        
        // 不显示默认的爆炸图形（我们已经有自己的视觉效果）
        explosionSpec.setShowGraphic(false);
        
        // 生成爆炸（引擎会自动处理所有的碰撞检测、伤害计算、击中点等）
        engine.spawnDamagingExplosion(explosionSpec, sourceShip, location, false);
        
        // 应用力场推力效果
        applyForceFieldPush(engine, beam, location, sourceShip);
    }
    
    /**
     * 应用力场吸引效果
     * 对整条光束周围500范围内的敌人施加指向光束的吸引力
     * 同时在终点额外添加500范围的圆形吸引区域
     * 
     * 来源：Global.getCombatEngine().getAllObjectGrid().getCheckIterator()
     * 参考：骤雨/src/data/scripts/weapons/armaa_magnaBurstEffect.java:197-199
     * 
     * @param engine 战斗引擎
     * @param beam 光束对象
     * @param explosionCenter 爆炸中心位置（光束终点+偏移）
     * @param sourceShip 源舰船
     */
    private void applyForceFieldPush(CombatEngineAPI engine, BeamAPI beam, Vector2f explosionCenter, ShipAPI sourceShip) {
        // 计算光束方向向量（从起点到终点）
        Vector2f beamStart = beam.getFrom();
        Vector2f beamEnd = beam.getTo();
        Vector2f beamDirection = new Vector2f(
            beamEnd.x - beamStart.x,
            beamEnd.y - beamStart.y
        );
        float beamLength = beamDirection.length();
        if (beamLength == 0) return;
        
        // 归一化光束方向
        beamDirection.scale(1.0f / beamLength);
        
        // 计算光束中点（用于grid遍历的中心）
        Vector2f beamCenter = new Vector2f(
            (beamStart.x + beamEnd.x) * 0.5f,
            (beamStart.y + beamEnd.y) * 0.5f
        );
        
        // 计算grid遍历范围（覆盖整条光束 + 两侧500范围 + 终点额外500范围）
        float gridWidth = beamLength + FORCE_FIELD_RANGE * 2f + FORCE_FIELD_ENDPOINT_RANGE;
        float gridHeight = FORCE_FIELD_RANGE * 2f + FORCE_FIELD_ENDPOINT_RANGE;
        
        // 使用 grid 遍历获取范围内的所有对象
        // 以光束中点为中心，创建一个覆盖整条光束的矩形区域
        Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
            beamCenter, 
            gridWidth,
            gridHeight
        );
        
        // 遍历范围内的所有对象
        while (iter.hasNext()) {
            Object obj = iter.next();
            
            // 只处理舰船
            if (!(obj instanceof ShipAPI)) continue;
            
            ShipAPI target = (ShipAPI) obj;
            
            // 基本检查
            if (target == null || !target.isAlive()) continue;
            if (target == sourceShip) continue;  // 跳过源舰船
            
            // 只对敌对舰船施加吸引力
            if (target.getOwner() == sourceShip.getOwner()) continue;
            
            // 计算目标在光束上的投影点
            Vector2f toTarget = new Vector2f(
                target.getLocation().x - beamStart.x,
                target.getLocation().y - beamStart.y
            );
            
            // 计算投影长度（点积）
            float projectionLength = Vector2f.dot(toTarget, beamDirection);
            
            // 限制投影点在光束范围内
            projectionLength = Math.max(0, Math.min(projectionLength, beamLength));
            
            // 计算投影点位置
            Vector2f projectionPoint = new Vector2f(
                beamStart.x + beamDirection.x * projectionLength,
                beamStart.y + beamDirection.y * projectionLength
            );
            
            // 计算目标到投影点的距离（垂直距离）
            float distanceToBeam = MathUtils.getDistance(target.getLocation(), projectionPoint);
            
            // 计算目标到终点的距离
            float distanceToEndpoint = MathUtils.getDistance(target.getLocation(), explosionCenter);
            
            // 判断是否在影响范围内：
            // 1. 距离光束500范围内（长方形区域）
            // 2. 或者距离终点500范围内（圆形区域）
            boolean inBeamRange = distanceToBeam <= FORCE_FIELD_RANGE;
            boolean inEndpointRange = distanceToEndpoint <= FORCE_FIELD_ENDPOINT_RANGE;
            
            if (!inBeamRange && !inEndpointRange) continue;
            
            // 计算从目标指向投影点的方向（吸引向光束）
            Vector2f pullDirection = new Vector2f(
                projectionPoint.x - target.getLocation().x,
                projectionPoint.y - target.getLocation().y
            );
            
            float pullDistance = pullDirection.length();
            if (pullDistance == 0) continue;
            
            // 归一化吸引方向
            pullDirection.scale(1.0f / pullDistance);
            
            // 应用吸引力（固定强度，不受质量影响）
            applyPushForce(target, pullDirection, FORCE_FIELD_STRENGTH);
        }
    }
    
    /**
     * 应用推力到目标舰船
     * 
     * @param target 目标舰船
     * @param direction 推力方向（已归一化）
     * @param forceMagnitude 推力大小
     */
    private void applyPushForce(ShipAPI target, Vector2f direction, float forceMagnitude) {
        if (target == null || direction.lengthSquared() == 0) return;
        
        // 获取实际要施加效果的实体（处理模块化舰船）
        CombatEntityAPI targetEntity = target;
        
        if (target.isStationModule()) {
            ShipAPI parent = target.getParentStation();
            if (parent != null) {
                targetEntity = parent;  // 对母舰施加效果
            }
        }
        
        // 动量放大系数（与 ImprovedWarpBeamEffect 一致）
        forceMagnitude *= 100f;
        
        // 计算推力向量
        Vector2f forceVector = new Vector2f(direction);
        forceVector.scale(forceMagnitude);
        
        // 直接应用速度变化（不除以质量，所有舰船受到相同的速度变化）
        Vector2f velocityChange = new Vector2f(forceVector);
        
        // 计算施加后的预期速度
        Vector2f currentVelocity = new Vector2f(targetEntity.getVelocity());
        Vector2f predictedVelocity = Vector2f.add(currentVelocity, velocityChange, null);
        float predictedSpeed = predictedVelocity.length();
        
        // 速度限制检查：如果预期速度会超过最大速度的4倍，按比例缩减
        float maxSpeed = target.getMaxSpeed();
        float speedLimit = maxSpeed * 4.0f;
        
        if (predictedSpeed > speedLimit) {
            float currentSpeed = currentVelocity.length();
            float maxAllowedSpeedIncrease = speedLimit - currentSpeed;
            float requestedSpeedIncrease = velocityChange.length();
            
            if (maxAllowedSpeedIncrease > 0 && requestedSpeedIncrease > 0) {
                float reductionFactor = maxAllowedSpeedIncrease / requestedSpeedIncrease;
                velocityChange.scale(reductionFactor);
            } else {
                // 当前速度已达到或超过限制，不施加推力
                return;
            }
        }
        
        // 应用速度变化
        Vector2f.add(targetEntity.getVelocity(), velocityChange, targetEntity.getVelocity());
    }
    
    /**
     * 生成飞溅粒子效果（180度扇形，指向爆炸中心）
     * 注意：现在AOE伤害使用原版爆炸API，会自动生成粒子
     * 这个方法保留用于其他需要手动生成飞溅粒子的场景
     * @param hitLocation 击中位置（目标位置）
     * @param explosionCenter 爆炸中心位置
     * @param damageMult 伤害倍率（用于调整粒子数量和大小）
     */
    @SuppressWarnings("unused")
    private void spawnSplashParticles(Vector2f hitLocation, Vector2f explosionCenter, float damageMult) {
        // 获取粒子控制器
        SimpleParticleControlData controller = ParticleControllerManager.getController();
        if (controller == null) {
            return;
        }
        
        // 计算从击中点指向爆炸中心的方向
        Vector2f splashDir = new Vector2f(
            explosionCenter.x - hitLocation.x,
            explosionCenter.y - hitLocation.y
        );
        float length = splashDir.length();
        if (length > 0) {
            splashDir.scale(1.0f / length);  // 归一化
        } else {
            // 如果击中点就是爆炸中心，随机一个方向
            float angle = (float) (Math.random() * Math.PI * 2.0);
            splashDir.x = (float) Math.cos(angle);
            splashDir.y = (float) Math.sin(angle);
        }
        
        // 计算基础角度（指向爆炸中心）
        float baseAngle = (float) Math.atan2(splashDir.y, splashDir.x);
        
        // 根据伤害倍率调整粒子数量（核心范围100%，外围50%）
        int particleCount = (int) (SPLASH_PARTICLE_COUNT * (damageMult > 0.99f ? 1.0f : 0.5f));
        
        // 根据伤害倍率调整粒子尺寸
        float sizeMult = damageMult > 0.99f ? 1.0f : 0.5f;
        
        // 生成飞溅粒子（180度扇形）
        for (int i = 0; i < particleCount; i++) {
            // 添加粒子 - 带null检查
            Instance2Data particle = controller.addParticle();
            if (particle == null) {
                continue;  // 粒子池已满，跳过
            }
            
            // 计算飞溅角度（180度扇形，以指向爆炸中心的方向为中心）
            float angleOffset = (i - particleCount * 0.5f) / particleCount * (float) Math.toRadians(SPLASH_ANGLE_SPREAD);
            float angle = baseAngle + angleOffset;
            
            // 随机速度
            float speed = MathUtils.getRandomNumberInRange(SPLASH_SPEED_MIN, SPLASH_SPEED_MAX);
            Vector2f velocity = new Vector2f(
                (float) Math.cos(angle) * speed,
                (float) Math.sin(angle) * speed
            );
            
            // 设置粒子属性
            particle.setLocation(hitLocation);
            particle.setVelocity(velocity);
            
            // 粒子朝向速度方向
            float facingAngle = (float) Math.toDegrees(angle);
            particle.setFacing(facingAngle);
            particle.setTurnRate(0.0f);
            
            // 随机尺寸（根据伤害倍率调整）
            float size = MathUtils.getRandomNumberInRange(SPLASH_SIZE_MIN, SPLASH_SIZE_MAX) * sizeMult;
            particle.setScale(size, size);
            particle.setScaleRate(0.5f, 0.5f);  // 逐渐缩小
            
            // 颜色渐变（从蓝白色到蓝色）
            particle.setLowColor(SPLASH_COLOR_START);
            particle.setHighColor(SPLASH_COLOR_END);
            
            // 发光颜色也设置渐变
            particle.setLowEmissiveColor(SPLASH_COLOR_START);
            particle.setHighEmissiveColor(SPLASH_COLOR_END);
            
            // 透明度
            particle.setAlpha(0.8f + (float) Math.random() * 0.2f);
            
            // 生命周期
            particle.setTimer(
                0.0f,
                MathUtils.getRandomNumberInRange(SPLASH_DURATION_MIN, SPLASH_DURATION_MAX),
                SPLASH_FADE_OUT
            );
        }
    }
    
    /**
     * 生成光束路径粒子效果
     */
    private void generatePathParticles(CombatEngineAPI engine, BeamAPI beam, WeaponAPI weapon, float amount) {
        float beamWidth = beam.getWidth() * PATH_PARTICLE_BEAM_WIDTH_MULT;  // 应用宽度倍率
        
        // 计算本帧应生成的粒子数量
        float beamLength = MathUtils.getDistance(beam.getTo(), beam.getFrom());
        float particleCount = beamWidth * PATH_PARTICLE_SPAWN_WIDTH_MULT 
            * beamLength * amount * PATH_PARTICLE_DENSITY;

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
            if (!engine.getViewport().isNearViewport(spawnPoint, PATH_PARTICLE_SIZE_MAX * 3f)) {
                continue;
            }

            // 计算速度（保留部分舰船速度）
            Vector2f velocity = new Vector2f(
                weapon.getShip().getVelocity().x * 0.3f,
                weapon.getShip().getVelocity().y * 0.3f
            );
            velocity = MathUtils.getRandomPointInCircle(velocity, PATH_PARTICLE_DRIFT);

            // 生成粒子 - 使用蓝白色
            Color particleColor = new Color(150, 200, 255, 200);
            
            engine.addSmoothParticle(
                spawnPoint,
                velocity,
                MathUtils.getRandomNumberInRange(PATH_PARTICLE_SIZE_MIN, PATH_PARTICLE_SIZE_MAX),
                1.0f,
                MathUtils.getRandomNumberInRange(PATH_PARTICLE_DURATION_MIN, PATH_PARTICLE_DURATION_MAX),
                particleColor
            );
        }
    }
    
    /**
     * 生成终点喷射粒子（360度全向）
     */
    private void generateSprayParticles(CombatEngineAPI engine, Vector2f endpoint, float beamFacing, Vector2f shipVelocity) {
        // 每帧生成2-4个粒子
        int particleCount = MathUtils.getRandomNumberInRange(2, 4);
        
        for (int i = 0; i < particleCount; i++) {
            // 随机距离
            float distance = MathUtils.getRandomNumberInRange(SPRAY_PARTICLE_DISTANCE_MIN, SPRAY_PARTICLE_DISTANCE_MAX);
            
            // 随机角度（360度全向）
            float angle = MathUtils.getRandomNumberInRange(0f, 360f);
            
            // 计算速度（向外喷射）
            float duration = SPRAY_PARTICLE_DURATION * MathUtils.getRandomNumberInRange(0.5f, 1.5f);
            float speed = distance / duration;
            Vector2f particleVelocity = MathUtils.getPointOnCircumference(shipVelocity, speed, angle);
            
            // 随机尺寸
            float size = MathUtils.getRandomNumberInRange(SPRAY_PARTICLE_SIZE_MIN, SPRAY_PARTICLE_SIZE_MAX);
            
            // 随机亮度
            float brightness = MathUtils.getRandomNumberInRange(0.8f, 1.2f);
            
            // 生成粒子
            engine.addHitParticle(endpoint, particleVelocity, size, brightness, duration, SPRAY_PARTICLE_COLOR);
        }
    }
    
    /**
     * 生成终点喷射光线（360度全向）
     */
    private void generateSprayTrails(CombatEngineAPI engine, Vector2f endpoint, float beamFacing, Vector2f shipVelocity) {
        // 每帧生成1-2条光线
        int trailCount = Math.random() < 0.5f ? 1 : 2;
        
        for (int i = 0; i < trailCount; i++) {
            // 随机距离
            float distance = MathUtils.getRandomNumberInRange(SPRAY_TRAIL_DISTANCE_MIN, SPRAY_TRAIL_DISTANCE_MAX);
            
            // 随机角度（360度全向）
            float angle = MathUtils.getRandomNumberInRange(0f, 360f);
            
            // 计算终点位置
            Vector2f targetPos = MathUtils.getPointOnCircumference(endpoint, distance, angle);
            
            // 计算方向向量
            Vector2f direction = new Vector2f(
                targetPos.x - endpoint.x,
                targetPos.y - endpoint.y
            );
            
            // 创建TrailEntity
            TrailEntity trail = new TrailEntity();
            
            // 设置材质
            trail.getMaterialData().setDiffuse(Global.getSettings().getSprite("graphics/fx/beamfringeb.png"));
            trail.getMaterialData().setEmissive(Global.getSettings().getSprite("graphics/fx/beamcoreb.png"));
            trail.getMaterialData().setEmissiveColor(SPRAY_TRAIL_COLOR_CORE);
            trail.getMaterialData().setEmissiveColorAlpha(0.4f);
            
            // 设置颜色
            trail.setStartColor(SPRAY_TRAIL_COLOR_START);
            trail.setEndColor(SPRAY_TRAIL_COLOR_END);
            
            // 设置宽度
            trail.setStartWidth(SPRAY_TRAIL_WIDTH_START);
            trail.setEndWidth(SPRAY_TRAIL_WIDTH_END);
            
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
            
            // 添加节点（初始长度很小，避免零长度）
            trail.addNode(new Vector2f(0, 0));
            trail.addNode(new Vector2f(direction.x * 0.01f, direction.y * 0.01f));
            
            // 设置位置
            trail.setLocation(endpoint);
            
            // 线条模式
            trail.setStripLineMode(true);
            
            // 提交节点
            trail.setNodeRefreshAllFromCurrentIndex();
            trail.submitNodes();
            
            // 定时器
            trail.setGlobalTimer(0.0f, SPRAY_TRAIL_DURATION, 0.3f);
            
            // 添加到渲染系统
            CombatRenderingManager.addEntity(trail);
            
            // 添加到活跃列表
            activeSprayTrails.add(new TrailEntityWrapper(trail, SPRAY_TRAIL_DURATION, endpoint, targetPos, SPRAY_TRAIL_COLOR_END, shipVelocity));
        }
    }
    
    /**
     * 更新所有活跃的喷射TrailEntity
     */
    private void updateSprayTrailEntities(float amount) {
        if (activeSprayTrails.isEmpty()) {
            return;
        }
        
        Iterator<TrailEntityWrapper> iterator = activeSprayTrails.iterator();
        
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
     * TrailEntity包装类
     */
    private static class TrailEntityWrapper {
        public final TrailEntity trail;
        public final float maxAge;
        public float age;
        public final Vector2f startPos;
        public final Vector2f fullDirection;
        public final Color originalEndColor;
        public final Vector2f shipVelocity;
        
        public TrailEntityWrapper(TrailEntity trail, float maxAge, Vector2f startPos, Vector2f targetPos, Color endColor, Vector2f shipVelocity) {
            this.trail = trail;
            this.maxAge = maxAge;
            this.age = 0f;
            this.startPos = new Vector2f(startPos);
            this.fullDirection = new Vector2f(
                targetPos.x - startPos.x,
                targetPos.y - startPos.y
            );
            this.originalEndColor = endColor;
            this.shipVelocity = new Vector2f(shipVelocity);
        }
    }
    
    /**
     * 粒子控制器管理器
     */
    private static class ParticleControllerManager {
        private static final ReentrantLock lock = new ReentrantLock();
        private static final String CONTROLLER_KEY = "Moci_WaveMotionGun_SplashParticle";
        
        /**
         * 获取或创建粒子控制器
         */
        public static SimpleParticleControlData getController() {
            lock.lock();
            try {
                ConcurrentMap<String, Object> customData = CombatRenderingManager.getCustomData();
                SimpleParticleControlData controller = (SimpleParticleControlData) customData.get(CONTROLLER_KEY);
                
                if (controller == null || controller.isEntityExpired()) {
                    // 创建新的粒子控制器
                    controller = new SimpleParticleControlData(4096, 3.2f, -5120.0f, false);
                    
                    // 创建粒子精灵
                    SpriteAPI emissiveSprite = Global.getSettings().getSprite("graphics/fx/Moci_splash_01.png");
                    SpriteEntity sprite = new SpriteEntity();
                    
                    sprite.setDiffuseSprite(BoxDatabase.BUtil_NONE);
                    sprite.setEmissiveSprite(emissiveSprite);
                    
                    // 让粒子颜色完全应用到发光纹理
                    sprite.getMaterialData().setAlphaToEmissive(1.0f);
                    
                    sprite.setAdditiveBlend();
                    sprite.getMaterialData().setIgnoreIllumination(true);
                    
                    // 不使用瓦片切割，使用完整贴图
                    sprite.setUVEnd(emissiveSprite.getTexWidth(), emissiveSprite.getTexHeight());
                    sprite.setBaseSizePerTiles(0.5f, 0.5f);
                    sprite.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
                    sprite.setControlData(controller);
                    
                    // 添加到渲染管理器
                    CombatRenderingManager.addEntity(sprite);
                    customData.put(CONTROLLER_KEY, controller);
                }
                
                return controller;
            } finally {
                lock.unlock();
            }
        }
    }
}
