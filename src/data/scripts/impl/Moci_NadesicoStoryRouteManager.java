package data.scripts.impl;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

public final class Moci_NadesicoStoryRouteManager {
    private static final String KEY_INIT = "$moci_nadesico_story_route_initialized";
    private static final String KEY_HAS_A = "$moci_nadesico_route_has_a_start";
    private static final String KEY_HAS_B = "$moci_nadesico_route_has_b_start";
    private static final String KEY_ROUTE = "$moci_nadesico_story_route";

    private static final String ROUTE_A_TO_B_TO_TDD = "moci_nadesico_route_a_to_b_to_tdd";
    private static final String ROUTE_B_TO_TDD = "moci_nadesico_route_b_to_tdd";

    private static final String HULL_A = "Moci_Nadesico_A";
    private static final String HULL_B = "Moci_Nadesico";

    private Moci_NadesicoStoryRouteManager() {
    }

    public static void initializeForNewGame() {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            return;
        }
        MemoryAPI globalMem = Global.getSector().getMemoryWithoutUpdate();
        if (globalMem.getBoolean(KEY_INIT)) {
            return;
        }
        FleetDataAPI data = Global.getSector().getPlayerFleet().getFleetData();
        boolean hasA = hasHull(data, HULL_A);
        boolean hasB = hasHull(data, HULL_B) || hasA;
        String route = hasA ? ROUTE_A_TO_B_TO_TDD : ROUTE_B_TO_TDD;
        globalMem.set(KEY_INIT, true);
        globalMem.set(KEY_HAS_A, hasA);
        globalMem.set(KEY_HAS_B, hasB);
        globalMem.set(KEY_ROUTE, route);
    }

    public static void ensureRouteState() {
        MemoryAPI globalMem = Global.getSector().getMemoryWithoutUpdate();
        if (globalMem.getBoolean(KEY_INIT)) {
            return;
        }
        initializeForNewGame();
    }

    public static boolean hasAStart() {
        return Boolean.TRUE.equals(Global.getSector().getMemoryWithoutUpdate().get(KEY_HAS_A));
    }

    public static boolean hasBStart() {
        return Boolean.TRUE.equals(Global.getSector().getMemoryWithoutUpdate().get(KEY_HAS_B));
    }

    public static String getRoute() {
        return Global.getSector().getMemoryWithoutUpdate().getString(KEY_ROUTE);
    }

    private static boolean hasHull(FleetDataAPI data, String hullId) {
        if (data == null || hullId == null) {
            return false;
        }
        for (FleetMemberAPI member : data.getMembersListCopy()) {
            if (member == null || member.getHullSpec() == null) {
                continue;
            }
            if (hullId.equals(member.getHullSpec().getHullId())) {
                return true;
            }
        }
        return false;
    }
}
