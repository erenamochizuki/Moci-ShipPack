package data.hullmods.ships.hiryu;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;

/**
 * 飞龙级战术系统相关船插的公共部分。
 *
 * <p>飞龙的三种战术系统各对应一个舰体：本体 {@code Moci_Hiryu}（高能聚焦）与两个隐藏皮肤
 * {@code Moci_Hiryu_Plasma}（等离子推进器）、{@code Moci_Hiryu_Recall}（召回指令）。
 * 切换战术系统就是把舰船的舰体在这三者之间替换（由内置的 {@link Moci_Pa_HiryuSystemSwitcher} 完成），
 * 不再克隆并改写舰体规格。装配里的「X系统」船插（{@link Moci_Pa_HiryuSystemMode}）
 * 只用来显示当前系统，拆掉它就循环到下一个系统。</p>
 */
abstract class Moci_Pa_HiryuSystemHullMod extends BaseHullMod {
  static final int ACTIVE_MODE_ORDER = -2000;

  private static final String D_SUFFIX = "_default_D";

  /** 飞龙的三种战术系统模式。 */
  enum Mode {
    HIGH_ENERGY("Moci_hiryu_system_highenergy", "Moci_Hiryu", "highenergyfocus", "config_highenergy_name"),
    PLASMA("Moci_hiryu_system_plasma", "Moci_Hiryu_Plasma", "plasmajets", "config_plasma_name"),
    RECALL("Moci_hiryu_system_recall", "Moci_Hiryu_Recall", "recalldevice", "config_recall_name");

    /** 切换到该模式的船插 ID。 */
    final String hullmodId;
    /** 该模式对应的（非 D）舰体 ID。 */
    final String hullId;
    final String systemId;
    /** Moci_conditional_system_change 文本里该模式名称的键。 */
    final String nameKey;

    Mode(String hullmodId, String hullId, String systemId, String nameKey) {
      this.hullmodId = hullmodId;
      this.hullId = hullId;
      this.systemId = systemId;
      this.nameKey = nameKey;
    }

    /** 拆掉当前模式船插后循环到的下一个模式：高能聚焦 → 召回指令 → 等离子推进器 → 高能聚焦。 */
    Mode next() {
      switch (this) {
        case HIGH_ENERGY: return RECALL;
        case RECALL: return PLASMA;
        default: return HIGH_ENERGY;
      }
    }

    static Mode ofHullId(String baseHullId) {
      if (baseHullId == null) return null;
      for (Mode mode : values()) {
        if (mode.hullId.equals(baseHullId)) return mode;
      }
      return null;
    }
  }

  @Override
  public final int getDisplayCategoryIndex() {
    // Display alongside built-ins without changing whether a mode can be removed.
    return 0;
  }

  @Override
  public int getDisplaySortOrder() {
    return ACTIVE_MODE_ORDER;
  }

  /** 去掉 D 后缀后的舰体 ID；D 舰体优先用 dParentHullId。 */
  static String baseHullId(ShipHullSpecAPI spec) {
    if (spec == null) return null;
    String parent = spec.getDParentHullId();
    if (parent != null && !parent.isEmpty()) return parent;
    String hullId = spec.getHullId();
    if (hullId != null && hullId.endsWith(D_SUFFIX)) {
      return hullId.substring(0, hullId.length() - D_SUFFIX.length());
    }
    return hullId;
  }

  static boolean isDHull(ShipHullSpecAPI spec) {
    if (spec == null) return false;
    String hullId = spec.getHullId();
    return (spec.getDParentHullId() != null && !spec.getDParentHullId().isEmpty()
        && !spec.getDParentHullId().equals(hullId))
        || (hullId != null && hullId.endsWith(D_SUFFIX));
  }

  /** 该舰体当前对应的战术系统模式；不是飞龙（含两个切换皮肤及其 D 版）时返回 null。 */
  static Mode currentMode(ShipHullSpecAPI spec) {
    return Mode.ofHullId(baseHullId(spec));
  }

  /** 切换到目标模式时应使用的舰体规格，保持 D 舰体状态；找不到 D 版时退回非 D 版。 */
  static ShipHullSpecAPI targetSpec(ShipHullSpecAPI current, Mode target) {
    if (target == null) return null;
    if (isDHull(current)) {
      try {
        ShipHullSpecAPI d = Global.getSettings().getHullSpec(target.hullId + D_SUFFIX);
        if (d != null) return d;
      } catch (RuntimeException ignored) {
        // 没有自动生成的 D 版：用非 D 版，D 插仍留在装配里
      }
    }
    return Global.getSettings().getHullSpec(target.hullId);
  }
}
