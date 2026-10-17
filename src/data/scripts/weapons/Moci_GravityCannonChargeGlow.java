package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.BufferUtils;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.WaveDistortion;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import data.scripts.weapons.render.Moci_SingularityRenderer;
import data.scripts.util.Moci_Util;
import data.scripts.util.Moci_BoxBasedUtil;
import data.scripts.util.Moci_TextLoader;
import org.apache.log4j.Logger;
import java.nio.FloatBuffer;
import org.boxutil.define.BoxEnum;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.DistortionEntity;
import org.boxutil.units.standard.entity.TrailEntity;
import org.boxutil.units.standard.attribute.NodeData;

/**
 * 引力炮投射物的分层渲染与引力/潮汐/坍塌期行为。
 * 协同 {@link data.scripts.weapons.render.Moci_SingularityRenderer} 执行奇点视觉与半径/质量逻辑。
 */
public class Moci_GravityCannonChargeGlow extends BaseCombatLayeredRenderingPlugin {
    private static final String TEXT_ID = "Moci_GravityCannonChargeGlow";
    private static final Logger LOG = Global.getLogger(Moci_GravityCannonChargeGlow.class);
    
    // ========================================
    // 可调整参数区域 - 所有数值常量集中在此
    // ========================================
    
    // === 基础参数 ===
    public static float ARC_RATE_MULT = 2.0F;
    public static int MAX_ARC_RANGE = 600;
    public static float REPAIR_RATE_MULT = 0.5F;
    public static float REPAIR_RATE_DEBUFF_DUR = 10.0F;
    // 底层粒子颜色 - 蓝紫色调
    public static Color UNDERCOLOR = new Color(100, 100, 255, 200);
    public static Color RIFT_COLOR = new Color(150, 150, 255, 250);
    public static Object STATUS_KEY = new Object();
    
    // === 汇聚粒子/光线参数 ===
    // 粒子参数
    private static final Color ACCRETION_PARTICLE_COLOR = new Color(255, 255, 255, 200);
    private static final float ACCRETION_PARTICLE_SIZE_MIN = 3f;
    private static final float ACCRETION_PARTICLE_SIZE_MAX = 6f;
    private static final float ACCRETION_PARTICLE_DURATION = 0.5f;
    private static final float ACCRETION_PARTICLE_ANGLE_SPREAD = 360f;  // 全向
    
    // 光线参数
    private static final Color ACCRETION_TRAIL_CORE = new Color(255, 255, 255, 255);
    private static final Color ACCRETION_TRAIL_START = new Color(150, 150, 255, 100);
    private static final Color ACCRETION_TRAIL_END = new Color(160, 160, 255, 200);
    private static final float ACCRETION_TRAIL_WIDTH_START = 2f;
    private static final float ACCRETION_TRAIL_WIDTH_END = 6.0f;
    private static final float ACCRETION_TRAIL_DURATION = 0.6f;
    
    // 生成数量（基于黑洞半径）
    private static final int ACCRETION_PARTICLE_COUNT_MIN = 3;   // 最小粒子数/帧
    private static final int ACCRETION_PARTICLE_COUNT_MAX = 9;   // 最大粒子数/帧
    private static final int ACCRETION_TRAIL_COUNT_MIN = 1;      // 最小光线数/帧
    private static final int ACCRETION_TRAIL_COUNT_MAX = 3;      // 最大光线数/帧
    
    // 距离范围（基于黑洞半径的倍数）
    private static final float ACCRETION_DISTANCE_MIN_MULT = 20.0f;  // 最小距离半径
    private static final float ACCRETION_DISTANCE_MAX_MULT = 2.0f;  // 最大距离半径
    
    // === 引力效果参数 ===
    private static final float DAMAGE_INTERVAL = 0.1F;  // 伤害应用间隔（秒）
    private static final float EFFECT_RANGE = 600F; // 效果范围（用于扭曲效果计算）
    
    // 引力作用半径计算
    public static final float GRAVITY_RANGE_BASE = 1350f; // 基础作用半径（缩小）
    public static final float GRAVITY_RANGE_MASS_MULT = 0.8f; // 质量增长系数（降低）
    
    // 舰船引力强度
    public static final float SHIP_GRAVITY_BASE_STRENGTH = 350f; // 舰船基础引力强度
    public static final float SHIP_GRAVITY_MASS_MULT = 0.7f; // 质量放大系数
    public static final float SHIP_HULK_GRAVITY_MULT = 1.5f; // 残骸引力倍率
    
    // 舰船质量衰减（大型舰船引力效果减弱）
    public static final float SHIP_MASS_DECAY_MIN = 2000f; // 最小质量（无衰减）
    public static final float SHIP_MASS_DECAY_MAX = 15000f; // 最大质量（完全无效）
    
    // === 投射物引力参数 ===
    public static final float PROJECTILE_GRAVITY_MISSILE = 3000f; // 导弹引力强度
    public static final float PROJECTILE_GRAVITY_NORMAL = 10f; // 普通投射物引力强度
    public static final float PROJECTILE_GRAVITY_MASS_MULT = 1.2f; // 质量放大系数
    
    // 投射物切向阻尼（抑制绕行）
    public static final float PROJECTILE_TANGENT_DAMPING_THRESHOLD = 0.2f; // 阻尼触发阈值（ramp）
    public static final float PROJECTILE_TANGENT_DAMPING_MAX = 0.6f; // 最大切向速度衰减（60%）
    
    // 投射物速度上限
    public static final float PROJECTILE_MAX_SPEED = 800f; // 最大速度限制
    
    // === 导弹阻尼参数 ===
    public static final float MISSILE_DAMPING_OUTER_RANGE = 50f; // 事件界限外预警范围
    public static final float MISSILE_DAMPING_OUTER_MAX = 0.2f; // 外围最大阻尼（20%）
    public static final float MISSILE_DAMPING_INNER_MAX = 0.95f; // 界限内最大阻尼（95%）
    
    // === 投射物轨迹修正参数 ===
    // 偏转比例（按距离分段）
    public static final float TRAJECTORY_DEFLECTION_MID_THRESHOLD = 0.7f; // 中远/近距离阈值（ramp）
    public static final float TRAJECTORY_DEFLECTION_FAR_MAX = 0.6f; // 远中距离最大偏转（60%）
    public static final float TRAJECTORY_DEFLECTION_NEAR_MAX = 0.95f; // 近距离最大偏转（95%）
    
    // 转向平滑插值因子（lerp factor）
    public static final float TRAJECTORY_LERP_FAR_MIN = 0.08f; // 远距离最小lerp因子
    public static final float TRAJECTORY_LERP_FAR_MAX = 0.25f; // 远中距离最大lerp因子
    public static final float TRAJECTORY_LERP_NEAR_MAX = 0.65f; // 近距离最大lerp因子
    
    // === 事件界限EMP伤害参数 ===
    // 事件界限内EMP伤害（不再造成实际伤害）
    public static final float EVENT_HORIZON_BASE_EMP_DPS = 500f; // 基础EMP DPS
    public static final float EVENT_HORIZON_MASS_SCALE = 2.5f; // 质量缩放系数
    public static final float EVENT_HORIZON_PENETRATION_MIN = 0.4f; // 最小深入伤害倍率（40%）
    public static final float EVENT_HORIZON_PENETRATION_MAX = 0.6f; // 深入伤害增幅范围（+60%）
    public static final float EVENT_HORIZON_ARMOR_RATIO = 0.1f; // 装甲伤害比例（10%）- 保留用于兼容
    public static final float EVENT_HORIZON_HULL_RATIO = 0.9f; // 船体伤害比例（90%）- 保留用于兼容
    
    // 舰载机/残骸移除阈值
    public static final float FIGHTER_HULK_REMOVE_PENETRATION = 0.8f; // 80%深入后移除
    public static final float ABSORBED_MASS_RATIO = 0.25f; // 吸收质量=船体×25%
    
    // === 潮汐撕扯EMP伤害参数 ===
    public static final float TIDAL_BASE_EMP_DPS = 200f; // 基础潮汐EMP DPS
    public static final float TIDAL_MAX_EMP_DPS = 500f; // 最大潮汐EMP DPS
    public static final float TIDAL_MASS_MULT = 0.6f; // 基础质量倍率
    public static final float TIDAL_MASS_SCALE = 0.5f; // 质量缩放系数
    public static final float TIDAL_ARMOR_RATIO = 0.8f; // 装甲伤害比例（80%）- 保留用于兼容
    public static final float TIDAL_HULL_RATIO = 0.2f; // 船体伤害比例（20%）- 保留用于兼容
    
    // 潮汐伤害：舰船尺寸乘数（越小越低，越大越高）
    private static final float TIDAL_SIZE_MULT_FIGHTER = 0.5f;
    private static final float TIDAL_SIZE_MULT_FRIGATE = 0.7f;
    private static final float TIDAL_SIZE_MULT_DESTROYER = 1f;
    private static final float TIDAL_SIZE_MULT_CRUISER = 1.25f;
    private static final float TIDAL_SIZE_MULT_CAPITAL = 1.5f;
    
    // === 坍塌阶段参数 ===
    private static final float PULSE_ARC_MAX_DEG = 160f; // 脉冲最大弧度
    private static final float PULSE_EXPAND_TIME = 1.0f;  // 启动/展开
    private static final float PULSE_HOLD_TIME = 0f;    // 维持
    private static final float PULSE_DECAY_TIME = 1.0f;   // 衰竭
    private static final int   PULSE_RAY_COUNT = 48;      // 射线数量
    private static final int   PULSE_COUNT = 5;           // 脉冲次数
    
    // 坍塌完成：360°静态光幕参数
    private static final float FINAL_RING_DURATION = 0f;    // 维持
    private static final float FINAL_RING_FADE_TIME = 1.0f;   // 尾端淡出
    private static final float FINAL_RING_THICKNESS = 140f;   // 视觉厚度
    
    // 连续EMP伤害参数
    private static final float PULSE_BASE_EMP_DPS = 200f;      // 脉冲基础EMP DPS
    private static final float RING_BASE_EMP_DPS = 1200f;      // 光幕基础EMP DPS
    private static final float MAX_DAMAGE_LEN = 1000000f;  // 伤害检测最大长度
    private static final int   RING_SEGMENTS = 288;        // 渲染细分
    
    // ========================================
    // 内部变量区域
    // ========================================
    
    protected WeaponAPI weapon;
    protected DamagingProjectileAPI proj;
    protected IntervalUtil interval = new IntervalUtil(0.1F, 0.2F);
    protected IntervalUtil arcInterval = new IntervalUtil(0.17F, 0.23F);
    // 引力效果定时器
    protected IntervalUtil gravityInterval = new IntervalUtil(DAMAGE_INTERVAL, DAMAGE_INTERVAL);
    // 视觉扭曲效果定时器
    protected float effectTimer = 0f;
    
    // 汇聚粒子/光线定时器
    protected IntervalUtil accretionInterval = new IntervalUtil(0.05f, 0.1f);
    protected final List<AccretionTrailWrapper> activeAccretionTrails = new ArrayList<>();
    
    protected float delay = 1.0F;
    
    // 引擎对象
    protected CombatEngineAPI engine;
    
    // 视觉扭曲效果渲染器
    protected DistortionRenderer distortionRenderer;
    private boolean distortionRendererRegistered = false;
    
    // 是否启用ShaderLib扭曲效果
    protected boolean useShaderDistortion = false;
    // 脉冲边缘特效节流
    protected IntervalUtil pulseEdgeInterval = new IntervalUtil(0.08f, 0.12f);
    
    // 开关：坍塌阶段与能量流（默认关闭）
    private boolean collapseEffectsEnabled = false;
    
    // 坍塌阶段：脉冲喷发
    private final List<CollapsePulse> activePulses = new ArrayList<CollapsePulse>();
    private boolean collapseStarted = false;
    private int pulsesTriggeredByTime = 0;
    private float pulseCooldown = 0f;
    
    // 坍塌完成：360°静态光幕
    private boolean finalRingActive = false;
    private float finalRingAge = 0f;
    private float finalRingDuration = FINAL_RING_DURATION;
    private float finalRingFadeTime = FINAL_RING_FADE_TIME;
    private float finalRingStartRadius = 0f;
    private float finalRingThickness = FINAL_RING_THICKNESS;
    private float finalRingGrowthSpeed = 0f;
    private int finalRingSourceOwner = -1;
    private Vector2f finalRingCenter = new Vector2f();
    
    // 黑洞消失扩散效果
    private boolean dissipationTriggered = false;
    private DistortionEntity dissipationDistortion = null;
    private Vector2f dissipationCenter = new Vector2f(); // 保存扩散中心位置
    private float dissipationTimer = 0f;
    private float dissipationExpandDuration = 2f; // 动态扩散时间（fadeIn阶段，根据范围计算）
    private static final float DISSIPATION_BASE_EXPAND = 1.0f; // 基础扩散时间
    private static final float DISSIPATION_EXPAND_PER_1000 = 0.25f; // 每1000范围增加的扩散时间
    private static final float DISSIPATION_FADE_OUT = 0.5f; // 淡出时间（fadeOut阶段）
    private static final float DISSIPATION_POWER = 0.6f;    // 扭曲强度
    private static final float DISSIPATION_DAMAGE_MULT = 5.0f; // 伤害倍率
    
    public Moci_GravityCannonChargeGlow(WeaponAPI weapon) {
        this.weapon = weapon;
        this.arcInterval = new IntervalUtil(0.17F, 0.23F);
        this.delay = 0.5F;
        this.layer = CombatEngineLayers.ABOVE_SHIPS_LAYER;
        this.engine = Global.getCombatEngine();
        
        // 每个 Glow 插件独立持有一个 DistortionRenderer 实例，避免多投射物竞争同一实例
        this.distortionRenderer = new DistortionRenderer();
        Global.getCombatEngine().addLayeredRenderingPlugin(distortionRenderer);
        this.distortionRendererRegistered = true;
        
        // 检查是否启用了ShaderLib
        this.useShaderDistortion = Global.getSettings().getModManager().isModEnabled("shaderLib");
        LOG.info("[GRAVITY_CANNON_DEBUG] Initialized - weapon: " + weapon.getId() + ", ShaderLib: " + this.useShaderDistortion);
    }

