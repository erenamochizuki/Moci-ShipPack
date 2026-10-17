package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;

/**
 * 机师培养系统 - 负责经验获取、等级提升和技能成长
 * 类似wingcom的机师成长机制
 */
public class Moci_PilotTraining {
    
    // 经验事件类型
    public enum ExperienceEvent {
        ENEMY_KILLED(50f),           // 击杀敌舰
        DAMAGE_DEALT(0.05f),         // 对敌造成伤害（每点伤害）
        SURVIVAL_TIME(0.3f),         // 生存时间（每秒）
        MISSION_COMPLETE(25f),       // 任务完成
        ASSIST_ALLY(8f),             // 协助友军
        OVERLOAD_ENEMY(5f),          // 触发敌舰过载
        ELITE_KILL(200f),             // 击杀精英敌舰
        LOW_HEALTH_SURVIVAL(10f),    // 低血量生存
        FORMATION_BONUS(2f),         // 编队协作奖励
        BATTLE_VICTORY(550f);       // 战斗获胜（用于测试升级功能）
        
        private final float baseExperience;
        
        ExperienceEvent(float baseExperience) {
            this.baseExperience = baseExperience;
        }
        
        public float getBaseExperience() {
            return baseExperience;
        }
    }
    
    // 等级提升所需经验 (最高7级)
    private static final Map<Integer, Float> LEVEL_THRESHOLDS = new HashMap<>();
    static {
        LEVEL_THRESHOLDS.put(1, 100f);     // 1级需要100经验
        LEVEL_THRESHOLDS.put(2, 300f);     // 2级需要300经验（累计）
        LEVEL_THRESHOLDS.put(3, 600f);     // 3级需要600经验（累计）
        LEVEL_THRESHOLDS.put(4, 1200f);    // 4级需要1200经验（累计）
        LEVEL_THRESHOLDS.put(5, 2500f);    // 5级需要2500经验（累计）
        LEVEL_THRESHOLDS.put(6, 4500f);    // 6级需要4500经验（累计）
        LEVEL_THRESHOLDS.put(7, 8000f);    // 7级需要8000经验（累计）
    }
    
    // 最大等级
    private static final int MAX_LEVEL = 7;
    
    private static final float MAX_EXPERIENCE = 30000f;
    
    /**
     * 为机师添加经验
     */
    public static void addExperience(String commanderId, int pilotIndex, ExperienceEvent event, 
                                   float multiplier, boolean persistent) {
        PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, persistent);
        if (pilot == null) {
            return;
        }
        
        // 检查是否已达满级，满级军官不再获得经验
        int currentLevel = pilot.getStats().getLevel();
        if (currentLevel >= MAX_LEVEL) {
            return;
        }
        
        float currentExp = Moci_PilotManager.getExperience(commanderId, pilotIndex, persistent);
        
        // 如果已达到满级经验，不再添加经验
        if (currentExp >= MAX_EXPERIENCE) {
            return;
        }
        
        // 简化的经验计算：基础经验 * 倍数
        float expGain = event.getBaseExperience() * multiplier;
        float newExp = Math.min(currentExp + expGain, MAX_EXPERIENCE); // 限制最大经验值
        
