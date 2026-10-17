package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;

/**
 * 炮塔模式 AI 行为标志物。
 * 只负责展示当前状态，并在本体船插被移除时清理自身。
 */
public class Moci_MechTurretAIModeMark extends BaseHullMod {

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        if (stats == null || stats.getVariant() == null) {
            return;
        }

        ShipVariantAPI variant = stats.getVariant();
        if (!variant.hasHullMod(Moci_MechTurretMode.HULLMOD_ID)) {
            Moci_MechTurretMode.cleanupAIModeMarkers(variant);
            return;
        }

        Moci_MechTurretMode.recordCurrentAIMode(variant);
    }

    @Override
    public int getDisplaySortOrder() {
        return 3000;
    }

    @Override
    public int getDisplayCategoryIndex() {
        return 3;
    }
}
