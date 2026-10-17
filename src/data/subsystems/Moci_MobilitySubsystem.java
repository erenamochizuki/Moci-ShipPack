package data.subsystems;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.MagicSubsystem;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import data.hullmods.Moci_MobileSuitRepairTracker;
import data.scripts.util.Moci_TextLoader;

import java.util.Locale;

/**
 * 机动子系统 - 高级加力燃烧器
 * 
 * 提供临时的机动性增强：
 * - 最大速度提升（动态效果级别）
 * - 加速度提升（动态效果级别）
 * - 基于充能系统而非固定冷却
 * - 支持所有MagicLib子系统参数
 */
public class Moci_MobilitySubsystem extends MagicSubsystem {
    private static final String TEXT_ID = "Moci_MobilitySubsystem";

    // ========== 不同船体尺寸的基础数值配置 ==========
    private static final float FRIGATE_SPEED_BONUS = 450f;
    private static final float DESTROYER_SPEED_BONUS = 350f;
    private static final float CRUISER_SPEED_BONUS = 250f;
    private static final float CAPITAL_SPEED_BONUS = 150f;

    private static final float FRIGATE_ACCEL_BONUS = 1000f;
    private static final float DESTROYER_ACCEL_BONUS = 800f;
    private static final float CRUISER_ACCEL_BONUS = 600f;
    private static final float CAPITAL_ACCEL_BONUS = 400f;

    private static final float FRIGATE_DECEL_BONUS = 1000f;
    private static final float DESTROYER_DECEL_BONUS = 800f;
    private static final float CRUISER_DECEL_BONUS = 600f;
    private static final float CAPITAL_DECEL_BONUS = 400f;

    private static final float FRIGATE_TURN_BONUS = 50f;
    private static final float DESTROYER_TURN_BONUS = 40f;
    private static final float CRUISER_TURN_BONUS = 30f;
    private static final float CAPITAL_TURN_BONUS = 20f;

    // ========== 效果参数 ==========
    private float maxSpeedBonus = 450f; // 最大速度提升（绝对值）
    private float maxAccelerationBonus = 1000f; // 最大加速度提升（绝对值）
    private float maxDecelerationBonus = 1000f; // 最大减速度提升（绝对值）
    private float maxTurnBonus = 0f; // 最大转向提升（百分比，暂不使用）

    private boolean useInstantStopBurstMode = true; //false: 原版模式 true: 瞬停爆发模式，启动瞬间清零速度向量，并将加速度/减速度翻倍

    // ========== 充能系统参数 ==========
    private int maxCharges = 4; // 最大充能数
    private float chargeRegenTime = 6f; // 充能恢复时间（秒）

    // ========== 时间参数 ==========
    private float inDuration = 0.33f; // 启动时间
    private float activeDuration = 0.66f; // 激活持续时间
    private float outDuration = 0.33f; // 结束时间

    // ========== 幅能消耗参数 ==========
    private float fluxPerSecond = 1f; // 每秒幅能消耗（激活期间）
    private float activationFluxFlat = 0f; // 激活时固定幅能消耗
    private float activationFluxPercent = 0f; // 激活时百分比幅能消耗
    private boolean isHardFlux = false; // 是否为硬通量

    // ========== 目标系统参数（未使用） ==========
    private boolean requiresTarget = false; // 是否需要目标
    private boolean targetOnlyEnemies = true; // 是否只能选择敌方目标
    private float range = -1f; // 射程（-1表示无限制）

    // ========== 其他参数 ==========
    private boolean canUseWhileOverloaded = false; // 过载时是否可用
    private boolean canUseWhileVenting = false; // 散热时是否可用
    private boolean usesChargesOnActivate = true; // 激活时是否消耗充能
    private float systemStatsEffectMult = 1f; // 系统技能效果倍数

    // ========== AI行为参数 ==========
    private float aiChaseMinDistance = 300f; // AI追击模式最小距离
    private float aiChaseMaxDistance = 2000f; // AI追击模式最大距离
    private float aiChaseSpeedThreshold = 0.5f; // AI追击模式速度阈值（相对最大速度的百分比）
    private float aiFleeHullThreshold = 0.4f; // AI逃跑模式血量阈值
    private float aiFleeDistance = 1000f; // AI逃跑模式触发距离

    // ========== 内部状态 ==========
    private String effectId = "moci_mobility_subsystem";
    private IntervalUtil glowInterval = new IntervalUtil(0.1f, 0.1f);