    public void attachToProjectile(DamagingProjectileAPI proj) {
        this.proj = proj;
        LOG.info("[GRAVITY_CANNON_DEBUG] Projectile attached: " + (proj != null ? proj.getProjectileSpecId() : "NULL"));
    }

    public boolean isCollapseEffectsEnabled() {
        return collapseEffectsEnabled;
    }

    public void setCollapseEffectsEnabled(boolean enabled) {
        this.collapseEffectsEnabled = enabled;
        if (!enabled) {
            activePulses.clear();
            finalRingActive = false;
        }
        try {
            Moci_SingularityRenderer.setCollapseEnabled(enabled);
        } catch (Throwable ignore) {}
    }

    @Override
    public void advance(float amount) {
        if (!engine.isPaused()) {
            // 只有投射物存在时才更新位置和效果
            if (this.proj == null) {
                // 武器充能阶段，不应用任何黑洞效果
                distortionRenderer.setActive(false);
                return;
            }
            
            // 获取当前位置
            Vector2f currentPosition = this.proj.getLocation();
            this.entity.getLocation().set(currentPosition);
            
            // 更新视觉扭曲效果
            distortionRenderer.setCenter(currentPosition);
            // 开关关闭：清理坍塌视觉状态
            if (!collapseEffectsEnabled) {
                finalRingActive = false;
                if (!activePulses.isEmpty()) activePulses.clear();
            }
            // 更新最终光幕与脉冲
            if (finalRingActive) {
                finalRingAge += amount;
                if (finalRingAge >= finalRingDuration) {
                    finalRingActive = false;
                }
            }
            if (!activePulses.isEmpty()) {
                for (int i = activePulses.size() - 1; i >= 0; i--) {
                    CollapsePulse p = activePulses.get(i);
                    p.age += amount;
                    if (!p.isActive()) { activePulses.remove(i); pulseCooldown = Math.max(pulseCooldown, 0.5f); }
                }
                // 边缘能量流形变：定期为边缘添加短寿命射线爆（仅在开启时）
                if (collapseEffectsEnabled) {
                    pulseEdgeInterval.advance(amount);
                    if (pulseEdgeInterval.intervalElapsed() && distortionRenderer != null) {
                        int owner = weapon.getShip().getOwner();
                        for (CollapsePulse p : activePulses) {
                            float arc = p.getCurrentArcDeg();
                            if (arc <= 0f) continue;
                            float half = arc * 0.5f;
                            float angL = p.angleDeg - half;
                            float angR = p.angleDeg + half;
                            float len = MAX_DAMAGE_LEN;
                            float life = 0.18f;
                            float width = 32f;
                            distortionRenderer.addBeamBurst(p.origin, angL, len, life, width, owner, 1f);
                            distortionRenderer.addBeamBurst(p.origin, angR, len, life, width, owner, 1f);
                        }
                    }
                }
            }
            // 推进喷发间隔冷却
            if (pulseCooldown > 0f) pulseCooldown = Math.max(0f, pulseCooldown - amount);
            
            boolean collapsing = Moci_SingularityRenderer.getOrCreate().isCollapsing(this.proj);
            boolean collapsed = Moci_SingularityRenderer.getOrCreate().isCollapsed(this.proj);
            boolean evaporating = Moci_SingularityRenderer.getOrCreate().isEvaporating(this.proj);
            boolean projectileExpired = isProjectileExpired(this.proj);
            
            // 检测黑洞消失并触发扩散效果
            // 在蒸发阶段开始时触发，而不是等待完全过期
            if (!dissipationTriggered && !collapsing && !collapsed) {
                // 触发条件：进入蒸发阶段 或 投射物已过期
                if (evaporating || projectileExpired) {
                    // 在触发扩散效果时，彻底清理distortionRenderer
                    if (distortionRenderer != null) {
                        distortionRenderer.setActive(false);
                        // 强制清空所有扭曲点，避免残留
                        distortionRenderer.forceCleanup();
                    }
                    triggerDissipationEffect(currentPosition);
                }
            }
            
            // 更新扩散效果
            if (dissipationDistortion != null) {
                dissipationTimer += amount;
                updateDissipationEffect(amount);
            }
            
            // 只有投射物存在且未过期时才应用引力效果
            boolean shouldApplyGravity = !projectileExpired || (collapseEffectsEnabled && (collapsing || collapsed));
            
            if (shouldApplyGravity) {
                // 禁用本地扭曲视觉，仅执行引力与潮汐逻辑
                distortionRenderer.setActive(false);

                // 应用引力/喷发效果：允许在坍塌/坍塌完成后继续推进喷发逻辑
                this.gravityInterval.advance(amount);
                if (this.gravityInterval.intervalElapsed()) {
                    this.applyGravityEffects();
                }
                
                // 生成汇聚粒子和光线效果
                this.accretionInterval.advance(amount);
                if (this.accretionInterval.intervalElapsed()) {
                    spawnAccretionEffects(currentPosition);
                }
            } else {
                distortionRenderer.setActive(false);
            }
            
            // 更新汇聚光线
            updateAccretionTrails(amount);

            // 不生成 EMP 弧
            /*
            if (this.proj != null) {
                Global.getSoundPlayer().playLoop("realitydisruptor_loop", this.proj, 1.0F, 1.0F * this.proj.getBrightness(), this.proj.getLocation(), this.proj.getVelocity());
            }
            */
        }
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        // 渲染由引擎自动处理，不需要额外代码
    }

    @Override
    public boolean isExpired() {
        // 投射物不存在时，检查是否还有需要完成的效果
        if (this.proj == null) {
            // 如果有扩散效果正在进行，等待其完成
            if (dissipationDistortion != null) {
                return false;
            }
            // 否则可以回收
            disposeDistortionRenderer();
            return true;
        }
        
        // 投射物存在时的判定
        boolean collapsing = Moci_SingularityRenderer.getOrCreate().isCollapsing(this.proj);
        boolean collapsed = Moci_SingularityRenderer.getOrCreate().isCollapsed(this.proj);
        boolean visualsActive = finalRingActive || !activePulses.isEmpty();
        boolean projectileAlive = !isProjectileExpired(this.proj) && !this.proj.isFading();
        boolean dissipationActive = dissipationDistortion != null;
        
        // 只要仍在充能、或弹体仍活、或（在开关开启时）处于坍塌并有视觉、或扩散效果正在进行，就不回收
        if (projectileAlive || dissipationActive || 
            ((collapseEffectsEnabled && collapsing) && visualsActive) || 
            ((collapseEffectsEnabled && collapsed) && visualsActive)) {
            return false;
        }
        
        // 其余情况允许回收
        boolean shouldExpire = this.weapon == null || this.weapon.getShip() == null || 
                               !this.weapon.getShip().isAlive() || 
                               (!collapsing && !collapsed && !visualsActive);
        
        if (shouldExpire) {
            disposeDistortionRenderer();
        }
        
        return shouldExpire;
    }

    @Override
    public float getRenderRadius() {
        // 渲染距离设为极大，避免被视口裁剪
        return 1000000.0F;
    }

    public void spawnArc() {
        // 禁用：保留方法以兼容，但不执行任何弧光创建
    }

    public Vector2f pickNoTargetDest(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        float range = 200.0F;
        Vector2f from = projectile.getLocation();
        Vector2f dir = Misc.getUnitVectorAtDegreeAngle((float)Math.random() * 360.0F);
        dir.scale(range);
        Vector2f.add(from, dir, dir);
        dir = Misc.getPointWithinRadius(dir, range * 0.25F);
        return dir;
    }

    public CombatEntityAPI findTarget(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        float range = (float)MAX_ARC_RANGE;
        Vector2f from = projectile.getLocation();
        Iterator<Object> iter = Global.getCombatEngine().getAllObjectGrid().getCheckIterator(from, range * 2.0F, range * 2.0F);
        int owner = projectile.getSource().getOwner();
        CombatEntityAPI best = null;
        float minScore = Float.MAX_VALUE;

        while(true) {
            CombatEntityAPI other;
            ShipAPI otherShip;
            do {
                do {
                    Object o;
                    do {
                        if (!iter.hasNext()) {
                            return best;
                        }
                        o = iter.next();
                    } while(!(o instanceof MissileAPI) && !(o instanceof ShipAPI));
                    other = (CombatEntityAPI)o;
                } while(other.getOwner() == owner);
                
                if (!(other instanceof ShipAPI)) {
                    break;
                }
                otherShip = (ShipAPI)other;
            } while(otherShip.isHulk() || otherShip.isPhased() || !otherShip.isTargetable());

            if (other.getCollisionClass() != CollisionClass.NONE) {
                float radius = Misc.getTargetingRadius(from, other, false);
                float dist = Misc.getDistance(from, other.getLocation()) - radius - 50.0F;
                if (!(dist > range) && dist < minScore) {
                    minScore = dist;
                    best = other;
                }
            }
        }
    }

    public void addChargingParticles() {
        // 获取当前位置 - 确保获取正确的位置
        Vector2f location = this.proj != null ? new Vector2f(this.proj.getLocation()) : new Vector2f(this.weapon.getFirePoint(0));
        CombatEngineAPI engine = Global.getCombatEngine();
        
        LOG.info("[GRAVITY_CANNON_DEBUG] addChargingParticles called at: " + location);
        
        Color darkColor = getColorForDarkening(RIFT_COLOR);
        
        // 基础尺寸参数
        float size = 50.0F;
        // UNDERCOLOR粒子的尺寸 - 增加范围
        float underSize = 120.0F; // 原为75.0F，增加了范围
        
        // 生命周期参数
        float out = 0.75F;
        out *= 3.0F;
        
        if (isWeaponCharging(weapon)) {
            size *= 0.25F + weapon.getChargeLevel() * 0.75F;
        }

        // 添加暗色粒子 - 使用引擎的addNegativeNebulaParticle
        Vector2f vel = new Vector2f(0, 0);
        
        engine.addNegativeNebulaParticle(
            new Vector2f(location), 
            vel, 
            size * 1.0F, 
            2.0F, // 膨胀系数
            0.25F, 
            0.0F, 
            out, 
            darkColor
        );
        
        if (this.proj != null) {
            Vector2f dir = Misc.getUnitVectorAtDegreeAngle(this.proj.getFacing() + 180.0F);
            Vector2f offset;
            Vector2f particleLoc;
            
            if (this.proj.getElapsed() > 0.2F) {
                particleLoc = new Vector2f(location);
                offset = new Vector2f(dir);
                offset.scale(size * 0.6F + (float)Math.random() * 0.2F);
                Vector2f.add(particleLoc, offset, particleLoc);
                
                engine.addNegativeNebulaParticle(
                    new Vector2f(particleLoc), 
                    vel, 
                    size * 1.0F, 
                    2.0F, 
                    0.25F, 
                    0.0F, 
                    out * 1.5F, 
                    darkColor
                );
            }

            if (this.proj.getElapsed() > 0.4F) {
                particleLoc = new Vector2f(location);
                offset = new Vector2f(dir);
                offset.scale(size * 1.2F + (float)Math.random() * 0.2F);
                Vector2f.add(particleLoc, offset, particleLoc);
                
                engine.addNegativeNebulaParticle(
                    new Vector2f(particleLoc), 
                    vel, 
                    size * 1.0F, 
                    2.0F, 
                    0.25F, 
                    0.0F, 
                    out * 1.3F, 
                    darkColor
                );
            }

            if (this.proj.getElapsed() > 0.6F) {
                particleLoc = new Vector2f(location);
                offset = new Vector2f(dir);
                offset.scale(size * 1.6F + (float)Math.random() * 0.2F);
                Vector2f.add(particleLoc, offset, particleLoc);
                
                engine.addNegativeNebulaParticle(
                    new Vector2f(particleLoc), 
                    vel, 
                    size * 0.8F, 
                    2.0F, 
                    0.25F, 
                    0.0F, 
                    out * 1.1F, 
                    darkColor
                );
            }

            if (this.proj.getElapsed() > 0.8F) {
                particleLoc = new Vector2f(location);
                offset = new Vector2f(dir);
                offset.scale(size * 2.0F + (float)Math.random() * 0.2F);
                Vector2f.add(particleLoc, offset, particleLoc);
                
                engine.addNegativeNebulaParticle(
                    new Vector2f(particleLoc), 
                    vel, 
                    size * 0.8F, 
                    2.0F, 
                    0.25F, 
                    0.0F, 
                    out * 1.1F, 
                    darkColor
                );
            }
        }

        // 添加底层粒子 - 使用引擎的addSmoothParticle
        // 增加持续时间和范围
        float underDuration = 3F; // 增加持续时间，原先约为1.5F
        
        engine.addSmoothParticle(
            new Vector2f(location), 
            new Vector2f(0, 0), 
            underSize, 
            1.0F, // 亮度
            underDuration, 
            UNDERCOLOR
        );
        
        // 添加更多UNDERCOLOR粒子，增加视觉效果
        for (int i = 0; i < 3; i++) {
            Vector2f randomOffset = Misc.getPointWithinRadius(new Vector2f(0, 0), underSize * 0.5F);
            Vector2f particleLoc = new Vector2f(location);
            Vector2f.add(particleLoc, randomOffset, particleLoc);
            
            engine.addSmoothParticle(
                particleLoc, 
                new Vector2f(randomOffset.x * 0.05F, randomOffset.y * 0.05F), 
                underSize * (0.5F + 0.3F * (float)Math.random()), 
                0.8F + 0.4F * (float)Math.random(), 
                underDuration * (0.8F + 0.4F * (float)Math.random()), 
                UNDERCOLOR
            );
        }
    }

