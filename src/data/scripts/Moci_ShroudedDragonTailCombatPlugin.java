package data.scripts;

import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import data.scripts.tail.Moci_TailConfig;
import data.scripts.tail.Moci_TailConfigRegistry;

/**
 * 通用尾部战斗插件 - 仿生肌肉系统
 * 支持多个舰船的尾巴动画，通过配置驱动
 */
public class Moci_ShroudedDragonTailCombatPlugin extends BaseEveryFrameCombatPlugin {
    private static final String TAIL_WEAPON_ID = "TAIL";
    
    // 尾部挂载点默认位置（配置缺失时回退）
    private static final Vector2f MOUNT_LOCATION = new Vector2f(-36, 0);
    
    // ==================== 链条物理参数 ====================
    private static final float OVERLAP_FACTOR = 0.82f;          // 体节重叠比例
    private static final float TAIL_TIP_PIVOT_OFFSET = 0.14f;   // 尾端旋转中心偏移系数
    private static final float TAIL_TIP_OVERLAP_BONUS = 0.03f;  // 尾端额外重叠补偿
    
    // ==================== 骨架约束（硬限制） ====================
    // 软棍子模式：所有体节有相似的自由度，形成平滑弧形
    private static final float BONE_ROOT_MAX_ANGLE = 35f;       // 根部（第1节）最大弯曲角度（降低）
    private static final float BONE_SECOND_BASE_ANGLE = 12f;    // 第二节基础角度
    private static final float BONE_ANGLE_INCREMENT = 4f;       // 每节递增角度
    private static final float BONE_MID_SECTION_BONUS = 10f;    // 3-5节额外弯曲加成
    
    // ==================== 直接角度更新参数 ====================
    // 根部驱动插值速度（每秒辽近目标的比例）
    private static final float ROOT_LERP_SPEED = 8f;            // 根部追踪速度
    // 后续体节插值速度（越大越快跟随）
    private static final float FOLLOW_LERP_SPEED = 12f;         // 跟随速度
    
    // ==================== 自主摆动参数 ====================
    private static final float SWING_FREQUENCY = 0.3f;          // 摆动频率（Hz）
    private static final float SWING_AMPLITUDE = 45f;           // 摆动幅度（度）
    
    // ==================== 仿生学行为参数 ====================
    // 1. 角动量平衡：舰船转向时，尾巴反向摆动来平衡
    private static final float BALANCE_RESPONSE = 0.8f;         // 平衡响应系数
    private static final float BALANCE_MAX_ANGLE = 35f;         // 平衡弯曲最大角度
    private static final float SWING_SUPPRESS_RATE = 0.02f;     // 角速度抑制摆动的系数
    private static final float SWING_MIN_AMPLITUDE = 0.2f;      // 最低摆动幅度比例
    
    // 2. 惯性响应：加速/减速时尾巴向反方向甩
    private static final float INERTIA_RESPONSE = 0.015f;       // 加速度转换为弯曲的系数
    private static final float INERTIA_MAX_ANGLE = 25f;         // 惯性弯曲最大角度
    
    // 2.5 横移响应：横移时尾巴向反方向飘动（基于侧向速度占最大速度的百分比）
    private static final float STRAFE_RESPONSE = 0.8f;          // 侧向速度百分比转换为弯曲的系数
    private static final float STRAFE_MAX_ANGLE = 30f;          // 横移弯曲最大角度
    
    // 3. 受伤反应：受伤时尾巴抽搐
    private static final float DAMAGE_TWITCH_THRESHOLD = 0.02f; // 触发抽搐的伤害比例（占最大船体）
    private static final float DAMAGE_TWITCH_STRENGTH = 30f;    // 抽搐强度（度）
    private static final float DAMAGE_TWITCH_DECAY = 5f;        // 抽搐衰减速度
    
    // 3.5 巨大伤害/过载反应：受到巨大伤害或过载时尾巴绷直+痉挛
    private static final float SEVERE_DAMAGE_THRESHOLD = 0.08f;  // 触发绷直的伤害比例（占最大船体）
    private static final float SEVERE_DAMAGE_SPASM_FREQ = 25f;   // 痉挛频率（Hz）
    private static final float SEVERE_DAMAGE_SPASM_AMP = 6f;     // 痉挛幅度（度）
    private static final float SEVERE_DAMAGE_DECAY = 2f;         // 绷直衰减速度
    private static final float OVERLOAD_STIFFNESS = 0.85f;       // 过载时绷直强度
    
