package data.hullmods.ships.hiryu;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.loading.Description;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 飞龙「战术系统：X」船插：告诉玩家当前是什么战术系统、有什么效果。
 *
 * <p>本身没有任何效果，也不在船插列表里出现（hull_mods.csv 里 hidden）。玩家在改装界面拆掉它，
 * 内置的 {@link Moci_Pa_HiryuSystemSwitcher} 就把舰船换成循环中下一个模式的舰体，并装上下一个模式的这个船插。</p>
 */
abstract class Moci_Pa_HiryuSystemMode extends Moci_Pa_HiryuSystemHullMod {
  private static final String SWITCHER_TEXT_ID = "Moci_conditional_system_change";

  private final Mode mode;

  Moci_Pa_HiryuSystemMode(Mode mode) {
    this.mode = mode;
  }

  @Override
  public boolean showInRefitScreenModPickerFor(ShipAPI ship) {
    return false;
  }

  @Override
  public boolean isApplicableToShip(ShipAPI ship) {
    return ship != null && currentMode(ship.getHullSpec()) == mode;
  }

  @Override
  public String getUnapplicableReason(ShipAPI ship) {
    if (ship == null || isApplicableToShip(ship)) return null;
    return Moci_TextLoader.getText(SWITCHER_TEXT_ID, "description.unapplicable_reason");
  }

  @Override
  public void addPostDescriptionSection(
      TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
    float pad = 10f;
    float opad = 5f;
    Color h = Misc.getHighlightColor();
    tooltip.addSectionHeading(switcherText("mode_title"),
        new Color(200, 100, 255, 255), new Color(80, 0, 120, 100), Alignment.MID, opad);
    tooltip.addPara(switcherText("mode_current"), pad, h, switcherText(mode.nameKey));

    String effect = systemDescription(mode.systemId);
    if (effect != null) {
      tooltip.addPara(effect, opad);
    }
    tooltip.addPara(switcherText("mode_switch_next"), pad, h, switcherText(mode.next().nameKey));
  }

  /** 战术系统本身的描述（原版 descriptions.csv 的 SHIP_SYSTEM 条目），取不到时返回 null。 */
  private static String systemDescription(String systemId) {
    try {
      Description description = Global.getSettings().getDescription(systemId, Description.Type.SHIP_SYSTEM);
      if (description == null) return null;
      String text = description.getText1();
      return text == null || text.trim().isEmpty() ? null : text;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String switcherText(String key) {
    return Moci_TextLoader.getText(SWITCHER_TEXT_ID, "description." + key);
  }
}
