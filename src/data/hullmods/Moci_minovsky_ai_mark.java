package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;

/**
 * 米诺夫斯基战术系统 - AI状态标记
 * 功能：标记米诺夫斯基战术系统的AI状态（启用或禁用）
 * 注意：此类同时用于 Moci_minovsky_ai_enabled 和 Moci_minovsky_ai_disabled 两个船插ID
 */
public class Moci_minovsky_ai_mark extends BaseHullMod {

    @Override
    public int getDisplaySortOrder() {
        return 3000;
    }

    @Override
    public int getDisplayCategoryIndex() {
        return 3;
    }
}