    // 4. 战斗姿态：有敌人时更活跃
    private static final float COMBAT_FREQUENCY_MULT = 1.5f;    // 战斗时摆动频率倍率
    private static final float COMBAT_AMPLITUDE_MULT = 1.3f;    // 战斗时摆动幅度倍率
    private static final float COMBAT_RANGE = 1500f;            // 检测敌人的范围
    
    // 5. 通量反应：高通量时僵硬/微颤
    private static final float FLUX_STIFFNESS_RATE = 0.5f;      // 通量影响僵硬度的系数
    private static final float FLUX_TREMOR_THRESHOLD = 0.7f;    // 触发微颤的通量比例
    private static final float FLUX_TREMOR_AMPLITUDE = 8f;      // 微颤幅度（度）
    private static final float FLUX_TREMOR_FREQUENCY = 15f;     // 微颤频率（Hz）
    
    // 6. 引擎联动：全速推进时拉直（基于速度百分比）
    private static final float ENGINE_STRAIGHTEN_THRESHOLD = 0.5f;  // 开始拉直的速度百分比阈值
    private static final float ENGINE_STRAIGHTEN_MAX = 0.7f;        // 最大拉直效果（减少摆动幅度的比例）
    
    // 8. 机动抑制：机动时抑制自主摆动，让尾巴响应机动而非自主摇摆
    private static final float MANEUVER_SUPPRESS_THRESHOLD = 0.3f;  // 开始抑制摆动的机动强度阈值
    private static final float MANEUVER_SUPPRESS_MAX = 0.85f;       // 最大抑制程度
    
    // 7. 系统激活反应：激活系统时猛甩
    private static final float SYSTEM_FLICK_STRENGTH = 45f;     // 系统激活眩动强度
    private static final float SYSTEM_FLICK_DECAY = 4f;         // 眩动衰减速度
    
    // ==================== 运行时状态 ====================
    private CombatEngineAPI engine;
    
    // 每个舰船的精灵和长度数据（根据配置动态加载）
    private Map<ShipAPI, SpriteAPI[]> shipSprites = new HashMap<>();
    private Map<ShipAPI, float[]> shipLengths = new HashMap<>();
    
    // 使用ConcurrentHashMap防止渲染器和插件之间的并发修改问题
    private ConcurrentHashMap<ShipAPI, Moci_ShroudedDragonTailRenderer.TailState> tailStates = new ConcurrentHashMap<>();
    private ConcurrentHashMap<ShipAPI, WeaponAPI> cachedTailWeapons = new ConcurrentHashMap<>();  // 缓存尾部武器引用，避免每帧遍历
    
    // 摆动相位追踪
    private ConcurrentHashMap<ShipAPI, Float> swingPhase = new ConcurrentHashMap<>();
    
    // 仿生行为追踪状态
    private ConcurrentHashMap<ShipAPI, Vector2f> prevShipVelocity = new ConcurrentHashMap<>();  // 上一帧速度
    private ConcurrentHashMap<ShipAPI, Float> prevHullLevel = new ConcurrentHashMap<>();       // 上一帧船体比例
    private ConcurrentHashMap<ShipAPI, Float> damageTwitchAngle = new ConcurrentHashMap<>();   // 当前抽搐角度
    private ConcurrentHashMap<ShipAPI, Boolean> prevSystemActive = new ConcurrentHashMap<>();  // 上一帧系统状态
    private ConcurrentHashMap<ShipAPI, Float> systemFlickAngle = new ConcurrentHashMap<>();    // 系统激活眩动角度
    private ConcurrentHashMap<ShipAPI, Float> severeDamageStiffness = new ConcurrentHashMap<>(); // 巨大伤害绷直强度
    private ConcurrentHashMap<ShipAPI, Float> severeDamagePhase = new ConcurrentHashMap<>();     // 巨大伤害痉挛相位
    
    private boolean rendererAdded = false;
    private Moci_ShroudedDragonTailRenderer rendererInstance;
    
    // 复用的临时向量
    private final Vector2f tempVec1 = new Vector2f();
    private final Vector2f tempVec2 = new Vector2f();
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        shipSprites.clear();
        shipLengths.clear();
        tailStates.clear();
        cachedTailWeapons.clear();
        swingPhase.clear();
        prevShipVelocity.clear();
        prevHullLevel.clear();
        damageTwitchAngle.clear();
        prevSystemActive.clear();
        systemFlickAngle.clear();
        severeDamageStiffness.clear();
        severeDamagePhase.clear();
        rendererAdded = false;
        
        // 不再在这里加载图片，而是在遇到舰船时动态加载
        