    public void spawnEMPParticles(ArcHitType type, Vector2f point, CombatEntityAPI target) {
        CombatEngineAPI engine = Global.getCombatEngine();
        Color color = getColorForDarkening(RIFT_COLOR);
        float size = 30.0F;
        float baseDuration = 1.5F * 2.0F;
        Vector2f vel = new Vector2f();
        int numNegative = 5;
        switch (type) {
            case SOURCE:
                size = 40.0F;
                numNegative = 10;
                break;
            case DEST:
                size = 50.0F;
                vel.set(target.getVelocity());
                if (vel.length() > 100.0F) {
                    vel.scale(100.0F / vel.length());
                }
            case DEST_NO_TARGET:
        }

        Vector2f dir = Misc.getUnitVectorAtDegreeAngle(this.proj.getFacing() + 180.0F);

        float dur;
        Vector2f pt;
        for(int i = 0; i < numNegative; ++i) {
            dur = baseDuration + baseDuration * (float)Math.random();
            float nSize = size;
            if (type == ArcHitType.SOURCE) {
                nSize = size * 1.5F;
            }

            pt = Misc.getPointWithinRadius(point, nSize * 0.5F);
            Vector2f v = Misc.getUnitVectorAtDegreeAngle((float)Math.random() * 360.0F);
            v.scale(nSize + nSize * (float)Math.random() * 0.5F);
            v.scale(0.2F);
            float endSizeMult = 2.0F;
            if (type == ArcHitType.SOURCE) {
                pt = Misc.getPointWithinRadius(point, nSize * 0.0F);
                Vector2f offset = new Vector2f(dir);
                offset.scale(size * 0.2F * (float)i);
                Vector2f.add(pt, offset, pt);
                endSizeMult = 1.5F;
                v.scale(0.5F);
            }

            Vector2f.add(vel, v, v);
            float maxSpeed = nSize * 1.5F * 0.2F;
            float minSpeed = nSize * 1.0F * 0.2F;
            float overMin = v.length() - minSpeed;
            if (overMin > 0.0F) {
                float durMult = 1.0F - overMin / (maxSpeed - minSpeed);
                if (durMult < 0.1F) {
                    durMult = 0.1F;
                }

                dur *= 0.5F + 0.5F * durMult;
            }

            engine.addNegativeNebulaParticle(pt, v, nSize * 1.0F, endSizeMult, 0.25F / dur, 0.0F, dur, color);
        }

        float durSwirly = 0.5F / baseDuration;
        color = UNDERCOLOR;

        for(int i = 0; i < 7; ++i) {
            pt = new Vector2f(point);
            pt = Misc.getPointWithinRadius(pt, size * 1.0F);
            float s = size * 4.0F * (0.5F + (float)Math.random() * 0.5F);
            engine.addSwirlyNebulaParticle(pt, vel, s, 1.5F, durSwirly, 0.0F, durSwirly, color, false);
        }
    }

    // 辅助方法
    private static boolean isProjectileExpired(DamagingProjectileAPI proj) {
        return proj.isExpired() || proj.didDamage() || !Global.getCombatEngine().isEntityInPlay(proj);
    }

    private static boolean isWeaponCharging(WeaponAPI weapon) {
        return weapon.getChargeLevel() > 0.0F && weapon.getCooldownRemaining() <= 0.0F;
    }
    
    private static Color getColorForDarkening(Color color) {
        int r = color.getRed();
        int g = color.getGreen();
        int b = color.getBlue();
        int a = Math.min(255, color.getAlpha() + 50);
        return new Color(r, g, b, a);
    }

    // 潮汐伤害尺寸乘数
    private static float getTidalDamageSizeMult(ShipAPI ship) {
        if (ship == null) return 1f;
        ShipAPI.HullSize hs = ship.getHullSize();
        if (hs == null) return 1f;
        switch (hs) {
            case FIGHTER: return TIDAL_SIZE_MULT_FIGHTER;
            case FRIGATE: return TIDAL_SIZE_MULT_FRIGATE;
            case DESTROYER: return TIDAL_SIZE_MULT_DESTROYER;
            case CRUISER: return TIDAL_SIZE_MULT_CRUISER;
            case CAPITAL_SHIP: return TIDAL_SIZE_MULT_CAPITAL;
            default: return 1f;
        }
    }
    
    // EMP弧光命中类型
    public enum ArcHitType {
        SOURCE,
        DEST,
        DEST_NO_TARGET
    }

    // 修复速率减益效果
    public static class RDRepairRateDebuff implements AdvanceableListener {
        public static String DEBUFF_ID = "gravity_cannon_repair_debuff";
        public ShipAPI ship;
        public float dur;

        public RDRepairRateDebuff(ShipAPI ship) {
            this.dur = REPAIR_RATE_DEBUFF_DUR;
            this.ship = ship;
            ship.getMutableStats().getCombatEngineRepairTimeMult().modifyMult(DEBUFF_ID, 1.0F / REPAIR_RATE_MULT);
            ship.getMutableStats().getCombatWeaponRepairTimeMult().modifyMult(DEBUFF_ID, 1.0F / REPAIR_RATE_MULT);
        }

        public void resetDur() {
            this.dur = REPAIR_RATE_DEBUFF_DUR;
        }

        public void advance(float amount) {
            this.dur -= amount;
            if (Global.getCurrentState() == GameState.COMBAT && Global.getCombatEngine() != null && Global.getCombatEngine().getPlayerShip() == this.ship) {
                Global.getCombatEngine().maintainStatusForPlayerShip(STATUS_KEY,
                        Global.getSettings().getSpriteName("ui", "icon_tactical_reality_disruptor"),
                        Moci_TextLoader.getText(TEXT_ID, "status.title"),
                        Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.repair_debuff",
                                Moci_TextLoader.mapOf("seconds", String.valueOf(Math.max(1, Math.round(this.dur))))),
                        true);
            }

