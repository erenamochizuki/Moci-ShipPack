package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.apache.log4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Combat-time weapon group manager for variable-frame MS/WR ships.
 *
 * WR weapons are discovered by the WR_ slot prefix. MS weapons are supplied by
 * the hull-specific transform controller so decorative/system slots are not
 * accidentally exposed to weapon groups.
 */
public class Moci_VariableFrameWeaponModeUtil {
    private static final Logger log = Global.getLogger(Moci_VariableFrameWeaponModeUtil.class);
    private static final String CUSTOM_KEY_PREFIX = "MOCI_VARIABLE_FRAME_WEAPON_MODE_";
    private static final String WR_SLOT_PREFIX = "WR_";
    private static final Color TRANSPARENT = new Color(0f, 0f, 0f, 0f);

    public static void applyWeaponMode(ShipAPI ship, boolean wrMode, boolean suppressFire, String... msSlotIds) {
        if (ship == null || ship.getAllWeapons() == null) {
            return;
        }

        State state = getState(ship, msSlotIds);
        if (!state.initialized) {
            initialize(ship, state);
        }

        if (state.wrMode == null || state.wrMode.booleanValue() != wrMode) {
            switchGroups(ship, state, wrMode);
            state.wrMode = wrMode;
        }

        applyVisibilityAndFireControl(ship, state, wrMode, suppressFire);
    }

    private static State getState(ShipAPI ship, String... msSlotIds) {
        String key = CUSTOM_KEY_PREFIX + buildMsSlotKey(msSlotIds);
        Object existing = ship.getCustomData().get(key);
        if (existing instanceof State) {
            return (State) existing;
        }

        State state = new State(msSlotIds);
        ship.setCustomData(key, state);
        return state;
    }

    private static String buildMsSlotKey(String... msSlotIds) {
        if (msSlotIds == null || msSlotIds.length == 0) {
            return "EMPTY";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < msSlotIds.length; i++) {
            if (i > 0) {
                builder.append('_');
            }
            builder.append(msSlotIds[i]);
        }
        return builder.toString();
    }

    private static void initialize(ShipAPI ship, State state) {
        state.initialized = true;
        List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();

        for (WeaponAPI weapon : ship.getAllWeapons()) {
            String slotId = getSlotId(weapon);
            if (slotId == null) {
                continue;
            }

            captureVisualState(state, weapon);

            if (isWrSlot(slotId)) {
                state.wrSlots.add(slotId);
            } else if (isFollowSlot(slotId)) {
                state.msSlotSet.add(slotId);
            }

            int groupIndex = findGroupIndex(groups, weapon);
            if (groupIndex >= 0) {
                state.initialGroupIndexBySlot.put(slotId, groupIndex);
            }
        }

        for (String wrSlotId : state.wrSlots) {
            int groupIndex = findWrGroupIndex(state, wrSlotId, groups);
            state.wrGroupIndexBySlot.put(wrSlotId, groupIndex);
        }
    }

    private static int findWrGroupIndex(State state, String wrSlotId, List<WeaponGroupAPI> groups) {
        int ownGroup = getInitialGroupIndex(state, wrSlotId);
        if (ownGroup >= 0) {
            return ownGroup;
        }

        if (wrSlotId.contains("MAINGUN")) {
            int headGroup = firstInitialGroupIndex(state, "HEAD_BEAM", "HEAD_SOURCE");
            if (headGroup >= 0) {
                return headGroup;
            }
        }

        if (wrSlotId.contains("LEFT")) {
            int leftGroup = firstInitialGroupIndex(state, "ARM_L_FOLLOW", "ARM_L_SOURCE");
            if (leftGroup >= 0) {
                return leftGroup;
            }
        }

        if (wrSlotId.contains("RIGHT")) {
            int rightGroup = firstInitialGroupIndex(state, "ARM_R_SOURCE");
            if (rightGroup >= 0) {
                return rightGroup;
            }
        }

        int msGroup = firstInitialGroupIndex(state, state.msSlotIds);
        if (msGroup >= 0) {
            return msGroup;
        }

        return groups == null || groups.isEmpty() ? -1 : 0;
    }

    private static int firstInitialGroupIndex(State state, String... slotIds) {
        if (slotIds == null) {
            return -1;
        }

        for (String slotId : slotIds) {
            int groupIndex = getInitialGroupIndex(state, slotId);
            if (groupIndex >= 0) {
                return groupIndex;
            }
        }
        return -1;
    }

    private static int getInitialGroupIndex(State state, String slotId) {
        Integer groupIndex = state.initialGroupIndexBySlot.get(slotId);
        return groupIndex == null ? -1 : groupIndex.intValue();
    }

