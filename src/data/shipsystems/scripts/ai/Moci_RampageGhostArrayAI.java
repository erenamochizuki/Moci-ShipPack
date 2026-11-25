package data.shipsystems.scripts.ai;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;

import data.shipsystems.scripts.drone.Moci_RampageGhostArrayStats;
import data.shipsystems.scripts.drone.Moci_RampageGhostStats;

/**
 * 暴走亡灵·列阵的系统 AI。
 * 满足以下任一条件时启动：
 * 当前目标在残影矩形内(残影会围住它集火)；
 * 当前目标在正前方的齐射带中部且在主炮射程内；
 * 齐射带内有两艘以上的敌舰。
 */
public class Moci_RampageGhostArrayAI implements ShipSystemAIScript {
    /** 齐射带中部的宽度比例：目标偏离中线超过这个比例时，平行弹幕的命中率太低，不值得启动 */
    private static final float LANE_CORE_FRACTION = 0.5f;
    /** 幅能水平高于此值时不启动(启动本身要消耗幅能，期间还不能排幅) */
    private static final float MAX_FLUX_LEVEL = 0.6f;
    private static final float FALLBACK_RANGE = 1200f;

    private ShipAPI ship;
    private ShipSystemAPI system;
    private ShipwideAIFlags flags;
    private CombatEngineAPI engine;
    private final IntervalUtil decisionInterval = new IntervalUtil(0.2f, 0.4f);

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.flags = flags;
        this.engine = engine;
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine == null || engine.isPaused() || ship == null || system == null
                || !ship.isAlive() || ship.isHulk()) {
            return;
        }
        decisionInterval.advance(amount);
        if (!decisionInterval.intervalElapsed()) return;

        if (system.isActive() || system.isCoolingDown() || system.isOutOfAmmo() || !system.canBeActivated()) return;
        if (ship.getFluxTracker().isOverloadedOrVenting()) return;
        if (ship.getFluxLevel() > MAX_FLUX_LEVEL) return;

        if (target == null && flags != null) {
            Object test = flags.getCustom(ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
            if (test instanceof ShipAPI) target = (ShipAPI) test;
        }

        float range = getFireRange();
        boolean use = false;
        if (Moci_RampageGhostStats.isValidTarget(ship, target)) {
            use = Moci_RampageGhostArrayStats.isInsideRectangle(ship, target, 0f)
                    || isInLane(target, range, LANE_CORE_FRACTION);
        }
        if (!use) {
            int inLane = 0;
            for (ShipAPI other : engine.getShips()) {
                if (other.isFighter() || !Moci_RampageGhostStats.isValidTarget(ship, other)) continue;
                if (isInLane(other, range, 1f) && ++inLane >= 2) {
                    use = true;
                    break;
                }
            }
        }
        if (use) ship.useSystem();
    }

    private float getFireRange() {
        for (WeaponAPI weapon : Moci_RampageGhostStats.findFireWeapons(ship)) {
            return weapon.getRange();
        }
        return FALLBACK_RANGE;
    }

    /** 目标是否在舰船正前方的齐射带内：横向不超过带宽的 widthFraction，纵向在主炮射程内 */
    private boolean isInLane(ShipAPI other, float range, float widthFraction) {
        double facing = Math.toRadians(ship.getFacing());
        float fx = (float) Math.cos(facing);
        float fy = (float) Math.sin(facing);
        float dx = other.getLocation().x - ship.getLocation().x;
        float dy = other.getLocation().y - ship.getLocation().y;
        float lateral = dx * fy - dy * fx;
        float forward = dx * fx + dy * fy;
        float radius = other.getCollisionRadius();
        return Math.abs(lateral) <= Moci_RampageGhostArrayStats.HALF_WIDTH * widthFraction + radius
                && forward > 0f
                && forward <= range + Moci_RampageGhostArrayStats.HALF_DEPTH + radius;
    }
}
