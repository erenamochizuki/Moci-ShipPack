package data.hullmods;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.ships.ai.Moci_MechTurretAI;
import data.scripts.util.Moci_TextLoader;

public class Moci_MechTurretMode extends BaseHullMod {
    public static final String HULLMOD_ID = "Moci_MechTurretMode";
    public static final String AI_MODE_HIGH_HULLMOD_ID = "Moci_mechturret_ai_high";
    public static final String AI_MODE_MEDIUM_HULLMOD_ID = "Moci_mechturret_ai_medium";
    public static final String AI_MODE_LOW_HULLMOD_ID = "Moci_mechturret_ai_low";
    public static final String AI_MODE_MANUAL_HULLMOD_ID = "Moci_mechturret_ai_manual";
    private static final String TEXT_ID = "Moci_MechTurretMode";
    private static final Map<String, String> AI_MODE_CYCLE = new HashMap<String, String>();
    private static final Map<ShipVariantAPI, String> AI_MODE_RECORDER = new HashMap<ShipVariantAPI, String>();

    public enum TurretAIMode {
        HIGH,
        MEDIUM,
        LOW,
        MANUAL
    }

    static {
        AI_MODE_CYCLE.put(AI_MODE_HIGH_HULLMOD_ID, AI_MODE_MEDIUM_HULLMOD_ID);
        AI_MODE_CYCLE.put(AI_MODE_MEDIUM_HULLMOD_ID, AI_MODE_LOW_HULLMOD_ID);
        AI_MODE_CYCLE.put(AI_MODE_LOW_HULLMOD_ID, AI_MODE_MANUAL_HULLMOD_ID);
        AI_MODE_CYCLE.put(AI_MODE_MANUAL_HULLMOD_ID, AI_MODE_HIGH_HULLMOD_ID);
    }

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        if (stats == null || stats.getVariant() == null) {
            return;
        }
        ensureAIModeMarker(stats.getVariant());
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        Moci_MechTurretAI.advanceCompatibilityShell(ship, amount);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship != null && ship.getVariant() != null) {
            recordCurrentAIMode(ship.getVariant());
        }
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null
                && ship.getVariant() != null
                && ship.getHullSize() == ShipAPI.HullSize.FRIGATE
                && ship.getVariant().hasHullMod("Moci_MobileSuits")
                && !Moci_MobileSuitsIDcard.hasAdvancedFrigateHullmod(ship.getVariant());
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_ship_null");
        }
        if (ship.getVariant() == null) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_variant_null");
        }
        if (ship.getHullSize() != ShipAPI.HullSize.FRIGATE) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_only_frigate");
        }
        if (!ship.getVariant().hasHullMod("Moci_MobileSuits")) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_only_ms");
        }
        if (Moci_MobileSuitsIDcard.hasAdvancedFrigateHullmod(ship.getVariant())) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_advanced_hullmod");
        }
        return null;
    }

    @Override
    public boolean showInRefitScreenModPickerFor(ShipAPI ship) {
        return isApplicableToShip(ship);
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize,
                                          ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 6f;
        float opad = 5f;
        Color highlight = Misc.getHighlightColor();
        Map<String, String> replacements = Moci_TextLoader.mapOf(
                "flux_transfer_mult", String.valueOf((int) Moci_MechTurretAI.FLUX_TRANSFER_MULT),
                "reload_interval", String.valueOf((int) Moci_MechTurretAI.AMMO_RELOAD_INTERVAL_SECONDS),
                "destroyer_bonus", String.valueOf((int) Moci_MechTurretAI.getRangeBonusForHostHullSize(ShipAPI.HullSize.DESTROYER)),
                "cruiser_bonus", String.valueOf((int) Moci_MechTurretAI.getRangeBonusForHostHullSize(ShipAPI.HullSize.CRUISER)),
                "capital_bonus", String.valueOf((int) Moci_MechTurretAI.getRangeBonusForHostHullSize(ShipAPI.HullSize.CAPITAL_SHIP))
        );

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.effect_heading"), Alignment.MID, opad);
        tooltip.addPara(
                Moci_TextLoader.getText(TEXT_ID, "description.effect_intro"),
                pad, new Color[]{highlight, highlight},
                Moci_TextLoader.getHighlights(TEXT_ID, "description.effect_intro_highlights").toArray(new String[0]));
        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.effect_flux_transfer", replacements),
                pad, new Color[]{highlight, highlight, highlight},
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.effect_flux_transfer_highlights", replacements).toArray(new String[0]));
        if (Moci_MechTurretAI.ENABLE_TURRET_AMMO_RELOAD) {
            tooltip.addPara(
                    Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.effect_reload", replacements),
                    pad, new Color[]{highlight, highlight, highlight, highlight, highlight},
                    Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.effect_reload_highlights", replacements).toArray(new String[0]));
        }

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.range_heading"), Alignment.MID, opad);

        float[] columnWidths = {width * 0.42f, width * 0.58f};
        tooltip.beginTable(
                Misc.getBasePlayerColor(),
                Misc.getDarkPlayerColor(),
                Misc.getBrightPlayerColor(),
                20f,
                Moci_TextLoader.getText(TEXT_ID, "description.table_host_hull_size"), columnWidths[0],
                Moci_TextLoader.getText(TEXT_ID, "description.table_range_bonus"), columnWidths[1]
        );

        tooltip.addRow(
                Alignment.MID, highlight, Moci_TextLoader.getText(TEXT_ID, "description.table_destroyer"),
                Alignment.MID, Misc.getTextColor(), createHighlightedTableValue(
                        tooltip,
                        Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.table_destroyer_bonus", replacements),
                        Misc.getTextColor(),
                        highlight));
        tooltip.addRow(
                Alignment.MID, highlight, Moci_TextLoader.getText(TEXT_ID, "description.table_cruiser"),
                Alignment.MID, Misc.getTextColor(), createHighlightedTableValue(
                        tooltip,
                        Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.table_cruiser_bonus", replacements),
                        Misc.getTextColor(),
                        highlight));
        tooltip.addRow(
                Alignment.MID, highlight, Moci_TextLoader.getText(TEXT_ID, "description.table_capital"),
                Alignment.MID, Misc.getTextColor(), createHighlightedTableValue(
                        tooltip,
                        Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.table_capital_bonus", replacements),
                        Misc.getTextColor(),
                        highlight));

        tooltip.addTable("", 0, pad);
    }

    private LabelAPI createHighlightedTableValue(TooltipMakerAPI tooltip, String text, Color baseColor, Color highlightColor) {
        LabelAPI label = tooltip.createLabel(text, baseColor);
        label.setHighlight(text);
        label.setHighlightColors(highlightColor);
        return label;
    }

    public static TurretAIMode getTurretAIMode(ShipAPI ship) {
        if (ship == null) {
            return TurretAIMode.HIGH;
        }
        return getTurretAIMode(ship.getVariant());
    }

    public static TurretAIMode getTurretAIMode(ShipVariantAPI variant) {
        if (variant == null) {
            return TurretAIMode.HIGH;
        }
        if (variant.getHullMods().contains(AI_MODE_LOW_HULLMOD_ID)) {
            return TurretAIMode.LOW;
        }
        if (variant.getHullMods().contains(AI_MODE_MANUAL_HULLMOD_ID)) {
            return TurretAIMode.MANUAL;
        }
        if (variant.getHullMods().contains(AI_MODE_MEDIUM_HULLMOD_ID)) {
            return TurretAIMode.MEDIUM;
        }
        return TurretAIMode.HIGH;
    }

    public static boolean isAIModeMarkerHullmod(String hullmodId) {
        return AI_MODE_HIGH_HULLMOD_ID.equals(hullmodId)
                || AI_MODE_MEDIUM_HULLMOD_ID.equals(hullmodId)
                || AI_MODE_LOW_HULLMOD_ID.equals(hullmodId)
                || AI_MODE_MANUAL_HULLMOD_ID.equals(hullmodId);
    }

    public static void cleanupAIModeMarkers(ShipVariantAPI variant) {
        if (variant == null) {
            return;
        }
        if (variant.getHullMods().contains(AI_MODE_HIGH_HULLMOD_ID)) {
            variant.removeMod(AI_MODE_HIGH_HULLMOD_ID);
        }
        if (variant.getHullMods().contains(AI_MODE_MEDIUM_HULLMOD_ID)) {
            variant.removeMod(AI_MODE_MEDIUM_HULLMOD_ID);
        }
        if (variant.getHullMods().contains(AI_MODE_LOW_HULLMOD_ID)) {
            variant.removeMod(AI_MODE_LOW_HULLMOD_ID);
        }
        if (variant.getHullMods().contains(AI_MODE_MANUAL_HULLMOD_ID)) {
            variant.removeMod(AI_MODE_MANUAL_HULLMOD_ID);
        }
    }

    public static void recordCurrentAIMode(ShipVariantAPI variant) {
        if (variant == null) {
            return;
        }
        String current = getCurrentAIModeHullmodId(variant);
        if (current != null) {
            AI_MODE_RECORDER.put(variant, current);
        }
    }

    private static void ensureAIModeMarker(ShipVariantAPI variant) {
        if (variant == null || !variant.hasHullMod(HULLMOD_ID)) {
            return;
        }

        String current = getCurrentAIModeHullmodId(variant);
        if (current != null) {
            cleanupExtraAIModeMarkers(variant, current);
            AI_MODE_RECORDER.put(variant, current);
            return;
        }

        String last = AI_MODE_RECORDER.get(variant);
        String next = AI_MODE_CYCLE.get(last);
        if (next == null) {
            next = AI_MODE_HIGH_HULLMOD_ID;
        }

        variant.addMod(next);
        AI_MODE_RECORDER.put(variant, next);
    }

    private static String getCurrentAIModeHullmodId(ShipVariantAPI variant) {
        if (variant == null) {
            return null;
        }
        if (variant.getHullMods().contains(AI_MODE_HIGH_HULLMOD_ID)) {
            return AI_MODE_HIGH_HULLMOD_ID;
        }
        if (variant.getHullMods().contains(AI_MODE_MEDIUM_HULLMOD_ID)) {
            return AI_MODE_MEDIUM_HULLMOD_ID;
        }
        if (variant.getHullMods().contains(AI_MODE_LOW_HULLMOD_ID)) {
            return AI_MODE_LOW_HULLMOD_ID;
        }
        if (variant.getHullMods().contains(AI_MODE_MANUAL_HULLMOD_ID)) {
            return AI_MODE_MANUAL_HULLMOD_ID;
        }
        return null;
    }

    private static void cleanupExtraAIModeMarkers(ShipVariantAPI variant, String keep) {
        if (variant == null) {
            return;
        }
        if (!AI_MODE_HIGH_HULLMOD_ID.equals(keep) && variant.getHullMods().contains(AI_MODE_HIGH_HULLMOD_ID)) {
            variant.removeMod(AI_MODE_HIGH_HULLMOD_ID);
        }
        if (!AI_MODE_MEDIUM_HULLMOD_ID.equals(keep) && variant.getHullMods().contains(AI_MODE_MEDIUM_HULLMOD_ID)) {
            variant.removeMod(AI_MODE_MEDIUM_HULLMOD_ID);
        }
        if (!AI_MODE_LOW_HULLMOD_ID.equals(keep) && variant.getHullMods().contains(AI_MODE_LOW_HULLMOD_ID)) {
            variant.removeMod(AI_MODE_LOW_HULLMOD_ID);
        }
        if (!AI_MODE_MANUAL_HULLMOD_ID.equals(keep) && variant.getHullMods().contains(AI_MODE_MANUAL_HULLMOD_ID)) {
            variant.removeMod(AI_MODE_MANUAL_HULLMOD_ID);
        }
    }
}