    private static void switchGroups(ShipAPI ship, State state, boolean wrMode) {
        List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
        Map<String, WeaponAPI> weaponsBySlot = mapWeaponsBySlot(ship);
        log.info("VariableFrame weapon mode switch: ship=" + getShipName(ship)
                + ", targetMode=" + (wrMode ? "WR" : "MS")
                + ", groups=" + (groups == null ? 0 : groups.size()));

        if (wrMode) {
            addWrWeaponsToGroups(ship, groups, state, weaponsBySlot);
            logInactiveSlotWeapons(ship, state.msSlotSet, weaponsBySlot, "MS disabled in WR");
        } else {
            addMsWeaponsToGroups(ship, groups, state, weaponsBySlot);
            logInactiveSlotWeapons(ship, state.wrSlots, weaponsBySlot, "WR disabled in MS");
        }
    }

    private static void addWrWeaponsToGroups(ShipAPI ship, List<WeaponGroupAPI> groups, State state, Map<String, WeaponAPI> weaponsBySlot) {
        for (String slotId : state.wrSlots) {
            Integer groupIndex = state.wrGroupIndexBySlot.get(slotId);
            addWeaponToGroup(ship, groups, weaponsBySlot.get(slotId),
                    groupIndex == null ? -1 : groupIndex.intValue(),
                    "MS->WR");
        }
    }

    private static void addMsWeaponsToGroups(ShipAPI ship, List<WeaponGroupAPI> groups, State state, Map<String, WeaponAPI> weaponsBySlot) {
        for (String slotId : state.msSlotSet) {
            Integer groupIndex = state.initialGroupIndexBySlot.get(slotId);
            if (groupIndex != null) {
                addWeaponToGroup(ship, groups, weaponsBySlot.get(slotId),
                        groupIndex.intValue(),
                        "WR->MS");
            }
        }
    }

    private static void addWeaponToGroup(ShipAPI ship, List<WeaponGroupAPI> groups, WeaponAPI weapon,
                                         int groupIndex, String reason) {
        if (groups == null || weapon == null || groupIndex < 0 || groupIndex >= groups.size()) {
            log.info("VariableFrame add skipped: ship=" + getShipName(ship)
                    + ", reason=" + reason
                    + ", weapon=" + describeWeapon(weapon)
                    + ", groupIndex=" + groupIndex
                    + ", groupCount=" + (groups == null ? 0 : groups.size()));
            return;
        }

        WeaponGroupAPI group = groups.get(groupIndex);
        if (group != null && !group.getWeaponsCopy().contains(weapon)) {
            group.addWeaponAPI(weapon);
            log.info("VariableFrame add weapon: ship=" + getShipName(ship)
                    + ", reason=" + reason
                    + ", weapon=" + describeWeapon(weapon)
                    + ", groupIndex=" + groupIndex
                    + ", groupSize=" + group.getWeaponsCopy().size());
        } else {
            log.info("VariableFrame add skipped already present: ship=" + getShipName(ship)
                    + ", reason=" + reason
                    + ", weapon=" + describeWeapon(weapon)
                    + ", groupIndex=" + groupIndex);
        }
    }

    private static void logInactiveSlotWeapons(ShipAPI ship, Iterable<String> slotIds, Map<String, WeaponAPI> weaponsBySlot, String reason) {
        if (slotIds == null) {
            return;
        }

        for (String slotId : slotIds) {
            logInactiveSlotWeapon(ship, slotId, weaponsBySlot, reason);
        }
    }

    private static void logInactiveSlotWeapon(ShipAPI ship, String slotId, Map<String, WeaponAPI> weaponsBySlot, String reason) {
        WeaponAPI weapon = weaponsBySlot.get(slotId);
        WeaponGroupAPI group = weapon == null ? null : ship.getWeaponGroupFor(weapon);
        if (weapon != null && group != null) {
            List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
            int groupIndex = groups.indexOf(group);
            log.info("VariableFrame inactive weapon retained: ship=" + getShipName(ship)
                    + ", reason=" + reason
                    + ", weapon=" + describeWeapon(weapon)
                    + ", groupIndex=" + groupIndex
                    + ", groupSize=" + group.getWeaponsCopy().size());
        } else {
            log.info("VariableFrame inactive retain skipped: ship=" + getShipName(ship)
                    + ", reason=" + reason
                    + ", slot=" + slotId
                    + ", weapon=" + describeWeapon(weapon));
        }
    }

    private static void applyVisibilityAndFireControl(ShipAPI ship, State state, boolean wrMode, boolean suppressFire) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            String slotId = getSlotId(weapon);
            if (slotId == null) {
                continue;
            }

            boolean wrWeapon = isWrSlot(slotId);
            boolean msWeapon = state.msSlotSet.contains(slotId);
            if (!wrWeapon && !msWeapon) {
                continue;
            }

