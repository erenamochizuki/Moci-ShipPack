package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import java.util.*;

/** Exact Zeta baseline changes. Foreign flat/percent/mult terms keep native composition and ownership. */
public final class ZetaMobility implements AutoCloseable {
    public static final String HULL="Moci_MSZ_006", MODIFIER="xi.arsenal.zeta.mobility",
            ORIGINAL_TRANSFORM="MOCI_MSZ_006_TRANSFORM_STATS", ORIGINAL_ZERO_SPEED="Moci_MobileSuitsIDcard";
    public static final float MS_ACCEL=220,MS_BRAKE=400,MS_TURN=100,MS_TURN_ACCEL=260,
            WR_SPEED=330,WR_ACCEL=650,WR_BRAKE=500,WR_TURN=60,WR_TURN_ACCEL=160,NATIVE_ZERO_FLUX=50;
    private final ShipAPI ship;private final MutableShipStatsAPI stats;private boolean closed,wr;
    private float compensatedZeroFlat;
    public ZetaMobility(ShipAPI ship){if(!supports(ship))throw new IllegalArgumentException("Zeta mobility requires complete MSZ-006 main body");this.ship=ship;stats=Objects.requireNonNull(ship.getMutableStats());}
    public static boolean supports(ShipAPI ship){
        if(ship==null||ship.isDrone()||ship.isFighter()||ship.isStationModule()||ship.getParentStation()!=null||ship.getHullSpec()==null)return false;
        Set<ShipHullSpecAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<ShipHullSpecAPI> queue=new ArrayDeque<>();queue.add(ship.getHullSpec());
        for(int n=0;n<16&&!queue.isEmpty();n++){ShipHullSpecAPI hull=queue.removeFirst();if(!seen.add(hull))continue;
            if(HULL.equals(hull.getHullId())||HULL.equals(hull.getBaseHullId())||HULL.equals(hull.getDParentHullId()))return true;
            if(hull.getBaseHull()!=null)queue.add(hull.getBaseHull());if(hull.getDParentHull()!=null)queue.add(hull.getDParentHull());}
        return false;
    }
    /** Called AFTER the one real transform callback has written its known35/45% term. */
    public void apply(boolean waveRider){if(closed)return;if(!ship.isAlive()||ship.isHulk()||ship.wasRemoved()){reset();return;}wr=waveRider;
        baseline(stats.getAcceleration(),wr?WR_ACCEL:MS_ACCEL);baseline(stats.getDeceleration(),wr?WR_BRAKE:MS_BRAKE);
        baseline(stats.getMaxTurnRate(),wr?WR_TURN:MS_TURN);baseline(stats.getTurnAcceleration(),wr?WR_TURN_ACCEL:MS_TURN_ACCEL);
        if(wr){
            baseline(stats.getMaxSpeed(),WR_SPEED);
            // Native zeroFluxEngineBoost=50 is verified in this engine's settings.json.
            // Cancel only this base50, preserving external boost percentages/flats/multipliers.
            stats.getZeroFluxSpeedBoost().modifyFlat(MODIFIER,-NATIVE_ZERO_FLUX);
            // Original Moci ID-card independently adds another30 speed for a zero-flux destroyer.
            // Read that exact currently present known term; do not cancel another owner's speed.
            MutableStat.StatMod original=stats.getMaxSpeed().getFlatStatMod(ORIGINAL_ZERO_SPEED);
            compensatedZeroFlat=original!=null&&Float.isFinite(original.getValue())?original.getValue():0;
            stats.getMaxSpeed().modifyFlat(MODIFIER,WR_SPEED-stats.getMaxSpeed().getBaseValue()-compensatedZeroFlat);
        }else{stats.getMaxSpeed().unmodify(MODIFIER);stats.getZeroFluxSpeedBoost().unmodify(MODIFIER);compensatedZeroFlat=0;}
    }
    private static void baseline(MutableStat stat,float target){
        float base=stat.getBaseValue();if(!Float.isFinite(base))throw new IllegalStateException("Non-finite native Zeta movement base");
        MutableStat.StatMod original=stat.getPercentStatMod(ORIGINAL_TRANSFORM);float percent=original==null?0:original.getValue();
        if(!Float.isFinite(percent))throw new IllegalStateException("Non-finite original Zeta transform modifier");
        // Native MutableStat applies percentage to its original base. The pair below
        // changes only our baseline and the known transform percentage; foreign terms stay intact.
        stat.modifyPercent(MODIFIER,-percent);stat.modifyFlat(MODIFIER,target-base);
    }
    public Map<String,Object> status(){return Map.of("closed",closed,"waveRiderStats",wr,"nativeZeroFluxBaseCancelled",wr?NATIVE_ZERO_FLUX:0,
            "knownMociZeroFluxFlatCancelled",compensatedZeroFlat,"waveRiderBaseline",WR_SPEED,"foreignModifiersPreserved",true);}
    private void reset(){for(MutableStat stat:new MutableStat[]{stats.getMaxSpeed(),stats.getAcceleration(),stats.getDeceleration(),stats.getMaxTurnRate(),stats.getTurnAcceleration(),stats.getZeroFluxSpeedBoost()})stat.unmodify(MODIFIER);wr=false;compensatedZeroFlat=0;}
    @Override public void close(){if(closed)return;reset();closed=true;}
}
