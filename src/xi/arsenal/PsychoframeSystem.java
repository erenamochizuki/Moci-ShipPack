package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import java.util.*;

/**
 * Native F toggle for the explicitly requested Nightingale / Hi-Nu refit.
 * Only the public in-memory hull-system field is changed during this Mod's one
 * application-load callback. Original Mod files, hull IDs, variants, skeletons,
 * shields and subsystems are untouched. It is application content, not a battle
 * modifier: do not restore it when one battle ends in a resident multi-battle JVM.
 * PsychoframeEnhancement is the single owner of the actual instance stat leases.
 */
public final class PsychoframeSystem extends BaseShipSystemScript {
    private static final Set<String> BASES=Set.of("Moci_MSN_04_2","Moci_RX_93_v2");
    private static final Map<String,String> REPLACED=new LinkedHashMap<>();
    private static boolean installed;
    public static synchronized void installHullSystems(){
        if(installed)return;
        Objects.requireNonNull(Global.getSettings().getShipSystemSpec(PsychoframeEnhancement.SYSTEM_ID),"Missing owned psychoframe system");
        for(ShipHullSpecAPI hull:Global.getSettings().getAllShipHullSpecs()){
            if(!matches(hull))continue;
            REPLACED.put(hull.getHullId(),hull.getShipSystemId());
            hull.setShipSystemId(PsychoframeEnhancement.SYSTEM_ID);
        }
        for(String required:BASES)if(!REPLACED.containsKey(required))throw new IllegalStateException("Psychoframe required hull was not loaded: "+required);
        installed=true;
        Global.getLogger(PsychoframeSystem.class).info("Owned psychoframe F systems installed via public hull API: "+REPLACED);
    }
    private static boolean matches(ShipHullSpecAPI hull){
        // A shield/arm module never shares these exact base/D-parent identities.
        return hull!=null&&(member(hull.getHullId())||member(hull.getBaseHullId())||member(hull.getDParentHullId()));
    }
    private static boolean member(String id){return id!=null&&BASES.contains(id);}
    public static Map<String,String> replacedSystems(){return Collections.unmodifiableMap(new LinkedHashMap<>(REPLACED));}
    @Override public StatusData getStatusData(int index,State state,float effectLevel){
        return switch(index){
            case 0->new StatusData("航速 +20% / 转向 +65% / 起步与急停 +80%",false);
            case 1->new StatusData("武器转向 +80% / 射击精度增强 / 浮游炮速度与瞄准增强",false);
            case 2->new StatusData("武器冷却延长 25%；再次激活关闭",true);
            default->null;
        };
    }
    @Override public String getInfoText(ShipSystemAPI system,ShipAPI ship){return system!=null&&system.isOn()?"再次激活关闭":"精神感应框架";}
}
