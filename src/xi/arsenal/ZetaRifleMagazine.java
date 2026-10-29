package xi.arsenal;

import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.*;

/** One physical rifle, four form/mode drivers. Only the original MS burst tracker regenerates ammunition. */
final class ZetaRifleMagazine implements AutoCloseable {
    private final WeaponAPI source;private final List<WeaponAPI> weapons;
    private final Map<WeaponAPI,Integer> last=new IdentityHashMap<>();private final Map<WeaponAPI,Float> regen=new IdentityHashMap<>();
    private final int capacity;private int reserve;private boolean reserved,closed;private long consumed,restored,confirmed,refunded,refills;
    ZetaRifleMagazine(WeaponAPI source,List<WeaponAPI> weapons){
        this.source=Objects.requireNonNull(source);this.weapons=List.copyOf(weapons);capacity=source.getMaxAmmo();
        if(capacity<1||this.weapons.size()!=4)throw new IllegalArgumentException("Zeta magazine requires four native drivers");
        Set<WeaponAPI> unique=Collections.newSetFromMap(new IdentityHashMap<>());reserve=capacity;
        for(WeaponAPI weapon:this.weapons){if(!unique.add(weapon)||weapon.getMaxAmmo()!=capacity)throw new IllegalStateException("Zeta form magazines differ");reserve=Math.min(reserve,weapon.getAmmo());if(weapon!=source){regen.put(weapon,weapon.getAmmoTracker().getAmmoPerSecond());weapon.getAmmoTracker().setAmmoPerSecond(0);}}
        if(!unique.contains(source))throw new IllegalStateException("Magazine source is not one of its native drivers");mirror();
    }
    void observe(){if(closed)return;int used=0;for(WeaponAPI weapon:weapons)used+=Math.max(0,last.get(weapon)-weapon.getAmmo());int gained=Math.max(0,source.getAmmo()-last.get(source));consumed+=used;restored+=gained;reserve=Math.max(0,Math.min(capacity,reserve-used+gained));mirror();}
    private void mirror(){for(WeaponAPI weapon:weapons){if(weapon.getAmmo()!=reserve)weapon.setAmmo(reserve);last.put(weapon,weapon.getAmmo());}}
    int reserve(){return reserve;}
    boolean reserveBeam(){observe();if(reserved||reserve<1||!source.getAmmoTracker().deductOneAmmo())return false;reserved=true;observe();return true;}
    void confirmBeam(){if(reserved){reserved=false;confirmed++;}}
    void cancel(){if(!reserved)return;reserved=false;int before=source.getAmmo();source.getAmmoTracker().addOneAmmo();observe();if(source.getAmmo()>before){refunded++;restored--;}}
    boolean pending(){return reserved;}
    /** External E-cap authority debits one item only after a positive result. No held/fire clock changes. */
    int refill(){observe();if(reserved||reserve>=capacity)return 0;int added=capacity-reserve;reserve=capacity;mirror();refills++;return added;}
    Map<String,Object> status(){return Map.of("capacity",capacity,"reserve",reserve,"beamReservation",reserved,"nativeConsumed",consumed,"nativeRegenerated",restored,"confirmedBeams",confirmed,"refunds",refunded,"instantRefills",refills,"regenerationOwner",source.getSlot().getId());}
    @Override public void close(){if(closed)return;cancel();closed=true;for(var entry:regen.entrySet())if(entry.getKey().getAmmoTracker().getAmmoPerSecond()==0)entry.getKey().getAmmoTracker().setAmmoPerSecond(entry.getValue());}
}
