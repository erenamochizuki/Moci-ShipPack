package xi.arsenal;

import com.fs.starfarer.api.combat.*;

/** One real native weapon shot requests the ram. The zero-collision carrier never applies damage. */
public final class ZetaRamTrigger implements OnFireEffectPlugin {
    @Override public void onFire(DamagingProjectileAPI projectile,WeaponAPI weapon,CombatEngineAPI engine){
        if(projectile!=null)engine.removeEntity(projectile);
        if(weapon==null||!ZetaContent.RAM.equals(weapon.getId())||weapon.getSlot()==null||!ZetaContent.SLOT.equals(weapon.getSlot().getId()))
            throw new IllegalStateException("Zeta ram trigger attached to foreign weapon");
        ZetaEnhancement enhancement=ZetaEnhancement.instance(engine);
        if(enhancement==null)throw new IllegalStateException("Zeta ram combat controller is not installed");
        enhancement.requestRam(weapon.getShip(),weapon.getShip().getMouseTarget());
    }
}
