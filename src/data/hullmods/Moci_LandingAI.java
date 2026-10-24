package data.hullmods;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.drones.PIDController;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.Misc;

import data.scripts.ships.ai.Moci_MechTurretAI;

/** 导航至已预约的泊位；整备会话负责清理状态，整备湾负责安排起飞。 */
public class Moci_LandingAI implements ShipAIPlugin {
    private static final boolean ENABLE_DEBUG_LOGS = false;
    private static final String ACTIVE_REFIT_REASON_KEY = "Moci_ActiveRefitReason";
    private static final float LANDING_TRIGGER_DISTANCE = 200f;
    // 接近与降落导航的总时限；动画启动和整备移交的宽限在 Moci_RefitSession 中单独调整。
    private static final float LANDING_TIMEOUT = 50f;

    public enum STATE { MOVING, LANDING, FINISHED_LANDING }

    private final ShipwideAIFlags flag;
    private final ShipAIConfig config;
    private final ShipAPI ship;
    private final Moci_RepairBayScript.Moci_RepairBay bay;
    private final boolean shouldResetAI;
    private final PIDController controller = new PIDController(2f, 2f, 6f, 0.5f);
    private Moci_RefitSession session;
    private STATE state = STATE.MOVING;
    private float stateTimer;
    private float totalTimer;
    private boolean landingTriggered;
    private boolean finished;
    private boolean shouldRemove;
    private Vector2f targetVelocity = new Vector2f();
    private float approachFactor;

    /** 启动失败时，不设置活动标记、控制锁，也不修改整备跟踪状态。 */
    public static boolean startLanding(ShipAPI ship, Moci_RepairBayScript.Moci_RepairBay bay) {
        if (ship == null || ship.getShipAI() == null || !Moci_RefitPolicy.canStartRefit(ship)) return false;
        Moci_LandingAI ai = new Moci_LandingAI(ship, ship.getAIFlags(), ship.getShipAI().getConfig(), bay);
        if (ai.shouldRemove()) return false;
        ship.setShipAI(ai);
        ai.session.bindInstalledAI();
        if (Global.getCombatEngine().getPlayerShip() != ship) ship.setShipTarget(bay.getShip());
        return true;
    }

    public Moci_LandingAI(ShipAPI ship, ShipwideAIFlags flag, ShipAIConfig config,
            Moci_RepairBayScript.Moci_RepairBay bay) {
        this(ship, flag, config, bay, true);
    }

    public Moci_LandingAI(ShipAPI ship, ShipwideAIFlags flag, ShipAIConfig config,
            Moci_RepairBayScript.Moci_RepairBay bay, boolean shouldResetAI) {
        this.ship = ship;
        this.flag = flag;
        this.config = config;
        this.bay = bay;
        this.shouldResetAI = shouldResetAI;
        Moci_RepairBayScript.Moci_RepairBayStatus status = getBayStatus();
        if (ship == null || ship.getShipAI() == null || !Moci_RefitPolicy.canStartRefit(ship)
                || bay == null || bay.getShip() == null || bay.getShip().getOwner() != ship.getOwner()
                || status == null || !status.tryReserve(ship)) {
            shouldRemove = true;
            return;
        }
        Moci_MechTurretAI.releaseTurretTarget(ship);
        session = Moci_RefitSession.getOrCreate(ship);
        session.beginApproach(bay, this);
        // 兼容其他 AI 将本控制器作为内部导航模块调用的情况。
        if (!shouldResetAI) session.bindInstalledAI();
        ship.setCustomData(ACTIVE_REFIT_REASON_KEY, Moci_LandingSequence.getRefitTriggerDetail(ship));
    }

    private Moci_RepairBayScript.Moci_RepairBayStatus getBayStatus() {
        if (bay == null || bay.getShip() == null) return null;
        Moci_RepairBayScript script = Moci_RepairBayScript.getInstance(bay.getShip());
        return script != null ? script.getBay(bay) : null;
    }

