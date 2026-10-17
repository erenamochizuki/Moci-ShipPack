package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_AdaptiveWeaponSystem extends BaseHullMod {
    private static final String TEXT_ID = "Moci_adaptive_weapon_system";
    
    // 配置参数
    public static float SAFETY_OVERRIDES_RANGE_BOOST = 150f; // 安全协议超驰时的射程阈值提升
    public static float CR_DEGRADATION_PENALTY = 50f; // 超过峰值时间后CR衰减速率增加百分比
    public static float LARGE_ENERGY_OP_REDUCTION = 10f; // 大型能量武器OP减少值

    // 存储原始AI性格的数据键
    private static final String ORIGINAL_PERSONALITY_KEY = "moci_adaptive_original_personality";

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 大型能量武器OP减少（无条件生效）
        stats.getDynamic().getMod(Stats.LARGE_ENERGY_MOD).modifyFlat(id, -LARGE_ENERGY_OP_REDUCTION);
        
        // 检查是否装备了安全协议超驰
        if (stats.getVariant().hasHullMod("safetyoverrides")) {
            // 提升武器射程阈值
            stats.getWeaponRangeThreshold().modifyFlat(id, SAFETY_OVERRIDES_RANGE_BOOST);
            
            // 增加超过峰值时间后的CR衰减速率
            stats.getCRLossPerSecondPercent().modifyMult(id, 1f + CR_DEGRADATION_PENALTY * 0.01f);
        }
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null || !ship.isAlive()) return;

        // 检查是否同时装备当前船插和"安全协议超驰"
        boolean hasSafetyOverrides = ship.getVariant().hasHullMod("safetyoverrides");

        if (hasSafetyOverrides) {
            // 设置鲁莽性格，并保存原始性格
            ShipAIConfig config = ship.getShipAI() != null ? ship.getShipAI().getConfig() : null;
            if (config != null) {
                // 检查是否已保存原始性格
                if (!ship.getCustomData().containsKey(ORIGINAL_PERSONALITY_KEY)) {
                    ship.setCustomData(ORIGINAL_PERSONALITY_KEY, config.personalityOverride); // 保存原始性格
                }
                config.personalityOverride = Personalities.RECKLESS; // 设置鲁莽
            }
        } else {
            // 未满足条件时恢复原始性格
            if (ship.getCustomData().containsKey(ORIGINAL_PERSONALITY_KEY)) {
                ShipAIConfig config = ship.getShipAI() != null ? ship.getShipAI().getConfig() : null;
                if (config != null) {
                    String originalPersonality = (String) ship.getCustomData().get(ORIGINAL_PERSONALITY_KEY);
                    config.personalityOverride = originalPersonality; // 恢复原始性格
                }
                ship.removeCustomData(ORIGINAL_PERSONALITY_KEY); // 清理数据
            }
        }
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3f;
        Color h = Misc.getHighlightColor();
        Color good = Misc.getPositiveHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();
        
        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.op_reduction",
                        Moci_TextLoader.mapOf("op_reduction", String.valueOf((int)LARGE_ENERGY_OP_REDUCTION))),
                pad, good,
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.op_reduction_highlights",
                        Moci_TextLoader.mapOf("op_reduction", String.valueOf((int)LARGE_ENERGY_OP_REDUCTION))).toArray(new String[0]));
        
        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.so_range",
                        Moci_TextLoader.mapOf("range_threshold", String.valueOf((int)(450f + SAFETY_OVERRIDES_RANGE_BOOST)))),
                pad, 
                new Color[]{h, good, h},
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.so_range_highlights",
                        Moci_TextLoader.mapOf("range_threshold", String.valueOf((int)(450f + SAFETY_OVERRIDES_RANGE_BOOST)))).toArray(new String[0]));
        
        tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.penalty",
                        Moci_TextLoader.mapOf("cr_penalty", String.valueOf((int)CR_DEGRADATION_PENALTY))),
                pad,new Color[]{bad, h},
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.penalty_highlights",
                        Moci_TextLoader.mapOf("cr_penalty", String.valueOf((int)CR_DEGRADATION_PENALTY))).toArray(new String[0]));
        
        tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.note"),
                pad, h,
                Moci_TextLoader.getHighlights(TEXT_ID, "description.note_highlights").toArray(new String[0]));
    }

    @Override
    public boolean affectsOPCosts() {
        return true;
    }
} 
