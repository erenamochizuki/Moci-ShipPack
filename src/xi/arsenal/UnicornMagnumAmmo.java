package xi.arsenal;

import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.*;

/** Mirror one five-round reserve after actual native consumption;only SOURCE's native tracker regenerates. */
final class UnicornMagnumAmmo implements AutoCloseable {
    private final WeaponAPI pulse,beam;private final float oldBeamRegen;private final int capacity;
    private int reserve,lastPulse,lastBeam;private long nativeConsumed,nativeRestored,synchronizations,reservations,confirmations,refunds;private boolean closed,reservation;
    UnicornMagnumAmmo(WeaponAPI pulse,WeaponAPI beam){
        this.pulse=Objects.requireNonNull(pulse);this.beam=Objects.requireNonNull(beam);
        capacity=pulse.getMaxAmmo();if(capacity<1||capacity!=beam.getMaxAmmo())throw new IllegalStateException("Magnum mode magazines must share one native capacity");
        reserve=Math.min(pulse.getAmmo(),beam.getAmmo());oldBeamRegen=beam.getAmmoTracker().getAmmoPerSecond();beam.getAmmoTracker().setAmmoPerSecond(0);
        mirror();
    }
    void observe(){
        if(closed)return;int p=pulse.getAmmo(),b=beam.getAmmo();
        int consumed=Math.max(0,lastPulse-p)+Math.max(0,lastBeam-b),restored=Math.max(0,p-lastPulse);
        nativeConsumed+=consumed;nativeRestored+=restored;reserve=Math.max(0,Math.min(capacity,reserve-consumed+restored));mirror();
    }
    private void mirror(){if(pulse.getAmmo()!=reserve){pulse.setAmmo(reserve);synchronizations++;}if(beam.getAmmo()!=reserve){beam.setAmmo(reserve);synchronizations++;}lastPulse=pulse.getAmmo();lastBeam=beam.getAmmo();}
    int reserve(){return reserve;}
    boolean reserveBeam(){observe();if(reservation||reserve<=0||!pulse.getAmmoTracker().deductOneAmmo())return false;reservation=true;reservations++;observe();return true;}
    void confirmBeam(){if(reservation){reservation=false;confirmations++;}}
    void cancelReservation(){if(!reservation)return;reservation=false;int before=pulse.getAmmo();pulse.getAmmoTracker().addOneAmmo();observe();if(pulse.getAmmo()>before){refunds++;nativeRestored--;}}
    boolean reservationPending(){return reservation;}
    Map<String,Object> status(){Map<String,Object> r=new LinkedHashMap<>();r.put("sharedAmmo",reserve);r.put("capacity",capacity);r.put("nativeConsumed",nativeConsumed);r.put("nativeRestored",nativeRestored);r.put("synchronizations",synchronizations);r.put("sourceNativeRegen",pulse.getAmmoTracker().getAmmoPerSecond());r.put("hiddenNativeRegen",beam.getAmmoTracker().getAmmoPerSecond());r.put("beamReservations",reservations);r.put("beamConfirmations",confirmations);r.put("beamRefunds",refunds);r.put("beamReservationPending",reservation);r.put("beamCostRule","explicit-source-magazine-not-continuous-native-auto");return r;}
    @Override public void close(){if(closed)return;cancelReservation();closed=true;if(beam.getAmmoTracker().getAmmoPerSecond()==0)beam.getAmmoTracker().setAmmoPerSecond(oldBeamRegen);}
}
