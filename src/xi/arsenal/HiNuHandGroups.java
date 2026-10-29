package xi.arsenal;

import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.combat.systems.WeaponGroup;
import com.fs.starfarer.combat.systems.o00O;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ship-local native group transaction for the ordinary Hi-Nu hand cannon.
 * Existing weapon instances and installed autofire AI identities are retained.
 * Public WeaponGroupAPI cannot create/reorder native groups or restore an
 * installed AI; that narrow boundary is isolated in Access, as for Magnum.
 */
final class HiNuHandGroups implements AutoCloseable {
    interface Access {
        List<WeaponGroupAPI> groups();
        WeaponGroupAPI create();
        void append(WeaponGroupAPI group, WeaponAPI weapon, AutofireAIPlugin ai);
        void refresh();
        default void restoreAppend(WeaponGroupAPI group, WeaponAPI weapon, AutofireAIPlugin ai) {
            append(group, weapon, ai);
        }
    }

    private record Snapshot(WeaponGroupAPI group, List<WeaponAPI> weapons,
                            List<AutofireAIPlugin> ai, boolean auto, WeaponGroupType type) {}

    private final ShipAPI ship;
    private final Access access;
    private final WeaponAPI held, beam;
    private final List<Snapshot> initial = new ArrayList<>();
    private final List<Snapshot> handOriginals = new ArrayList<>();
    private final Map<WeaponGroupAPI, Snapshot> handWritten = new IdentityHashMap<>();
    private List<WeaponGroupAPI> handOrder = List.of(), expectedOrder = List.of();
    private WeaponGroupAPI cannonGroup;
    private Snapshot cannonWritten;
    private AutofireAIPlugin beamAI;
    private boolean shown, detached, orderConflict, closed;
    private long conflicts, shows, hides, detaches, restores;

    HiNuHandGroups(ShipAPI ship, WeaponAPI held, WeaponAPI beam) {
        this(ship, held, beam, null);
    }

    HiNuHandGroups(ShipAPI ship, WeaponAPI held, WeaponAPI beam, Access access) {
        this.ship = Objects.requireNonNull(ship, "Missing Hi-Nu group ship");
        this.held = held;
        this.beam = Objects.requireNonNull(beam, "Missing Hi-Nu native cannon");
        this.access = access == null ? nativeAccess(ship) : access;
        WeaponGroupAPI selected = ship.getSelectedGroupAPI();
        // Native deployment can automatically group an unlisted built-in.
        // Until the charge is full this particular new weapon has no group.
        for (WeaponGroupAPI group : List.copyOf(this.access.groups())) {
            Snapshot before = snapshot(group);
            initial.add(before);
            for (int i = before.weapons.size() - 1; i >= 0; i--) {
                if (before.weapons.get(i) != beam) continue;
                if (beamAI == null) beamAI = before.ai.get(i);
                group.removeWeapon(i);
            }
            if (!before.weapons.isEmpty() && group.getWeaponsCopy().isEmpty()
                    && identity(before.weapons, beam) >= 0) {
                this.access.groups().remove(group);
            }
        }
        this.access.refresh();
        preserveSelection(selected, true);
        expectedOrder = List.copyOf(this.access.groups());
    }

    /** Reveal the charged skill; actual player selection is a separate event. */
    boolean showCannon() {
        if (closed) return false;
        observeOrder();
        if (shown) return ownedCannon();
        if (cannonGroup != null && (!sameMembersAndAi(cannonGroup, cannonWritten)
                || identity(access.groups(), cannonGroup) >= 0)) return conflict();
        for (WeaponGroupAPI group : access.groups()) {
            if (identity(group.getWeaponsCopy(), beam) >= 0) return conflict();
        }
        if (cannonGroup == null) {
            cannonGroup = access.create();
            access.append(cannonGroup, beam, beamAI);
        }
        // A new reveal starts in a predictable manual mode. During a reveal,
        // ordinary user toggles are allowed and repeated show calls leave them alone.
        cannonGroup.setType(WeaponGroupType.LINKED);
        if (cannonGroup.isAutofiring()) cannonGroup.toggleOff();
        cannonWritten = snapshot(cannonGroup);
        access.groups().add(cannonGroup);
        shown = true;
        shows++;
        expectedOrder = List.copyOf(access.groups());
        access.refresh();
        return true;
    }

