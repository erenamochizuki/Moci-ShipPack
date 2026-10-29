package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.input.InputEventAPI;
import java.util.List;

/** Native toggle; narrowly preserves an existing activation across native vent flux refusal. */
public final class MinovskyFlightStats extends BaseShipSystemScript {
    public static final String SYSTEM_ID = "Moci_fw_xi_minovsky_flight";
    /** Penelope uses a separate .system definition (different useSound) but shares every guard here. */
    public static final String PENELOPE_SYSTEM_ID = "Moci_fw_xi_minovsky_flight_penelope";
    public static final String MODIFIER_SUFFIX = ":xi_minovsky_flight";
    static final String VENT_CONTINUITY_KEY = "xi.arsenal.minovsky.ventContinuity";
    private static final class Continuity {
        final ShipSystemAPI system;
        boolean manualOff;
        Continuity(ShipSystemAPI system) { this.system = system; }
    }
    public static final float SPEED_BONUS = 220f, TURN_BONUS = 95f;
    public static final float ACCELERATION_MULT = 3.2f, DECELERATION_MULT = 3.6f, TURN_ACCELERATION_MULT = 3.6f;
    public record Profile(float speed, float turn, float acceleration, float deceleration, float turnAcceleration) { }
    public static final Profile XI = new Profile(SPEED_BONUS, TURN_BONUS, ACCELERATION_MULT, DECELERATION_MULT, TURN_ACCELERATION_MULT);
    public static final Profile PENELOPE = new Profile(170f, 15f, 1.5f, 1.08f, 1.2f);
    public static Profile profileFor(ShipAPI ship) {
        return ship != null && PenelopeAgilityHullmod.isPenelope(ship.getHullSpec()) ? PENELOPE : XI;
    }

    public static String modifierId(String nativeId) {
        return (nativeId == null ? SYSTEM_ID : nativeId) + MODIFIER_SUFFIX;
    }

