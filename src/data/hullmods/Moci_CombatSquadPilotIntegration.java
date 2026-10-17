package data.hullmods;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * CombatSquad机师系统整合器
 * 负责协调机师管理、培养、指挥官效果等各组件与CombatSquad系统的交互
 * 提供统一的API接口，类似wingcom的核心管理模块
 */
public class Moci_CombatSquadPilotIntegration {
    
    // 系统启用状态 (现在通过配置管理器控制)
    // private static boolean pilotSystemEnabled = true; // 已移到配置管理器
    
    // 更新间隔控制
    private static final IntervalUtil updateInterval = new IntervalUtil(0.5f, 1.0f);
    
    // 经验事件间隔控制（避免频繁触发）
    private static final IntervalUtil experienceInterval = new IntervalUtil(1.0f, 2.0f);
    
    /**
     * 主要整合方法 - 在CombatSquad船插中调用
     * @param motherShip 母舰
     * @param amount 时间增量
     */
    public static void integrateWithCombatSquad(ShipAPI motherShip, float amount) {
        // 检查配置：如果系统禁用，则不运行动态机师系统
        if (!Moci_CombatSquadPilotConfig.isPilotSystemEnabled() || motherShip == null) return;
        
        // 重要：只有玩家势力舰船才使用动态机师系统，其他势力舰船使用原本的固定配置
        if (!isPlayerShip(motherShip)) return;
        
        PersonAPI commander = motherShip.getCaptain();
        
        // 检查军官情况，决定使用哪种模式
        if (commander == null || commander.isDefault()) {
            // 情况1：无军官 -> 使用固定配置，不运行动态机师系统
            return;
        }
        
        // 情况2：有军官 -> 检查是否启用动态机师模式
        if (!Moci_CombatSquadPilotConfig.useDynamicPilots()) {
            // 动态机师模式未启用，使用固定军官配置
            return;
        }
        
        // 情况3：有军官且启用动态模式 -> 运行完整的动态机师系统
        updateInterval.advance(amount);
        if (!updateInterval.intervalElapsed()) return;
        
        try {
            // 确定是否为持久化模式
            boolean persistent = Global.getSector() != null;
            
            // 获取编队规模
            int squadSize = getSquadSize(motherShip);
            if (squadSize <= 0) return;
            
            // 检查军官换船情况：如果军官已有小队但当前编队规模不匹配，说明换船了
            handleOfficerTransfer(motherShip, commander, squadSize, persistent);
            
            // 初始化或更新机师编队
            initializeOrUpdateSquad(motherShip, commander, squadSize, persistent);
            
            // 应用机师和指挥官效果到僚机
            applyPilotEffectsToWingmen(motherShip, commander, squadSize, persistent);
            
            // 处理经验获取（仅在战斗中）
            if (!persistent && Global.getCombatEngine() != null) {
                handleCombatExperience(motherShip, commander, squadSize, amount);
            }
            
        } catch (Exception e) {
            Global.getLogger(Moci_CombatSquadPilotIntegration.class).error(
                "机师系统整合错误: " + e.getMessage(), e
            );
        }
    }
    
    /**
     * 处理军官换船情况
     * 当军官已有小队但当前舰船编队配置不同时，自动调整小队规模
     */
    private static void handleOfficerTransfer(ShipAPI motherShip, PersonAPI commander, 
                                            int newSquadSize, boolean persistent) {
        String commanderId = commander.getId();
        int existingSquadSize = Moci_PilotManager.getCurrentSquadSize(commanderId, persistent);
        
        // 检查是否为军官换船的情况
        if (existingSquadSize > 0 && existingSquadSize != newSquadSize) {
            // 如果新编队更大，创建额外机师
            if (newSquadSize > existingSquadSize) {
                for (int i = existingSquadSize; i < newSquadSize; i++) {
                    createSinglePilotForOfficer(commander, i, persistent);
                }
            }
            // 如果新编队更小，暂时保留多余机师但标记为未分配
            else {
                for (int i = newSquadSize; i < existingSquadSize; i++) {
                    Moci_PilotManager.setPilotAssigned(commanderId, i, false, persistent);
                }
            }
            
            // 更新编队规模记录
            Moci_PilotManager.setSquadSize(commanderId, newSquadSize, persistent);
        }
    }
    