    /** Remove only our single-weapon group and keep selection on a live group. */
    boolean hideCannon() {
        if (closed) return false;
        observeOrder();
        if (!shown) return true;
        if (!ownedCannon()) return false;
        WeaponGroupAPI selected = ship.getSelectedGroupAPI();
        if (cannonGroup.isAutofiring()) cannonGroup.toggleOff();
        access.groups().remove(cannonGroup);
        shown = false;
        hides++;
        expectedOrder = List.copyOf(access.groups());
        access.refresh();
        preserveSelection(selected, true);
        return true;
    }

    boolean cannonSelected() {
        return !closed && shown && ship.getSelectedGroupAPI() == cannonGroup
                && identity(access.groups(), cannonGroup) >= 0
                && sameIdentity(cannonGroup.getWeaponsCopy(), List.of(beam));
    }

    /**
     * Snapshot the current hand arrangement at throw time. User edits made
     * before the throw are thus preserved, rather than reset to deployment.
     */
    boolean detachHand() {
        if (closed) return false;
        if (detached) return true;
        WeaponGroupAPI selected = ship.getSelectedGroupAPI();
        handOriginals.clear();
        handWritten.clear();
        handOrder = List.copyOf(access.groups());
        orderConflict = false;
        if (held != null) {
            for (WeaponGroupAPI group : handOrder) {
                Snapshot before = snapshot(group);
                if (identity(before.weapons, held) < 0) continue;
                handOriginals.add(before);
                for (int i = before.weapons.size() - 1; i >= 0; i--) {
                    if (before.weapons.get(i) == held) group.removeWeapon(i);
                }
                handWritten.put(group, snapshot(group));
                if (group.getWeaponsCopy().isEmpty()) access.groups().remove(group);
            }
        }
        detached = true;
        detaches++;
        expectedOrder = List.copyOf(access.groups());
        access.refresh();
        // A removed preceding hand group shifts the native selected index.
        // Re-select the SAME group identity, especially the selected cannon.
        preserveSelection(selected, true);
        return true;
    }

    /** Restore original group objects, member order, AI, type and autofire. */
    boolean restoreHand() {
        if (closed) return false;
        if (!detached) return true;
        observeOrder();
        if (orderConflict) return false;
        for (Snapshot original : handOriginals) {
            Snapshot expected = handWritten.get(original.group);
            if (!sameMembersAndAi(original.group, expected)) return conflict();
            boolean shouldBePresent = !expected.weapons.isEmpty();
            if ((identity(access.groups(), original.group) >= 0) != shouldBePresent) return conflict();
        }
        // An external owner may have re-added the hand weapon elsewhere.
        if (held != null) for (WeaponGroupAPI group : access.groups()) {
            if (identity(group.getWeaponsCopy(), held) >= 0) return conflict();
        }
        WeaponGroupAPI selected = ship.getSelectedGroupAPI();
        for (Snapshot original : handOriginals) {
            // A mixed group remains available while the hand is out. The user
            // may change its firing mode/autofire; restoring the hand must not
            // undo those normal choices or reject pickup because of them.
            boolean stillPresent = identity(access.groups(), original.group) >= 0;
            Snapshot restoration = stillPresent
                    ? new Snapshot(original.group, original.weapons, original.ai,
                                   original.group.isAutofiring(), original.group.getType())
                    : original;
            rewrite(restoration);
            if (identity(access.groups(), original.group) < 0) {
                access.groups().add(restorationIndex(original.group), original.group);
            }
        }
        detached = false;
        restores++;
        handOriginals.clear();
        handWritten.clear();
        handOrder = List.of();
        expectedOrder = List.copyOf(access.groups());
        access.refresh();
        preserveSelection(selected, true);
        return true;
    }

    private int restorationIndex(WeaponGroupAPI group) {
        int oldIndex = identity(handOrder, group);
        for (int i = oldIndex + 1; i < handOrder.size(); i++) {
            int present = identity(access.groups(), handOrder.get(i));
            if (present >= 0) return present;
        }
        // An owned cannon shown later belongs after the original hand groups.
        int cannonIndex = identity(access.groups(), cannonGroup);
        return cannonIndex >= 0 ? cannonIndex : access.groups().size();
    }

    private void rewrite(Snapshot original) {
        for (int i = original.group.getWeaponsCopy().size() - 1; i >= 0; i--) {
            original.group.removeWeapon(i);
        }
        for (int i = 0; i < original.weapons.size(); i++) {
            access.restoreAppend(original.group, original.weapons.get(i), original.ai.get(i));
        }
        original.group.setType(original.type);
        if (original.group.isAutofiring() != original.auto) {
            if (original.auto) original.group.toggleOn(); else original.group.toggleOff();
        }
    }

