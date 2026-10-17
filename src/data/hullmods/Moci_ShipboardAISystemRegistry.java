package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一管理舰载AI系统的互斥关系和旧AI类型ID兼容映射。
 */
public final class Moci_ShipboardAISystemRegistry {

    private static final List<String> MANAGED_HULLMOD_IDS = Arrays.asList(
            Moci_System99.HULLMOD_ID,
            Moci_HaroAISystem.HULLMOD_ID,
            Moci_AlphaCoreAISystem.HULLMOD_ID,
            Moci_OmegaCoreAISystem.HULLMOD_ID
    );

    private static final Map<String, String> LEGACY_AI_TYPE_IDS = createLegacyAiTypeMap();

    private Moci_ShipboardAISystemRegistry() {
    }

    public static List<String> getManagedHullmodIds() {
        return new ArrayList<>(MANAGED_HULLMOD_IDS);
    }

    public static String getCanonicalAiTypeId(String aiTypeId) {
        if (aiTypeId == null) {
            return null;
        }
        String trimmed = aiTypeId.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String mapped = LEGACY_AI_TYPE_IDS.get(trimmed);
        return mapped == null ? trimmed : mapped;
    }

    public static boolean hasConflict(ShipAPI ship, String selfHullmodId) {
        return !getConflictingHullmods(ship == null ? null : ship.getVariant(), selfHullmodId).isEmpty();
    }

    public static String getConflictReason(ShipAPI ship, String selfHullmodId) {
        List<String> conflicts = getConflictingHullmods(ship == null ? null : ship.getVariant(), selfHullmodId);
        if (conflicts.isEmpty()) {
            return null;
        }

        List<String> names = new ArrayList<>();
        for (String hullmodId : conflicts) {
            names.add(getHullmodDisplayName(hullmodId));
        }
        return Moci_ShipboardAITextRegistry.formatTemplate(
                Moci_ShipboardAITextRegistry.getUiText("unapplicable_single_ai_system",
                        "只能安装一个舰载AI系统，当前已存在 {names}。"),
                Moci_ShipboardAITextRegistry.mapOf("names", joinNames(names)));
    }

    public static List<String> getConflictingHullmods(ShipVariantAPI variant, String selfHullmodId) {
        List<String> result = new ArrayList<>();
        if (variant == null) {
            return result;
        }

        for (String hullmodId : MANAGED_HULLMOD_IDS) {
            if (hullmodId.equals(selfHullmodId)) {
                continue;
            }
            if (hasHullmod(variant, hullmodId)) {
                result.add(hullmodId);
            }
        }
        return result;
    }

    public static void enforceSingleAISystemInRefit(MutableShipStatsAPI stats, String preferredHullmodId) {
        if (stats == null || stats.getFleetMember() == null) {
            return;
        }
        enforceSingleAISystem(stats.getFleetMember(), preferredHullmodId);
    }

    public static boolean enforceSingleAISystem(FleetMemberAPI member, String preferredHullmodId) {
        if (member == null || member.getVariant() == null) {
            return false;
        }

        ShipVariantAPI current = member.getVariant();
        List<String> present = getPresentManagedHullmods(current);
        if (present.size() <= 1) {
            return false;
        }

        String winner = determineWinner(current, preferredHullmodId, present);
        ShipVariantAPI editable = prepareEditableVariant(member);
        boolean changed = false;

        for (String hullmodId : present) {
            if (hullmodId.equals(winner)) {
                continue;
            }
            if (removeHullmod(editable, hullmodId)) {
                changed = true;
            }
        }

        if (changed && Global.getSector() != null && Global.getSector().getPlayerFleet() != null) {
            Global.getSector().getPlayerFleet().forceSync();
        }
        return changed;
    }

    private static List<String> getPresentManagedHullmods(ShipVariantAPI variant) {
        List<String> result = new ArrayList<>();
        if (variant == null) {
            return result;
        }

        for (String hullmodId : MANAGED_HULLMOD_IDS) {
            if (hasHullmod(variant, hullmodId)) {
                result.add(hullmodId);
            }
        }
        return result;
    }

