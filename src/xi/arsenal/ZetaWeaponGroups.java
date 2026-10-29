package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.combat.systems.WeaponGroup;
import com.fs.starfarer.combat.systems.o00O;
import java.awt.Color;
import java.util.*;

/** Battle-thread owner of the Zeta's visible native group list. Never call from a weapon callback.
 * Parked groups retain their real members, installed AI, fire mode and autofire state. The original
 * variable-frame utility's cached group-index mutation must not run alongside this owner. */
public final class ZetaWeaponGroups implements AutoCloseable {
    public static final String KEY="xi.arsenal.zeta.groups";
    private static final Set<String> MS_SLOTS=Set.of("HEAD_SOURCE","ARM_L_SOURCE","ARM_L_FOLLOW",
            "ARM_R_SOURCE",ZetaRifleModes.SNIPER_SLOT,"ZETA_WRIST_L","ZETA_WRIST_R",ZetaTetherCombo.COMMAND_SLOT,ZetaTetherCombo.LAUNCHER_SLOT);
    private static final int MAX_WEAPONS=256,MAX_GROUPS=64;
    private static final Color TRANSPARENT=new Color(0,0,0,0);
    interface Access {
        List<WeaponGroup> groups();
        WeaponGroup create();
        void addUnassigned(WeaponGroup group,WeaponAPI weapon);
        void select(WeaponGroup group);
        void refresh();
    }
    private final ShipAPI ship;
    private final Access access;
    private final List<WeaponGroup> originals;
    private final Map<WeaponGroup,Snapshot> snapshots=new IdentityHashMap<>();
    private final LinkedHashMap<String,WeaponGroup> buckets=new LinkedHashMap<>();
    private final Map<WeaponAPI,String> kinds=new IdentityHashMap<>();
    private final Map<WeaponGroup,String> owned=new IdentityHashMap<>();
    private final Set<WeaponGroup> emptied=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<WeaponGroup> created=Collections.newSetFromMap(new IdentityHashMap<>());
    private final WeaponGroupAPI originalSelection;
    private WeaponGroup rememberedMs,rememberedWr;
    private boolean waveRider,ramEligible,closed;
    private long uiUpdates,foreignMixed;

