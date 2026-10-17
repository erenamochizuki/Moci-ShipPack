package data.shipsystems.scripts.ai;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashSet;
import java.util.Set;

public class Moci_LidararraySystemAI implements ShipSystemAIScript {

    private ShipAPI ship;
    private ShipSystemAPI system;
    private ShipwideAIFlags flags;
    private CombatEngineAPI engine;
    private boolean phaseCloak = false;

    private IntervalUtil tracker = new IntervalUtil(0.5f, 1f);
    private float sinceLast = 0f;

    private static final String MAIN_GUN_SLOT = "MAINGUN_";
    private static final String SYSTEM_ID = "Moci_lidararray";

    private Set<WeaponAPI> mainGuns = new HashSet<>();

    private float systemRange = 0f;

    private static final float MAX_FIRING_ARC = 90f; // 最大射击角度

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.flags = flags;
        this.engine = engine;

        // 通过检查系统ID来判断槽位（完全遵循DefenseDroneAI的模式）
        if (system != null && SYSTEM_ID.equals(system.getId())) {
            this.system = system;
        } else if (ship.getPhaseCloak() != null && SYSTEM_ID.equals(ship.getPhaseCloak().getId())) {
            this.system = ship.getPhaseCloak();
            this.phaseCloak = true;
        }

        // 初始化时缓存主炮武器并计算系统射程
        cacheMainGuns();
        calculateSystemRange();
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        // 推进时间
        tracker.advance(amount);
        sinceLast += amount;

        // 如果引擎暂停、系统已激活或没有目标，直接返回
        if (engine == null || engine.isPaused() || system.isActive() || target == null) {
            return;
        }

        // 如果时间间隔已过，进行相关检查
        if (tracker.intervalElapsed()) {
            // 判断目标是否在系统射程内
            boolean targetInRange = isTargetInRange(target);

            // 判断目标是否在射界内
            boolean targetInArc = isTargetInArc(target);

            // 如果目标在射程内且在射界内，则根据条件激活系统
            if (targetInRange && targetInArc) {
                activateSystem();
            }
        }
    }

    // 缓存主炮武器
    private void cacheMainGuns() {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot().getId().startsWith(MAIN_GUN_SLOT)) {
                mainGuns.add(weapon); // 将主炮武器添加到 Set 中
            }
        }
    }

    // 计算系统射程
    private void calculateSystemRange() {
        WeaponAPI farthestRangeGun = findFarthestRangeGun();
        if (farthestRangeGun != null) {
            this.systemRange = farthestRangeGun.getRange();
        } else {
            this.systemRange = 0f; // 如果没有找到主炮武器，射程设为 0
        }
    }

    // 查找射程最远的主炮武器
    private WeaponAPI findFarthestRangeGun() {
        WeaponAPI farthestRangeGun = null;
        float maxRange = 0f;

        for (WeaponAPI weapon : mainGuns) {
            float weaponRange = weapon.getRange();
            if (weaponRange > maxRange) {
                maxRange = weaponRange;
                farthestRangeGun = weapon;
            }
        }

        return farthestRangeGun;
    }

    // 判断目标是否在系统射程内
    private boolean isTargetInRange(ShipAPI target) {
        // 判断距离是否小于系统射程加上目标的碰撞半径
        float allowedRange = systemRange + target.getCollisionRadius();
        return Misc.getDistanceSq(ship.getLocation(), target.getLocation()) < allowedRange * allowedRange;
    }

    // 判断目标是否在射界内
    private boolean isTargetInArc(ShipAPI target) {
        float shipFacing = ship.getFacing();
        Vector2f targetLocation = target.getLocation();

        return Misc.isInArc(shipFacing, MAX_FIRING_ARC, ship.getLocation(), targetLocation);
    }

    // 根据条件激活系统
    private void activateSystem() {
        if (phaseCloak) {
            ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
        } else {
            ship.useSystem();
        }
        sinceLast = 0f;
    }
}
