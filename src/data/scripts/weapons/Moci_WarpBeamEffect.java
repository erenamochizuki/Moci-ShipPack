package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

public class Moci_WarpBeamEffect implements BeamEffectPlugin {

    // 基础参数配置
    private static final float BASE_MOMENTUM = -200f;      // 基础动量强度
    private static final float DEBUFF_DURATION = 2f;       // Debuff持续时间(秒)
    private static final float CRITICAL_MASS_RATIO = 2f;   // 完全失效质量比（目标质量/释放者质量）
    private static final float SAFE_DISTANCE_MULT = 1.4f;    // 安全距离倍数
    private static final float SPEED_DAMPING_FACTOR = 0.7f; // 速度衰减系数
    //private static final float ANGULAR_DAMPING = 0.9f;       // 角速度衰减系数

    // 动态状态跟踪
    private ShipAPI sourceShip;                 // 释放光束的舰船
    private boolean isEffectApplied  = false;   // 惩罚是否已应用

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused()) return;

        // 初始化源舰船监听器
        if (sourceShip == null) {
            sourceShip = beam.getSource();
            if (!sourceShip.hasListenerOfClass(WarpDebuff.class)) {
                sourceShip.addListener(new WarpDebuff(sourceShip));
            }
        }

        CombatEntityAPI target = beam.getDamageTarget();
        boolean isActive = (target != null && target != sourceShip && beam.didDamageThisFrame());

        // 动态效果处理
        if (isActive) {
            applyDynamicMomentum(target, beam, amount);
            updateDebuffState(true);
        } else {
            updateDebuffState(false);
        }

        cleanUpIfNeeded();
    }

    /**
     * 动态动量计算
     */
    private void applyDynamicMomentum(CombatEntityAPI target, BeamAPI beam, float amount) {
        // 计算复合安全距离（双方碰撞半径和）
        float safeDistance = (sourceShip.getCollisionRadius() +
                (target instanceof ShipAPI ? ((ShipAPI) target).getCollisionRadius() : 0f))
                * SAFE_DISTANCE_MULT;

        float actualDistance = MathUtils.getDistance(sourceShip.getLocation(), target.getLocation());

        // 目标进入安全区域时的动量衰减
        if (actualDistance <= safeDistance) {
            applyDampingEffect(target, amount);
            return; // 跳过常规动量修正
        }
        // 记录原始质量
        float originalMass = target.getMass() + 1;
        // 计算质量比（使用原始质量）
        float sourceMass = sourceShip.getMass();
        float massRatio = originalMass / sourceMass;

        // 动量衰减曲线（当质量比>=2时完全失效）
        float effectiveness = Math.max(0f, 1f - (massRatio / CRITICAL_MASS_RATIO));
        float scaledMomentum = BASE_MOMENTUM * effectiveness;

        // 临时质量修正（增强计算稳定性）
        target.setMass(target.getMass() + 100f);
        applyWarpMomentum(
                target,
                beam.getTo(),
                Vector2f.sub(beam.getTo(), beam.getFrom(), null),
                scaledMomentum,
                false
        );
        target.setMass(target.getMass() - 100f);
    }


    private static void applyWarpMomentum(CombatEntityAPI entity, Vector2f pointOfImpact,
                                          Vector2f direction, float momentum, boolean elasticCollision) {
        if (entity == null || direction.lengthSquared() == 0) return;

        // 处理空间站和模块化舰船
        float mass = entity.getMass();
        if (entity instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) entity;

            // 忽略空间站及其模块
            if (ship.isStation() || (ship.isStationModule() && ship.getParentStation().isStation()) && ship.isShuttlePod()) {
                return;
            }

            // 模块化舰船使用总质量
            if (ship.isStationModule() && ship.isShipWithModules()) {
                ShipAPI parent = ship.getParentStation();
                if (parent != null) {
                    entity = parent;
                    mass = parent.getMassWithModules();
                }
            }
        }
        mass = Math.max(1f, mass); // 质量下限保护

        // 动量放大系数
        momentum *= 100f;

        // 向量计算准备
        Vector2f BPtoMC = Vector2f.sub(entity.getLocation(), pointOfImpact, null);
        Vector2f forceV = new Vector2f(direction);
        forceV.normalise().scale(momentum);

        // 计算加速度
        BPtoMC.normalise();
        float dotProduct = Vector2f.dot(forceV, BPtoMC);
        BPtoMC.scale(dotProduct / mass);

        // 应用线速度变化
        if (elasticCollision) {
            Vector2f.add(entity.getVelocity(), BPtoMC, entity.getVelocity());
        } else {
            Vector2f impulse = new Vector2f(forceV);
            impulse.scale(1f / mass);
            Vector2f.add(entity.getVelocity(), impulse, entity.getVelocity());
        }

        // 计算角动量变化
        //float crossProduct = forceV.x * BPtoMC.y - forceV.y * BPtoMC.x;
        //float angularAcc = (float) Math.toDegrees(
       //         crossProduct / (0.5f * mass * entity.getCollisionRadius() * entity.getCollisionRadius())
        //);

        // 应用角速度变化（符号根据碰撞类型调整）
        //float currentAngular = entity.getAngularVelocity();
        //entity.setAngularVelocity(currentAngular + (elasticCollision ? angularAcc : -angularAcc));
    }

    /**
     * 应用渐进式动量衰减
     */
    private void applyDampingEffect(CombatEntityAPI target, float amount) {
        // 线性速度衰减
        Vector2f currentVel = new Vector2f(target.getVelocity());
        currentVel.scale((float) Math.pow(SPEED_DAMPING_FACTOR, amount * 60f)); // 每帧修正
        target.getVelocity().set(currentVel);

        // 角速度衰减
        //float currentAngular = target.getAngularVelocity();
        //target.setAngularVelocity(currentAngular * (float) Math.pow(ANGULAR_DAMPING, amount * 60f));

        // 速度下限保护（避免无限趋近零）
        if (currentVel.length() < 1f) target.getVelocity().set(0, 0);
        //if (Math.abs(currentAngular) < 1f) target.setAngularVelocity(0);
    }

    /**
     * 更新Debuff状态
     * @param activate 是否激活新Debuff
     */
    private void updateDebuffState(boolean activate) {
        if (sourceShip == null) return;

        WarpDebuff debuff = getExistingDebuff();
        if (debuff == null) return;

        if (activate) {
            // 刷新持续时间
            debuff.refreshDuration(DEBUFF_DURATION);
            if (!isEffectApplied ) {
                debuff.applyEffects();
                isEffectApplied  = true;
            }
        } else {
            // 自然衰减时不刷新时间
            if (isEffectApplied ) {
                isEffectApplied  = false;
            }
        }
    }

    /**
     * 获取现有Debuff实例
     */
    private WarpDebuff getExistingDebuff() {
        for (AdvanceableListener listener : sourceShip.getListeners(AdvanceableListener.class)) {
            if (listener instanceof WarpDebuff) {
                return (WarpDebuff) listener;
            }
        }
        return null;
    }

    /**
     * 清理无效状态
     */
    private void cleanUpIfNeeded() {
        if (sourceShip != null && !sourceShip.isAlive()) {
            WarpDebuff debuff = getExistingDebuff();
            if (debuff != null) {
                debuff.forceRemove();
                sourceShip.removeListener(debuff); // 强制移除监听器
            }
            sourceShip = null;
        }
    }


    // ========================== Debuff监听器 ========================== //

    private static class WarpDebuff implements AdvanceableListener {
        private static final String DEBUFF_ID = "moci_warp_debuff";
        private final ShipAPI hostShip;
        private float remainingTime = 0f;
        private boolean isApplied = false;

        // 惩罚参数
        private static final float SPEED_MULT = 0.9f; //移动速度惩罚
        private static final float TURN_MULT = 0.9f; //机动性惩罚

        WarpDebuff(ShipAPI ship) {
            this.hostShip = ship;
        }

        @Override
        public void advance(float amount) {
            if (hostShip == null || !hostShip.isAlive() || Global.getCombatEngine().isPaused()) {
                forceRemove();
                return;
            }

            if (remainingTime > 0) {
                remainingTime -= amount;
                if (!isApplied) {
                    applyEffects();
                }
            } else if (isApplied) {
                removeEffects();
            }
        }

        /**
         * 刷新Debuff持续时间
         */
        void refreshDuration(float duration) {
            remainingTime = Math.max(remainingTime, duration);
        }

        /**
         * 应用状态效果
         */
        void applyEffects() {
            if (isApplied) return;

            MutableShipStatsAPI stats = hostShip.getMutableStats();

            // 速度修正
            stats.getMaxSpeed().modifyMult(DEBUFF_ID, SPEED_MULT);

            // 机动性修正
            stats.getTurnAcceleration().modifyMult(DEBUFF_ID, TURN_MULT);
            stats.getMaxTurnRate().modifyMult(DEBUFF_ID, TURN_MULT);

            isApplied = true;
        }

        /**
         * 移除状态效果
         */
        void removeEffects() {
            MutableShipStatsAPI stats = hostShip.getMutableStats();

            stats.getMaxSpeed().unmodify(DEBUFF_ID);
            stats.getTurnAcceleration().unmodify(DEBUFF_ID);
            stats.getMaxTurnRate().unmodify(DEBUFF_ID);

            isApplied = false;
        }

        /**
         * 强制移除（舰船销毁时调用）
         */
        void forceRemove() {
            removeEffects();
            hostShip.removeListener(this);
        }
    }
}