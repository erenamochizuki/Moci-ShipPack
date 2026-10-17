package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

public class Moci_DefenseDroneStats extends BaseShipSystemScript {

	public static final float SENSOR_RANGE_PERCENT = 5; // 视野加成
	public static final float EWAR_STRENGTH = 0.25f; // 电子战强度值
	private static final String STATUS_ICON = "graphics/icons/hullsys/drone_sensor.png"; // 沿用DamperField图标
	private static final String STATUS_NAME = "守卫无人机";
	private static final String STATUS_DESC_FORMAT =
			"战场视野+ %s | 电子战强度+ %s";

	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		float sensorRangePercent = SENSOR_RANGE_PERCENT * effectLevel;
		float ecmPercent = EWAR_STRENGTH * effectLevel;

		// 传感器范围加成
		stats.getSightRadiusMod().modifyPercent(id, sensorRangePercent);

		// 电子战效果
		stats.getDynamic().getMod(Stats.ELECTRONIC_WARFARE_FLAT).modifyFlat(id, ecmPercent);
		stats.getAutofireAimAccuracy().modifyFlat(id, ecmPercent);
		// 玩家舰船状态显示
		ShipAPI ship = null;
		boolean player = false;

		CombatEngineAPI engine = Global.getCombatEngine();

		if (stats.getEntity() instanceof ShipAPI) {
			ship = (ShipAPI) stats.getEntity();
			player = ship == Global.getCombatEngine().getPlayerShip();
		}
		if (player) {
			String desc;
			try {
				desc = String.format(
						STATUS_DESC_FORMAT,
						(int) sensorRangePercent + "%",
						(int) ecmPercent + "%"
				);
			} catch (Exception e) {
				desc = "状态激活中";
			}

			// 最终状态更新
			engine.maintainStatusForPlayerShip(
					"Moci_DefenseDroneStats",
					STATUS_ICON,
					STATUS_NAME,
					desc,
					false
			);
		}
	}

	public void unapply(MutableShipStatsAPI stats, String id) {
		stats.getSightRadiusMod().unmodify(id);
		stats.getDynamic().getMod(Stats.ELECTRONIC_WARFARE_FLAT).unmodify(id);
		stats.getAutofireAimAccuracy().unmodify(id);
	}

	// public StatusData getStatusData(int index, State state, float effectLevel) {
	// 	float sensorRangePercent = SENSOR_RANGE_PERCENT * effectLevel;
	// 	float ecmPercent = EWAR_STRENGTH * effectLevel;
	// 	if (index == 0) {
	// 		return new StatusData("战场视野 +" + (int) sensorRangePercent + "%", false);
	// 	} else if (index == 1) {
	// 		return new StatusData("电子战强度 +" + (int) ecmPercent + "%", false);
	// 	}
	// 	return null;
	// }
}
