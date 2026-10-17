package data.hullmods;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.magiclib.util.MagicIncompatibleHullmods;
import data.scripts.util.Moci_TextLoader;

public class Moci_EmergencyMissileFactory extends BaseHullMod {
    private static final String TEXT_ID = "Moci_emergency_missile_factory";
    public static String DATA_KEY = "emergency_missile_factory_data";
    
    // 不同尺寸导弹的补充周期
    private static final float SMALL_RELOAD_INTERVAL = 10f; // 小型导弹补充周期
    private static final float MEDIUM_RELOAD_INTERVAL = 15f; // 中型导弹补充周期
    private static final float LARGE_RELOAD_INTERVAL = 20f; // 大型导弹补充周期
    
    private static final float MISSILE_AMMO_REDUCTION = 0.4f; // 导弹备弹量减少
    
    // 冲突的船插ID
    public static final Set<String> CONFLICTING_HULLMODS = new HashSet<>();
    static {
        CONFLICTING_HULLMODS.add("missleracks");
    }

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 减少所有导弹武器30%的备弹量
        stats.getMissileAmmoBonus().modifyMult(id, 1f - MISSILE_AMMO_REDUCTION);
        for (String hullmMod : CONFLICTING_HULLMODS) {
            if (stats.getVariant().hasHullMod(hullmMod)) {
                MagicIncompatibleHullmods.removeHullmodWithWarning(stats.getVariant(), hullmMod, spec.getId());
            }
        }
    }
    
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (!ship.isAlive()) return;

        CombatEngineAPI engine = Global.getCombatEngine();
        String key = DATA_KEY + "_" + ship.getId();
        EmergencyMissileFactoryData data = (EmergencyMissileFactoryData)engine.getCustomData().get(key);
        
        if (data == null) {
            data = new EmergencyMissileFactoryData();
            engine.getCustomData().put(key, data);
        }

        // 更新所有间隔计时器
        data.smallInterval.advance(amount);
        data.mediumInterval.advance(amount);
        data.largeInterval.advance(amount);
        
        // 检查小型导弹补充
        if (data.smallInterval.intervalElapsed()) {
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w.getType() == WeaponAPI.WeaponType.MISSILE && w.usesAmmo() && w.getAmmo() < w.getMaxAmmo() 
                        && isSmallWeapon(w)) {
                    w.setAmmo(w.getAmmo() + 1);
                }
            }
        }
        
        // 检查中型导弹补充
        if (data.mediumInterval.intervalElapsed()) {
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w.getType() == WeaponAPI.WeaponType.MISSILE && w.usesAmmo() && w.getAmmo() < w.getMaxAmmo() 
                        && isMediumWeapon(w)) {
                    w.setAmmo(w.getAmmo() + 1);
                }
            }
        }
        
        // 检查大型导弹补充
        if (data.largeInterval.intervalElapsed()) {
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w.getType() == WeaponAPI.WeaponType.MISSILE && w.usesAmmo() && w.getAmmo() < w.getMaxAmmo() 
                        && isLargeWeapon(w)) {
                    w.setAmmo(w.getAmmo() + 1);
                }
            }
        }
    }
    
    // 判断武器尺寸的辅助方法
    private boolean isSmallWeapon(WeaponAPI weapon) {
        return weapon.getSize() == WeaponAPI.WeaponSize.SMALL;
    }
    
    private boolean isMediumWeapon(WeaponAPI weapon) {
        return weapon.getSize() == WeaponAPI.WeaponSize.MEDIUM;
    }
    
    private boolean isLargeWeapon(WeaponAPI weapon) {
        return weapon.getSize() == WeaponAPI.WeaponSize.LARGE;
    }

    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3f;
        float opad = 10f;
        Color h = Misc.getHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();

        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.intro"), opad);

        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.ammo_reduction",
                        Moci_TextLoader.mapOf("ammo_reduction", String.valueOf((int) (MISSILE_AMMO_REDUCTION * 100)))),
                pad,
                bad,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.ammo_reduction_highlights",
                        Moci_TextLoader.mapOf("ammo_reduction", String.valueOf((int) (MISSILE_AMMO_REDUCTION * 100)))).toArray(new String[0])
        );
        tooltip.addPara(
                Moci_TextLoader.getText(TEXT_ID, "description.incompatible"),
                pad,
                h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.incompatible_highlights").toArray(new String[0])
        );
        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.reload_heading"), Alignment.MID, opad);
        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.small_reload",
                        Moci_TextLoader.mapOf("small_interval", String.valueOf((int) SMALL_RELOAD_INTERVAL))),
                opad,
                h,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.small_reload_highlights",
                        Moci_TextLoader.mapOf("small_interval", String.valueOf((int) SMALL_RELOAD_INTERVAL))).toArray(new String[0])
        );
        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.medium_reload",
                        Moci_TextLoader.mapOf("medium_interval", String.valueOf((int) MEDIUM_RELOAD_INTERVAL))),
                pad,
                h,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.medium_reload_highlights",
                        Moci_TextLoader.mapOf("medium_interval", String.valueOf((int) MEDIUM_RELOAD_INTERVAL))).toArray(new String[0])
        );
        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.large_reload",
                        Moci_TextLoader.mapOf("large_interval", String.valueOf((int) LARGE_RELOAD_INTERVAL))),
                pad,
                h,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.large_reload_highlights",
                        Moci_TextLoader.mapOf("large_interval", String.valueOf((int) LARGE_RELOAD_INTERVAL))).toArray(new String[0])
        );
    }
    
    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null) return null;

        if (ship.getVariant().hasHullMod("missleracks"))
                return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_reason");
        return null;
    }
    
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null) return false;
        
        for (String hullmodId : CONFLICTING_HULLMODS) {
            if (ship.getVariant().getHullMods().contains(hullmodId)) {
                return false;
            }
        }
        return true;
    }

    public static class EmergencyMissileFactoryData {
        IntervalUtil smallInterval = new IntervalUtil(SMALL_RELOAD_INTERVAL, SMALL_RELOAD_INTERVAL);
        IntervalUtil mediumInterval = new IntervalUtil(MEDIUM_RELOAD_INTERVAL, MEDIUM_RELOAD_INTERVAL);
        IntervalUtil largeInterval = new IntervalUtil(LARGE_RELOAD_INTERVAL, LARGE_RELOAD_INTERVAL);
    }
}
