package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.ids.Skills;

/**
 * 指挥官效果系统 - 计算指挥官技能对编队的影响
 * 类似wingcom的指挥官加成机制
 */
public class Moci_CommanderEffects {
    
    // 指挥官等级基础加成系数
    private static final float BASE_LEVEL_BONUS = 0.02f; // 每级2%加成
    private static final float MAX_LEVEL_BONUS = 0.30f;  // 最大30%加成
    
    // 技能加成配置
    private static final Map<String, Float> SKILL_BONUSES = new HashMap<>();
    static {
        // 舰船操控技能
        SKILL_BONUSES.put(Skills.HELMSMANSHIP, 0.15f);         // 舵手技能
        SKILL_BONUSES.put(Skills.COMBAT_ENDURANCE, 0.12f);     // 战斗耐力
        SKILL_BONUSES.put(Skills.TARGET_ANALYSIS, 0.16f);      // 目标分析
        
        // 武器专精技能
        SKILL_BONUSES.put(Skills.BALLISTIC_MASTERY, 0.12f);    // 实弹精通
        SKILL_BONUSES.put(Skills.ENERGY_WEAPON_MASTERY, 0.12f);// 能量武器精通
        SKILL_BONUSES.put(Skills.MISSILE_SPECIALIZATION, 0.10f);// 导弹专精
        
        // 战术技能
        SKILL_BONUSES.put(Skills.COORDINATED_MANEUVERS, 0.18f); // 协调机动
        SKILL_BONUSES.put(Skills.TACTICAL_DRILLS, 0.15f);       // 战术演习
        SKILL_BONUSES.put(Skills.CARRIER_GROUP, 0.10f);         // 舰载机编队
        
        // 高级技能
        SKILL_BONUSES.put(Skills.BEST_OF_THE_BEST, 0.25f);      // 精英中的精英
        SKILL_BONUSES.put(Skills.OFFICER_MANAGEMENT, 0.20f);    // 军官管理
    }
    
    /**
     * 应用指挥官效果到僚机
     */
    public static void applyCommanderEffects(ShipAPI wingman, PersonAPI commander, String modId) {
        if (wingman == null || commander == null) return;
        
        MutableShipStatsAPI stats = wingman.getMutableStats();
        
        // 计算总加成
        float totalBonus = calculateCommanderInfluence(commander);
        
        if (totalBonus > 0f) {
            // 基础战斗能力提升
            applyBasicCombatBonuses(stats, totalBonus, modId);
            
            // 武器性能提升
            applyWeaponBonuses(stats, totalBonus, modId);
            
            // 防御和生存能力
            applyDefensiveBonuses(stats, totalBonus, modId);
            
            // 特定技能额外加成
            applySpecificSkillBonuses(stats, commander, modId);
        }
        
        Global.getLogger(Moci_CommanderEffects.class).debug(
            "应用指挥官效果: " + commander.getNameString() + 
            " -> " + wingman.getName() + " (加成: " + (totalBonus * 100f) + "%)"
        );
    }
    
    /**
     * 计算指挥官影响力
     */
    public static float calculateCommanderInfluence(PersonAPI commander) {
        if (commander == null) return 0f;
        
        float influence = 0f;
        
        // 等级影响（最多30%）
        int level = commander.getStats().getLevel();
        influence += Math.min(level * BASE_LEVEL_BONUS, MAX_LEVEL_BONUS);
        
        // 技能影响（最多40%）
        float skillBonus = 0f;
        for (Map.Entry<String, Float> entry : SKILL_BONUSES.entrySet()) {
            String skillId = entry.getKey();
            float bonus = entry.getValue();
            
            if (commander.getStats().hasSkill(skillId)) {
                float skillLevel = commander.getStats().getSkillLevel(skillId);
                skillBonus += bonus * skillLevel;
            }
        }
        influence += Math.min(skillBonus, 0.40f);
        
        // 特殊标签影响（最多30%）
        float tagBonus = 0f;
        if (commander.hasTag("player")) {
            tagBonus += 0.15f; // 玩家指挥官15%加成
        }
        if (commander.hasTag("elite")) {
            tagBonus += 0.10f; // 精英指挥官10%加成
        }
        if (commander.hasTag("legendary")) {
            tagBonus += 0.20f; // 传奇指挥官20%加成
        }
        influence += Math.min(tagBonus, 0.30f);
        
        // 总影响力上限100%
        return Math.min(influence, 1.0f);
    }
    
