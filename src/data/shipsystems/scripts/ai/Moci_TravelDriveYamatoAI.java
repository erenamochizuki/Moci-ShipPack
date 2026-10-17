package data.shipsystems.scripts.ai;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

/**
 * 大和号波动引擎AI
 * 用于追击敌人或快速脱离战场
 */
public class Moci_TravelDriveYamatoAI implements ShipSystemAIScript {

    // ==================== 可调整参数 ====================

    // 使用条件
    private static final float MIN_HULL_LEVEL = 0.3f; // 最低船体等级（低于此值时用于逃跑）
    private static final float CHASE_RANGE = 3000f; // 追击范围
    private static final float ESCAPE_RANGE = 800f; // 逃跑判定范围
    private static final float FACING_TOLERANCE = 15f; // 朝向容差（度）
    private static final float STOP_CHASE_RANGE = 300f; // 停止追击距离（接近此距离时关闭系统）
    private static final float STOP_FACING_TOLERANCE = 25f; // 停止朝向容差（朝向偏离超过此角度时关闭系统）
    private static final float STOP_ESCAPE_RANGE = 3500f; // 停止逃跑距离（远离敌人超过此距离时关闭系统）

    // AI行为开关
    private static final boolean ENABLE_CHASE = true; // 启用追击模式
    private static final boolean ENABLE_ESCAPE = true; // 启用逃跑模式
    private static final boolean ENABLE_AUTO_STOP = true; // 启用自动停止（接近目标时关闭系统）

    // ==================== 内部变量 ====================

    private ShipAPI ship;
    private ShipSystemAPI system;
    private ShipwideAIFlags flags;
    private CombatEngineAPI engine;

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.flags = flags;
        this.engine = engine;
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine.isPaused())
            return;

        // 自动停止：如果系统正在运行且effectLevel>0（真正启动状态），则检查停止条件
        if (ENABLE_AUTO_STOP && system.isActive() && system.getEffectLevel() > 0f) {
            // 追击模式的停止条件
            if (isBasicallyValidEnemyShip(ship, target)) {
                // 条件1：距离过近
                if (MathUtils.isWithinRange(ship, target, STOP_CHASE_RANGE)) {
                    ship.useSystem(); // 再次调用会关闭系统
                    return;
                }

                // 条件2：朝向偏离过大
                float angleToTarget = VectorUtils.getAngle(ship.getLocation(), target.getLocation());
                float angleDiff = MathUtils.getShortestRotation(ship.getFacing(), angleToTarget);
                if (Math.abs(angleDiff) > STOP_FACING_TOLERANCE) {
                    ship.useSystem(); // 朝向偏离太多，关闭系统
                    return;
                }
            } else {
                // 条件3：没有有效目标时，检查周围是否有敌人
                boolean hasNearbyEnemy = false;
                for (ShipAPI enemy : AIUtils.getNearbyEnemies(ship, CHASE_RANGE)) {
                    if (isBasicallyValidEnemyShip(ship, enemy)) {
                        hasNearbyEnemy = true;
                        break;
                    }
                }

                // 只有在周围完全没有敌人时才关闭系统
                if (!hasNearbyEnemy) {
                    ship.useSystem(); // 没有追击目标，关闭系统
                    return;
                }
                // 如果有敌人但target无效，不关闭系统，让AI继续寻找目标
            }

            // 逃跑模式的停止条件
            if (ship.getHullLevel() < MIN_HULL_LEVEL) {
                // 条件1：周围没有敌人了（安全了）
                boolean hasNearbyEnemy = false;
                for (ShipAPI enemy : AIUtils.getNearbyEnemies(ship, STOP_ESCAPE_RANGE)) {
                    if (enemy.isFighter() || enemy.isDrone())
                        continue;
                    if (enemy.isPhased())
                        continue;
                    hasNearbyEnemy = true;
                    break;
                }

                if (!hasNearbyEnemy) {
                    ship.useSystem(); // 已经安全，关闭系统
                    return;
                }

                // 条件2：距离最近的敌人超过3000（逃得够远了）
                ShipAPI nearestEnemy = AIUtils.getNearestEnemy(ship);
                if (nearestEnemy != null && !nearestEnemy.isFighter() && !nearestEnemy.isDrone()) {
                    if (!MathUtils.isWithinRange(ship, nearestEnemy, STOP_ESCAPE_RANGE)) {
                        ship.useSystem(); // 逃得够远，关闭系统
                        return;
                    }
                }
            }
        }

        if (!AIUtils.canUseSystemThisFrame(ship))
            return;

        // 逃跑模式：船体受损时远离敌人
        if (ENABLE_ESCAPE && ship.getHullLevel() < MIN_HULL_LEVEL) {
            if (shouldEscape()) {
                ship.useSystem();
                return;
            }
        }

        // 追击模式：追击敌人
        if (ENABLE_CHASE && isBasicallyValidEnemyShip(ship, target)) {
            if (shouldChase(target)) {
                ship.useSystem();
                return;
            }
        }
    }

    /**
     * 判断是否应该逃跑
     */
    private boolean shouldEscape() {
        // 检查附近是否有敌人
        for (ShipAPI enemy : AIUtils.getNearbyEnemies(ship, ESCAPE_RANGE)) {
            if (enemy.isFighter() || enemy.isDrone())
                continue;
            if (enemy.isPhased())
                continue;

            // 如果正对着远离敌人的方向，则使用系统
            float angleToEnemy = VectorUtils.getAngle(ship.getLocation(), enemy.getLocation());
            float angleDiff = MathUtils.getShortestRotation(ship.getFacing(), angleToEnemy);

            // 如果背对敌人（角度差大于90度），则启动
            if (Math.abs(angleDiff) > 90f) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否应该追击
     */
    private boolean shouldChase(ShipAPI target) {
        if (target == null)
            return false;
        if (target.isPhased())
            return false;

        // 检查距离
        if (!MathUtils.isWithinRange(ship, target, CHASE_RANGE))
            return false;

        // 检查朝向
        float angleToTarget = VectorUtils.getAngle(ship.getLocation(), target.getLocation());
        float angleDiff = MathUtils.getShortestRotation(ship.getFacing(), angleToTarget);

        // 如果基本正对目标，则启动
        return Math.abs(angleDiff) < FACING_TOLERANCE;
    }

    /**
     * 检查目标是否有效
     */
    private boolean isBasicallyValidEnemyShip(ShipAPI ship, ShipAPI target) {
        if (target == null)
            return false;
        if (target.getOwner() == ship.getOwner())
            return false;
        if (target.isFighter() || target.isDrone())
            return false;
        if (target.isHulk())
            return false;
        if (target.isShuttlePod())
            return false;
        return true;
    }
}
