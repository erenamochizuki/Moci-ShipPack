package xi.arsenal;

import com.fs.starfarer.api.combat.*;
import java.util.Map;

/** Observes the real native round; ammo, flux, burst scheduling and HE damage remain native. */
public final class ZetaWristOnFire implements OnFireEffectPlugin, ProximityExplosionEffect {
    public static final String EXPLOSION_KEY = ZetaWristMount.KEY + ".explosion";
    @Override public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (weapon.getEffectPlugin() instanceof ZetaWristMount mount) mount.nativeShot(projectile);
        else throw new IllegalStateException("Native Zeta grenade fired without its wrist mount callback");
    }

    /** Native explosions intentionally have no WeaponAPI; record provenance at the real fuse callback. */
    @Override public void onExplosion(DamagingProjectileAPI explosion, DamagingProjectileAPI original) {
        if (explosion == null || original == null) return;
        WeaponAPI weapon = original.getWeapon();
        if (weapon == null || !ZetaWristMount.WEAPON_ID.equals(weapon.getId()) || weapon.getSlot() == null) return;
        String slot = weapon.getSlot().getId();
        if (!ZetaWristMount.LEFT_SLOT.equals(slot) && !ZetaWristMount.RIGHT_SLOT.equals(slot)) return;
        DamageAPI damage = original.getDamage();
        if (damage == null || damage.getType() == null) return;
        ShipAPI source = original.getSource();
        String sourceId = source == null || source.getId() == null ? "" : source.getId();
        // Only immutable value data: no retained source, weapon or projectile instances.
        explosion.setCustomData(EXPLOSION_KEY, Map.of("weaponId", weapon.getId(), "slot", slot,
                "baseDamage", damage.getBaseDamage(), "damageType", damage.getType().name(), "sourceId", sourceId));
    }
}
