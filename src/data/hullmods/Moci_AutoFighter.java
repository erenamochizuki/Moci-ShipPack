package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import data.scripts.util.Moci_TextLoader;

public class Moci_AutoFighter extends BaseHullMod {
	private static final String TEXT_ID = "Moci_autofighter";

	@Override
	public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// 设置作战人员损失为0
		stats.getDynamic().getStat("fighter_crew_loss_mult").modifyMult(id, 0f);
	}

	@Override
	public void applyEffectsToFighterSpawnedByShip(ShipAPI fighter, ShipAPI ship, String id) {
		// 创建AI核心
		String aiCoreId = "gamma_core";
		PersonAPI aiCore = Global.getFactory().createPerson();
		
		// 安全地获取母舰的faction，参考CjyToolClass的实现
		String factionId = "player"; // 默认值
		if (ship != null && ship.getFleetMember() != null
				&& ship.getFleetMember().getFleetData() != null
				&& ship.getFleetMember().getFleetData().getFleet() != null
				&& ship.getFleetMember().getFleetData().getFleet().getFaction() != null) {
			factionId = ship.getFleetMember().getFleetData().getFleet().getFaction().getId();
		}
		
		aiCore.setFaction(factionId);
		aiCore.setAICoreId(aiCoreId);
		aiCore.setPortraitSprite("graphics/portraits/portrait_ai1b.png");
		aiCore.getStats().setLevel(3);
		aiCore.getStats().setSkillLevel(Skills.TARGET_ANALYSIS, 2);
		aiCore.getStats().setSkillLevel(Skills.HELMSMANSHIP, 2);
		aiCore.getStats().setSkillLevel(Skills.COMBAT_ENDURANCE, 2);
		aiCore.setPersonality("reckless");
		aiCore.setRankId(Ranks.SPACE_CAPTAIN);
		aiCore.setPostId((String)null);
		// 应用AI到战斗机
		fighter.setCaptain(aiCore);
		
		// 更新舰船数据，确保技能效果立即生效
		if (fighter.getFleetMember() != null) {
			fighter.getFleetMember().updateStats();
		}

		// 如果AI有导弹专精技能，重置导弹武器弹药
		if (aiCore.getStats().hasSkill("missile_specialization")) {
			for (WeaponAPI weapon : fighter.getAllWeapons()) {
				if (weapon.getType() == WeaponType.MISSILE) {
					weapon.resetAmmo(); // 重置弹药
				}
			}
		}
	}

	public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
		if (index == 0) return Moci_TextLoader.getText(TEXT_ID, "description.param_auto_ai_fighter");
		if (index == 1) return Moci_TextLoader.getText(TEXT_ID, "description.param_gamma_core");
		return null;
	}
}
