package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.combat.systems.WeaponGroup;
import com.fs.starfarer.combat.systems.o00O;
import java.util.*;

/** Outer combat-owner only:two selectable native groups,without moving unrelated weapons or repicking their AI. */
final class UnicornMagnumGroups implements AutoCloseable {
    interface Access {List<WeaponGroupAPI> groups();WeaponGroupAPI create();void append(WeaponGroupAPI group,WeaponAPI weapon,AutofireAIPlugin ai);void refresh();}
    private record Snapshot(WeaponGroupAPI group,List<WeaponAPI> weapons,List<AutofireAIPlugin> ai,boolean auto,WeaponGroupType type){}
    private final ShipAPI ship;private final Access access;private final WeaponAPI pulse,beam;private final List<Snapshot> originals=new ArrayList<>();
    private final Set<WeaponGroupAPI> created=Collections.newSetFromMap(new IdentityHashMap<>());private final Map<WeaponGroupAPI,List<WeaponAPI>> written=new IdentityHashMap<>();
    final WeaponGroupAPI pulseGroup,chargeGroup;private boolean closed;
    UnicornMagnumGroups(ShipAPI ship,WeaponAPI pulse,WeaponAPI beam){this(ship,pulse,beam,nativeAccess(ship));}
    UnicornMagnumGroups(ShipAPI ship,WeaponAPI pulse,WeaponAPI beam,Access access){
        this.ship=ship;this.pulse=pulse;this.beam=beam;this.access=access;
        for(WeaponGroupAPI group:List.copyOf(access.groups())){List<WeaponAPI> weapons=group.getWeaponsCopy();List<AutofireAIPlugin> ai=new ArrayList<>();for(WeaponAPI w:weapons)ai.add(group.getAutofirePlugin(w));originals.add(new Snapshot(group,List.copyOf(weapons),Collections.unmodifiableList(ai),group.isAutofiring(),group.getType()));}
        WeaponGroupAPI selected=ship.getSelectedGroupAPI();boolean selectedManaged=selected!=null&&selected.getWeaponsCopy().stream().anyMatch(w->w==pulse||w==beam);
        pulseGroup=separate(pulse);chargeGroup=separate(beam);if(chargeGroup.isAutofiring())chargeGroup.toggleOff();
        for(WeaponGroupAPI group:access.groups())written.put(group,List.copyOf(group.getWeaponsCopy()));access.refresh();if(selectedManaged||selected==null)select(false);
    }
    private WeaponGroupAPI separate(WeaponAPI wanted){
        WeaponGroupAPI prior=null;AutofireAIPlugin ai=null;
        for(WeaponGroupAPI group:access.groups()){List<WeaponAPI> members=group.getWeaponsCopy();int i=identity(members,wanted);if(i<0)continue;if(members.size()==1)return group;prior=group;ai=group.getAutofirePlugin(wanted);group.removeWeapon(i);break;}
        WeaponGroupAPI target=access.create();target.setType(WeaponGroupType.LINKED);access.append(target,wanted,ai);access.groups().add(target);created.add(target);return target;
    }
    boolean select(boolean charge){WeaponGroupAPI group=charge?chargeGroup:pulseGroup;int index=identity(access.groups(),group);if(index<0)return false;ship.giveCommand(ShipCommand.SELECT_GROUP,null,index);return true;}
    private static Access nativeAccess(ShipAPI ship){if(!(ship instanceof Ship nativeShip))throw new IllegalArgumentException("Native RX0 ship required to create Magnum groups");return new Access(){
        @SuppressWarnings({"unchecked","rawtypes"})public List<WeaponGroupAPI> groups(){return(List)nativeShip.getGroups();}
        public WeaponGroupAPI create(){WeaponGroup group=new WeaponGroup();group.setShip(nativeShip);return group;}
        public void append(WeaponGroupAPI group,WeaponAPI weapon,AutofireAIPlugin ai){if(ai!=null){if(!(weapon instanceof o00O installed))throw new IllegalStateException("Native weapon interface missing");WeaponGroup g=(WeaponGroup)group;g.getWeapons().add(installed);g.getAIPlugins().add(ai);}else group.addWeaponAPI(weapon);}
        public void refresh(){nativeShip.setNeedsWeaponGroupUIUpdate(true);}
    };}
    private static int identity(List<?> list,Object wanted){for(int i=0;i<list.size();i++)if(list.get(i)==wanted)return i;return -1;}
    private static boolean same(List<?> a,List<?> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(a.get(i)!=b.get(i))return false;return true;}
    Map<String,Object> status(){return Map.of("pulseGroupIndex",identity(access.groups(),pulseGroup),"chargeGroupIndex",identity(access.groups(),chargeGroup),"groupCount",access.groups().size());}
    @Override public void close(){if(closed)return;closed=true;
        for(Snapshot old:originals){List<WeaponAPI> expected=written.get(old.group);if(expected==null||!same(old.group.getWeaponsCopy(),expected))continue;
            for(int i=old.group.getWeaponsCopy().size()-1;i>=0;i--)old.group.removeWeapon(i);for(int i=0;i<old.weapons.size();i++)access.append(old.group,old.weapons.get(i),old.ai.get(i));old.group.setType(old.type);if(old.group.isAutofiring()!=old.auto){if(old.auto)old.group.toggleOn();else old.group.toggleOff();}}
        for(WeaponGroupAPI group:created)if(group.getWeaponsCopy().stream().allMatch(w->w==pulse||w==beam))access.groups().remove(group);access.refresh();
    }
}
