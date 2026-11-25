package data.ai;

import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.drones.PIDController;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.Misc;

import data.scripts.combatsquad.Moci_SquadRecallController;
import data.scripts.util.Moci_AIRestoreUtil;

/**
 * 「编队集结」期间临时接管僚机的导航 AI：只负责飞向站位，不做战术判断。
 *
 * <p>生命周期由 {@code Moci_SquadRecallController} 管理：它每帧更新目的地，
 * 并在到位、超时或被打断时恢复僚机原来的 AI。武器保持原有的自动开火设置。</p>
 */
public class Moci_SquadRecallAI implements ShipAIPlugin {
    private final ShipAPI ship;
    private final ShipAIPlugin savedAI;
    private final ShipAIConfig config;
    private final ShipwideAIFlags flags;
    private final PIDController controller = new PIDController(2f, 2f, 6f, 0.5f);
    private final Vector2f destination = new Vector2f();
    private boolean finished;

    public Moci_SquadRecallAI(ShipAPI ship, ShipAIPlugin savedAI, ShipAIConfig savedConfig) {
        this.ship = ship;
        this.savedAI = savedAI;
        this.config = savedConfig != null ? savedConfig : new ShipAIConfig();
        this.flags = ship.getAIFlags() != null ? ship.getAIFlags() : new ShipwideAIFlags();
        this.destination.set(ship.getLocation());
    }

    public void setDestination(Vector2f location) {
        if (location != null) {
            this.destination.set(location);
        }
    }

    public Vector2f getDestination() {
        return destination;
    }

    /** 召回已结束。此后若本对象又被装回舰船（见 {@link #advance}），会立即把原 AI 换回来。 */
    public void markFinished() {
        finished = true;
    }

    @Override
    public void advance(float amount) {
        if (Global.getCombatEngine() == null || Global.getCombatEngine().isPaused()) return;

        if (finished) {
            // 召回途中被整备流程接管时，整备会话会把「当时的 AI」也就是本对象记作原 AI，
            // 整备结束后再装回来。这时召回早已结束，直接把真正的原 AI 换回去。
            Moci_AIRestoreUtil.restoreSavedOrDefaultAI(ship, savedAI, config);
            return;
        }

        float distance = Misc.getDistance(ship.getLocation(), destination);
        // 近距离由召回控制器吸附并对齐机头，避免同时下达推力与转向指令。
        if (distance <= Moci_SquadRecallController.ATTRACTION_DISTANCE) return;
        controller.move(destination, ship);
        controller.rotate(Misc.getAngleInDegrees(ship.getLocation(), destination), ship);
    }

    @Override
    public void setDoNotFireDelay(float amount) {
    }

    @Override
    public void forceCircumstanceEvaluation() {
    }

    @Override
    public boolean needsRefit() {
        return false;
    }

    @Override
    public ShipwideAIFlags getAIFlags() {
        return flags;
    }

    @Override
    public void cancelCurrentManeuver() {
    }

    @Override
    public ShipAIConfig getConfig() {
        return config;
    }
}