    /**
     * 应用基础战斗加成
     */
    private static void applyBasicCombatBonuses(MutableShipStatsAPI stats, float bonus, String modId) {
        // 机动性提升
        stats.getMaxSpeed().modifyPercent(modId + "_commander_speed", bonus * 20f);
        stats.getAcceleration().modifyPercent(modId + "_commander_accel", bonus * 25f);
        stats.getDeceleration().modifyPercent(modId + "_commander_decel", bonus * 25f);
        stats.getMaxTurnRate().modifyPercent(modId + "_commander_turn", bonus * 30f);
        stats.getTurnAcceleration().modifyPercent(modId + "_commander_turn_accel", bonus * 30f);
    }
    
    /**
     * 应用武器性能加成
     */
    private static void applyWeaponBonuses(MutableShipStatsAPI stats, float bonus, String modId) {
        // 武器转速和射程
        stats.getWeaponTurnRateBonus().modifyPercent(modId + "_commander_weapon_turn", bonus * 40f);
        stats.getBallisticWeaponRangeBonus().modifyPercent(modId + "_commander_ballistic_range", bonus * 15f);
        stats.getEnergyWeaponRangeBonus().modifyPercent(modId + "_commander_energy_range", bonus * 15f);
        
        // 武器精度
        stats.getAutofireAimAccuracy().modifyPercent(modId + "_commander_accuracy", bonus * 25f);
    }
    
    /**
     * 应用防御加成
     */
    private static void applyDefensiveBonuses(MutableShipStatsAPI stats, float bonus, String modId) {
        // 减伤和生存能力
        stats.getHullDamageTakenMult().modifyMult(modId + "_commander_hull_dmg", 1f - bonus * 0.15f);
        stats.getCrewLossMult().modifyMult(modId + "_commander_crew_loss", 1f - bonus * 0.20f);
        
        // 护盾和装甲
        stats.getShieldUpkeepMult().modifyMult(modId + "_commander_shield", 1f - bonus * 0.10f);
        stats.getArmorDamageTakenMult().modifyMult(modId + "_commander_armor", 1f - bonus * 0.10f);
        
        // 维修时间
        stats.getCombatEngineRepairTimeMult().modifyMult(modId + "_commander_repair", 1f - bonus * 0.25f);
    }
    
