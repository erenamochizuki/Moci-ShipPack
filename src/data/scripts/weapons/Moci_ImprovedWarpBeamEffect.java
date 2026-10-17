package data.scripts.weapons;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 改进版曲率光束效果
 * 基于质量比实现双向动力学系统：
 * - 质量相同时：双方都受到一半力度
 * - 质量差2倍时：重的不动，轻的受满力度
 * - 中间按质量差线性插值
 */
public class Moci_ImprovedWarpBeamEffect implements BeamEffectPlugin {

    // 基础参数配置
    private static final float BASE_MOMENTUM = -300f;        // 基础动量强度
    private static final float SAFE_DISTANCE = 50f;       // 安全距离
    private static final float SPEED_DAMPING_FACTOR = 0.7f; // 速度衰减系数
    private static final float MASS_RATIO_THRESHOLD = 3f;   // 质量比阈值（2倍时一方完全不动）
    private static final float SOURCE_MASS_MULTIPLIER = 5f; // 源舰船质量倍率（用于计算力分配）

    // 动态状态跟踪
    private ShipAPI sourceShip;

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused()) return;

        // 初始化源舰船
        if (sourceShip == null) {
            sourceShip = beam.getSource();
        }

        CombatEntityAPI target = beam.getDamageTarget();
        boolean isActive = (target != null && target != sourceShip && beam.didDamageThisFrame());

        // 动态效果处理
        if (isActive) {
            applyBidirectionalMomentum(target, beam, amount);
        }

        cleanUpIfNeeded();
    }

    /**
     * 应用双向动量效果
     * @param target 目标实体
     * @param beam 光束对象
     * @param amount 时间增量(秒)
     */
    private void applyBidirectionalMomentum(CombatEntityAPI target, BeamAPI beam, float amount) {
        // 计算距离，在安全区域内应用衰减
        float actualDistance = MathUtils.getDistance(sourceShip.getLocation(), target.getLocation());
        if (actualDistance <= SAFE_DISTANCE) {
            applyDampingEffect(target, amount);
            applyDampingEffect(sourceShip, amount);
            return;
        }

        // 获取质量（源舰船应用倍率）
        float sourceMass = getEffectiveMass(sourceShip) * SOURCE_MASS_MULTIPLIER;
        float targetMass = getEffectiveMass(target);
        
        // 防止除零错误
        if (sourceMass <= 0 || targetMass <= 0) return;

        // 计算质量比和力分配
        float massRatio = Math.max(sourceMass, targetMass) / Math.min(sourceMass, targetMass);
        MomentumAllocation allocation = calculateMomentumAllocation(sourceMass, targetMass, massRatio);

        // 计算方向向量（从源指向目标）
        Vector2f direction = Vector2f.sub(beam.getTo(), beam.getFrom(), null);
        if (direction.lengthSquared() == 0) return;

        // 应用动量到双方
        if (allocation.targetMomentumMult > 0) {
            applyWarpMomentum(target, beam.getTo(), direction, 
                            BASE_MOMENTUM * allocation.targetMomentumMult, false);
        }
        
        if (allocation.sourceMomentumMult > 0) {
            Vector2f reverseDirection = new Vector2f(-direction.x, -direction.y);
            applyWarpMomentum(sourceShip, beam.getFrom(), reverseDirection, 
                            BASE_MOMENTUM * allocation.sourceMomentumMult, false);
        }
    }

    /**
     * 获取实体的有效质量
     */
    private float getEffectiveMass(CombatEntityAPI entity) {
        // 移除忽略逻辑，对于模块船使用模块本身的质量
        return Math.max(1f, entity.getMass());
    }

    /**
     * 计算动量分配
     */
    private MomentumAllocation calculateMomentumAllocation(float sourceMass, float targetMass, float massRatio) {
        MomentumAllocation allocation = new MomentumAllocation();
        
        if (massRatio <= 1f) {
            // 质量相同，各受到一半力度
            allocation.sourceMomentumMult = 0.5f;
            allocation.targetMomentumMult = 0.5f;
        } else if (massRatio >= MASS_RATIO_THRESHOLD) {
            // 质量差2倍或以上
            if (sourceMass > targetMass) {
                // 源更重：源不动，目标受满力度
                allocation.sourceMomentumMult = 0f;
                allocation.targetMomentumMult = 1f;
            } else {
                // 目标更重：目标不动，源受满力度
                allocation.sourceMomentumMult = 1f;
                allocation.targetMomentumMult = 0f;
            }
        } else {
            // 质量比在1-2之间，线性插值
            float interpolation = (massRatio - 1f) / (MASS_RATIO_THRESHOLD - 1f);
            
            if (sourceMass > targetMass) {
                // 源更重
                allocation.sourceMomentumMult = 0.5f * (1f - interpolation);
                allocation.targetMomentumMult = 0.5f + 0.5f * interpolation;
            } else {
                // 目标更重
                allocation.sourceMomentumMult = 0.5f + 0.5f * interpolation;
                allocation.targetMomentumMult = 0.5f * (1f - interpolation);
            }
        }
        
        return allocation;
    }

    /**
     * 应用扭曲动量效果
     */
    private static void applyWarpMomentum(CombatEntityAPI entity, Vector2f pointOfImpact,
                                          Vector2f direction, float momentum, boolean elasticCollision) {
        if (entity == null || direction.lengthSquared() == 0) return;

        // 使用原始实体的质量进行计算（模块船使用模块本身质量）
        float mass = Math.max(1f, entity.getMass());
        CombatEntityAPI targetEntity = entity; // 记录实际要施加效果的实体
        
        if (entity instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) entity;

            // 对于模块化舰船的模块，将效果施加到母舰上（但质量计算仍使用模块质量）
            if (ship.isStationModule()) {
                ShipAPI parent = ship.getParentStation();
                if (parent != null) {
                    targetEntity = parent; // 实际施加效果的目标改为母舰
                    // 注意：质量计算仍使用原始模块的质量
                }
            }
        }

        // 动量放大系数
        momentum *= 100f;

        // 计算冲击方向（使用实际目标实体的位置）
        Vector2f BPtoMC = Vector2f.sub(targetEntity.getLocation(), pointOfImpact, null);
        Vector2f forceV = new Vector2f(direction);
        forceV.normalise().scale(momentum);

        // 计算加速度
        BPtoMC.normalise();
        float dotProduct = Vector2f.dot(forceV, BPtoMC);
        Vector2f accelerationVector = new Vector2f(BPtoMC);
        accelerationVector.scale(dotProduct / mass);

        // 预计算施加动量后的速度变化
        Vector2f velocityChange;
        if (elasticCollision) {
            velocityChange = new Vector2f(accelerationVector);
        } else {
            Vector2f impulse = new Vector2f(forceV);
            impulse.scale(1f / mass);
            velocityChange = impulse;
        }

        // 计算施加后的预期速度
        Vector2f currentVelocity = new Vector2f(targetEntity.getVelocity());
        Vector2f predictedVelocity = Vector2f.add(currentVelocity, velocityChange, null);
        float predictedSpeed = predictedVelocity.length();

        // 速度限制检查：如果预期速度会超过最大速度的2倍，按比例缩减动量
        if (targetEntity instanceof ShipAPI) {
            ShipAPI targetShip = (ShipAPI) targetEntity;
            float maxSpeed = targetShip.getMaxSpeed();
            float speedLimit = maxSpeed * 4.0f;
            
            if (predictedSpeed > speedLimit) {
                // 计算缩减比例，使最终速度刚好达到限制
                float currentSpeed = currentVelocity.length();
                float maxAllowedSpeedIncrease = speedLimit - currentSpeed;
                float requestedSpeedIncrease = velocityChange.length();
                
                if (maxAllowedSpeedIncrease > 0 && requestedSpeedIncrease > 0) {
                    float reductionFactor = maxAllowedSpeedIncrease / requestedSpeedIncrease;
                    velocityChange.scale(reductionFactor);
                } else {
                    // 如果当前速度已经达到或超过限制，不施加任何动量
                    return;
                }
            }
        }

        // 应用限制后的速度变化
        Vector2f.add(targetEntity.getVelocity(), velocityChange, targetEntity.getVelocity());
    }

    /**
     * 应用渐进式动量衰减
     */
    private void applyDampingEffect(CombatEntityAPI target, float amount) {
        // 对于模块化舰船的模块，将衰减效果施加到母舰上
        CombatEntityAPI targetEntity = target;
        if (target instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) target;
            if (ship.isStationModule()) {
                ShipAPI parent = ship.getParentStation();
                if (parent != null) {
                    targetEntity = parent;
                }
            }
        }
        
        Vector2f currentVel = new Vector2f(targetEntity.getVelocity());
        currentVel.scale((float) Math.pow(SPEED_DAMPING_FACTOR, amount * 60f));
        targetEntity.getVelocity().set(currentVel);

        // 速度下限保护
        if (currentVel.length() < 1f) targetEntity.getVelocity().set(0, 0);
    }

    /**
     * 清理无效状态
     */
    private void cleanUpIfNeeded() {
        if (sourceShip != null && !sourceShip.isAlive()) {
            sourceShip = null;
        }
    }

    /**
     * 动量分配结果
     */
    private static class MomentumAllocation {
        float sourceMomentumMult = 0f;  // 源舰船受到的动量倍数
        float targetMomentumMult = 0f;  // 目标受到的动量倍数
    }
} 