package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPluginWithAdvanceAfter;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import data.scripts.weapons.Moci_MechProjDecoWeaponEffect_noHide;
import java.util.Set;
import org.lazywizard.lazylib.MathUtils;

/** Original Moci noHide head behavior with a selected-empty-group guard; body callback still runs once. */
public final class ZetaHeadEffect extends Moci_MechProjDecoWeaponEffect_noHide implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    private static final Set<String> AIMING=Set.of("ARM_L_SOURCE","ARM_R_SOURCE","ZETA_RIFLE_SNIPER","HEAD_SOURCE","HEAD_BEAM","ARM_L_MAIN","ARM_R_MAIN","ZETA_WRIST_L","ZETA_WRIST_R");
    private boolean sourceBound;
    private WeaponAPI headSource;
    @Override protected boolean shouldApplyRecoil(WeaponAPI weapon){return false;}
    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){
        if(engine==null||engine.isPaused())return;
        super.advance(amount,engine,weapon);
        ShipAPI ship=weapon.getShip();
        if(!sourceBound){
            sourceBound=true;
            // The original parent's source field is package-private. Resolve the same
            // actual SOURCE slot through public APIs, preserving its last-match binding.
            String sourceSlot=weapon.getSlot().getId()+"_SOURCE";
            for(WeaponAPI candidate:ship.getAllWeapons())if(sourceSlot.equals(candidate.getSlot().getId()))headSource=candidate;
        }
        float headAngle=headSource!=null?headSource.getCurrAngle():ship.getFacing();
        WeaponGroupAPI selected=ship.getSelectedGroupAPI();
        // Native getActiveWeapon selects index0 when empty. Empty groups are legal while
        // the variable-frame utility is switching modes, and with an unarmed WR loadout.
        if(selected!=null&&!selected.getWeaponsCopy().isEmpty()){
            WeaponAPI current=selected.getActiveWeapon();
            if(current!=null&&current.getSlot()!=null&&AIMING.contains(current.getSlot().getId()))headAngle=current.getCurrAngle();
        }
        float diff=MathUtils.getShortestRotation(weapon.getCurrAngle(),headAngle);
        float turn=weapon.getTurnRate()*amount;
        if(Math.abs(diff)>turn)diff=Math.signum(diff)*turn;
        weapon.setCurrAngle(weapon.getCurrAngle()+diff);
    }
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon){
        if(engine!=null&&!engine.isPaused()){RifleFiringPose.apply(weapon);ZetaTetherPose.apply(weapon);ZetaBeamDeflection.applyPose(weapon);}
    }
}
