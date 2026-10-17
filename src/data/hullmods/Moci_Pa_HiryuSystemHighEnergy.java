package data.hullmods;

import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_Pa_HiryuSystemHighEnergy extends BaseHullMod {
  private static final String TEXT_ID = "Moci_hiryu_system_highenergy";
  @Override
  public void applyEffectsBeforeShipCreation(
      ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
    if (stats.getVariant() != null) {
      // 修复D-hull问题：获取基础船体ID
      String hullId = stats.getVariant().getHullSpec().getDParentHullId();
      if (hullId == null) {
        hullId = stats.getVariant().getHullSpec().getHullId();
      }
      if ("Moci_Hiryu".equals(hullId)) {
        setShipSystemSafely(stats, "highenergyfocus");
      }
    }
  }

  @Override
  public void addPostDescriptionSection(
      TooltipMakerAPI tooltip,
      ShipAPI.HullSize hullSize,
      ShipAPI ship,
      float width,
      boolean isForModSpec) {
    float pad = 10f;
    float opad = 5f;
    Color h = Misc.getHighlightColor();
    tooltip.addSectionHeading(
        Moci_TextLoader.getText(TEXT_ID, "description.title"), new Color(200, 100, 255, 255), new Color(80, 0, 120, 100), Alignment.MID, opad);
    tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.install"), pad, h,
        Moci_TextLoader.getHighlights(TEXT_ID, "description.install_highlights").toArray(new String[0]));
    tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.switch_next"), pad, Misc.getTextColor(),
        Moci_TextLoader.getHighlights(TEXT_ID, "description.switch_next_highlights").toArray(new String[0]));
  }

  @Override
  public boolean isApplicableToShip(ShipAPI ship) {
    if (ship == null) return false;
    // 修复D-hull问题：获取基础船体ID
    String hullId = ship.getHullSpec().getDParentHullId();
    if (hullId == null) {
      hullId = ship.getHullSpec().getHullId();
    }
    return "Moci_Hiryu".equals(hullId);
  }

  @Override
  public String getUnapplicableReason(ShipAPI ship) {
    if (ship == null) return null;
    // 修复D-hull问题：获取基础船体ID
    String hullId = ship.getHullSpec().getDParentHullId();
    if (hullId == null) {
      hullId = ship.getHullSpec().getHullId();
    }
    if (!"Moci_Hiryu".equals(hullId)) {
      return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_reason");
    }
    return null;
  }

  private static boolean setShipSystemSafely(MutableShipStatsAPI stats, String systemId) {
    if (stats == null) return false;
    ShipVariantAPI variant = stats.getVariant();
    if (variant == null) return false;
    ShipHullSpecAPI originalSpec = variant.getHullSpec();
    if (originalSpec == null) return false;
    ShipHullSpecAPI clonedSpec = cloneHullSpec(originalSpec);
    if (clonedSpec == null) return false;
    clonedSpec.setShipSystemId(systemId);
    variant.setHullSpecAPI(clonedSpec);
    return true;
  }

  private static ShipHullSpecAPI cloneHullSpec(ShipHullSpecAPI originalSpec) {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      Class<?> hullSpecClass = originalSpec.getClass();
      MethodHandle cloneMethod = lookup.findVirtual(hullSpecClass, "clone", MethodType.methodType(Object.class));
      return (ShipHullSpecAPI) cloneMethod.invoke(originalSpec);
    } catch (Throwable t) {
      return null;
    }
  }
}