        // 添加渲染器
        if (!rendererAdded && engine != null) {
            try {
                rendererInstance = new Moci_ShroudedDragonTailRenderer(shipSprites, shipLengths, tailStates);
                engine.addLayeredRenderingPlugin(rendererInstance);
                rendererAdded = true;
            } catch (Exception e) {
                Global.getLogger(this.getClass()).error("Failed to add tail renderer: " + e.getMessage());
            }
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        
        // 清理已死亡的舰船状态
        Iterator<ShipAPI> tailIter = tailStates.keySet().iterator();
        while (tailIter.hasNext()) {
            ShipAPI ship = tailIter.next();
            if (!isShipValid(ship)) {
                tailIter.remove();
                cleanupShipState(ship);
            }
        }
        
        // 清理已缓存的武器引用
        Iterator<ShipAPI> cacheIter = cachedTailWeapons.keySet().iterator();
        while (cacheIter.hasNext()) {
            if (!isShipValid(cacheIter.next())) {
                cacheIter.remove();
            }
        }
        
        // 处理所有已注册的舰船
        for (ShipAPI ship : engine.getShips()) {
            if (!isTargetShip(ship)) {
                continue;
            }
            
            // 首次遇到这个舰船时，加载对应的图片
            loadTailSpritesForShip(ship);
            
            // 每帧持续隐藏原始尾部武器贴图（防止外部效果如放大熵重置透明度）
            hideTailWeapon(ship);
            
            // 获取或创建尾部状态
            Moci_ShroudedDragonTailRenderer.TailState state = tailStates.get(ship);
            if (state == null) {
                state = createInitialTailState(ship);
                tailStates.put(ship, state);
            }
            
            calculateTailPhysics(ship, state, amount);
        }
    }
    
    private boolean isShipValid(ShipAPI ship) {
        return ship != null && ship.isAlive() && !ship.isHulk() && engine.isEntityInPlay(ship);
    }
    
    private boolean isTargetShip(ShipAPI ship) {
        // 检查是否是已注册的舰船
        if (!isShipValid(ship)) return false;
        String hullId = ship.getHullSpec().getHullId();
        return Moci_TailConfigRegistry.hasConfig(hullId);
    }

    private Moci_TailConfig getTailConfig(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        return Moci_TailConfigRegistry.getConfig(ship.getHullSpec().getHullId());
    }

    private String getTailWeaponSlotId(ShipAPI ship) {
        Moci_TailConfig config = getTailConfig(ship);
        if (config != null && config.tailWeaponSlotId != null && !config.tailWeaponSlotId.isEmpty()) {
            return config.tailWeaponSlotId;
        }
        return TAIL_WEAPON_ID;
    }

    private Vector2f getMountLocation(ShipAPI ship) {
        Moci_TailConfig config = getTailConfig(ship);
        if (config != null && config.mountLocation != null) {
            return config.mountLocation;
        }
        return MOUNT_LOCATION;
    }