    public Moci_MobilitySubsystem(ShipAPI ship) {
        super(ship);
        // 根据船体尺寸自动配置参数
        setupParametersByHullSize(ship.getHullSize());
    }
    
    /**
     * 根据船体尺寸自动配置参数
     */
    private void setupParametersByHullSize(HullSize hullSize) {
        switch (hullSize) {
            case FRIGATE:
                maxSpeedBonus = FRIGATE_SPEED_BONUS;
                maxAccelerationBonus = FRIGATE_ACCEL_BONUS;
                maxDecelerationBonus = FRIGATE_DECEL_BONUS;
                maxTurnBonus = FRIGATE_TURN_BONUS;
                break;
            case DESTROYER:
                maxSpeedBonus = DESTROYER_SPEED_BONUS;
                maxAccelerationBonus = DESTROYER_ACCEL_BONUS;
                maxDecelerationBonus = DESTROYER_DECEL_BONUS;
                maxTurnBonus = DESTROYER_TURN_BONUS;
                break;
            case CRUISER:
                maxSpeedBonus = CRUISER_SPEED_BONUS;
                maxAccelerationBonus = CRUISER_ACCEL_BONUS;
                maxDecelerationBonus = CRUISER_DECEL_BONUS;
                maxTurnBonus = CRUISER_TURN_BONUS;
                break;
            case CAPITAL_SHIP:
                maxSpeedBonus = CAPITAL_SPEED_BONUS;
                maxAccelerationBonus = CAPITAL_ACCEL_BONUS;
                maxDecelerationBonus = CAPITAL_DECEL_BONUS;
                maxTurnBonus = CAPITAL_TURN_BONUS;
                break;
            default:
                // 默认使用护卫舰数值
                maxSpeedBonus = FRIGATE_SPEED_BONUS;
                maxAccelerationBonus = FRIGATE_ACCEL_BONUS;
                maxDecelerationBonus = FRIGATE_DECEL_BONUS;
                maxTurnBonus = FRIGATE_TURN_BONUS;
                break;
        }
    }
    
    /**
     * 获取描述参数（供船插使用）
     */
    public static String getDescriptionParam(int index, HullSize hullSize) {
        switch (index) {
            case 0: // 速度加成
                switch (hullSize) {
                    case FRIGATE: return "" + (int) FRIGATE_SPEED_BONUS;
                    case DESTROYER: return "" + (int) DESTROYER_SPEED_BONUS;
                    case CRUISER: return "" + (int) CRUISER_SPEED_BONUS;
                    case CAPITAL_SHIP: return "" + (int) CAPITAL_SPEED_BONUS;
                    default: return "" + (int) FRIGATE_SPEED_BONUS;
                }
            case 1: // 加速度加成
                switch (hullSize) {
                    case FRIGATE: return "" + (int) FRIGATE_ACCEL_BONUS;
                    case DESTROYER: return "" + (int) DESTROYER_ACCEL_BONUS;
                    case CRUISER: return "" + (int) CRUISER_ACCEL_BONUS;
                    case CAPITAL_SHIP: return "" + (int) CAPITAL_ACCEL_BONUS;
                    default: return "" + (int) FRIGATE_ACCEL_BONUS;
                }
            case 2: // 转向加成
                switch (hullSize) {
                    case FRIGATE: return "" + (int) FRIGATE_TURN_BONUS;
                    case DESTROYER: return "" + (int) DESTROYER_TURN_BONUS;
                    case CRUISER: return "" + (int) CRUISER_TURN_BONUS;
                    case CAPITAL_SHIP: return "" + (int) CAPITAL_TURN_BONUS;
                    default: return "" + (int) FRIGATE_TURN_BONUS;
                }
            case 3: // 最大充能数
                return "4";
            case 4: // 充能恢复时间
                return "6";
        }
        return null;
    }

    // ========== 基础系统参数 ==========

    @Override
    public int getOrder() {
        // MagicLib 按 order 降序显示/分配按键；让喷气背包固定排在 NTD 前面。
        return ORDER_SHIP_MODULAR + 1;
    }

    @Override
    public float getBaseInDuration() {
        return inDuration; // 启动时间
    }

    @Override
    public float getBaseActiveDuration() {
        return activeDuration; // 激活持续时间
    }

    @Override
    public float getBaseOutDuration() {
        return outDuration; // 结束时间
    }