    @Override
    public void advance(float amount) {
        if (shouldRemove || session == null || !session.isActive()) return;
        if (Global.getCombatEngine() == null || Global.getCombatEngine().isPaused()) return;
        session.advance();
        if (!session.isActive() || finished) return;
        if (!ship.isAlive() || !Moci_RefitPolicy.isEligibleThisBattle(ship)) {
            abort();
            return;
        }
        totalTimer += amount;
        stateTimer += amount;
        if (totalTimer > LANDING_TIMEOUT) {
            logEvent("着陆超时，中止。state=" + state);
            abort();
            return;
        }
        Moci_RepairBayScript.Moci_RepairBayStatus status = getBayStatus();
        if (status == null || !status.isAvailableFor(ship) || !status.renewReservation(ship)) {
            logEvent("整备湾或预约失效，中止着陆。");
            abort();
            return;
        }
        session.maintainApproachCollision();
        Vector2f target = bay.getLocation();
        float distance = Misc.getDistance(ship.getLocation(), target);
        STATE previous = state;
        switch (state) {
            case MOVING:
                ship.getVelocity().set(calculateSmoothPath(ship.getLocation(), target,
                        ship.getVelocity(), ship.getMaxSpeedWithoutBoost(), distance, amount));
                controller.rotate(VectorUtils.getAngle(ship.getLocation(), target), ship);
                if (distance < 600f) state = STATE.LANDING;
                break;
            case LANDING:
                advanceLanding(target, distance, amount);
                break;
            case FINISHED_LANDING:
                if (!session.isLandingAnimationComplete() || !status.startRepair(ship)) {
                    abort();
                    return;
                }
                finished = true;
                break;
        }
        if (state != previous) {
            stateTimer = 0f;
            logEvent("状态切换: " + previous + " -> " + state);
        }
        if (ship == Global.getCombatEngine().getPlayerShip()) {
            String text = state == STATE.MOVING ? "撤退中"
                    : state == STATE.LANDING ? "正在着陆" : "正在整备";
            Global.getCombatEngine().maintainStatusForPlayerShip("Moci_LANDINGAI",
                    Global.getSettings().getSpriteName("ui", "icon_tactical_bdeck"), "整备程序", text, true);
        }
    }

    private void advanceLanding(Vector2f target, float distance, float amount) {
        if (distance <= LANDING_TRIGGER_DISTANCE && !landingTriggered) {
            session.beginLandingAnimation();
            landingTriggered = true;
        }
        Vector2f velocity = calculateSmoothPath(ship.getLocation(), target, ship.getVelocity(),
                ship.getMaxSpeedWithoutBoost() * 1.2f, distance, amount);
        if (distance > 50f) ship.getVelocity().set(velocity);
        float targetFacing = Misc.getAngleInDegrees(ship.getLocation(), target);
        float angleDiff = MathUtils.getShortestRotation(ship.getFacing(), targetFacing);
        float alignFactor = Math.max(0f, Math.min(1f, (600f - distance) / 600f * 3f));
        float facingBlend = 1f - (float) Math.exp(-alignFactor * 4.8f * Math.max(0f, amount));
        ship.setFacing(ship.getFacing() + angleDiff * facingBlend);
        if (landingTriggered) {
            session.lockLandingControls();
            if (session.isLandingAnimationComplete()) {
                Moci_RepairBayScript.markLandingAnimationFinished(ship, "native_finished_landing_flag");
                state = STATE.FINISHED_LANDING;
            }
        }
        if (ship.isLanding() && distance > 5f && distance <= 50f) {
            float radius = Math.max(1f, bay.getShip().getCollisionRadius());
            float factor = 2f - Math.min(1f, distance / radius);
            // 保持原先每秒 60 帧时的运动效果，并让较低帧率下的插值仍然稳定。
            float positionBlend = 1f - (float) Math.pow(1f - factor * 0.05f, Math.max(0f, amount) * 60f);
            float velocityBlend = 1f - (float) Math.pow(Math.max(0f, 1f - factor * 0.5f), Math.max(0f, amount) * 60f);
            Vector2f location = ship.getLocation();
            location.x += (target.x - location.x) * positionBlend;
            location.y += (target.y - location.y) * positionBlend;
            Vector2f currentVelocity = ship.getVelocity();
            Vector2f carrierVelocity = bay.getShip().getVelocity();
            currentVelocity.x += (carrierVelocity.x - currentVelocity.x) * velocityBlend;
            currentVelocity.y += (carrierVelocity.y - currentVelocity.y) * velocityBlend;
        }
    }