        // 保存新经验值
        saveExperience(commanderId, pilotIndex, newExp, persistent);
        
    }
    
    /**
     * 根据经验计算等级（修复HashMap迭代顺序问题）
     */
    private static int calculateLevel(float experience) {
        int level = 0;
        // 修复：使用确定的顺序遍历，从高到低检查
        for (int checkLevel = MAX_LEVEL; checkLevel >= 1; checkLevel--) {
            Float threshold = LEVEL_THRESHOLDS.get(checkLevel);
            if (threshold != null && experience >= threshold) {
                level = checkLevel;
                break; // 找到最高满足条件的等级就退出
            }
        }
        return level;
    }
    
    /**
     * 手动升级机师（用于界面升级按钮）
     */
    public static boolean manualLevelUpPilot(String commanderId, int pilotIndex, boolean persistent) {
        PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, persistent);
        if (pilot == null) {
            return false;
        }
        
        int currentLevel = pilot.getStats().getLevel();
        if (currentLevel >= MAX_LEVEL) {
            return false;
        }
        
        // 检查是否有足够经验升级
        float currentExp = Moci_PilotManager.getExperience(commanderId, pilotIndex, persistent);
        Float requiredExp = LEVEL_THRESHOLDS.get(currentLevel + 1);
        if (requiredExp == null || currentExp < requiredExp) {
            return false;
        }
        
        // 计算技能上限
        com.fs.starfarer.api.characters.MutableCharacterStatsAPI playerStats = Global.getSector().getPlayerFleet().getFleetData().getCommander().getStats();
        int maxLevel = (int)playerStats.getDynamic().getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.OFFICER_MAX_LEVEL_MOD).computeEffective(0) + (int)com.fs.starfarer.api.util.Misc.MAX_OFFICER_LEVEL;
        int maxElite = calculatePilotMaxEliteSkills(pilot); // 使用机师专用的精英技能上限计算
        int currentEliteSkills = com.fs.starfarer.api.util.Misc.getNumEliteSkills(pilot);
        
        // 升级到下一级
        int newLevel = currentLevel + 1;
        pilot.getStats().setLevel(newLevel);
        
        // 手动升级不消耗经验，保留当前累计
        saveExperience(commanderId, pilotIndex, currentExp, persistent);
        
        return true;
    }
    
    /**
     * 获取机师等级加成系数 (最高7级)
     */
    public static float getPilotLevelBonus(PersonAPI pilot) {
        if (pilot == null) return 0f;
        
        int level = pilot.getStats().getLevel();
        switch (level) {
            case 0: return 0f;      // 无加成
            case 1: return 0.05f;   // 5%加成
            case 2: return 0.12f;   // 12%加成
            case 3: return 0.22f;   // 22%加成
            case 4: return 0.35f;   // 35%加成
            case 5: return 0.50f;   // 50%加成
            case 6: return 0.68f;   // 68%加成
            case 7: return 0.90f;   // 90%加成
            default: return 0.90f;  // 最大90%加成
        }
    }
    
    /**
     * 保存经验值
     */
    private static void saveExperience(String commanderId, int pilotIndex, float experience, boolean persistent) {
        try {
            Map<String, Object> storage = getStorage(persistent);
            String key = "moci_combatSquad_exp_" + pilotIndex + "_" + commanderId;
            storage.put(key, experience);
        } catch (Exception e) {
            // 静默处理保存失败
        }
    }
    
    /**
     * 获取存储对象
     */
    private static Map<String, Object> getStorage(boolean persistent) {
        try {
            if (persistent && Global.getSector() != null) {
                return Global.getSector().getPersistentData();
            } else if (Global.getCombatEngine() != null) {
                return Global.getCombatEngine().getCustomData();
            } else {
                return new HashMap<>();
            }
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
    
    /**
     * 获取等级进度信息
     */
    public static String getLevelProgressInfo(float experience) {
        int currentLevel = calculateLevel(experience);
        if (currentLevel >= MAX_LEVEL) {
            return Moci_CombatSquadTextRegistry.format(
                    "pilot_training_level_max",
                    "等级 {level} (满级)",
                    "level", String.valueOf(currentLevel));
        }
        
        float nextLevelExp = LEVEL_THRESHOLDS.get(currentLevel + 1);
        float progress = (experience / nextLevelExp) * 100f;
        
        return Moci_CombatSquadTextRegistry.format(
                "pilot_training_level_progress",
                "等级 {level} ({progress}%)",
                "level", String.valueOf(currentLevel),
                "progress", String.format("%.1f", progress));
    }
    
    /**
     * 批量处理编队经验事件
     */
    public static void processSquadExperienceEvent(String commanderId, ExperienceEvent event, 
                                                 float multiplier, boolean persistent) {
        try {
            int squadSize = Moci_PilotManager.getCurrentSquadSize(commanderId, persistent);
            
            if (squadSize <= 0) {
                return;
            }
            
            for (int i = 0; i < squadSize; i++) {
                try {
                    addExperience(commanderId, i, event, multiplier, persistent);
                } catch (Exception e) {
                    // 静默处理失败
                }
            }
        } catch (Exception e) {
            // 静默处理失败
        }
    }
    
    /**
     * 计算机师的精英技能上限
     * 机师等级越高，可用的精英技能越多
     * 7级时有3个精英技能可用
     */
    public static int calculatePilotMaxEliteSkills(PersonAPI pilot) {
        if (pilot == null) return 0;
        
        int level = pilot.getStats().getLevel();
        
        // 基础精英技能数量（来自游戏设置和玩家加成）
        com.fs.starfarer.api.characters.MutableCharacterStatsAPI playerStats = Global.getSector().getPlayerFleet().getFleetData().getCommander().getStats();
        int baseElite = (int)Global.getSettings().getInt("officerMaxEliteSkills") + 
                       (int)playerStats.getDynamic().getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.OFFICER_MAX_ELITE_SKILLS_MOD).computeEffective(0);
        
        // 机师等级加成：更平缓的精英技能获得曲线
        int levelBonus = 0;
        if (level >= 1) levelBonus = 1; // 1级+1个精英技能
        if (level >= 4) levelBonus = 2; // 4级+2个精英技能（总共2个）
        if (level >= 7) levelBonus = 3; // 7级+3个精英技能（总共3个）
        
        int maxElite = baseElite + levelBonus;
        
        return maxElite;
    }
    
    /**
     * 单独的精英化技能方法
     */
    public static boolean elitePilotSkill(String commanderId, int pilotIndex, String skillId, boolean persistent) {
        PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, persistent);
        if (pilot == null) {
            return false;
        }
        
        // 检查是否已经拥有该技能
        if (!pilot.getStats().hasSkill(skillId)) {
            return false;
        }
        
        // 检查是否已经精英化
        if (pilot.getStats().getSkillLevel(skillId) >= 2) {
            return false;
        }
        
        // 检查精英技能上限
        int maxElite = calculatePilotMaxEliteSkills(pilot);
        int currentEliteSkills = com.fs.starfarer.api.util.Misc.getNumEliteSkills(pilot);
        
        if (currentEliteSkills >= maxElite) {
            return false;
        }
        
        // 精英化技能
        pilot.getStats().setSkillLevel(skillId, 2);
        
        return true;
    }
} 
