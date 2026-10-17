package data.scripts.weapons;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 扩展版的隐藏武器效果，除了同步 source/follow 之外还负责根据命名规则切帧隐藏腰部/backpack/翼部装甲。
 */
public class Moci_HiddenArmorWingWeaponEffect extends Moci_HiddenWeapon {
    private boolean armorInit = false;
    private ArmorSlotFamily group = ArmorSlotFamily.UNKNOWN;
    private final List<WeaponAPI> armorTargets = new ArrayList<>();
    private Boolean lastArmorHiddenState = null;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        super.advance(amount, engine, weapon);

        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || ship.isHulk() || !ship.isAlive()) {
            return;
        }

        if (!armorInit) {
            armorInit = true;
            group = detectGroup(weapon);
            if (group != ArmorSlotFamily.UNKNOWN) {
                populateArmorTargets(ship, weapon);
            } else {
                Global.getLogger(getClass()).info("未识别的槽位组：" + weapon.getSlot().getId());
            }
        }

        syncArmorFrames();
    }

    private void syncArmorFrames() {
        if (armorTargets.isEmpty()) {
            return;
        }

        boolean shouldHide = hasWeaponInstalled;
        if (lastArmorHiddenState != null && lastArmorHiddenState == shouldHide) {
            return;
        }

        lastArmorHiddenState = shouldHide;
        for (WeaponAPI armor : armorTargets) {
            if (armor == null) {
                continue;
            }
            applyFrameForArmor(armor, shouldHide);
        }
    }

    private void applyFrameForArmor(WeaponAPI armor, boolean hide) {
        if (armor.getAnimation() != null && armor.getAnimation().getNumFrames() > 1) {
            int targetFrame = hide ? armor.getAnimation().getNumFrames() - 1 : 0;
            armor.getAnimation().setFrame(targetFrame);
            armor.getAnimation().pause();
        } else if (armor.getAnimation() != null && armor.getAnimation().getNumFrames() == 1) {
            armor.getAnimation().setFrame(0);
            armor.getAnimation().pause();
        } else if (armor.getSprite() != null) {
            armor.getSprite().setAlphaMult(hide ? 0f : 1f);
        }
    }

    private void populateArmorTargets(ShipAPI ship, WeaponAPI controller) {
        List<String> slotIds = getSlotIdsForGroup(group);
        if (slotIds.isEmpty()) {
            return;
        }

        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w == null || w.getSlot() == null) {
                continue;
            }

            String slotId = w.getSlot().getId();
            if (slotId == null) {
                continue;
            }

            for (String target : slotIds) {
                if (target.equalsIgnoreCase(slotId)) {
                    if (w == controller) {
                        continue;
                    }
                    armorTargets.add(w);
                    break;
                }
            }
        }
    }

    private ArmorSlotFamily detectGroup(WeaponAPI weapon) {
        if (weapon == null || weapon.getSlot() == null) {
            return ArmorSlotFamily.UNKNOWN;
        }
        return detectGroup(weapon.getSlot().getId());
    }

    private ArmorSlotFamily detectGroup(String slotId) {
        if (slotId == null) {
            return ArmorSlotFamily.UNKNOWN;
        }

        String upper = slotId.toUpperCase(Locale.ROOT);
        if (upper.contains("BACKPACK_ARMOR") && upper.contains("_L")) {
            return ArmorSlotFamily.BACKPACK_LEFT;
        }
        if (upper.contains("BACKPACK_ARMOR") && upper.contains("_R")) {
            return ArmorSlotFamily.BACKPACK_RIGHT;
        }
        if (upper.contains("BODY_ARMOR") && upper.endsWith("_L")) {
            return ArmorSlotFamily.BODY_LEFT;
        }
        if (upper.contains("BODY_ARMOR") && upper.endsWith("_R")) {
            return ArmorSlotFamily.BODY_RIGHT;
        }
        if (upper.startsWith("SUBWING_LEFT")) {
            return ArmorSlotFamily.SUBWING_LEFT;
        }
        if (upper.startsWith("SUBWING_RIGHT")) {
            return ArmorSlotFamily.SUBWING_RIGHT;
        }
        if (upper.contains("SUBWING_LEFT")) {
            return ArmorSlotFamily.SUBWING_LEFT;
        }
        if (upper.contains("SUBWING_RIGHT")) {
            return ArmorSlotFamily.SUBWING_RIGHT;
        }

        return ArmorSlotFamily.UNKNOWN;
    }

    private List<String> getSlotIdsForGroup(ArmorSlotFamily family) {
        switch (family) {
            case BACKPACK_LEFT:
                return Arrays.asList("BACKPACK_ARMOR_L1", "BACKPACK_ARMOR_L2");
            case BACKPACK_RIGHT:
                return Arrays.asList("BACKPACK_ARMOR_R1", "BACKPACK_ARMOR_R2");
            case BODY_LEFT:
                return Arrays.asList("BODY_ARMOR_L");
            case BODY_RIGHT:
                return Arrays.asList("BODY_ARMOR_R");
            case SUBWING_LEFT:
                return Arrays.asList("SUBWING_LEFT_L", "SUBWING_LEFT_M", "SUBWING_LEFT_S", "SUBWING_LEFT_UNDER");
            case SUBWING_RIGHT:
                return Arrays.asList("SUBWING_RIGHT_L", "SUBWING_RIGHT_M", "SUBWING_RIGHT_S", "SUBWING_RIGHT_UNDER");
            default:
                return Collections.emptyList();
        }
    }

    private enum ArmorSlotFamily {
        BACKPACK_LEFT,
        BACKPACK_RIGHT,
        BODY_LEFT,
        BODY_RIGHT,
        SUBWING_LEFT,
        SUBWING_RIGHT,
        UNKNOWN
    }
}
