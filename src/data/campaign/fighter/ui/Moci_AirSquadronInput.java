package data.campaign.fighter.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Native-button checked-state adapter, dependency-free so its dispatch contract can be checked standalone. */
final class Moci_AirSquadronInput<T> {
    static final class Confirmation<T> {
        private T pending;
        boolean isPending() { return pending != null; }
        boolean request(T action, boolean dirty) {
            if (dirty) { pending = action; return false; }
            return true;
        }
        void keep() { pending = null; }
        T discard() { T result = pending; pending = null; return result; }
    }
    private record Entry<T>(BooleanSupplier checked, Runnable clear, BooleanSupplier enabled, T action) {}
    private final List<Entry<T>> entries = new ArrayList<>();
    void add(BooleanSupplier checked, Runnable clear, BooleanSupplier enabled, T action) {
        entries.add(new Entry<>(checked, clear, enabled, action));
    }
    void clear() { entries.clear(); }
    T poll() {
        T first = null;
        for (Entry<T> entry : entries) {
            if (!entry.checked.getAsBoolean()) continue;
            entry.clear.run();
            if (first == null && entry.enabled.getAsBoolean()) first = entry.action;
        }
        return first;
    }

    public static void main(String[] args) {
        Moci_AirSquadronInput<String> input = new Moci_AirSquadronInput<>();
        boolean[] checked = {false, false, false};
        boolean[] enabled = {true, true, false};
        for (int i = 0; i < checked.length; i++) {
            final int index = i;
            input.add(() -> checked[index], () -> checked[index] = false, () -> enabled[index], "button" + index);
        }
        int checks = 0;
        checked[1] = true;
        if (!"button1".equals(input.poll()) || checked[1]) throw new AssertionError("Second slot did not dispatch/clear");
        checks++;
        if (input.poll() != null) throw new AssertionError("Button repeated without a new click");
        checks++;
        checked[0] = checked[1] = true;
        if (!"button0".equals(input.poll()) || checked[0] || checked[1] || input.poll() != null)
            throw new AssertionError("Multiple clicks dispatched more than one action");
        checks++;
        checked[2] = true;
        if (input.poll() != null || checked[2]) throw new AssertionError("Disabled action dispatched or remained checked");
        checks++;
        for (int i = 0; i < 20; i++) {
            checked[1] = true;
            if (!"button1".equals(input.poll()) || input.poll() != null) throw new AssertionError("Repeated press was duplicated");
            checks++;
        }
        input.clear(); checked[0] = true;
        if (input.poll() != null) throw new AssertionError("Stale button survived rebuild");
        checks++;
        Confirmation<String> confirmation = new Confirmation<>();
        String selectedBay = "first";
        String dirtyDraft = "modified";
        if (confirmation.request("second", true) || !confirmation.isPending()) throw new AssertionError("Dirty bay switched immediately");
        checks++;
        confirmation.keep();
        if (confirmation.isPending() || !"first".equals(selectedBay) || !"modified".equals(dirtyDraft))
            throw new AssertionError("Keep lost the selected bay or draft");
        checks++;
        confirmation.request("second", true);
        selectedBay = confirmation.discard();
        if (!"second".equals(selectedBay) || confirmation.isPending() || confirmation.discard() != null)
            throw new AssertionError("Discard did not release exactly one switch");
        checks++;
        if (!confirmation.request("close", false) || confirmation.isPending()) throw new AssertionError("Clean close blocked");
        checks++;
        System.out.println("Aircraft native-button dispatch checks passed: " + checks + ".");
    }
}
