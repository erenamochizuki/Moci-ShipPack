package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import data.scripts.util.Moci_ProjectileConvergenceUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import org.boxutil.define.BoxEnum;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.TrailEntity;

import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 弹道收束系统
 * 
 * 功能概述：
 * 1. 蓄力时的粒子特效（向枪口汇聚的粒子）
 * 2. 开火时的枪口闪光特效
 * 3. 弹丸收束效果（让弹丸以可控幅度向武器朝向收束）
 * 4. MSN_04_2武器的充能特效渲染
 *
 */
public class Moci_GuidingWeapon implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    
    // ==================== 收束系统参数 ====================
    
    /**
     * 弹丸收束强度（0.0-1.0）
     * 0.0 = 不收束（直线飞行）
     * 0.5 = 中等收束（默认值）
     * 1.0 = 强力收束
     * 
     * 【在这里调整收束强度】
     */
    private static final float CONVERGENCE_STRENGTH = 0.75f;

    private static final float MAX_CHARGE_LEVEL = 0.7f;

    
    // ==================== 蓄力粒子特效参数 ====================
    
    // 粒子生成的角度扩散范围（度）
    private static final float CHARGEUP_PARTICLE_ANGLE_SPREAD = 160f;
    
    // 粒子亮度（0.0-1.0）
    private static final float CHARGEUP_PARTICLE_BRIGHTNESS = 1f;
    
    // 粒子颜色
    private static final Color CHARGEUP_PARTICLE_COLOR = new Color(196, 255, 70, 255);
    
    // 每帧生成的粒子数量
    private static final int CHARGEUP_PARTICLE_COUNT_PER_FRAME = 3;
    
    // 粒子生成的最大距离（从枪口）
    private static final float CHARGEUP_PARTICLE_DISTANCE_MAX = 180f; // 增加范围
    
    // 粒子生成的最小距离（从枪口）
    private static final float CHARGEUP_PARTICLE_DISTANCE_MIN = 60f; // 增加最小距离
    
    // 粒子持续时间（秒）
    private static final float CHARGEUP_PARTICLE_DURATION = 0.4f; // 延长持续时间
    
    // 粒子最大尺寸
    private static final float CHARGEUP_PARTICLE_SIZE_MAX = 12f;
    
    // 粒子最小尺寸
    private static final float CHARGEUP_PARTICLE_SIZE_MIN = 4f;
    
    // ==================== TrailEntity光线收束参数 ====================
    
    // 每帧生成的光线数量
    private static final int TRAIL_COUNT_PER_FRAME = 1;
    
    // 光线核心颜色（更亮的黄绿色）
    private static final Color TRAIL_COLOR_CORE = new Color(255, 255, 150, 255); // 更亮的核心
    private static final Color TRAIL_COLOR_START = new Color(255, 255, 0, 100);
    private static final Color TRAIL_COLOR_END = new Color(132, 255, 70, 150);
    
    // 光线持续时间（秒）
    private static final float TRAIL_DURATION = 0.6f;
    
    // 光线生成距离范围
    private static final float TRAIL_DISTANCE_MAX = 200f; // 最大距离
    private static final float TRAIL_DISTANCE_MIN = 80f;  // 最小距离
    
    // ==================== 枪口闪光特效参数 ====================
    
    // 枪口闪光颜色
    private static final Color MUZZLE_FLASH_COLOR = new Color(221, 255, 199, 200);
    
    // 枪口闪光持续时间（秒）
    private static final float MUZZLE_FLASH_DURATION = 0.15f;
    
    // 枪口闪光尺寸
    private static final float MUZZLE_FLASH_SIZE = 25.0f;
    
    // 固定武器的枪口偏移量
    private static final float MUZZLE_OFFSET_HARDPOINT = 8f;
    
    // 炮塔武器的枪口偏移量
    private static final float MUZZLE_OFFSET_TURRET = 10f;

    // ==================== MSN_04_2特效参数 ====================
    
    // 触发特效的充能阈值
    private static final float MSN_04_2_CHARGE_THRESHOLD = 0.8f;
    
    // 特效缩小持续时间（秒）
    private static final float MSN_04_2_SHRINK_DURATION = 0.15f;
    
    // 特效尺寸
    private static final float MSN_04_2_MAX_WIDTH = 120f;
    private static final float MSN_04_2_MAX_HEIGHT = 138f;
    
    // ==================== 状态追踪变量 ====================
    
    // 上一帧的蓄力等级（用于检测蓄力变化）
    private float lastChargeLevel = 0.0f;
    
    // 上一帧的弹药数量（用于检测开火）
    private int lastWeaponAmmo = 1;
    
    // 是否已初始化渲染插件
    private boolean renderPluginInitialized = false;
    
    // TrailEntity列表（管理所有活跃的光线）
    private final List<TrailEntityWrapper> activeTrails = new ArrayList<>();


    /**
     * 每帧更新方法 - 处理蓄力粒子特效、TrailEntity光线收束和枪口闪光
     * 
     * @param amount 距离上一帧的时间（秒）
     * @param engine 战斗引擎API
     * @param weapon 武器API
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 更新TrailEntity光线
        updateTrailEntities(amount);
        
        // ==================== 初始化MSN_04_2特效渲染 ====================
        if (!renderPluginInitialized && weapon.getSpec().hasTag("Moci_MSN_04_2")) {
            renderPluginInitialized = true;
            engine.addLayeredRenderingPlugin(new MSN_04_2_ChargeRender(weapon));
        }
        
        // 获取当前蓄力等级（0.0-1.0）
        float chargeLevel = weapon.getChargeLevel();
        
        // 获取当前弹药数量
        int weaponAmmo = weapon.getAmmo();

        // ==================== 检测蓄力或开火状态 ====================
        // 如果蓄力等级增加 或 弹药减少（表示开火了）
        if ((chargeLevel > lastChargeLevel) || (weaponAmmo < lastWeaponAmmo)) {
            // 获取武器位置
            Vector2f weaponLocation = weapon.getLocation();
            
            // 获取武器所在的舰船
            ShipAPI ship = weapon.getShip();
            
            // 获取武器当前朝向角度
            float shipFacing = weapon.getCurrAngle();
            
            // 获取舰船速度向量
            Vector2f shipVelocity = ship.getVelocity();
            
            // 计算枪口位置（根据武器类型使用不同的偏移量）
            Vector2f muzzleLocation = MathUtils.getPointOnCircumference(weaponLocation,
                    weapon.getSlot().isHardpoint() ? MUZZLE_OFFSET_HARDPOINT : MUZZLE_OFFSET_TURRET, shipFacing);

            // ==================== 蓄力粒子特效 ====================
            // 每帧生成少量粒子，武器正在开火时
            if (weapon.isFiring()) {
                // 计算标准化的蓄力等级（0.0-1.0，在MAX_CHARGE_LEVEL时达到1.0）
                float normalizedChargeLevel = Math.min(chargeLevel / MAX_CHARGE_LEVEL, 1.0f);
                
                // 每帧生成固定数量的粒子
                for (int i = 0; i < CHARGEUP_PARTICLE_COUNT_PER_FRAME; ++i) {
                    // 随机距离（粒子从这个距离向枪口移动）
                    float distance = MathUtils.getRandomNumberInRange(CHARGEUP_PARTICLE_DISTANCE_MIN, CHARGEUP_PARTICLE_DISTANCE_MAX);
                    
                    // 随机尺寸（随蓄力等级增加）
                    float sizeMin = CHARGEUP_PARTICLE_SIZE_MIN * normalizedChargeLevel;
                    float sizeMax = CHARGEUP_PARTICLE_SIZE_MAX * normalizedChargeLevel;
                    float size = MathUtils.getRandomNumberInRange(sizeMin, sizeMax);
                    
                    // 随机角度偏移
                    float angle = MathUtils.getRandomNumberInRange(-0.5f * CHARGEUP_PARTICLE_ANGLE_SPREAD, 0.5f * CHARGEUP_PARTICLE_ANGLE_SPREAD);
                    
                    // 计算粒子生成位置（在枪口周围的圆形区域）
                    Vector2f spawnLocation = MathUtils.getPointOnCircumference(muzzleLocation, distance, (angle + shipFacing));
                    
                    // 随机持续时间（基础时间的0.5-1.5倍）
                    float duration = CHARGEUP_PARTICLE_DURATION * MathUtils.getRandomNumberInRange(0.5f, 1.5f);
                    
                    // 计算速度（粒子需要在持续时间内移动到枪口）
                    float speed = distance / duration;
                    
                    // 计算粒子速度向量（朝向枪口，加上舰船速度）
                    Vector2f particleVelocity = MathUtils.getPointOnCircumference(shipVelocity, speed, 180.0f + angle + shipFacing);
                    
                    // 计算粒子亮度（随蓄力等级增加）
                    float brightness = CHARGEUP_PARTICLE_BRIGHTNESS * normalizedChargeLevel;
                    
                    // 生成粒子（亮度和尺寸都随蓄力等级变化）
                    engine.addHitParticle(spawnLocation, particleVelocity, size, brightness,
                            duration, CHARGEUP_PARTICLE_COLOR);
                }
            }
            
            // ==================== TrailEntity光线收束特效 ====================
            // 每帧生成少量光线，武器正在开火时
            if (weapon.isFiring()) {
                // 计算标准化的蓄力等级
                float normalizedChargeLevel = Math.min(chargeLevel / MAX_CHARGE_LEVEL, 1.0f);
                
                // 只在蓄力达到一定程度时生成光线（避免过早出现）
                if (normalizedChargeLevel > 0.3f) {
                    spawnTrailEntities(muzzleLocation, shipFacing, normalizedChargeLevel, engine);
                }
            }

            // ==================== 枪口闪光特效 ====================
            // 如果弹药减少（表示刚刚开火）
            if (weaponAmmo < lastWeaponAmmo) {
                // 生成爆炸效果（枪口闪光）
                engine.spawnExplosion(muzzleLocation, shipVelocity, MUZZLE_FLASH_COLOR, MUZZLE_FLASH_SIZE, MUZZLE_FLASH_DURATION);
                
                // 生成平滑粒子（更大更持久的光晕）
                engine.addSmoothParticle(muzzleLocation, shipVelocity, MUZZLE_FLASH_SIZE * 3f, 1f, MUZZLE_FLASH_DURATION * 2f, MUZZLE_FLASH_COLOR);
            }
        }

        // 保存当前状态，用于下一帧比较
        lastChargeLevel = chargeLevel;
        lastWeaponAmmo = weaponAmmo;
    }

    /**
     * 开火时触发的方法 - 为弹丸添加收束效果
     * 
     * 功能说明：
     * 让弹丸以可控的幅度向武器最大射程的朝向点收束
     * 不是复杂的追踪系统，而是简单的弹道修正
     * 
     * @param projectile 发射的弹丸
     * @param weapon 发射武器
     * @param engine 战斗引擎
     */
    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (projectile == null || weapon == null) {
            return;
        }
        
        // ==================== 计算收束目标点 ====================
        // 目标点 = 武器位置 + 武器朝向上的最大射程距离
        Vector2f weaponLocation = weapon.getLocation();
        float weaponAngle = weapon.getCurrAngle();
        float weaponRange = weapon.getRange();
        
        // 计算目标收束点（武器最大射程的朝向点）
        Vector2f targetPoint = MathUtils.getPoint(weaponLocation, weaponRange, weaponAngle);
        
        // ==================== 注册弹丸到收束系统 ====================
        // 使用工具类管理弹丸的收束行为
        Moci_ProjectileConvergenceUtil.addConvergingProjectile(projectile, targetPoint, CONVERGENCE_STRENGTH);
    }
    
    // ==================== TrailEntity光线收束辅助方法 ====================
    
    /**
     * 生成TrailEntity光线
     * 
     * @param muzzleLocation 枪口位置
     * @param shipFacing 武器朝向
     * @param chargeLevel 蓄力等级（0.0-1.0）
     * @param engine 战斗引擎
     */
    private void spawnTrailEntities(Vector2f muzzleLocation, float shipFacing, float chargeLevel, CombatEngineAPI engine) {
        // 每帧生成少量光线
        for (int i = 0; i < TRAIL_COUNT_PER_FRAME; i++) {
            // 随机距离（光线从这个距离向枪口收束）
            float distance = MathUtils.getRandomNumberInRange(TRAIL_DISTANCE_MIN, TRAIL_DISTANCE_MAX);
            
            // 随机角度偏移（与粒子保持一致的扩散范围）
            float angleSpread = CHARGEUP_PARTICLE_ANGLE_SPREAD * 0.8f; // 光线扩散范围略小于粒子
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
            trail.getMaterialData().setEmissiveColor(TRAIL_COLOR_CORE);
            trail.getMaterialData().setEmissiveColorAlpha(0.4f);
            
            // 设置颜色
            trail.setStartColor(TRAIL_COLOR_START);
            trail.setEndColor(TRAIL_COLOR_END);
            
            // 设置光线宽度
            trail.setStartWidth(1.0f);
            trail.setEndWidth(5.0f);
            
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
            
            // 添加节点（相对坐标）
            // 起点在原点
            trail.addNode(new Vector2f(0, 0));
            // 终点初始位置（给一个很小的初始长度）
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
        Iterator<TrailEntityWrapper> iterator = activeTrails.iterator();
        
        while (iterator.hasNext()) {
            TrailEntityWrapper wrapper = iterator.next();
            wrapper.age += amount;
            
            // 计算进度（0.0 = 刚生成，1.0 = 到达终点）
            float progress = wrapper.age / wrapper.maxAge;
            
            if (progress >= 1.0f) {
                // 光线已到达终点并过期，删除资源
                wrapper.trail.delete();
                iterator.remove();
            } else {
                // 更新光线位置和长度
                wrapper.trail.setLocation(wrapper.startPos);
                
                // 计算当前终点位置（从起点逐渐延伸到枪口）
                Vector2f currentDirection = new Vector2f(
                    wrapper.fullDirection.x * progress,
                    wrapper.fullDirection.y * progress
                );
                
                // 获取节点列表并直接修改
                List<Vector2f> nodes = wrapper.trail.getNodes();
                if (nodes != null && nodes.size() >= 2) {
                    // 起点保持在原点，终点逐渐延伸
                    nodes.get(0).set(0, 0);
                    nodes.get(1).set(currentDirection);
                    
                    // 刷新节点
                    wrapper.trail.setNodeRefreshIndex(0);
                    wrapper.trail.setNodeRefreshSize(2);
                    wrapper.trail.submitNodes();
                }
                
                // 计算淡出效果（接近终点时淡出）
                float alpha = 1.0f;
                if (progress > 0.7f) {
                    // 最后30%的时间淡出
                    alpha = (1.0f - progress) / 0.3f;
                }
                
                // 更新透明度
                Color endColor = new Color(
                    TRAIL_COLOR_END.getRed(),
                    TRAIL_COLOR_END.getGreen(),
                    TRAIL_COLOR_END.getBlue(),
                    (int)(TRAIL_COLOR_END.getAlpha() * alpha)
                );
                
                wrapper.trail.setEndColor(endColor);
            }
        }
    }
    
    /**
     * TrailEntity包装类，用于管理光线的生命周期和动画
     */
    private static class TrailEntityWrapper {
        public final TrailEntity trail;
        public final float maxAge;
        public float age;
        public final Vector2f startPos;      // 起始位置（世界坐标）
        public final Vector2f fullDirection; // 完整方向向量（从起点到终点）
        
        public TrailEntityWrapper(TrailEntity trail, float maxAge, Vector2f startPos, Vector2f targetPos) {
            this.trail = trail;
            this.maxAge = maxAge;
            this.age = 0f;
            this.startPos = new Vector2f(startPos);
            this.fullDirection = new Vector2f(
                targetPos.x - startPos.x,
                targetPos.y - startPos.y
            );
        }
    }
    
    // ==================== MSN_04_2特效渲染系统 ====================
    
    /**
     * MSN_04_2武器的充能特效渲染插件
     */
    private static class MSN_04_2_ChargeRender extends BaseCombatLayeredRenderingPlugin {
        private final ShipAPI ship;
        private final WeaponAPI weapon;
        private final SpriteAPI sprite;
        private boolean isExpired = false;
        private final List<ChargeEffectObj> effects = new ArrayList<>();
        private boolean lastFiring = false;
        
        private MSN_04_2_ChargeRender(WeaponAPI weapon) {
            this.ship = weapon.getShip();
            this.weapon = weapon;
            
            // 设置渲染层级
            this.layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER;
            
            // 加载特效贴图
            this.sprite = Global.getSettings().getSprite("fx", "MSN_04_2_body_beam_fire");
        }
        
        @Override
        public float getRenderRadius() {
            return Math.max(MSN_04_2_MAX_WIDTH, MSN_04_2_MAX_HEIGHT) * 2f;
        }
        
        @Override
        public void advance(float amount) {
            // 更新实体位置到武器开火点
            this.entity.getLocation().set(weapon.getFirePoint(0));
            
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine.isPaused()) {
                return;
            }
            
            // 更新所有特效对象
            Iterator<ChargeEffectObj> iter = effects.iterator();
            while (iter.hasNext()) {
                ChargeEffectObj effect = iter.next();
                effect.advance(amount);
                if (effect.shouldRemove()) {
                    iter.remove();
                }
            }
            
            // 检查舰船是否存活
            if (!ship.isAlive()) {
                if (effects.isEmpty()) {
                    isExpired = true;
                }
                return;
            }
            
            // 检测充能到达阈值且开始开火
            float chargeLevel = weapon.getChargeLevel();
            boolean isFiring = weapon.isFiring();
            
            // 当充能达到阈值且刚开始开火时，创建新的特效对象
            if (chargeLevel >= MSN_04_2_CHARGE_THRESHOLD && isFiring && !lastFiring) {
                effects.add(new ChargeEffectObj(sprite));
            }
            
            lastFiring = isFiring;
        }
        
        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (this.layer == layer) {
                Vector2f point = weapon.getFirePoint(0);
                float x = point.x;
                float y = point.y;
                float angle = weapon.getCurrAngle();
                
                for (ChargeEffectObj effect : effects) {
                    effect.render(x, y, angle);
                }
            }
        }
        
        @Override
        public boolean isExpired() {
            return isExpired;
        }
    }
    
    /**
     * 充能特效对象
     */
    private static class ChargeEffectObj {
        private final SpriteAPI sprite;
        private float life;
        private static final float GROW_DURATION = 0.05f; // 快速增长到最大尺寸
        private final float totalLife;
        
        private ChargeEffectObj(SpriteAPI sprite) {
            this.sprite = sprite;
            this.totalLife = GROW_DURATION + MSN_04_2_SHRINK_DURATION;
            this.life = totalLife;
        }
        
        public void advance(float amount) {
            if (life > 0) {
                life -= amount;
                if (life < 0) {
                    life = 0;
                }
            }
        }
        
        public void render(float x, float y, float angle) {
            if (life > 0) {
                float scale;
                float elapsed = totalLife - life;
                
                if (elapsed < GROW_DURATION) {
                    // 增长阶段：从0快速增长到最大尺寸
                    scale = (elapsed / GROW_DURATION) * 3f;
                } else {
                    // 缩小阶段：从最大缩小到0
                    float shrinkProgress = (elapsed - GROW_DURATION) / MSN_04_2_SHRINK_DURATION;
                    scale = 3f * (1f - shrinkProgress);
                }
                
                // 计算实际尺寸
                float width = MSN_04_2_MAX_WIDTH * scale;
                float height = MSN_04_2_MAX_HEIGHT * scale;
                
                // 计算渲染中心点：从开火点（底边中心）向武器朝向偏移半个高度
                float offsetDistance = height / 2f;
                float renderX = x + (float) Math.cos(Math.toRadians(angle)) * offsetDistance;
                float renderY = y + (float) Math.sin(Math.toRadians(angle)) * offsetDistance;
                
                // 设置贴图尺寸和角度（按原始比例缩放）
                sprite.setWidth(width);
                sprite.setHeight(height);
                sprite.setAngle(angle - 90f);
                sprite.renderAtCenter(renderX, renderY);
            }
        }
        
        public boolean shouldRemove() {
            return life <= 0;
        }
    }
}
