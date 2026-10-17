package data.campaign.skills;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.LevelBasedEffect;
import com.fs.starfarer.api.characters.MarketSkillEffect;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

/**
 * 欧米茄管理技能
 * 分为5个独立的效果类，便于在UI中分行显示
 */
public class Moci_OmegaAdministrationSkill {
    
    // 统一加成数值
    public static final float FLEET_SIZE_BONUS = 25f;
    public static final float GROUND_DEFENSE_BONUS = 25f;
    public static final float ACCESSIBILITY_BONUS = 0.25f;
    public static final int DEMAND_REDUCTION = 1;
    public static final float UPKEEP_MULT = 0.75f;
    
    /**
     * 效果1: 舰队规模加成
     */
    public static class FleetSize implements MarketSkillEffect {
        @Override
        public void apply(MarketAPI market, String id, float level) {
            market.getStats().getDynamic().getMod(Stats.COMBAT_FLEET_SIZE_MULT)
                .modifyFlat(id, FLEET_SIZE_BONUS / 100f, "欧米茄管理");
        }
        
        @Override
        public void unapply(MarketAPI market, String id) {
            market.getStats().getDynamic().getMod(Stats.COMBAT_FLEET_SIZE_MULT).unmodifyFlat(id);
        }
        
        @Override
        public String getEffectDescription(float level) {
            return "舰队规模 +25%";
        }
        
        @Override
        public String getEffectPerLevelDescription() {
            return null;
        }
        
        @Override
        public LevelBasedEffect.ScopeDescription getScopeDescription() {
            return LevelBasedEffect.ScopeDescription.GOVERNED_OUTPOST;
        }
    }
    
    /**
     * 效果2: 地面防御加成
     */
    public static class GroundDefense implements MarketSkillEffect {
        @Override
        public void apply(MarketAPI market, String id, float level) {
            market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD)
                .modifyMult(id, 1f + GROUND_DEFENSE_BONUS * 0.01f, "欧米茄管理");
        }
        
        @Override
        public void unapply(MarketAPI market, String id) {
            market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD).unmodify(id);
        }
        
        @Override
        public String getEffectDescription(float level) {
            return "地面防御 +25%";
        }
        
        @Override
        public String getEffectPerLevelDescription() {
            return null;
        }
        
        @Override
        public LevelBasedEffect.ScopeDescription getScopeDescription() {
            return LevelBasedEffect.ScopeDescription.GOVERNED_OUTPOST;
        }
    }
    
    /**
     * 效果3: 流通性加成
     */
    public static class Accessibility implements MarketSkillEffect {
        @Override
        public void apply(MarketAPI market, String id, float level) {
            market.getAccessibilityMod().modifyFlat(id, ACCESSIBILITY_BONUS, "欧米茄管理");
        }
        
        @Override
        public void unapply(MarketAPI market, String id) {
            market.getAccessibilityMod().unmodifyFlat(id);
        }
        
        @Override
        public String getEffectDescription(float level) {
            return "流通性 +25%";
        }
        
        @Override
        public String getEffectPerLevelDescription() {
            return null;
        }
        
        @Override
        public LevelBasedEffect.ScopeDescription getScopeDescription() {
            return LevelBasedEffect.ScopeDescription.GOVERNED_OUTPOST;
        }
    }
    
    /**
     * 效果4: 需求减少
     */
    public static class DemandReduction implements MarketSkillEffect {
        @Override
        public void apply(MarketAPI market, String id, float level) {
            market.getStats().getDynamic().getMod(Stats.DEMAND_REDUCTION_MOD)
                .modifyFlat(id, DEMAND_REDUCTION, "欧米茄管理");
        }
        
        @Override
        public void unapply(MarketAPI market, String id) {
            market.getStats().getDynamic().getMod(Stats.DEMAND_REDUCTION_MOD).unmodifyFlat(id);
        }
        
        @Override
        public String getEffectDescription(float level) {
            return "需求减少 -1";
        }
        
        @Override
        public String getEffectPerLevelDescription() {
            return null;
        }
        
        @Override
        public LevelBasedEffect.ScopeDescription getScopeDescription() {
            return LevelBasedEffect.ScopeDescription.GOVERNED_OUTPOST;
        }
    }
    
    /**
     * 效果5: 维护费用减少
     */
    public static class UpkeepReduction implements MarketSkillEffect {
        @Override
        public void apply(MarketAPI market, String id, float level) {
            market.getUpkeepMult().modifyMult(id, UPKEEP_MULT, "欧米茄管理");
        }
        
        @Override
        public void unapply(MarketAPI market, String id) {
            market.getUpkeepMult().unmodify(id);
        }
        
        @Override
        public String getEffectDescription(float level) {
            return "维护费用 -25%";
        }
        
        @Override
        public String getEffectPerLevelDescription() {
            return null;
        }
        
        @Override
        public LevelBasedEffect.ScopeDescription getScopeDescription() {
            return LevelBasedEffect.ScopeDescription.GOVERNED_OUTPOST;
        }
    }
}
