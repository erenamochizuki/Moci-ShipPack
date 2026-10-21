package data.subsystems;

import java.awt.Color;
import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;

import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.MagicSubsystem;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CollisionGridAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
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
    private static final boolean USE_TACTICAL_AI = true;
    private static final float AI_DECISION_MIN_INTERVAL = 0.15f;
    private static final float AI_DECISION_INTERVAL_STEP = 0.01f;
    private static final int AI_DECISION_INTERVAL_STEPS = 16;
    private static final float WEAPON_RANGE_REFRESH_INTERVAL = 1f;
    private static final float TRAVEL_MIN_DISTANCE = 2000f;
    private static final float TRAVEL_THREAT_RANGE = 2000f;
    private static final float PARTICLE_INTERVAL = 1f / 30f;
    private static final int MAX_PARTICLE_BATCHES_PER_FRAME = 3;

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
    private float maxTurnBonus = 0f;

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

    // ========== 目标系统参数 ==========
    private boolean requiresTarget = false; // 是否需要目标
    private boolean targetOnlyEnemies = true; // 是否只能选择敌方目标
    private float range = -1f; // 射程（-1表示无限制）

    // ========== 其他参数 ==========
    private boolean canUseWhileOverloaded = false; // 过载时是否可用
    private boolean canUseWhileVenting = false; // 散热时是否可用
    private boolean usesChargesOnActivate = true; // 激活时是否消耗充能
    private float systemStatsEffectMult = 1f;

    // ========== AI行为参数 ==========
    private float aiChaseMinDistance = 300f; // AI追击模式最小距离
    private float aiChaseMaxDistance = 2000f; // AI追击模式最大距离
    private float aiChaseSpeedThreshold = 0.5f; // AI追击模式速度阈值（相对最大速度的百分比）
    private float aiFleeHullThreshold = 0.4f; // AI逃跑模式血量阈值
    private float aiFleeDistance = 1000f; // AI逃跑模式触发距离

    // ========== 内部状态 ==========
    private final String effectId = "moci_mobility_subsystem";
    private float particleElapsed = 0f;
    private float aiDecisionInterval = nextAiDecisionInterval();
    private float aiDecisionElapsed = ThreadLocalRandom.current().nextFloat() * aiDecisionInterval;
    private float weaponRangeElapsed = WEAPON_RANGE_REFRESH_INTERVAL;
    private float cachedWeaponRange = 300f;
    private boolean effectsApplied = false;
    private float cachedStateDuration = -1f;
    private float cachedStateReciprocal = 0f;

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
        return systemStatsEffectMult;
    }

    @Override
    public boolean canActivate() {
        // 检查舰船状态
        if (!ship.isAlive() || ship.isHulk() || isBlockedDuringRepair()) {
            return false;
        }

        // 检查是否过载或散热中
        if ((!canUseWhileOverloaded() && ship.getFluxTracker().isOverloaded())
                || (!canUseWhileVenting() && ship.getFluxTracker().isVenting())) {
            return false;
        }

        return true;
    }

    @Override
    public boolean shouldActivateAI(float amount) {
        if (!canActivate() || !canActivateInternal()) {
            return false;
        }
        if (!USE_TACTICAL_AI) {
            return shouldActivateLegacyAI();
        }
        if (aiDecisionElapsed < aiDecisionInterval) {
            return false;
        }
        boolean shouldActivate = shouldActivateTacticalAI();
        aiDecisionElapsed = 0f;
        aiDecisionInterval = nextAiDecisionInterval();
        return shouldActivate;
    }

    private static float nextAiDecisionInterval() {
        return AI_DECISION_MIN_INTERVAL
                + ThreadLocalRandom.current().nextInt(AI_DECISION_INTERVAL_STEPS) * AI_DECISION_INTERVAL_STEP;
    }

    private boolean shouldActivateLegacyAI() {
        if (ship.isRetreating()) {
            return true;
        }
        ShipAPI target = ship.getShipTarget();
        if (target == null) {
            return false;
        }
        float offsetX = target.getLocation().x - ship.getLocation().x;
        float offsetY = target.getLocation().y - ship.getLocation().y;
        float distanceSquared = offsetX * offsetX + offsetY * offsetY;
        float speedThreshold = ship.getMaxSpeed() * aiChaseSpeedThreshold;
        if (distanceSquared > aiChaseMinDistance * aiChaseMinDistance
                && distanceSquared < aiChaseMaxDistance * aiChaseMaxDistance
                && ship.getVelocity().lengthSquared() > speedThreshold * speedThreshold) {
            return true;
        }
        return ship.getHullLevel() < aiFleeHullThreshold && distanceSquared < aiFleeDistance * aiFleeDistance;
    }

    private boolean shouldActivateTacticalAI() {
        CombatEngineAPI combat = Global.getCombatEngine();
        ShipEngineControllerAPI engines = ship.getEngineController();
        if (combat == null || engines == null || engines.isDisabled() || engines.isFlamedOut()
                || engines.isDecelerating()) {
            return false;
        }
        float forward = (engines.isAccelerating() ? 1f : 0f) - (engines.isAcceleratingBackwards() ? 1f : 0f);
        float sideways = (engines.isStrafingLeft() ? 1f : 0f) - (engines.isStrafingRight() ? 1f : 0f);
        float movementSquared = forward * forward + sideways * sideways;
        if (movementSquared == 0f) {
            return false;
        }
        if (ship.isRetreating()) {
            return true;
        }
        float facing = (float) Math.toRadians(ship.getFacing());
        float facingCos = (float) Math.cos(facing);
        float facingSin = (float) Math.sin(facing);
        float movementX = facingCos * forward - facingSin * sideways;
        float movementY = facingSin * forward + facingCos * sideways;
        float preferredRange = getPreferredWeaponRange();
        float shipRadius = ship.getCollisionRadius();
        float shipX = ship.getLocation().x;
        float shipY = ship.getLocation().y;
        ShipwideAIFlags flags = ship.getAIFlags();
        boolean backingOff = flags != null && (flags.hasFlag(AIFlags.BACK_OFF)
                || flags.hasFlag(AIFlags.BACKING_OFF) || flags.hasFlag(AIFlags.RUN_QUICKLY));
        float fluxLevel = ship.getFluxLevel();
        boolean travelCandidate = !backingOff && fluxLevel <= 0.5f
                && shouldBoostForTravel(engines, flags, movementX, movementY, movementSquared);
        boolean travelSafe = travelCandidate;
        float travelThreatRange = TRAVEL_THREAT_RANGE;
        if (travelCandidate) {
            float horizon = getInDuration() + getActiveDuration() + getOutDuration();
            float boostTravel = maxSpeedBonus * horizon;
            Vector2f velocity = ship.getVelocity();
            travelThreatRange += boostTravel + (Math.abs(velocity.x) + Math.abs(velocity.y)) * horizon;
        }
        ShipAPI nearestThreat = null;
        float nearestDistanceSquared = Float.MAX_VALUE;
        int nearbyThreats = 0;
        CollisionGridAPI grid = combat.getShipGrid();
        float scanRange = travelCandidate ? Math.max(aiFleeDistance, travelThreatRange) : aiFleeDistance;
        float queryDiameter = (scanRange + shipRadius) * 2f;
        Iterator<?> candidates = grid != null
                ? grid.getCheckIterator(ship.getLocation(), queryDiameter, queryDiameter)
                : combat.getShips().iterator();
        while (candidates.hasNext()) {
            Object candidate = candidates.next();
            if (!(candidate instanceof ShipAPI)) {
                continue;
            }
            ShipAPI other = (ShipAPI) candidate;
            if (!isValidEnemy(other, combat)) {
                continue;
            }
            float offsetX = other.getLocation().x - shipX;
            float offsetY = other.getLocation().y - shipY;
            float distanceSquared = offsetX * offsetX + offsetY * offsetY;
            if (travelSafe) {
                float safeRange = travelThreatRange + shipRadius + other.getCollisionRadius();
                if (distanceSquared < safeRange * safeRange) {
                    travelSafe = false;
                }
            }
            if (other.isFighter()) {
                continue;
            }
            float threatRange = aiFleeDistance + shipRadius + other.getCollisionRadius();
            if (distanceSquared >= threatRange * threatRange) {
                continue;
            }
            nearbyThreats++;
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearestThreat = other;
            }
        }
        float hullLevel = ship.getHullLevel();
        if (nearestThreat != null) {
            float offsetX = nearestThreat.getLocation().x - shipX;
            float offsetY = nearestThreat.getLocation().y - shipY;
            float movementDot = movementX * offsetX + movementY * offsetY;
            float relativeX = ship.getVelocity().x - nearestThreat.getVelocity().x;
            float relativeY = ship.getVelocity().y - nearestThreat.getVelocity().y;
            float closeRange = preferredRange * 0.7f + shipRadius + nearestThreat.getCollisionRadius();
            boolean tooClose = nearestDistanceSquared < closeRange * closeRange
                    && relativeX * offsetX + relativeY * offsetY > 0f;
            boolean underPressure = hullLevel < aiFleeHullThreshold || fluxLevel > 0.8f
                    || nearbyThreats >= 3 || backingOff;
            if ((underPressure || tooClose) && movementDot < 0f
                    && movementDot * movementDot > 0.1225f * movementSquared * nearestDistanceSquared) {
                return true;
            }
        }
        if (travelSafe) {
            return true;
        }
        ShipAPI target = ship.getShipTarget();
        if (backingOff || hullLevel < aiFleeHullThreshold || fluxLevel > 0.8f
                || !isValidEnemy(target, combat) || target.isFighter()) {
            return false;
        }
        float offsetX = target.getLocation().x - shipX;
        float offsetY = target.getLocation().y - shipY;
        float distanceSquared = offsetX * offsetX + offsetY * offsetY;
        float movementDot = movementX * offsetX + movementY * offsetY;
        if (distanceSquared <= aiChaseMinDistance * aiChaseMinDistance
                || distanceSquared >= aiChaseMaxDistance * aiChaseMaxDistance || movementDot <= 0f
                || movementDot * movementDot < 0.4225f * movementSquared * distanceSquared) {
            return false;
        }
        float boostTravel = maxSpeedBonus * (getActiveDuration() + getInDuration() * 0.5f);
        float preferredDistance = preferredRange + shipRadius + target.getCollisionRadius();
        float safeDistance = preferredDistance + Math.max(100f, boostTravel * 0.65f);
        if (distanceSquared <= safeDistance * safeDistance) {
            return false;
        }
        float horizon = getInDuration() + getActiveDuration();
        float predictedX = offsetX - (ship.getVelocity().x - target.getVelocity().x) * horizon;
        float predictedY = offsetY - (ship.getVelocity().y - target.getVelocity().y) * horizon;
        if (predictedX * offsetX + predictedY * offsetY <= 0f
                || predictedX * predictedX + predictedY * predictedY <= safeDistance * safeDistance) {
            return false;
        }
        if (usesChargesOnActivate() && getCharges() <= 1) {
            float reserveDistance = preferredDistance + Math.max(400f, boostTravel);
            if (nearbyThreats > 1 || fluxLevel > 0.5f || hullLevel < 0.6f
                    || distanceSquared < reserveDistance * reserveDistance) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldBoostForTravel(ShipEngineControllerAPI engines, ShipwideAIFlags flags,
                                        float movementX, float movementY, float movementSquared) {
        if (!usesChargesOnActivate() || flags == null || !engines.isAccelerating()
                || engines.isTurningLeft() || engines.isTurningRight()) {
            return false;
        }
        int chargeCapacity = calcMaxCharges();
        if (chargeCapacity <= 0 || getCharges() < chargeCapacity) {
            return false;
        }
        ShipSystemAPI travelDrive = ship.getTravelDrive();
        if (travelDrive != null && travelDrive.isOn()) {
            return false;
        }
        Object destination = flags.getCustom(AIFlags.MOVEMENT_DEST);
        if (!(destination instanceof Vector2f)) {
            return false;
        }
        Vector2f destinationLocation = (Vector2f) destination;
        float offsetX = destinationLocation.x - ship.getLocation().x;
        float offsetY = destinationLocation.y - ship.getLocation().y;
        float distanceSquared = offsetX * offsetX + offsetY * offsetY;
        float movementDot = movementX * offsetX + movementY * offsetY;
        float minimumDistance = TRAVEL_MIN_DISTANCE + ship.getCollisionRadius();
        if (!Float.isFinite(distanceSquared) || distanceSquared <= minimumDistance * minimumDistance
                || movementDot <= 0f || movementDot * movementDot < 0.81f * movementSquared * distanceSquared) {
            return false;
        }
        Vector2f velocity = ship.getVelocity();
        float velocityDot = velocity.x * offsetX + velocity.y * offsetY;
        float speedSquared = velocity.x * velocity.x + velocity.y * velocity.y;
        if (velocityDot < 0f || velocityDot * velocityDot < 0.81f * speedSquared * distanceSquared) {
            return false;
        }
        float horizon = getInDuration() + getActiveDuration() + getOutDuration();
        float boostTravel = maxSpeedBonus * horizon;
        float safeDistance = minimumDistance + boostTravel;
        float predictedX = offsetX - velocity.x * horizon;
        float predictedY = offsetY - velocity.y * horizon;
        return predictedX * offsetX + predictedY * offsetY > 0f
                && predictedX * predictedX + predictedY * predictedY > safeDistance * safeDistance;
    }

    private boolean isValidEnemy(ShipAPI other, CombatEngineAPI combat) {
        int owner = ship.getOwner();
        return other != null && other != ship && other.isAlive() && !other.isHulk()
                && owner >= 0 && owner <= 1 && other.getOwner() == 1 - owner && combat.isEntityInPlay(other);
    }

    private float getPreferredWeaponRange() {
        if (weaponRangeElapsed < WEAPON_RANGE_REFRESH_INTERVAL) {
            return cachedWeaponRange;
        }
        weaponRangeElapsed = 0f;
        float weightedRange = 0f;
        float totalWeight = 0f;
        float missileRange = 0f;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.isDecorative() || weapon.isDisabled() || weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY)
                    || (weapon.usesAmmo() && weapon.getAmmo() <= 0) || weapon.getRange() <= 0f) {
                continue;
            }
            if (weapon.getType() == WeaponAPI.WeaponType.MISSILE) {
                missileRange = Math.max(missileRange, weapon.getRange());
            } else if (weapon.getType() == WeaponAPI.WeaponType.BALLISTIC
                    || weapon.getType() == WeaponAPI.WeaponType.ENERGY) {
                float weight = Math.max(1f, weapon.getDerivedStats().getSustainedDps());
                weightedRange += weapon.getRange() * weight;
                totalWeight += weight;
            }
        }
        float weaponRange = totalWeight > 0f ? weightedRange / totalWeight : missileRange;
        cachedWeaponRange = Math.max(aiChaseMinDistance, weaponRange * 0.9f);
        return cachedWeaponRange;
    }

    @Override
    public void onActivate() {
        particleElapsed = 0f;

        // 播放激活音效
        Global.getSoundPlayer().playSound("system_maneuvering_jets", 1f, 1f,
        ship.getLocation(), ship.getVelocity());
    }

    @Override
    public void onFinished() {
        // 系统结束时移除效果
        removeAllEffects();
        particleElapsed = 0f;
    }

    @Override
    public void onShipDeath() {
        forceStop();
    }

    @Override
    public void advance(float amount, boolean isPaused) {
        if (isPaused)
            return;

        aiDecisionElapsed = Math.min(aiDecisionInterval, aiDecisionElapsed + amount);
        weaponRangeElapsed = Math.min(WEAPON_RANGE_REFRESH_INTERVAL, weaponRangeElapsed + amount);
        if (!ship.isAlive() || ship.isHulk() || isBlockedDuringRepair()) {
            forceStop();
            return;
        }

        // 动态应用效果，基于effectLevel
        float effectLevel = getEffectLevel();
        applyDynamicEffects(effectLevel);
        updateVisualEffects(amount, effectLevel);

        // 应用引擎光效
        applyEngineGlowEffects(effectLevel);
    }

    /**
     * 动态应用机动性增强效果
     * 基于effectLevel实现平滑的效果变化
     */
    private void applyDynamicEffects(float effectLevel) {
        if (effectLevel <= 0f) {
            removeAllEffects();
            return;
        }
        effectsApplied = true;
        if (state == State.OUT) {
            stats.getMaxSpeed().unmodify(effectId);
        } else {
            stats.getMaxSpeed().modifyFlat(effectId, getCurrentSpeedBonus(effectLevel));
        }
        stats.getAcceleration().modifyFlat(effectId, getCurrentAccelerationBonus(effectLevel));
        stats.getDeceleration().modifyFlat(effectId, maxDecelerationBonus * effectLevel);
        if (maxTurnBonus > 0f) {
            float currentTurnBonus = maxTurnBonus * effectLevel;
            stats.getTurnAcceleration().modifyPercent(effectId, currentTurnBonus);
            stats.getMaxTurnRate().modifyPercent(effectId, currentTurnBonus);
        } else {
            stats.getTurnAcceleration().unmodify(effectId);
            stats.getMaxTurnRate().unmodify(effectId);
        }
    }

    private float getCurrentSpeedBonus(float effectLevel) {
        return state == State.OUT ? 0f : maxSpeedBonus * effectLevel;
    }

    private float getCurrentAccelerationBonus(float effectLevel) {
        return maxAccelerationBonus * effectLevel;
    }

    private void applyEngineGlowEffects(float effectLevel) {
        if (effectLevel > 0f && ship.getEngineController() != null) {
            ship.getEngineController().extendFlame(this, 1f + 2f * effectLevel, 1f + effectLevel, 1f + 2f * effectLevel);
        }
    }

    private void removeAllEffects() {
        if (!effectsApplied) {
            return;
        }
        stats.getMaxSpeed().unmodify(effectId);
        stats.getAcceleration().unmodify(effectId);
        stats.getDeceleration().unmodify(effectId);
        stats.getTurnAcceleration().unmodify(effectId);
        stats.getMaxTurnRate().unmodify(effectId);
        effectsApplied = false;
    }

    private boolean isBlockedDuringRepair() {
        return Moci_MobileSuitRepairTracker.isShipRepairing(ship);
    }

    private void forceStop() {
        removeAllEffects();
        particleElapsed = 0f;
        activeElapsed = false;
        if (state != State.READY) {
            setState(State.READY);
        }
    }

    private void updateVisualEffects(float amount, float effectLevel) {
        if (effectLevel <= 0f || Global.getCombatEngine() == null) {
            particleElapsed = 0f;
            return;
        }
        particleElapsed = Math.min(particleElapsed + amount, PARTICLE_INTERVAL * MAX_PARTICLE_BATCHES_PER_FRAME);
        while (particleElapsed + 0.000001f >= PARTICLE_INTERVAL) {
            particleElapsed = Math.max(0f, particleElapsed - PARTICLE_INTERVAL);
            spawnEngineParticles(effectLevel);
        }
    }

    private void spawnEngineParticles(float effectLevel) {
        ShipEngineControllerAPI engines = ship.getEngineController();
        if (engines == null || engines.isDisabled() || engines.isFlamedOut()) {
            return;
        }
        for (ShipEngineControllerAPI.ShipEngineAPI engine : engines.getShipEngines()) {
            if (engine.isActive() && !engine.isDisabled() && engine.getEngineSlot() != null) {
                float angle = engine.getEngineSlot().getAngle() + ship.getFacing();
                float length = engine.getEngineSlot().getLength() * 2f * effectLevel;
                spawnTrailParticles(engine.getLocation(), angle, length, engine.getEngineSlot().getWidth(),
                        effectLevel, engine);
            }
        }
    }
    private static final float PARTICLE_VEL_MIN = 0.25f;  // 粒子速度最小倍数
    private static final float PARTICLE_VEL_MAX = 0.5f;  // 粒子速度最大倍数
    private static final float PARTICLE_CONE_ANGLE = 30f;  // 粒子扩散角度
    private static final float PARTICLE_DURATION_MIN = 0.4f;  // 粒子最小持续时间
    private static final float PARTICLE_DURATION_MAX = 0.6f;  // 粒子最大持续时间
    
    // 粒子尺寸和速度的最小保底值
    private static final float PARTICLE_SIZE_MIN_FALLBACK = 1f;  // 粒子最小尺寸保底值
    private static final float PARTICLE_SPEED_MIN_FALLBACK = 200f;  // 粒子最小速度保底值

    private void spawnTrailParticles(Vector2f engineLoc, float angle, float length, float width, float effectLevel,
                                     ShipEngineControllerAPI.ShipEngineAPI engine) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Color engineColor = engine.getEngineColor();
        if (engineColor == null) {
            engineColor = Color.CYAN;
        }
        Color particleColor = new Color(engineColor.getRed() * (1f / 255f),
                engineColor.getGreen() * (1f / 255f), engineColor.getBlue() * (1f / 255f), effectLevel);
        float randomOffset = random.nextFloat() * PARTICLE_CONE_ANGLE - PARTICLE_CONE_ANGLE * 0.5f;
        float particleAngle = (float) Math.toRadians(angle + randomOffset);
        float baseSpeed = Math.max(length * 50f + 100f, PARTICLE_SPEED_MIN_FALLBACK);
        float velocity = (random.nextFloat() * (PARTICLE_VEL_MAX - PARTICLE_VEL_MIN) + PARTICLE_VEL_MIN)
                * baseSpeed;
        Vector2f particleVelocity = new Vector2f((float) Math.cos(particleAngle) * velocity,
                (float) Math.sin(particleAngle) * velocity);
        float engineAngle = (float) Math.toRadians(angle);
        Vector2f particleLocation = new Vector2f(engineLoc.x - (float) Math.cos(engineAngle),
                engineLoc.y - (float) Math.sin(engineAngle));
        float minSize = Math.max(width * 0.5f, PARTICLE_SIZE_MIN_FALLBACK);
        float particleSize = minSize * (1f + random.nextFloat() * 2f);
        float duration = PARTICLE_DURATION_MIN + random.nextFloat() * (PARTICLE_DURATION_MAX - PARTICLE_DURATION_MIN);
        Global.getCombatEngine().addHitParticle(particleLocation, particleVelocity, particleSize, effectLevel,
                duration, particleColor);
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
            int currentSpeed = (int) getCurrentSpeedBonus(effectLevel);
            int currentAccel = (int) getCurrentAccelerationBonus(effectLevel);
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

    @Override
    public float getStateCompleteRatio() {
        if (stateInterval == null) {
            return 0f;
        }
        float duration = stateInterval.getIntervalDuration();
        if (duration <= 0f) {
            return 1f;
        }
        if (duration != cachedStateDuration) {
            cachedStateDuration = duration;
            cachedStateReciprocal = 1f / duration;
        }
        return Math.max(0f, Math.min(1f, stateInterval.getElapsed() * cachedStateReciprocal));
    }

    // ========== 参数设置方法（供船插使用） ==========

    // 效果参数
    public void setMaxSpeedBonus(float maxSpeedBonus) {
        requireNonNegative(maxSpeedBonus, "maxSpeedBonus");
        this.maxSpeedBonus = maxSpeedBonus;
    }

    public void setMaxAccelerationBonus(float maxAccelerationBonus) {
        requireNonNegative(maxAccelerationBonus, "maxAccelerationBonus");
        this.maxAccelerationBonus = maxAccelerationBonus;
    }

    public void setMaxDecelerationBonus(float maxDecelerationBonus) {
        requireNonNegative(maxDecelerationBonus, "maxDecelerationBonus");
        this.maxDecelerationBonus = maxDecelerationBonus;
    }

    public void setMaxTurnBonus(float maxTurnBonus) {
        requireNonNegative(maxTurnBonus, "maxTurnBonus");
        this.maxTurnBonus = maxTurnBonus;
    }

    // 充能系统参数
    public void setMaxCharges(int maxCharges) {
        if (maxCharges < 1) {
            throw new IllegalArgumentException("maxCharges must be positive");
        }
        this.maxCharges = maxCharges;
        if (inited) {
            charges = Math.min(charges, calcMaxCharges());
        }
    }

    public void setChargeRegenTime(float chargeRegenTime) {
        setChargeGenerationDuration(chargeRegenTime, true);
    }

    // 时间参数
    public void setInDuration(float inDuration) {
        setInDuration(inDuration, true);
    }

    @Override
    public void setInDuration(float inDuration, boolean preserve) {
        requireNonNegative(inDuration, "inDuration");
        this.inDuration = inDuration;
        if (inited) {
            super.setInDuration(inDuration, preserve);
        }
    }

    public void setActiveDuration(float activeDuration) {
        setActiveDuration(activeDuration, true);
    }

    @Override
    public void setActiveDuration(float activeDuration, boolean preserve) {
        requireNonNegative(activeDuration, "activeDuration");
        this.activeDuration = activeDuration;
        if (inited) {
            super.setActiveDuration(activeDuration, preserve);
        }
    }

    public void setOutDuration(float outDuration) {
        setOutDuration(outDuration, true);
    }

    @Override
    public void setOutDuration(float outDuration, boolean preserve) {
        requireNonNegative(outDuration, "outDuration");
        this.outDuration = outDuration;
        if (inited) {
            super.setOutDuration(outDuration, preserve);
        }
    }

    // 幅能消耗参数
    public void setFluxPerSecond(float fluxPerSecond) {
        requireNonNegative(fluxPerSecond, "fluxPerSecond");
        this.fluxPerSecond = fluxPerSecond;
    }

    public void setActivationFluxFlat(float activationFluxFlat) {
        requireNonNegative(activationFluxFlat, "activationFluxFlat");
        this.activationFluxFlat = activationFluxFlat;
    }

    public void setActivationFluxPercent(float activationFluxPercent) {
        requireRatio(activationFluxPercent, "activationFluxPercent");
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
        if (!Float.isFinite(range) || (range < 0f && range != -1f)) {
            throw new IllegalArgumentException("range must be -1 or non-negative");
        }
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
        requireNonNegative(systemStatsEffectMult, "systemStatsEffectMult");
        this.systemStatsEffectMult = systemStatsEffectMult;
    }

    @Override
    public void setChargeGenerationDuration(float duration, boolean preserve) {
        requirePositive(duration, "chargeRegenTime");
        chargeRegenTime = duration;
        if (inited) {
            super.setChargeGenerationDuration(duration, preserve);
        }
    }

    @Override
    public void setCharges(int charges) {
        if (charges < 0 || charges > calcMaxCharges()) {
            throw new IllegalArgumentException("charges must be between zero and the effective maximum");
        }
        super.setCharges(charges);
    }

    private static void requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0f) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0f) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireRatio(float value, String name) {
        requireNonNegative(value, name);
        if (value > 1f) {
            throw new IllegalArgumentException(name + " must not exceed one");
        }
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

    // ========== AI行为参数设置方法 ==========

    public void setAiChaseMinDistance(float aiChaseMinDistance) {
        requireNonNegative(aiChaseMinDistance, "aiChaseMinDistance");
        if (aiChaseMinDistance >= aiChaseMaxDistance) {
            throw new IllegalArgumentException("aiChaseMinDistance must be below aiChaseMaxDistance");
        }
        this.aiChaseMinDistance = aiChaseMinDistance;
        weaponRangeElapsed = WEAPON_RANGE_REFRESH_INTERVAL;
    }

    public void setAiChaseMaxDistance(float aiChaseMaxDistance) {
        requirePositive(aiChaseMaxDistance, "aiChaseMaxDistance");
        if (aiChaseMaxDistance <= aiChaseMinDistance) {
            throw new IllegalArgumentException("aiChaseMaxDistance must exceed aiChaseMinDistance");
        }
        this.aiChaseMaxDistance = aiChaseMaxDistance;
    }

    public void setAiChaseSpeedThreshold(float aiChaseSpeedThreshold) {
        requireRatio(aiChaseSpeedThreshold, "aiChaseSpeedThreshold");
        this.aiChaseSpeedThreshold = aiChaseSpeedThreshold;
    }

    public void setAiFleeHullThreshold(float aiFleeHullThreshold) {
        requireRatio(aiFleeHullThreshold, "aiFleeHullThreshold");
        this.aiFleeHullThreshold = aiFleeHullThreshold;
    }

    public void setAiFleeDistance(float aiFleeDistance) {
        requireNonNegative(aiFleeDistance, "aiFleeDistance");
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
