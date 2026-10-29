package xi.arsenal;

import com.fs.starfarer.api.combat.*;
/** A manual group presents a non-damaging toggle action. Its SHIP_FIRE down edge is
 * consumed by the owning actor, so holding fire cannot flip it on/off every frame. */
public final class ZetaDeflectionWeapon implements EveryFrameWeaponEffectPlugin,OnFireEffectPlugin {
    public static final String ID="Moci_fw_zeta_beam_deflection",SLOT="ZETA_BEAM_DEFLECTION";
    @Override public void advance(float dt,CombatEngineAPI engine,WeaponAPI weapon){weapon.setForceNoFireOneFrame(true);WeaponGroupAPI group=weapon.getShip().getWeaponGroupFor(weapon);if(group!=null&&group.isAutofiring())group.toggleOff();}
    @Override public void onFire(DamagingProjectileAPI shot,WeaponAPI weapon,CombatEngineAPI engine){if(shot!=null)engine.removeEntity(shot);}
}
