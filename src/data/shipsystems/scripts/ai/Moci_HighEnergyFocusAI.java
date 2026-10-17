package data.shipsystems.scripts.ai;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.HashSet;
import java.util.Set;

public class Moci_HighEnergyFocusAI implements ShipSystemAIScript {

    private static final String MAIN_GUN_SLOT_PREFIX = "MAINGUN_";
    private static final float TARGET_ARC = 5f; // 正前方5度范围
    private static final float CHECK_INTERVAL = 0.3f; // 检查间隔（秒）

    private ShipAPI ship;
    private CombatEngineAPI engine;
    private ShipSystemAPI system;
    private IntervalUtil tracker = new IntervalUtil(CHECK_INTERVAL, CHECK_INTERVAL*1.5f);
    private Set<WeaponAPI> mainGuns = new HashSet<>();
    private float maxWeaponRange;

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        cacheMainGuns(); // 初始化时缓存主炮武器
        calculateMaxRange(); // 计算最大射程
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine == null || engine.isPaused()) return;
        tracker.advance(amount);

        if (tracker.intervalElapsed()) {
            if (shouldActivateSystem(target)) {
                ship.useSystem();
            }
        }
    }

    //===== 核心逻辑判断 =====//
    private boolean shouldActivateSystem(ShipAPI target) {
        // 基础条件检查
        if (system.isActive() || system.isCoolingDown()) return false;
        if (target == null || target.isHulk()) return false;

        // 角度检查（正前方5度）
        if (!Misc.isInArc(ship.getFacing(), TARGET_ARC, ship.getLocation(), target.getLocation())) {
            return false;
        }

        // 射程检查（包含目标碰撞半径）
        float allowedRange = maxWeaponRange + target.getCollisionRadius();
        if (Misc.getDistanceSq(ship.getLocation(), target.getLocation()) > allowedRange * allowedRange) {
            return false;
        }

        // 弹药可用性检查
        return hasAvailableMainGuns();
    }

    //===== 武器相关方法 =====//
    private void cacheMainGuns() {
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.getSlot().getId().startsWith(MAIN_GUN_SLOT_PREFIX)) {
                mainGuns.add(w);
            }
        }
    }

    private void calculateMaxRange() {
        maxWeaponRange = 0f;
        for (WeaponAPI w : mainGuns) {
            maxWeaponRange = Math.max(maxWeaponRange, w.getRange());
        }
    }

    private boolean hasAvailableMainGuns() {
        for (WeaponAPI w : mainGuns) {
            // 能量武器：检查是否冷却完成
            if (w.getType() == WeaponAPI.WeaponType.ENERGY && w.getCooldownRemaining() <= 0) {
                return true;
            }
            // 导弹/弹道武器：检查剩余弹药
            if (w.getAmmo() > 0 && !w.isDisabled()) {
                return true;
            }
        }
        return false;
    }
}