    /**
     * 为军官创建单个机师（用于军官换船时的扩展）
     */
    private static void createSinglePilotForOfficer(PersonAPI commander, int index, boolean persistent) {
        try {
            // 使用现有的机师创建逻辑，但只创建单个机师
            // 这里需要调用PilotManager的内部方法
            // 由于createSinglePilot是私有的，我们临时创建一个只有1个机师的编队然后获取
            
            String commanderId = commander.getId();
            PersonAPI existingPilot = Moci_PilotManager.getPilot(commanderId, index, persistent);
            
            if (existingPilot == null) {
                // 需要创建新机师 - 使用一个临时的小技巧
                // 先备份当前的编队规模
                int currentSize = Moci_PilotManager.getCurrentSquadSize(commanderId, persistent);
                
                // 临时设置编队规模为index+1来触发机师创建
                Moci_PilotManager.createSquadPilots(commander, null, index + 1, persistent);
                
                // 恢复原编队规模（如果需要）
                if (currentSize > 0 && currentSize != index + 1) {
                    Moci_PilotManager.setSquadSize(commanderId, Math.max(currentSize, index + 1), persistent);
                }
                
            }
        } catch (Exception e) {
            Global.getLogger(Moci_CombatSquadPilotIntegration.class).error(
                "为军官换船创建机师失败: " + e.getMessage(), e
            );
        }
    }
    
    /**
     * 初始化或更新编队机师
     */
    private static void initializeOrUpdateSquad(ShipAPI motherShip, PersonAPI commander, 
                                              int squadSize, boolean persistent) {
        String commanderId = commander.getId();
        
        // 检查是否需要创建新机师
        int currentSquadSize = Moci_PilotManager.getCurrentSquadSize(commanderId, persistent);
        if (currentSquadSize != squadSize) {
            Moci_PilotManager.createSquadPilots(commander, motherShip, squadSize, persistent);
        }
    }
    
