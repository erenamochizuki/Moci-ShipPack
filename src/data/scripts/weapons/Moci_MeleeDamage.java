package data.scripts.weapons;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.lwjgl.util.vector.Vector2f;

/** Shared damage calculation for the blade, axe, and their interception sweep. */
public final class Moci_MeleeDamage {
    private Moci_MeleeDamage() {
    }

    public static float calculateFullDamage(WeaponAPI weapon) {
        if (weapon == null || weapon.getShip() == null) {
            return 0f;
        }

        // Preserve the existing minimum one-second attack budget before modifiers.
        // Applying this floor after modifiers would silently cancel damage penalties.
        float baseDamage = weapon.getDamage().getDamage()
                * Math.max(1f, weapon.getSpec().getBurstDuration());
        MutableShipStatsAPI stats = weapon.getShip().getMutableStats();
        MutableStat weaponDamage = null;
        if (weapon.getType() == WeaponAPI.WeaponType.ENERGY) {
            weaponDamage = stats.getEnergyWeaponDamageMult();
        } else if (weapon.getType() == WeaponAPI.WeaponType.BALLISTIC) {
            weaponDamage = stats.getBallisticWeaponDamageMult();
        } else if (weapon.getType() == WeaponAPI.WeaponType.MISSILE) {
            weaponDamage = stats.getMissileWeaponDamageMult();
        }

        float percent = 0f;
        float flat = 0f;
        float mult = 1f;
        if (weaponDamage != null) {
            percent += weaponDamage.getPercentMod();
            flat += weaponDamage.getFlatMod();
            mult *= weaponDamage.getMult();
        }
        if (weapon.isBeam()) {
            MutableStat beamDamage = stats.getBeamWeaponDamageMult();
            percent += beamDamage.getPercentMod();
            flat += beamDamage.getFlatMod();
            mult *= beamDamage.getMult();
        }

        // Match the engine's combined damage modifier, whose base value is one.
        return Math.max(0f, baseDamage * (1f + percent * 0.01f + flat) * mult);
    }

    /**
     * Keep the real beam as the modifier parameter so source-aware listeners (such
     * as Energy Weapon Mastery and I-Field) can identify this hit. Flux-based damage
     * bonuses are applied by those listeners, not pre-multiplied into the attack.
     *
     * The public applyDamage API creates instantaneous damage. Passing a beam
     * restores its source, but does not change DamageAPI.isDps(); core modifiers
     * that rely on that flag still treat this as instantaneous damage. Retaining
     * this API also preserves the full per-hit amount and existing hard flux.
     */
    public static void applyDamage(CombatEngineAPI engine, BeamAPI beam,
            CombatEntityAPI target, Vector2f point, float damage, float emp) {
        WeaponAPI weapon = beam.getWeapon();
        engine.applyDamage(beam, target, point, damage, weapon.getDamageType(), emp,
                false, false, weapon.getShip(), false);
    }
}
