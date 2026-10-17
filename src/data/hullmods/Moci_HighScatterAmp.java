package data.hullmods;

import java.util.HashSet;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicIncompatibleHullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier;
import data.scripts.util.Moci_TextLoader;

public class Moci_HighScatterAmp extends BaseHullMod {

	private static final String TEXT_ID = "Moci_HighScatterAmp";
	public static float VENT_BONUS = 25f;
	public static float BEAM_TURN_PENALTY = 25f;
	private static final Set<String> UNAPPLICABLE = new HashSet<>();
	static {
		UNAPPLICABLE.add("advancedoptics");
		UNAPPLICABLE.add("high_scatter_amp");
	}

	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getVentRateMult().modifyPercent(id, VENT_BONUS);  //V排效率
		stats.getBeamWeaponTurnRateBonus().modifyMult(id, 1f - BEAM_TURN_PENALTY * 0.01f); //炮塔转速
		for (String hullmMod : UNAPPLICABLE) {
			if (stats.getVariant().hasHullMod(hullmMod)) {
				MagicIncompatibleHullmods.removeHullmodWithWarning(stats.getVariant(), hullmMod, spec.getId());
			}
		}
	}

	@Override
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		if (!ship.hasListenerOfClass(HighScatterAmpDamageDealtMod.class)) {
			ship.addListener(new HighScatterAmpDamageDealtMod(ship));
		}
	}

	public static class HighScatterAmpDamageDealtMod implements DamageDealtModifier {
		protected ShipAPI ship;
		public HighScatterAmpDamageDealtMod(ShipAPI ship) {
			this.ship = ship;
		}

		@Override
		public String modifyDamageDealt(Object param,
								   		CombatEntityAPI target, DamageAPI damage,
								   		Vector2f point, boolean shieldHit) {

			if (!(param instanceof DamagingProjectileAPI) && param instanceof BeamAPI) {
				damage.setForceHardFlux(true);
			}
			return null;
		}
	}

	@Override
	public String getUnapplicableReason(ShipAPI ship) {
		if (ship.getVariant().hasHullMod("advancedoptics"))
			return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_advanced_optics");
		if (ship.getVariant().hasHullMod("high_scatter_amp"))
			return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_high_scatter_amp");
		return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_unknown");
	}

	@Override
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int)Math.round(VENT_BONUS) + "%";
		if (index == 1) return "" + (int)Math.round(BEAM_TURN_PENALTY) + "%";
		if (index == 2) return Moci_TextLoader.getText(TEXT_ID, "description.param_hard_flux");
		if (index == 3) return Moci_TextLoader.getText(TEXT_ID, "description.param_advanced_optics");
		if (index == 4) return Moci_TextLoader.getText(TEXT_ID, "description.param_high_scatter_amp");
		return null;
	}
}







