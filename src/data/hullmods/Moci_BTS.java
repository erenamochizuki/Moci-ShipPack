package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * BTS - Big Target Slayer
 * 对舰船级别高于自身的舰船造成额外20%伤害
 */
public class Moci_BTS extends BaseHullMod {
    private static final String TEXT_ID = "Moci_BTS";

	public static float DAMAGE_BONUS = 20f; // 对高级目标的伤害加成

	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int)Math.round(DAMAGE_BONUS) + "%";
		return null;
	}

	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// 根据舰船级别提供对应的伤害加成
		HullSize currentSize = stats.getVariant().getHullSize();
		
		// 对更高级舰船的伤害加成
		switch (currentSize) {
			case FRIGATE:
				// 护卫舰对驱逐舰、巡洋舰、主力舰的伤害+20%
				stats.getDamageToDestroyers().modifyPercent(id, DAMAGE_BONUS);
				stats.getDamageToCruisers().modifyPercent(id, DAMAGE_BONUS);
				stats.getDamageToCapital().modifyPercent(id, DAMAGE_BONUS);
				break;
			case DESTROYER:
				// 驱逐舰对巡洋舰、主力舰的伤害+20%
				stats.getDamageToCruisers().modifyPercent(id, DAMAGE_BONUS);
				stats.getDamageToCapital().modifyPercent(id, DAMAGE_BONUS);
				break;
			case CRUISER:
				// 巡洋舰对主力舰的伤害+20%
				stats.getDamageToCapital().modifyPercent(id, DAMAGE_BONUS);
				break;
			case CAPITAL_SHIP:
				// 主力舰没有更高级的目标
				break;
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
		
		// 主要效果说明
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.heading"), Alignment.MID, opad);
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.main",
						Moci_TextLoader.mapOf("damage_bonus", String.valueOf((int) DAMAGE_BONUS))),
				pad, good,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.main_highlights",
						Moci_TextLoader.mapOf("damage_bonus", String.valueOf((int) DAMAGE_BONUS))).toArray(new String[0]));
		
		// 具体效果表格
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.detail_heading"), Alignment.MID, opad);
		
		// 创建表格
		float[] columnWidths = {width * 0.3f, width * 0.7f};
		tooltip.beginTable(
				Misc.getBasePlayerColor(),
				Misc.getDarkPlayerColor(), 
				Misc.getBrightPlayerColor(),
				20f,
				Moci_TextLoader.getText(TEXT_ID, "description.table_ship_type"), columnWidths[0],
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.table_bonus_targets",
						Moci_TextLoader.mapOf("damage_bonus", String.valueOf((int) DAMAGE_BONUS))), columnWidths[1]
		);
		
		// 添加表格行
		tooltip.addRow(highlight, Moci_TextLoader.getText(TEXT_ID, "description.table_frigate"), Moci_TextLoader.getText(TEXT_ID, "description.table_frigate_targets"));
		tooltip.addRow(highlight, Moci_TextLoader.getText(TEXT_ID, "description.table_destroyer"), Moci_TextLoader.getText(TEXT_ID, "description.table_destroyer_targets"));  
		tooltip.addRow(highlight, Moci_TextLoader.getText(TEXT_ID, "description.table_cruiser"), Moci_TextLoader.getText(TEXT_ID, "description.table_cruiser_targets"));
		tooltip.addRow(Misc.getGrayColor(), Moci_TextLoader.getText(TEXT_ID, "description.table_capital"), Moci_TextLoader.getText(TEXT_ID, "description.table_capital_targets"));
		
		tooltip.addTable("", 0, pad);
		
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.note"),
				pad, highlight);
	}
} 
