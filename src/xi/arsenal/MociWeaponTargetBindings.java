package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import data.scripts.weapons.Moci_MechProjDecoWeaponEffect;
import data.scripts.weapons.Moci_MechProjDecoWeaponEffect_noHide;
import data.scripts.weapons.Moci_MechProjDecoWeaponEffect_shield_arm_RX0;
import java.util.*;

/** Read-only structural discovery for exact original Moci callbacks; no original callback or pose advancement. */
public final class MociWeaponTargetBindings implements AutoCloseable {
    public static final String KEY="host.combat.weaponBindings", STATUS_KEY="xi.arsenal.mociWeaponTargetBindings";
    public static final int MAX_SHIPS=256, MAX_SCAN_SHIPS=8192, MAX_WEAPONS=512;
    private final CombatEngineAPI engine;
    private final Object previousBridge,previousStatus;
    private final Map<String,Object> bridge=new LinkedHashMap<>(),facts=new LinkedHashMap<>();
    private final Map<String,Object> bridgeView=Collections.unmodifiableMap(bridge),factsView=Collections.unmodifiableMap(facts);
    private boolean closed,ownsBridge;
    private float scanClock;
    private long scans,budgetSkips,conflicts,unsupportedSources;

    public MociWeaponTargetBindings(CombatEngineAPI engine){
        this.engine=Objects.requireNonNull(engine);previousBridge=engine.getCustomData().get(KEY);previousStatus=engine.getCustomData().get(STATUS_KEY);
        bridge.put("schema",1);bridge.put("bindings",Map.of());bridge.put("capabilities",Map.of());
        if(previousBridge==null||validBridge(previousBridge)){
            IdentityHashMap<ShipAPI,Map<String,String>> initialBindings=new IdentityHashMap<>();
            IdentityHashMap<ShipAPI,Map<String,Map<String,Object>>> initialCapabilities=new IdentityHashMap<>();
            copyPrevious(initialBindings,initialCapabilities);
            bridge.put("bindings",Collections.unmodifiableMap(initialBindings));bridge.put("capabilities",Collections.unmodifiableMap(initialCapabilities));
            engine.getCustomData().put(KEY,bridgeView);ownsBridge=true;
        }else facts.put("error","Existing weapon-binding bridge has an unsupported schema; left untouched");
        engine.getCustomData().put(STATUS_KEY,factsView);publish();
    }
    public void advance(float dt){
        if(closed)return;if(!Float.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid structural-binding step");
        if(Global.getCombatEngine()!=engine||engine.isCombatOver()){close();return;}
        if(!ownsBridge)return;
        if(engine.getCustomData().get(KEY)!=bridgeView){ownsBridge=false;facts.put("error","Another binding provider replaced the bridge; original Moci provider relinquished it");publish();return;}
        if(engine.isPaused())return;scanClock-=dt;if(scanClock>0)return;scanClock=.25f;scans++;
        IdentityHashMap<ShipAPI,Map<String,String>> result=new IdentityHashMap<>();
        IdentityHashMap<ShipAPI,Map<String,Map<String,Object>>> capabilities=new IdentityHashMap<>();
        copyPrevious(result,capabilities);
        List<ShipAPI> ships=engine.getShips();
        for(int i=0;i<Math.min(ships.size(),MAX_SCAN_SHIPS);i++){
            ShipAPI ship=ships.get(i);
            if(ship==null||!ship.isAlive()||ship.isHulk()||ship.wasRemoved()||!engine.isEntityInPlay(ship)
                    ||ship.isDrone()||ship.isStationModule()||ship.getParentStation()!=null)continue;
            List<WeaponAPI> weapons=ship.getAllWeapons();
            if(weapons.size()>MAX_WEAPONS){budgetSkips++;continue;}
            Map<String,WeaponAPI> slots=new LinkedHashMap<>();Set<String> duplicateSlots=new HashSet<>();
            for(WeaponAPI weapon:weapons)if(weapon!=null&&weapon.getSlot()!=null){String slot=weapon.getSlot().getId();
                if(slot!=null&&!slot.isBlank()&&slots.putIfAbsent(slot,weapon)!=null)duplicateSlots.add(slot);}
            TreeMap<String,String> aliases=new TreeMap<>(result.getOrDefault(ship,Map.of()));
            TreeMap<String,Map<String,Object>> caps=new TreeMap<>(capabilities.getOrDefault(ship,Map.of()));
            boolean added=false;
            for(var part:slots.entrySet()){
                WeaponAPI body=part.getValue();int kind=knownEffect(body.getEffectPlugin());
                if(kind==0||!body.isDecorative()||duplicateSlots.contains(part.getKey()))continue;
                String base=part.getKey(),sourceSlot=base+"_SOURCE",canonical=sourceSlot,follow=base+"_FOLLOW";
                String opticalSlot=kind==4?ZetaRifleModes.SNIPER_SLOT:null;
                WeaponAPI source=slots.get(sourceSlot);
                if(kind==4){
                    // The owned Zeta arm's visible SOURCE follows its actually selected sniper
                    // on an independent optical slot (not the parent's angle-controlled FOLLOW).
                    // Use that real driver as the fire-control/range unit,
                    // rather than requiring the inactive short-range burst group to autofire.
                    WeaponAPI driver=ZetaRifleModes.activeDriver(ship);
                    if(!"ARM_R".equals(base)||!ZetaOverdriveStats.supports(ship)||source==null||source.isDecorative()
                            ||driver==null||duplicateSlots.contains(opticalSlot)){unsupportedSources++;continue;}
                    if(driver==slots.get(opticalSlot)&&ZetaRifleModes.SNIPER.equals(driver.getId()))canonical=opticalSlot;
                    else if(driver!=source||!ZetaRifleModes.BURST.equals(driver.getId())){unsupportedSources++;continue;}
                    source=driver;
                }
                if(source==null||source.isDecorative()||duplicateSlots.contains(sourceSlot)||duplicateSlots.contains(canonical)){unsupportedSources++;continue;}
                Map<String,String> family=new LinkedHashMap<>();family.put(canonical,canonical);family.put(sourceSlot,canonical);family.put(base,canonical);
                if(slots.containsKey(follow)&&!duplicateSlots.contains(follow))family.put(follow,canonical);
                if(kind==4&&slots.containsKey(opticalSlot)&&ZetaRifleModes.SNIPER.equals(slots.get(opticalSlot).getId()))family.put(opticalSlot,canonical);
                // The RX0 shield callback also explicitly synchronizes this existing cover slot.
                if(kind==3){String suffix=base.substring(base.lastIndexOf('_')+1),cover="SHIELD_"+suffix+"_COVER";
                    if(slots.containsKey(cover)&&!duplicateSlots.contains(cover)&&slots.get(cover).isDecorative())family.put(cover,canonical);}
                boolean conflict=family.entrySet().stream().anyMatch(alias->aliases.containsKey(alias.getKey())&&!aliases.get(alias.getKey()).equals(alias.getValue()));
                if(conflict){conflicts++;continue;}
                aliases.putAll(family);added=true;
                LinkedHashMap<String,Object> description=new LinkedHashMap<>(caps.getOrDefault(canonical,Map.of()));
                boolean defenseLimited=kind==3||body.getEffectPlugin() instanceof AdaptiveShieldArmEffects||"Moci_fw_xi_salvo_arm_left".equals(body.getId());
                description.putAll(Map.of("defenseLimited",defenseLimited,
                        "sourceEffect",body.getEffectPlugin().getClass().getName(),"sourceWeaponId",Objects.toString(source.getId(),""),
                        "bodySlot",base,"reason",defenseLimited?"Physical shield defense may override SOURCE aiming; defensive pose remains authoritative":kind==4?"Owned Zeta arm uses the actual independent optical driver for aiming, native range and autofire eligibility; the original parent does not angle-control its sniper slot":"Original Moci callback synchronizes SOURCE/FOLLOW aiming and muzzle geometry"));
                caps.put(canonical,Collections.unmodifiableMap(description));
            }
            if(added){
                if(result.size()>=MAX_SHIPS&&!result.containsKey(ship)||capabilities.size()>=MAX_SHIPS&&!capabilities.containsKey(ship)){budgetSkips++;continue;}
                result.put(ship,Collections.unmodifiableMap(aliases));capabilities.put(ship,Collections.unmodifiableMap(caps));
            }
        }
        if(ships.size()>MAX_SCAN_SHIPS)budgetSkips+=ships.size()-MAX_SCAN_SHIPS;
        bridge.put("bindings",Collections.unmodifiableMap(result));bridge.put("capabilities",Collections.unmodifiableMap(capabilities));
        bridge.put("provider","Owned Moci source/follow evidence adapter");publish();
    }
    private static int knownEffect(EveryFrameWeaponEffectPlugin effect){
        effect=AdaptiveShieldArmEffects.originalEffect(effect);
        if(effect==null)return 0;Class<?> type=effect.getClass();
        if(type==Moci_MechProjDecoWeaponEffect.class)return 1;
        if(type==ZetaRifleArmEffect.class)return 4;
        if(type==Moci_MechProjDecoWeaponEffect_noHide.class||type==FullSalvoArmEffect.class||type==ZetaHeadEffect.class)return 2;
        return type==Moci_MechProjDecoWeaponEffect_shield_arm_RX0.class?3:0;
    }
    private static boolean validBridge(Object value){return value instanceof Map<?,?> root&&root.get("schema") instanceof Number n
            &&n.intValue()==1&&root.get("bindings") instanceof Map<?,?>;}
    @SuppressWarnings("unchecked")private void copyPrevious(IdentityHashMap<ShipAPI,Map<String,String>> bindings,
            IdentityHashMap<ShipAPI,Map<String,Map<String,Object>>> capabilities){
        if(!(previousBridge instanceof Map<?,?> root))return;
        if(root.get("bindings") instanceof Map<?,?> ships){int count=0;for(var item:ships.entrySet()){
            if(++count>MAX_SHIPS){budgetSkips++;break;}if(!(item.getKey() instanceof ShipAPI ship)||!(item.getValue() instanceof Map<?,?> slots))continue;
            LinkedHashMap<String,String> copy=new LinkedHashMap<>();int checked=0;
            for(var entry:slots.entrySet()){if(++checked>MAX_WEAPONS){budgetSkips++;break;}if(entry.getKey() instanceof String slot&&entry.getValue() instanceof String canonical)copy.put(slot,canonical);}
            bindings.put(ship,Collections.unmodifiableMap(copy));
        }}
        if(root.get("capabilities") instanceof Map<?,?> ships){int count=0;for(var item:ships.entrySet()){
            if(++count>MAX_SHIPS)break;if(!(item.getKey() instanceof ShipAPI ship)||!(item.getValue() instanceof Map<?,?> slots))continue;
            LinkedHashMap<String,Map<String,Object>> copy=new LinkedHashMap<>();int checked=0;
            for(var entry:slots.entrySet()){if(++checked>MAX_WEAPONS)break;if(entry.getKey() instanceof String slot&&entry.getValue() instanceof Map<?,?> fields){
                LinkedHashMap<String,Object> values=new LinkedHashMap<>();int fieldCount=0;for(var field:fields.entrySet()){
                    if(++fieldCount>16)break;if(field.getKey() instanceof String name&&(field.getValue() instanceof String||field.getValue() instanceof Boolean||field.getValue() instanceof Number))values.put(name,field.getValue());}
                copy.put(slot,Collections.unmodifiableMap(values));}}
            capabilities.put(ship,Collections.unmodifiableMap(copy));
        }}
    }
    private void publish(){facts.put("schema",1);facts.put("closed",closed);facts.put("ownsBridge",ownsBridge);facts.put("scans",scans);
        facts.put("budgetSkips",budgetSkips);facts.put("conflicts",conflicts);facts.put("unsupportedSources",unsupportedSources);
        Object bindings=bridge.get("bindings");facts.put("boundShips",bindings instanceof Map<?,?> map?map.size():0);
        facts.put("scope","Exact original Moci decorative callbacks and owned FullSalvoArmEffect/ZetaHeadEffect/ZetaRifleArmEffect. Zeta's actual active optical driver supplies its canonical range/autofire unit. Real SOURCE must exist; unknown scripts and partial MAIN-only follow are not bound. This adapter changes only bridge metadata.");}
    public Map<String,Object> status(){publish();return Collections.unmodifiableMap(new LinkedHashMap<>(facts));}
    @Override public void close(){if(closed)return;closed=true;
        if(engine.getCustomData().get(KEY)==bridgeView){if(previousBridge==null)engine.getCustomData().remove(KEY);else engine.getCustomData().put(KEY,previousBridge);}
        if(engine.getCustomData().get(STATUS_KEY)==factsView){if(previousStatus==null)engine.getCustomData().remove(STATUS_KEY);else engine.getCustomData().put(STATUS_KEY,previousStatus);}
        ownsBridge=false;bridge.put("bindings",Map.of());bridge.put("capabilities",Map.of());publish();}
}
