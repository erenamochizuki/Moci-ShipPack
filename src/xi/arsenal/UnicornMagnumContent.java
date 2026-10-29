package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import java.util.*;

/** Startup-only exact three-family mapping; original structure,variants and module declarations remain. */
public final class UnicornMagnumContent {
    public static final String PULSE="Moci_fw_unicorn_magnum_pulse",CHARGE="Moci_fw_unicorn_magnum_charge",
            SOURCE_SLOT="ARM_R_SOURCE",CHARGE_SLOT="UNICORN_MAGNUM_CHARGE";
    private static boolean installed;private static int hulls,variants;
    private UnicornMagnumContent(){}
    public static synchronized void install(){
        if(installed)return;
        Objects.requireNonNull(Global.getSettings().getWeaponSpec(PULSE),"Missing owned Magnum pulse");
        Objects.requireNonNull(Global.getSettings().getWeaponSpec(CHARGE),"Missing owned Magnum charge");
        for(ShipHullSpecAPI hull:Global.getSettings().getAllShipHullSpecs()){
            if(!supports(hull))continue;
            if(hull.getWeaponSlotAPI(SOURCE_SLOT)==null||hull.getWeaponSlotAPI(CHARGE_SLOT)==null)throw new IllegalStateException("Complete RX0 missing exact Magnum mode slots: "+hull.getHullId());
            String old=hull.getBuiltInWeapons().get(SOURCE_SLOT);
            if(!UnicornNtdStats.MAGNUM_ID.equals(old)&&!PULSE.equals(old))throw new IllegalStateException("RX0 right source belongs to another content: "+hull.getHullId()+" / "+old);
            if(!CHARGE.equals(hull.getBuiltInWeapons().get(CHARGE_SLOT)))throw new IllegalStateException("RX0 hidden Magnum declaration differs: "+hull.getHullId());
            hull.addBuiltInWeapon(SOURCE_SLOT,PULSE);hulls++;
        }
        if(hulls<3)throw new IllegalStateException("All three complete RX0 Magnum hull mappings are required");
        for(String id:List.copyOf(Global.getSettings().getAllVariantIds())){
            ShipVariantAPI variant=Global.getSettings().getVariant(id);if(variant==null||!supports(variant.getHullSpec()))continue;
            // Native variants import their own unchanged HullSpec's built-ins through this public path.
            variant.setHullSpecAPI(variant.getHullSpec());
            if(!PULSE.equals(variant.getWeaponId(SOURCE_SLOT))||!CHARGE.equals(variant.getWeaponId(CHARGE_SLOT)))throw new IllegalStateException("RX0 cached variant did not materialize mode weapons: "+id);
            variants++;
        }
        installed=true;Global.getLogger(UnicornMagnumContent.class).info("Owned RX0 Magnum two-mode mappings: hulls="+hulls+", variants="+variants);
    }
    static boolean supports(ShipHullSpecAPI hull){return hull!=null&&(member(hull.getHullId())||member(hull.getBaseHullId())||member(hull.getDParentHullId()));}
    private static boolean member(String id){return id!=null&&UnicornNtdStats.HULL_IDS.contains(id);}
    public static Map<String,Object> status(){return Map.of("installed",installed,"hulls",hulls,"variants",variants);}
}
