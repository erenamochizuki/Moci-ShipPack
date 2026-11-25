package data.hullmods.ships.hiryu;

import com.fs.starfarer.api.combat.BaseHullMod;

abstract class Moci_Pa_HiryuSystemHullMod extends BaseHullMod {
  static final int ACTIVE_MODE_ORDER = -2000;

  @Override
  public final int getDisplayCategoryIndex() {
    // Display alongside built-ins without changing whether a mode can be removed.
    return 0;
  }

  @Override
  public int getDisplaySortOrder() {
    return ACTIVE_MODE_ORDER;
  }
}
