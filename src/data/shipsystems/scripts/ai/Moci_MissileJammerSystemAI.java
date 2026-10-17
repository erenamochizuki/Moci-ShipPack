package data.shipsystems.scripts.ai;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

/**
 * 导弹干扰系统AI脚本
 * 功能：基于权重系统智能激活干扰系统
 */
public class Moci_MissileJammerSystemAI implements ShipSystemAIScript {
    private ShipAPI ship; // AI控制的舰船
    private ShipSystemAPI system; // 干扰系统
    private CombatEngineAPI engine; // 战斗引擎

    // 权重系统参数
    private static final float ACTIVATION_THRESHOLD = 0.7f; // 激活阈值
    private static final float DETECTION_RANGE = 3300f; // 检测范围
    private static final float UNDER_ATTACK_RANGE = 1800f; // 受攻击时的敌军检测范围

    // 用于跟踪受攻击状态
    private float lastRemoteAttackTime = 0f; // 最后一次受到远程攻击的时间
    private static final float REMOTE_ATTACK_DURATION = 10f; // 远程攻击权重持续时间（秒）

    // 伤害跟踪器
    private DamageTracker damageTracker;

    // 节流扫描缓存
    private final IntervalUtil scanInterval = new IntervalUtil(0.2f, 0.3f);
    private int cachedEnemyCapitalCount = 0;
    private int cachedTotalEnemyCount = 0;
    private int cachedFriendlyFrigateCount = 0;
    private boolean cachedHasEnemyNearby = false;
    private boolean cachedHasEnemyInUnderAttackRange = false;

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.engine = engine;

        // 初始化伤害跟踪器
        this.damageTracker = new DamageTracker(this);
        ship.addListener(damageTracker);
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (ship == null || system == null || engine == null || engine.isPaused() || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 检查是否安装了禁用AI的船插
        if (ship.getVariant().getHullMods().contains("Moci_minovsky_ai_disabled")) {
            // AI被禁用，不执行任何逻辑（玩家仍可手动开启）
            return;
        }

        updateNearbyState(amount);
        float activationWeight = calculateActivationWeight(missileDangerDir, collisionDangerDir, target);

        if (!system.isActive() && system.getCooldownRemaining() == 0) {
            // 权重达到阈值时激活系统
            if (activationWeight >= ACTIVATION_THRESHOLD) {
                ship.useSystem();
            }
        } else if (system.isActive()) {
            // 系统激活时，如果权重低于阈值则关闭系统
            if (activationWeight < ACTIVATION_THRESHOLD) {
                ship.useSystem(); // 切换式系统，再次调用useSystem()来关闭
            }
        }
    }

    /**
     * 计算系统激活权重
     * 权重组成：
     * - 单个敌军主力舰：0.7
     * - 多个敌军主力舰：0.4 + (数量-1) * 0.2
     * - 友军护卫舰保护：0.3 + (数量-3) * 0.1
     * - 导弹威胁：0.2
     * - 远程攻击：0.3
     */
    private float calculateActivationWeight(Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        float weight = 0f;

        // 1. 敌军主力舰权重计算
        weight += calculateEnemyCapitalWeight();

        // 2. 友军护卫舰权重计算
        weight += calculateFriendlyFrigateWeight();

        // 3. 导弹威胁权重
        if (missileDangerDir != null) {
            weight += 0.2f;
        }

        // 4. 受攻击且附近无敌军权重
        weight += calculateUnderAttackWeight();

        return weight;
    }

    /**
     * 计算敌军主力舰权重
     * 3000范围内敌军主力舰超过1个，基础权重0.4，每多1艘+0.2
     * 如果范围内只有一个敌军且是主力舰，权重+0.7
     */
    private float calculateEnemyCapitalWeight() {
        // 特殊情况：范围内只有一个敌军且是主力舰
        if (cachedTotalEnemyCount == 1 && cachedEnemyCapitalCount == 1) {
            return 0.7f;
        }

        // 常规情况：多个敌军主力舰
        if (cachedEnemyCapitalCount > 1) {
            return 0.4f + (cachedEnemyCapitalCount - 1) * 0.2f;
        }

        return 0f;
    }

