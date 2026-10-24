"""Exercise compiled combat loot code with real API interfaces and isolated campaign doubles.

Run after moci.mjs compile/build: python tools/test_combat_loot.py
Only SalvageEntity is replaced, to inspect drop groups without starting the game.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

SALVAGE = r"""
package com.fs.starfarer.api.impl.campaign.rulecmd.salvage;
import java.util.*;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec.DropData;
public class SalvageEntity {
    public static CargoAPI empty;
    public static DropData last;
    public static int calls;
    public static CargoAPI generateSalvage(Random r, float a, float b, float c, float d,
            List<DropData> value, List<DropData> random) {
        calls++;
        last = random.get(0);
        return empty;
    }
}
"""

HARNESS = r"""
import java.lang.reflect.*;
import java.util.*;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.listeners.ListenerManagerAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.SalvageEntity;
import data.scripts.Moci_bounty_plugin;
import data.scripts.impl.Moci_CombatLootListener;

public class CombatLootRegression {
    static final String LOW = "Moci_Vorttear", MID = "Moci_Mournscale", HIGH = "Moci_Dracaryx";
    static final String PROGRESS = "$Moci_CombatLootVorttearProgress";
    static final List<CampaignEventListener> persistent = new ArrayList<>(), temporary = new ArrayList<>();
    static final List<Object> managed = new ArrayList<>();
    static final Map<String, Object> saved = new HashMap<>();
    static int checks, memberId;

