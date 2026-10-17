package data.hullmods;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Skills;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import data.hullmods.Moci_CombatSquadOfficierManager.SquadMember;
import data.scripts.Moci_CombatSquadCombatPlugin;
// 经验系统现在使用全局监听器，不再需要战斗插件
// import data.scripts.Moci_CombatSquadExperiencePlugin;

public class Moci_CombatSquadV2 extends BaseHullMod {
    public static float CR_RETREAT_THRESHOLD = 0.1f; // CR撤退阈值
    public static float TRAVEL_DRIVE_DURATION = 3.0f; // 巡航驱动持续时间
    public static float DEPLOYMENT_COST_MULT = 1.5f; // 部署点消耗倍数
    public static Object STATUS_KEY = new Object();

    private static String text(String key, String fallback) {
        return Moci_CombatSquadTextRegistry.getText(key, fallback);
    }
    


    /**
     * 获取舰船的编队配置（支持自定义僚机舰船）
     */
    public static List<SquadMember> getSquadConfig(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) return null;
        
        // 1. 优先查询自定义配置
        PersonAPI captain = ship.getCaptain();
        if (captain != null && !captain.isDefault()) {
            List<SquadMember> customConfig = getCustomSquadConfig(captain.getId());
            if (customConfig != null && !customConfig.isEmpty()) {
                return customConfig;
            }
        }
        