    @Override
    public float getBaseCooldownDuration() {
        return 0f; // 使用充能系统，无冷却时间
    }

    @Override
    public boolean isToggle() {
        return false; // 非切换式，一次性激活
    }

    @Override
    public boolean canAssignKey() {
        return true; // 可以分配按键
    }

    // ========== 充能系统参数 ==========

    @Override
    public int getMaxCharges() {
        return maxCharges; // 最大充能数
    }

    @Override
    public float getBaseChargeRechargeDuration() {
        return chargeRegenTime; // 充能恢复时间
    }

    @Override
    public boolean usesChargesOnActivate() {
        return usesChargesOnActivate; // 激活时消耗充能
    }

    // ========== 目标系统参数 ==========

    @Override
    public boolean requiresTarget() {
        return requiresTarget; // 是否需要目标
    }

    @Override
    public boolean targetOnlyEnemies() {
        return targetOnlyEnemies; // 是否只能选择敌方目标
    }

    @Override
    protected float getRange() {
        return range; // 射程
    }

    // ========== 幅能消耗参数 ==========

    @Override
    public float getFluxCostFlatOnActivation() {
        return activationFluxFlat; // 激活时固定幅能消耗
    }

    @Override
    public float getFluxCostPercentOnActivation() {
        return activationFluxPercent; // 激活时百分比幅能消耗
    }

    @Override
    public boolean isHardFluxForActivation() {
        return isHardFlux; // 激活时是否为硬通量
    }

    @Override
    public float getFluxCostFlatPerSecondWhileActive() {
        return fluxPerSecond; // 激活期间每秒固定幅能消耗
    }

    @Override
    public float getFluxCostPercentPerSecondWhileActive() {
        return 0f; // 激活期间每秒百分比幅能消耗（未使用）
    }

    @Override
    public boolean isHardFluxPerSecondWhileActive() {
        return isHardFlux; // 激活期间是否为硬通量
    }

    // ========== 其他系统参数 ==========

    @Override
    public boolean canUseWhileOverloaded() {
        return canUseWhileOverloaded; // 过载时是否可用
    }

    @Override
    public boolean canUseWhileVenting() {
        return canUseWhileVenting; // 散热时是否可用
    }

    @Override
    public float getSystemStatsEffectMult() {
        return systemStatsEffectMult; // 系统技能效果倍数
    }

    @Override
    public boolean canActivate() {
        // 检查舰船状态
        if (!ship.isAlive() || ship.isHulk() || isBlockedDuringRepair()) {
            return false;
        }

        // 检查是否过载或散热中
        if (ship.getFluxTracker().isOverloaded() || ship.getFluxTracker().isVenting()) {
            return false;
        }

        return true;
    }

