package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.FighterOPCostModifier;
import com.fs.starfarer.api.combat.listeners.WeaponOPCostModifier;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import data.scripts.util.Moci_TextLoader;

public class Moci_AdvanceWeaponIntegration extends BaseHullMod implements WeaponOPCostModifier, FighterOPCostModifier {
    private static final String TEXT_ID = "Moci_advance_weaponIntegration";

    // 配置参数
    private static final float OP_CAP = 20f;               // 装配点上限
    //private static final float RECOIL_BONUS = 25f;  // 后坐力增加25%
    // private static final float TURN_PENALTY = 25f; // 武器转速降低25%
    // private static final float ROF_BONUS = 25f; // 武器射速提高25%

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 添加监听器以修改武器OP
        stats.addListener(this);
        
        // 全局负面效果
        // stats.getMaxRecoilMult().modifyMult(id, 1f + (0.01f * RECOIL_BONUS));
        // stats.getRecoilPerShotMult().modifyMult(id, 1f + (0.01f * RECOIL_BONUS));
        // stats.getRecoilDecayMult().modifyMult(id, 1f + (0.01f * RECOIL_BONUS));
        // stats.getWeaponTurnRateBonus().modifyMult(id, 1f - TURN_PENALTY * 0.01f);

        // 全局增益
        //提高所有武器射速
        // stats.getBallisticRoFMult().modifyPercent(id, ROF_BONUS); // 实弹武器射速加成
        // stats.getEnergyRoFMult().modifyPercent(id, ROF_BONUS); // 能量武器射速加成
        // stats.getMissileRoFMult().modifyPercent(id, ROF_BONUS);      // 导弹射速加成
    }

    @Override
    public int getWeaponOPCost(MutableShipStatsAPI stats, WeaponSpecAPI weapon, int currCost) {
        /*
        // 仅处理大型实弹/能量武器
        if (weapon.getSize() == WeaponAPI.WeaponSize.LARGE &&
            (weapon.getType() == WeaponAPI.WeaponType.BALLISTIC ||
             weapon.getType() == WeaponAPI.WeaponType.ENERGY)) {

         */

        // 对所有大型武器生效
        if (weapon.getSize() == WeaponAPI.WeaponSize.LARGE) {
            
            // 应用OP上限逻辑
            if (currCost > OP_CAP) {
                return (int) OP_CAP;
            }
        }
        return currCost; // 其他武器保持原值
    }

    @Override
    public int getFighterOPCost(MutableShipStatsAPI stats, FighterWingSpecAPI fighter, int currCost) {
        // 对舰载机应用OP上限逻辑
        if (currCost > OP_CAP) {
            return (int) OP_CAP;
        }
        return currCost;
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        switch (index) {
            case 0: return "" + (int) OP_CAP;               // OP上限值
            // case 1: return "大型实弹/能量武器";                // 武器类型
            case 1: return Moci_TextLoader.getText(TEXT_ID, "description.param_large_weapons");                // 武器类型
            case 2: return Moci_TextLoader.getText(TEXT_ID, "description.param_fighters");
            case 3: return "" + (int) OP_CAP;               // OP上限值
            // case 4: return "" + (int) RECOIL_BONUS + "%";   // 后坐力增幅
            // case 5: return "" + (int) TURN_PENALTY + "%";   // 转速降幅
            // case 6: return "" + (int) ROF_BONUS + "%";   // 射速降幅
            default: return null;
        }
    }

    @Override
    public boolean affectsOPCosts() {
        return true;
    }
}
