package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import data.scripts.util.Moci_TextLoader;

import java.util.HashSet;
import java.util.Set;

import org.magiclib.util.MagicIncompatibleHullmods;

/**
 * 米诺夫斯基飞行器
 * 结合了辅助推进器、增强引擎和高级地面支援的特性
 * 
 * 基础特性：
 * - 机动性提升50%（来自辅助推进器）
 * - 航速等级+2（来自增强引擎）
 * - 地面支援+200（来自高级地面支援）
 * - 战备恢复+10%
 * 
 * S-Mod额外特性：
 * - 航速等级再+1
 * - 零幅能速度+10
 * - 零幅能转向倍率x2
 * - 燃料消耗-15%
 */
public class Moci_MinovskyFlightUnit extends BaseHullMod {
    private static final String TEXT_ID = "Moci_MinovskyFlightUnit";

    // 机动性
    private static final float MANEUVER_BONUS = 40f;

    // 航速等级
    private static final int BURN_LEVEL_BONUS = 1;

    // 地面支援
    private static final float GROUND_SUPPORT_BONUS = 150f;
    private static final int SMOD_BURN_LEVEL_BONUS = 1; // S-Mod航速等级
    private static final float SMOD_MANEUVER_BONUS = 10f; // S-Mod机动性加成
    private static final float SMOD_GROUND_SUPPORT_BONUS = 50f; // S-Mod地面支援
    private static final float SMOD_ZERO_FLUX_SPEED_BONUS = 10f; // S-Mod零幅能速度加成
    private static final float SMOD_ZERO_FLUX_TURN_MULT = 2f; // S-Mod零幅能转向倍率
	private static final Set<String> UNAPPLICABLE = new HashSet<>();
	static {
		UNAPPLICABLE.add("converted_hangar");
		UNAPPLICABLE.add("pointdefenseai");
		UNAPPLICABLE.add("surveying_equipment");
	}
    
    // 允许安装的舰船ID
    private static final Set<String> ALLOWED_HULL_IDS = new HashSet<>();
    static {
        ALLOWED_HULL_IDS.add("Moci_Sodon");
        ALLOWED_HULL_IDS.add("Moci_WhiteBase");
    }

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        boolean sMod = isSMod(stats);

        // 机动性加成
        stats.getAcceleration().modifyPercent(id, MANEUVER_BONUS);
        stats.getDeceleration().modifyPercent(id, MANEUVER_BONUS);
        stats.getTurnAcceleration().modifyPercent(id, MANEUVER_BONUS);
        stats.getMaxTurnRate().modifyPercent(id, MANEUVER_BONUS);

        // 航速等级加成
        stats.getMaxBurnLevel().modifyFlat(id, BURN_LEVEL_BONUS + (sMod ? SMOD_BURN_LEVEL_BONUS : 0));

        // 地面支援加成
        stats.getDynamic().getMod(Stats.FLEET_GROUND_SUPPORT).modifyFlat(id, GROUND_SUPPORT_BONUS);

        // S-Mod额外效果
        if (sMod) {
            // 零幅能加速效果
            stats.getDynamic().getStat(Stats.ZERO_FLUX_BOOST_TURN_RATE_BONUS_MULT).modifyMult(id,
                    SMOD_ZERO_FLUX_TURN_MULT);
            stats.getZeroFluxSpeedBoost().modifyFlat(id, SMOD_ZERO_FLUX_SPEED_BONUS);
            stats.getAcceleration().modifyPercent(id, SMOD_MANEUVER_BONUS);
            stats.getDeceleration().modifyPercent(id, SMOD_MANEUVER_BONUS);
            stats.getTurnAcceleration().modifyPercent(id, SMOD_MANEUVER_BONUS);
            stats.getMaxTurnRate().modifyPercent(id, SMOD_MANEUVER_BONUS);
            stats.getDynamic().getMod(Stats.FLEET_GROUND_SUPPORT).modifyFlat(id, SMOD_GROUND_SUPPORT_BONUS);
        }

		for (String hullmMod : UNAPPLICABLE) {
			if (stats.getVariant().hasHullMod(hullmMod)) {
				MagicIncompatibleHullmods.removeHullmodWithWarning(stats.getVariant(), hullmMod, spec.getId());
			}
		}
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        // 参数0: 机动性加成
        if (index == 0)
            return "" + (int) MANEUVER_BONUS + "%";
        // 参数1: 航速等级加成
        if (index == 1)
            return "" + BURN_LEVEL_BONUS;
        // 参数2: 地面支援加成
        if (index == 2)
            return "" + (int) GROUND_SUPPORT_BONUS;
        return null;
    }

    @Override
    public String getSModDescriptionParam(int index, HullSize hullSize) {
        // S-Mod参数0: 额外航速等级
        if (index == 0)
            return "+" + SMOD_BURN_LEVEL_BONUS;
        // S-Mod参数1: 零幅能速度加成
        if (index == 1)
            return "" + (int) SMOD_ZERO_FLUX_SPEED_BONUS;
        // S-Mod参数2: 零幅能转向倍率描述
        if (index == 2)
            return Moci_TextLoader.getText(TEXT_ID, "description.smod_param_zero_flux_turn_mult");
        // S-Mod参数2: 额外机动性
        if (index == 3)
            return "+" + (int) SMOD_MANEUVER_BONUS;
        // S-Mod参数2: 额外地面支援
        if (index == 4)
            return "+" + (int) SMOD_GROUND_SUPPORT_BONUS;
        return null;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        
        // 修复D-hull问题：获取基础船体ID
        String hullId = ship.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = ship.getHullSpec().getHullId();
        }
        
        // 检查是否为允许的舰船ID
        return ALLOWED_HULL_IDS.contains(hullId);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        
        // 修复D-hull问题：获取基础船体ID
        String hullId = ship.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = ship.getHullSpec().getHullId();
        }
        
        if (!ALLOWED_HULL_IDS.contains(hullId)) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_allowed_hulls");
        }
        
        return null;
    }
}