    public ZetaWeaponGroups(ShipAPI ship){this(ship,nativeAccess(ship));}
    ZetaWeaponGroups(ShipAPI ship,Access access){
        this.ship=Objects.requireNonNull(ship);this.access=Objects.requireNonNull(access);
        if(ship.getCustomData().containsKey(KEY+".owner"))throw new IllegalStateException("Zeta native groups already owned");
        if(ship.getAllWeapons().size()>MAX_WEAPONS||access.groups().size()>MAX_GROUPS)throw new IllegalStateException("Zeta group inventory exceeds bounded initialization budget");
        originals=List.copyOf(access.groups());originalSelection=ship.getSelectedGroupAPI();
        for(WeaponGroup group:originals)snapshots.put(group,Snapshot.capture(group));
        for(WeaponAPI weapon:ship.getAllWeapons()){String kind=kind(weapon);if(kind!=null)kinds.put(weapon,kind);}
        normalize();ship.setCustomData(KEY+".owner",this);advance(false,false);
    }
    public static ZetaWeaponGroups instance(ShipAPI ship){Object value=ship==null?null:ship.getCustomData().get(KEY+".owner");return value instanceof ZetaWeaponGroups groups&&!groups.closed?groups:null;}
    /** Called only by the outer actor/constructors, never by a firing callback. */
    public static ZetaWeaponGroups ensure(ShipAPI ship){ZetaWeaponGroups own=instance(ship);return own!=null?own:new ZetaWeaponGroups(ship);}
    private static Access nativeAccess(ShipAPI ship){
        if(!(ship instanceof Ship nativeShip))throw new IllegalArgumentException("Native Ship required for native Zeta group ownership");
        return new Access(){public List<WeaponGroup> groups(){return nativeShip.getGroups();}
            public WeaponGroup create(){WeaponGroup group=new WeaponGroup();group.setShip(nativeShip);group.setType(WeaponGroupType.LINKED);return group;}
            public void addUnassigned(WeaponGroup group,WeaponAPI weapon){group.addWeaponAPI(weapon);}
            public void select(WeaponGroup group){int index=identityIndex(groups(),group);if(index>=0)ship.giveCommand(ShipCommand.SELECT_GROUP,null,index);}
            public void refresh(){nativeShip.setNeedsWeaponGroupUIUpdate(true);}};
    }
    private static String kind(WeaponAPI weapon){
        if(weapon==null||weapon.getSlot()==null)return null;String slot=weapon.getSlot().getId();if(slot==null)return null;
        if(ZetaContent.SLOT.equals(slot)&&ZetaContent.RAM.equals(weapon.getId()))return "RAM";
        if(ZetaDeflectionWeapon.SLOT.equals(slot))return "DEFLECTION";
        if(ZetaTetherCombo.COMMAND_SLOT.equals(slot)||ZetaTetherCombo.LAUNCHER_SLOT.equals(slot))return "MS:TETHER";
        if(slot!=null&&slot.startsWith("WR_"))return weapon.isDecorative()?null:"WR:"+slot;
        if(!MS_SLOTS.contains(slot)||weapon.isDecorative())return null;
        if("ZETA_WRIST_L".equals(slot)||"ZETA_WRIST_R".equals(slot))return "MS:WRIST";
        if("ARM_L_FOLLOW".equals(slot))return "MS:ARM_L_SOURCE";
        return "MS:"+slot;
    }
    private void normalize(){
        // Retain an original pure group for its first category. Mixed foreign groups are never
        // hidden or repurposed; only the exact managed members are extracted synchronously.
        for(WeaponGroup group:originals){List<WeaponAPI> members=group.getWeaponsCopy();
            boolean foreign=members.stream().anyMatch(w->!kinds.containsKey(w));
            String anchor=foreign?null:members.stream().map(kinds::get).filter(Objects::nonNull).findFirst().orElse(null);
            if(anchor!=null&&!buckets.containsKey(anchor)){buckets.put(anchor,group);owned.put(group,anchor);}
            else anchor=null;
            for(int i=members.size()-1;i>=0;i--){WeaponAPI weapon=members.get(i);String target=kinds.get(weapon);if(target==null||target.equals(anchor))continue;
                AutofireAIPlugin ai=group.getAutofirePlugin(weapon);group.removeWeapon(i);
                WeaponGroup destination=bucket(target);
                if(created.contains(destination)&&destination.getWeaponsCopy().isEmpty()){
                    destination.setType(group.getType());
                    if(!manual(target)&&group.isAutofiring()&&!destination.isAutofiring())destination.toggleOn();
                }
                appendInstalled(destination,weapon,ai);
            }
            if(!members.isEmpty()&&group.getWeaponsCopy().isEmpty())emptied.add(group);
        }
        List<WeaponAPI> ordered=new ArrayList<>(kinds.keySet());ordered.sort(Comparator.comparing(w->w.getSlot().getId()));
        for(WeaponAPI weapon:ordered)if(!containsInstalled(weapon)){access.addUnassigned(bucket(kinds.get(weapon)),weapon);}
        // RAM is a manual command trigger. It is never selected or enabled as an automatic weapon.
        for(var entry:buckets.entrySet())if(manual(entry.getKey())&&entry.getValue().isAutofiring())entry.getValue().toggleOff();
    }
    private WeaponGroup bucket(String kind){WeaponGroup group=buckets.get(kind);if(group==null){group=access.create();buckets.put(kind,group);owned.put(group,kind);created.add(group);}return group;}
    private static void appendInstalled(WeaponGroup group,WeaponAPI weapon,AutofireAIPlugin ai){
        if(!(weapon instanceof o00O nativeWeapon))throw new IllegalStateException("Installed Zeta weapon lacks native weapon interface");
        group.getWeapons().add(nativeWeapon);group.getAIPlugins().add(ai);
    }
    private boolean containsInstalled(WeaponAPI wanted){for(WeaponGroup group:allGroups())for(WeaponAPI weapon:group.getWeaponsCopy())if(weapon==wanted)return true;return false;}
    private List<WeaponGroup> allGroups(){List<WeaponGroup> result=new ArrayList<>(originals);for(WeaponGroup group:buckets.values())if(identityIndex(result,group)<0)result.add(group);return result;}
    private boolean pure(WeaponGroup group,String kind){List<WeaponAPI> members=group.getWeaponsCopy();return !members.isEmpty()&&members.stream().allMatch(w->kind.equals(kinds.get(w)));}
    private boolean wanted(String kind){if("DEFLECTION".equals(kind))return ZetaOverdriveLifecycle.immune(ship);
        if(ZetaOverdriveLifecycle.immune(ship)&&(kind.equals("MS:"+ZetaRifleModes.SNIPER_SLOT)||kind.equals("WR:"+ZetaRifleModes.WR_SNIPER_SLOT)))return false;
        return "RAM".equals(kind)?ramEligible:waveRider?kind.startsWith("WR:"):kind.startsWith("MS:");}
    private static boolean manual(String kind){return "RAM".equals(kind)||"MS:TETHER".equals(kind)||"DEFLECTION".equals(kind);}

