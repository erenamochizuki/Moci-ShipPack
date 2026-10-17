package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 霜岚专用隐藏武器扩展。
 * 推荐命名：
 * 1. 控制器槽位使用 <TARGET>_HIDDEN / <TARGET>_WEAPON / <TARGET>_CTRL。
 * 2. SOURCE/FOLLOW 继续沿用 Moci_HiddenWeapon 的 <controllerSlot>_SOURCE / _FOLLOW。
 * 3. 单槽位默认控制同名装饰槽；SUBWING_LEFT/RIGHT 会自动展开为整组副翼槽位。
 */
public class Moci_HoarShroudDragonGroupHiddenWeaponEffect extends Moci_HiddenWeapon {
    private static final List<String> CONTROLLER_SUFFIXES = Arrays.asList("_HIDDEN", "_WEAPON", "_CTRL");

    private boolean linkedInit = false;
    private final List<WeaponAPI> controlledWeapons = new ArrayList<WeaponAPI>();

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (weapon == null) {
            return;
        }

        super.advance(amount, engine, weapon);

        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        if (!linkedInit) {
            linkedInit = true;
            initializeControlledWeapons(weapon);
        }

        updateControlledWeapons(source != null);
    }

    protected void initializeControlledWeapons(WeaponAPI controller) {
        controlledWeapons.clear();

        if (controller == null || controller.getShip() == null || controller.getSlot() == null) {
            return;
        }

        ShipAPI ship = controller.getShip();
        for (String slotId : getControlledSlotIds(controller.getSlot().getId())) {
            WeaponAPI linkedWeapon = findWeaponBySlot(ship, slotId);
            if (linkedWeapon != null) {
                controlledWeapons.add(linkedWeapon);
            }
        }
    }

    protected List<String> getControlledSlotIds(String controllerSlotId) {
        if (controllerSlotId == null || controllerSlotId.isEmpty()) {
            return Collections.emptyList();
        }

        String groupId = stripControllerSuffix(controllerSlotId);
        if ("SUBWING_LEFT".equals(groupId)) {
            return Arrays.asList(
                    "SUBWING_LEFT_L",
                    "SUBWING_LEFT_M",
                    "SUBWING_LEFT_S",
                    "SUBWING_LEFT_UNDER");
        }
        if ("SUBWING_RIGHT".equals(groupId)) {
            return Arrays.asList(
                    "SUBWING_RIGHT_L",
                    "SUBWING_RIGHT_M",
                    "SUBWING_RIGHT_S",
                    "SUBWING_RIGHT_UNDER");
        }

        return Collections.singletonList(groupId);
    }

    protected void updateControlledWeapons(boolean active) {
        for (WeaponAPI linkedWeapon : controlledWeapons) {
            setControlledWeaponFrame(linkedWeapon, active);
        }
    }

    protected void setControlledWeaponFrame(WeaponAPI linkedWeapon, boolean active) {
        if (linkedWeapon == null) {
            return;
        }

        if (linkedWeapon.getAnimation() != null && linkedWeapon.getAnimation().getNumFrames() > 1) {
            int targetFrame = active ? getBlankFrame(linkedWeapon) : getVisibleFrame(linkedWeapon);
            setSafeFrame(linkedWeapon, targetFrame);
            linkedWeapon.getAnimation().pause();
            applyWeaponAlpha(linkedWeapon, 255);
            return;
        }

        // 单帧装饰件用透明度兜底，避免没有空白帧时无法隐藏。
        applyWeaponAlpha(linkedWeapon, active ? 0 : 255);
    }

    protected int getVisibleFrame(WeaponAPI weapon) {
        return 0;
    }

    protected int getBlankFrame(WeaponAPI weapon) {
        if (weapon == null || weapon.getAnimation() == null) {
            return 0;
        }
        return Math.max(0, weapon.getAnimation().getNumFrames() - 1);
    }

    private void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        int safeFrame = Math.max(0, Math.min(frame, maxFrame));
        weapon.getAnimation().setFrame(safeFrame);
    }

    private void applyWeaponAlpha(WeaponAPI weapon, int alpha) {
        if (weapon == null) {
            return;
        }

        applySpriteAlpha(weapon.getSprite(), alpha);
        applySpriteAlpha(weapon.getBarrelSpriteAPI(), alpha);
        applySpriteAlpha(weapon.getUnderSpriteAPI(), alpha);
        applySpriteAlpha(weapon.getGlowSpriteAPI(), alpha);
    }

    private void applySpriteAlpha(com.fs.starfarer.api.graphics.SpriteAPI sprite, int alpha) {
        if (sprite == null) {
            return;
        }

        Color current = sprite.getColor();
        if (current == null) {
            current = Color.WHITE;
        }

        sprite.setColor(new Color(
                current.getRed(),
                current.getGreen(),
                current.getBlue(),
                Math.max(0, Math.min(255, alpha))));
    }

    private String stripControllerSuffix(String controllerSlotId) {
        String result = controllerSlotId;
        boolean stripped = true;

        while (stripped) {
            stripped = false;
            for (String suffix : CONTROLLER_SUFFIXES) {
                if (result.endsWith(suffix) && result.length() > suffix.length()) {
                    result = result.substring(0, result.length() - suffix.length());
                    stripped = true;
                    break;
                }
            }
        }

        return result;
    }

    private WeaponAPI findWeaponBySlot(ShipAPI ship, String slotId) {
        if (ship == null || slotId == null) {
            return null;
        }

        for (WeaponAPI shipWeapon : ship.getAllWeapons()) {
            if (shipWeapon.getSlot() != null && slotId.equals(shipWeapon.getSlot().getId())) {
                return shipWeapon;
            }
        }

        return null;
    }
}
