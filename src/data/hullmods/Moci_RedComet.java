package data.hullmods;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import org.magiclib.util.MagicIncompatibleHullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 红色彗星 - Red Comet
 * 舰船的最大速度、机动性提高20%，并且总是获得0幅能加速
 */
public class Moci_RedComet extends BaseHullMod {
    private static final String TEXT_ID = "Moci_RedComet";

	public static float SPEED_BONUS = 20f; // 速度加成
	public static float MANEUVER_BONUS = 20f; // 机动性加成
	
	// 不兼容的船插列表
	private static final Set<String> UNAPPLICABLE = new HashSet<>();
	static {
		UNAPPLICABLE.add("safetyoverrides"); // 安全协议超驰
	}
	
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int)Math.round(SPEED_BONUS) + "%";
		if (index == 1) return "" + (int)Math.round(MANEUVER_BONUS) + "%";
		return null;
	}

	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// 最大速度提升20%
		stats.getMaxSpeed().modifyPercent(id, SPEED_BONUS);
		
		// 机动性提升20%
		stats.getAcceleration().modifyPercent(id, MANEUVER_BONUS);
		stats.getDeceleration().modifyPercent(id, MANEUVER_BONUS);
		stats.getTurnAcceleration().modifyPercent(id, MANEUVER_BONUS);
		stats.getMaxTurnRate().modifyPercent(id, MANEUVER_BONUS);
		
		// 0幅能加速 - 参考SafetyOverrides的正确实现
		stats.getZeroFluxMinimumFluxLevel().modifyFlat(id, 2f); // set to two, meaning boost is always on
		
		// 检查并移除不兼容的船插
		for (String hullMod : UNAPPLICABLE) {
			if (stats.getVariant().hasHullMod(hullMod)) {
				MagicIncompatibleHullmods.removeHullmodWithWarning(stats.getVariant(), hullMod, spec.getId());
			}
		}
	}

    @Override
    public int getDisplaySortOrder() {
        return 200;
    }
	
	@Override
	public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
		float pad = 6f;
		float opad = 5f;
		Color highlight = Misc.getHighlightColor();
		Color good = Misc.getPositiveHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();
		
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.speed_bonus",
						Moci_TextLoader.mapOf("speed_bonus", String.valueOf((int) SPEED_BONUS))),
				pad, good,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.speed_bonus_highlights",
						Moci_TextLoader.mapOf("speed_bonus", String.valueOf((int) SPEED_BONUS))).toArray(new String[0]));
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.maneuver_bonus",
						Moci_TextLoader.mapOf("maneuver_bonus", String.valueOf((int) MANEUVER_BONUS))),
				pad, good,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.maneuver_bonus_highlights",
						Moci_TextLoader.mapOf("maneuver_bonus", String.valueOf((int) MANEUVER_BONUS))).toArray(new String[0]));
		
		tooltip.addPara(
				Moci_TextLoader.getText(TEXT_ID, "description.zero_flux"),
				pad, good,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.zero_flux_highlights").toArray(new String[0]));
		
		tooltip.addPara(
				Moci_TextLoader.getText(TEXT_ID, "description.incompatible"),
				pad, bad,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.incompatible_highlights").toArray(new String[0]));
		
		// 红色彗星传说
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.legend_heading"), Alignment.MID, opad);
		tooltip.addPara(
				Moci_TextLoader.getText(TEXT_ID, "description.legend_text"),
				pad, new Color(255, 100, 100),
				Moci_TextLoader.getHighlights(TEXT_ID, "description.legend_text_highlights").toArray(new String[0]));
	}
} 