    /** The outer actor calls once per frame, after computing complete-WR and F eligibility.
     * No weapon is removed/recreated here; only real group references enter/leave the visible list. */
    public void advance(boolean completeWaveRider,boolean ramEligible){
        if(closed)return;WeaponGroupAPI selected=ship.getSelectedGroupAPI();boolean modeChanged=waveRider!=completeWaveRider;
        if(modeChanged&&selected instanceof WeaponGroup selectedGroup){String previousKind=owned.get(selectedGroup);
            if(previousKind!=null&&previousKind.startsWith("MS:"))rememberedMs=selectedGroup;
            if(previousKind!=null&&previousKind.startsWith("WR:"))rememberedWr=selectedGroup;}
        waveRider=completeWaveRider;this.ramEligible=completeWaveRider&&ramEligible;
        List<WeaponGroup> next=new ArrayList<>();for(WeaponGroup group:access.groups()){
            String category=owned.get(group);
            if(category!=null&&pure(group,category))continue;
            if(emptied.contains(group)&&group.getWeaponsCopy().isEmpty())continue;
            if(category!=null&&!group.getWeaponsCopy().isEmpty())foreignMixed++;
            next.add(group);
        }
        for(var entry:buckets.entrySet())if(wanted(entry.getKey())&&pure(entry.getValue(),entry.getKey())&&identityIndex(next,entry.getValue())<0)next.add(entry.getValue());
        if(!sameIdentities(access.groups(),next)){
            if(selected!=null&&identityIndex(next,selected)<0)ship.resetSelectedGroup();
            access.groups().clear();access.groups().addAll(next);access.refresh();uiUpdates++;
            if(selected instanceof WeaponGroup old&&ZetaOverdriveLifecycle.immune(ship)&&Objects.equals(owned.get(old),waveRider?"WR:"+ZetaRifleModes.WR_SNIPER_SLOT:"MS:"+ZetaRifleModes.SNIPER_SLOT)){WeaponGroup replacement=buckets.get(waveRider?"WR:"+ZetaRifleModes.WR_SOURCE_SLOT:"MS:"+ZetaRifleModes.SOURCE_SLOT);if(replacement!=null&&identityIndex(next,replacement)>=0)access.select(replacement);}
            if(modeChanged&&ship.getSelectedGroupAPI()==null){WeaponGroup remembered=waveRider?rememberedWr:rememberedMs;if(remembered!=null&&identityIndex(next,remembered)>=0)access.select(remembered);}
        }
        for(var entry:buckets.entrySet())if(manual(entry.getKey())&&pure(entry.getValue(),entry.getKey())&&entry.getValue().isAutofiring())entry.getValue().toggleOff();
        publish();
    }
    /** Original variable-frame visibility/NoFire behavior without its stale cached group indices.
     * Safe from a weapon callback: this function never changes groups or advances weapons. */
    public static void applyVisibilityAndFireControl(ShipAPI ship,boolean wrMode,boolean suppressFire){
        if(ship==null)return;for(WeaponAPI weapon:ship.getAllWeapons()){
            if(weapon.getSlot()==null)continue;String slot=weapon.getSlot().getId();boolean wr=slot!=null&&slot.startsWith("WR_");
            boolean ms=slot!=null&&(MS_SLOTS.contains(slot)||slot.endsWith("_FOLLOW"));if(!wr&&!ms)continue;
            if(wr){hide(weapon.getSprite());hide(weapon.getBarrelSpriteAPI());hide(weapon.getUnderSpriteAPI());hide(weapon.getGlowSpriteAPI());
                if(weapon.getMissileRenderData()!=null)for(MissileRenderDataAPI render:weapon.getMissileRenderData())if(render!=null&&render.getSprite()!=null){render.getSprite().setWidth(0);render.getSprite().setHeight(0);}}
            if(suppressFire||(wrMode?!wr:!ms))weapon.setForceNoFireOneFrame(true);
        }
    }
    private static void hide(SpriteAPI sprite){if(sprite!=null){sprite.setColor(TRANSPARENT);sprite.setAlphaMult(0);}}
    public Map<String,Object> status(){Map<String,Object> result=new LinkedHashMap<>();result.put("closed",closed);result.put("mode",waveRider?"WR":"MS");result.put("ramEligible",ramEligible);result.put("uiUpdates",uiUpdates);result.put("foreignMixed",foreignMixed);
        List<Map<String,Object>> groups=new ArrayList<>();for(var entry:buckets.entrySet()){WeaponGroup group=entry.getValue();int index=identityIndex(access.groups(),group);Map<String,Object> row=new LinkedHashMap<>();row.put("kind",entry.getKey());row.put("visible",index>=0);row.put("index",index);if(index>=0)row.put("number",index+1);row.put("identity",Integer.toHexString(System.identityHashCode(group)));row.put("autofire",group.isAutofiring());row.put("selected",ship.getSelectedGroupAPI()==group);row.put("members",group.getWeaponsCopy().stream().map(w->w.getSlot()==null?"":w.getSlot().getId()).toList());groups.add(Collections.unmodifiableMap(row));}
        result.put("groups",List.copyOf(groups));return Collections.unmodifiableMap(result);}
    private void publish(){ship.setCustomData(KEY,status());}
    @Override public void close(){if(closed)return;closed=true;
        // Restore original membership only where nobody introduced foreign members into our group.
        Set<WeaponAPI> releasable=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var entry:owned.entrySet())if(pure(entry.getKey(),entry.getValue()))releasable.addAll(entry.getKey().getWeaponsCopy());
        for(WeaponGroup group:allGroups()){List<WeaponAPI> members=group.getWeaponsCopy();for(int i=members.size()-1;i>=0;i--)if(releasable.contains(members.get(i)))group.removeWeapon(i);}
        for(WeaponGroup group:originals){Snapshot snapshot=snapshots.get(group);
            List<WeaponAPI> remaining=group.getWeaponsCopy();List<WeaponAPI> originalForeign=snapshot.weapons.stream().filter(w->!kinds.containsKey(w)).toList();
            if(sameIdentities(remaining,originalForeign)){
                // Restore the original interleaving, preserving any current foreign AI wrapper.
                Map<WeaponAPI,AutofireAIPlugin> foreignAi=new IdentityHashMap<>();for(WeaponAPI weapon:remaining)foreignAi.put(weapon,group.getAutofirePlugin(weapon));
                group.getWeapons().clear();group.getAIPlugins().clear();
                for(int i=0;i<snapshot.weapons.size();i++){WeaponAPI weapon=snapshot.weapons.get(i);if(releasable.contains(weapon)||foreignAi.containsKey(weapon))appendInstalled(group,weapon,foreignAi.containsKey(weapon)?foreignAi.get(weapon):snapshot.ais.get(i));}
            }else for(int i=0;i<snapshot.weapons.size();i++){WeaponAPI weapon=snapshot.weapons.get(i);if(releasable.contains(weapon))appendInstalled(group,weapon,snapshot.ais.get(i));}
            if(manual(owned.get(group))&&snapshot.autofire&&!group.isAutofiring()&&group.getWeaponsCopy().stream().allMatch(kinds::containsKey))group.toggleOn();}
        List<WeaponGroup> restored=new ArrayList<>();for(WeaponGroup group:originals)if(!group.getWeaponsCopy().isEmpty()||!emptied.contains(group))restored.add(group);
        for(WeaponGroup group:access.groups())if(identityIndex(restored,group)<0&&(!created.contains(group)||!group.getWeaponsCopy().isEmpty()))restored.add(group);
        if(ship.getSelectedGroupAPI()!=null&&identityIndex(restored,ship.getSelectedGroupAPI())<0)ship.resetSelectedGroup();
        access.groups().clear();access.groups().addAll(restored);access.refresh();uiUpdates++;
        if(ship.getSelectedGroupAPI()==null&&originalSelection instanceof WeaponGroup original&&identityIndex(restored,original)>=0)access.select(original);
        if(ship.getCustomData().get(KEY+".owner")==this)ship.removeCustomData(KEY+".owner");publish();
    }
    private record Snapshot(List<WeaponAPI> weapons,List<AutofireAIPlugin> ais,boolean autofire){static Snapshot capture(WeaponGroup group){List<WeaponAPI> weapons=group.getWeaponsCopy();List<AutofireAIPlugin> ais=new ArrayList<>();for(WeaponAPI weapon:weapons)ais.add(group.getAutofirePlugin(weapon));return new Snapshot(List.copyOf(weapons),Collections.unmodifiableList(ais),group.isAutofiring());}}
    private static int identityIndex(List<?> values,Object wanted){for(int i=0;i<values.size();i++)if(values.get(i)==wanted)return i;return -1;}
    private static boolean sameIdentities(List<?> a,List<?> b){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(a.get(i)!=b.get(i))return false;return true;}
}
