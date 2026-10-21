package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 改进版曲率光束效果
 * 将目标拖向光束发射舰船前方的稳定位置，并按质量分配反作用力。
 */
public class Moci_ImprovedWarpBeamEffect implements BeamEffectPlugin {

    // 基础参数配置
    private static final float ANCHOR_DISTANCE = 50f;
    private static final float POSITION_STIFFNESS = 2.25f;
    private static final float RELATIVE_VELOCITY_DAMPING = 3f;
    private static final float MAX_PULL_ACCELERATION = 600f;
    private static final float SPEED_LIMIT_MULTIPLIER = 4f;
    private static final float MASS_RATIO_THRESHOLD = 3f;
    private static final float SOURCE_MASS_MULTIPLIER = 5f;

    // 动态状态跟踪
    private ShipAPI sourceShip;

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused()) return;

        // 初始化源舰船
        if (sourceShip == null) sourceShip = beam.getSource();
        if (sourceShip == null || !sourceShip.isAlive()) return;

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
        if (amount <= 0f || target == null) return;

        CombatEntityAPI sourceEntity = getMovementEntity(sourceShip);
        CombatEntityAPI targetEntity = getMovementEntity(target);
        if (sourceEntity == targetEntity) return;
        float sourcePhysicalMass = getEffectiveMass(sourceEntity);
        float targetMass = getEffectiveMass(targetEntity);
        float sourceMass = sourcePhysicalMass * SOURCE_MASS_MULTIPLIER;
        if (sourcePhysicalMass <= 0f || targetMass <= 0f) return;

        // 计算质量比和力分配
        float massRatio = Math.max(sourceMass, targetMass) / Math.min(sourceMass, targetMass);
        MomentumAllocation allocation = calculateMomentumAllocation(sourceMass, targetMass, massRatio);

        // 锚点位于光束方向上、源舰船前方固定距离处。
        Vector2f direction = Vector2f.sub(beam.getTo(), beam.getFrom(), null);
        if (direction.lengthSquared() == 0) return;
        direction.normalise();

        Vector2f anchor = new Vector2f(sourceShip.getLocation());
        Vector2f offset = new Vector2f(direction);
        offset.scale(ANCHOR_DISTANCE);
        Vector2f.add(anchor, offset, anchor);

        Vector2f positionError = Vector2f.sub(anchor, targetEntity.getLocation(), null);
        Vector2f relativeVelocity = Vector2f.sub(targetEntity.getVelocity(), sourceEntity.getVelocity(), null);
        Vector2f acceleration = new Vector2f(positionError);
        acceleration.scale(POSITION_STIFFNESS);
        Vector2f damping = new Vector2f(relativeVelocity);
        damping.scale(RELATIVE_VELOCITY_DAMPING);
        Vector2f.sub(acceleration, damping, acceleration);

        float accelerationLength = acceleration.length();
        if (accelerationLength > MAX_PULL_ACCELERATION) {
            acceleration.scale(MAX_PULL_ACCELERATION / accelerationLength);
        }

        // 将加速度按时间积分为冲量，保持效果不随帧率变化。
        Vector2f impulse = new Vector2f(acceleration);
        impulse.scale(targetMass * amount);

        if (allocation.targetMomentumMult > 0) {
            Vector2f targetDeltaVelocity = new Vector2f(impulse);
            targetDeltaVelocity.scale(allocation.targetMomentumMult / targetMass);
            applyVelocityChange(targetEntity, targetDeltaVelocity);
        }

        if (allocation.sourceMomentumMult > 0) {
            Vector2f sourceDeltaVelocity = new Vector2f(impulse);
            sourceDeltaVelocity.scale(-allocation.sourceMomentumMult / sourcePhysicalMass);
            applyVelocityChange(sourceEntity, sourceDeltaVelocity);
        }
    }

    /**
     * 获取实体的有效质量
     */
    private float getEffectiveMass(CombatEntityAPI entity) {
        if (entity instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) entity;
            if (ship.isShipWithModules()) return Math.max(1f, ship.getMassWithModules());
        }
        return Math.max(1f, entity.getMass());
    }

    private CombatEntityAPI getMovementEntity(CombatEntityAPI entity) {
        if (entity instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) entity;
            if (ship.isStationModule() && ship.getParentStation() != null) {
                return ship.getParentStation();
            }
        }
        return entity;
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
            // 质量比达到3倍或以上
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
            // 质量比在1到3之间线性插值
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
    private static void applyVelocityChange(CombatEntityAPI entity, Vector2f velocityChange) {
        if (entity == null || velocityChange.lengthSquared() == 0f) return;

        Vector2f currentVelocity = new Vector2f(entity.getVelocity());
        Vector2f predictedVelocity = Vector2f.add(currentVelocity, velocityChange, null);
        if (entity instanceof ShipAPI) {
            float speedLimit = ((ShipAPI) entity).getMaxSpeed() * SPEED_LIMIT_MULTIPLIER;
            float currentSpeed = currentVelocity.length();
            float predictedSpeed = predictedVelocity.length();

            if (currentSpeed >= speedLimit) {
                if (predictedSpeed >= currentSpeed) return;
            } else if (predictedSpeed > speedLimit) {
                float quadratic = velocityChange.lengthSquared();
                float linear = Vector2f.dot(currentVelocity, velocityChange);
                float constant = currentVelocity.lengthSquared() - speedLimit * speedLimit;
                float discriminant = linear * linear - quadratic * constant;
                if (discriminant <= 0f) return;
                float scale = (-linear + (float) Math.sqrt(discriminant)) / quadratic;
                velocityChange.scale(Math.max(0f, Math.min(1f, scale)));
            }
        }

        Vector2f.add(currentVelocity, velocityChange, entity.getVelocity());
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
