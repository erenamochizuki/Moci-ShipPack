package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.Pair;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;
import org.boxutil.base.SimpleParticleControlData;
import org.boxutil.base.api.RenderDataAPI;
import org.boxutil.define.BoxDatabase;
import org.boxutil.define.BoxEnum;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.attribute.Instance2Data;
import org.boxutil.units.standard.attribute.NodeData;
import org.boxutil.units.standard.entity.FlareEntity;
import org.boxutil.units.standard.entity.SegmentEntity;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.boxutil.util.CalculateUtil;
import org.boxutil.util.CurveUtil;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BoxUtil曲线光束武器效果 - 粒子飞溅版本
 * 使用SimpleParticleControlData创建飞溅粒子效果
 */
public class Moci_CurveBeamWeapon_ParticleSplash_Effect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    
    // 配置项：光晕是否跟随武器角度旋转
    private static final boolean FLARE_FOLLOW_WEAPON_ANGLE = false;  // 默认不跟随
    // 配置项：如果跟随角度，需要添加的角度偏移（度）
    private static final float FLARE_ANGLE_OFFSET = 90.0f;  // 如果跟随，则+90度
    
    private List<FlareEntity> muzzleFlares = new ArrayList<>();  // 保存所有光斑引用
    
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
            // 只在当前开火点生成光束
            spawnSingleCurveBeamAtFirePoint(engine, weapon, ship, firePointIndex);
            
            // 只在当前开火点生成光晕
            spawnSingleMuzzleFlare(engine, weapon, firePointIndex);
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
                float distSq = (firePoint.x - targetLocation.x) * (firePoint.x - targetLocation.x) +
                               (firePoint.y - targetLocation.y) * (firePoint.y - targetLocation.y);
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closestIndex = i;
                }
            }
        }
        
        return closestIndex;
    }
    
    /**
     * 在指定开火点生成单个光晕
     */
    private void spawnSingleMuzzleFlare(CombatEngineAPI engine, WeaponAPI weapon, int firePointIndex) {
        Vector2f firePoint = weapon.getFirePoint(firePointIndex);
        if (firePoint == null) {
            firePoint = weapon.getLocation();
        }
        
        FlareEntity flare = createSingleFlare(engine, weapon, firePoint);
        muzzleFlares.add(flare);
    }
    
    /**
     * 在指定开火点生成单条曲线光束
     */
    private void spawnSingleCurveBeamAtFirePoint(CombatEngineAPI engine, WeaponAPI weapon, ShipAPI source, int firePointIndex) {
        // 从武器获取射程和伤害
        float range = weapon.getRange();
        float damage = weapon.getDamage().getDamage();
        DamageType damageType = weapon.getDamageType();
        float empDamage = weapon.getDamage().getFluxComponent();
        
        // 获取指定开火点位置
        Vector2f firePoint = weapon.getFirePoint(firePointIndex);
        if (firePoint == null) {
            firePoint = weapon.getLocation();
        }
        
        // 生成光束
        spawnSingleCurveBeam(engine, weapon, source, firePoint, range, damage, damageType, empDamage);
    }
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused()) {
            return;
        }
        
        // 清理已删除的光斑
        muzzleFlares.removeIf(flare -> flare == null || flare.hasDelete());
        
        // 获取所有开火点
        List<Vector2f> firePoints = weapon.getSpec().getTurretFireOffsets();
        if (firePoints == null || firePoints.isEmpty()) {
            firePoints = weapon.getSpec().getHardpointFireOffsets();
        }
        if (firePoints == null || firePoints.isEmpty()) {
            return;
        }
        
        // 更新每个光斑的位置（和角度，如果配置了跟随）
        for (int i = 0; i < muzzleFlares.size() && i < firePoints.size(); i++) {
            FlareEntity flare = muzzleFlares.get(i);
            if (flare != null && !flare.hasDelete()) {
                // 获取世界坐标系中的开火点位置
                Vector2f firePoint = weapon.getFirePoint(i);
                if (firePoint != null) {
                    if (FLARE_FOLLOW_WEAPON_ANGLE) {
                        // 跟随位置和角度
                        flare.setStateVanilla(firePoint, weapon.getCurrAngle() + FLARE_ANGLE_OFFSET);
                    } else {
                        // 只跟随位置，不改变角度
                        flare.setLocation(firePoint);
                    }
                }
            }
        }
    }
    
    /**
     * 创建单个枪口光晕特效
     * @return 返回创建的FlareEntity
     */
    private FlareEntity createSingleFlare(CombatEngineAPI engine, WeaponAPI weapon, Vector2f location) {
        FlareEntity flare = new FlareEntity();
        
        // 尺寸设置 - 参考MissionDefinition
        flare.setSize(640.0f, 16.0f);
        
        // 形状设置
        flare.setSmoothDisc();
        
        // 颜色设置 - 边缘色与光束起点颜色一致（金黄色）
        flare.setFringeColor(new Color(255, 210, 80, 255));  // 金黄色边缘
        flare.setCoreColor(Color.WHITE);                      // 白色核心
        flare.setCoreAlpha(1.0f);
        flare.setFringeAlpha(1.0f);
        
        // 混合模式 - 使用NormalBlend而不是AdditiveBlend
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
     */
    private void spawnSingleCurveBeam(CombatEngineAPI engine, WeaponAPI weapon, ShipAPI source, 
                                      Vector2f fireLoc, float range, float damage, 
                                      DamageType damageType, float empDamage) {
        float facing = weapon.getCurrAngle();
        
        NodeData start = new NodeData();
        NodeData end = new NodeData();
        
        start.setTangentRight(
            MathUtils.getRandomNumberInRange(range * 0.1f, range * 0.3f), 
            0.0f
        );
        
        end.setLocation(range, 0.0f);
        float t2 = range * 0.23f;
        end.setTangentLeft(
            -MathUtils.getRandomNumberInRange(0.0f, range * 0.7f),
            MathUtils.getRandomNumberInRange(-t2, t2)
        );
        
        // 计算光束终点颜色（用于后续粒子）
        Color beamEndColor = new Color(255, 30, 80, 255);  // 红色
        
        Pair<SegmentEntity, Vector3f> pair = CurveUtil.spawnCurveBeam(
            engine,
            new Vector3f(fireLoc.x, fireLoc.y, facing),
            start,
            end,
            range + 64.0f,
            new BeamDealt(engine, source, damage, damageType, empDamage, beamEndColor)
        );
        
        if (pair == null || pair.one == null) {
            return;
        }
        
        start = pair.one.getNodes().get(0);
        start.setEmissiveColor(new Color(255, 210, 80, 255));
        start.setWidth(24.0f);
        
        end = pair.one.getNodes().get(1);
        float curveT = pair.two == null ? 1.0f : pair.two.z;
        end.setColor(CalculateUtil.mix(Color.WHITE, new Color(240, 200, 255, 255), false, curveT));
        end.setEmissiveColor(CalculateUtil.mix(
            new Color(255, 210, 80, 255), 
            beamEndColor, 
            false, 
            curveT
        ));
        end.setWidth(CalculateUtil.mix(24.0f, 8.0f, curveT));
        
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
        
        // 使用新API（不需要BoxEnum参数）
        CombatRenderingManager.addEntity(pair.one);
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
            
            this.coreColor = new Color(255, 210, 80, 255);
            this.fringeColor = new Color(255, 30, 80, 255);
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