    /**
     * 应用特定技能的额外加成
     */
    private static void applySpecificSkillBonuses(MutableShipStatsAPI stats, PersonAPI commander, String modId) {
        // 舵手技能 - 机动性加成
        if (commander.getStats().hasSkill(Skills.HELMSMANSHIP)) {
            float skillLevel = commander.getStats().getSkillLevel(Skills.HELMSMANSHIP);
            stats.getMaxSpeed().modifyPercent(modId + "_helmsmanship", skillLevel * 8f);
            stats.getMaxTurnRate().modifyPercent(modId + "_helmsmanship", skillLevel * 12f);
        }
        
        // 战斗耐力 - 幅能和散热
        if (commander.getStats().hasSkill(Skills.COMBAT_ENDURANCE)) {
            float skillLevel = commander.getStats().getSkillLevel(Skills.COMBAT_ENDURANCE);
            stats.getFluxCapacity().modifyPercent(modId + "_endurance", skillLevel * 10f);
            stats.getFluxDissipation().modifyPercent(modId + "_endurance", skillLevel * 12f);
        }
        
        // 目标分析 - 武器精度
        if (commander.getStats().hasSkill(Skills.TARGET_ANALYSIS)) {
            float skillLevel = commander.getStats().getSkillLevel(Skills.TARGET_ANALYSIS);
            stats.getAutofireAimAccuracy().modifyPercent(modId + "_target_analysis", skillLevel * 15f);
            stats.getWeaponTurnRateBonus().modifyPercent(modId + "_target_analysis", skillLevel * 20f);
        }
        
        // 实弹精通 - 实弹武器加成
        if (commander.getStats().hasSkill(Skills.BALLISTIC_MASTERY)) {
            float skillLevel = commander.getStats().getSkillLevel(Skills.BALLISTIC_MASTERY);
            stats.getBallisticWeaponDamageMult().modifyMult(modId + "_ballistic", 1f + skillLevel * 0.08f);
            stats.getBallisticWeaponRangeBonus().modifyPercent(modId + "_ballistic", skillLevel * 10f);
        }
        
        // 能量武器精通 - 能量武器加成
        if (commander.getStats().hasSkill(Skills.ENERGY_WEAPON_MASTERY)) {
            float skillLevel = commander.getStats().getSkillLevel(Skills.ENERGY_WEAPON_MASTERY);
            stats.getEnergyWeaponDamageMult().modifyMult(modId + "_energy", 1f + skillLevel * 0.08f);
            stats.getEnergyWeaponRangeBonus().modifyPercent(modId + "_energy", skillLevel * 10f);
        }
        
        // 导弹专精 - 导弹加成
        if (commander.getStats().hasSkill(Skills.MISSILE_SPECIALIZATION)) {
            float skillLevel = commander.getStats().getSkillLevel(Skills.MISSILE_SPECIALIZATION);
            stats.getMissileWeaponDamageMult().modifyMult(modId + "_missile", 1f + skillLevel * 0.10f);
            stats.getMissileRoFMult().modifyMult(modId + "_missile_rof", 1f + skillLevel * 0.05f);
        }
        
        // 协调机动 - 编队协作加成
        if (commander.getStats().hasSkill(Skills.COORDINATED_MANEUVERS)) {
            float skillLevel = commander.getStats().getSkillLevel(Skills.COORDINATED_MANEUVERS);
            stats.getAcceleration().modifyPercent(modId + "_coordinated", skillLevel * 15f);
            stats.getDeceleration().modifyPercent(modId + "_coordinated", skillLevel * 15f);
        }
        
        // 战术演习 - 反应速度加成
        if (commander.getStats().hasSkill(Skills.TACTICAL_DRILLS)) {
            float skillLevel = commander.getStats().getSkillLevel(Skills.TACTICAL_DRILLS);
            stats.getWeaponTurnRateBonus().modifyPercent(modId + "_tactical", skillLevel * 25f);
            stats.getBeamWeaponTurnRateBonus().modifyPercent(modId + "_tactical", skillLevel * 25f);
        }
    }
    
    /**
     * 获取指挥官评级标题
     */
    public static String getCommanderRating(PersonAPI commander) {
        if (commander == null) return Moci_CombatSquadTextRegistry.getText("commander_rating_unknown", "未知指挥官");
        
        float influence = calculateCommanderInfluence(commander);
        
        if (influence >= 0.8f) {
            return Moci_CombatSquadTextRegistry.getText("commander_rating_legendary", "传奇指挥官");
        } else if (influence >= 0.6f) {
            return Moci_CombatSquadTextRegistry.getText("commander_rating_elite", "精英指挥官");
        } else if (influence >= 0.4f) {
            return Moci_CombatSquadTextRegistry.getText("commander_rating_veteran", "资深指挥官");
        } else if (influence >= 0.2f) {
            return Moci_CombatSquadTextRegistry.getText("commander_rating_experienced", "经验指挥官");
        } else {
            return Moci_CombatSquadTextRegistry.getText("commander_rating_regular", "普通指挥官");
        }
    }
    
