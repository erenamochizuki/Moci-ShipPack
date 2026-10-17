package data.hullmods;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_FighterExpand extends BaseHullMod {
    private static final String TEXT_ID = "Moci_fighter_expand";
    private static final String MOD_ID = "moci_fighterexpand";

    // 常量定义
    private static final float RANGE_MULTIPLIER = 0.5f; // 作战范围倍率
    private static final float REPLACEMENT_RATE_MULT = 1.5f; // 整备速率倍率
    private static final int FIGHTER_COUNT_MULTIPLIER = 2; // 舰载机数量倍率
    private static final float EXTRA_DURATION = 999999f; // 额外部署持续时间
    private static final float UPDATE_INTERVAL = 0.1f; // 更新间隔（秒）

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0)
            return Moci_TextLoader.getText(TEXT_ID, "description.param_double");
        if (index == 1)
            return "50%";
        if (index == 2)
            return "50%";
        if (index == 3)
            return Moci_TextLoader.getText(TEXT_ID, "description.param_disabled_system");
        return null;
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 绑定监听器实时调整舰载机属性
        if (!ship.hasListenerOfClass(MociDeckListener.class)) {
            ship.addListener(new MociDeckListener(ship));
        }
    }

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 整备下降速率加快
        stats.getDynamic().getStat(Stats.REPLACEMENT_RATE_DECREASE_MULT).modifyMult(id, REPLACEMENT_RATE_MULT);

        // 舰载机作战范围减半
        stats.getFighterWingRange().modifyMult(id, RANGE_MULTIPLIER);
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        int bays = (int) ship.getMutableStats().getNumFighterBays().getModifiedValue();
        return bays > 0;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_reason");
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width,
            boolean isForModSpec) {
        float pad = 10f;
        float opad = 10f;
        Color highlight = Misc.getHighlightColor();

        tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.limit_heading"),
                new Color(255, 150, 150, 255), // 文字颜色
                new Color(200, 0, 0, 150), // 背景颜色
                Alignment.MID, // 文字位置
                opad); // 与其他元素之间的间距

        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.limit_main"),
                pad, highlight,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.limit_main_highlights").toArray(new String[0]));
    }

    public static class MociDeckListener implements AdvanceableListener {
        private final ShipAPI ship;
        private final Map<FighterLaunchBayAPI, Boolean> bayInitStatus = new HashMap<>();
        private final IntervalUtil interval = new IntervalUtil(UPDATE_INTERVAL, UPDATE_INTERVAL); // 更新间隔控制

        public MociDeckListener(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public void advance(float amount) {
            if (Global.getCombatEngine().isPaused())
                return;

            // 使用间隔控制减少更新频率
            interval.advance(amount);
            if (!interval.intervalElapsed())
                return;

            for (FighterLaunchBayAPI bay : ship.getLaunchBaysCopy()) {
                if (bay.getWing() == null)
                    continue;

                // 初始化状态记录
                if (!bayInitStatus.containsKey(bay)) {
                    bayInitStatus.put(bay, false);
                }

                // 动态计算部署需求
                int baseCount = bay.getWing().getSpec().getNumFighters();
                int deployed = bay.getWing().getWingMembers().size();
                int maxTotal = baseCount * FIGHTER_COUNT_MULTIPLIER;
                int actualAdd = maxTotal - deployed;

                // 设置部署参数
                bay.setExtraDeployments(Math.max(actualAdd, 0));
                bay.setExtraDeploymentLimit(maxTotal);
                bay.setExtraDuration(EXTRA_DURATION);

                // 首次部署快速补充
                if (actualAdd > 0 && !bayInitStatus.get(bay)) {
                    bay.setFastReplacements(actualAdd);
                    bayInitStatus.put(bay, true);
                }
            }
        }
    }
}