    @Override
    public boolean shouldActivateAI(float amount) {
        // AI激活逻辑
        if (!canActivate()) {
            return false;
        }

        // 优先检查：如果舰船正在撤退，无条件激活机动系统
        if (ship.isRetreating()) {
            return true;
        }

        // 当AI需要快速机动时激活（比如追击或逃跑）
        ShipAPI target = ship.getShipTarget();
        if (target != null) {
            float distance = Vector2f.sub(target.getLocation(), ship.getLocation(), null).length();

            // 如果目标距离适中且AI想要接近，激活机动系统
            if (distance > aiChaseMinDistance && distance < aiChaseMaxDistance) {
                // 检查舰船是否在加速（表示想要接近）
                Vector2f velocity = ship.getVelocity();
                if (velocity.length() > ship.getMaxSpeed() * aiChaseSpeedThreshold) {
                    return true;
                }
            }

            // 如果血量较低且有敌人接近，激活机动系统逃跑
            if (ship.getHullLevel() < aiFleeHullThreshold && distance < aiFleeDistance) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void onActivate() {
        if (useInstantStopBurstMode) {
            resetShipVelocity();
        }

        // 播放激活音效
        Global.getSoundPlayer().playSound("system_maneuvering_jets", 1f, 1f,
        ship.getLocation(), ship.getVelocity());
    }

    @Override
    public void onFinished() {
        // 系统结束时移除效果
        removeAllEffects();

    }

    @Override
    public void onShipDeath() {
        // 舰船死亡时清理效果
        removeAllEffects();
    }

    @Override
    public void advance(float amount, boolean isPaused) {
        if (isPaused)
            return;

        if (isBlockedDuringRepair()) {
            forceStopForRepair();
            return;
        }

        // 动态应用效果，基于effectLevel
        applyDynamicEffects();
        updateVisualEffects(amount);

        // 应用引擎光效
        applyEngineGlowEffects();
    }

    /**
     * 动态应用机动性增强效果
     * 基于effectLevel实现平滑的效果变化
     */
    private void applyDynamicEffects() {
        float effectLevel = getEffectLevel(); // 0.0 到 1.0

        if (effectLevel > 0f) {
            float mobilityBonusMultiplier = useInstantStopBurstMode ? 2f : 1f;

            // 计算当前效果强度
            float currentAccelBonus = maxAccelerationBonus * mobilityBonusMultiplier * effectLevel;
            float currentDecelBonus = maxDecelerationBonus * mobilityBonusMultiplier * effectLevel;

            // 在OUT状态时，移除最大速度限制让舰船能够减速
            if (state == State.OUT) {
                // 关键：在OUT状态时移除速度加成，让舰船减速回正常速度
                ship.getMutableStats().getMaxSpeed().unmodify(effectId);
            } else {
                // 在IN和ACTIVE状态时，应用速度加成
                float currentSpeedBonus = maxSpeedBonus * effectLevel;
                ship.getMutableStats().getMaxSpeed().modifyFlat(effectId, currentSpeedBonus);
            }

            // 加速度和减速度在所有状态下都应用，确保平滑过渡
            ship.getMutableStats().getAcceleration().modifyFlat(effectId, currentAccelBonus);
            ship.getMutableStats().getDeceleration().modifyFlat(effectId, currentDecelBonus);

            // 转向效果
            if (maxTurnBonus > 0f) {
                float currentTurnBonus = maxTurnBonus * effectLevel;
                ship.getMutableStats().getTurnAcceleration().modifyPercent(effectId, currentTurnBonus);
                ship.getMutableStats().getMaxTurnRate().modifyPercent(effectId, currentTurnBonus);
            }

        } else {
            // 效果级别为0时，移除所有效果
            removeAllEffects();
        }
    }

    /**
     * 瞬停爆发模式：启动时将舰船当前平移速度清零，方便观察爆发式机动效果。
     */
    private void resetShipVelocity() {
        ship.getVelocity().set(0f, 0f);
    }

    /**
     * 应用引擎光效
     * 基于effectLevel动态调整引擎光晕效果
     */
    private void applyEngineGlowEffects() {
        float effectLevel = getEffectLevel();

        if (effectLevel > 0f) {
            // 计算引擎光效强度
            float ENGINE_GLOW_LENGTH_MULT = 1f + 2f * effectLevel; // 1.0 到 3.0
            float ENGINE_GLOW_WIDTH_MULT = 1f + 1f * effectLevel; // 1.0 到 2.0
            float ENGINE_GLOW_GLOW_MULT = 1f + 2f * effectLevel; // 1.0 到 3.0

            // 正确的引擎光效设置方法 - 每帧调用
            ship.getEngineController().extendFlame(
                    this,
                    ENGINE_GLOW_LENGTH_MULT,
                    ENGINE_GLOW_WIDTH_MULT,
                    ENGINE_GLOW_GLOW_MULT);
        }
        // 注意：当effectLevel为0时，不调用extendFlame，引擎光效会自动恢复正常
    }

    /**
     * 移除所有机动性增强效果
     */
    private void removeAllEffects() {
        // 移除机动性效果
        ship.getMutableStats().getMaxSpeed().unmodify(effectId);
        ship.getMutableStats().getAcceleration().unmodify(effectId);
        ship.getMutableStats().getDeceleration().unmodify(effectId);
        ship.getMutableStats().getTurnAcceleration().unmodify(effectId);
        ship.getMutableStats().getMaxTurnRate().unmodify(effectId);

        // 引擎光效会在不调用extendFlame时自动恢复正常
        // 无需手动清理
    }

    private boolean isBlockedDuringRepair() {
        return Moci_MobileSuitRepairTracker.isShipRepairing(ship);
    }

    private void forceStopForRepair() {
        removeAllEffects();
        if (state != State.READY) {
            setState(State.READY);
        }
    }

    /**
     * 更新视觉效果
     */
    private void updateVisualEffects(float amount) {
        glowInterval.advance(amount);
        // 预留用于未来的视觉效果扩展
    }

    @Override
    public void renderWorld(ViewportAPI viewport) {
        if (isBlockedDuringRepair())
            return;

        if (!isOn())
            return;

        float effectLevel = getEffectLevel();
        if (effectLevel <= 0f)
            return;

        // 为每个引擎生成尾迹粒子
        if (ship.getEngineController() != null && ship.getEngineController().getShipEngines() != null) {
            for (Object engineObj : ship.getEngineController().getShipEngines()) {
                if (engineObj instanceof com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI) {
                    com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI engine = 
                        (com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI) engineObj;
                    
                    // 只为激活的引擎生成粒子
                    if (engine.isActive() && engine.getEngineSlot() != null) {
                        Vector2f engineLoc = engine.getLocation();
                        float angle = engine.getEngineSlot().getAngle() + ship.getFacing();
                        float length = engine.getEngineSlot().getLength() * 2f * effectLevel;
                        float width = engine.getEngineSlot().getWidth();
                        
                        spawnTrailParticles(engineLoc, angle, length, width, effectLevel, engine);
                    }
                }
            }
        }
    }
    // 粒子效果基础参数（可调整）
    private static final float PARTICLE_SPAWN_CHANCE = 0.5f;  // 粒子生成概率（0-1）
    private static final float PARTICLE_VEL_MIN = 0.25f;  // 粒子速度最小倍数
    private static final float PARTICLE_VEL_MAX = 0.5f;  // 粒子速度最大倍数
    private static final float PARTICLE_CONE_ANGLE = 30f;  // 粒子扩散角度
    private static final float PARTICLE_DURATION_MIN = 0.4f;  // 粒子最小持续时间
    private static final float PARTICLE_DURATION_MAX = 0.6f;  // 粒子最大持续时间
    
    // 粒子尺寸和速度的最小保底值
    private static final float PARTICLE_SIZE_MIN_FALLBACK = 1f;  // 粒子最小尺寸保底值
    private static final float PARTICLE_SPEED_MIN_FALLBACK = 200f;  // 粒子最小速度保底值

    /**
     * 生成引擎喷射粒子特效（根据引擎尺寸动态调整）
     * 
     * @param engineLoc 引擎位置
     * @param angle 引擎角度（世界坐标系，指向引擎喷口方向）
     * @param length 引擎长度
     * @param width 引擎宽度
     * @param effectLevel 效果级别（0.0-1.0）
     * @param engine 引擎对象（用于获取颜色）
     */
    private void spawnTrailParticles(Vector2f engineLoc, float angle, float length, float width, float effectLevel, 
                                     com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI engine) {
        try {
            // 根据概率生成粒子
            if (Math.random() > PARTICLE_SPAWN_CHANCE) return;
            
            // 获取引擎颜色
            Color engineColor = engine.getEngineColor();
            if (engineColor == null) {
                engineColor = new Color(0, 150, 255);
            }
            
            // 创建粒子颜色（使用引擎颜色，透明度基于effectLevel）
            Color particleColor = new Color(
                engineColor.getRed() / 255f,
                engineColor.getGreen() / 255f,
                engineColor.getBlue() / 255f,
                1f * effectLevel
            );
            
            // 计算粒子扩散角度范围
            float halfConeAngle = PARTICLE_CONE_ANGLE / 2f;
            
            // 生成随机角度（在引擎喷射方向的锥形范围内）
            // 然后在这个反方向上加上随机扩散角度
            float randomOffset = (float) (Math.random() * PARTICLE_CONE_ANGLE - halfConeAngle);
            float particleAngle = angle + randomOffset;
            
            // 根据引擎长度计算粒子速度：length * 50 + 100，但不小于200
            float baseSpeed = Math.max(length * 50f + 100f, PARTICLE_SPEED_MIN_FALLBACK);
            float vel = (float) (Math.random() * (PARTICLE_VEL_MAX - PARTICLE_VEL_MIN) + PARTICLE_VEL_MIN) * baseSpeed;
            
            // 计算粒子速度向量
            Vector2f particleVel = new Vector2f(
                (float) Math.cos(Math.toRadians(particleAngle)) * vel,
                (float) Math.sin(Math.toRadians(particleAngle)) * vel
            );
            
            // 计算粒子起始位置（引擎位置稍微向后偏移）
            Vector2f particleLoc = new Vector2f(engineLoc);
            Vector2f offset = new Vector2f(
                (float) Math.cos(Math.toRadians(angle + 180f)),
                (float) Math.sin(Math.toRadians(angle + 180f))
            );
            Vector2f.add(particleLoc, offset, particleLoc);
            
            // 根据引擎宽度计算粒子尺寸：width * 0.5，但不小于1
            float particleSizeMin = Math.max(width * 0.5f, PARTICLE_SIZE_MIN_FALLBACK);
            float particleSizeMax = particleSizeMin * 3f;  // 最大尺寸是最小尺寸的3倍
            float particleSize = (float) (Math.random() * (particleSizeMax - particleSizeMin) + particleSizeMin);
            
            // 随机持续时间
            float duration = (float) (Math.random() * (PARTICLE_DURATION_MAX - PARTICLE_DURATION_MIN) + PARTICLE_DURATION_MIN);
            
            // 生成粒子（使用addHitParticle获得更好的视觉效果）
            Global.getCombatEngine().addHitParticle(
                particleLoc,
                particleVel,
                particleSize,
                1f * effectLevel,
                duration,
                particleColor
            );

        } catch (Exception e) {
            // 静默处理粒子生成错误
        }
    }

    @Override
    public String getDisplayText() {
        return text("display", "Jet Pack");
    }

    @Override
    public String getBriefText() {
        return text("brief", "Greatly increases speed");
    }

    @Override
    public String getStateText() {
        switch (state) {
            case READY:
                return text("state.ready", "Ready");
            case IN:
                return text("state.in", "Starting");
            case ACTIVE:
                return text("state.active", "Boosting");
            case OUT:
                return text("state.out", "Ending");
            case COOLDOWN:
                return text("state.cooldown", "Cooldown");
            default:
                return "";
        }
    }

    @Override
    public String getExtraInfoText() {
        if (isOn()) {
            float effectLevel = getEffectLevel();
            int currentSpeed = (int) (maxSpeedBonus * effectLevel);
            int currentAccel = (int) (maxAccelerationBonus * (useInstantStopBurstMode ? 2f : 1f) * effectLevel);
            return String.format(Locale.ROOT, text("extra.active", "Speed +%s Mobility +%s"), currentSpeed, currentAccel);
        }
        return null;
    }

    private String text(String key, String fallback) {
        return Moci_TextLoader.getText(TEXT_ID, key, fallback);
    }

    @Override
    public Color getHUDColor() {
        if (isOn()) {
            return Color.CYAN; // 激活时青色
        } else if (isCooldown()) {
            return Color.ORANGE; // 冷却时橙色
        } else {
            return Color.GREEN; // 就绪时绿色
        }
    }

    @Override
    public float getBarFill() {
        // 自定义进度条显示
        if (state == State.ACTIVE) {
            return 1f; // 激活时满条
        } else if (state == State.IN) {
            return getStateCompleteRatio(); // 启动进度
        } else if (state == State.OUT) {
            return 1f - getStateCompleteRatio(); // 结束进度
        } else if (state == State.COOLDOWN) {
            return 1f - getStateCompleteRatio(); // 冷却进度
        }
        return 0f; // 就绪时空条
    }

    // ========== 参数设置方法（供船插使用） ==========

    // 效果参数
    public void setMaxSpeedBonus(float maxSpeedBonus) {
        this.maxSpeedBonus = maxSpeedBonus;
    }

    public void setMaxAccelerationBonus(float maxAccelerationBonus) {
        this.maxAccelerationBonus = maxAccelerationBonus;
    }

    public void setMaxDecelerationBonus(float maxDecelerationBonus) {
        this.maxDecelerationBonus = maxDecelerationBonus;
    }

    public void setMaxTurnBonus(float maxTurnBonus) {
        this.maxTurnBonus = maxTurnBonus;
    }

    // 充能系统参数
    public void setMaxCharges(int maxCharges) {
        this.maxCharges = maxCharges;
    }

    public void setChargeRegenTime(float chargeRegenTime) {
        this.chargeRegenTime = chargeRegenTime;
    }

    // 时间参数
    public void setInDuration(float inDuration) {
        this.inDuration = inDuration;
    }

    public void setActiveDuration(float activeDuration) {
        this.activeDuration = activeDuration;
    }

    public void setOutDuration(float outDuration) {
        this.outDuration = outDuration;
    }

    // 幅能消耗参数
    public void setFluxPerSecond(float fluxPerSecond) {
        this.fluxPerSecond = fluxPerSecond;
    }

    public void setActivationFluxFlat(float activationFluxFlat) {
        this.activationFluxFlat = activationFluxFlat;
    }

    public void setActivationFluxPercent(float activationFluxPercent) {
        this.activationFluxPercent = activationFluxPercent;
    }

    public void setHardFlux(boolean isHardFlux) {
        this.isHardFlux = isHardFlux;
    }

    // 目标系统参数
    public void setRequiresTarget(boolean requiresTarget) {
        this.requiresTarget = requiresTarget;
    }

    public void setTargetOnlyEnemies(boolean targetOnlyEnemies) {
        this.targetOnlyEnemies = targetOnlyEnemies;
    }

    public void setRange(float range) {
        this.range = range;
    }

    // 其他参数
    public void setCanUseWhileOverloaded(boolean canUseWhileOverloaded) {
        this.canUseWhileOverloaded = canUseWhileOverloaded;
    }

    public void setCanUseWhileVenting(boolean canUseWhileVenting) {
        this.canUseWhileVenting = canUseWhileVenting;
    }

    public void setUsesChargesOnActivate(boolean usesChargesOnActivate) {
        this.usesChargesOnActivate = usesChargesOnActivate;
    }

    public void setSystemStatsEffectMult(float systemStatsEffectMult) {
        this.systemStatsEffectMult = systemStatsEffectMult;
    }

    public void setUseInstantStopBurstMode(boolean useInstantStopBurstMode) {
        this.useInstantStopBurstMode = useInstantStopBurstMode;
    }

    // ========== 参数获取方法 ==========

    public float getMaxSpeedBonus() {
        return maxSpeedBonus;
    }

    public float getMaxAccelerationBonus() {
        return maxAccelerationBonus;
    }

    public float getMaxDecelerationBonus() {
        return maxDecelerationBonus;
    }

    public float getMaxTurnBonus() {
        return maxTurnBonus;
    }

    public int getMaxChargesValue() {
        return maxCharges;
    }

    public float getChargeRegenTime() {
        return chargeRegenTime;
    }

    public float getInDurationValue() {
        return inDuration;
    }

    public float getActiveDurationValue() {
        return activeDuration;
    }

    public float getOutDurationValue() {
        return outDuration;
    }

    public float getFluxPerSecond() {
        return fluxPerSecond;
    }

    public float getActivationFluxFlat() {
        return activationFluxFlat;
    }

    public float getActivationFluxPercent() {
        return activationFluxPercent;
    }

    public boolean isHardFluxValue() {
        return isHardFlux;
    }

    public boolean requiresTargetValue() {
        return requiresTarget;
    }

    public boolean targetOnlyEnemiesValue() {
        return targetOnlyEnemies;
    }

    public float getRangeValue() {
        return range;
    }

    public boolean canUseWhileOverloadedValue() {
        return canUseWhileOverloaded;
    }

    public boolean canUseWhileVentingValue() {
        return canUseWhileVenting;
    }

    public boolean usesChargesOnActivateValue() {
        return usesChargesOnActivate;
    }

    public float getSystemStatsEffectMultValue() {
        return systemStatsEffectMult;
    }

    public boolean isUseInstantStopBurstMode() {
        return useInstantStopBurstMode;
    }

    // ========== AI行为参数设置方法 ==========

    public void setAiChaseMinDistance(float aiChaseMinDistance) {
        this.aiChaseMinDistance = aiChaseMinDistance;
    }

    public void setAiChaseMaxDistance(float aiChaseMaxDistance) {
        this.aiChaseMaxDistance = aiChaseMaxDistance;
    }

    public void setAiChaseSpeedThreshold(float aiChaseSpeedThreshold) {
        this.aiChaseSpeedThreshold = aiChaseSpeedThreshold;
    }

    public void setAiFleeHullThreshold(float aiFleeHullThreshold) {
        this.aiFleeHullThreshold = aiFleeHullThreshold;
    }

    public void setAiFleeDistance(float aiFleeDistance) {
        this.aiFleeDistance = aiFleeDistance;
    }

    // ========== AI行为参数获取方法 ==========

    public float getAiChaseMinDistance() {
        return aiChaseMinDistance;
    }

    public float getAiChaseMaxDistance() {
        return aiChaseMaxDistance;
    }

    public float getAiChaseSpeedThreshold() {
        return aiChaseSpeedThreshold;
    }

    public float getAiFleeHullThreshold() {
        return aiFleeHullThreshold;
    }

    public float getAiFleeDistance() {
        return aiFleeDistance;
    }
}