            boolean active = wrMode ? wrWeapon : msWeapon;
            if (wrWeapon) {
                hideWeaponVisuals(weapon);
            }
            if (!active || suppressFire) {
                weapon.setForceNoFireOneFrame(true);
            }
        }
    }

    private static Map<String, WeaponAPI> mapWeaponsBySlot(ShipAPI ship) {
        Map<String, WeaponAPI> result = new HashMap<String, WeaponAPI>();
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            String slotId = getSlotId(weapon);
            if (slotId != null) {
                result.put(slotId, weapon);
            }
        }
        return result;
    }

    private static int findGroupIndex(List<WeaponGroupAPI> groups, WeaponAPI weapon) {
        if (groups == null || weapon == null) {
            return -1;
        }

        for (int i = 0; i < groups.size(); i++) {
            WeaponGroupAPI group = groups.get(i);
            if (group != null && group.getWeaponsCopy().contains(weapon)) {
                return i;
            }
        }
        return -1;
    }

    private static int findGroupIndex(List<WeaponGroupAPI> groups, WeaponGroupAPI checked) {
        if (groups == null || checked == null) {
            return -1;
        }
        return groups.indexOf(checked);
    }

    private static String getSlotId(WeaponAPI weapon) {
        if (weapon == null || weapon.getSlot() == null) {
            return null;
        }
        return weapon.getSlot().getId();
    }

    private static boolean isWrSlot(String slotId) {
        return slotId != null && slotId.startsWith(WR_SLOT_PREFIX);
    }

    private static boolean isFollowSlot(String slotId) {
        return slotId != null && slotId.endsWith("_FOLLOW");
    }

    private static String getShipName(ShipAPI ship) {
        if (ship == null) {
            return "null";
        }
        if (ship.getHullSpec() != null && ship.getHullSpec().getHullId() != null) {
            return ship.getName() + "/" + ship.getHullSpec().getHullId();
        }
        return ship.getName();
    }

    private static String describeWeapon(WeaponAPI weapon) {
        if (weapon == null) {
            return "null";
        }
        return weapon.getId() + "@" + getSlotId(weapon);
    }

    private static void captureVisualState(State state, WeaponAPI weapon) {
        String slotId = getSlotId(weapon);
        if (slotId == null || state.visualBySlot.containsKey(slotId)) {
            return;
        }

        VisualState visual = new VisualState();
        visual.main = captureLayer(weapon.getSprite());
        visual.barrel = captureLayer(weapon.getBarrelSpriteAPI());
        visual.under = captureLayer(weapon.getUnderSpriteAPI());
        visual.glow = captureLayer(weapon.getGlowSpriteAPI());
        captureMissileRenderData(weapon, visual);
        state.visualBySlot.put(slotId, visual);
    }

    private static SpriteLayerState captureLayer(SpriteAPI sprite) {
        if (sprite == null) {
            return null;
        }

        SpriteLayerState layer = new SpriteLayerState();
        layer.color = sprite.getColor();
        layer.alpha = sprite.getAlphaMult();
        return layer;
    }

    private static void captureMissileRenderData(WeaponAPI weapon, VisualState visual) {
        if (weapon.getMissileRenderData() == null) {
            return;
        }

        for (MissileRenderDataAPI render : weapon.getMissileRenderData()) {
            if (render == null || render.getSprite() == null) {
                visual.missiles.add(null);
                continue;
            }

            MissileSpriteState missile = new MissileSpriteState();
            missile.width = render.getSprite().getWidth();
            missile.height = render.getSprite().getHeight();
            visual.missiles.add(missile);
        }
    }

    private static void hideWeaponVisuals(WeaponAPI weapon) {
        hideLayer(weapon.getSprite());
        hideLayer(weapon.getBarrelSpriteAPI());
        hideLayer(weapon.getUnderSpriteAPI());
        hideLayer(weapon.getGlowSpriteAPI());
        hideMissileRenderData(weapon);
    }

    private static void hideLayer(SpriteAPI sprite) {
        if (sprite == null) {
            return;
        }

        sprite.setColor(TRANSPARENT);
        sprite.setAlphaMult(0f);
    }

    private static void hideMissileRenderData(WeaponAPI weapon) {
        if (weapon.getMissileRenderData() == null) {
            return;
        }

        for (MissileRenderDataAPI render : weapon.getMissileRenderData()) {
            if (render != null && render.getSprite() != null) {
                render.getSprite().setWidth(0f);
                render.getSprite().setHeight(0f);
            }
        }
    }

    private static class State {
        private final String[] msSlotIds;
        private final Set<String> msSlotSet = new HashSet<String>();
        private final List<String> wrSlots = new ArrayList<String>();
        private final Map<String, Integer> initialGroupIndexBySlot = new HashMap<String, Integer>();
        private final Map<String, Integer> wrGroupIndexBySlot = new HashMap<String, Integer>();
        private final Map<String, VisualState> visualBySlot = new HashMap<String, VisualState>();
        private boolean initialized = false;
        private Boolean wrMode = null;

        private State(String... msSlotIds) {
            this.msSlotIds = msSlotIds == null ? new String[0] : msSlotIds;
            for (String slotId : this.msSlotIds) {
                this.msSlotSet.add(slotId);
            }
        }
    }

    private static class VisualState {
        private SpriteLayerState main;
        private SpriteLayerState barrel;
        private SpriteLayerState under;
        private SpriteLayerState glow;
        private final List<MissileSpriteState> missiles = new ArrayList<MissileSpriteState>();
    }

    private static class SpriteLayerState {
        private Color color = Color.WHITE;
        private float alpha = 1f;
    }

    private static class MissileSpriteState {
        private float width = 0f;
        private float height = 0f;
    }
}
