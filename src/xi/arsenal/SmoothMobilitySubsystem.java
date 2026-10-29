package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import data.subsystems.Moci_MobilitySubsystem;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Keep native input/charges/AI/flames; preserve momentum and taper the native OUT speed bonus. */
public final class SmoothMobilitySubsystem extends Moci_MobilitySubsystem {
    public static final String OUT_MODIFIER="xi.arsenal.mobility.smoothExit";
    private static final Set<String> GUNDAM_FAMILIES=Set.of(
            "Moci_RX_105","Moci_RX_104FF","Moci_MSN_04_2","Moci_RX_93","Moci_RX_93_v2","Moci_RX_94",
            "Moci_MSZ_006","Moci_MSZ_010","Moci_RX_0_1","Moci_RX_0_2","Moci_RX_0_3",
            "Moci_RX_105_framework_arsenal","Moci_RX_104FF_framework_arsenal",
            "Moci_RX_78_2","Moci_FA_78_3","Moci_FA_00100S","Moci_MSN_00100","Moci_MSN_02","Moci_MSN_03","Moci_MSN_06S",
            "Moci_MS_06","Moci_MS_06_luna2","Moci_MS_06R","Moci_MS_07","Moci_MS_14","Moci_MS_18E",
            "Moci_AMS_119","Moci_AMX_004","Moci_NZ_333","Moci_NZ_666",
            "Moci_RGM_79","Moci_RGM_79P","Moci_RGM_79SP","Moci_RGM_86","Moci_RGM_89","Moci_RGM_96X",
            "Moci_RGZ_91","Moci_RMS_106","Moci_RMS_179","Moci_ASW_G_08","Moci_ASW_G_08S","Moci_ZGMF_X20A");
    public SmoothMobilitySubsystem(ShipAPI ship){
        super(ship);
        // 3.0.0 no longer exposes the instant-stop API at all; only the author's
        // extra thrust/braking strength from that option is ported here.
        setMaxAccelerationBonus(getMaxAccelerationBonus()*2f);
        setMaxDecelerationBonus(getMaxDecelerationBonus()*2f);
    }
    @Override public void advance(float amount,boolean paused){
        super.advance(amount,paused); // the native MagicLib owner invokes this only once
        if(paused)return;
        if(state==State.OUT&&ship.isAlive()&&!ship.isHulk()&&!ship.wasRemoved()){
            float level=getEffectLevel();
            if(!Float.isFinite(level))throw new IllegalStateException("Invalid native backpack effect level");
            // Original removes its entire max-speed bonus at the first OUT frame.
            // Own only this missing fade; the original still owns IN/ACTIVE stats.
            ship.getMutableStats().getMaxSpeed().modifyFlat(OUT_MODIFIER,getMaxSpeedBonus()*Math.max(0f,Math.min(1f,level)));
        }else clearExit();
    }
    private void clearExit(){ship.getMutableStats().getMaxSpeed().unmodify(OUT_MODIFIER);}
    @Override public void onFinished(){super.onFinished();clearExit();}
    @Override public void onShipDeath(){super.onShipDeath();clearExit();}
    @Override public String getBriefText(){return "惯性衔接 · 平滑加力";}
    @Override public String getDisplayText(){return emergencyName(ship.getHullSpec())?"紧急机动":"喷气背包";}
    /** Curated exact original Gundam-universe primary families,including their base/D skins.
     * New content may opt in with a semantic tag. The source Mobile_Suit asset folder
     * also contains AltEisen/Gespenst/other media,so it is deliberately not a classifier.
     */
    public static boolean emergencyName(ShipHullSpecAPI spec){
        if(spec==null)return false;
        Set<ShipHullSpecAPI> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<ShipHullSpecAPI> queue=new ArrayDeque<>();queue.add(spec);
        for(int i=0;i<8&&!queue.isEmpty();i++){
            ShipHullSpecAPI current=queue.removeFirst();if(!seen.add(current))continue;
            if(current.hasTag("fw_emergency_maneuver")||family(current.getHullId())||family(current.getBaseHullId())||family(current.getDParentHullId()))return true;
            ShipHullSpecAPI base=current.getBaseHull(),parent=current.getDParentHull();
            if(base!=null&&!seen.contains(base))queue.addLast(base);
            if(parent!=null&&!seen.contains(parent))queue.addLast(parent);
        }
        return false;
    }
    private static boolean family(String id){return id!=null&&GUNDAM_FAMILIES.contains(id);}
}
