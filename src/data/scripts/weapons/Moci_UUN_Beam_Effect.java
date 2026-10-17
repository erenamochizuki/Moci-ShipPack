package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.Pair;
import data.scripts.util.Moci_SpriteAnimationUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;
import org.boxutil.base.SimpleParticleControlData;
import org.boxutil.define.BoxDatabase;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.attribute.Instance2Data;
import org.boxutil.units.standard.attribute.NodeData;
import org.boxutil.units.standard.entity.FlareEntity;
import org.boxutil.units.standard.entity.SegmentEntity;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.boxutil.units.standard.entity.TrailEntity;
import org.boxutil.util.CalculateUtil;
import org.boxutil.util.CurveUtil;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BoxUtil曲线光束武器效果 - 固定弧线版本
 * 特性：
 * 1. 开火前有光线汇聚效果（无粒子）
 * 2. 固定弧线模式：左倾斜、右倾斜、左倾斜加大、右倾斜加大...循环
 * 3. 支持随机模式：随机选择弧线但保证每个弧线都被使用一次
 */
public class Moci_UUN_Beam_Effect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    
    // ========== 爆炸特效配置 ==========
    // 爆炸特效基础尺寸
    private static final float EXPLOSION_SIZE_BASE = 64;
    // 爆炸特效尺寸随机范围（基础尺寸的倍数）
    private static final float EXPLOSION_SIZE_MIN = 0.6f;  // 最小60%
    private static final float EXPLOSION_SIZE_MAX = 1.0f;  // 最大100%
    // 爆炸位置随机偏移范围（像素）
    private static final float EXPLOSION_POSITION_OFFSET = 50.0f;
    // 爆炸动画持续时间（秒）
    private static final float EXPLOSION_DURATION = 0.5f;
    
    // ========== 光晕配置 ==========
    // 光晕是否跟随武器开火点移动
    private static final boolean FLARE_FOLLOW_WEAPON_POSITION = true;  // 默认跟随位置
    // 光晕是否跟随武器角度旋转
    private static final boolean FLARE_FOLLOW_WEAPON_ANGLE = true;  // 默认不跟随角度
    // 如果跟随角度，需要添加的角度偏移（度）
    private static final float FLARE_ANGLE_OFFSET = 90.0f;  // 如果跟随，则+90度
    
    // ========== 光线汇聚配置（使用TrailEntity，完全参考Moci_GuidingWeapon） ==========
    // 是否启用开火前的光线汇聚效果
    private static final boolean ENABLE_CHARGE_EFFECT = true;
    // 每帧生成的光线数量
    private static final int TRAIL_COUNT_PER_FRAME = 3;
    // 光线起始距离范围（像素）
    private static final float TRAIL_DISTANCE_MIN = 100.0f;
    private static final float TRAIL_DISTANCE_MAX = 200.0f;
    // 光线角度扩散范围（度）
    private static final float TRAIL_ANGLE_SPREAD = 80.0f;
    // 光线持续时间（秒）
    private static final float TRAIL_DURATION = 0.4f;
    // 光线宽度
    private static final float TRAIL_START_WIDTH = 5.0f;
    private static final float TRAIL_END_WIDTH = 1.0f;
    
    // ========== 弧线模式配置 ==========
    // 弧线模式：true=随机模式，false=顺序模式
    private static final boolean RANDOM_ARC_MODE = true;
    // 每个循环的弧线数量
    private static final int ARC_PATTERN_COUNT = 10;
    
    // ========== 光束配置 ==========
    // 光束起点宽度（像素）
    private static final float BEAM_START_WIDTH = 6.0f;
    // 光束终点宽度（像素）
    private static final float BEAM_END_WIDTH = 36.0f;
    // 光束起点发光颜色
    private static final Color BEAM_START_EMISSIVE_COLOR = new Color(255, 127, 80, 255);  // 珊瑚橙
    // 光束终点发光颜色
    private static final Color BEAM_END_EMISSIVE_COLOR = new Color(255, 30, 80, 255);  // 红色
    // 光束终点基础颜色（混合用）
    private static final Color BEAM_END_BASE_COLOR = new Color(240, 200, 255, 255);  // 淡紫色
    
    // ========== 追踪配置 ==========
    // 是否启用终点追踪效果
    private static final boolean ENABLE_TRACKING = true;
    // 终点追踪搜索半径（像素）
    private static final float TRACKING_SEARCH_RADIUS = 300.0f;
    // 路径追踪搜索半径（终点搜索半径的一半）
    private static final float PATH_SEARCH_RADIUS = TRACKING_SEARCH_RADIUS * 0.5f;
    // 路径采样点数量（用于检测路径上的敌人）
    private static final int PATH_SAMPLE_COUNT = 8;
    // 追踪优先级：true=优先追踪护盾目标，false=优先追踪船体目标
    private static final boolean PREFER_SHIELDED_TARGETS = false;
    
    // 光晕数据类 - 记录光晕和对应的开火点索引
    private static class FlareData {
        FlareEntity flare;
        int firePointIndex;
        
        FlareData(FlareEntity flare, int firePointIndex) {
            this.flare = flare;
            this.firePointIndex = firePointIndex;
        }
    }
    
    private List<FlareData> muzzleFlares = new ArrayList<>();  // 保存所有光斑引用和索引
    private List<TrailEntityWrapper> activeTrails = new ArrayList<>();  // 保存汇聚光线（TrailEntity）
    
    // 弧线索引管理
    private int currentArcIndex = 0;  // 当前弧线索引
    private List<Integer> remainingIndices = new ArrayList<>();  // 随机模式下剩余的索引
    
    // 蓄力状态追踪（用于控制光线生成）
    private float lastChargeLevel = 0.0f;  // 上一帧的蓄力等级
    private int lastWeaponAmmo = 1;  // 上一帧的弹药数量
    
    /**
     * TrailEntity包装类，用于管理光线的生命周期和动画
     */
    private static class TrailEntityWrapper {
        public final TrailEntity trail;
        public final float maxAge;
        public float age;
        public final Vector2f startPos;
        public final Vector2f targetPos;
        public final Vector2f fullDirection; // 完整方向向量（从起点到终点）
        
        public TrailEntityWrapper(TrailEntity trail, float maxAge, Vector2f startPos, Vector2f targetPos) {
            this.trail = trail;
            this.maxAge = maxAge;
            this.age = 0.0f;
            this.startPos = new Vector2f(startPos);
            this.targetPos = new Vector2f(targetPos);
            
            // 计算完整方向向量
            this.fullDirection = new Vector2f(
                targetPos.x - startPos.x,
                targetPos.y - startPos.y
            );
        }
    }
    
    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 移除弹丸
        if (projectile != null) {
            engine.removeEntity(projectile);
        }
        
        // 获取弹丸生成位置（即开火点位置）
        Vector2f fireLocation = projectile != null ? projectile.getLocation() : weapon.getLocation();
        
        // 找到最接近弹丸位置的开火点索引
        int firePointIndex = findClosestFirePointIndex(weapon, fireLocation);
        
        // 生成曲线光束
        ShipAPI ship = weapon.getShip();
        if (ship != null) {
            // 获取下一个弧线索引
            int arcIndex = getNextArcIndex();
            
            // 直接使用弹丸位置生成光束
            spawnSingleCurveBeam(engine, weapon, ship, fireLocation, 
                weapon.getRange(), 
                weapon.getDamage().getDamage(), 
                weapon.getDamageType(), 
                weapon.getDamage().getFluxComponent(),
                arcIndex);
            
            // 在弹丸位置生成光晕，并记录开火点索引
            FlareEntity flare = createSingleFlare(engine, weapon, fireLocation);
            muzzleFlares.add(new FlareData(flare, firePointIndex));
        }
    }
    
    /**
     * 获取下一个弧线索引
     */
    private int getNextArcIndex() {
        if (RANDOM_ARC_MODE) {
            // 随机模式
            if (remainingIndices.isEmpty()) {
                // 重新填充索引列表
                for (int i = 0; i < ARC_PATTERN_COUNT; i++) {
                    remainingIndices.add(i);
                }
            }
            // 随机选择一个索引
            int randomIndex = (int) (Math.random() * remainingIndices.size());
            int arcIndex = remainingIndices.remove(randomIndex);
            return arcIndex;
        } else {
            // 顺序模式
            int arcIndex = currentArcIndex;
            currentArcIndex = (currentArcIndex + 1) % ARC_PATTERN_COUNT;
            return arcIndex;
        }
    }
    
    
    /**
     * 找到最接近指定位置的开火点索引
     */
    private int findClosestFirePointIndex(WeaponAPI weapon, Vector2f targetLocation) {
        List<Vector2f> fireOffsets = weapon.getSpec().getTurretFireOffsets();
        if (fireOffsets == null || fireOffsets.isEmpty()) {
            fireOffsets = weapon.getSpec().getHardpointFireOffsets();
        }
        
        if (fireOffsets == null || fireOffsets.isEmpty()) {
            return 0;
        }
        
        // 找到距离最近的开火点
        int closestIndex = 0;
        float closestDistSq = Float.MAX_VALUE;
        
        for (int i = 0; i < fireOffsets.size(); i++) {
            Vector2f firePoint = weapon.getFirePoint(i);
            if (firePoint != null) {
                float dx = firePoint.x - targetLocation.x;
                float dy = firePoint.y - targetLocation.y;
                float distSq = dx * dx + dy * dy;
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closestIndex = i;
                }
            }
        }
        
        return closestIndex;
    }
    
    /**
     * 按优先级搜索目标
     * 优先级：
     * 1. 终点舰船
     * 2. 路径舰船
     */
    private CombatEntityAPI findTargetByPriority(CombatEngineAPI engine, ShipAPI source,
                                                  Vector2f startPoint, Vector2f endPoint) {
        if (source == null || startPoint == null || endPoint == null) {
            return null;
        }
        
        // 1. 优先级最高：终点舰船
        CombatEntityAPI target = findNearestShip(engine, source, endPoint, TRACKING_SEARCH_RADIUS);
        if (target != null) return target;
        
        // 2. 路径舰船
        target = findShipAlongPath(engine, source, startPoint, endPoint, PATH_SEARCH_RADIUS);
        if (target != null) return target;
        
        return null;
    }
    
    /**
     * 搜索最近的舰船（非战斗机）
     */
    private ShipAPI findNearestShip(CombatEngineAPI engine, ShipAPI source,
                                    Vector2f searchCenter, float searchRadius) {
        if (source == null || searchCenter == null) {
            return null;
        }
        
        ShipAPI nearestShip = null;
        float nearestDistSq = searchRadius * searchRadius;
        
        for (ShipAPI ship : engine.getShips()) {
            if (ship == source 
                || ship.getOwner() == source.getOwner()
                || ship.isFighter()
                || ship.isDrone()
                || ship.isHulk()
                || ship.isShuttlePod()
                || !ship.isAlive()
                || ship.isPhased()
                || ship.getCollisionClass() == CollisionClass.NONE) {
                continue;
            }
            
            Vector2f shipLoc = ship.getLocation();
            float dx = shipLoc.x - searchCenter.x;
            float dy = shipLoc.y - searchCenter.y;
            float distSq = dx * dx + dy * dy;
            
            if (distSq < nearestDistSq) {
                if (PREFER_SHIELDED_TARGETS) {
                    if (ship.getShield() != null && ship.getShield().isOn()) {
                        nearestShip = ship;
                        nearestDistSq = distSq;
                    } else if (nearestShip == null) {
                        nearestShip = ship;
                        nearestDistSq = distSq;
                    }
                } else {
                    nearestShip = ship;
                    nearestDistSq = distSq;
                }
            }
        }
        
        return nearestShip;
    }
    
    /**
     * 在路径上搜索舰船
     */
    private ShipAPI findShipAlongPath(CombatEngineAPI engine, ShipAPI source,
                                      Vector2f startPoint, Vector2f endPoint,
                                      float searchRadius) {
        return findShipAlongPathImpl(engine, source, startPoint, endPoint, searchRadius);
    }
    
    /**
     * 在路径上搜索舰船的实现
     */
    private ShipAPI findShipAlongPathImpl(CombatEngineAPI engine, ShipAPI source,
                                          Vector2f startPoint, Vector2f endPoint,
                                          float searchRadius) {
        if (source == null || startPoint == null || endPoint == null) {
            return null;
        }
        
        // 计算路径方向和长度
        float dx = endPoint.x - startPoint.x;
        float dy = endPoint.y - startPoint.y;
        float pathLength = (float) Math.sqrt(dx * dx + dy * dy);
        
        if (pathLength < 1.0f) {
            return null;
        }
        
        // 归一化方向向量
        float dirX = dx / pathLength;
        float dirY = dy / pathLength;
        
        // 沿路径采样，寻找最近的舰船
        ShipAPI closestShip = null;
        float closestDistToStart = Float.MAX_VALUE;
        
        // 从起点开始，每隔一段距离采样一个点
        float stepSize = pathLength / PATH_SAMPLE_COUNT;
        
        for (int i = 0; i <= PATH_SAMPLE_COUNT; i++) {
            float distance = i * stepSize;
            Vector2f samplePoint = new Vector2f(
                startPoint.x + dirX * distance,
                startPoint.y + dirY * distance
            );
            
            // 在这个采样点附近搜索舰船
            ShipAPI ship = findNearestShip(engine, source, samplePoint, searchRadius);
            
            if (ship != null) {
                // 计算舰船到起点的距离
                Vector2f shipLoc = ship.getLocation();
                float distX = shipLoc.x - startPoint.x;
                float distY = shipLoc.y - startPoint.y;
                float distToStart = distX * distX + distY * distY;
                
                // 保存距离起点最近的舰船（优先追踪路径前方的目标）
                if (distToStart < closestDistToStart) {
                    closestShip = ship;
                    closestDistToStart = distToStart;
                }
            }
        }
        
        return closestShip;
    }
    

    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused()) {
            return;
        }
        
        // 清理已删除的光斑
        muzzleFlares.removeIf(flareData -> flareData == null || flareData.flare == null || flareData.flare.hasDelete());
        
        // 更新TrailEntity光线
        updateTrailEntities(amount);
        
        // 获取当前蓄力等级和弹药数量
        float chargeLevel = weapon.getChargeLevel();
        int weaponAmmo = weapon.getAmmo();
        
        // ==================== 检测蓄力或开火状态（完全按照Moci_GuidingWeapon） ====================
        // 如果蓄力等级增加 或 弹药减少（表示开火了）
        if ((chargeLevel > lastChargeLevel) || (weaponAmmo < lastWeaponAmmo)) {
            // 如果武器正在开火且启用了汇聚效果，生成光线
            if (ENABLE_CHARGE_EFFECT && weapon.isFiring()) {
                Vector2f muzzleLocation = weapon.getLocation();
                float weaponAngle = weapon.getCurrAngle();
                spawnTrailEntities(muzzleLocation, weaponAngle, engine);
            }
        }
        
        // 保存当前状态，用于下一帧比较
        lastChargeLevel = chargeLevel;
        lastWeaponAmmo = weaponAmmo;
        
        // 如果配置了跟随位置，更新每个光斑的位置
        if (FLARE_FOLLOW_WEAPON_POSITION) {
            for (FlareData flareData : muzzleFlares) {
                if (flareData.flare != null && !flareData.flare.hasDelete()) {
                    // 获取当前开火点的世界坐标
                    Vector2f firePoint = weapon.getFirePoint(flareData.firePointIndex);
                    if (firePoint != null) {
                        if (FLARE_FOLLOW_WEAPON_ANGLE) {
                            // 跟随位置和角度
                            flareData.flare.setStateVanilla(firePoint, weapon.getCurrAngle() + FLARE_ANGLE_OFFSET);
                        } else {
                            // 只跟随位置，不改变角度
                            flareData.flare.setLocation(firePoint);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 生成TrailEntity光线
     * 
     * @param muzzleLocation 枪口位置
     * @param shipFacing 武器朝向
     * @param engine 战斗引擎
     */
    private void spawnTrailEntities(Vector2f muzzleLocation, float shipFacing, CombatEngineAPI engine) {
        // 每帧生成少量光线
        for (int i = 0; i < TRAIL_COUNT_PER_FRAME; i++) {
            // 随机距离（光线从这个距离向枪口收束）
            float distance = MathUtils.getRandomNumberInRange(TRAIL_DISTANCE_MIN, TRAIL_DISTANCE_MAX);
            
            // 随机角度偏移
            float angleSpread = TRAIL_ANGLE_SPREAD;
            float angle = MathUtils.getRandomNumberInRange(-0.5f * angleSpread, 0.5f * angleSpread);
            
            // 计算光线起始位置（世界坐标）
            Vector2f startPos = MathUtils.getPointOnCircumference(muzzleLocation, distance, angle + shipFacing);
            
            // 计算方向向量（从起点到枪口）
            Vector2f direction = new Vector2f(
                muzzleLocation.x - startPos.x,
                muzzleLocation.y - startPos.y
            );
            
            // 创建TrailEntity
            TrailEntity trail = new TrailEntity();
            
            // 设置材质纹理（完全按照演示的方式）
            trail.getMaterialData().setDiffuse(Global.getSettings().getSprite("graphics/fx/beamfringeb.png"));
            trail.getMaterialData().setEmissive(Global.getSettings().getSprite("graphics/fx/beamcoreb.png"));
            trail.getMaterialData().setEmissiveColor(BEAM_START_EMISSIVE_COLOR);  // 使用光束起点颜色
            trail.getMaterialData().setEmissiveColorAlpha(0.4f);
            
            // 设置颜色（使用光束颜色）
            trail.setStartColor(BEAM_START_EMISSIVE_COLOR);
            trail.setEndColor(BEAM_START_EMISSIVE_COLOR);
            
            // 设置光线宽度（按照演示的值）
            trail.setStartWidth(TRAIL_START_WIDTH);
            trail.setEndWidth(TRAIL_END_WIDTH);
            
            // 效果设置（按照演示）
            trail.setJitterPower(0.0f);
            trail.setFlick(true);
            trail.setFlickMixValue(0.5f);
            
            // 纹理参数（按照演示）
            trail.setTexturePixels(256.0f);
            trail.setTextureSpeed(100.0f);
            
            // 填充设置（按照演示）
            trail.setFillStartAlpha(0.0f);
            trail.setFillEndAlpha(0.0f);
            trail.setFillStartFactor(0.9f);
            trail.setFillEndFactor(0.9f);
            
            // 渲染层级
            trail.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
            
            // 混合模式
            trail.setAdditiveBlend();
            
            // 添加节点（相对坐标）
            // 起点在原点
            trail.addNode(new Vector2f(0, 0));
            // 终点初始位置（给一个很小的初始长度）
            trail.addNode(new Vector2f(direction.x * 0.01f, direction.y * 0.01f));
            
            // 设置位置（世界坐标）
            trail.setLocation(startPos);
            
            // 线条模式（按照演示）
            trail.setStripLineMode(true);
            
            // 提交节点（按照演示的顺序）
            trail.setNodeRefreshAllFromCurrentIndex();
            trail.submitNodes();
            
            // 定时器（按照演示，在最后设置）
            trail.setGlobalTimer(0.0f, TRAIL_DURATION, 0.3f);
            
            // 添加到渲染系统
            CombatRenderingManager.addEntity(trail);
            
            // 添加到活跃列表
            activeTrails.add(new TrailEntityWrapper(trail, TRAIL_DURATION, startPos, muzzleLocation));
        }
    }
    
    /**
     * 更新所有活跃的TrailEntity
     * 
     * @param amount 时间增量
     */
    private void updateTrailEntities(float amount) {
        if (activeTrails.isEmpty()) {
            return;
        }
        
        // 更新所有光线的生命周期和位置
        activeTrails.removeIf(wrapper -> {
            wrapper.age += amount;
            
            // 如果光线已经被删除或超时，移除它
            if (wrapper.trail.hasDelete() || wrapper.age >= wrapper.maxAge) {
                if (!wrapper.trail.hasDelete()) {
                    wrapper.trail.delete();
                }
                return true;
            }
            
            // 计算当前进度（0.0到1.0）
            float progress = Math.min(wrapper.age / wrapper.maxAge, 1.0f);
            
            // 更新终点位置（从起点逐渐延伸到目标点）
            Vector2f currentEnd = new Vector2f(
                wrapper.fullDirection.x * progress,
                wrapper.fullDirection.y * progress
            );
            
            // 更新TrailEntity的终点节点
            wrapper.trail.getNodes().get(1).set(currentEnd);
            
            // 设置刷新范围（只刷新终点节点）
            wrapper.trail.setNodeRefreshIndex(1);
            wrapper.trail.setNodeRefreshSize(1);
            
            // 提交更新
            wrapper.trail.submitNodes();
            
            return false;
        });
    }
    
    /**
     * 创建单个枪口光晕特效
     * @return 返回创建的FlareEntity
     */
    private FlareEntity createSingleFlare(CombatEngineAPI engine, WeaponAPI weapon, Vector2f location) {
        FlareEntity flare = new FlareEntity();
        
        // 尺寸设置
        flare.setSize(100.0f, 10.0f);
        
        // 形状设置
        flare.setSmoothDisc();
        
        // 颜色设置 - 边缘色与光束起点颜色一致（金黄色）
        flare.setFringeColor(new Color(255, 95, 80, 255));
        flare.setCoreColor(Color.WHITE);                      // 白色核心
        flare.setCoreAlpha(1.0f);
        flare.setFringeAlpha(1.0f);
        
        // 混合模式
        flare.setNormalBlend();
        
        // 效果设置
        flare.setFlick(true);
        flare.setFlickWhenPaused(false);
        flare.setNoisePower(0.33f);
        flare.setGlowPower(1.0f);
        
        // 自动宽高比
        flare.autoAspect();
        
        // 渲染层级
        flare.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        
        // 位置
        flare.setLocation(location);
        
        // 朝向（根据配置决定是否跟随武器角度）
        if (FLARE_FOLLOW_WEAPON_ANGLE) {
            flare.setStateVanilla(location, weapon.getCurrAngle() + FLARE_ANGLE_OFFSET);
        }
        
        // 定时器 - 持续时间与光束一致
        flare.setGlobalTimer(0.05f, 0.4f, 0.2f);
        
        // 添加到渲染管理器
        CombatRenderingManager.addEntity(flare);
        
        return flare;
    }
    
    /**
     * 从指定位置生成单条曲线光束
     * @param arcIndex 弧线索引（0-9），决定弧线的形状
     */
    private void spawnSingleCurveBeam(CombatEngineAPI engine, WeaponAPI weapon, ShipAPI source, 
                                      Vector2f fireLoc, float range, float damage, 
                                      DamageType damageType, float empDamage, int arcIndex) {
        float facing = weapon.getCurrAngle();
        
        // 计算默认终点位置（武器朝向 + 射程）
        float angleRad = (float) Math.toRadians(facing);
        float cos = (float) Math.cos(angleRad);
        float sin = (float) Math.sin(angleRad);
        Vector2f defaultEndPoint = new Vector2f(
            fireLoc.x + cos * range,
            fireLoc.y + sin * range
        );
        
        // 尝试追踪敌人（按优先级搜索）
        Vector2f actualEndPoint = defaultEndPoint;
        if (ENABLE_TRACKING) {
            CombatEntityAPI target = findTargetByPriority(engine, source, fireLoc, defaultEndPoint);
            if (target != null) {
                actualEndPoint = target.getLocation();
            }
        }
        
        // 计算从开火点到终点的向量（世界坐标）
        float worldDx = actualEndPoint.x - fireLoc.x;
        float worldDy = actualEndPoint.y - fireLoc.y;
        float actualRange = (float) Math.sqrt(worldDx * worldDx + worldDy * worldDy);
        
        // 计算终点相对于武器朝向的角度偏移
        float worldAngle = (float) Math.toDegrees(Math.atan2(worldDy, worldDx));
        float angleOffset = worldAngle - facing;
        
        // 将角度偏移归一化到 [-180, 180] 范围
        while (angleOffset > 180) angleOffset -= 360;
        while (angleOffset < -180) angleOffset += 360;
        
        // 将终点转换到局部坐标系（相对于武器朝向）
        float angleOffsetRad = (float) Math.toRadians(angleOffset);
        float localEndX = actualRange * (float) Math.cos(angleOffsetRad);
        float localEndY = actualRange * (float) Math.sin(angleOffsetRad);
        
        NodeData start = new NodeData();
        NodeData end = new NodeData();
        
        // 根据弧线索引计算固定的切线参数
        ArcPattern pattern = getArcPattern(arcIndex);
        
        // 起点切线：向前延伸，带垂直偏移
        start.setTangentRight(
            actualRange * pattern.startTangentForward,
            actualRange * pattern.startTangentVertical
        );
        
        // 终点位置：使用局部坐标
        end.setLocation(localEndX, localEndY);
        
        // 终点切线：向后延伸，带垂直偏移
        end.setTangentLeft(
            actualRange * pattern.endTangentBackward,
            actualRange * pattern.endTangentVertical
        );
        
        // 计算光束终点颜色（用于后续粒子）
        Color beamEndColor = BEAM_END_EMISSIVE_COLOR;
        
        Pair<SegmentEntity, Vector3f> pair = CurveUtil.spawnCurveBeam(
            engine,
            new Vector3f(fireLoc.x, fireLoc.y, facing),  // 使用武器原始朝向
            start,
            end,
            actualRange + 64.0f,  // 使用实际射程
            new BeamDealt(engine, source, damage, damageType, empDamage, beamEndColor)
        );
        
        if (pair == null || pair.one == null) {
            return;
        }
        
        start = pair.one.getNodes().get(0);
        start.setEmissiveColor(BEAM_START_EMISSIVE_COLOR);
        start.setWidth(BEAM_START_WIDTH);
        
        end = pair.one.getNodes().get(1);
        float curveT = pair.two == null ? 1.0f : pair.two.z;
        end.setColor(CalculateUtil.mix(Color.WHITE, BEAM_END_BASE_COLOR, false, curveT));
        end.setEmissiveColor(CalculateUtil.mix(
            BEAM_START_EMISSIVE_COLOR,
            BEAM_END_EMISSIVE_COLOR, 
            false, 
            curveT
        ));
        end.setWidth(CalculateUtil.mix(BEAM_START_WIDTH, BEAM_END_WIDTH, curveT));
        
        SpriteAPI coreSprite = Global.getSettings().getSprite("graphics/fx/beam_rough2_core.png");
        SpriteAPI fringeSprite = Global.getSettings().getSprite("graphics/fx/beam_rough2_fringe.png");
        
        pair.one.getMaterialData().setDiffuse(coreSprite);
        pair.one.getMaterialData().setEmissive(fringeSprite);
        pair.one.getMaterialData().setColorAlpha(0.75f);
        pair.one.getMaterialData().setEmissiveColorAlpha(0.8f);
        pair.one.getMaterialData().setAlphaToEmissive(0.0f);
        
        pair.one.setInterpolation((short) 32);
        pair.one.setTexturePixels(256.0f);
        pair.one.setTextureSpeed(512.0f);
        pair.one.setAdditiveBlend();
        
        float smoothFactor = CurveUtil.getCurveLength(start, end, 8);
        smoothFactor = Math.max(smoothFactor - 4.0f, 0.0f) / smoothFactor;
        pair.one.setFillStartAlpha(0.0f);
        pair.one.setFillStartFactor(smoothFactor);
        pair.one.setFillEndAlpha(0.0f);
        pair.one.setFillEndFactor(smoothFactor);
        
        pair.one.setNodeRefreshAllFromCurrentIndex();
        pair.one.submitNodes();
        pair.one.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
        pair.one.setGlobalTimer(0.05f, 0.4f, 0.2f);
        
        CombatRenderingManager.addEntity(pair.one);
    }
    
    /**
     * 弧线模式数据类
     */
    private static class ArcPattern {
        float startTangentForward;   // 起点切线前向分量（相对射程）
        float startTangentVertical;  // 起点切线垂直分量（相对射程）
        float endTangentBackward;    // 终点切线后向分量（相对射程，负值）
        float endTangentVertical;    // 终点切线垂直分量（相对射程）
        
        ArcPattern(float startForward, float startVertical, float endBackward, float endVertical) {
            this.startTangentForward = startForward;
            this.startTangentVertical = startVertical;
            this.endTangentBackward = endBackward;
            this.endTangentVertical = endVertical;
        }
    }
    
    /**
     * 获取指定索引的弧线模式
     * 索引0-9对应：左倾斜、右倾斜、左倾斜加大、右倾斜加大、左倾斜更大、右倾斜更大、左倾斜最大、右倾斜最大、左倾斜极限、右倾斜极限
     */
    private ArcPattern getArcPattern(int index) {
        // 归一化索引到0-9范围
        index = index % ARC_PATTERN_COUNT;
        
        // 基础参数
        float baseForward = 0.2f;      // 起点前向切线基础值
        float baseBackward = -0.1f;    // 终点后向切线基础值
        
        // 根据索引计算倾斜程度
        int level = index / 2;  // 倾斜等级：0,1,2,3,4
        boolean isLeft = (index % 2 == 0);  // 是否左倾斜
        
        // 垂直偏移随等级递增
        float verticalOffset = 0.15f + level * 0.1f;
        
        // 左倾斜为正，右倾斜为负
        float startVertical = isLeft ? verticalOffset : -verticalOffset;
        // 终点切线也应该向同一侧偏移，但幅度稍小
        float endVertical = isLeft ? verticalOffset * 0.5f : -verticalOffset * 0.5f;
        
        return new ArcPattern(baseForward, startVertical, baseBackward, endVertical);
    }
    
    /**
     * 粒子控制器管理器
     */
    private static class ParticleControllerManager {
        private static final ReentrantLock lock = new ReentrantLock();
        private static final String CONTROLLER_KEY = "Moci_CurveBeam_SplashParticle";
        
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
                    sprite.getMaterialData().setColorToEmissive(1.0f);  // 颜色完全应用到发光
                    sprite.getMaterialData().setAlphaToEmissive(1.0f);  // 透明度完全应用到发光
                    
                    sprite.setAdditiveBlend();
                    sprite.getMaterialData().setIgnoreIllumination(true);
                    
                    // 不使用瓦片切割，使用完整贴图
                    sprite.setUVEnd(emissiveSprite.getTexWidth(), emissiveSprite.getTexHeight());
                    sprite.setBaseSizePerTiles(0.5f, 0.5f);
                    sprite.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
                    sprite.setControlData(controller);
                    
                    // 添加到渲染管理器 - 使用新API
                    CombatRenderingManager.addEntity(sprite);
                    customData.put(CONTROLLER_KEY, controller);
                }
                
                return controller;
            } finally {
                lock.unlock();
            }
        }
    }
    
    /**
     * 控制曲线光束效果的部分
     */
    private static class BeamDealt implements CurveUtil.DealtController {
        private final CombatEngineAPI engine;
        private final ShipAPI source;
        private final float maxDamage;
        private final float minDamage;
        private final DamageType damageType;
        private final float empDamage;
        private final Color coreColor;
        private final Color fringeColor;
        private final Color beamEndColor;  // 光束终点颜色，用于粒子
        private final float hitParticleDuration;
        private final float hitParticleSize;
        private final float hitParticleSize2;
        
        public BeamDealt(CombatEngineAPI engine, ShipAPI source, float damage, DamageType damageType, float empDamage, Color beamEndColor) {
            this.engine = engine;
            this.source = source;
            this.maxDamage = damage;
            this.minDamage = this.maxDamage * 0.5f;
            this.damageType = damageType;
            this.empDamage = empDamage;
            
            this.coreColor = BEAM_START_EMISSIVE_COLOR;
            this.fringeColor = BEAM_END_EMISSIVE_COLOR;
            this.beamEndColor = beamEndColor;  // 保存光束终点颜色
            this.hitParticleDuration = 0.9f;
            this.hitParticleSize = 24.0f * 3.0f;
            this.hitParticleSize2 = this.hitParticleSize * 2.0f;
        }
        
        @Override
        public void applyEffect(CombatEntityAPI combatEntityAPI, Vector2f point, float beamT, boolean isShieldHit) {
            // 应用伤害 - 使用武器的伤害类型和EMP伤害
            float damage = CalculateUtil.mix(this.maxDamage, this.minDamage, beamT);
            this.engine.applyDamage(
                combatEntityAPI, 
                point, 
                damage, 
                this.damageType, 
                this.empDamage, 
                false, 
                true, 
                this.source,
                true
            );
            
            // 添加命中粒子特效（双层）
            this.engine.addHitParticle(
                point, 
                new Vector2f(), 
                this.hitParticleSize2, 
                0.6666667f, 
                this.hitParticleDuration, 
                this.fringeColor
            );
            
            this.engine.addHitParticle(
                point, 
                new Vector2f(), 
                this.hitParticleSize, 
                1.0f, 
                this.hitParticleDuration, 
                this.coreColor
            );
            
            // 添加粒子飞溅效果
            spawnParticleSplash(point, combatEntityAPI);
            
            // 添加爆炸特效（4x4网格帧动画）
            spawnExplosionEffect(point, combatEntityAPI);
        }
        
        /**
         * 生成爆炸特效（2x4网格帧动画）
         * 使用Moci_SpriteAnimationUtil工具类
         * 使用光束终点颜色
         */
        private void spawnExplosionEffect(Vector2f hitPoint, CombatEntityAPI target) {
            // 计算随机偏移位置
            float offsetAngle = MathUtils.getRandomNumberInRange(0.0f, 360.0f);
            float offsetDistance = MathUtils.getRandomNumberInRange(0.0f, EXPLOSION_POSITION_OFFSET);
            Vector2f explosionPos = MathUtils.getPointOnCircumference(
                hitPoint, 
                offsetDistance, 
                offsetAngle
            );
            
            // 随机尺寸（基础尺寸的60%-100%）
            float sizeMultiplier = MathUtils.getRandomNumberInRange(EXPLOSION_SIZE_MIN, EXPLOSION_SIZE_MAX);
            float explosionSize = EXPLOSION_SIZE_BASE * sizeMultiplier;
            
            // 完全随机朝向（0-360度）
            float facing = MathUtils.getRandomNumberInRange(0.0f, 360.0f);
            
            // 使用工具类播放1行8列网格动画
            SpriteEntity explosion = Moci_SpriteAnimationUtil.playAnimation(
                this.engine,
                "fx",
                "Moci_breakingdawn_exp",
                explosionPos,
                facing,
                explosionSize,
                explosionSize,
                1,  // 1行
                8,  // 8列
                8,  // 8帧
                EXPLOSION_DURATION,
                true,  // 加法混合
                CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER,
                Moci_SpriteAnimationUtil.FrameOrder.BOTTOM_TO_TOP
            );
            
            // 设置颜色为光束终点颜色
            explosion.getMaterialData().setColor(BEAM_END_EMISSIVE_COLOR);
        }
        
        /**
         * 生成粒子飞溅效果
         */
        private void spawnParticleSplash(Vector2f hitPoint, CombatEntityAPI target) {
            // 获取粒子控制器
            SimpleParticleControlData controller = ParticleControllerManager.getController();
            if (controller == null) {
                return;
            }
            
            // 计算飞溅方向（远离目标中心）
            Vector2f splashDir = new Vector2f();
            if (target != null && target.getLocation() != null) {
                Vector2f.sub(hitPoint, target.getLocation(), splashDir);
                float length = splashDir.length();
                if (length > 0) {
                    splashDir.scale(1.0f / length);
                } else {
                    float angle = (float) (Math.random() * Math.PI * 2.0);
                    splashDir.x = (float) Math.cos(angle);
                    splashDir.y = (float) Math.sin(angle);
                }
            } else {
                float angle = (float) (Math.random() * Math.PI * 2.0);
                splashDir.x = (float) Math.cos(angle);
                splashDir.y = (float) Math.sin(angle);
            }
            
            // 生成16个飞溅粒子
            int particleCount = 16;
            float angleSpread = 90.0f;  // 扇形角度范围
            float baseAngle = (float) Math.atan2(splashDir.y, splashDir.x);
            
            for (int i = 0; i < particleCount; i++) {
                // 添加粒子 - 带null检查
                Instance2Data particle = controller.addParticle();
                if (particle == null) {
                    continue;  // 粒子池已满，跳过
                }
                
                // 计算飞溅角度
                float angleOffset = (i - particleCount * 0.5f) / particleCount * angleSpread;
                float angle = baseAngle + (float) Math.toRadians(angleOffset);
                
                // 速度在范围内随机：
                float speed = 100.0f + (float) (Math.random() * 200f);  // 100-300像素/秒
                Vector2f velocity = new Vector2f(
                    (float) Math.cos(angle) * speed,
                    (float) Math.sin(angle) * speed
                );
                
                // 设置粒子属性
                particle.setLocation(hitPoint);
                particle.setVelocity(velocity);
                
                // 粒子朝向速度方向（转换为角度）
                float facingAngle = (float) Math.toDegrees(angle);
                particle.setFacing(facingAngle);
                particle.setTurnRate(0.0f);  // 不旋转，保持朝向速度方向
                
                // 随机尺寸
                float size = 6.0f + (float) Math.random() * 14.0f;  // 8-20像素
                particle.setScale(size, size);
                particle.setScaleRate(0.5f, 0.5f);  // 逐渐缩小
                
                // 颜色渐变 - 粒子生命周期内从红色渐变到金黄色
                // LowColor = 起始颜色（光束终点的红色）
                // HighColor = 结束颜色（光束起点的金黄色）
                // BoxUtil会根据timer自动在两个颜色之间插值
                
                particle.setLowColor(this.beamEndColor);      // 起始：红色 RGB(255, 30, 80)
                particle.setHighColor(this.coreColor);        // 结束：金黄色 RGB(255, 210, 80)
                
                // 发光颜色也设置渐变
                particle.setLowEmissiveColor(this.beamEndColor);
                particle.setHighEmissiveColor(this.coreColor);
                
                // 透明度
                particle.setAlpha(0.8f + (float) Math.random() * 0.2f);
                
                // 生命周期
                particle.setTimer(
                    0.0f,                           // 淡入
                    0.2f + (float) Math.random() * 0.2f,  // 持续0.2-0.4秒
                    0.3f                            // 淡出
                );
            }
        }
        
        @Override
        public boolean isIgnore(CombatEntityAPI target) {
            boolean isFighter = false;
            boolean parentIsSource = false;
            boolean inPhased = false;
            
            if (target instanceof ShipAPI) {
                ShipAPI shipTarget = (ShipAPI) target;
                isFighter = shipTarget.isFighter();
                parentIsSource = shipTarget.getParentStation() == this.source;
                inPhased = shipTarget.isPhased();
            }
            
            return target == this.source 
                || target.getCollisionClass() == CollisionClass.NONE 
                || inPhased 
                || (target instanceof MissileAPI || isFighter || parentIsSource) 
                    && target.getOwner() == source.getOwner();
        }
        
        @Override
        public boolean isPierceShield(ShipAPI target) {
            return false;
        }
        
        @Override
        public boolean isPierce(CombatEntityAPI target, Vector2f vector2f, float beamT, boolean isShieldHit) {
            boolean isFighter = target instanceof ShipAPI && ((ShipAPI) target).isFighter();
            return target instanceof CombatAsteroidAPI 
                || target instanceof MissileAPI 
                || isFighter;
        }
    }
}
