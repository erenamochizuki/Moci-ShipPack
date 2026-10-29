package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.combat.entities.Ship;
import java.util.*;

/** Manual trigger guard; ZetaWeaponGroups parks the complete real native group. */
final class ZetaRamWeaponGroup implements AutoCloseable {
    private final ShipAPI ship;
    private final WeaponAPI weapon;
    private boolean eligible,closed;
    private String reason="not-unlocked";
    ZetaRamWeaponGroup(ShipAPI ship){this.ship=Objects.requireNonNull(ship);WeaponAPI found=null;
        for(WeaponAPI candidate:ship.getAllWeapons())if(ZetaContent.RAM.equals(candidate.getId())&&candidate.getSlot()!=null&&ZetaContent.SLOT.equals(candidate.getSlot().getId())){
            if(found!=null)throw new IllegalStateException("Duplicate Zeta ram trigger slot");found=candidate;}
        weapon=found;if(weapon!=null){if(ship instanceof Ship)ZetaWeaponGroups.ensure(ship);weapon.setForceNoFireOneFrame(true);}
    }
    void advance(boolean eligible,boolean busy){this.eligible=eligible;
        if(closed||weapon==null){reason=weapon==null?"missing-native-ram-weapon":"closed";return;}
        boolean installed=ship.getAllWeapons().stream().anyMatch(actual->actual==weapon);
        WeaponGroupAPI group=installed?ship.getWeaponGroupFor(weapon):null;
        boolean visible=group!=null&&ship.getWeaponGroupsCopy().stream().anyMatch(actual->actual==group);
        reason=!installed?"ram-weapon-was-removed":!(ship instanceof Ship)?"unsupported-native-group-owner":!eligible?"not-unlocked":visible?"overloaded-waverider-unlocked":"waiting-for-native-form-group-owner";
        if(!eligible||busy||!visible){weapon.setForceNoFireOneFrame(true);weapon.stopFiring();}
    }
    WeaponAPI weapon(){return weapon;}
    Map<String,Object> status(){Map<String,Object> row=new LinkedHashMap<>();boolean installed=weapon!=null&&ship.getAllWeapons().stream().anyMatch(actual->actual==weapon);
        WeaponGroupAPI group=installed?ship.getWeaponGroupFor(weapon):null;List<WeaponGroupAPI> groups=ship.getWeaponGroupsCopy();int index=-1;for(int i=0;i<groups.size();i++)if(groups.get(i)==group&&group!=null){index=i;break;}
        row.put("visible",index>=0);row.put("groupIndex",index);if(index>=0)row.put("groupNumber",index+1);row.put("eligible",eligible);row.put("reason",reason);row.put("nativeWeaponPresent",installed);row.put("selected",group!=null&&ship.getSelectedGroupAPI()==group);row.put("autofire",group!=null&&group.isAutofiring());return Collections.unmodifiableMap(row);}
    @Override public void close(){if(closed)return;closed=true;if(weapon!=null){weapon.setForceNoFireOneFrame(true);weapon.stopFiring();}reason="closed";}
}
