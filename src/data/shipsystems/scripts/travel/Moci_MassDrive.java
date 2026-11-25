package data.shipsystems.scripts.travel;

import data.scripts.util.Moci_TextLoader;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

public class Moci_MassDrive extends BaseShipSystemScript {
    static final String EVENT = "Moci_MassDrive_event";
    private static final String SEQUENCE = "Moci_MassDrive_sequence";

    interface Pass extends CombatLayeredRenderingPlugin {
        boolean isLeaving();
        void cancel();
        void stop();
    }

    private final String eventKey, sequenceKey;
    public Moci_MassDrive() { this(EVENT, SEQUENCE); }
    Moci_MassDrive(String eventKey, String sequenceKey) { this.eventKey=eventKey; this.sequenceKey=sequenceKey; }

    Pass createPass(CombatEngineAPI engine, ShipAPI ship, int index) {
        return new Moci_MassDrivePass(engine, ship, index, eventKey);
    }

    public static boolean hidesArrivalHull(ShipAPI ship) {
        ShipAPI parent = ship.getParentStation();
        ShipAPI owner = parent == null ? ship : parent;
        return owner.getCustomData().get(EVENT) instanceof Moci_MassDrivePass pass && pass.hidesNativeHull();
    }

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        if (state == State.OUT) {
            stats.getMaxSpeed().unmodify(id);
            return;
        }
        if (state != State.IN && state != State.ACTIVE) return;
        stats.getMaxSpeed().modifyFlat(id, 600f * effectLevel);
        stats.getAcceleration().modifyFlat(id, 600f * effectLevel);
        CombatEngineAPI engine = Global.getCombatEngine();
        if (!(stats.getEntity() instanceof ShipAPI ship) || engine == null || ship.isHulk()
                || ship.getParentStation() != null) return;
        if (ship.getCustomData().get(eventKey) instanceof Pass current) {
            if (current.isLeaving() || !ship.isRetreating()) return;
            current.cancel(); // Retreat order during the same continuous drive activation.
        }
        int index = ship.getCustomData().get(sequenceKey) instanceof Integer last ? last + 1 : 0;
        Pass pass = createPass(engine, ship, index);
        engine.addLayeredRenderingPlugin(pass);
        ship.setCustomData(sequenceKey, index);
        ship.setCustomData(eventKey, pass);
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        stats.getMaxSpeed().unmodify(id);
        stats.getAcceleration().unmodify(id);
        if (stats.getEntity() instanceof ShipAPI ship && ship.getCustomData().get(eventKey) instanceof Pass pass) {
            pass.stop();
            if (pass.isLeaving() || pass.isExpired()) ship.removeCustomData(eventKey);
        }
    }

    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        return index == 0 ? new StatusData(Moci_TextLoader.getText("code_Moci_MassDrive", "t001", "提高引擎出力"), false) : null;
    }
}
