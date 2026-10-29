package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPluginWithAdvanceAfter;
import com.fs.starfarer.api.combat.WeaponAPI;
import data.scripts.weapons.*;

/** One native callback wrapper; the original arm callback keeps its animation, hiding and muzzle work. */
public final class AdaptiveShieldArmEffects implements EveryFrameWeaponEffectPluginWithAdvanceAfter {
    private EveryFrameWeaponEffectPlugin original;
    private String weaponId;

    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon) {
        if(original==null) {
            weaponId=weapon.getId();
            original=createOriginal(AdaptiveShieldGuard.originalEffect(engine,weaponId));
        } else if(!weaponId.equals(weapon.getId()))throw new IllegalStateException("Shield arm callback was shared by different weapons");
        original.advance(amount,engine,weapon);
        AdaptiveShieldGuard.recordFrame(amount,engine,weapon);
    }
    @Override public void advanceAfter(float amount,CombatEngineAPI engine,WeaponAPI weapon) {
        if(original instanceof EveryFrameWeaponEffectPluginWithAdvanceAfter after)after.advanceAfter(amount,engine,weapon);
        if(engine!=null&&!engine.isPaused()){AdaptiveShieldGuard.applyAfter(weapon);RifleFiringPose.apply(weapon);ZetaTetherPose.apply(weapon);ZetaBeamDeflection.applyPose(weapon);}
    }
    /** Structural observers may unwrap this callback; observers must never advance the returned delegate. */
    public static EveryFrameWeaponEffectPlugin originalEffect(EveryFrameWeaponEffectPlugin effect) {
        return effect instanceof AdaptiveShieldArmEffects wrapper?wrapper.original:effect;
    }
    static EveryFrameWeaponEffectPlugin createOriginal(String name) {
        return switch(name) {
            case "data.scripts.weapons.Moci_MechProjDecoWeaponEffect_shield_arm" -> new Moci_MechProjDecoWeaponEffect_shield_arm();
            case "data.scripts.weapons.Moci_MechProjDecoWeaponEffect_shield_arm_Hide" -> new Moci_MechProjDecoWeaponEffect_shield_arm_Hide();
            case "data.scripts.weapons.Moci_MechProjDecoWeaponEffect_shield_arm_keepdef" -> new Moci_MechProjDecoWeaponEffect_shield_arm_keepdef();
            case "data.scripts.weapons.Moci_MechProjDecoWeaponEffect_shield_arm_RX0" -> new Moci_MechProjDecoWeaponEffect_shield_arm_RX0();
            case "data.scripts.weapons.Moci_MechProjDecoWeaponEffect_shield_arm_NoDefense" -> new Moci_MechProjDecoWeaponEffect_shield_arm_NoDefense();
            case "data.scripts.weapons.Moci_MechProjDecoWeaponEffect_noHide" -> new Moci_MechProjDecoWeaponEffect_noHide();
            case "data.scripts.weapons.Moci_MSZ006ShieldArmEffect" -> new Moci_MSZ006ShieldArmEffect();
            default -> throw new IllegalArgumentException("Unsupported original shield-arm callback: "+name);
        };
    }
}