    /**
     * 获取指挥官影响力详细信息
     */
    public static String getCommanderInfluenceDetails(PersonAPI commander) {
        if (commander == null) return Moci_CombatSquadTextRegistry.getText("commander_details_none", "无指挥官信息");
        
        StringBuilder details = new StringBuilder();
        float totalInfluence = calculateCommanderInfluence(commander);
        
        details.append(Moci_CombatSquadTextRegistry.getText("commander_details_commander", "指挥官: "))
                .append(commander.getNameString()).append("\n");
        details.append(Moci_CombatSquadTextRegistry.getText("commander_details_total_influence", "总影响力: "))
                .append(String.format("%.1f%%", totalInfluence * 100f)).append("\n");
        details.append(Moci_CombatSquadTextRegistry.getText("commander_details_rating", "评级: "))
                .append(getCommanderRating(commander)).append("\n\n");
        
        // 等级影响
        int level = commander.getStats().getLevel();
        float levelInfluence = Math.min(level * BASE_LEVEL_BONUS, MAX_LEVEL_BONUS);
        details.append(Moci_CombatSquadTextRegistry.getText("commander_details_level_prefix", "等级影响 ("))
               .append(level)
               .append(Moci_CombatSquadTextRegistry.getText("commander_details_level_suffix", "级): "))
               .append(String.format("%.1f%%", levelInfluence * 100f)).append("\n");
        
        // 技能影响
        details.append(Moci_CombatSquadTextRegistry.getText("commander_details_skill_bonus", "技能加成:\n"));
        for (Map.Entry<String, Float> entry : SKILL_BONUSES.entrySet()) {
            String skillId = entry.getKey();
            if (commander.getStats().hasSkill(skillId)) {
                float skillLevel = commander.getStats().getSkillLevel(skillId);
                float bonus = entry.getValue() * skillLevel;
                String skillName = Global.getSettings().getSkillSpec(skillId).getName();
                details.append(Moci_CombatSquadTextRegistry.getText("commander_details_skill_prefix", "  - "))
                       .append(skillName).append(" (").append((int)skillLevel)
                       .append(Moci_CombatSquadTextRegistry.getText("commander_details_skill_suffix", "级): "))
                       .append(String.format("%.1f%%", bonus * 100f)).append("\n");
            }
        }
        
        return details.toString();
    }
    
    /**
     * 移除指挥官效果
     */
    public static void removeCommanderEffects(ShipAPI wingman, String modId) {
        if (wingman == null) return;
        
        MutableShipStatsAPI stats = wingman.getMutableStats();
        
        // 移除所有相关的修正效果
        String[] effectTypes = {
            "_commander_speed", "_commander_accel", "_commander_decel", "_commander_turn", "_commander_turn_accel",
            "_commander_weapon_turn", "_commander_ballistic_range", "_commander_energy_range", "_commander_accuracy",
            "_commander_hull_dmg", "_commander_crew_loss", "_commander_shield", "_commander_armor", "_commander_repair",
            "_helmsmanship", "_endurance", "_target_analysis", "_ballistic", "_energy", "_missile", "_missile_rof",
            "_coordinated", "_tactical"
        };
        
        for (String effectType : effectTypes) {
            String effectId = modId + effectType;
            stats.getMaxSpeed().unmodifyPercent(effectId);
            stats.getAcceleration().unmodifyPercent(effectId);
            stats.getDeceleration().unmodifyPercent(effectId);
            stats.getMaxTurnRate().unmodifyPercent(effectId);
            stats.getTurnAcceleration().unmodifyPercent(effectId);
            stats.getWeaponTurnRateBonus().unmodifyPercent(effectId);
            stats.getBallisticWeaponRangeBonus().unmodifyPercent(effectId);
            stats.getEnergyWeaponRangeBonus().unmodifyPercent(effectId);
            stats.getAutofireAimAccuracy().unmodifyPercent(effectId);
            stats.getHullDamageTakenMult().unmodifyMult(effectId);
            stats.getCrewLossMult().unmodifyMult(effectId);
            stats.getShieldUpkeepMult().unmodifyMult(effectId);
            stats.getArmorDamageTakenMult().unmodifyMult(effectId);
            stats.getCombatEngineRepairTimeMult().unmodifyMult(effectId);
            stats.getFluxCapacity().unmodifyPercent(effectId);
            stats.getFluxDissipation().unmodifyPercent(effectId);
            stats.getBallisticWeaponDamageMult().unmodifyMult(effectId);
            stats.getEnergyWeaponDamageMult().unmodifyMult(effectId);
            stats.getMissileWeaponDamageMult().unmodifyMult(effectId);
            stats.getMissileRoFMult().unmodifyMult(effectId);
            stats.getBeamWeaponTurnRateBonus().unmodifyPercent(effectId);
        }
        
        Global.getLogger(Moci_CommanderEffects.class).debug(
            "移除指挥官效果: " + wingman.getName()
        );
    }
} 