            if (this.dur <= 0.0F) {
                this.ship.removeListener(this);
                this.ship.getMutableStats().getCombatEngineRepairTimeMult().unmodify(DEBUFF_ID);
                this.ship.getMutableStats().getCombatWeaponRepairTimeMult().unmodify(DEBUFF_ID);
            }
        }
    }

    /**
     * 应用引力效果
     */
    public void applyGravityEffects() {
        if (this.proj == null || this.proj.isExpired() || !Global.getCombatEngine().isEntityInPlay(this.proj)) {
            // 投射物过期或不在战场，不再注册黑洞
            return;
        }

        Vector2f center = this.proj.getLocation();
        try {
            Vector2f frozen = Moci_SingularityRenderer.getOrCreate().getCenterWorld(this.proj);
            if (frozen != null) center = frozen;
        } catch (Throwable ignore) { }
        ShipAPI source = this.proj.getSource();
        int sourceOwner = source != null ? source.getOwner() : this.weapon.getShip().getOwner();
        
        // 向光束弯曲渲染器注册黑洞位置和半径
        // 光束弯曲功能已移除

         // 添加扭曲效果
         if (useShaderDistortion) {
             addGravityDistortionEffect(center);
         }
 
         // 坍塌过程中的喷发：顺序触发 5 次；每次结束后需间隔 0.5s 再开始下一次（受开关控制）
         if (collapseEffectsEnabled && Moci_SingularityRenderer.getOrCreate().isCollapsing(this.proj)) {
             if (!collapseStarted) { collapseStarted = true; pulsesTriggeredByTime = 0; pulseCooldown = 0f; }
             // 更新现有脉冲年龄，并清理完成的
             for (int i = activePulses.size() - 1; i >= 0; i--) {
                 CollapsePulse p = activePulses.get(i);
                 p.age += DAMAGE_INTERVAL;
                 if (!p.isActive()) { activePulses.remove(i); pulseCooldown = Math.max(pulseCooldown, 0.5f); }
             }
             // 冷却时间推进
             if (pulseCooldown > 0f) pulseCooldown = Math.max(0f, pulseCooldown - DAMAGE_INTERVAL);
             // 若没有正在进行的脉冲、未达 5 次、且冷却为 0，则启动下一次
             if (activePulses.isEmpty() && pulsesTriggeredByTime < PULSE_COUNT && pulseCooldown <= 0f) {
                 float ang = (float)(Math.random() * 360f);
                 CollapsePulse pulse = new CollapsePulse(center, ang, PULSE_ARC_MAX_DEG, PULSE_EXPAND_TIME, PULSE_HOLD_TIME, PULSE_DECAY_TIME, PULSE_RAY_COUNT, sourceOwner);
                 activePulses.add(pulse);
                 pulsesTriggeredByTime++;
             }
             // 为处于活动状态的脉冲应用持续伤害
             if (!activePulses.isEmpty()) {
                 applyPulseContinuousDamage(activePulses, center, sourceOwner);
             }
         } else {
             // 非坍塌状态，重置调度状态
             collapseStarted = false;
             pulsesTriggeredByTime = 0;
             pulseCooldown = 0f;
         }

         // 坍塌完成：全方位喷发一次（受开关控制）
         if (collapseEffectsEnabled && Moci_SingularityRenderer.getOrCreate().isCollapsed(this.proj)) {
            String token = Integer.toHexString(System.identityHashCode(this.proj));
            String key = "moci_singularity_erupt_full_" + token;
            if (Global.getCombatEngine().getCustomData().get(key) == null) {
                Global.getCombatEngine().getCustomData().put(key, Boolean.TRUE);
                LOG.info("[MOCI_SINGULARITY] collapse full ring trigger");
                 // 启动360°无间断光幕
                 finalRingActive = true;
                 finalRingAge = 0f;
                 try {
                     Vector2f frozenC = Moci_SingularityRenderer.getOrCreate().getCenterWorld(this.proj);
                     if (frozenC != null) finalRingCenter.set(frozenC); else finalRingCenter.set(center);
                 } catch (Throwable ex) {
                     finalRingCenter.set(center);
                 }
                 finalRingSourceOwner = sourceOwner;
             }
             // 对光幕应用持续伤害
             if (finalRingActive) {
                 applyFinalRingContinuousDamage(finalRingCenter, sourceOwner);
             }
         }

         // 坍塌阶段与坍塌完成后：停止持续引力与事件视界内持续伤害，仅保留喷发（仅在开启时拦截）
         if (collapseEffectsEnabled && (Moci_SingularityRenderer.getOrCreate().isCollapsing(this.proj) || Moci_SingularityRenderer.getOrCreate().isCollapsed(this.proj))) {
             return;
         }

        // 质量基准与尺度
        float massValue = Moci_SingularityRenderer.getOrCreate().getMassValue(this.proj);
        float mHat = Math.max(0f, massValue) / 10000f;
        // 吞噬半径（世界）
        float rsWorld = Moci_SingularityRenderer.getOrCreate().getEventHorizonRadiusWorld(this.proj);
        float rSoft = rsWorld * 0.6f;
        // 作用半径（增强，并随质量增长更显著）
        float R_world = GRAVITY_RANGE_BASE * (1f + GRAVITY_RANGE_MASS_MULT * (float)Math.sqrt(mHat));

        // 使用Grid遍历优化性能（只检查作用范围内的舰船）
        List<ShipAPI> ships = Moci_Util.getShipsInRange(center, R_world);
        if (ships.isEmpty()) return;

        for (ShipAPI target : ships) {
            if (target == null) continue;
            // 过滤逃生舱
            if (target.isShuttlePod()) continue;
            // 过滤无敌单位（伤害倍率为0的单位）
            if (target.getMutableStats().getHullDamageTakenMult().getModifiedValue() <= 0f) continue;

            float distance = Misc.getDistance(center, target.getLocation());
            if (distance > R_world) continue;

            boolean isHulk = target.isHulk();
            boolean isFighter = target.isFighter();
            boolean isAlive = target.isAlive();
            int owner = target.getOwner();

            // 牵拉规则：
            // - 完好舰船（非hulk且存活）：仅牵拉敌舰
            // - 残骸/碎片（hulk）：不分敌我牵拉
            // - 舰载机：仅在进入视界时删除；牵拉只对敌方可选（为一致性，按完好舰船规则）
            boolean applyPull = false;
            if (isHulk) {
                applyPull = true; // 残骸统一牵拉
            } else if (isAlive) {
                applyPull = owner != sourceOwner; // 完好仅敌舰
            }

            if (applyPull) {
                float ratio = R_world / (distance + rSoft);
                float K = SHIP_GRAVITY_BASE_STRENGTH;
                float aM = SHIP_GRAVITY_MASS_MULT;
                float dv = K * (1f + aM * (float)Math.sqrt(mHat)) * ratio * ratio * DAMAGE_INTERVAL;
                // 对残骸提高牵拉强度，使其更快清场
                if (isHulk) dv *= SHIP_HULK_GRAVITY_MULT;
                applyGravitationalForce(target, center, dv);
                
                // 记录受影响的舰船（用于黑洞结束时的速度阻尼）
                try {
                    Moci_SingularityRenderer.getOrCreate().recordAffectedShip(this.proj, target);
                } catch (Throwable ignore) {}
            }

            // 事件视界处理
            float eventHorizon = rsWorld;
            // 触碰事件视界的判定：舰船边缘触碰到视界即开始受伤
            float distanceToEdge = distance - target.getCollisionRadius(); // 舰船外缘到黑洞中心的距离
            boolean touchingHorizon = distanceToEdge <= eventHorizon;
            
            if (touchingHorizon) {
                // 计算深入事件视界的程度：0(刚触碰) → 1(完全进入)
                float penetration = Math.max(0f, Math.min(1f, (eventHorizon - distanceToEdge) / Math.max(1f, target.getCollisionRadius() * 2f)));
                
                // 舰载机与残骸：完全进入后移除（不分敌我），质量增长=完整舰船结构的1/5
                if ((isFighter || isHulk) && penetration >= FIGHTER_HULK_REMOVE_PENETRATION) {
                    float absorbedMass = Math.max(0f, target.getMaxHitpoints()) * ABSORBED_MASS_RATIO;
                    Moci_SingularityRenderer.getOrCreate().addMass(this.proj, absorbedMass);
                    engine.removeEntity(target);
                    continue;
                }

                // 完好舰船：事件界限内造成强大的EMP伤害，穿透护盾
                // 伤害随深入程度增强
                if (isAlive && owner != sourceOwner) {
                    float baseEmpDps = EVENT_HORIZON_BASE_EMP_DPS;
                    float empScale = 1f + EVENT_HORIZON_MASS_SCALE * (float)Math.sqrt(mHat);
                    // 伤害随深入程度增强
                    float penetrationMultiplier = EVENT_HORIZON_PENETRATION_MIN + EVENT_HORIZON_PENETRATION_MAX * penetration;
                    float empDps = baseEmpDps * empScale * penetrationMultiplier;
                    float empDamagePerTick = empDps * DAMAGE_INTERVAL;

                    // 事件界限内：造成纯EMP伤害，微量实际伤害（1点）
                    engine.applyDamage(target, target.getLocation(), 1f, DamageType.ENERGY, empDamagePerTick, true, false, source, true);
                    
                    // 吸收质量按造成的EMP伤害计入
                    Moci_SingularityRenderer.getOrCreate().addMass(this.proj, empDamagePerTick * 0.5f);
                }
            } else {
                // 外围潮汐撕扯：对处于引力作用半径内但未进入事件界限的舰船
                // 产生轻微的EMP伤害，穿透护盾
                float outerEdge = distance - target.getCollisionRadius(); // 舰体外缘到中心的距离
                float span = Math.max(1e-3f, R_world - rsWorld);
                float rFactor = 1f - Math.min(1f, Math.max(0f, (outerEdge - rsWorld) / span)); // R_world->0, rs->1
                if (rFactor > 0f && applyPull) {
                    // 仅对敌方活体生效（残骸不受潮汐伤害）
                    boolean harmful = (isAlive && owner != sourceOwner);
                    if (harmful) {
                        float massMult = TIDAL_MASS_MULT + 0.4f * (float)Math.sqrt(Math.max(0f, mHat));
                        // 潮汐撕扯：造成EMP伤害
                        float tidalEmpDpsBase = TIDAL_BASE_EMP_DPS;
                        float tidalEmpDpsMax = TIDAL_MAX_EMP_DPS;
                        
                        // 距离采用二次曲线增强中心区影响
                        float sizeMult = getTidalDamageSizeMult(target);
                        float distanceCurve = rFactor * rFactor; // 二次方：越近伤害越高
                        
                        float totalEmpDps = (tidalEmpDpsBase + (tidalEmpDpsMax - tidalEmpDpsBase) * distanceCurve)
                                * (massMult * (1.0f + TIDAL_MASS_SCALE * (float)Math.sqrt(Math.max(0f, mHat))))
                                * sizeMult;
                        float totalEmpDamage = totalEmpDps * DAMAGE_INTERVAL;
                        
                        // 造成纯EMP伤害，微量实际伤害（1点）
                        engine.applyDamage(target, target.getLocation(), 1f, DamageType.ENERGY, totalEmpDamage, true, false, source, true);
                        
                        // 按较低比例计入质量
                        Moci_SingularityRenderer.getOrCreate().addMass(this.proj, totalEmpDamage * 0.1f);
                    }
                }
            }
        }

        // 吸引并吞噬其他投射物（包括残骸弹片）
        // 使用Grid遍历优化性能
        List<DamagingProjectileAPI> projectiles = Moci_Util.getAllProjectilesInRange(center, R_world);
        for (DamagingProjectileAPI other : projectiles) {
            if (other == this.proj) continue;
            // 避免奇点相互吸引：若该投射物是奇点（被渲染器跟踪），则跳过
            try {
                Moci_SingularityRenderer rend = Moci_SingularityRenderer.getOrCreate();
                if (rend != null && rend.isTracked(other)) continue;
            } catch (Throwable ignore) {}
            float d = Misc.getDistance(center, other.getLocation());
            if (d > R_world) continue;
            
            // 检测是否为导弹
            boolean isMissile = (other instanceof MissileAPI);
            
            // 获取投射物碰撞半径（用于精确的吞噬判定）
            float projectileRadius = 0f;
            try {
                if (other.getCollisionRadius() > 0) {
                    projectileRadius = other.getCollisionRadius();
                }
            } catch (Exception e) {
                // 如果获取失败，使用默认值5像素
                projectileRadius = 5f;
            }
            
            // 简化引力计算：直接使用矢量加速度
            Vector2f dir = Vector2f.sub(center, other.getLocation(), null);
            float len = dir.length();
            float ramp = 0f; // 距离权重，声明在外层作用域
            
            if (len > 0f) {
                dir.scale(1f / len); // 归一化为单位方向向量
                
                // ramp: 从 0（远端）到 1（靠近核心）
                float rampDen = Math.max(1e-3f, R_world - rsWorld * 0.5f);
                ramp = Math.max(0f, Math.min(1f, (R_world - d) / rampDen));
                ramp = ramp * ramp; // 二次平滑，让近距离引力增强更明显
                
                // === 第一步：统一的引力加速度计算 ===
                // 所有投射物使用统一的强力引力公式（基于R_world，保证远距离有效）
                // 引力加速度足够强，让投射物能够自然形成螺旋轨迹
                float ratio = R_world / Math.max(1f, d + rSoft);
                float K = isMissile ? PROJECTILE_GRAVITY_MISSILE : PROJECTILE_GRAVITY_NORMAL;
                float aM = PROJECTILE_GRAVITY_MASS_MULT;
                float acceleration = K * (1f + aM * (float)Math.sqrt(mHat)) * ratio * ratio / DAMAGE_INTERVAL;
                
                // 将加速度转换为本帧的速度变化（Δv = a * Δt）
                float deltaV = acceleration * DAMAGE_INTERVAL;
                
                // 计算并应用引力加速度向量
                Vector2f v = new Vector2f(other.getVelocity());
                v.x += dir.x * deltaV;
                v.y += dir.y * deltaV;
                
                // === 第二步：切向阻尼（促进径向落入，减少绕行）===
                if (ramp > PROJECTILE_TANGENT_DAMPING_THRESHOLD) {
                    float vr = v.x * dir.x + v.y * dir.y; // 径向分量
                    Vector2f vt = new Vector2f(v.x - dir.x * vr, v.y - dir.y * vr); // 切向分量
                    float tangDamping = 1f - PROJECTILE_TANGENT_DAMPING_MAX * ramp;
                    vt.scale(tangDamping);
                    v.x = dir.x * vr + vt.x;
                    v.y = dir.y * vr + vt.y;
                }
                
                // === 第三步：事件界限阻尼（只在接近/进入事件界限时触发）===
                float outerDampingRange = MISSILE_DAMPING_OUTER_RANGE;
                
                if (d < rsWorld + outerDampingRange) {
                    if (d > rsWorld) {
                        // 在事件界限外预警范围内：所有投射物微弱阻尼
                        float distanceFromHorizon = d - rsWorld;
                        float proximity = 1.0f - (distanceFromHorizon / outerDampingRange);
                        proximity = Math.max(0f, Math.min(1f, proximity));
                        
                        // 导弹阻尼更强
                        float weakDamping = isMissile ? MISSILE_DAMPING_OUTER_MAX : (MISSILE_DAMPING_OUTER_MAX * 0.5f);
                        float dampingMult = 1.0f - weakDamping * proximity;
                        v.x *= dampingMult;
                        v.y *= dampingMult;
                    } else {
                        // 在事件界限内：所有投射物强力阻尼，导弹更强
                        float depthInHorizon = rsWorld - d;
                        float penetration = Math.min(1f, depthInHorizon / rsWorld);
                        
                        // 三次方曲线让阻尼急剧增强
                        float dampingFactor = penetration * penetration * penetration;
                        
                        // 导弹减速更强
                        float maxDamping = isMissile ? MISSILE_DAMPING_INNER_MAX : (MISSILE_DAMPING_INNER_MAX - 0.15f);
                        float dampingMult = 1.0f - maxDamping * dampingFactor;
                        v.x *= dampingMult;
                        v.y *= dampingMult;
                    }
                }
                
                // 速度上限：防止过快穿透
                float finalSpeed = (float)Math.sqrt(v.x * v.x + v.y * v.y);
                if (finalSpeed > PROJECTILE_MAX_SPEED) {
                    float limitScale = PROJECTILE_MAX_SPEED / finalSpeed;
                    v.x *= limitScale;
                    v.y *= limitScale;
                }
                
                // 近距离强制修正：如果非常接近但仍在向外，强制反转
                if (d < rsWorld * 2.0f) {
                    float vr = v.x * dir.x + v.y * dir.y;
                    if (vr < 0f) {
                        // 向外运动，强制反转为向内
                        v.x += dir.x * (-vr * 2f);
                        v.y += dir.y * (-vr * 2f);
                    }
                }
                
                other.getVelocity().set(v);
            }
            
            // 吞噬判定：严格判定，只吞噬真正进入事件界限的投射物
            // 投射物中心到黑洞中心的距离
            float distanceFromCenter = d;
            
            // 严格的事件视界半径，不向外延伸
            float swallowRadius = rsWorld;
            
            // 只有当投射物的边缘触碰到事件视界时才吞噬
            // 使用更精确的判定：投射物外缘距离 <= 事件视界半径
            if (distanceFromCenter - projectileRadius <= swallowRadius) {
                float dmgMass = Math.max(0f, other.getDamageAmount()) * 0.5f;
                Moci_SingularityRenderer.getOrCreate().addMass(this.proj, dmgMass);
                engine.removeEntity(other);
            } else {
                // 视觉朝向调整：使用更平滑的曲线，不直接对准中心
                // 只在靠近视界时才调整朝向，远处保持自然轨迹
                try {
                    applySmoothVisualOrientation(other, center, dir, d, rsWorld, R_world, ramp);
                } catch (Throwable ignore) {}
            }
        }

        // 之前的光束弯折/截断/吸收逻辑已移除，准备采用更合适的API方案
    }
    
    /**
     * 应用引力效果
     */
    private void applyGravitationalForce(ShipAPI target, Vector2f center, float force) {
        if (target.getMass() <= 0f) return;
        
        // 基于质量的衰减因子
        // 调整衰减：2000质量以下不衰减，15000质量时几乎无效
        float massDecayFactor = 1.0f;
        float minMassForDecay = 2000f;
        float maxMassForDecay = 15000f;
        
        if (target.getMass() > minMassForDecay) {
            if (target.getMass() >= maxMassForDecay) {
                // 质量达到上限，引力完全无效
                return;
            }
            // 线性插值计算衰减因子
            massDecayFactor = 1.0f - (target.getMass() - minMassForDecay) / (maxMassForDecay - minMassForDecay);
        }
        
        // 计算方向向量 (从目标指向中心点)
        Vector2f direction = new Vector2f(center.x - target.getLocation().x, 
                                         center.y - target.getLocation().y);
        float length = direction.length();
        if (length <= 0f) return;
        
        // 标准化并应用力量，使其成为单位向量
        direction.scale(1f / length);
        
        // 力量受质量影响 - 增强版本会让小型舰艇更明显地被拉扯
        float massScale = Math.min(1.0f, 80f / target.getMass());
        // 增强小型舰艇的引力，使效果更明显
        force = force * (1f + massScale * 2f);
        
        // 应用质量衰减因子
        force *= massDecayFactor;
        
        // 应用力量，较轻舰艇受到的影响更大
        direction.scale(force / (float)Math.sqrt(target.getMass()));
        
        // 对飞船施加推力 - 累积到当前速度上
        Vector2f.add(target.getVelocity(), direction, target.getVelocity());
        
        // 移除牵引粒子可视化，保持纯物理牵引
        
        // 添加旋转效果 - 质量较小的船会旋转得更剧烈
        float angularImpulse = (float)Math.random() * 16f - 8f;
        // 质量越小的舰船，旋转效果越明显
        angularImpulse *= (1f + massScale * 3f);
        // 应用质量衰减因子到旋转效果
        angularImpulse *= massDecayFactor;
        target.setAngularVelocity(target.getAngularVelocity() + angularImpulse);
        
    }


    /**
     * 为投射物应用平滑的视觉朝向：基于速度方向而非直接对准中心，产生更自然的曲线轨迹
     * 距离越近偏转越大，在引力透镜范围内可以180度大转弯
     * 
     * @param entity 投射物实体
     * @param center 黑洞中心
     * @param toCenter 指向黑洞的单位向量
     * @param distance 到黑洞的距离
     * @param rsWorld 事件视界半径
     * @param R_world 作用半径
     * @param ramp 距离权重（0=远，1=近）
     */
    private void applySmoothVisualOrientation(CombatEntityAPI entity,
                                              Vector2f center,
                                              Vector2f toCenter,
                                              float distance,
                                              float rsWorld,
                                              float R_world,
                                              float ramp) {
        if (entity == null || center == null) return;
        
        Vector2f vel = entity.getVelocity();
        float speed = vel.length();
        if (speed < 1e-3f) return; // 速度太低不调整
        
        // 计算速度方向的角度（投射物应该朝向其飞行方向）
        float velocityAngle = (float)Math.toDegrees(Math.atan2(vel.y, vel.x));
        
        // 计算朝向黑洞的角度
        float toCenterAngle = (float)Math.toDegrees(Math.atan2(toCenter.y, toCenter.x));
        
        // 计算两个角度之间的最短差
        float angleDiff = shortestRotationDeg(velocityAngle, toCenterAngle);
        
        // 偏转系数：使用平滑的三次Hermite插值（smootherstep），实现C1连续
        // 距离越近偏转越强，让投射物朝向能够跟随速度变化形成螺旋轨迹
        float deflectionRatio;
        if (ramp < TRAJECTORY_DEFLECTION_MID_THRESHOLD) {
            // 远中距离：0% → DEFLECTION_FAR_MAX，使用smoothstep实现平滑过渡
            float t = ramp / TRAJECTORY_DEFLECTION_MID_THRESHOLD;
            // smootherstep: 6t⁵ - 15t⁴ + 10t³
            float smooth = t * t * t * (t * (t * 6f - 15f) + 10f);
            deflectionRatio = TRAJECTORY_DEFLECTION_FAR_MAX * smooth;
        } else {
            // 引力透镜范围：DEFLECTION_FAR_MAX → DEFLECTION_NEAR_MAX，接近完全对准以跟上引力加速度
            // 让朝向快速跟随速度方向，避免"横着飞"
            float t = (ramp - TRAJECTORY_DEFLECTION_MID_THRESHOLD) / (1f - TRAJECTORY_DEFLECTION_MID_THRESHOLD);
            // 使用二次方让后期快速增强
            float accel = t * t;
            deflectionRatio = TRAJECTORY_DEFLECTION_FAR_MAX + (TRAJECTORY_DEFLECTION_NEAR_MAX - TRAJECTORY_DEFLECTION_FAR_MAX) * accel;
        }
        
        // 计算目标角度：让朝向指向速度方向和黑洞方向的插值
        float targetAngle = velocityAngle + angleDiff * deflectionRatio;
        
        // 当前朝向
        float currentFacing = entity.getFacing();
        
        // 使用平滑插值（lerp）让朝向逐渐逼近目标角度，产生完美的贝塞尔曲线
        // 不使用固定转速限制，而是使用比例插值，距离越近插值越快
        float lerpFactor; // 每帧朝向移动到目标角度的比例
        if (ramp < TRAJECTORY_DEFLECTION_MID_THRESHOLD) {
            // 远中距离：LERP_FAR_MIN → LERP_FAR_MAX，使用smoothstep
            float t = ramp / TRAJECTORY_DEFLECTION_MID_THRESHOLD;
            float smooth = t * t * t * (t * (t * 6f - 15f) + 10f);
            lerpFactor = TRAJECTORY_LERP_FAR_MIN + (TRAJECTORY_LERP_FAR_MAX - TRAJECTORY_LERP_FAR_MIN) * smooth;
        } else {
            // 引力透镜范围：LERP_FAR_MAX → LERP_NEAR_MAX，快速跟随速度变化
            float t = (ramp - TRAJECTORY_DEFLECTION_MID_THRESHOLD) / (1f - TRAJECTORY_DEFLECTION_MID_THRESHOLD);
            float accel = t * t; // 二次方加速
            lerpFactor = TRAJECTORY_LERP_FAR_MAX + (TRAJECTORY_LERP_NEAR_MAX - TRAJECTORY_LERP_FAR_MAX) * accel;
        }
        
        // 计算角度差（最短路径）
        float desiredDelta = shortestRotationDeg(currentFacing, targetAngle);
        
        // 使用lerp平滑过渡，产生指数衰减曲线而非多边形
        float actualDelta = desiredDelta * lerpFactor;
        
        // 应用新朝向
        float newFacing = currentFacing + actualDelta;
        while (newFacing < 0f) newFacing += 360f;
        while (newFacing >= 360f) newFacing -= 360f;
        entity.setFacing(newFacing);
    }
    
    // 计算[-180,180]范围的最短角度差（保留此方法供新旧代码使用）
    private float shortestRotationDeg(float fromAngle, float toAngle) {
        float delta = toAngle - fromAngle;
        while (delta > 180f) delta -= 360f;
        while (delta < -180f) delta += 360f;
        return delta;
    }
    
    // 以下方法保留用于未来可能的光束弯曲效果实现
    // 目前光束弯折/截断/吸收逻辑已移除，准备采用更合适的API方案

    /**
     * 视觉扭曲效果渲染器
     */
    private class DistortionRenderer extends BaseCombatLayeredRenderingPlugin {
        private boolean isActive = false;
        private boolean expired = false;
        private Vector2f center = new Vector2f(0f, 0f);
        // 完全移除环形扭曲点与纹理，避免白色环粒子
        private List<DistortionPoint> distortionPoints = new ArrayList<>();
        private static final int SECTOR_SEGMENTS = 28;
        private static final float SECTOR_BASE_ALPHA = 0.28f;
        private final List<SectorBurst> sectorBursts = new ArrayList<SectorBurst>();
        // 动态白色能量射线（射线爆）
        private static final int BEAM_SEGMENTS = 24;
        private static final float BEAM_BASE_ALPHA = 0.78f;
        private static final float BEAM_NOISE_FREQ = 7.5f;
        private static final float BEAM_PHASE = 11.0f;
        private final List<BeamBurst> beamBursts = new ArrayList<BeamBurst>();
        private static final float INFINITE_RENDER_RADIUS = 1000000f;
        // 能量洪流视觉：沿射线向外移动的亮带
        private static final float FLOW_BAND_WORLD = 300f;
        private static final float FLOW_ALPHA_GAIN = 0.85f;
        private static final float FLOW_BASE_WIDTH = 24f;
        private final IntervalUtil flowBurstInterval = new IntervalUtil(0.08f, 0.12f);
        // 实例化渲染器（beamBursts 批渲染）
        private InstancedBeamRenderer instancedRenderer;
        
        public DistortionRenderer() {
            this.layer = CombatEngineLayers.ABOVE_SHIPS_LAYER;
        }
        
        public void setActive(boolean active) {
            this.isActive = active;
        }
        
        public void setCenter(Vector2f center) {
            this.center.set(center);
        }
        
        /**
         * 强制清理所有扭曲效果，用于黑洞消失时避免残留
         */
        public void forceCleanup() {
            // 清空所有扭曲点列表
            distortionPoints.clear();
            sectorBursts.clear();
            beamBursts.clear();
            expired = true;
        }
        
        @Override
        public void advance(float amount) {
            // 确保挂件实体位置跟随中心，降低引擎基于位置的裁剪影响
            if (this.entity != null) {
                this.entity.getLocation().set(center);
            }
            // 先始终更新扇形洪流的寿命（即使未激活依然需要渲染/消退）
            Iterator<SectorBurst> sit = sectorBursts.iterator();
            while (sit.hasNext()) {
                SectorBurst s = sit.next();
                s.age += amount;
                if (s.age >= s.life) sit.remove();
            }

            // 更新能量射线寿命与命中长度（被舰船碰撞箱阻挡）
            Iterator<BeamBurst> bit = beamBursts.iterator();
            while (bit.hasNext()) {
                BeamBurst b = bit.next();
                b.age += amount;
                if (b.age >= b.life) { bit.remove(); continue; }
                float angRad = (float)Math.toRadians(b.angleDeg);
                Vector2f dir = new Vector2f((float)Math.cos(angRad), (float)Math.sin(angRad));
                HitResult hr = Moci_GravityCannonChargeGlow.this.findFirstBlocking(b.origin, dir, b.maxLen, Global.getCombatEngine().getShips(), b.sourceOwner);
                if (hr != null) {
                    b.currentLen = Math.min(b.maxLen, hr.dist);
                    b.blocked = true;
                    b.hitPoint = new Vector2f(hr.point);
                } else {
                    b.currentLen = b.maxLen;
                    b.blocked = false;
                    b.hitPoint = null;
                }
            }

            // 周期性生成向外洪流的射线扇形，叠加在脉冲与光幕上
            flowBurstInterval.advance(amount);
            if (flowBurstInterval.intervalElapsed()) {
                int owner = Moci_GravityCannonChargeGlow.this.weapon.getShip().getOwner();
                // 为每个活动脉冲填充若干条流动射线
                for (CollapsePulse p : Moci_GravityCannonChargeGlow.this.activePulses) {
                    float arc = p.getCurrentArcDeg();
                    if (arc <= 0f) continue;
                    float alphaMult = 0.6f * p.getIntensityMult();
                    int rays = 6; float life = 0.9f; float width = FLOW_BASE_WIDTH;
                    addBeamFanBurst(p.origin, p.angleDeg, arc, Moci_GravityCannonChargeGlow.MAX_DAMAGE_LEN, life, width, rays, owner, alphaMult);
                }
                // 为360°光幕填充环向流动射线
                if (Moci_GravityCannonChargeGlow.this.finalRingActive) {
                    float tHold = Math.max(0f, Moci_GravityCannonChargeGlow.this.finalRingDuration - Moci_GravityCannonChargeGlow.this.finalRingFadeTime);
                    float tNow = Moci_GravityCannonChargeGlow.this.finalRingAge;
                    float fade = (tNow <= tHold) ? 1f : Math.max(0f, 1f - (tNow - tHold) / Math.max(0.0001f, Moci_GravityCannonChargeGlow.this.finalRingFadeTime));
                    float alphaMult = 0.55f * fade;
                    int rays = 12; float life = 0.9f; float width = FLOW_BASE_WIDTH * 0.85f;
                    addBeamFanBurst(Moci_GravityCannonChargeGlow.this.finalRingCenter, 0f, 360f, Moci_GravityCannonChargeGlow.MAX_DAMAGE_LEN, life, width, rays, Moci_GravityCannonChargeGlow.this.finalRingSourceOwner, alphaMult);
                }
            }

            if (!isActive) {
                // 当效果不活跃时，逐渐淡出现有的扭曲点
                if (!distortionPoints.isEmpty()) {
                    for (DistortionPoint point : distortionPoints) {
                        point.fade(amount);
                    }
                    // 移除已经消失的点
                    Iterator<DistortionPoint> iter = distortionPoints.iterator();
                    while (iter.hasNext()) {
                        if (iter.next().alpha <= 0f) iter.remove();
                    }
                }
                // 即便当前不活跃，仍需在 collapse 可视阶段绘制脉冲/光幕（由 render 负责），因此不 return
            }

            // 不再生成任何扭曲点（彻底移除白色环来源）

            // 更新所有扭曲点
            for (DistortionPoint point : distortionPoints) {
                point.update(amount);
            }

            // 移除已消失的扭曲点
            Iterator<DistortionPoint> iter = distortionPoints.iterator();
            while (iter.hasNext()) {
                if (iter.next().alpha <= 0f) iter.remove();
            }
        }
        
        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            boolean hasRing = Moci_GravityCannonChargeGlow.this.finalRingActive;
            if ((!collapseEffectsEnabled || !hasRing) && distortionPoints.isEmpty() && sectorBursts.isEmpty() && beamBursts.isEmpty()) return;

            // 不再绘制任何环形扭曲精灵

            // 绘制射线爆（动态白色能量流）：优先实例化渲染
            if (collapseEffectsEnabled && !beamBursts.isEmpty()) {
                if (InstancedBeamRenderer.isSupported()) {
                    if (instancedRenderer == null) instancedRenderer = new InstancedBeamRenderer();
                    instancedRenderer.draw(beamBursts);
                } else {
                    // 回退到 GL11 即时模式
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                    for (BeamBurst b : beamBursts) {
                        float lifeT = Math.min(1f, Math.max(0f, b.age / b.life));
                        float baseAlpha = BEAM_BASE_ALPHA * (1f - lifeT) * b.alphaMult;
                        float angRad = (float)Math.toRadians(b.angleDeg);
                        Vector2f dir = new Vector2f((float)Math.cos(angRad), (float)Math.sin(angRad));
                        Vector2f perp = new Vector2f(-dir.y, dir.x);
                        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
                        for (int i=0; i<=BEAM_SEGMENTS; i++) {
                            float t = (float)i / (float)BEAM_SEGMENTS;
                            float pos = t * b.currentLen;
                            float noise = (float)Math.sin(b.noiseSeed + (b.age * BEAM_NOISE_FREQ) + (t * BEAM_PHASE));
                            float width = b.baseWidth * (0.75f + 0.25f * noise) * (1f - lifeT);
                            float travelPos = lifeT * b.currentLen;
                            float band = 1f - Math.min(1f, Math.abs(pos - travelPos) / FLOW_BAND_WORLD);
                            band = Math.max(0f, band);
                            float x = b.origin.x + dir.x * pos;
                            float y = b.origin.y + dir.y * pos;
                            float a = baseAlpha * (0.4f + 0.6f * band) * FLOW_ALPHA_GAIN;
                            GL11.glColor4f(1f, 1f, 1f, a);
                            GL11.glVertex2f(x + perp.x * width, y + perp.y * width);
                            GL11.glVertex2f(x - perp.x * width, y - perp.y * width);
                        }
                        GL11.glEnd();
                    }
                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                }
            }

            // 动态渲染坍塌阶段的5次脉冲扇形光幕（带遮挡，边缘有能量流）
            if (collapseEffectsEnabled && !Moci_GravityCannonChargeGlow.this.activePulses.isEmpty()) {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                for (CollapsePulse p : Moci_GravityCannonChargeGlow.this.activePulses) {
                    if (!p.isActive()) continue;
                    float arc = p.getCurrentArcDeg();
                    if (arc <= 0f) continue;
                    float half = (float)Math.toRadians(arc * 0.5f);
                    float baseRad = (float)Math.toRadians(p.angleDeg);
                    float alpha = 0.6f + 0.4f * p.getIntensityMult();
                    GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                    GL11.glColor4f(1f, 1f, 1f, alpha);
                    GL11.glVertex2f(p.origin.x, p.origin.y);
                    for (int i=0; i<=SECTOR_SEGMENTS; i++) {
                        float k = (float)i / (float)SECTOR_SEGMENTS;
                        float ang = baseRad - half + (2f*half) * k;
                        Vector2f dir = new Vector2f((float)Math.cos(ang), (float)Math.sin(ang));
                        HitResult hr = Moci_GravityCannonChargeGlow.this.findFirstBlocking(p.origin, dir, MAX_DAMAGE_LEN, Global.getCombatEngine().getShips(), Moci_GravityCannonChargeGlow.this.weapon.getShip().getOwner());
                        float len = (hr != null) ? hr.dist : MAX_DAMAGE_LEN;
                        float x = p.origin.x + dir.x * len;
                        float y = p.origin.y + dir.y * len;
                        GL11.glVertex2f(x, y);
                    }
                    GL11.glEnd();
                }
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
            }

            // 渲染扇形洪流（非纹理扇形，三角扇）
            if (collapseEffectsEnabled && !sectorBursts.isEmpty()) {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                for (SectorBurst s : sectorBursts) {
                    float t = Math.min(1f, Math.max(0f, s.age / s.life));
                    float alpha = SECTOR_BASE_ALPHA * (1f - t);
                    float half = (float)Math.toRadians(s.arcDeg * 0.5f);
                    float baseRad = (float)Math.toRadians(s.angleDeg);
                    GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                    GL11.glColor4f(1f, 1f, 1f, alpha);
                    GL11.glVertex2f(s.center.x, s.center.y);
                    for (int i=0; i<=SECTOR_SEGMENTS; i++) {
                        float k = (float)i / (float)SECTOR_SEGMENTS;
                        float ang = baseRad - half + (2f*half) * k;
                        float len = s.length;
                        if (s.occluded) {
                            // 进行一次从中心到该角度方向的最近阻挡检测
                            Vector2f dir = new Vector2f((float)Math.cos(ang), (float)Math.sin(ang));
                            HitResult hr = Moci_GravityCannonChargeGlow.this.findFirstBlocking(s.center, dir, s.length, Global.getCombatEngine().getShips(), s.sourceOwner);
                            if (hr != null) {
                                len = Math.min(len, hr.dist);
                            }
                        }
                        float a = alpha * (0.6f + 0.4f * (1f - Math.abs(0.5f - k) * 2f));
                        GL11.glColor4f(1f, 1f, 1f, a);
                        GL11.glVertex2f(s.center.x + (float)Math.cos(ang) * len, s.center.y + (float)Math.sin(ang) * len);
                    }
                    GL11.glEnd();
                }
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
            }

            // 渲染最终360°光幕（可被舰船遮挡，使用三角带）
            if (hasRing) {
                Vector2f c = new Vector2f(Moci_GravityCannonChargeGlow.this.finalRingCenter);
                float age = Moci_GravityCannonChargeGlow.this.finalRingAge;
                float R = Moci_GravityCannonChargeGlow.this.finalRingStartRadius + Moci_GravityCannonChargeGlow.this.finalRingGrowthSpeed * age;
                float thick = Moci_GravityCannonChargeGlow.this.finalRingThickness;
                float inner = Math.max(0f, R - thick * 0.5f);
                // outer 未使用，直接用内缘 + outLen 计算
                int segs = Math.max(24, RING_SEGMENTS);
                int owner = Moci_GravityCannonChargeGlow.this.finalRingSourceOwner;

                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
                for (int i=0; i<=segs; i++) {
                    float ang = (float)i * (360f / (float)segs);
                    float rad = (float)Math.toRadians(ang);
                    Vector2f dir = new Vector2f((float)Math.cos(rad), (float)Math.sin(rad));
                    // 从内缘出发，检测遮挡，长度近似无限
                    float maxLen = Moci_GravityCannonChargeGlow.MAX_DAMAGE_LEN;
                    HitResult hr = Moci_GravityCannonChargeGlow.this.findFirstBlocking(new Vector2f(c.x + dir.x * inner, c.y + dir.y * inner), dir, maxLen, Global.getCombatEngine().getShips(), owner);
                    float outLen = (hr != null) ? Math.min(maxLen, hr.dist) : maxLen;
                    // 根据年龄淡出
                    float tHold = Math.max(0f, Moci_GravityCannonChargeGlow.this.finalRingDuration - Moci_GravityCannonChargeGlow.this.finalRingFadeTime);
                    float tNow = Moci_GravityCannonChargeGlow.this.finalRingAge;
                    float fade = (tNow <= tHold) ? 1f : Math.max(0f, 1f - (tNow - tHold) / Math.max(0.0001f, Moci_GravityCannonChargeGlow.this.finalRingFadeTime));
                    float alpha = 0.95f * fade; // 纯白接近不透明
                    GL11.glColor4f(1f, 1f, 1f, alpha);
                    // 外缘点
                    float ox = c.x + dir.x * (inner + outLen);
                    float oy = c.y + dir.y * (inner + outLen);
                    // 内缘点
                    float ix = c.x + dir.x * inner;
                    float iy = c.y + dir.y * inner;
                    GL11.glVertex2f(ix, iy);
                    GL11.glVertex2f(ox, oy);
                }
                GL11.glEnd();
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
            }
        }
        
        @Override
        public boolean isExpired() {
            return expired;
        }
        
        @Override
        public float getRenderRadius() {
            // 渲染距离设为极大，避免被视口裁剪
            return INFINITE_RENDER_RADIUS;
        }
        
        @Override
        public EnumSet<CombatEngineLayers> getActiveLayers() {
            return EnumSet.of(layer);
        }

        // 保留未使用的方法用于未来的扩展功能

        public void addBeamBurst(Vector2f c, float angleDeg, float maxLen, float life, float baseWidth, int sourceOwner, float alphaMult) {
            BeamBurst b = new BeamBurst();
            b.origin = new Vector2f(c);
            b.angleDeg = angleDeg;
            b.maxLen = maxLen;
            b.currentLen = maxLen;
            b.life = life;
            b.age = 0f;
            b.baseWidth = baseWidth;
            b.sourceOwner = sourceOwner;
            b.noiseSeed = (float)(Math.random() * Math.PI * 2f);
            b.alphaMult = alphaMult;
            beamBursts.add(b);
        }

        public void addBeamFanBurst(Vector2f c, float centerAngleDeg, float arcDeg, float maxLen, float life, float baseWidth, int rays, int sourceOwner, float alphaMult) {
            float half = arcDeg * 0.5f;
            if (rays < 1) rays = 1;
            for (int i=0; i<rays; i++) {
                float t = (rays == 1) ? 0.5f : ((float)i / (float)(rays - 1));
                float ang = centerAngleDeg - half + arcDeg * t;
                float width = baseWidth * (0.9f + 0.2f * (float)Math.random());
                addBeamBurst(c, ang, maxLen, life, width, sourceOwner, alphaMult);
            }
        }
    }
    
    /**
     * 扭曲点类，表示单个扭曲效果（保留用于未来可能的视觉效果）
     */
    private static class DistortionPoint {
        @SuppressWarnings("unused") // 保留用于未来功能
        public float size;
        public float alpha;
        public float duration;
        public float maxDuration;
        public float maxSize;
        public float rotation;
        
        public void update(float amount) {
            // 扭曲点生命周期管理
            duration -= amount;
            
            // 缓慢旋转扭曲点，增加动态效果
            rotation += amount * (20f + (float)Math.random() * 10f) * (Math.random() > 0.5f ? 1 : -1);
            if (rotation > 360f) rotation -= 360f;
            if (rotation < 0f) rotation += 360f;
            
            // 扭曲点生长和消退动画
            if (duration > maxDuration * 0.7f) {
                // 生长阶段
                float growProgress = 1f - ((duration - (maxDuration * 0.7f)) / (maxDuration * 0.3f));
                size = maxSize * growProgress;
                alpha = Math.min(1f, growProgress * 2f);
            } else {
                // 消退阶段
                float fadeProgress = duration / (maxDuration * 0.7f);
                size = maxSize;
                alpha = fadeProgress;
            }
            
            if (duration <= 0f) {
                alpha = 0f;
            }
        }
        
        public void fade(float amount) {
            alpha = Math.max(0f, alpha - amount * 2f);
        }
    }

    private static class SectorBurst {
        Vector2f center;
        float angleDeg;
        float arcDeg;
        float length;
        float life;
        float age;
        boolean occluded;
        int sourceOwner;
    }

    private static class BeamBurst {
        Vector2f origin = new Vector2f();
        float angleDeg;
        float maxLen;
        float currentLen;
        float life;
        float age;
        float baseWidth;
        float noiseSeed;
        @SuppressWarnings("unused") // 用于碰撞检测逻辑
        boolean blocked;
        @SuppressWarnings("unused") // 用于记录碰撞点
        Vector2f hitPoint;
        int sourceOwner;
        float alphaMult = 1f;
    }

    /**
     * 基于 ARB Instanced 的射线批渲染器：用一个统一的条带基元（沿 X 轴 0..1）
     * 在顶点着色器中按实例参数（origin、dir、perp、currentLen、width、alpha、noiseSeed、lifeT）生成实际顶点。
     * 若硬件或驱动不支持实例化，则不使用该渲染器。
     */
    private static class InstancedBeamRenderer {
        // 每条 beam 作为一个实例；基础几何使用 TRIANGLE_STRIP
        private static final int BEAM_SEGMENTS_CONST = 24;
        private static final int VERTS_PER_STRIP = (BEAM_SEGMENTS_CONST + 1) * 2;
        private static final float BASE_ALPHA_CONST = 0.78f;
        private boolean initialized = false;
        private int vbo;
        private int program;
        private int attrib_pos;
        // 实例化属性位置（使用 ARBInstancedArrays）
        private int attrib_instOrigin;
        private int attrib_instDir;
        private int attrib_instPerp;
        private int attrib_instLenWidthAlpha;
        private int attrib_instNoiseLife;
        private int instanceVbo;
        private boolean supported;

        static boolean isSupported() {
            // 需要 Instanced Arrays 与 DrawInstanced（LWJGL2 用 ARB）
            return GLContext.getCapabilities().GL_ARB_instanced_arrays && GLContext.getCapabilities().GL_ARB_draw_instanced;
        }

        InstancedBeamRenderer() {
            this.supported = isSupported();
        }

        private void ensureInit() {
            if (!supported || initialized) return;
            // 创建基础条带顶点：参数 t in [0..1]，重复两次作为上下边
            FloatBuffer vb = BufferUtils.createFloatBuffer(VERTS_PER_STRIP * 2);
            for (int i=0; i<=BEAM_SEGMENTS_CONST; i++) {
                float t = (float)i / (float)BEAM_SEGMENTS_CONST; // 沿长度
                // 上边（+1）和下边（-1）
                vb.put(t).put(1f);
                vb.put(t).put(-1f);
            }
            vb.flip();
            vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vb, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            // 创建实例缓冲（动态）
            instanceVbo = GL15.glGenBuffers();

            // 编译着色器（最简内联 GLSL）
            String vs =
                "#version 120\n" +
                "#extension GL_ARB_instanced_arrays : enable\n" +
                "attribute vec2 aPos;\n" +
                "attribute vec2 iOrigin;\n" +
                "attribute vec2 iDir;\n" +
                "attribute vec2 iPerp;\n" +
                "attribute vec3 iLenWidthAlpha; // x=len, y=baseWidth, z=alpha\n" +
                "attribute vec2 iNoiseLife; // x=noiseSeed, y=lifeT\n" +
                "varying float vAlpha;\n" +
                "void main(){\n" +
                "  float t = aPos.x;\n" +
                "  float side = aPos.y;\n" +
                "  float lifeT = clamp(iNoiseLife.y, 0.0, 1.0);\n" +
                "  float pos = t * iLenWidthAlpha.x;\n" +
                "  float noise = sin(iNoiseLife.x + (t * 11.0) + (lifeT * 7.5));\n" +
                "  float width = iLenWidthAlpha.y * (0.75 + 0.25 * noise) * (1.0 - lifeT);\n" +
                "  float travelPos = lifeT * iLenWidthAlpha.x;\n" +
                "  float band = 1.0 - clamp(abs(pos - travelPos) / 300.0, 0.0, 1.0);\n" +
                "  float a = iLenWidthAlpha.z * (0.4 + 0.6 * band) * 0.85;\n" +
                "  vec2 world = iOrigin + iDir * pos + iPerp * (width * side);\n" +
                "  gl_Position = gl_ModelViewProjectionMatrix * vec4(world, 0.0, 1.0);\n" +
                "  vAlpha = a;\n" +
                "}\n";
            String fs =
                "#version 120\n" +
                "varying float vAlpha;\n" +
                "void main(){\n" +
                "  gl_FragColor = vec4(1.0, 1.0, 1.0, vAlpha);\n" +
                "}\n";
            int vsId;
            int fsId;
            try {
                vsId = compile(GL20.GL_VERTEX_SHADER, vs);
                fsId = compile(GL20.GL_FRAGMENT_SHADER, fs);
            } catch (RuntimeException ex) {
                // 编译失败则禁用实例化路径
                supported = false; return;
            }
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vsId);
            GL20.glAttachShader(program, fsId);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                // 链接失败则标记不支持，避免崩溃
                supported = false; return;
            }

            GL20.glUseProgram(program);
            attrib_pos = GL20.glGetAttribLocation(program, "aPos");
            attrib_instOrigin = GL20.glGetAttribLocation(program, "iOrigin");
            attrib_instDir = GL20.glGetAttribLocation(program, "iDir");
            attrib_instPerp = GL20.glGetAttribLocation(program, "iPerp");
            attrib_instLenWidthAlpha = GL20.glGetAttribLocation(program, "iLenWidthAlpha");
            attrib_instNoiseLife = GL20.glGetAttribLocation(program, "iNoiseLife");
            GL20.glUseProgram(0);

            initialized = true;
        }

        private int compile(int type, String src) {
            int id = GL20.glCreateShader(type);
            GL20.glShaderSource(id, src);
            GL20.glCompileShader(id);
            if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) {
                String log = GL20.glGetShaderInfoLog(id, 4096);
                GL20.glDeleteShader(id);
                throw new RuntimeException("Shader compile failed: " + log);
            }
            return id;
        }

        void draw(List<BeamBurst> beams) {
            ensureInit();
            if (!supported || !initialized) return;
            // 构建实例数据：每实例 2+2+2 +3 +2 = 11 floats
            int count = beams.size();
            final int floatsPerInstance = 11;
            FloatBuffer ib = BufferUtils.createFloatBuffer(count * floatsPerInstance);
            for (int i=0; i<count; i++) {
                BeamBurst b = beams.get(i);
                float lifeT = Math.min(1f, Math.max(0f, b.age / b.life));
                float angRad = (float)Math.toRadians(b.angleDeg);
                float dx = (float)Math.cos(angRad);
                float dy = (float)Math.sin(angRad);
                float px = -dy, py = dx;
                float baseAlpha = BASE_ALPHA_CONST * (1f - lifeT) * b.alphaMult;
                ib.put(b.origin.x).put(b.origin.y);             // iOrigin
                ib.put(dx).put(dy);                              // iDir
                ib.put(px).put(py);                              // iPerp
                ib.put(b.currentLen).put(b.baseWidth).put(baseAlpha); // iLenWidthAlpha
                ib.put(b.noiseSeed).put(lifeT);                  // iNoiseLife
            }
            ib.flip();

            // 绑定基础几何 VBO
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL20.glUseProgram(program);
            GL20.glEnableVertexAttribArray(attrib_pos);
            GL20.glVertexAttribPointer(attrib_pos, 2, GL11.GL_FLOAT, false, 8, 0);

            // 上传实例数据
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ib, GL15.GL_STREAM_DRAW);

            int stride = (floatsPerInstance * 4);
            int offset = 0;
            GL20.glEnableVertexAttribArray(attrib_instOrigin);
            GL20.glVertexAttribPointer(attrib_instOrigin, 2, GL11.GL_FLOAT, false, stride, offset);
            ARBInstancedArrays.glVertexAttribDivisorARB(attrib_instOrigin, 1);
            offset += 8;
            GL20.glEnableVertexAttribArray(attrib_instDir);
            GL20.glVertexAttribPointer(attrib_instDir, 2, GL11.GL_FLOAT, false, stride, offset);
            ARBInstancedArrays.glVertexAttribDivisorARB(attrib_instDir, 1);
            offset += 8;
            GL20.glEnableVertexAttribArray(attrib_instPerp);
            GL20.glVertexAttribPointer(attrib_instPerp, 2, GL11.GL_FLOAT, false, stride, offset);
            ARBInstancedArrays.glVertexAttribDivisorARB(attrib_instPerp, 1);
            offset += 8;
            GL20.glEnableVertexAttribArray(attrib_instLenWidthAlpha);
            GL20.glVertexAttribPointer(attrib_instLenWidthAlpha, 3, GL11.GL_FLOAT, false, stride, offset);
            ARBInstancedArrays.glVertexAttribDivisorARB(attrib_instLenWidthAlpha, 1);
            offset += 12;
            GL20.glEnableVertexAttribArray(attrib_instNoiseLife);
            GL20.glVertexAttribPointer(attrib_instNoiseLife, 2, GL11.GL_FLOAT, false, stride, offset);
            ARBInstancedArrays.glVertexAttribDivisorARB(attrib_instNoiseLife, 1);

            // 全局状态
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

            // 绘制：每实例画一条条带
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            ARBDrawInstanced.glDrawArraysInstancedARB(GL11.GL_TRIANGLE_STRIP, 0, VERTS_PER_STRIP, count);

            // 清理状态
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            // 解绑 attrib divisor（可选，避免影响其他渲染）
            ARBInstancedArrays.glVertexAttribDivisorARB(attrib_instOrigin, 0);
            ARBInstancedArrays.glVertexAttribDivisorARB(attrib_instDir, 0);
            ARBInstancedArrays.glVertexAttribDivisorARB(attrib_instPerp, 0);
            ARBInstancedArrays.glVertexAttribDivisorARB(attrib_instLenWidthAlpha, 0);
            ARBInstancedArrays.glVertexAttribDivisorARB(attrib_instNoiseLife, 0);

            GL20.glDisableVertexAttribArray(attrib_pos);
            GL20.glDisableVertexAttribArray(attrib_instOrigin);
            GL20.glDisableVertexAttribArray(attrib_instDir);
            GL20.glDisableVertexAttribArray(attrib_instPerp);
            GL20.glDisableVertexAttribArray(attrib_instLenWidthAlpha);
            GL20.glDisableVertexAttribArray(attrib_instNoiseLife);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL20.glUseProgram(0);
        }
    }

    /**
     * 坍塌阶段单次脉冲：从0°扩展到maxArc，再保持，随后收敛
     */
    private static class CollapsePulse {
        final Vector2f origin = new Vector2f();
        final float angleDeg;
        final float maxArcDeg;
        final float tExpand;
        final float tHold;
        final float tDecay;
        final int rays;
        float age = 0f;
        CollapsePulse(Vector2f c, float angleDeg, float maxArcDeg, float tExpand, float tHold, float tDecay, int rays, @SuppressWarnings("unused") int sourceOwner) {
            this.origin.set(c);
            this.angleDeg = angleDeg;
            this.maxArcDeg = maxArcDeg;
            this.tExpand = tExpand;
            this.tHold = tHold;
            this.tDecay = tDecay;
            this.rays = Math.max(1, rays);
        }
        boolean isActive() { return age < (tExpand + tHold + tDecay); }
        float getCurrentArcDeg() {
            float t = age;
            if (t < tExpand) {
                return maxArcDeg * (t / Math.max(0.0001f, tExpand));
            }
            t -= tExpand;
            if (t < tHold) {
                return maxArcDeg;
            }
            t -= tHold;
            float k = 1f - Math.min(1f, t / Math.max(0.0001f, tDecay));
            return maxArcDeg * Math.max(0f, k);
        }
        float getIntensityMult() {
            float t = age;
            if (t < tExpand) return Math.min(1f, t / Math.max(0.0001f, tExpand));
            t -= tExpand;
            if (t < tHold) return 1f;
            t -= tHold;
            return Math.max(0f, 1f - t / Math.max(0.0001f, tDecay));
        }
    }

    // 持续EMP伤害：脉冲扇形
    private void applyPulseContinuousDamage(List<CollapsePulse> pulses, Vector2f center, int sourceOwner) {
        float massValue = 0f;
        try { massValue = Moci_SingularityRenderer.getOrCreate().getMassValue(this.proj); } catch (Throwable ignore) { }
        float mHat = Math.max(0f, massValue) / 10000f;
        float empDpsScale = 1f + 1.6f * (float)Math.sqrt(mHat);
        List<ShipAPI> ships = engine.getShips();
        for (CollapsePulse p : pulses) {
            float arc = p.getCurrentArcDeg();
            if (arc <= 0f) continue;
            float half = arc * 0.5f;
            // 多条射线采样，遇阻即止
            for (int i=0; i<p.rays; i++) {
                float t = (p.rays == 1) ? 0.5f : ((float)i / (float)(p.rays - 1));
                float ang = p.angleDeg - half + arc * t;
                float angRad = (float)Math.toRadians(ang);
                Vector2f dir = new Vector2f((float)Math.cos(angRad), (float)Math.sin(angRad));
                HitResult hr = findFirstBlocking(center, dir, MAX_DAMAGE_LEN, ships, sourceOwner);
                if (hr != null && hr.entity instanceof ShipAPI) {
                    ShipAPI target = (ShipAPI)hr.entity;
                    // 过滤逃生舱和无敌单位
                    if (target.isShuttlePod()) continue;
                    if (target.getMutableStats().getHullDamageTakenMult().getModifiedValue() <= 0f) continue;
                    if (target.isAlive() && target.getOwner() != sourceOwner) {
                        float sizeMult = getTidalDamageSizeMult(target);
                        float empDps = PULSE_BASE_EMP_DPS * empDpsScale * p.getIntensityMult() * sizeMult;
                        float empDamage = empDps * DAMAGE_INTERVAL;
                        engine.applyDamage(target, hr.point != null ? hr.point : target.getLocation(), 1f, DamageType.ENERGY, empDamage, true, false, this.weapon.getShip(), true);
                    }
                }
            }
        }
    }

    // 持续EMP伤害：360°最终光幕
    private void applyFinalRingContinuousDamage(Vector2f center, int sourceOwner) {
        if (!finalRingActive) return;
        float age = finalRingAge;
        float R = finalRingStartRadius + finalRingGrowthSpeed * age;
        float inner = Math.max(0f, R - finalRingThickness * 0.5f);
        // EMP伤害检测沿径向向外无限延伸，遇阻即止
        float massValue = 0f;
        try { massValue = Moci_SingularityRenderer.getOrCreate().getMassValue(this.proj); } catch (Throwable ignore) { }
        float mHat = Math.max(0f, massValue) / 10000f;
        float empDpsScale = 1f + 1.4f * (float)Math.sqrt(mHat);
        List<ShipAPI> ships = engine.getShips();
        // 采样多角度，形成连续光幕并进行遮挡检测
        for (int i=0; i<RING_SEGMENTS; i++) {
            float ang = (float)i * (360f / (float)RING_SEGMENTS);
            float angRad = (float)Math.toRadians(ang);
            Vector2f dir = new Vector2f((float)Math.cos(angRad), (float)Math.sin(angRad));
            // 从内缘向外无限搜索第一个阻挡
            HitResult hr = findFirstBlocking(new Vector2f(center.x + dir.x * inner, center.y + dir.y * inner), dir, MAX_DAMAGE_LEN, ships, sourceOwner);
            if (hr != null && hr.entity instanceof ShipAPI) {
                ShipAPI target = (ShipAPI)hr.entity;
                // 过滤逃生舱和无敌单位
                if (target.isShuttlePod()) continue;
                if (target.getMutableStats().getHullDamageTakenMult().getModifiedValue() <= 0f) continue;
                if (target.isAlive() && target.getOwner() != sourceOwner) {
                    float sizeMult = getTidalDamageSizeMult(target);
                    float empDps = RING_BASE_EMP_DPS * empDpsScale * sizeMult;
                    float empDamage = empDps * DAMAGE_INTERVAL / (float)RING_SEGMENTS * 2f; // 分摊到段
                    engine.applyDamage(target, hr.point != null ? hr.point : target.getLocation(), 1f, DamageType.ENERGY, empDamage, true, false, this.weapon.getShip(), true);
                }
            }
        }
    }

    

    /**
     * 触发黑洞消失扩散效果
     */
    private void triggerDissipationEffect(Vector2f center) {
        if (dissipationTriggered) return;
        
        dissipationTriggered = true;
        dissipationTimer = 0f;
        dissipationCenter.set(center); // 保存中心位置
        
        try {
            // 获取黑洞的最终质量和半径
            float massValue = Moci_SingularityRenderer.getOrCreate().getMassValue(this.proj);
            float eventHorizonRadius = Moci_SingularityRenderer.getOrCreate().getEventHorizonRadiusWorld(this.proj);
            float mHat = Math.max(0f, massValue) / 10000f;
            
            // 计算最终扩散半径（引力作用范围）
            float finalRadius = GRAVITY_RANGE_BASE * (1f + GRAVITY_RANGE_MASS_MULT * (float)Math.sqrt(mHat));
            
            // 根据范围动态计算扩散时间（fadeIn阶段）
            // 基础时间 + (范围/1000) * 系数
            dissipationExpandDuration = DISSIPATION_BASE_EXPAND + (finalRadius / 1000f) * DISSIPATION_EXPAND_PER_1000;
            // 限制在合理范围内（0.8秒到4秒）
            dissipationExpandDuration = Math.max(0.8f, Math.min(4.0f, dissipationExpandDuration));
            
            // 创建扩散扭曲实体
            dissipationDistortion = new DistortionEntity();
            dissipationDistortion.setLocation(center.x, center.y);
            
            // 设置尺寸：环形波纹效果（像水中涟漪）
            // fadeIn阶段：从小尺寸扩散到最大（这是扩散动画的主要阶段）
            // full阶段：不需要（设置为0）
            // fadeOut阶段：保持最大尺寸但逐渐淡出
            float startSize = 50f; // 从50像素开始
            dissipationDistortion.setSizeIn(startSize, startSize);      // fadeIn起始尺寸
            dissipationDistortion.setSizeFull(finalRadius, finalRadius); // fadeIn结束尺寸（也是full阶段尺寸）
            dissipationDistortion.setSizeOut(finalRadius, finalRadius);  // fadeOut保持最大尺寸
            
            // 设置扭曲强度
            dissipationDistortion.setPowerIn(DISSIPATION_POWER);         // fadeIn阶段强度
            dissipationDistortion.setPowerFull(DISSIPATION_POWER);       // full阶段强度
            dissipationDistortion.setPowerOut(DISSIPATION_POWER * 0.3f); // fadeOut阶段减弱
            
            // 设置中间挖空效果 - 85%挖空形成清晰的环形
            dissipationDistortion.setInnerIn(0.85f, 0.85f);    // fadeIn阶段挖空
            dissipationDistortion.setInnerFull(0.85f, 0.85f);  // full阶段挖空
            dissipationDistortion.setInnerOut(0.85f, 0.85f);   // fadeOut阶段挖空
            
            // 设置边缘硬度
            dissipationDistortion.setRingHardness(0.6f);       // 外环硬度
            dissipationDistortion.setInnerHardness(0.6f);      // 内环硬度
            
            // 设置时间参数：
            // fadeIn = 动态扩散时间（扩散动画在这个阶段完成）
            // full = 0（不需要保持阶段）
            // fadeOut = 0.5秒（淡出）
            dissipationDistortion.setGlobalTimer(dissipationExpandDuration, 0f, DISSIPATION_FADE_OUT);
            
            // 添加到渲染系统
            CombatRenderingManager.addEntity(dissipationDistortion);
            
            // 播放消失音效
            Global.getSoundPlayer().playSound("system_phase_cloak_activate", 1.0f, 0.6f, center, new Vector2f());

        } catch (Throwable e) {
            LOG.error("[GRAVITY_CANNON_DEBUG] Failed to create dissipation effect", e);
            dissipationTriggered = false;
        }
    }
    
    /**
     * 更新扩散效果并造成伤害
     */
    private void updateDissipationEffect(float amount) {
        if (dissipationDistortion == null) {
            return;
        }
        
        // 检查扩散效果是否结束 - 使用动态计算的扩散时间
        // 总时长 = fadeIn（扩散） + full（0） + fadeOut（淡出）
        float totalDuration = dissipationExpandDuration + 0f + DISSIPATION_FADE_OUT;
        if (dissipationTimer >= totalDuration) {
            dissipationDistortion = null;
            return;
        }
        
        // 在扩散开始时造成一次伤害（fadeIn阶段刚开始）
        if (dissipationTimer >= 0.05f && dissipationTimer - amount < 0.05f) {
            applyDissipationDamage();
        }
    }

    private void disposeDistortionRenderer() {
        if (distortionRenderer != null && distortionRendererRegistered && engine != null) {
            distortionRenderer.forceCleanup();
            distortionRendererRegistered = false;
        }
    }

    /**
     * 应用扩散伤害
     */
    private void applyDissipationDamage() {
        if (this.proj == null || dissipationDistortion == null) return;
        
        try {
            Vector2f center = new Vector2f(dissipationCenter); // 使用保存的中心位置
            ShipAPI source = this.proj.getSource();
            int sourceOwner = source != null ? source.getOwner() : this.weapon.getShip().getOwner();
            
            // 获取黑洞的最终质量和半径
            float massValue = Moci_SingularityRenderer.getOrCreate().getMassValue(this.proj);
            float mHat = Math.max(0f, massValue) / 10000f;
            float finalRadius = GRAVITY_RANGE_BASE * (1f + GRAVITY_RANGE_MASS_MULT * (float)Math.sqrt(mHat));
            
            // 计算基础伤害（基于质量）
            float baseDamage = this.proj.getDamageAmount() * DISSIPATION_DAMAGE_MULT;
            float baseEmpDamage = baseDamage * (1f + mHat * 0.5f);
            
            int shipsAffected = 0;
            List<ShipAPI> ships = engine.getShips();
            for (ShipAPI target : ships) {
                if (target == null) continue;
                if (target.isShuttlePod()) continue;
                if (target.getMutableStats().getHullDamageTakenMult().getModifiedValue() <= 0f) continue;
                
                float distance = Misc.getDistance(center, target.getLocation());
                if (distance > finalRadius) continue;
                
                // 只对敌方单位造成伤害
                if (target.getOwner() == sourceOwner) continue;
                
                shipsAffected++;
                
                // 计算距离衰减（中心最强，边缘最弱）
                float distanceFactor = 1f - (distance / finalRadius);
                distanceFactor = distanceFactor * distanceFactor; // 二次衰减
                
                // 根据舰船尺寸调整伤害
                float sizeMult = getTidalDamageSizeMult(target);
                
                // 计算最终伤害
                float finalEmpDamage = baseEmpDamage * distanceFactor * sizeMult;
                float finalEnergyDamage = baseDamage * distanceFactor * sizeMult;
                
                // 造成EMP伤害（穿盾）
                engine.applyDamage(
                    target, 
                    target.getLocation(), 
                    finalEnergyDamage,  // 实际伤害
                    DamageType.ENERGY, 
                    finalEmpDamage,     // EMP伤害
                    true,               // 穿盾
                    false,              // 不是护盾伤害
                    source, 
                    true                // 显示伤害数字
                );
                
                // 添加视觉效果
                engine.addHitParticle(
                    target.getLocation(),
                    new Vector2f(),
                    50f + (float)Math.random() * 30f,
                    1f,
                    0.3f,
                    new Color(150, 150, 255, 200)
                );
                
            }
            
            if (shipsAffected > 0) {
            }
            
        } catch (Throwable e) {
            LOG.error("[GRAVITY_CANNON_DEBUG] Failed to apply dissipation damage", e);
        }
    }

    /**
     * 添加引力扭曲效果
     */
    private void addGravityDistortionEffect(Vector2f center) {
        try {
            LOG.info("[GRAVITY_CANNON_DEBUG] Attempting to add ShaderLib distortion at: " + center);

            WaveDistortion wave = new WaveDistortion(center, new Vector2f(0f, 0f));
            wave.setIntensity(2.0f);
            wave.setSize(EFFECT_RANGE * 0.6f);
            wave.flip(true);
            wave.setLifetime(0.0f);
            wave.fadeOutIntensity(0.5f);

            DistortionShader.addDistortion(wave);
            LOG.info("[GRAVITY_CANNON_DEBUG] ShaderLib distortion added successfully");
        } catch (Exception e) {
            // 如果发生异常，记录并继续
            // 可能是ShaderLib未加载或其他原因
            LOG.error("[GRAVITY_CANNON_DEBUG] Failed to add ShaderLib distortion", e);
            useShaderDistortion = false;
        }
    }

    

    // smallestAngleDiffDeg 方法已被 shortestRotationDeg 替代

    private static class HitResult {
        public CombatEntityAPI entity;
        public Vector2f point;
        public float dist;
    }

    private HitResult findFirstBlocking(Vector2f origin, Vector2f dirUnit, float maxLen, List<ShipAPI> ships, int sourceOwner) {
        HitResult best = null;
        for (ShipAPI s : ships) {
            if (s == null) continue;
            if (s.isHulk()) continue;              // 残骸不遮挡
            if (s.isShuttlePod()) continue;        // 逃生舱不遮挡
            if (s.getMutableStats().getHullDamageTakenMult().getModifiedValue() <= 0f) continue; // 无敌单位不遮挡

            // 1) 优先用精确船体轮廓（ExactBounds）做线段相交测试
            BoundsAPI bounds = s.getExactBounds();
            if (bounds != null) {
                bounds.update(s.getLocation(), s.getFacing());
                List<SegmentAPI> segs = bounds.getSegments();
                for (SegmentAPI seg : segs) {
                    Vector2f p1 = seg.getP1();
                    Vector2f p2 = seg.getP2();
                    Float t = intersectRaySegment(origin, dirUnit, p1, p2);
                    if (t != null && t > 0f && t <= maxLen) {
                        if (best == null || t < best.dist) {
                            best = new HitResult();
                            best.entity = s;
                            best.dist = t;
                            best.point = new Vector2f(origin.x + dirUnit.x * t, origin.y + dirUnit.y * t);
                        }
                    }
                }
                if (best != null) continue; // 已找到最近精确遮挡
            }

            // 2) 回退到圆形近似
            Vector2f c = s.getLocation();
            float r = s.getCollisionRadius();
            float t = intersectRayCircle(origin, dirUnit, c, r);
            if (t > 0f && t <= maxLen) {
                if (best == null || t < best.dist) {
                    best = new HitResult();
                    best.entity = s;
                    best.dist = t;
                    best.point = new Vector2f(origin.x + dirUnit.x * t, origin.y + dirUnit.y * t);
                }
            }
        }
        return best;
    }

    // 返回沿射线的最近交点距离 t，若无交点返回 -1
    private float intersectRayCircle(Vector2f p, Vector2f dUnit, Vector2f c, float r) {
        float fx = p.x - c.x;
        float fy = p.y - c.y;
        float b = 2f * (fx * dUnit.x + fy * dUnit.y);
        float cval = fx*fx + fy*fy - r*r;
        float disc = b*b - 4f * cval;
        if (disc < 0f) return -1f;
        float sqrt = (float)Math.sqrt(disc);
        float t1 = (-b - sqrt) * 0.5f;
        float t2 = (-b + sqrt) * 0.5f;
        if (t1 > 0f) return t1;
        if (t2 > 0f) return t2;
        return -1f;
    }

    // 线段相交：返回射线参数 t（起点 p，方向 dUnit，t>0），无交点返回 null
    private Float intersectRaySegment(Vector2f p, Vector2f dUnit, Vector2f a, Vector2f b) {
        float rx = dUnit.x;
        float ry = dUnit.y;
        float sx = b.x - a.x;
        float sy = b.y - a.y;

        float denom = rx * sy - ry * sx;
        if (Math.abs(denom) < 1e-6f) return null; // 平行

        float dx = a.x - p.x;
        float dy = a.y - p.y;

        float t = (dx * sy - dy * sx) / denom; // 射线参数
        float u = (dx * ry - dy * rx) / denom; // 线段参数 [0,1]
        if (t > 0f && u >= 0f && u <= 1f) return t;
        return null;
    }
    
    /**
     * 生成汇聚粒子和光线效果
     */
    private void spawnAccretionEffects(Vector2f center) {
        if (this.proj == null) return;
        
        try {
            // 获取黑洞半径
            float eventHorizonRadius = Moci_SingularityRenderer.getOrCreate().getEventHorizonRadiusWorld(this.proj);
            if (eventHorizonRadius <= 0f) return;
            
            // 计算生成数量（基于半径，线性插值）
            // 假设半径范围：10-100像素
            float radiusNormalized = Math.min(1f, Math.max(0f, (eventHorizonRadius - 10f) / 90f));
            
            int particleCount = ACCRETION_PARTICLE_COUNT_MIN + 
                (int)((ACCRETION_PARTICLE_COUNT_MAX - ACCRETION_PARTICLE_COUNT_MIN) * radiusNormalized);
            int trailCount = ACCRETION_TRAIL_COUNT_MIN + 
                (int)((ACCRETION_TRAIL_COUNT_MAX - ACCRETION_TRAIL_COUNT_MIN) * radiusNormalized);
            
            // 计算距离范围
            float minDistance = eventHorizonRadius * ACCRETION_DISTANCE_MIN_MULT;
            float maxDistance = eventHorizonRadius * ACCRETION_DISTANCE_MAX_MULT;
            
            // 生成粒子（使用BoxUtil批处理优化）
            for (int i = 0; i < particleCount; i++) {
                spawnAccretionParticleOptimized(center, minDistance, maxDistance);
            }
            
            // 生成光线
            for (int i = 0; i < trailCount; i++) {
                spawnAccretionTrail(center, minDistance, maxDistance);
            }
            
        } catch (Throwable e) {
            LOG.error("[GRAVITY_CANNON_DEBUG] Failed to spawn accretion effects", e);
        }
    }
    
    /**
     * 生成汇聚粒子（优化版本，使用BoxUtil批处理）
     */
    private void spawnAccretionParticleOptimized(Vector2f center, float minDistance, float maxDistance) {
        // 随机距离
        float distance = minDistance + (float)Math.random() * (maxDistance - minDistance);
        
        // 随机角度（360度全向）
        float angle = (float)Math.random() * 360f;
        
        // 计算生成位置
        float angleRad = (float)Math.toRadians(angle);
        Vector2f spawnPos = new Vector2f(
            center.x + (float)Math.cos(angleRad) * distance,
            center.y + (float)Math.sin(angleRad) * distance
        );
        
        // 计算速度（指向中心）
        float duration = ACCRETION_PARTICLE_DURATION * (0.5f + (float)Math.random() * 1.0f);
        Vector2f velocity = new Vector2f(
            (center.x - spawnPos.x) / duration,
            (center.y - spawnPos.y) / duration
        );
        
        // 随机尺寸
        float size = ACCRETION_PARTICLE_SIZE_MIN + 
            (float)Math.random() * (ACCRETION_PARTICLE_SIZE_MAX - ACCRETION_PARTICLE_SIZE_MIN);
        
        // 随机亮度
        float brightness = 0.6f + (float)Math.random() * 0.4f;
        
        // 使用BoxUtil批处理粒子（性能更好）
        Moci_BoxBasedUtil.addSingleParticle(
            "gravity_accretion", // 使用统一的key进行批处理
            spawnPos,
            velocity,
            size,
            brightness,
            0.05f, // 淡入
            0f,    // 无保持
            duration - 0.05f, // 淡出
            ACCRETION_PARTICLE_COLOR,
            "graphics/fx/hit_glow.png", // 使用发光纹理
            0f,    // 无旋转
            0f,    // 无旋转速度
            0f     // 无增长
        );
    }
    
    /**
     * 生成汇聚光线
     */
    private void spawnAccretionTrail(Vector2f center, float minDistance, float maxDistance) {
        // 随机距离
        float distance = minDistance + (float)Math.random() * (maxDistance - minDistance);
        
        // 随机角度（360度全向）
        float angle = (float)Math.random() * 360f;
        
        // 计算起始位置
        float angleRad = (float)Math.toRadians(angle);
        Vector2f startPos = new Vector2f(
            center.x + (float)Math.cos(angleRad) * distance,
            center.y + (float)Math.sin(angleRad) * distance
        );
        
        // 计算方向向量（指向中心）
        Vector2f direction = new Vector2f(
            center.x - startPos.x,
            center.y - startPos.y
        );
        
        // 创建TrailEntity
        TrailEntity trail = new TrailEntity();
        
        // 设置材质
        trail.getMaterialData().setDiffuse(Global.getSettings().getSprite("graphics/fx/beamfringeb.png"));
        trail.getMaterialData().setEmissive(Global.getSettings().getSprite("graphics/fx/beamcoreb.png"));
        trail.getMaterialData().setEmissiveColor(ACCRETION_TRAIL_CORE);
        trail.getMaterialData().setEmissiveColorAlpha(0.4f);
        
        // 设置颜色
        trail.setStartColor(ACCRETION_TRAIL_START);
        trail.setEndColor(ACCRETION_TRAIL_END);
        
        // 设置宽度
        trail.setStartWidth(ACCRETION_TRAIL_WIDTH_START);
        trail.setEndWidth(ACCRETION_TRAIL_WIDTH_END);
        
        // 效果设置
        trail.setJitterPower(0.1f);
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
        
        // 添加节点（初始为零长度，避免闪烁）
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
        trail.setGlobalTimer(0.0f, ACCRETION_TRAIL_DURATION, 0.3f);
        
        // 添加到渲染系统
        CombatRenderingManager.addEntity(trail);
        
        // 添加到活跃列表
        activeAccretionTrails.add(new AccretionTrailWrapper(trail, ACCRETION_TRAIL_DURATION, startPos, center));
    }
    
    /**
     * 更新所有活跃的汇聚光线
     */
    private void updateAccretionTrails(float amount) {
        if (activeAccretionTrails.isEmpty()) {
            return;
        }
        
        Iterator<AccretionTrailWrapper> iterator = activeAccretionTrails.iterator();
        
        while (iterator.hasNext()) {
            AccretionTrailWrapper wrapper = iterator.next();
            wrapper.age += amount;
            
            float progress = wrapper.age / wrapper.maxAge;
            
            if (progress >= 1.0f || wrapper.trail.hasDelete()) {
                if (!wrapper.trail.hasDelete()) {
                    wrapper.trail.delete();
                }
                iterator.remove();
            } else {
                // 更新方向（光线逐渐延伸到中心）
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
                
                Color endColor = new Color(
                    ACCRETION_TRAIL_END.getRed(),
                    ACCRETION_TRAIL_END.getGreen(),
                    ACCRETION_TRAIL_END.getBlue(),
                    (int)(ACCRETION_TRAIL_END.getAlpha() * alpha)
                );
                
                wrapper.trail.setEndColor(endColor);
            }
        }
    }
    
    /**
     * 汇聚光线包装类
     */
    private static class AccretionTrailWrapper {
        public final TrailEntity trail;
        public final float maxAge;
        public float age;
        public final Vector2f startPos;
        public final Vector2f fullDirection;
        
        public AccretionTrailWrapper(TrailEntity trail, float maxAge, Vector2f startPos, Vector2f center) {
            this.trail = trail;
            this.maxAge = maxAge;
            this.age = 0f;
            this.startPos = new Vector2f(startPos);
            this.fullDirection = new Vector2f(
                center.x - startPos.x,
                center.y - startPos.y
            );
        }
    }
} 