    /**
     * 应用机师和指挥官效果到僚机
     */
    private static void applyPilotEffectsToWingmen(ShipAPI motherShip, PersonAPI commander, 
                                                 int squadSize, boolean persistent) {
        String commanderId = commander.getId();
        String modId = "moci_combatSquad_pilot_" + commanderId;
        
        // 获取僚机列表
        List<ShipAPI> wingmen = getWingmenFromSquad(motherShip, squadSize);
        
        for (int i = 0; i < Math.min(wingmen.size(), squadSize); i++) {
            ShipAPI wingman = wingmen.get(i);
            if (wingman == null || !wingman.isAlive()) continue;
            
            // 获取对应的机师
            PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, i, persistent);
            if (pilot != null) {
                // 应用机师技能加成
                applyPilotBonuses(wingman, pilot, modId + "_" + i);
                
                // 将机师分配给僚机（如果还没有分配）
                if (wingman.getCaptain() == null || wingman.getCaptain().isDefault()) {
                    wingman.setCaptain(pilot);
                }
            }
            
            // 应用指挥官效果
            Moci_CommanderEffects.applyCommanderEffects(wingman, commander, modId + "_commander_" + i);
        }
    }
    
    /**
     * 应用机师技能加成到僚机
     */
    private static void applyPilotBonuses(ShipAPI wingman, PersonAPI pilot, String modId) {
        if (wingman == null || pilot == null) return;
        
        // 获取机师等级加成
        float levelBonus = Moci_PilotTraining.getPilotLevelBonus(pilot);
        
        if (levelBonus > 0f) {
            // 基础属性加成
            wingman.getMutableStats().getMaxSpeed().modifyPercent(modId + "_pilot_level", levelBonus * 20f);
            wingman.getMutableStats().getAcceleration().modifyPercent(modId + "_pilot_level", levelBonus * 30f);
            wingman.getMutableStats().getDeceleration().modifyPercent(modId + "_pilot_level", levelBonus * 30f);
            wingman.getMutableStats().getMaxTurnRate().modifyPercent(modId + "_pilot_level", levelBonus * 25f);
            wingman.getMutableStats().getTurnAcceleration().modifyPercent(modId + "_pilot_level", levelBonus * 25f);
            
            // 武器相关加成
            wingman.getMutableStats().getBallisticWeaponRangeBonus().modifyPercent(modId + "_pilot_level", levelBonus * 15f);
            wingman.getMutableStats().getEnergyWeaponRangeBonus().modifyPercent(modId + "_pilot_level", levelBonus * 15f);
            wingman.getMutableStats().getWeaponTurnRateBonus().modifyPercent(modId + "_pilot_level", levelBonus * 40f);
            
            // 防御相关加成
            wingman.getMutableStats().getShieldUpkeepMult().modifyMult(modId + "_pilot_level", 1f - levelBonus * 0.3f);
        }
        
        // 应用特殊天赋加成 (暂时禁用)
        // applySpecialTalentBonuses(wingman, pilot, modId);
    }
    
    /**
     * 应用特殊天赋加成 (暂时禁用 - 特殊天赋系统设计中)
     */
    private static void applySpecialTalentBonuses(ShipAPI wingman, PersonAPI pilot, String modId) {
        // 特殊天赋系统暂时注释，等待后续设计
        
        /* TODO: 特殊天赋系统设计
        // 各种特殊天赋的具体加成
        float aceBonus = Moci_PilotTraining.getSpecialTalentBonus(pilot, "ace_pilot");
        if (aceBonus > 0f) {
            wingman.getMutableStats().getMaxSpeed().modifyPercent(modId + "_ace", aceBonus * 100f);
            wingman.getMutableStats().getCombatEngineRepairTimeMult().modifyMult(modId + "_ace", 1f - aceBonus);
        }
        
        float sharpshooterBonus = Moci_PilotTraining.getSpecialTalentBonus(pilot, "weapon_turn_rate");
        if (sharpshooterBonus > 0f) {
            wingman.getMutableStats().getWeaponTurnRateBonus().modifyPercent(modId + "_sharpshooter", sharpshooterBonus * 100f);
            wingman.getMutableStats().getAutofireAimAccuracy().modifyPercent(modId + "_sharpshooter", sharpshooterBonus * 100f);
        }
        
        float engineerBonus = Moci_PilotTraining.getSpecialTalentBonus(pilot, "system_cooldown");
        if (engineerBonus > 0f) {
            wingman.getMutableStats().getSystemCooldownBonus().modifyPercent(modId + "_engineer", engineerBonus * 100f);
            wingman.getMutableStats().getSystemUsesBonus().modifyFlat(modId + "_engineer", 1f);
        }
        
        float tacticianBonus = Moci_PilotTraining.getSpecialTalentBonus(pilot, "acceleration");
        if (tacticianBonus > 0f) {
            wingman.getMutableStats().getAcceleration().modifyPercent(modId + "_tactician", tacticianBonus * 100f);
            wingman.getMutableStats().getSensorProfile().modifyPercent(modId + "_tactician", -tacticianBonus * 50f);
        }
        
        float survivorBonus = Moci_PilotTraining.getSpecialTalentBonus(pilot, "hull_damage_reduction");
        if (survivorBonus > 0f) {
            wingman.getMutableStats().getHullDamageTakenMult().modifyMult(modId + "_survivor", 1f - survivorBonus);
            wingman.getMutableStats().getCrewLossMult().modifyMult(modId + "_survivor", 1f - survivorBonus);
        }
        */
    }
    
    /**
     * 处理战斗中的经验获取
     */
    private static void handleCombatExperience(ShipAPI motherShip, PersonAPI commander, 
                                             int squadSize, float amount) {
        experienceInterval.advance(amount);
        if (!experienceInterval.intervalElapsed()) return;
        
        String commanderId = commander.getId();
        
        // 生存时间经验
        Moci_PilotTraining.processSquadExperienceEvent(commanderId, 
            Moci_PilotTraining.ExperienceEvent.SURVIVAL_TIME, 1.0f, false);
        
        // 编队协作奖励（如果僚机在合理距离内）
        List<ShipAPI> wingmen = getWingmenFromSquad(motherShip, squadSize);
        int nearbyWingmen = 0;
        for (ShipAPI wingman : wingmen) {
            if (wingman != null && wingman.isAlive() && 
                isWithinFormationRange(motherShip, wingman)) {
                nearbyWingmen++;
            }
        }
        
        if (nearbyWingmen >= 2) {
            Moci_PilotTraining.processSquadExperienceEvent(commanderId, 
                Moci_PilotTraining.ExperienceEvent.FORMATION_BONUS, 1.0f, false);
        }
    }
    
    /**
     * 处理特定经验事件
     */
    public static void handleExperienceEvent(ShipAPI ship, Moci_PilotTraining.ExperienceEvent event, 
                                           float multiplier) {
        PersonAPI commander = ship.getCaptain();
        if (commander == null || commander.isDefault()) return;
        
        boolean persistent = Global.getSector() != null;
        String commanderId = commander.getId();
        
        // 为整个编队添加经验
        Moci_PilotTraining.processSquadExperienceEvent(commanderId, event, multiplier, persistent);
        
        Global.getLogger(Moci_CombatSquadPilotIntegration.class).debug(
            "处理编队经验事件: " + event.name() + " (指挥官: " + commander.getNameString() + ")"
        );
    }
    
    /**
     * 获取编队摘要信息
     */
    public static String getSquadSummary(ShipAPI motherShip, PersonAPI commander) {
        StringBuilder summary = new StringBuilder();
        
        // 检查系统状态
        if (!isPilotSystemEnabled()) {
            summary.append("机师系统已禁用\n");
            return summary.toString();
        }
        
        // 检查是否为玩家势力舰船
        if (!isPlayerShip(motherShip)) {
            summary.append("非玩家势力舰船 - 使用标准配置\n");
            return summary.toString();
        }
        
        // 检查军官情况
        if (commander == null || commander.isDefault()) {
            summary.append("无军官配置 - 使用固定军官配置\n");
            return summary.toString();
        }
        
        // 检查动态机师模式
        if (!Moci_CombatSquadPilotConfig.useDynamicPilots()) {
            summary.append("固定军官模式\n");
            summary.append("指挥官: ").append(commander.getNameString()).append("\n");
            return summary.toString();
        }
        
        // 动态机师模式 - 显示详细信息
        boolean persistent = Global.getSector() != null;
        String commanderId = commander.getId();
        
        summary.append("=== 动态机师编队 ===\n");
        summary.append("指挥官: ").append(commander.getNameString()).append("\n");
        
        // 编队名称
        String squadName = Moci_PilotManager.getSquadName(commanderId, persistent);
        if (squadName != null) {
            summary.append("编队: ").append(squadName).append("\n");
        }
        
        // 编队规模和状态
        int squadSize = Moci_PilotManager.getCurrentSquadSize(commanderId, persistent);
        if (squadSize > 0) {
            summary.append("编队规模: ").append(squadSize).append("\n");
        
            // 统计机师信息
            int activePilots = 0;
            float totalExperience = 0f;
            int totalLevel = 0;
            
        for (int i = 0; i < squadSize; i++) {
            PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, i, persistent);
            if (pilot != null) {
                    activePilots++;
                    float exp = Moci_PilotManager.getExperience(commanderId, i, persistent);
                    totalExperience += exp;
                    totalLevel += pilot.getStats().getLevel();
                }
            }
            
            if (activePilots > 0) {
                float avgLevel = (float) totalLevel / activePilots;
                summary.append("活跃机师: ").append(activePilots).append("/").append(squadSize).append("\n");
                summary.append("平均等级: ").append(String.format("%.1f", avgLevel)).append("\n");
                summary.append("总经验: ").append(String.format("%.1f", totalExperience)).append("\n");
            } else {
                summary.append("编队未初始化\n");
            }
        } else {
            summary.append("编队规模: 未设置\n");
        }
        
        // 指挥官评级
        summary.append("指挥官评级: ").append(Moci_CommanderEffects.getCommanderRating(commander)).append("\n");
        
        return summary.toString();
    }
    
    /**
     * 获取编队规模（从CombatSquad系统获取）
     */
    private static int getSquadSize(ShipAPI motherShip) {
        // 从CombatSquadV2系统获取编队配置
        List<Moci_CombatSquadOfficierManager.SquadMember> config = Moci_CombatSquadV2.getSquadConfig(motherShip);
        if (config == null || config.isEmpty()) {
            return 0; // 没有配置编队
        }
        
        // 计算总编队规模
        int totalSize = 0;
        for (Moci_CombatSquadOfficierManager.SquadMember member : config) {
            totalSize += member.count;
        }
        
        return totalSize;
    }
    
    /**
     * 获取僚机列表（从CombatSquad系统获取）
     */
    private static List<ShipAPI> getWingmenFromSquad(ShipAPI motherShip, int squadSize) {
        List<ShipAPI> wingmen = new java.util.ArrayList<>();
        
        // 在战斗中，通过搜索使用CombatSquadAI的舰船来找到僚机
        if (Global.getCombatEngine() != null && motherShip != null) {
            for (ShipAPI ship : Global.getCombatEngine().getShips()) {
                if (ship.getShipAI() != null && 
                    ship.getShipAI().getClass().getSimpleName().equals("Moci_CombatSquadAIV2")) {
                    
                    // 检查这个僚机是否属于当前母舰
                    // 这需要通过距离判断或者AI内部状态来确定
                    if (isWingmanOfMother(ship, motherShip)) {
                        wingmen.add(ship);
                    }
                }
            }
        }
        
        return wingmen;
    }
    
    /**
     * 检查僚机是否属于指定的母舰
     */
    private static boolean isWingmanOfMother(ShipAPI wingman, ShipAPI motherShip) {
        if (wingman == null || motherShip == null) return false;
        
        // 简单的距离检查 - 僚机通常在母舰周围一定范围内
        float distance = com.fs.starfarer.api.util.Misc.getDistance(
            wingman.getLocation(), motherShip.getLocation()
        );
        
        // 如果僚机在母舰1500单位范围内，认为它属于这个编队
        // 这个值可以根据实际情况调整
        if (distance > 1500f) return false;
        
        // 额外检查：确保不是敌方舰船
        return wingman.getOwner() == motherShip.getOwner();
    }
    
    /**
     * 检查僚机是否在编队范围内
     */
    private static boolean isWithinFormationRange(ShipAPI motherShip, ShipAPI wingman) {
        if (motherShip == null || wingman == null) return false;
        
        float distance = com.fs.starfarer.api.util.Misc.getDistance(
            motherShip.getLocation(), wingman.getLocation()
        );
        
        // 编队范围设定为800单位
        return distance <= 800f;
    }
    
    /**
     * 设置机师系统启用状态 (通过配置管理器)
     */
    public static void setPilotSystemEnabled(boolean enabled) {
        Moci_CombatSquadPilotConfig.setPilotSystemEnabled(enabled);
    }
    
    /**
     * 获取机师系统启用状态 (通过配置管理器)
     */
    public static boolean isPilotSystemEnabled() {
        return Moci_CombatSquadPilotConfig.isPilotSystemEnabled();
    }
    
    /**
     * 刷新所有编队数据
     */
    public static void refreshAllSquads() {
        // 这里可以添加刷新逻辑，比如重新初始化所有编队的机师数据
    }
    
    /**
     * 清理特定编队的数据
     */
    public static void cleanupSquad(ShipAPI motherShip, PersonAPI commander) {
        if (commander == null) return;
        
        boolean persistent = Global.getSector() != null;
        String commanderId = commander.getId();
        
        Moci_PilotManager.cleanupSquadData(commanderId, persistent);
    }
    
    /**
     * 检查是否为玩家势力舰船
     * 只有玩家势力舰船才使用动态机师系统，其他势力舰船使用固定配置
     */
    private static boolean isPlayerShip(ShipAPI ship) {
        if (ship == null) return false;
        
        // 在战役模式中，使用玩家势力ID检查
        if (Global.getSector() != null && ship.getFleetMember() != null) {
            FactionAPI playerFaction = Global.getSector().getPlayerFaction();
            if (playerFaction != null && ship.getFleetMember().getFleetData() != null) {
                // 通过FleetMemberAPI获取舰船所属势力
                FactionAPI shipFaction = ship.getFleetMember().getFleetData().getFleet().getFaction();
                return shipFaction != null && 
                       shipFaction.getId().equals(playerFaction.getId());
            }
        }
        
        // 在战斗模式中，使用owner检查（0通常是玩家）
        // 这是fallback方案，用于战斗中无法访问sector的情况
        return ship.getOriginalOwner() == 0;
    }
    
    /**
     * 获取详细的编队统计信息
     */
    public static String getDetailedSquadStats(ShipAPI motherShip, PersonAPI commander) {
        StringBuilder stats = new StringBuilder();
        
        // 检查系统状态
        if (!isPilotSystemEnabled()) {
            stats.append("=== 机师系统状态 ===\n");
            stats.append("系统状态: 已禁用\n");
            stats.append("所有CombatSquad僚机将没有军官\n");
            return stats.toString();
        }
        
        // 检查是否为玩家势力舰船
        if (!isPlayerShip(motherShip)) {
            stats.append("=== 非玩家势力舰船 ===\n");
            stats.append("舰船类型: 非玩家势力\n");
            stats.append("配置模式: 标准NPC配置\n");
            stats.append("说明: 非玩家势力舰船使用游戏默认的舰船配置\n");
            return stats.toString();
        }
        
        // 检查军官情况
        if (commander == null || commander.isDefault()) {
            stats.append("=== 无军官配置 ===\n");
            stats.append("指挥官状态: 无军官\n");
            stats.append("配置模式: 固定军官配置\n");
            stats.append("说明: 将使用预设的固定军官配置生成僚机\n");
            stats.append("建议: 为舰船分配军官以使用动态机师系统\n");
            return stats.toString();
        }
        
        // 检查动态机师模式
        if (!Moci_CombatSquadPilotConfig.useDynamicPilots()) {
            stats.append("=== 固定军官模式 ===\n");
            stats.append("指挥官: ").append(commander.getNameString()).append("\n");
            stats.append("配置模式: 固定军官配置\n");
            stats.append("说明: 使用传统CombatSquad系统的固定军官分配\n");
            stats.append("特点: 军官属性固定，无经验系统和升级\n");
            stats.append("性能: 低系统负担，稳定性能\n");
            return stats.toString();
        }
        
        // 动态机师模式 - 显示详细统计
        boolean persistent = Global.getSector() != null;
        String commanderId = commander.getId();
        int squadSize = Moci_PilotManager.getCurrentSquadSize(commanderId, persistent);
        
        stats.append("=== 动态机师编队详细统计 ===\n");
        stats.append("指挥官: ").append(commander.getNameString()).append("\n");
        
        // 编队基本信息
        String squadName = Moci_PilotManager.getSquadName(commanderId, persistent);
        if (squadName != null) {
            stats.append("编队名称: ").append(squadName).append("\n");
        } else {
            stats.append("编队名称: 未设置\n");
        }
        
        stats.append("编队规模: ").append(squadSize).append("\n");
        stats.append("指挥官等级: ").append(commander.getStats().getLevel()).append("\n");
        stats.append(Moci_CommanderEffects.getCommanderInfluenceDetails(commander));
        
        if (squadSize > 0) {
            stats.append("\n=== 编队成员详情 ===\n");
        
        float totalExperience = 0f;
        int totalLevel = 0;
            int activePilots = 0;
        
        for (int i = 0; i < squadSize; i++) {
            PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, i, persistent);
            if (pilot != null) {
                String callsign = Moci_PilotManager.getCallsign(commanderId, i, persistent);
                float exp = Moci_PilotManager.getExperience(commanderId, i, persistent);
                int level = pilot.getStats().getLevel();
                
                    activePilots++;
                totalExperience += exp;
                totalLevel += level;
                
                stats.append(String.format("机师%d: %s (等级%d, 经验%.1f)\n", 
                    i + 1, callsign, level, exp));
                
                    // 特殊天赋显示
                    boolean hasSpecialTalent = false;
                    if (pilot.hasTag("moci_ace_pilot")) {
                        stats.append("  - 王牌机师\n");
                        hasSpecialTalent = true;
                    }
                    if (pilot.hasTag("moci_sharpshooter")) {
                        stats.append("  - 神射手\n");
                        hasSpecialTalent = true;
                    }
                    if (pilot.hasTag("moci_engineer")) {
                        stats.append("  - 工程师\n");
                        hasSpecialTalent = true;
                    }
                    if (pilot.hasTag("moci_tactician")) {
                        stats.append("  - 战术家\n");
                        hasSpecialTalent = true;
                    }
                    if (pilot.hasTag("moci_survivor")) {
                        stats.append("  - 幸存者\n");
                        hasSpecialTalent = true;
                    }
                    if (!hasSpecialTalent) {
                        stats.append("  - 无特殊天赋\n");
                    }
                } else {
                    stats.append(String.format("机师%d: 未初始化\n", i + 1));
                }
            }
            
            // 编队统计
            if (activePilots > 0) {
                stats.append(String.format("\n=== 编队统计 ===\n"));
                stats.append(String.format("活跃机师: %d/%d\n", activePilots, squadSize));
                stats.append(String.format("平均等级: %.1f\n", (float)totalLevel / activePilots));
                stats.append(String.format("总经验值: %.1f\n", totalExperience));
                stats.append(String.format("编队效率: %.1f%%\n", ((float)activePilots / squadSize) * 100f));
            }
        } else {
            stats.append("\n编队状态: 未初始化\n");
            stats.append("说明: 编队将在首次战斗时自动初始化\n");
        }
        
        return stats.toString();
    }
} 
