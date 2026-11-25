package data.hullmods.ships.hiryu;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 飞龙「定制化战术系统」（舰体内置），负责战术系统的循环切换。
 *
 * <p>当前战术系统由舰体决定：本体 {@code Moci_Hiryu} 是高能聚焦，两个隐藏皮肤分别是等离子推进器和召回指令。
 * 装配里挂着当前模式的「X系统」船插（{@link Moci_Pa_HiryuSystemMode}）用来显示。
 * 玩家在改装界面拆掉它，这里就把舰体换成循环中的下一个模式，并装上下一个模式的船插。
 * 做法参照大和的「波动炮核心修复协议」：克隆装配后用 {@code setHullSpecAPI} 换舰体，
 * 武器、船插、D 插都留在装配里（三个舰体共用同一套槽位）。</p>
 *
 * <p>怎么区分「玩家拆掉了」和「这条装配本来就没装」：装配带 {@link #READY_TAG} 标签说明已经初始化过，
 * 这时一个模式船插都没有就是被拆掉的；没有标签时只补装当前模式的船插并打上标签。
 * 只处理玩家舰队的舰船，NPC 舰船的战术系统本来就由舰体决定，不改它们共用的预设装配。</p>
 */
public class Moci_Pa_HiryuSystemSwitcher extends Moci_Pa_HiryuSystemHullMod {
  private static final String TEXT_ID = "Moci_conditional_system_change";
  /** 装配已初始化过模式船插的标记。 */
  static final String READY_TAG = "moci_hiryu_system_ready";

  private static String text(String key) {
    return Moci_TextLoader.getText(TEXT_ID, key);
  }

  @Override
  public int getDisplaySortOrder() {
    return ACTIVE_MODE_ORDER + 1;
  }

  // ------------------------------------------------------------------ 切换

  /** 改装界面拆掉模式船插后立刻生效。 */
  @Override
  public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
    if (stats == null) return;
    FleetMemberAPI member = stats.getFleetMember();
    if (!isPlayerMember(member)) return;
    ShipVariantAPI current = stats.getVariant();
    if (current == null) return;
    Mode currentMode = currentMode(current.getHullSpec());
    if (currentMode == null) return;

    Mode target = resolveTarget(current, currentMode);
    if (target == null) {
      if (!isInitialized(current, currentMode)) initialize(current, currentMode);
      return;
    }
    try {
      if (member.getVariant() != null) {
        ShipVariantAPI copy = member.getVariant().clone();
        copy.setSource(VariantSource.REFIT);
        copy.setHullVariantId(Misc.genUID());
        member.setVariant(copy, false, false);
        current = stats.getVariant();
        if (current == null) return;
      }
      switchTo(current, target);
      syncPlayerFleet();
    } catch (RuntimeException ex) {
      Global.getLogger(Moci_Pa_HiryuSystemSwitcher.class).warn("飞龙战术系统切换失败（装配） target=" + target.hullId, ex);
    }
  }

  /** 战役层兜底：新入手的飞龙补装模式船插；装配回调没走到时在这里完成切换。 */
  @Override
  public void advanceInCampaign(FleetMemberAPI member, float amount) {
    if (!isPlayerMember(member)) return;
    ShipVariantAPI current = member.getVariant();
    if (current == null) return;
    Mode currentMode = currentMode(current.getHullSpec());
    if (currentMode == null) return;

    Mode target = resolveTarget(current, currentMode);
    boolean needsInit = target == null && !isInitialized(current, currentMode);
    if (target == null && !needsInit) return;
    try {
      ShipVariantAPI updated = current.clone();
      updated.setSource(VariantSource.REFIT);
      updated.setHullVariantId(Misc.genUID());
      if (target != null) {
        switchTo(updated, target);
      } else {
        initialize(updated, currentMode);
      }
      member.setVariant(updated, false, true);
      syncPlayerFleet();
    } catch (RuntimeException ex) {
      Global.getLogger(Moci_Pa_HiryuSystemSwitcher.class).warn("飞龙战术系统切换失败（战役）", ex);
    }
  }

  /**
   * 需要切换到的模式；不需要切换时返回 null。
   * 只有「已初始化过、且一个模式船插都没有」才算玩家拆掉了，切到下一个模式。
   * 装着别的模式的船插（例如自动装配套用了别的舰体的装配）不切换，由 {@link #initialize} 改回当前模式的船插。
   */
  private static Mode resolveTarget(ShipVariantAPI variant, Mode currentMode) {
    if (!variant.hasTag(READY_TAG)) return null;
    for (Mode mode : Mode.values()) {
      if (variant.hasHullMod(mode.hullmodId)) return null;
    }
    return currentMode.next();
  }

  /** 装配里恰好挂着当前模式的船插，且已打上初始化标记。 */
  private static boolean isInitialized(ShipVariantAPI variant, Mode currentMode) {
    if (!variant.hasTag(READY_TAG) || !variant.hasHullMod(currentMode.hullmodId)) return false;
    for (Mode mode : Mode.values()) {
      if (mode != currentMode && variant.hasHullMod(mode.hullmodId)) return false;
    }
    return true;
  }

  /** 只留当前模式的船插，并打上初始化标记。 */
  private static void initialize(ShipVariantAPI variant, Mode currentMode) {
    for (Mode mode : Mode.values()) {
      if (mode != currentMode && variant.hasHullMod(mode.hullmodId)) {
        variant.removePermaMod(mode.hullmodId);
        variant.removeMod(mode.hullmodId);
      }
    }
    if (!variant.hasHullMod(currentMode.hullmodId)) variant.addMod(currentMode.hullmodId);
    if (!variant.hasTag(READY_TAG)) variant.addTag(READY_TAG);
  }

  private static void switchTo(ShipVariantAPI variant, Mode target) {
    ShipHullSpecAPI spec = targetSpec(variant.getHullSpec(), target);
    if (spec == null) return;
    variant.setHullSpecAPI(spec);
    initialize(variant, target);
  }

  private static boolean isPlayerMember(FleetMemberAPI member) {
    if (member == null || member.getFleetData() == null) return false;
    CampaignFleetAPI fleet = member.getFleetData().getFleet();
    return fleet != null && fleet.isPlayerFleet();
  }

  private static void syncPlayerFleet() {
    if (Global.getSector() != null && Global.getSector().getPlayerFleet() != null) {
      Global.getSector().getPlayerFleet().forceSync();
    }
  }

  // ------------------------------------------------------------------ 描述

  @Override
  public void addPostDescriptionSection(
      TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
    float pad = 10f;
    float opad = 5f;
    Color h = Misc.getHighlightColor();
    tooltip.addSectionHeading(text("description.title"), Alignment.MID, opad);

    Mode currentMode = ship != null ? currentMode(ship.getHullSpec()) : null;
    if (currentMode != null) {
      tooltip.addPara(text("description.current"), pad, h, text("description." + currentMode.nameKey));
    }
    tooltip.addPara(text("description.intro"), pad);
    tooltip.addPara(text("description.cycle"), opad, h,
        text("description." + Mode.HIGH_ENERGY.nameKey),
        text("description." + Mode.RECALL.nameKey),
        text("description." + Mode.PLASMA.nameKey));
    tooltip.addPara(text("description.switch_keep"), pad);
  }
}
