package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;

public class Moci_GespenstWeaponSwap extends BaseHullMod {

    // 武器槽位ID定义
    private static final String LEFT_SLOT_ID = "ARM_L_SOURCE";
    private static final String RIGHT_SLOT_ID = "ARM_R_SOURCE";
    private static final String LEFT_DECO_SLOT_ID = "ARM_L";
    private static final String RIGHT_DECO_SLOT_ID = "ARM_R";

    // 武器选择器映射 (模式名称 -> 武器ID)
    private final Map<String, String> LEFT_WEAPONS = new HashMap<>();
    {
        LEFT_WEAPONS.put("fist", "Moci_Gespenst_left_fist");
        LEFT_WEAPONS.put("gun", "Moci_Gespenst_left_gun");
    }

    private final Map<String, String> RIGHT_WEAPONS = new HashMap<>();
    {
        RIGHT_WEAPONS.put("fist", "Moci_Gespenst_right_fist");
        RIGHT_WEAPONS.put("gun", "Moci_Gespenst_right_gun");
    }

    // 装饰武器选择器映射
    private final Map<String, String> LEFT_DECO_WEAPONS = new HashMap<>();
    {
        LEFT_DECO_WEAPONS.put("fist", "Moci_Gespenst_arm_left_fist");
        LEFT_DECO_WEAPONS.put("gun", "Moci_Gespenst_arm_left_gun");
    }

    private final Map<String, String> RIGHT_DECO_WEAPONS = new HashMap<>();
    {
        RIGHT_DECO_WEAPONS.put("fist", "Moci_Gespenst_arm_right_fist");
        RIGHT_DECO_WEAPONS.put("gun", "Moci_Gespenst_arm_right_gun");
    }

    // 武器模式循环（当前模式 -> 下一模式）
    private final Map<String, String> LOADOUT_CYCLE = new HashMap<>();
    {
        LOADOUT_CYCLE.put("fist", "gun");
        LOADOUT_CYCLE.put("gun", "fist");
    }

    // 模式选择器hull mods
    private final Map<String, String> MODE_SWITCH = new HashMap<>();
    {
        MODE_SWITCH.put("fist", "Moci_Gespenst_left_mode_fist");
        MODE_SWITCH.put("gun", "Moci_Gespenst_left_mode_gun");
    }

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        if (stats.getEntity() == null) return;

        // --- 左臂武器切换逻辑 ---
        boolean switchLeft = true;
        for (String hullmod : MODE_SWITCH.values()) {
            if (stats.getVariant().getHullMods().contains(hullmod)) {
                switchLeft = false;
                break;
            }
        }

        if (switchLeft) {
            // 默认切换到拳模式
            String newMode = "fist";

            // 检测当前武器模式
            String leftWeapon = stats.getVariant().getWeaponId(LEFT_SLOT_ID);
            if (leftWeapon != null) {
                if (leftWeapon.contains("fist")) newMode = "gun";
                else if (leftWeapon.contains("gun")) newMode = "fist";
            }

            // 添加对应模式的hullmod
            stats.getVariant().addMod(MODE_SWITCH.get(newMode));

            // 清空并安装新武器
            stats.getVariant().clearSlot(LEFT_SLOT_ID);
            stats.getVariant().addWeapon(LEFT_SLOT_ID, LEFT_WEAPONS.get(newMode));

            // 清空并安装装饰武器
            stats.getVariant().clearSlot(LEFT_DECO_SLOT_ID);
            stats.getVariant().addWeapon(LEFT_DECO_SLOT_ID, LEFT_DECO_WEAPONS.get(newMode));
        }

        // --- 右臂武器切换逻辑 ---
        // 右臂逻辑与左臂相同，只是使用右臂的槽位和武器ID
        boolean switchRight = true;
        for (String hullmod : MODE_SWITCH.values()) {
            if (stats.getVariant().getHullMods().contains(hullmod.replace("left", "right"))) {
                switchRight = false;
                break;
            }
        }

        if (switchRight) {
            String newMode = "fist";

            String rightWeapon = stats.getVariant().getWeaponId(RIGHT_SLOT_ID);
            if (rightWeapon != null) {
                if (rightWeapon.contains("fist")) newMode = "gun";
                else if (rightWeapon.contains("gun")) newMode = "fist";
            }

            stats.getVariant().addMod(MODE_SWITCH.get(newMode).replace("left", "right"));

            stats.getVariant().clearSlot(RIGHT_SLOT_ID);
            stats.getVariant().addWeapon(RIGHT_SLOT_ID, RIGHT_WEAPONS.get(newMode));

            stats.getVariant().clearSlot(RIGHT_DECO_SLOT_ID);
            stats.getVariant().addWeapon(RIGHT_DECO_SLOT_ID, RIGHT_DECO_WEAPONS.get(newMode));
        }
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship.getOriginalOwner() < 0) {
            if (Global.getSector() != null
                    && Global.getSector().getPlayerFleet() != null
                    && Global.getSector().getPlayerFleet().getCargo() != null) {

                for (CargoStackAPI stack : Global.getSector().getPlayerFleet().getCargo().getStacksCopy()) {
                    if (stack.isWeaponStack()
                            && (stack.getWeaponSpecIfWeapon().getWeaponId().startsWith("Moci_Gespenst_left_") ||
                            stack.getWeaponSpecIfWeapon().getWeaponId().startsWith("Moci_Gespenst_right_") ||
                            stack.getWeaponSpecIfWeapon().getWeaponId().startsWith("Moci_Gespenst_arm_"))) {
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

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship.getHullSpec().getHullId().contains("Moci_Gespenst");
    }
}