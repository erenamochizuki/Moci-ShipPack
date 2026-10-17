package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_Hidden_Hullmod extends BaseHullMod {
	private static final String TEXT_ID = "Moci_hidden_hullmod";

	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// DP归零，CR时间一千倍
		stats.getDynamic().getMod(Stats.DEPLOYMENT_POINTS_MOD).modifyMult(id, 0f);
		stats.getPeakCRDuration().modifyMult(id,1000);
		stats.getSuppliesToRecover().modifyMult(id,0);
		stats.getSuppliesPerMonth().modifyMult(id,0);
		// 禁用所有武器的能量消耗
		// stats.getBallisticWeaponFluxCostMod().modifyMult(id, 0);
		// stats.getEnergyWeaponFluxCostMod().modifyMult(id, 0);
		// stats.getMissileWeaponFluxCostMod().modifyMult(id, 0);
	}

	@Override
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		// 设置飞船碰撞体积为NONE
		ship.setCollisionClass(CollisionClass.NONE);
		
		// 隐藏舰船贴图
		if (ship.getSpriteAPI() != null) {
			ship.getSpriteAPI().setSize(0.0f, 0.0f);
		}
		
		// 注意：护盾和引擎的贴图隐藏需要在战斗中动态处理
		// 因为这些组件的贴图方法在舰船创建时可能不可用
		
		// ship.setCollisionRadius(0);
	}

	@Override
	public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
		float pad = 10f;
		float opad = 5f;
		Color highlight = Misc.getHighlightColor();
		Color good = Misc.getPositiveHighlightColor();
		
		// 隐身效果说明
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.stealth_heading"), Alignment.MID, opad);
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.stealth_intro"),
				pad, Misc.getTextColor());
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.stealth_sprite"),
				pad, good);
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.stealth_collision"),
				pad, good,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.stealth_collision_highlights").toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.stealth_effects"),
				pad, good);
		
		// 部署成本说明
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.deployment_heading"), Alignment.MID, opad);
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.deployment_dp"),
				pad, good,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.deployment_dp_highlights").toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.deployment_peak"),
				pad, good,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.deployment_peak_highlights").toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.deployment_maintenance"),
				pad, good,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.deployment_maintenance_highlights").toArray(new String[0]));
		
		// 注意事项
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.warning_heading"), 
				new Color(255,150,150,255),
				new Color(200,0,0,150),
				Alignment.MID, opad);
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.warning_text"),
				pad, highlight);
	}

	public String getDescriptionParam(int index, HullSize hullSize) {
		return null;
	}
}
