package data.scripts.impl;

import com.fs.starfarer.api.impl.campaign.econ.impl.GroundDefenses;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Pair;

public class Moci_BastionFortress extends GroundDefenses {

    public static final String INDUSTRY_ID = "moci_bastion_fortress";

    // 地面防御倍率参数。
    //
    // 最终显示/生效公式是：
    //     ground defense multiplier = 1 + DEFENSE_BONUS * deficitMult
    //
    // deficitMult 由 SUPPLIES / HEAVY_MACHINERY / HAND_WEAPONS 的短缺程度决定：
    //     供应充足时 deficitMult = 1，因此 DEFENSE_BONUS = 4 会给出 x5 地面防御。
    //     供应短缺时 deficitMult 会下降，防御倍率也会随之降低。
    //
    // 常用调法：
    //     2f = x3，3f = x4，4f = x5，5f = x6。
    public static final float DEFENSE_BONUS = 5f;

    // 改良后的额外地面防御倍率。
    //
    // 公式是：
    //     improved multiplier = 1 + IMPROVE_BONUS
    //
    // 这里 0.25f 等于改良后再乘 x1.25。它和上面的主防御倍率相乘，
    // 所以满供应且已改良时大约是 x5 * x1.25 = x6.25。
    public static final float IMPROVE_BONUS = 1.5f;

    // 稳定度加成。GroundDefenses/BaseIndustry 会用这个值给市场添加稳定度修正。
    private static final int STABILITY_BONUS = 5;

    // 需求/产出都以市场规模 market.getSize() 为基础。
    //
    // 举例：5 规模殖民地，SUPPLIES_DEMAND_BONUS = 1 时，补给需求就是 5 + 1 = 6。
    // 这些数值是“经济单位”，不是货舱里的实际件数。
    private static final int SUPPLIES_DEMAND_BONUS = 1;
    private static final int HEAVY_MACHINERY_DEMAND_BONUS = 0;
    private static final int HAND_WEAPONS_DEMAND_BONUS = 0;

    private static final int CREW_SUPPLY_BONUS = 1;
    private static final int MARINES_SUPPLY_BONUS = 1;
    private static final int HAND_WEAPONS_SUPPLY_BONUS = 1;

    // 短缺影响产出的强度参数。
    //
    // applyDeficitToProduction() 会根据最大短缺削减后面列出的产出。
    // 这个值越高，短缺越容易重创 CREW / MARINES / HAND_WEAPONS 的产出。
    // 想让要塞在断供时仍然更稳定，可以调低；想让后勤更重要，可以调高。
    private static final int DEFICIT_TO_PRODUCTION_MULT = 1;

    public void apply() {
        // true 表示按正常产业/建筑逻辑应用收入与维护费。
        // 具体维护费来自 industries.csv 里的 upkeep 列，而不是这里的 Java 常量。
        super.apply(true);

        int size = market.getSize();

        // 需求量 = 市场规模 + 对应 bonus。
        // 这些需求同时会参与 deficitMult，短缺会降低地面防御倍率。
        demand(Commodities.SUPPLIES, size + SUPPLIES_DEMAND_BONUS);
        demand(Commodities.HEAVY_MACHINERY, size + HEAVY_MACHINERY_DEMAND_BONUS);
        demand(Commodities.HAND_WEAPONS, size + HAND_WEAPONS_DEMAND_BONUS);

        // 产出量 = 市场规模 + 对应 bonus。
        // 增加这些 bonus 可以直接提高该建筑给经济提供的船员/陆战队/重型武器。
        supply(Commodities.CREW, size + CREW_SUPPLY_BONUS);
        supply(Commodities.MARINES, size + MARINES_SUPPLY_BONUS);
        supply(Commodities.HAND_WEAPONS, size + HAND_WEAPONS_SUPPLY_BONUS);

        // 找出三项关键需求中最严重的短缺，用它作为产出惩罚的依据。
        Pair<String, Integer> deficit = getMaxDeficit(
                Commodities.SUPPLIES,
                Commodities.HEAVY_MACHINERY,
                Commodities.HAND_WEAPONS
        );
        applyDeficitToProduction(
                DEFICIT_TO_PRODUCTION_MULT,
                deficit,
                Commodities.CREW,
                Commodities.MARINES,
                Commodities.HAND_WEAPONS
        );

        // 稳定度加成来自 getBaseStabilityMod()，也会受到 getStabilityAffectingDeficit() 的短缺影响。
        modifyStabilityWithBaseMod();

        // 这里单独计算地面防御的短缺倍率。供应充足时为 1，短缺时小于 1。
        float mult = getDeficitMult(
                Commodities.SUPPLIES,
                Commodities.HEAVY_MACHINERY,
                Commodities.HAND_WEAPONS
        );
        String extra = "";
        if (mult != 1f) {
            String commodity = getMaxDeficit(
                    Commodities.SUPPLIES,
                    Commodities.HEAVY_MACHINERY,
                    Commodities.HAND_WEAPONS
            ).one;
            extra = " (" + getDeficitText(commodity).toLowerCase() + ")";
        }

        market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD)
                .modifyMult(getModId(), 1f + DEFENSE_BONUS * mult, getNameForModifier() + extra);

        // 如果建筑被扰乱、禁用或其他原因导致不运作，清掉产出并撤销所有修正。
        if (!isFunctional()) {
            supply.clear();
            unapply();
        }
    }

    @Override
    protected void addPostDemandSection(TooltipMakerAPI tooltip, boolean hasDemand, IndustryTooltipMode mode) {
        if (mode != IndustryTooltipMode.NORMAL || isFunctional()) {
            // 这些只负责 tooltip 展示，不直接改变实际效果；实际效果在 apply() 里。
            addStabilityPostDemandSection(tooltip, hasDemand, mode);
            addGroundDefensesImpactSection(
                    tooltip,
                    DEFENSE_BONUS,
                    Commodities.SUPPLIES,
                    Commodities.HEAVY_MACHINERY,
                    Commodities.HAND_WEAPONS
            );
        }
    }

    @Override
    protected int getBaseStabilityMod() {
        return STABILITY_BONUS;
    }

    @Override
    protected Pair<String, Integer> getStabilityAffectingDeficit() {
        // 稳定度加成也看这三类物资的最大短缺。
        // 如果想让稳定度不受某项物资影响，就从这里和 apply() 的 defense deficit 列表里一起移除。
        return getMaxDeficit(
                Commodities.SUPPLIES,
                Commodities.HEAVY_MACHINERY,
                Commodities.HAND_WEAPONS
        );
    }

    @Override
    protected void applyImproveModifiers() {
        if (isImproved()) {
            // 改良效果使用独立的 modifier id，避免覆盖主防御倍率。
            market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD).modifyMult(
                    getModId(2),
                    1f + IMPROVE_BONUS,
                    getImprovementsDescForModifiers() + " (" + getNameForModifier() + ")"
            );
        } else {
            market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD).unmodifyMult(getModId(2));
        }
    }
}