    private Vector2f calculateSmoothPath(Vector2f currentPos, Vector2f targetPos, Vector2f currentVel,
            float maxSpeed, float distance, float amount) {
        // 初始化目标速度
        if (targetVelocity.length() < 0.1f) {
            targetVelocity = new Vector2f(currentVel);
        }

        // 计算目标位置的移动速度（考虑母舰运动）
        Vector2f targetVel = new Vector2f(0f, 0f);
        if (bay != null && bay.getShip() != null) {
            targetVel = new Vector2f(bay.getShip().getVelocity());
        }

        // 计算理想方向向量
        Vector2f idealDir = new Vector2f(
                targetPos.x - currentPos.x,
                targetPos.y - currentPos.y);

        if (idealDir.length() < 0.1f) {
            return new Vector2f(0f, 0f); // 已经非常接近目标
        }

        // 标准化理想方向
        idealDir.normalise();

        // 根据距离计算接近因子 - 提高接近速度
        float targetApproachFactor;
        if (distance > 600f) {
            targetApproachFactor = 0.1f; // 远距离时更强的修正
        } else if (distance > 300f) {
            targetApproachFactor = 0.15f + (600f - distance) / 300f * 0.15f; // 中距离渐进增强
        } else if (distance > 100f) {
            targetApproachFactor = 0.3f + (300f - distance) / 200f * 0.3f; // 近距离明显修正
        } else {
            targetApproachFactor = 0.6f + (100f - distance) / 100f * 0.4f; // 极近距离强力修正
        }

        // 平滑过渡接近因子
        approachFactor += (targetApproachFactor - approachFactor) * Math.min(amount * 5f, 0.2f);

        // 计算目标速度大小 - 优化减速曲线，保持更高速度
        float targetSpeed;
        if (distance > 500f) {
            targetSpeed = maxSpeed; // 远距离全速
        } else if (distance > 200f) {
            targetSpeed = maxSpeed * (0.85f + distance / 500f * 0.15f); // 中距离轻微减速
        } else if (distance > 100f) {
            targetSpeed = maxSpeed * 0.85f * (distance / 200f); // 近距离保持较高速度
        } else if (distance > 50f) {
            targetSpeed = maxSpeed * 0.7f * (distance / 100f); // 接近时适度减速
        } else if (distance > 20f) {
            targetSpeed = maxSpeed * 0.5f * (distance / 50f); // 最后阶段减速
        } else {
            targetSpeed = maxSpeed * 0.3f * (distance / 20f); // 极近距离减速
        }

        // 确保最低速度 - 提高到50单位，避免过慢
        targetSpeed = Math.max(targetSpeed, 50f);

        // 计算理想速度向量（考虑目标运动）
        Vector2f idealVelocity = new Vector2f(
                idealDir.x * targetSpeed + targetVel.x,
                idealDir.y * targetSpeed + targetVel.y);

        // 平滑插值到理想速度 - 提高响应速度
        float velocityBlend = 1f - (float) Math.exp(-approachFactor * Math.max(0f, amount) * 80f);
        targetVelocity.x += (idealVelocity.x - targetVelocity.x) * velocityBlend;
        targetVelocity.y += (idealVelocity.y - targetVelocity.y) * velocityBlend;

        // 限制最大速度
        float resultSpeed = targetVelocity.length();
        if (resultSpeed > maxSpeed * 1.5f) { // 允许更高的最大速度
            targetVelocity.normalise();
            targetVelocity.scale(maxSpeed * 1.5f);
        }

        return targetVelocity;
    }

    protected void abort() {
        shouldRemove = true;
        if (session != null) session.abort(shouldResetAI);
    }

    void sessionFinished() {
        shouldRemove = true;
    }

    public boolean shouldRemove() { return shouldRemove; }
    public STATE getState() { return state; }
    public Moci_RepairBayScript.Moci_RepairBay getBay() { return bay; }
    public float getStateTimer() { return stateTimer; }
    @Override public boolean needsRefit() { return false; }
    @Override public ShipwideAIFlags getAIFlags() { return flag; }
    @Override public ShipAIConfig getConfig() { return config; }
    @Override public void forceCircumstanceEvaluation() { }
    @Override public void cancelCurrentManeuver() { }
    // 本导航 AI 不负责开火；起飞后的禁火延迟由恢复后的战斗 AI 执行。
    @Override public void setDoNotFireDelay(float amount) { }

    public static String getRememberedRefitReason(ShipAPI ship) {
        if (ship == null) return "原因=未知";
        Object remembered = ship.getCustomData().get(ACTIVE_REFIT_REASON_KEY);
        return remembered instanceof String && !((String) remembered).isEmpty()
                ? (String) remembered : Moci_LandingSequence.getRefitTriggerDetail(ship);
    }

    private void logEvent(String message) {
        if (ENABLE_DEBUG_LOGS) Global.getLogger(Moci_LandingAI.class).info("[MOCI_LANDING] " + ship.getName() + " - " + message);
    }
}
