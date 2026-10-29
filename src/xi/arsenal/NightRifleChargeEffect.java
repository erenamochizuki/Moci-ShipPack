package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Exact Nightingale rifle presentation port. The original callback only registered a
 * growing552-unit muzzle sprite; native firing,charge,ammo and collision live elsewhere.
 * Report real charging to the one owned visual observer instead of drawing a second ray.
 */
public final class NightRifleChargeEffect implements EveryFrameWeaponEffectPlugin {
    public static final String WEAPON_ID="MSN_04_2_arm_right_rifle";
    private WeaponAPI boundWeapon;
    private CombatEngineAPI boundEngine;

    @Override public void advance(float amount,CombatEngineAPI engine,WeaponAPI weapon){
        if(engine==null||weapon==null)throw new IllegalArgumentException("Nightingale charge visual requires native engine and weapon");
        if(!WEAPON_ID.equals(weapon.getId()))throw new IllegalArgumentException("Nightingale charge visual bound to the wrong weapon");
        if(boundWeapon==null){boundWeapon=weapon;boundEngine=engine;}
        else if(boundWeapon!=weapon||boundEngine!=engine)throw new IllegalStateException("Nightingale charge visual instance changed ownership");
        if(!Float.isFinite(amount)||amount<=0f||engine.isPaused())return;
        float charge=weapon.getChargeLevel();
        if(!Float.isFinite(charge)||!weapon.isFiring())return;
        charge=Math.max(0f,Math.min(1f,charge));
        if(charge>=.1f){PsychoArmamentVisuals.ensure(engine);PsychoArmamentVisuals.nativeChargeGlow(engine,weapon,charge);}
    }
}