    interface Call { Object run(String name, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("toString")) return type.getSimpleName();
            Object result = call.run(m.getName(), a == null ? new Object[0] : a);
            if (result != null || !m.getReturnType().isPrimitive()) return result;
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType() == float.class) return 0f;
            if (m.getReturnType() == double.class) return 0d;
            if (m.getReturnType() == long.class) return 0L;
            if (m.getReturnType() == void.class) return null;
            return 0;
        });
    }
    static MemoryAPI memory(Map<String, Object> data) {
        return proxy(MemoryAPI.class, (n, a) -> {
            if (n.equals("set")) { data.put((String)a[0], a[1]); return null; }
            if (n.equals("get")) return data.get(a[0]);
            if (n.equals("getBoolean")) return Boolean.TRUE.equals(data.get(a[0]));
            if (n.equals("getLong")) return ((Number)data.getOrDefault(a[0], 0L)).longValue();
            return null;
        });
    }
    static void sector() {
        ListenerManagerAPI manager = proxy(ListenerManagerAPI.class, (n, a) -> {
            if (n.equals("getListeners")) {
                List<Object> found = new ArrayList<>();
                for (Object o : managed) if (((Class<?>)a[0]).isInstance(o)) found.add(o);
                return found;
            }
            if (n.equals("removeListener")) managed.remove(a[0]);
            return null;
        });
        MemoryAPI memory = memory(saved);
        Global.setSector(proxy(SectorAPI.class, (n, a) -> {
            if (n.equals("getMemoryWithoutUpdate")) return memory;
            if (n.equals("getListenerManager")) return manager;
            if (n.equals("getAllListeners")) {
                List<CampaignEventListener> all = new ArrayList<>(persistent);
                all.addAll(temporary);
                return all;
            }
            if (n.equals("removeListener")) { persistent.remove(a[0]); temporary.remove(a[0]); }
            if (n.equals("addListener")) persistent.add((CampaignEventListener)a[0]);
            if (n.equals("addTransientListener")) temporary.add((CampaignEventListener)a[0]);
            return null;
        }));
    }
    static class Cargo {
        Map<String, Float> amounts = new HashMap<>();
        int cores;
        CargoAPI api = proxy(CargoAPI.class, (n, a) -> {
            if (n.equals("addCommodity")) amounts.merge((String)a[0], ((Number)a[1]).floatValue(), Float::sum);
            if (n.equals("removeCommodity")) amounts.merge((String)a[0], -((Number)a[1]).floatValue(), Float::sum);
            if (n.equals("getCommodityQuantity")) return amount((String)a[0]);
            if (n.equals("addItems")) cores += ((Number)a[2]).intValue();
            if (n.equals("removeItems")) cores -= ((Number)a[2]).intValue();
            if (n.equals("getQuantity")) return (float)cores;
            return null;
        });
        float amount(String id) { return amounts.getOrDefault(id, 0f); }
    }
    static FleetMemberAPI member(int fp, HullSize size, boolean fighter) {
        String id = "member-" + ++memberId;
        ShipHullSpecAPI hull = proxy(ShipHullSpecAPI.class, (n, a) -> n.equals("getHullSize") ? size : null);
        return proxy(FleetMemberAPI.class, (n, a) -> {
            if (n.equals("getId")) return id;
            if (n.equals("getFleetPointCost")) return fp;
            if (n.equals("getHullSpec")) return hull;
            if (n.equals("isFighterWing")) return fighter;
            return null;
        });
    }
    static CampaignFleetAPI fleet(String faction, List<FleetMemberAPI> lost,
            List<FleetMemberAPI> survivors, boolean vortex) {
        ArrayList<FleetMemberAPI> snapshot = new ArrayList<>(survivors);
        snapshot.addAll(lost);
        FleetDataAPI data = proxy(FleetDataAPI.class, (n, a) -> {
            if (n.equals("getSnapshot")) return snapshot;
            if (n.equals("getMembersListCopy")) return new ArrayList<>(survivors);
            return null;
        });
        Map<String, Object> values = new HashMap<>();
        values.put(MemFlags.SALVAGE_SEED, 12345L);
        values.put("$Moci_GachaMobFleet", vortex);
        MemoryAPI memory = memory(values);
        FactionAPI side = proxy(FactionAPI.class, (n, a) -> n.equals("getId") ? faction : null);
        return proxy(CampaignFleetAPI.class, (n, a) -> {
            if (n.equals("getFleetData")) return data;
            if (n.equals("getMemoryWithoutUpdate")) return memory;
            if (n.equals("getFaction")) return side;
            if (n.equals("getFleetPoints")) return survivors.stream().mapToInt(FleetMemberAPI::getFleetPointCost).sum();
            return null;
        });
    }
    static CampaignFleetAPI fleet(String faction, int lostFP, int survivingFP) {
        return fleet(faction, List.of(member(lostFP, HullSize.CRUISER, false)),
            survivingFP == 0 ? List.of() : List.of(member(survivingFP, HullSize.CAPITAL_SHIP, false)), false);
    }
    static FleetEncounterContextPlugin encounter(float contribution, boolean won, boolean player, CampaignFleetAPI... enemies) {
        CampaignFleetAPI winner = proxy(CampaignFleetAPI.class, (n, a) -> null);
        BattleAPI battle = proxy(BattleAPI.class, (n, a) -> {
            if (n.equals("isPlayerInvolved")) return player;
            if (n.equals("onPlayerSide")) return won;
            if (n.equals("getNonPlayerSideSnapshot")) return Arrays.asList(enemies);
            return null;
        });
        return proxy(FleetEncounterContextPlugin.class, (n, a) -> {
            if (n.equals("getBattle")) return battle;
            if (n.equals("getWinner")) return winner;
            if (n.equals("computePlayerContribFraction")) return contribution;
            return null;
        });
    }
    static Cargo reward(Moci_CombatLootListener listener, float contribution, CampaignFleetAPI... fleets) {
        Cargo cargo = new Cargo();
        listener.reportEncounterLootGenerated(encounter(contribution, true, true, fleets), cargo.api);
        return cargo;
    }
    static void eq(double expected, double actual, String label) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.00001)
            throw new AssertionError(label + ": " + actual + " != " + expected);
    }
    static void registration() throws Exception {
        persistent.add(new Moci_CombatLootListener());
        persistent.add(new Moci_CombatLootListener());
        CampaignEventListener foreign = new BaseCampaignEventListener(false);
        persistent.add(foreign);
        managed.add(new Moci_CombatLootListener());
        Moci_bounty_plugin plugin = new Moci_bounty_plugin();
        Method register = Moci_bounty_plugin.class.getDeclaredMethod("registerCombatLootListener");
        register.setAccessible(true);
        register.invoke(plugin);
        register.invoke(plugin);
        eq(1, persistent.size(), "remove legacy listeners but preserve foreign");
        eq(1, temporary.size(), "registration is idempotent");
        eq(0, managed.size(), "remove alternate manager legacy listeners");
        temporary.clear(); // Engine discards transient listeners when loading a save.
        register.invoke(plugin);
        eq(1, temporary.size(), "load restores exactly one transient listener");
    }
    static void battleRewards() {
        Moci_CombatLootListener listener = new Moci_CombatLootListener();
        saved.clear();
        eq(2, reward(listener, 1, fleet("pirates", 100, 0)).amount(LOW), "full wipe 100 FP");
        eq(2, reward(listener, 1, fleet("pirates", 100, 1000)).amount(LOW), "survivors do not inflate rewards");
        eq(4, reward(listener, 1, fleet("pirates", 200, 0)).amount(LOW), "larger wipe pays more");
        eq(0, reward(listener, 1, fleet("pirates", 0, 1000)).amount(LOW), "zero losses pays nothing");
        eq(0, reward(listener, 1, fleet("luddic_church", 100, 0)).amount(LOW), "unlisted faction pays nothing");
        eq(0, reward(listener, 0, fleet("pirates", 100, 0)).amount(LOW), "zero contribution pays nothing");
        eq(0, reward(listener, 1, fleet("pirates", List.of(member(100, HullSize.FIGHTER, true)), List.of(), false)).amount(LOW), "fighter wings excluded");
        saved.clear();
        eq(0, reward(listener, 0.25f, fleet("pirates", 100, 0)).amount(LOW), "half pearl carried");
        listener = new Moci_CombatLootListener(); // New listener after load, same persistent sector memory.
        eq(1, reward(listener, 0.25f, fleet("pirates", 100, 0)).amount(LOW), "carry survives listener replacement");
        saved.clear();
        eq(1, reward(listener, 1, fleet("pirates", 25, 0), fleet("pirates", 25, 0)).amount(LOW), "multiple enemy fleets accumulate");
        saved.clear();
        Cargo duplicate = new Cargo();
        FleetEncounterContextPlugin context = encounter(1, true, true, fleet("pirates", 125, 0));
        listener.reportEncounterLootGenerated(context, duplicate.api);
        listener.reportEncounterLootGenerated(context, duplicate.api);
        eq(2, duplicate.amount(LOW), "repeat callback does not duplicate items");
        eq(0.5, ((Number)saved.get(PROGRESS)).doubleValue(), "repeat callback does not duplicate progress");
        Cargo defeat = new Cargo();
        defeat.amounts.put(LOW, 3f);
        defeat.amounts.put("supplies", 10f);
        defeat.cores = 1;
        listener.reportEncounterLootGenerated(encounter(1, false, true, fleet("pirates", 100, 0)), defeat.api);
        eq(0, defeat.amount(LOW), "defeat removes legacy pearl drops");
        eq(0, defeat.cores, "defeat removes legacy core drops");
        eq(10, defeat.amount("supplies"), "defeat preserves unrelated loot");
        Cargo npc = new Cargo();
        listener.reportEncounterLootGenerated(encounter(1, true, false, fleet("pirates", 100, 0)), npc.api);
        eq(0, npc.amount(LOW), "NPC-only battle pays nothing");
        saved.clear();
        float total = 0;
        for (int i = 0; i < 10; i++) total += reward(listener, 1, fleet("pirates", 100, 0)).amount(LOW);
        eq(20, total, "ten 100-FP pirate victories fund a draw");
        saved.clear();
        eq(5, reward(listener, 1, fleet("luddic_path", 200, 0)).amount(LOW), "path low pearl rate");
        eq(3, reward(listener, 1, fleet("remnant", 200, 0)).amount(LOW), "remnant low pearl rate");
        eq(2, reward(listener, 1, fleet("derelict", 200, 0)).amount(LOW), "derelict low pearl rate");
        eq(3, reward(listener, 1, fleet("hegemony", 400, 0)).amount(LOW), "hegemony low pearl rate");
        eq(2, reward(listener, 2, fleet("pirates", 100, 0)).amount(LOW), "contribution capped at one");
        eq(0, reward(listener, -1, fleet("pirates", 100, 0)).amount(LOW), "negative contribution pays nothing");
        saved.put(PROGRESS, Double.NaN);
        eq(2, reward(listener, 1, fleet("pirates", 100, 0)).amount(LOW), "invalid saved progress resets");
        saved.clear();
        Cargo large = reward(listener, 1, fleet("remnant", 10000, 0));
        eq(25, large.amount(MID), "large fleets can award multiple middle pearls");
        if (large.amount(HIGH) < 2 || large.amount(HIGH) > 3) throw new AssertionError("rare pearl rounding");
        checks++;
        CampaignFleetAPI sameLosses = fleet("remnant", 2500, 0);
        Cargo first = reward(listener, 1, sameLosses);
        Cargo replay = reward(new Moci_CombatLootListener(), 1, sameLosses);
        eq(first.amount(MID), replay.amount(MID), "same losses reproduce middle pearl result");
        eq(first.amount(HIGH), replay.amount(HIGH), "same losses reproduce rare pearl result");
    }
    static void addedFactionRewards() {
        Moci_CombatLootListener listener = new Moci_CombatLootListener();
        for (String faction : List.of("persean", "independent", "tritachyon")) {
            saved.clear();
            Cargo cargo = reward(listener, 1, fleet(faction, 10000, 0));
            eq(75, cargo.amount(LOW), faction + " matches hegemony low pearl rate");
            eq(5, cargo.amount(MID), faction + " matches hegemony middle pearl rate");
            eq(faction.equals("tritachyon") ? 1 : 0, cargo.amount(HIGH),
                faction + " rare pearl eligibility and rate");
        }
        saved.clear();
        eq(1, reward(listener, 0.5f, fleet("persean", 400, 0)).amount(LOW),
            "new faction rewards scale by contribution");
        eq(2, reward(listener, 1, fleet("independent", 200, 0)).amount(LOW),
            "new factions share fractional progress");
    }
    static void vortexRewards() throws Exception {
        Moci_CombatLootListener listener = new Moci_CombatLootListener();
        SalvageEntity.empty = new Cargo().api;
        List<FleetMemberAPI> capitals = new ArrayList<>();
        for (int i = 0; i < 5; i++) capitals.add(member(50, HullSize.CAPITAL_SHIP, false));
        reward(listener, 1, fleet("Moci_VortexClan", capitals, List.of(), true));
        if (!SalvageEntity.last.group.equals("Moci_pearl3")) throw new AssertionError("full wipe must retain high tier");
        checks++;
        eq(100, SalvageEntity.last.chances, "25 destroyed hull-size points yield 100 high rolls");
        reward(listener, 0.5f, fleet("Moci_VortexClan", capitals.subList(0, 2), capitals.subList(2, 5), true));
        if (!SalvageEntity.last.group.equals("Moci_pearl1")) throw new AssertionError("tier uses losses only");
        checks++;
        eq(75, SalvageEntity.last.chances, "vortex pearls scale by contribution");
        int calls = SalvageEntity.calls;
        reward(listener, 1, fleet("Moci_VortexClan", List.of(), capitals, true));
        eq(calls, SalvageEntity.calls, "no vortex loss produces no salvage");
        Method add = Moci_CombatLootListener.class.getDeclaredMethod("addVortexFleetDrops", List.class,
            float.class, CargoAPI.class, Random.class, float.class, boolean.class);
        add.setAccessible(true);
        Random always = new Random() { public float nextFloat() { return 0f; } };
        Cargo core = new Cargo();
        add.invoke(listener, capitals, 250f, core.api, always, 1f, true);
        add.invoke(listener, capitals, 250f, core.api, always, 1f, false);
        eq(1, core.cores, "core eligibility enforces encounter cap");
    }
    public static void main(String[] args) throws Exception {
        org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.OFF);
        Global.setSettings(proxy(SettingsAPI.class, (n, a) -> {
            if (n.equals("getFloat")) return 1f;
            if (n.equals("getColor")) return java.awt.Color.WHITE;
            return null;
        }));
        sector();
        registration();
        battleRewards();
        addedFactionRewards();
        vortexRewards();
        System.out.println("PASS: " + checks + " combat loot regression checks");
    }
}
"""


def main():
    config_path = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex")) / "starsector-projects/moci.json"
    config = json.loads(config_path.read_text(encoding="utf-8-sig"))
    # The production build uses the same bound JDK; dependencies remain local and read-only.
    java_home = Path(config.get("jdkHome", "C:/Program Files/Java/jdk-17"))
    dependencies = [ROOT / "target/classes", *sorted((ROOT / "lib").glob("*.jar")),
                    *sorted((ROOT / "lib97").glob("*.jar"))]
    with tempfile.TemporaryDirectory(prefix="moci-loot-") as folder:
        work = Path(folder)
        (work / "CombatLootRegression.java").write_text(HARNESS, encoding="utf-8")
        (work / "SalvageEntity.java").write_text(SALVAGE, encoding="utf-8")
        classpath = os.pathsep.join(map(str, [work, *dependencies]))
        subprocess.run([str(java_home / "bin/javac.exe"), "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(work), str(work / "SalvageEntity.java"),
                        str(work / "CombatLootRegression.java")], check=True, cwd=ROOT)
        subprocess.run([str(java_home / "bin/java.exe"), "-cp", classpath,
                        "CombatLootRegression"], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
