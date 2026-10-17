package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import java.util.HashMap;
import java.util.Map;

import data.scripts.util.Moci_TextLoader;

public class Moci_TpcWeaponSwap extends BaseHullMod {
    private static final String TEXT_ID = "Moci_TpcWeaponSwap";

    // 武器槽位ID定义
    private static final String TPC_SLOT_1 = "WS0018";  // 武器槽
    private static final String TPC_SLOT_2 = "WS0019";  // 武器槽
    private static final String TPC_SLOT_3 = "WS0020";  // 武器槽
    private static final String TPC_SLOT_4 = "WS0021";  // 武器槽
    private static final String TPC_SLOT_5 = "WS0022";  // 武器槽
    
    // 武器ID前缀
    private static final String WEAPON_TYPE = "Moci_tpc_";  // 武器前缀

    // 武器模式循环（当前模式 -> 下一模式）
    private static final Map<String, String> LOADOUT_CYCLE = new HashMap<>();
    static {
        LOADOUT_CYCLE.put("cannon", "shotgun");  // 加农炮 -> 霰弹枪
        LOADOUT_CYCLE.put("shotgun", "flamer");      // 霰弹枪 -> 火焰喷射器
        LOADOUT_CYCLE.put("flamer", "cannon");   // 火焰喷射器 -> 加农炮
    }

    /**
     * 舰船创建前处理
     * @param stats 可变舰船状态
     * @param id 插件实例ID
     */
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        if (stats.getEntity() == null) return;

        // 检查是否需要切换武器模式（当没有任何模式hullmod存在时触发）
        boolean switchLoadout = true;
        for (String hullmod : LOADOUT_CYCLE.values()) {
            if (stats.getVariant().getHullMods().contains("Moci_tpc_mode_" + hullmod)) {
                switchLoadout = false;
                break;
            }
        }

        if (switchLoadout) {
            // 默认切换到加农炮模式（当没有检测到任何现有武器时）
            String newWeapon = "cannon";
            
            // 遍历检测当前武器模式
            for (String currentMode : LOADOUT_CYCLE.keySet()) {
                // 检查1号武器槽
                String Weapon1 = stats.getVariant().getWeaponId(TPC_SLOT_1);
                if (Weapon1 != null && Weapon1.contains(currentMode)) {
                    newWeapon = LOADOUT_CYCLE.get(currentMode);
                }
                // 检查2号武器槽
                String Weapon2 = stats.getVariant().getWeaponId(TPC_SLOT_2);
                if (Weapon2 != null && Weapon2.contains(currentMode)) {
                    newWeapon = LOADOUT_CYCLE.get(currentMode);
                }
                // 检查3号武器槽
                String Weapon3 = stats.getVariant().getWeaponId(TPC_SLOT_3);
                if (Weapon3 != null && Weapon3.contains(currentMode)) {
                    newWeapon = LOADOUT_CYCLE.get(currentMode);
                }
                // 检查4号武器槽
                String Weapon4 = stats.getVariant().getWeaponId(TPC_SLOT_4);
                if (Weapon4 != null && Weapon4.contains(currentMode)) {
                    newWeapon = LOADOUT_CYCLE.get(currentMode);
                }
                // 检查1号武器槽
                String Weapon5 = stats.getVariant().getWeaponId(TPC_SLOT_5);
                if (Weapon5 != null && Weapon5.contains(currentMode)) {
                    newWeapon = LOADOUT_CYCLE.get(currentMode);
                }
            }

            // 添加对应模式的hullmod
            stats.getVariant().addMod("Moci_tpc_mode_" + newWeapon);
            
            // 清空武器槽并安装新武器
            stats.getVariant().clearSlot(TPC_SLOT_1);
            stats.getVariant().clearSlot(TPC_SLOT_2);
            stats.getVariant().clearSlot(TPC_SLOT_3);
            stats.getVariant().clearSlot(TPC_SLOT_4);
            stats.getVariant().clearSlot(TPC_SLOT_5);
            stats.getVariant().addWeapon(TPC_SLOT_1, WEAPON_TYPE + newWeapon);
            stats.getVariant().addWeapon(TPC_SLOT_2, WEAPON_TYPE + newWeapon);
            stats.getVariant().addWeapon(TPC_SLOT_3, WEAPON_TYPE + newWeapon);
            stats.getVariant().addWeapon(TPC_SLOT_4, WEAPON_TYPE + newWeapon);
            stats.getVariant().addWeapon(TPC_SLOT_5, WEAPON_TYPE + newWeapon);
        }
    }

    /**
     * 舰船创建后处理
     * @param ship 目标舰船
     * @param id 插件实例ID
     */
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship.getOriginalOwner() < 0) {  // 仅处理玩家舰船
            // 确保玩家舰队货物存在
            if (Global.getSector() != null 
                && Global.getSector().getPlayerFleet() != null 
                && Global.getSector().getPlayerFleet().getCargo() != null) {
                
                // 遍历所有货物
                for (CargoStackAPI stack : Global.getSector().getPlayerFleet().getCargo().getStacksCopy()) {
                    if (stack.isWeaponStack() 
                        && stack.getWeaponSpecIfWeapon().getWeaponId().startsWith("Moci_tpc_")) {
                        // 移除所有热脉冲加农炮（防止重复）
                        Global.getSector().getPlayerFleet().getCargo().removeStack(stack);
                    }
                }
            }
        }
    }

    @Override
    public int getDisplayCategoryIndex() {
        return 2;
    }

    // 描述文本参数
    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        return Moci_TextLoader.getText(TEXT_ID, "description.hidden_error");
    }

}