    /** Observe the mapped native action before core controls; never consume it or issue a second command. */
    static void observeInput(CombatEngineAPI engine, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || engine.isUIShowingDialog() || events == null) return;
        if (engine.getCombatUI() != null && (engine.getCombatUI().isShowingCommandUI()
                || engine.getCombatUI().isShowingDeploymentDialog())) return;
        ShipAPI ship = engine.getPlayerShip();
        if (!usableShip(ship) || ship.controlsLocked()) return;
        ShipSystemAPI system = ship.getSystem();
        Object value = ship.getCustomData().get(VENT_CONTINUITY_KEY);
        if (!(value instanceof Continuity continuity) || continuity.system != system || !system.isOn()) return;
        for (InputEventAPI event : events) {
            if (event == null || event.isConsumed() || event.isRepeat()) continue;
            if (event.isControlDownEvent("SHIP_USE_SYSTEM")) { continuity.manualOff = true; return; }
        }
    }

    private static boolean usableShip(ShipAPI ship) {
        return ship != null && ship.isAlive() && !ship.isHulk() && !ship.wasRemoved() && !ship.isExpired()
                && !ship.isShipSystemDisabled() && ship.getSystem() != null
                && ownedId(ship.getSystem().getId()) && ship.getFluxTracker() != null
                && !ship.getFluxTracker().isOverloaded();
    }

    private static boolean ownedId(String id) {
        return SYSTEM_ID.equals(id) || PENELOPE_SYSTEM_ID.equals(id);
    }

    private static boolean retainDuringVent(ShipAPI ship) {
        if (!usableShip(ship) || !ship.getFluxTracker().isVenting()) return false;
        Object value = ship.getCustomData().get(VENT_CONTINUITY_KEY);
        if (!(value instanceof Continuity continuity) || continuity.system != ship.getSystem() || continuity.manualOff) return false;
        // Native ENGINE_MOD rejects even a zero-sized flux request while venting, then drops its
        // toggle to IDLE. Restore this same activation, without useSystem/fire or activation callbacks.
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) return false;
        ship.getSystem().forceState(ShipSystemAPI.SystemState.ACTIVE, 0f);
        float dt = engine.getElapsedInLastFrame(), local = ship.getMutableStats().getTimeMult().getModifiedValue();
        float cost = ship.getSystem().getFluxPerSecond();
        if (Float.isFinite(dt) && dt > 0f && Float.isFinite(local) && local > 0f && Float.isFinite(cost) && cost > 0f) {
            // increaseFlux is precisely the native API that rejects costs during vent. Keep this
            // soft maintenance cost by changing total flux only; never convert or erase hard flux.
            FluxTrackerAPI flux = ship.getFluxTracker();
            flux.setCurrFlux(Math.min(ship.getMaxFlux(), flux.getCurrFlux() + cost * dt * local));
        }
        return true;
    }

    @Override public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = stats.getEntity() instanceof ShipAPI entity ? entity : null;
        float level = Float.isFinite(effectLevel) ? Math.max(0f, Math.min(1f, effectLevel)) : 0f;
        if (level <= 0f && retainDuringVent(ship)) level = 1f;
        if (level <= 0f) { unapply(stats, id); return; }
        if (usableShip(ship) && (state == State.ACTIVE || state == State.IN)) {
            Object value = ship.getCustomData().get(VENT_CONTINUITY_KEY);
            if (!(value instanceof Continuity continuity) || continuity.system != ship.getSystem())
                ship.setCustomData(VENT_CONTINUITY_KEY, new Continuity(ship.getSystem()));
        }
        Profile profile = profileFor(ship);
        String modifier = modifierId(id);
        stats.getMaxSpeed().modifyFlat(modifier, profile.speed() * level);
        stats.getMaxTurnRate().modifyFlat(modifier, profile.turn() * level);
        stats.getAcceleration().modifyMult(modifier, 1f + (profile.acceleration() - 1f) * level);
        stats.getDeceleration().modifyMult(modifier, 1f + (profile.deceleration() - 1f) * level);
        stats.getTurnAcceleration().modifyMult(modifier, 1f + (profile.turnAcceleration() - 1f) * level);
    }

    @Override public void unapply(MutableShipStatsAPI stats, String id) {
        if (stats.getEntity() instanceof ShipAPI ship) ship.getCustomData().remove(VENT_CONTINUITY_KEY);
        String modifier = modifierId(id);
        stats.getMaxSpeed().unmodify(modifier);
        stats.getMaxTurnRate().unmodify(modifier);
        stats.getAcceleration().unmodify(modifier);
        stats.getDeceleration().unmodify(modifier);
        stats.getTurnAcceleration().unmodify(modifier);
    }

    @Override public StatusData getStatusData(int index, State state, float effectLevel) {
        float level = Float.isFinite(effectLevel) ? Math.max(0f, Math.min(1f, effectLevel)) : 0f;
        ShipAPI player = Global.getCombatEngine() == null ? null : Global.getCombatEngine().getPlayerShip();
        if (level <= 0f && usableShip(player) && player.getFluxTracker().isVenting()
                && player.getCustomData().get(VENT_CONTINUITY_KEY) instanceof Continuity continuity
                && continuity.system == player.getSystem() && !continuity.manualOff) level = 1f;
        if (level <= 0f) return null; // runScriptWhileIdle must not advertise active bonuses while actually OFF.
        Profile profile = profileFor(player);
        return switch (index) {
            case 0 -> new StatusData("最高航速 +" + Math.round(profile.speed() * level)
                    + " / 转向 +" + Math.round(profile.turn() * level), false);
            case 1 -> new StatusData("加速 +" + Math.round((profile.acceleration() - 1f) * 100f * level)
                    + "% / 减速 +" + Math.round((profile.deceleration() - 1f) * 100f * level)
                    + "% / 转向加速度 +" + Math.round((profile.turnAcceleration() - 1f) * 100f * level) + "%", false);
            case 2 -> new StatusData("维持消耗：60 软幅能/秒；排幅保持开启；再次激活关闭", false);
            default -> null;
        };
    }

    @Override public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
        return system != null && system.isOn() ? "再次激活关闭" : "开启飞行强化";
    }
}
