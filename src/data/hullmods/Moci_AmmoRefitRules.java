package data.hullmods;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.jetbrains.annotations.Nullable;

/**
 * 弹药整备相关的统一规则。
 *
 * 这里把“是否触发整备”和“进湾后如何处理弹药”拆成两套明确规则，
 * 避免多个调用点各写一份判断后出现遗漏。
 */
public final class Moci_AmmoRefitRules {

    public static final String TAG_SKIP_REPAIR_BAY_AMMO = "MOCIBIT";
    public static final String TAG_REFILL_DURING_REPAIR_ONLY = "MOCI_AMMO";
    private static final String RX93_HWS_HULLMOD_ID = "Moci_RX93v2HWS";
    private static final String RX93_HWS_BODY_ARMOR_SLOT_ID = "BODY_ARMOR_HWS";
    private static final String RX93_HWS_AUTO_RELOAD_SLOT_LEFT = "WS0001";
    private static final String RX93_HWS_AUTO_RELOAD_SLOT_RIGHT = "WS0002";

    private Moci_AmmoRefitRules() {
    }

    /**
     * 是否带有“完全跳过整备弹药逻辑”的标签。
     * 这类武器既不参与弹药返航判定，也不会在整备时被补给或计入补给时间。
     */
    public static boolean hasAmmoRefitExclusionTag(@Nullable WeaponAPI weapon) {
        return weapon != null
                && weapon.getSpec() != null
                && weapon.getSpec().hasTag(TAG_SKIP_REPAIR_BAY_AMMO);
    }

    /**
     * 是否带有“不会触发返航整备，但进湾后会顺手补满”的标签。
     */
    public static boolean hasRepairRefillOnlyTag(@Nullable WeaponAPI weapon) {
        return weapon != null
                && weapon.getSpec() != null
                && weapon.getSpec().hasTag(TAG_REFILL_DURING_REPAIR_ONLY);
    }

    /**
     * 是否属于“不会触发返航整备，但进湾后会顺手补满”的武器。
     *
     * 除了直接带 MOCI_AMMO 标签的武器外，也包括那些由舰体/模块机制
     * 在战斗中自动补弹的槽位。当前已知例子：
     * - RX93 HWS 在胸甲模块存活时，WS0001 / WS0002 会被自动补弹
     */
    public static boolean isRepairRefillOnlyWeapon(@Nullable WeaponAPI weapon) {
        return hasRepairRefillOnlyTag(weapon)
                || isRx93HwsAutoReloadWeapon(weapon);
    }

    private static boolean isRx93HwsAutoReloadWeapon(@Nullable WeaponAPI weapon) {
        if (weapon == null || weapon.getSlot() == null) {
            return false;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || ship.getVariant() == null) {
            return false;
        }
        if (!ship.getVariant().getHullMods().contains(RX93_HWS_HULLMOD_ID)) {
            return false;
        }

        String slotId = weapon.getSlot().getId();
        if (!RX93_HWS_AUTO_RELOAD_SLOT_LEFT.equals(slotId)
                && !RX93_HWS_AUTO_RELOAD_SLOT_RIGHT.equals(slotId)) {
            return false;
        }

        return isRx93HwsBodyArmorAlive(ship);
    }

    private static boolean isRx93HwsBodyArmorAlive(@Nullable ShipAPI ship) {
        if (ship == null) {
            return false;
        }

        for (ShipAPI module : ship.getChildModulesCopy()) {
            if (module == null || module.getStationSlot() == null) {
                continue;
            }
            if (RX93_HWS_BODY_ARMOR_SLOT_ID.equals(module.getStationSlot().getId())) {
                return module.isAlive() && !module.isHulk();
            }
        }
        return false;
    }

    /**
     * 是否在整备判定中忽略某个内置武器。
     * 保留旧逻辑：即使开启开关，LARGE 内置武器仍然参与判定。
     */
    public static boolean shouldSkipBuiltInWeaponForRefit(@Nullable WeaponAPI weapon,
                                                          boolean skipBuiltInWeaponsForRefit) {
        if (weapon == null || weapon.getSlot() == null || !weapon.getSlot().isBuiltIn()) {
            return false;
        }
        if (!skipBuiltInWeaponsForRefit) {
            return false;
        }
        return weapon.getSize() != WeaponAPI.WeaponSize.LARGE;
    }

    /**
     * 是否属于“会影响弹药返航/整备触发”的有限弹药武器。
     *
     * 规则：
     * - 排除装饰武器
     * - 排除带 MOCIBIT 标签的武器
     * - 排除带 MOCI_AMMO 标签的武器
     * - 必须 usesAmmo()
     * - 必须有最大弹药
     * - 排除会自动回复弹药的武器（ammoPerSecond > 0）
     */
    public static boolean isFiniteAmmoWeapon(@Nullable WeaponAPI weapon) {
        return weapon != null
                && !weapon.isDecorative()
                && !hasAmmoRefitExclusionTag(weapon)
                && !isRepairRefillOnlyWeapon(weapon)
                && weapon.usesAmmo()
                && weapon.getAmmoPerSecond() <= 0f
                && weapon.getMaxAmmo() > 0;
    }

    /**
     * 是否参与“是否需要因为弹药返航整备”的判定。
     */
    public static boolean isRelevantForAmmoRefitTrigger(@Nullable WeaponAPI weapon,
                                                        boolean skipBuiltInWeaponsForRefit) {
        return isFiniteAmmoWeapon(weapon)
                && !shouldSkipBuiltInWeaponForRefit(weapon, skipBuiltInWeaponsForRefit);
    }

    /**
     * 已经进入整备时，这个武器是否应该被直接补满。
     *
     * 这里允许自动回复弹药武器和带 MOCI_AMMO 标签的武器也一起补满，
     * 但它们不会反过来触发返航整备。
     */
    public static boolean shouldRefillAmmoDuringRepair(@Nullable WeaponAPI weapon) {
        return weapon != null
                && weapon.getSpec() != null
                && !weapon.isDecorative()
                && !hasAmmoRefitExclusionTag(weapon)
                && weapon.usesAmmo()
                && weapon.getAmmo() < weapon.getMaxAmmo();
    }

    /**
     * 已经进入整备时，这个武器是否应该额外增加整备耗时。
     *
     * 自动回复弹药的武器和带 MOCI_AMMO 标签的武器会被顺手补满，
     * 但都不额外增加整备时间。
     */
    public static boolean shouldCountAmmoRefitTime(@Nullable WeaponAPI weapon) {
        return shouldRefillAmmoDuringRepair(weapon)
                && !isRepairRefillOnlyWeapon(weapon)
                && weapon.getAmmoPerSecond() <= 0f;
    }
}