        // 2. 回退到默认配置（使用getSquadConfigFromFile确保有保底配置）
        String hullId = ship.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = ship.getHullSpec().getHullId(); // 备用方案：如果不是D-hull则使用getHullId()
        }
        return Moci_CombatSquadOfficierManager.getSquadConfigFromFile(hullId);
    }
    
    /**
     * 获取自定义编队配置
     */
    private static List<SquadMember> getCustomSquadConfig(String commanderId) {
        List<SquadMember> customSquad = new ArrayList<>();
        int squadSize = Moci_PilotManager.getSquadSize(commanderId);
        
        // 获取默认配置作为备用
        List<SquadMember> defaultConfig = getDefaultSquadConfig(commanderId);
        
        for (int i = 0; i < squadSize; i++) {
            Moci_CustomWingmanManager.CustomWingmanShip customShip = 
                Moci_CustomWingmanManager.getCustomWingmanShip(commanderId, i);
            
            if (customShip != null) {
                // 使用自定义舰船配置
                SquadMember member = new SquadMember(
                    customShip.variantId,
                    customShip.displayName,
                    1, // 数量固定为1
                    3, // 默认等级，实际由机师系统管理
                    new String[]{Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE}, // 默认技能
                    "aggressive" // 默认性格
                );
                customSquad.add(member);
            } else if (defaultConfig != null && i < defaultConfig.size()) {
                // 使用默认配置的对应位置
                customSquad.add(defaultConfig.get(i));
            } else {
                // 如果没有对应的默认配置，创建一个基础配置
                SquadMember member = new SquadMember(
                    "Moci_MS_06", // 默认扎古
                    text("hullmod_fallback_member_name", "扎古_标准型"),
                    1,
                    2,
                    new String[]{Skills.HELMSMANSHIP, Skills.COMBAT_ENDURANCE},
                    "aggressive"
                );
                customSquad.add(member);
            }
        }
        
        return customSquad.isEmpty() ? null : customSquad;
    }
    
    /**
     * 获取默认编队配置（用于自定义配置的备用）
     */
    private static List<SquadMember> getDefaultSquadConfig(String commanderId) {
        // 通过指挥官ID找到其舰船，然后获取默认配置
        FleetMemberAPI commanderShip = getCommanderShipByOfficerId(commanderId);
        if (commanderShip == null) return null;
        
        String hullId = commanderShip.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = commanderShip.getHullSpec().getHullId();
        }
        
        return Moci_CombatSquadOfficierManager.getSquadConfigFromFile(hullId);
    }
    
    /**
     * 根据军官ID获取其指挥的舰船
     */
    private static FleetMemberAPI getCommanderShipByOfficerId(String commanderId) {
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) return null;
        
        for (FleetMemberAPI ship : playerFleet.getFleetData().getMembersListCopy()) {
            PersonAPI captain = ship.getCaptain();
            if (captain != null && commanderId.equals(captain.getId())) {
                return ship;
            }
        }
        return null;
    }

    /**
     * 获取编队总人数（用于状态显示）
     */
    private static int getTotalSquadSize(ShipAPI ship) {
        List<SquadMember> config = getSquadConfig(ship);
        if (config == null) return 0;

        int total = 0;
        for (SquadMember member : config) {
            total += member.count;
        }
        return total;
    }

    /**
     * 获取编队总人数（ShipVariantAPI版本）
     */
    private static int getTotalSquadSize(com.fs.starfarer.api.combat.ShipVariantAPI variant) {
        if (variant == null || variant.getHullSpec() == null) return 0;
        
        // 使用getDParentHullId()获取基础船体ID
        String hullId = variant.getHullSpec().getDParentHullId();
        if (hullId == null) {
            hullId = variant.getHullSpec().getHullId();
        }
        
        List<SquadMember> config = Moci_CombatSquadOfficierManager.getSquadConfigFromFile(hullId);
        if (config == null) return 0;

        int total = 0;
        for (SquadMember member : config) {
            total += member.count;
        }
        return total;
    }

    /**
     * 翻译军官性格
     */
    private static String translatePersonality(String personality) {
        switch (personality) {
            case "timid": return text("personality_timid", "胆小");
            case "cautious": return text("personality_cautious", "谨慎");
            case "steady": return text("personality_steady", "沉着");
            case "aggressive": return text("personality_aggressive", "激进");
            case "reckless": return text("personality_reckless", "鲁莽");
            default: return personality; // 如果没有匹配的翻译，返回原文
        }
    }

    /**
     * 创建编队成员军官
     */
    private static PersonAPI createSquadOfficer(SquadMember memberConfig) {
        PersonAPI officer = Global.getFactory().createPerson();
        officer.setFaction("player");
        officer.setPortraitSprite("graphics/portraits/Moci_mob.png");
        officer.getStats().setLevel(memberConfig.officerLevel);

        // 设置技能
        for (String skill : memberConfig.skills) {
            officer.getStats().setSkillLevel(skill, 1);
        }

        // 设置性格和军衔
        officer.setPersonality(memberConfig.personality);
        officer.setRankId(Ranks.SPACE_LIEUTENANT);
        officer.setPostId(null);

        return officer;
    }

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 使用动态部署点计算，回退到固定惩罚
        float deploymentMultiplier = DEPLOYMENT_COST_MULT; // 默认固定惩罚
        
        // 尝试获取动态计算结果（在ship创建后才能获取完整信息）
        // 这里先使用固定值，在applyEffectsAfterShipCreation中进行动态调整
        stats.getDynamic().getMod("deployment_points_mod").modifyMult(id, deploymentMultiplier);
    }
    
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 处理CombatSquad安装逻辑
        handleCombatSquadInstallation(ship);
        
        // 应用动态部署点计算
        applyDynamicDeploymentCost(ship, id);
    }
    
    /**
     * 应用动态部署点计算
     */
    private void applyDynamicDeploymentCost(ShipAPI ship, String id) {
        if (ship == null || ship.getMutableStats() == null) {
            return;
        }
        
        try {
            // 计算动态部署点倍数
            float dynamicMultiplier = Moci_DynamicDeploymentCalculator.calculateDeploymentMultiplier(ship);
            
            // 重置之前的固定倍数，应用新的动态倍数
            ship.getMutableStats().getDynamic().getMod("deployment_points_mod").unmodify(id);
            ship.getMutableStats().getDynamic().getMod("deployment_points_mod").modifyMult(id, dynamicMultiplier);
            
        } catch (Exception e) {
            // 动态计算失败时保持固定惩罚
            Global.getLogger(Moci_CombatSquadV2.class)
                  .error("应用动态部署点计算失败，保持固定惩罚 [ship=" + 
                        (ship != null ? ship.getHullSpec().getHullId() : "null") + "]: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查是否为玩家势力舰船
     */
    private boolean isPlayerShip(ShipAPI ship) {
        if (ship == null) return false;
        
        // 在战役模式中，使用玩家势力ID检查
        if (Global.getSector() != null && ship.getFleetMember() != null) {
            com.fs.starfarer.api.campaign.FactionAPI playerFaction = Global.getSector().getPlayerFaction();
            if (playerFaction != null && ship.getFleetMember().getFleetData() != null) {
                // 通过FleetMemberAPI获取舰船所属势力
                com.fs.starfarer.api.campaign.FactionAPI shipFaction = ship.getFleetMember().getFleetData().getFleet().getFaction();
                return shipFaction != null && 
                       shipFaction.getId().equals(playerFaction.getId());
            }
        }
        
        // 在战斗模式中，使用owner检查（0通常是玩家）
        // 这是fallback方案，用于战斗中无法访问sector的情况
        return ship.getOriginalOwner() == 0;
    }
    


    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        // 检查是否安装了队长机船插
        if (ship != null && ship.getVariant() != null) {
            return ship.getVariant().getHullMods().contains("Moci_CaptainMachine");
        }
        return false;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null) return text("hullmod_unapplicable_ship_null", "舰船为空");
        if (ship.getVariant() == null) return text("hullmod_unapplicable_variant_null", "舰船变体为空");
        if (!ship.getVariant().getHullMods().contains("Moci_CaptainMachine")) {
            return text("hullmod_unapplicable_need_captain", "需要安装队长机船插");
        }
        return null;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 10f;
        float opad = 5f;
        Color highlight = Misc.getHighlightColor();
        tooltip.addSectionHeading(text("hullmod_section_title", "作战小队"), Alignment.MID, opad);
        tooltip.addPara(
                text("hullmod_intro", "战斗开始时召唤专属编队支援作战。编队成员 %s 。"),
                pad,
                highlight,
                text("hullmod_intro_highlight", "不占用部署点")
        );

        tooltip.addPara(
                text("hullmod_retreat", "当编队成员自身CR低于 %s 或母舰被击毁时，对应成员将开始撤退。"),
                pad,
                highlight,
                (int)(CR_RETREAT_THRESHOLD * 100) + "%"
        );

        // 使用动态部署点描述生成器
        Moci_DeploymentDescriptionGenerator.addDynamicDeploymentDescription(tooltip, ship, width, isForModSpec);

        // 使用新的UI工具类添加机师系统详细描述
        Moci_CombatSquadUI.addCombatSquadDescription(tooltip, ship, width, isForModSpec);
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) return "" + (int)(CR_RETREAT_THRESHOLD * 100) + "%";
        if (index == 1) return "" + (int)TRAVEL_DRIVE_DURATION + text("description_seconds_suffix", "秒");
        if (index == 2) {
            // 返回默认部署点惩罚（动态计算在addPostDescriptionSection中处理）
            return "+" + (int)((DEPLOYMENT_COST_MULT - 1) * 100) + "%";
        }
        return null;
    }

    @Override
    public int getDisplaySortOrder() {
        return 3000;
    }

    /**
     * 战役模式下的处理 - 按照骤雨wingCommander的做法
     * 检查有军官的舰船，如果军官没有编队就创建编队
     */
    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        if (member.getFleetData() == null || member.getFleetData().getFleet() == null) {
            return;
        }
        
        // 只处理玩家舰队
        if (!member.getFleetData().getFleet().isPlayerFleet()) {
            return;
        }
        
        PersonAPI captain = member.getCaptain();
        if (captain != null && !captain.isDefault()) {
            // 检查是否启用了动态机师系统
            if (Moci_CombatSquadPilotConfig.isPilotSystemEnabled() && 
                Moci_CombatSquadPilotConfig.useDynamicPilots()) {
                
                String commanderId = captain.getId();
                boolean hasExistingSquad = hasExistingSquad(captain);
                
                if (!hasExistingSquad) {
                    // 获取编队规模
                    int newSquadSize = getTotalSquadSize(member.getVariant());
                    if (newSquadSize > 0) {
                        try {
                            // 创建编队
                            Moci_PilotManager.createSquadPilots(captain, null, newSquadSize, true);
                        } catch (Exception e) {
                            // 创建编队失败，静默处理
                        }
                    }
                }
            }
        }
    }

    /**
     * 注册战斗插件和整合机师系统
     */
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (!ship.isAlive()) return;
        
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        
        // 经验系统现在使用全局监听器，由Moci_bounty_plugin管理
        
        // 检查插件是否已经注册，避免重复注册
        if (!engine.getCustomData().containsKey("Moci_CombatSquadCombatPlugin_Registered")) {
            engine.addPlugin(new Moci_CombatSquadCombatPlugin());
            engine.getCustomData().put("Moci_CombatSquadCombatPlugin_Registered", true);
        }
        
        // 整合机师系统 - 为每个安装了CombatSquad的舰船管理机师
        try {
            Moci_CombatSquadPilotIntegration.integrateWithCombatSquad(ship, amount);
        } catch (Exception e) {
            // 整合失败，静默处理
        }
    }
    
    /**
     * 获取舰船的当前部署点倍数（用于UI显示）
     */
    public static float getCurrentDeploymentMultiplier(ShipAPI ship) {
        if (ship == null) {
            return DEPLOYMENT_COST_MULT;
        }
        
        try {
            return Moci_DynamicDeploymentCalculator.calculateDeploymentMultiplier(ship);
        } catch (Exception e) {
            return DEPLOYMENT_COST_MULT;
        }
    }
    
    /**
     * 获取部署点计算详情（用于UI显示）
     */
    public static Moci_DynamicDeploymentCalculator.CalculationDetails getDeploymentCalculationDetails(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        
        try {
            return Moci_DynamicDeploymentCalculator.getCalculationDetails(ship);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 刷新舰船的部署点计算（当僚机配置改变时调用）
     */
    public static void refreshDeploymentCost(ShipAPI ship) {
        if (ship == null || ship.getMutableStats() == null) {
            return;
        }
        
        try {
            // 使缓存失效
            Moci_DeploymentCalculationCache.invalidateCache(ship);
            
            // 重新计算并应用部署点倍数
            float dynamicMultiplier = Moci_DynamicDeploymentCalculator.calculateDeploymentMultiplier(ship);
            
            // 重置并重新应用
            String modId = "Moci_CombatSquad"; // 使用船插ID作为修改器ID
            ship.getMutableStats().getDynamic().getMod("deployment_points_mod").unmodify(modId);
            ship.getMutableStats().getDynamic().getMod("deployment_points_mod").modifyMult(modId, dynamicMultiplier);
            
        } catch (Exception e) {
            Global.getLogger(Moci_CombatSquadV2.class)
                  .error("刷新部署点计算失败 [ship=" + 
                        (ship != null ? ship.getHullSpec().getHullId() : "null") + "]: " + e.getMessage(), e);
        }
    }
    
    /**
     * 使指定指挥官的所有缓存失效（当僚机配置改变时调用）
     */
    public static void invalidateCommanderCache(String commanderId) {
        if (commanderId != null && !commanderId.isEmpty()) {
            Moci_DeploymentCalculationCache.invalidateCacheByCommander(commanderId);
        }
    }
    
    /**
     * 获取缓存统计信息（用于调试和监控）
     */
    public static Moci_DeploymentCalculationCache.CacheStatistics getCacheStatistics() {
        return Moci_DeploymentCalculationCache.getStatistics();
    }
    
    /**
     * 重置状态（保留方法以兼容现有代码）
     */
    public static void resetManagerAutoOpenStatus() {
        // Intel管理现在由船包ModPlugin处理，无需重置状态
    }

    /**
     * 处理CombatSquad的安装逻辑 - 修复编队绑定
     * 实现用户设计的绑定方案：
     * 1. 安装时无军官 → 使用固定配置
     * 2. 安装时有军官 → 为军官绑定动态小队
     * 3. 军官换船 → 小队跟随军官
     */
    private void handleCombatSquadInstallation(ShipAPI ship) {
        if (!isPlayerShip(ship)) {
            return;
        }
        
        PersonAPI officer = ship.getCaptain();
        
        if (officer == null || officer.isDefault()) {
            // 情况1：安装时无军官，使用固定配置
        } else {
            // 情况2：安装时有军官，检查是否已有小队
            String commanderId = officer.getId();
            
            if (hasExistingSquad(officer)) {
                // 军官已有小队，继续使用现有小队 - 这就是编队跟随的核心机制
            } else {
                // 军官没有小队，为其创建新小队
                if (Moci_CombatSquadPilotConfig.isPilotSystemEnabled() &&
                    Moci_CombatSquadPilotConfig.useDynamicPilots()) {
                    
                    createOfficerSquad(ship, officer);
                }
            }
        }
    }
    
    /**
     * 检查军官是否已有编队 - 修复检测逻辑
     */
    private boolean hasExistingSquad(PersonAPI officer) {
        if (officer == null) {
            return false;
        }
        
        String commanderId = officer.getId();
        boolean persistent = Global.getSector() != null;
        
        // 检查编队名称
        boolean hasName = Moci_PilotManager.hasSquadName(commanderId, persistent);
        
        // 检查编队规模
        int squadSize = Moci_PilotManager.getCurrentSquadSize(commanderId, persistent);
        
        // 只要有编队名称或者编队规模大于0就认为已有编队
        boolean hasExisting = hasName || squadSize > 0;
        
        return hasExisting;
    }
    
    /**
     * 为军官创建新编队
     */
    private void createOfficerSquad(ShipAPI ship, PersonAPI officer) {
        try {
            // 获取编队规模
            int squadSize = getTotalSquadSize(ship);
            if (squadSize <= 0) {
                return;
            }
            
            boolean persistent = Global.getSector() != null;
            
            // 创建机师编队
            Moci_PilotManager.createSquadPilots(officer, ship, squadSize, persistent);
            
        } catch (Exception e) {
            // 创建编队失败，静默处理
        }
    }
}