    /**
     * 计算友军护卫舰权重
     * 3000范围内我方护卫舰数量超过3个且有敌军，基础数值0.3，每多1艘护卫舰+0.1
     */
    private float calculateFriendlyFrigateWeight() {
        if (cachedFriendlyFrigateCount > 3 && cachedHasEnemyNearby) {
            return 0.3f + (cachedFriendlyFrigateCount - 3) * 0.1f;
        }
        return 0f;
    }

    /**
     * 当受到远程攻击时调用此方法
     */
    public void onRemoteAttack() {
        lastRemoteAttackTime = engine.getTotalElapsedTime(false);
    }

    /**
     * 检查是否为远程攻击（2000范围内无敌军）
     */
    private boolean isRemoteAttack() {
        return !cachedHasEnemyInUnderAttackRange;
    }

    /**
     * 伤害跟踪器 - 监听舰船受到的伤害并判断是否为远程攻击
     */
    private static class DamageTracker implements DamageTakenModifier {
        private final Moci_MissileJammerSystemAI ai;

        public DamageTracker(Moci_MissileJammerSystemAI ai) {
            this.ai = ai;
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point,
                boolean shieldHit) {
            // 检查是否为远程攻击
            if (ai.isRemoteAttack()) {
                ai.onRemoteAttack();
            }
            return null; // 不修改伤害
        }
    }

    /**
     * 计算受攻击且附近无敌军权重
     * 舰船遭到光束或弹丸攻击且2000范围内没有敌军时，权重+0.3，持续10秒
     */
    private float calculateUnderAttackWeight() {
        float currentTime = engine.getTotalElapsedTime(false);

        // 检查是否在远程攻击权重持续时间内
        boolean inRemoteAttackDuration = (currentTime - lastRemoteAttackTime) <= REMOTE_ATTACK_DURATION;

        if (inRemoteAttackDuration) {
            return 0.3f;
        }

        return 0f;
    }

    private void updateNearbyState(float amount) {
        scanInterval.advance(amount);
        if (!scanInterval.intervalElapsed()) {
            return;
        }

        int enemyCapitalCount = 0;
        int totalEnemyCount = 0;
        int friendlyFrigateCount = 0;
        boolean hasEnemyNearby = false;
        boolean hasEnemyInUnderAttackRange = false;
        float detectionRangeSq = DETECTION_RANGE * DETECTION_RANGE;
        float underAttackRangeSq = UNDER_ATTACK_RANGE * UNDER_ATTACK_RANGE;

        for (ShipAPI nearbyShip : engine.getShips()) {
            if (nearbyShip == null || nearbyShip == ship || nearbyShip.isHulk()) {
                continue;
            }

            float distanceSq = Misc.getDistanceSq(ship.getLocation(), nearbyShip.getLocation());
            if (distanceSq > detectionRangeSq) {
                if (nearbyShip.getOwner() != ship.getOwner() && distanceSq <= underAttackRangeSq) {
                    hasEnemyInUnderAttackRange = true;
                }
                continue;
            }

            if (nearbyShip.getOwner() == ship.getOwner()) {
                if (nearbyShip.getHullSize() == ShipAPI.HullSize.FRIGATE) {
                    friendlyFrigateCount++;
                }
            } else {
                totalEnemyCount++;
                hasEnemyNearby = true;
                if (nearbyShip.getHullSize() == ShipAPI.HullSize.CAPITAL_SHIP) {
                    enemyCapitalCount++;
                }
                if (distanceSq <= underAttackRangeSq) {
                    hasEnemyInUnderAttackRange = true;
                }
            }
        }

        cachedEnemyCapitalCount = enemyCapitalCount;
        cachedTotalEnemyCount = totalEnemyCount;
        cachedFriendlyFrigateCount = friendlyFrigateCount;
        cachedHasEnemyNearby = hasEnemyNearby;
        cachedHasEnemyInUnderAttackRange = hasEnemyInUnderAttackRange;
    }
}