    private void observeOrder() {
        if (detached && !sameIdentity(access.groups(), expectedOrder) && !orderConflict) {
            orderConflict = true;
            conflicts++;
        }
    }

    private boolean ownedCannon() {
        if (cannonGroup == null || identity(access.groups(), cannonGroup) < 0
                || !sameMembersAndAi(cannonGroup, cannonWritten)) return conflict();
        return true;
    }

    private boolean conflict() { conflicts++; return false; }

    private void preserveSelection(WeaponGroupAPI wanted, boolean fallback) {
        int index = identity(access.groups(), wanted);
        if (index < 0 && fallback && !access.groups().isEmpty()) index = 0;
        if (index >= 0) ship.giveCommand(ShipCommand.SELECT_GROUP, null, index);
        else if (access.groups().isEmpty()) ship.resetSelectedGroup();
    }

    private static Snapshot snapshot(WeaponGroupAPI group) {
        List<WeaponAPI> members = List.copyOf(group.getWeaponsCopy());
        List<AutofireAIPlugin> ai = new ArrayList<>();
        for (WeaponAPI weapon : members) ai.add(group.getAutofirePlugin(weapon));
        return new Snapshot(group, members, Collections.unmodifiableList(ai), group.isAutofiring(), group.getType());
    }

    private static boolean sameMembersAndAi(WeaponGroupAPI group, Snapshot expected) {
        if (expected == null || !sameIdentity(group.getWeaponsCopy(), expected.weapons)) return false;
        for (int i = 0; i < expected.weapons.size(); i++) {
            if (group.getAutofirePlugin(expected.weapons.get(i)) != expected.ai.get(i)) return false;
        }
        return true;
    }

    private static int identity(List<?> list, Object value) {
        for (int i = 0; i < list.size(); i++) if (list.get(i) == value) return i;
        return -1;
    }

    private static boolean sameIdentity(List<?> a, List<?> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    Map<String, Object> status() {
        observeOrder();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cannonShown", shown);
        row.put("cannonSelected", cannonSelected());
        row.put("cannonGroupIndex", identity(access.groups(), cannonGroup));
        row.put("handDetached", detached);
        row.put("groupCount", access.groups().size());
        row.put("initialGroupCount", initial.size());
        row.put("snapshotHandGroupCount", handOriginals.size());
        row.put("groupOwnershipConflicts", conflicts);
        row.put("externalOrderConflict", orderConflict);
        row.put("shows", shows); row.put("hides", hides);
        row.put("detaches", detaches); row.put("restores", restores);
        row.put("closed", closed);
        row.put("nativeBoundary", "Ship.getGroups and WeaponGroup installed-AI restoration; public API lacks these mutations");
        return Collections.unmodifiableMap(row);
    }

    @Override public void close() {
        if (closed) return;
        hideCannon();
        restoreHand();
        closed = true;
    }

    private static Access nativeAccess(ShipAPI ship) {
        if (!(ship instanceof Ship nativeShip)) {
            throw new IllegalArgumentException("Native Hi-Nu ship required for native group transactions");
        }
        return new Access() {
            @Override @SuppressWarnings({"unchecked", "rawtypes"})
            public List<WeaponGroupAPI> groups() { return (List) nativeShip.getGroups(); }
            @Override public WeaponGroupAPI create() {
                WeaponGroup group = new WeaponGroup(); group.setShip(nativeShip); return group;
            }
            @Override public void append(WeaponGroupAPI group, WeaponAPI weapon, AutofireAIPlugin ai) {
                if (ai == null) { group.addWeaponAPI(weapon); return; }
                if (!(group instanceof WeaponGroup nativeGroup) || !(weapon instanceof o00O installed)) {
                    throw new IllegalStateException("Native Hi-Nu group or installed weapon unavailable");
                }
                nativeGroup.getWeapons().add(installed);
                nativeGroup.getAIPlugins().add(ai);
            }
            @Override public void restoreAppend(WeaponGroupAPI group, WeaponAPI weapon, AutofireAIPlugin ai) {
                if (!(group instanceof WeaponGroup nativeGroup) || !(weapon instanceof o00O installed)) {
                    throw new IllegalStateException("Native Hi-Nu group or installed weapon unavailable");
                }
                // A captured null means that no AI was installed; do not repick one.
                nativeGroup.getWeapons().add(installed);
                nativeGroup.getAIPlugins().add(ai);
            }
            @Override public void refresh() { nativeShip.setNeedsWeaponGroupUIUpdate(true); }
        };
    }
}