    private static String determineWinner(ShipVariantAPI variant, String preferredHullmodId, List<String> present) {
        Set<String> builtIns = getBuiltInManagedHullmods(variant);
        if (!builtIns.isEmpty()) {
            if (preferredHullmodId != null && builtIns.contains(preferredHullmodId)) {
                return preferredHullmodId;
            }
            for (String hullmodId : MANAGED_HULLMOD_IDS) {
                if (builtIns.contains(hullmodId)) {
                    return hullmodId;
                }
            }
        }

        if (preferredHullmodId != null && present.contains(preferredHullmodId)) {
            return preferredHullmodId;
        }
        return present.isEmpty() ? preferredHullmodId : present.get(0);
    }

    private static Set<String> getBuiltInManagedHullmods(ShipVariantAPI variant) {
        Set<String> result = new LinkedHashSet<>();
        if (variant == null || variant.getHullSpec() == null || variant.getHullSpec().getBuiltInMods() == null) {
            return result;
        }

        for (String hullmodId : MANAGED_HULLMOD_IDS) {
            if (variant.getHullSpec().getBuiltInMods().contains(hullmodId)) {
                result.add(hullmodId);
            }
        }
        return result;
    }

    private static boolean hasHullmod(ShipVariantAPI variant, String hullmodId) {
        if (variant == null || hullmodId == null || hullmodId.trim().isEmpty()) {
            return false;
        }
        if (variant.getHullSpec() != null && variant.getHullSpec().getBuiltInMods() != null
                && variant.getHullSpec().getBuiltInMods().contains(hullmodId)) {
            return true;
        }
        if (variant.getPermaMods() != null && variant.getPermaMods().contains(hullmodId)) {
            return true;
        }
        return variant.hasHullMod(hullmodId);
    }

    private static boolean removeHullmod(ShipVariantAPI variant, String hullmodId) {
        if (variant == null || hullmodId == null || hullmodId.trim().isEmpty()) {
            return false;
        }

        boolean changed = false;
        if (variant.getPermaMods() != null && variant.getPermaMods().contains(hullmodId)
                && !isBuiltInByHull(variant, hullmodId)) {
            variant.removePermaMod(hullmodId);
            changed = true;
        }
        if (variant.hasHullMod(hullmodId) && !isBuiltInByHull(variant, hullmodId)) {
            variant.removeMod(hullmodId);
            changed = true;
        }
        return changed;
    }

    private static boolean isBuiltInByHull(ShipVariantAPI variant, String hullmodId) {
        return variant != null
                && variant.getHullSpec() != null
                && variant.getHullSpec().getBuiltInMods() != null
                && variant.getHullSpec().getBuiltInMods().contains(hullmodId);
    }

    private static ShipVariantAPI prepareEditableVariant(FleetMemberAPI member) {
        ShipVariantAPI variant = member.getVariant();
        if (variant == null) {
            return null;
        }

        try {
            ShipVariantAPI cloned = variant.clone();
            cloned.setSource(VariantSource.REFIT);
            cloned.setHullVariantId(Misc.genUID());
            member.setVariant(cloned, false, false);
            return member.getVariant() == null ? cloned : member.getVariant();
        } catch (Exception ex) {
            return variant;
        }
    }

    private static String getHullmodDisplayName(String hullmodId) {
        if (hullmodId == null || hullmodId.trim().isEmpty()) {
            return "";
        }
        try {
            return Global.getSettings().getHullModSpec(hullmodId).getDisplayName();
        } catch (Exception ex) {
            return hullmodId;
        }
    }

    private static String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        if (names.size() == 1) {
            return names.get(0);
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                builder.append("、");
            }
            builder.append(names.get(i));
        }
        return builder.toString();
    }

    private static Map<String, String> createLegacyAiTypeMap() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("system99", Moci_System99.AI_TYPE_ID);
        result.put("ai_ein", "Moci_Nine");
        result.put("haro", Moci_HaroAISystem.AI_TYPE_ID);
        return result;
    }
}
