package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import java.util.*;

/** Application-load content mapping, using the original body's existing empty hidden SYSTEM slot. */
public final class ZetaContent {
    public static final String SYSTEM="Moci_fw_zeta_biosensor_overload",RAM="Moci_fw_zeta_shield_ram",SLOT="SPWP";
    private static final Map<String,String> ARMAMENT=Map.of("ARM_R_SOURCE","Moci_fw_zeta_rifle_burst","ZETA_RIFLE_SNIPER","Moci_fw_zeta_rifle_sniper",
            "ZETA_WRIST_L","Moci_fw_zeta_wrist_grenade","ZETA_WRIST_R","Moci_fw_zeta_wrist_grenade",
            "WR_MAINGUN","Moci_fw_zeta_rifle_burst","WR_RIFLE_SNIPER","Moci_fw_zeta_rifle_sniper",ZetaDeflectionWeapon.SLOT,ZetaDeflectionWeapon.ID);
    private static final Map<String,String> replaced=new LinkedHashMap<>();
    private static boolean installed;
    private static int patchedVariants;
    private ZetaContent(){}
    public static synchronized void install(){
        if(installed)return;
        Objects.requireNonNull(Global.getSettings().getShipSystemSpec(SYSTEM),"Missing Zeta biosensor system");
        Objects.requireNonNull(Global.getSettings().getWeaponSpec(RAM),"Missing Zeta shield ram trigger");
        for(String weapon:new HashSet<>(ARMAMENT.values()))Objects.requireNonNull(Global.getSettings().getWeaponSpec(weapon),"Missing Zeta built-in: "+weapon);
        for(ShipHullSpecAPI hull:Global.getSettings().getAllShipHullSpecs()){
            if(!matches(hull))continue;
            // Original Zeta declares SHIELD_L in its variant, but its CSV omitted
            // this native deployment hint. Without it the fleet manager never
            // instantiates those declared modules. Do not manufacture an FX shield.
            hull.getHints().add(ShipHullSpecAPI.ShipTypeHints.SHIP_WITH_MODULES);
            if(hull.getWeaponSlotAPI(SLOT)==null)throw new IllegalStateException("Zeta is missing its original SPWP slot: "+hull.getHullId());
            // This invisible command trigger must accept a cursor point on either
            // side before the ram's own alignment. Original gun/thruster slots stay unchanged.
            hull.getWeaponSlotAPI(SLOT).setArc(360);
            String existing=hull.getBuiltInWeapons().get(SLOT);
            if(existing!=null&&!RAM.equals(existing))throw new IllegalStateException("Zeta SPWP already belongs to another content: "+existing);
            replaced.put(hull.getHullId(),hull.getShipSystemId());hull.setShipSystemId(SYSTEM);hull.addBuiltInWeapon(SLOT,RAM);
            for(var entry:ARMAMENT.entrySet()){
                if(hull.getWeaponSlotAPI(entry.getKey())==null)throw new IllegalStateException("Zeta armament slot missing: "+hull.getHullId()+" / "+entry.getKey());
                String previous=hull.getBuiltInWeapons().get(entry.getKey());
                boolean originalRifle=entry.getKey().equals("ARM_R_SOURCE")&&"MSZ_006_arm_right_gun_rifle".equals(previous);
                if(previous!=null&&!entry.getValue().equals(previous)&&!originalRifle)throw new IllegalStateException("Zeta armament slot belongs to another content: "+entry.getKey()+" / "+previous);
                hull.addBuiltInWeapon(entry.getKey(),entry.getValue());
            }
        }
        if(!replaced.containsKey("Moci_MSZ_006"))throw new IllegalStateException("Original complete Zeta hull was not loaded");
        // Native variants materialize built-ins when their hull is first loaded.
        // Updating only HullSpec after that cannot populate a cloned variant's
        // fitted-slot table, which is the factory's actual weapon creation source.
        for(String id:List.copyOf(Global.getSettings().getAllVariantIds())){
            ShipVariantAPI variant=Global.getSettings().getVariant(id);if(variant==null||!matches(variant.getHullSpec()))continue;
            String previous=variant.getWeaponId(SLOT);
            if(previous!=null&&!RAM.equals(previous))throw new IllegalStateException("Zeta variant SPWP is already occupied: "+id+" / "+previous);
            // Ordinary addWeapon intentionally refuses built-in slots. Refreshing
            // the SAME public hull spec is the native supported path that imports
            // all built-ins with its internal protected-slot flag, preserving refits.
            variant.setHullSpecAPI(variant.getHullSpec());
            if(!RAM.equals(variant.getWeaponId(SLOT))||!variant.getFittedWeaponSlots().contains(SLOT))
                throw new IllegalStateException("Zeta native variant failed to materialize SPWP: "+id);
            for(var entry:ARMAMENT.entrySet())if(!entry.getValue().equals(variant.getWeaponId(entry.getKey()))||!variant.getFittedWeaponSlots().contains(entry.getKey()))
                throw new IllegalStateException("Zeta variant failed to materialize built-in: "+id+" / "+entry.getKey());
            patchedVariants++;
        }
        installed=true;Global.getLogger(ZetaContent.class).info("Owned Zeta biosensor/ram mappings installed: "+replaced+"; fitted cached variants="+patchedVariants);
    }
    public static Map<String,String> replacedSystems(){return Collections.unmodifiableMap(new LinkedHashMap<>(replaced));}
    private static boolean matches(ShipHullSpecAPI hull){return hull!=null&&("Moci_MSZ_006".equals(hull.getHullId())||"Moci_MSZ_006".equals(hull.getBaseHullId())||"Moci_MSZ_006".equals(hull.getDParentHullId()));}
}
