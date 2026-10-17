package data.shipsystems.scripts.ai;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;

public class Moci_PointDefenseAI implements ShipSystemAIScript {
    
    private ShipAPI ship;
    private ShipSystemAPI system;
    private CombatEngineAPI engine;
    private final IntervalUtil tracker = new IntervalUtil(0.5f, 1f); // 检测间隔控制器

    // 系统参数配置
    private static final float TRIGGER_DISTANCE = 200f;    // 射程缓冲距离（单位：su）
    private static final float LOW_FLUX_THRESHOLD = 0.9f;  // 幅能分界线（90%）
    private float detectionRange = 0f;                     // 动态计算的检测范围
    private boolean hasPointDefenseWeapons = false;        // 当前舰船是否装备了实际点防武器

    // 威胁等级阈值
    // 低幅能条件（flux < 90%）
    private static final int LOW_CAPITAL_THRESHOLD = 1;    // 主力舰/巡洋舰数量阈值
    private static final int LOW_ESCORT_THRESHOLD = 2;     // 小型舰船数量阈值
    private static final int LOW_MISSILE_THRESHOLD = 4;    // 导弹数量阈值

    // 高幅能条件（flux >= 90%）
    private static final int HIGH_CAPITAL_THRESHOLD = 2;
    private static final int HIGH_ESCORT_THRESHOLD = 3;
    private static final int HIGH_MISSILE_THRESHOLD = 6;

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.engine = engine;
        updateDetectionRange(); // 初始化时计算检测范围
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        // 基础状态检查：引擎暂停/舰船无效/系统不可用时直接返回
        if (engine.isPaused() || ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 推进间隔计时器
        tracker.advance(amount);
        if (!tracker.intervalElapsed()) return; // 每0.5-1秒检测一次

        // 系统状态检查：正在运行/冷却中/弹药耗尽时不处理
        if (system.isActive() || system.isCoolingDown() || system.isOutOfAmmo()) {
            return;
        }

        updateDetectionRange();       // 更新动态检测范围
        if (!hasPointDefenseWeapons) {
            return;
        }

        var threat = assessThreatLevel(); // 获取当前威胁等级

        // 决策是否激活系统
        if (shouldActivateSystem(threat)) {
            ship.useSystem();
        }
    }

    /**
     * 动态检测范围计算
     */
    private void updateDetectionRange() {
        float maxRange = 0f;
        boolean foundPointDefense = false;
        // 遍历所有武器，找到点防御武器的最大射程
        for (var weapon : ship.getAllWeapons()) {
            if (isPointDefenseWeapon(weapon)) {
                foundPointDefense = true;
                maxRange = Math.max(maxRange, weapon.getRange());
            }
        }
        hasPointDefenseWeapons = foundPointDefense;
        // 最终检测范围 = 最大武器射程 + 缓冲距离
        detectionRange = foundPointDefense ? maxRange + TRIGGER_DISTANCE : 0f;
    }

    /**
     * 威胁评估
     */
    private ThreatLevel assessThreatLevel() {
        var threat = new ThreatLevel();
        // 合并舰船和导弹实体列表
        var entities = new ArrayList<CombatEntityAPI>(engine.getShips());
        entities.addAll(engine.getMissiles());
        float detectionRangeSq = detectionRange * detectionRange;

        for (var entity : entities) {
            // 排除友军单位
            if (entity.getOwner() == ship.getOwner()) continue;

            // 距离检测
            float distanceSq = Misc.getDistanceSq(ship.getLocation(), entity.getLocation());
            if (distanceSq > detectionRangeSq) continue;

            // 分类统计威胁
            if (entity instanceof ShipAPI enemyShip) {
                var size = enemyShip.getHullSize();
                // 主力舰/巡洋舰计数
                if (size == ShipAPI.HullSize.CAPITAL_SHIP || size == ShipAPI.HullSize.CRUISER) {
                    threat.capitalCount++;
                }
                // 驱逐舰/护卫舰/战斗机计数
                else if (size == ShipAPI.HullSize.DESTROYER ||
                        size == ShipAPI.HullSize.FRIGATE ||
                        size == ShipAPI.HullSize.FIGHTER) {
                    threat.escortCount++;
                }
            } else if (entity instanceof MissileAPI) {
                threat.missileCount++; // 导弹计数
            }
        }
        return threat;
    }

    /**
     * 激活决策
     */
    private boolean shouldActivateSystem(ThreatLevel threat) {
        float fluxLevel = ship.getFluxTracker().getFluxLevel();

        // 低幅能条件：宽松阈值
        if (fluxLevel < LOW_FLUX_THRESHOLD) {
            return threat.capitalCount >= LOW_CAPITAL_THRESHOLD ||
                    threat.escortCount >= LOW_ESCORT_THRESHOLD ||
                    threat.missileCount >= LOW_MISSILE_THRESHOLD;
        }
        // 高幅能条件：严格阈值
        else {
            return threat.capitalCount >= HIGH_CAPITAL_THRESHOLD ||
                    threat.escortCount >= HIGH_ESCORT_THRESHOLD ||
                    threat.missileCount >= HIGH_MISSILE_THRESHOLD;
        }
    }

    /**
     * 点防御武器识别
     */
    private boolean isPointDefenseWeapon(WeaponAPI weapon) {
        if (weapon == null) {
            return false;
        }

        var slot = weapon.getSlot();
        if (weapon.isDecorative() ||
                weapon.hasAIHint(WeaponAPI.AIHints.SYSTEM) ||
                slot == null ||
                slot.isDecorative() ||
                slot.isSystemSlot()) {
            return false;
        }

        // 通过AIHint标签判断是否为点防御武器
        return weapon.hasAIHint(WeaponAPI.AIHints.PD) ||
                weapon.hasAIHint(WeaponAPI.AIHints.PD_ALSO) ||
                weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY);
    }

    /**
     * 威胁数据容器
     * 用于存储当前检测范围内的威胁统计
     */
    private static class ThreatLevel {
        int capitalCount = 0;  // 主力舰/巡洋舰数量
        int escortCount = 0;   // 小型舰船/战斗机数量
        int missileCount = 0;  // 导弹数量
    }
}
