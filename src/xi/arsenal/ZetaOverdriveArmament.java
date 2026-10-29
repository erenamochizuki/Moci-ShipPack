package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.loading.*;
import java.util.*;

/** Replace the existing group's loadout behavior in place, preserving installed weapons,
 * original callbacks, manual selection and native ammo. No duplicate list of extra attacks. */
public final class ZetaOverdriveArmament implements AutoCloseable {
    private final ShipAPI ship;private final Map<WeaponAPI,Lease> leases=new IdentityHashMap<>();private boolean enabled,closed;
    public ZetaOverdriveArmament(ShipAPI ship){this.ship=Objects.requireNonNull(ship);}
    public void advance(boolean active){if(closed)return;enabled=active;
        if(!active){for(Lease l:leases.values())l.restore();leases.clear();return;}
        for(WeaponAPI w:ship.getAllWeapons()){
            String name=switch(w.getId()){
                case ZetaSaberMotion.WEAPON->"巨型光束军刀";
                case ZetaRifleModes.BURST,ZetaRifleModes.SNIPER->"光束步枪：超载";
                case ZetaRifleModes.WRIST->"腕部双联装榴弹：高速装填";
                case ZetaTetherCombo.COMMAND->"钢索弹：超载牵引";
                default->null;};if(name==null)continue;
            Lease lease=leases.get(w);if(lease==null){w.ensureClonedSpec();lease=new Lease(w);leases.put(w,lease);}lease.apply(name);
        }}
    private static final class Lease {
        final WeaponAPI weapon;final WeaponSpecAPI spec;final String name;final float regen,delay,trackerRegen;String writtenName;float writtenRegen,writtenDelay,writtenTracker;
        Lease(WeaponAPI weapon){this.weapon=weapon;spec=weapon.getSpec();name=spec.getWeaponName();regen=writtenRegen=spec.getAmmoPerSecond();trackerRegen=writtenTracker=weapon.getAmmoTracker().getAmmoPerSecond();delay=writtenDelay=spec instanceof ProjectileWeaponSpecAPI p?p.getRefireDelay():0;writtenName=name;}
        void apply(String next){if(spec!=weapon.getSpec())throw new IllegalStateException("Zeta overload spec identity changed");
            if(Objects.equals(spec.getWeaponName(),writtenName)){spec.setWeaponName(next);writtenName=next;}
            if(ZetaRifleModes.WRIST.equals(weapon.getId())){if(spec.getAmmoPerSecond()==writtenRegen){writtenRegen=regen*8;spec.setAmmoPerSecond(writtenRegen);}if(weapon.getAmmoTracker().getAmmoPerSecond()==writtenTracker){writtenTracker=trackerRegen*8;weapon.getAmmoTracker().setAmmoPerSecond(writtenTracker);}if(spec instanceof ProjectileWeaponSpecAPI p&&p.getRefireDelay()==writtenDelay){writtenDelay=delay*.18f;p.setRefireDelay(writtenDelay);}if(weapon.getCooldownRemaining()>.25f)weapon.setRemainingCooldownTo(.25f);}
        }
        void restore(){if(Objects.equals(spec.getWeaponName(),writtenName))spec.setWeaponName(name);if(spec.getAmmoPerSecond()==writtenRegen)spec.setAmmoPerSecond(regen);if(weapon.getAmmoTracker().getAmmoPerSecond()==writtenTracker)weapon.getAmmoTracker().setAmmoPerSecond(trackerRegen);if(spec instanceof ProjectileWeaponSpecAPI p&&p.getRefireDelay()==writtenDelay)p.setRefireDelay(delay);}
    }
    public Map<String,Object> status(){return Map.of("active",enabled,"closed",closed,"clonedLoadoutEntries",leases.size(),"groupPolicy","replace managed group roles, park ordinary sniper while overloaded; restore original groups and names on exit","grenadeReloadMult",enabled?8:1);}
    @Override public void close(){if(closed)return;advance(false);closed=true;}
}