    private WeaponAPI getTailWeapon(ShipAPI ship) {
        WeaponAPI tailWeapon = cachedTailWeapons.get(ship);
        String tailWeaponSlotId = getTailWeaponSlotId(ship);

        if (tailWeapon != null) {
            if (tailWeapon.getShip() == ship
                    && tailWeapon.getSlot() != null
                    && tailWeaponSlotId.equals(tailWeapon.getSlot().getId())) {
                return tailWeapon;
            }
            cachedTailWeapons.remove(ship);
        }

        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.getSlot() != null && tailWeaponSlotId.equals(w.getSlot().getId())) {
                cachedTailWeapons.put(ship, w);
                return w;
            }
        }

        return null;
    }
    
    /**
     * 为指定舰船加载尾巴图片
     * 根据配置动态加载对应的精灵
     */
    private void loadTailSpritesForShip(ShipAPI ship) {
        // 如果已经加载过，跳过
        if (shipSprites.containsKey(ship)) {
            return;
        }
        
        String hullId = ship.getHullSpec().getHullId();
        Moci_TailConfig config = Moci_TailConfigRegistry.getConfig(hullId);
        
        if (config == null) {
            Global.getLogger(this.getClass()).warn("No tail config found for ship: " + hullId);
            return;
        }
        
        // 加载图片
        SpriteAPI[] sprites = new SpriteAPI[config.numSegments];
        float[] lengths = new float[config.numSegments];
        
        for (int i = 0; i < config.numSegments; i++) {
            try {
                String spritePath = config.spritePaths[i];
                sprites[i] = Global.getSettings().getSprite(spritePath);
                lengths[i] = (sprites[i] != null) ? sprites[i].getHeight() : 0f;
            } catch (Exception e) {
                Global.getLogger(this.getClass()).warn(
                    "Failed to load tail sprite " + i + " for " + hullId + ": " + e.getMessage());
                sprites[i] = null;
                lengths[i] = 0f;
            }
        }
        
        shipSprites.put(ship, sprites);
        shipLengths.put(ship, lengths);
        
        Global.getLogger(this.getClass()).info(
            "Loaded " + config.numSegments + " tail sprites for " + hullId);
    }
    
    private Moci_ShroudedDragonTailRenderer.TailState createInitialTailState(ShipAPI ship) {
        String hullId = ship.getHullSpec().getHullId();
        Moci_TailConfig config = Moci_TailConfigRegistry.getConfig(hullId);
        
        if (config == null) {
            Global.getLogger(this.getClass()).warn("No config found for ship: " + hullId);
            return new Moci_ShroudedDragonTailRenderer.TailState();
        }
        
        Moci_ShroudedDragonTailRenderer.TailState state = new Moci_ShroudedDragonTailRenderer.TailState();
        // 初始角度为舰船后方
        float initialAngle = MathUtils.clampAngle(ship.getFacing() + 180f);
        for (int i = 0; i < config.numSegments; i++) {
            Moci_ShroudedDragonTailRenderer.SegmentData segment = new Moci_ShroudedDragonTailRenderer.SegmentData();
            segment.angle = initialAngle;
            state.segments.add(segment);
        }
        return state;
    }

    private void hideTailWeapon(ShipAPI ship) {
        // 使用缓存避免每帧遍历武器列表
        WeaponAPI tailWeapon = getTailWeapon(ship);
        if (tailWeapon == null) {
            // 首次查找并缓存
            String tailWeaponSlotId = getTailWeaponSlotId(ship);
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w.getSlot() != null && tailWeaponSlotId.equals(w.getSlot().getId())) {
                    tailWeapon = w;
                    cachedTailWeapons.put(ship, w);
                    break;
                }
            }
        }
        
        if (tailWeapon != null) {
            try {
                hideSprite(tailWeapon.getSprite());
                hideSprite(tailWeapon.getBarrelSpriteAPI());
                hideSprite(tailWeapon.getUnderSpriteAPI());
                hideSprite(tailWeapon.getGlowSpriteAPI());
            } catch (Exception e) {
                // 武器可能已失效，清除缓存
                cachedTailWeapons.remove(ship);
            }
        }
    }
    
    private void hideSprite(SpriteAPI sprite) {
        if (sprite != null) {
            sprite.setAlphaMult(0f);
            sprite.setColor(TRANSPARENT);
        }
    }

    private void cleanupShipState(ShipAPI ship) {
        cachedTailWeapons.remove(ship);
        swingPhase.remove(ship);
        prevShipVelocity.remove(ship);
        prevHullLevel.remove(ship);
        damageTwitchAngle.remove(ship);
        prevSystemActive.remove(ship);
        systemFlickAngle.remove(ship);
        severeDamageStiffness.remove(ship);
        severeDamagePhase.remove(ship);
    }
    
    /**
     * 计算尾部物理 - 直接角度更新 + 完整仿生行为
     */
    private void calculateTailPhysics(
            ShipAPI ship, 
            Moci_ShroudedDragonTailRenderer.TailState state, 
            float amount) {
        Moci_TailConfig config = getTailConfig(ship);
        float balanceResponse = (config != null) ? config.balanceResponse : BALANCE_RESPONSE;
        float balanceMaxAngle = (config != null) ? config.balanceMaxAngle : BALANCE_MAX_ANGLE;
        float inertiaResponse = (config != null) ? config.inertiaResponse : INERTIA_RESPONSE;
        float inertiaMaxAngle = (config != null) ? config.inertiaMaxAngle : INERTIA_MAX_ANGLE;
        float strafeResponse = (config != null) ? config.strafeResponse : STRAFE_RESPONSE;
        float strafeMaxAngle = (config != null) ? config.strafeMaxAngle : STRAFE_MAX_ANGLE;
        float rootLerpSpeed = (config != null) ? config.rootLerpSpeed : ROOT_LERP_SPEED;
        float followLerpSpeed = (config != null) ? config.followLerpSpeed : FOLLOW_LERP_SPEED;
        float swingFrequency = (config != null) ? config.swingFrequency : SWING_FREQUENCY;
        float swingAmplitude = (config != null) ? config.swingAmplitude : SWING_AMPLITUDE;
        float safeSwingFrequency = Math.max(0.001f, swingFrequency);
        float compressionSensitivity =
            (config != null) ? Math.max(0f, Math.min(1f, config.compressionSensitivity)) : 1f;
        
        Vector2f shipLocation = ship.getLocation();
        Vector2f shipVelocity = ship.getVelocity();
        float shipFacing = ship.getFacing();
        float shipAngularVel = ship.getAngularVelocity();  // 度/秒
        float shipSpeed = shipVelocity.length();
        
        // 尾部基础朝向（舰船后方）
        float baseAngle = MathUtils.clampAngle(shipFacing + 180f);
        
        // ==================== 仿生学行为计算 ====================
        
        // 1. 角动量平衡：舰船转向时，尾巴反向弯曲
        float balanceBend = -shipAngularVel * balanceResponse;
        balanceBend = Math.max(-balanceMaxAngle, Math.min(balanceMaxAngle, balanceBend));
        
        // 2. 惯性响应：加速/减速时尾巴向反方向甩
        float inertiaBend = 0f;
        float facingRad = (float)Math.toRadians(shipFacing);
        float sideX = -(float)Math.sin(facingRad);  // 舰船左侧方向
        float sideY = (float)Math.cos(facingRad);
        
        Vector2f prevVel = prevShipVelocity.get(ship);
        if (prevVel != null && amount > 0.001f) {
            // 计算舰船坐标系下的加速度
            float accelX = (shipVelocity.x - prevVel.x) / amount;
            float accelY = (shipVelocity.y - prevVel.y) / amount;
            // 将加速度投影到舰船侧向
            float sideAccel = accelX * sideX + accelY * sideY;
            // 侧向加速度转换为尾巴弯曲
            inertiaBend = -sideAccel * inertiaResponse;
            inertiaBend = Math.max(-inertiaMaxAngle, Math.min(inertiaMaxAngle, inertiaBend));
        }
        prevShipVelocity.put(ship, new Vector2f(shipVelocity));
        
        // 2.5 横移响应：横移时尾巴向反方向飘动（基于侧向速度占最大速度的百分比）
        float strafeBend = 0f;
        float maxSpeed = ship.getMaxSpeed();
        if (maxSpeed > 0.01f) {
            // 计算侧向速度分量
            float sideVelocity = shipVelocity.x * sideX + shipVelocity.y * sideY;
            // 侧向速度百分比
            float strafePct = sideVelocity / maxSpeed;
            // 侧向速度转换为尾巴弯曲（向左横移=正侧向速度，尾巴向左弯=正角度）
            strafeBend = strafePct * strafeMaxAngle * strafeResponse;
            strafeBend = Math.max(-strafeMaxAngle, Math.min(strafeMaxAngle, strafeBend));
        }
        
        // 3. 受伤反应：受伤时尾巴抽搐
        float twitchBend = 0f;
        float currentHullLevel = ship.getHullLevel();
        Float prevHull = prevHullLevel.get(ship);
        Float currentTwitch = damageTwitchAngle.get(ship);
        if (currentTwitch == null) currentTwitch = 0f;
        
        // 3.5 巨大伤害绷直+痉挛状态
        Float currentStiffness = severeDamageStiffness.get(ship);
        if (currentStiffness == null) currentStiffness = 0f;
        Float spasmPhase = severeDamagePhase.get(ship);
        if (spasmPhase == null) spasmPhase = 0f;
        
        if (prevHull != null) {
            float hullDamage = prevHull - currentHullLevel;
            if (hullDamage > SEVERE_DAMAGE_THRESHOLD) {
                // 巨大伤害：触发绷直+痉挛
                currentStiffness = 1f;
                // 同时触发一个强烈抽搐
                float twitchDir = (Math.random() > 0.5) ? 1f : -1f;
                currentTwitch = twitchDir * DAMAGE_TWITCH_STRENGTH * 1.5f;
            } else if (hullDamage > DAMAGE_TWITCH_THRESHOLD) {
                // 普通伤害：触发抽搐
                float twitchDir = (Math.random() > 0.5) ? 1f : -1f;
                currentTwitch = twitchDir * DAMAGE_TWITCH_STRENGTH * Math.min(hullDamage * 10f, 1f);
            }
        }
        prevHullLevel.put(ship, currentHullLevel);
        
        // 抽搐衰减
        if (Math.abs(currentTwitch) > 0.1f) {
            currentTwitch *= (float)Math.exp(-DAMAGE_TWITCH_DECAY * amount);
            twitchBend = currentTwitch;
        } else {
            currentTwitch = 0f;
        }
        damageTwitchAngle.put(ship, currentTwitch);
        
        // 过载检测：过载时也触发绷直+痉挛
        boolean isOverloaded = ship.getFluxTracker().isOverloaded();
        if (isOverloaded) {
            // 过载时维持绷直强度
            currentStiffness = Math.max(currentStiffness, OVERLOAD_STIFFNESS);
        }
        
        // 绷直衰减和痉挛相位更新
        float spasmBend = 0f;
        if (currentStiffness > 0.01f) {
            // 更新痉挛相位
            spasmPhase += amount * SEVERE_DAMAGE_SPASM_FREQ * 2f * (float)Math.PI;
            // 痉挛弯曲（随绷直强度衰减）
            spasmBend = SEVERE_DAMAGE_SPASM_AMP * currentStiffness * (float)Math.sin(spasmPhase);
            // 绷直强度衰减（过载时不衰减）
            if (!isOverloaded) {
                currentStiffness *= (float)Math.exp(-SEVERE_DAMAGE_DECAY * amount);
            }
        } else {
            currentStiffness = 0f;
        }
        severeDamageStiffness.put(ship, currentStiffness);
        severeDamagePhase.put(ship, spasmPhase);
        
        // 4. 战斗姿态：检测附近敌人
        boolean inCombat = false;
        for (ShipAPI enemy : engine.getShips()) {
            if (enemy.getOwner() != ship.getOwner() && !enemy.isHulk() && !enemy.isShuttlePod()) {
                float dist = MathUtils.getDistance(ship, enemy);
                if (dist < COMBAT_RANGE) {
                    inCombat = true;
                    break;
                }
            }
        }
        float combatFreqMult = inCombat ? COMBAT_FREQUENCY_MULT : 1f;
        float combatAmpMult = inCombat ? COMBAT_AMPLITUDE_MULT : 1f;
        
        // 5. 通量反应：高通量时僵硬/微颤
        float fluxLevel = ship.getFluxLevel();
        float fluxStiffness = 1f - fluxLevel * FLUX_STIFFNESS_RATE;  // 越高通量越僵硬
        float tremorBend = 0f;
        if (fluxLevel > FLUX_TREMOR_THRESHOLD) {
            // 高通量时微颤
            float tremorIntensity = (fluxLevel - FLUX_TREMOR_THRESHOLD) / (1f - FLUX_TREMOR_THRESHOLD);
            Float phase = swingPhase.get(ship);
            if (phase == null) phase = 0f;
            tremorBend = FLUX_TREMOR_AMPLITUDE * tremorIntensity * 
                (float)Math.sin(phase * FLUX_TREMOR_FREQUENCY / safeSwingFrequency);
        }
        
        // 6. 引擎联动：高速时拉直尾巴（基于速度百分比）
        float engineStraighten = 1f;
        float speedPct = (maxSpeed > 0.01f) ? (shipSpeed / maxSpeed) : 0f;
        if (speedPct > ENGINE_STRAIGHTEN_THRESHOLD) {
            float speedFactor = Math.min((speedPct - ENGINE_STRAIGHTEN_THRESHOLD) / (1f - ENGINE_STRAIGHTEN_THRESHOLD), 1f);
            engineStraighten = 1f - speedFactor * ENGINE_STRAIGHTEN_MAX;
        }
        
        // 7. 系统激活反应
        float systemFlick = 0f;
        boolean systemActive = ship.getSystem() != null && ship.getSystem().isActive();
        Boolean prevActive = prevSystemActive.get(ship);
        Float currentFlick = systemFlickAngle.get(ship);
        if (currentFlick == null) currentFlick = 0f;
        
        if (prevActive != null && !prevActive && systemActive) {
            // 系统刚激活：猛甩
            float flickDir = (Math.random() > 0.5) ? 1f : -1f;
            currentFlick = flickDir * SYSTEM_FLICK_STRENGTH;
        }
        prevSystemActive.put(ship, systemActive);
        
        // 眩动衰减
        if (Math.abs(currentFlick) > 0.1f) {
            currentFlick *= (float)Math.exp(-SYSTEM_FLICK_DECAY * amount);
            systemFlick = currentFlick;
        } else {
            currentFlick = 0f;
        }
        systemFlickAngle.put(ship, currentFlick);
        
        // 8. 机动抑制：高速移动或转向时抑制自主摆动
        // 计算综合机动强度（速度百分比 + 转向百分比）
        float maxTurnRate = ship.getMaxTurnRate();
        float turnPct = (maxTurnRate > 0.01f) ? (Math.abs(shipAngularVel) / maxTurnRate) : 0f;
        // 计算前向速度分量（用于判断是否在前进/后退）
        float forwardX = (float)Math.cos(facingRad);
        float forwardY = (float)Math.sin(facingRad);
        float forwardVelocity = shipVelocity.x * forwardX + shipVelocity.y * forwardY;
        float forwardPct = (maxSpeed > 0.01f) ? (Math.abs(forwardVelocity) / maxSpeed) : 0f;
        float sidePct = (maxSpeed > 0.01f) ? (Math.abs(shipVelocity.x * sideX + shipVelocity.y * sideY) / maxSpeed) : 0f;
        
        // 综合机动强度 = 前进强度 + 横移强度 + 转向强度
        float maneuverIntensity = Math.min(1f, forwardPct * 0.8f + sidePct * 1.2f + turnPct * 0.6f);
        
        // 机动抑制：机动越剧烈，自主摆动越弱
        float maneuverSuppress = 1f;
        if (maneuverIntensity > MANEUVER_SUPPRESS_THRESHOLD) {
            float suppressFactor = (maneuverIntensity - MANEUVER_SUPPRESS_THRESHOLD) / (1f - MANEUVER_SUPPRESS_THRESHOLD);
            maneuverSuppress = 1f - suppressFactor * MANEUVER_SUPPRESS_MAX;
        }
        
        // 转向时额外降低自主摆动幅度（保留原有逻辑但改用百分比）
        float swingSuppress = Math.max(SWING_MIN_AMPLITUDE, 1f - turnPct * 0.5f);
        
        // 计算尾部挂载点位置
        Vector2f mountLocation = getMountLocation(ship);
        tempVec1.set(mountLocation.x, mountLocation.y);
        tempVec2.set(0f, 0f);
        Vector2f baseMountPoint = VectorUtils.rotateAroundPivot(tempVec1, tempVec2, shipFacing);
        Vector2f.add(baseMountPoint, shipLocation, baseMountPoint);
        
        tempVec1.set(baseMountPoint);  // currentAttachPoint
        
        // 更新摆动相位（根部驱动）
        Float phase = swingPhase.get(ship);
        if (phase == null) phase = 0f;
        phase += amount * safeSwingFrequency * combatFreqMult * 2f * (float)Math.PI;
        swingPhase.put(ship, phase);
        
        // ==================== 合成根部目标角度 ====================
        // 自主摆动（受各种因素调节，机动时大幅抑制）
        float swingBend = swingAmplitude * swingSuppress * maneuverSuppress * engineStraighten * fluxStiffness * combatAmpMult 
            * (float)Math.sin(phase);
        // 机动响应（横移+惯性+平衡）在机动时增强
        float maneuverBoost = 1f + maneuverIntensity * 0.5f;  // 机动时响应增强
        float maneuverBend = (strafeBend + inertiaBend + balanceBend) * maneuverBoost;
        // 合成所有仿生行为
        float rootTargetBend = swingBend + maneuverBend + twitchBend + tremorBend + systemFlick;
        
        // 遍历每个体节
        for (int i = 0; i < state.segments.size(); i++) {
            Moci_ShroudedDragonTailRenderer.SegmentData segment = state.segments.get(i);
            float segmentLength = getSegmentLength(ship, i);
            if (segmentLength <= 0f) segmentLength = 1f;
            
            // 获取前一节的角度作为参考
            float parentAngle = (i == 0) ? baseAngle : state.segments.get(i - 1).angle;
            
            // 当前相对弯曲角度（相对于前一节的偏离）
            float currentBend = MathUtils.getShortestRotation(parentAngle, segment.angle);
            
            // ========== 直接角度更新 ==========
            float targetBend;
            float lerpSpeed;
            
            // 巨大伤害绷直状态：所有体节趋向直线 + 痉挛
            Float stiffness = severeDamageStiffness.get(ship);
            if (stiffness == null) stiffness = 0f;
            
            if (i == 0) {
                // 根部：追踪正弦波目标，绷直时趋向直线+痉挛
                float normalTarget = rootTargetBend;
                float stiffTarget = spasmBend;  // 绷直时只有痉挛
                targetBend = normalTarget * (1f - stiffness) + stiffTarget * stiffness;
                lerpSpeed = rootLerpSpeed + stiffness * 10f;  // 绷直时响应更快
            } else {
                // 后续体节：目标是与前一节成直线（bendAngle = 0）
                // 绷直时叠加微小的痉挛（随体节递减）
                float segmentSpasm = spasmBend * stiffness * (1f - i * 0.1f);
                targetBend = segmentSpasm;
                lerpSpeed = followLerpSpeed + stiffness * 8f;
            }
            
            // 插值计算：平滑地向目标角度靠近
            float lerpFactor = 1f - (float)Math.exp(-lerpSpeed * amount);
            float newBend = currentBend + (targetBend - currentBend) * lerpFactor;
            
            // ========== 骨架约束（硬限制） ==========
            float maxAngle = getBoneMaxAngle(ship, i);
            if (Math.abs(newBend) > maxAngle) {
                newBend = Math.signum(newBend) * maxAngle;
            }
            
            // 计算最终绝对角度
            segment.angle = MathUtils.clampAngle(parentAngle + newBend);
            
            // 计算体节位置
            float pivotOffset = calculatePivotOffset(ship, i, segmentLength);
            float radAngle = (float)Math.toRadians(segment.angle);
            tempVec2.set(
                (float)Math.cos(radAngle) * pivotOffset,
                (float)Math.sin(radAngle) * pivotOffset
            );
            Vector2f.add(tempVec1, tempVec2, segment.position);
            
            // ========== 压缩力计算（基于舰船命令） ==========
            // 检测舰船引擎命令
            boolean isAcceleratingBackwards = ship.getEngineController().isAcceleratingBackwards();
            boolean isAccelerating = ship.getEngineController().isAccelerating();
            boolean isDecelerating = ship.getEngineController().isDecelerating();
            
            float currentCompression = segment.compression;
            float targetCompression = 0f;
            float responseSpeed;
            
            if (isAcceleratingBackwards || isDecelerating) {
                // 后退或减速命令：压缩尾巴
                targetCompression = compressionSensitivity;
                responseSpeed = (config != null) ? config.compressionSpeed : 8f;
            } else if (isAccelerating) {
                // 前进命令：快速释放
                targetCompression = 0f;
                responseSpeed = (config != null) ? config.compressionSpeed * 1.5f : 12f;  // 前进时释放更快
            } else {
                // 无命令：缓慢释放
                targetCompression = 0f;
                responseSpeed = (config != null) ? config.decompressionSpeed : 2f;
            }
            
            // 平滑插值到目标压缩状态
            float compressionLerpFactor = 1f - (float)Math.exp(-responseSpeed * amount);
            segment.compression = currentCompression + (targetCompression - currentCompression) * compressionLerpFactor;
            
            // 限制范围
            segment.compression = Math.max(0f, Math.min(1f, segment.compression));
            
            // 计算下一节的连接点（使用动态重叠系数）
            float dynamicOverlapFactor = calculateDynamicOverlapFactor(ship, i, segment.compression);
            float linkDistance = segmentLength * dynamicOverlapFactor;
            tempVec2.set(
                (float)Math.cos(radAngle) * linkDistance,
                (float)Math.sin(radAngle) * linkDistance
            );
            Vector2f.add(tempVec1, tempVec2, tempVec1);
        }
    }
    
    private float calculatePivotOffset(ShipAPI ship, int segmentIndex, float segmentLength) {
        Moci_TailConfig config = getTailConfig(ship);
        if (config == null) return segmentLength / 2f;
        return config.calculatePivotOffset(segmentIndex, segmentLength);
    }
    
    private float calculateOverlapFactor(ShipAPI ship, int segmentIndex) {
        Moci_TailConfig config = getTailConfig(ship);
        if (config == null) return 0.82f;
        
        return config.calculateOverlapFactor(segmentIndex);
    }
    
    /**
     * 计算动态重叠系数（含压缩状态）
     */
    private float calculateDynamicOverlapFactor(ShipAPI ship, int segmentIndex, float compression) {
        Moci_TailConfig config = getTailConfig(ship);
        if (config == null) return 0.82f;
        
        return config.calculateDynamicOverlapFactor(segmentIndex, compression);
    }

    private float getSegmentLength(ShipAPI ship, int index) {
        float[] lengths = shipLengths.get(ship);
        if (lengths != null && index >= 0 && index < lengths.length) {
            return lengths[index];
        }
        return 0f;
    }
    
    /**
     * 获取指定体节的最大弯曲角度
     * 使用配置中的参数
     */
    private float getBoneMaxAngle(ShipAPI ship, int segmentIndex) {
        Moci_TailConfig config = getTailConfig(ship);
        if (config == null) {
            if (segmentIndex == 0) {
                return BONE_ROOT_MAX_ANGLE;
            }
            float baseAngle = BONE_SECOND_BASE_ANGLE + (segmentIndex - 1) * BONE_ANGLE_INCREMENT;
            if (segmentIndex >= 2 && segmentIndex <= 4) {
                baseAngle += BONE_MID_SECTION_BONUS;
            }
            return baseAngle;
        }
        
        return config.getBoneMaxAngle(segmentIndex);
    }
}
