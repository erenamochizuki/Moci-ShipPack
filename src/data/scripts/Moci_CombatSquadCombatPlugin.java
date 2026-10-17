package data.scripts;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.combat.*;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import data.ai.Moci_CombatSquadAIV2;
import data.ai.Moci_RetreatModule;
import data.hullmods.Moci_CombatSquadOfficierManager;
import data.hullmods.Moci_CombatSquadV2;
import data.hullmods.Moci_PilotManager;
import data.hullmods.Moci_DynamicDataSynchronizer;
import data.hullmods.Moci_ShipInstanceManager;
import data.hullmods.Moci_CustomWingmanManager;
import data.shipsystems.scripts.Moci_MSLaunchTravelDrive;
import data.scripts.util.Moci_AIRestoreUtil;
import org.magiclib.subsystems.drones.PIDController;

public class Moci_CombatSquadCombatPlugin extends BaseEveryFrameCombatPlugin {
    private static final String DEPLOYMENT_LOG_PREFIX = "[CombatSquad部署] ";
    private IntervalUtil interval = new IntervalUtil(0.1f, 0.5f);
    private static final String DATA_SYNC_KEY = "Moci_CombatSquad_DataSynced";
    
    // 控制是否为僚机分配自定义AI
    // true: 使用Moci_CombatSquadAIV2自定义AI
    // false: 使用游戏默认AI
    private static final boolean USE_CUSTOM_AI = false;

    // 控制是否启用保底逻辑（仅在不使用自定义AI时生效）
    // true: 启用护航任务分配和自动撤退
    // false: 完全使用游戏默认行为
    private static final boolean ENABLE_FALLBACK_LOGIC = true;

    // 控制撤退时是否使用Moci_RetreatModule
    // true: 队长死亡时切换到Moci_RetreatModule进行撤退（推荐）
    // false: 使用setRetreating()标志让默认AI处理撤退
    private static final boolean USE_RETREAT_MODULE = true;

    // 实验性功能：控制僚机部署方式
    // true: 使用createFXDrone部署（不会在部署界面显示，实验性）
    // false: 使用spawnFleetMember部署（标准方式，推荐）
    private static final boolean USE_FX_DRONE_DEPLOYMENT = false;

    // CombatSquad僚机默认AI行为开关（与ShipAIConfig字段值直接对应，不做换算）
    private static final boolean WINGMAN_ALWAYS_STRAFE_OFFENSIVELY = false;
    private static final boolean WINGMAN_BACKING_OFF_WHILE_NOT_VENTING_ALLOWED = false;
    private static final boolean WINGMAN_TURN_TO_FACE_WITH_UNDAMAGED_ARMOR = false;
    private static final boolean WINGMAN_BURN_DRIVE_IGNORE_ENEMIES = true;

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (Global.getCombatEngine().isPaused())
            return;

        // 战斗开始时进行一次数据同步
        if (!Global.getCombatEngine().getCustomData().containsKey(DATA_SYNC_KEY)) {
            syncPilotDataToCombat();
            Global.getCombatEngine().getCustomData().put(DATA_SYNC_KEY, true);
        }

        interval.advance(amount);
        List<ShipAPI> toAdd = new ArrayList<>();
        if (interval.intervalElapsed()) {
            for (ShipAPI ship : Global.getCombatEngine().getShips()) {
                if (ship.getVariant().hasHullMod("Moci_CombatSquad") &&
                        (ship.getCustomData() == null
                                || !ship.getCustomData().containsKey("Moci_CombatSquadDeployed"))) {
                    toAdd.add(ship);
                }
            }
            for (ShipAPI ship : toAdd) {
                deploySquadForShip(ship);
            }
        }

        // 如果不使用自定义AI但启用了保底逻辑，则执行护航和撤退管理
        if (!USE_CUSTOM_AI && ENABLE_FALLBACK_LOGIC) {
            manageFallbackLogic(amount);
        }
    }



    /**
     * 为指定舰船部署编队
     */
    private void deploySquadForShip(ShipAPI ship) {
        Moci_DynamicDataSynchronizer.syncPilotDataForShip(ship);

        List<PersonAPI> officerList = getPilotsFromManager(ship);

        if (officerList == null || officerList.isEmpty()) {
            officerList = Moci_CombatSquadOfficierManager.getSquadByMemberId(ship.getFleetMember(),
                    ship.getHullSpec());

            if (officerList == null || officerList.isEmpty()) {
                officerList = Moci_CombatSquadOfficierManager.createNewSquad(ship.getHullSpec());
            }
        }

        List<Moci_CombatSquadOfficierManager.SquadMember> squadList = Moci_CombatSquadV2.getSquadConfig(ship);

        if (squadList == null || squadList.isEmpty()) {
            logDeploymentInfo("跳过僚机部署: 未找到编队配置, " + getCommanderDebugInfo(ship));
            ship.setCustomData("Moci_CombatSquadDeployed", true);
            return;
        }

        logDeploymentInfo("开始部署僚机编队: 计划数量=" + squadList.size()
                + ", 可用机师数量=" + (officerList != null ? officerList.size() : 0)
                + ", 方式=" + getDeploymentMethodLabel()
                + ", " + getCommanderDebugInfo(ship));

        int index = 0;
        for (Moci_CombatSquadOfficierManager.SquadMember m : squadList) {
            if (deployWingman(ship, m, index, officerList)) {
                index++;
            }
        }

        logDeploymentInfo("僚机编队部署结束: 成功数量=" + index
                + ", 计划数量=" + squadList.size()
                + ", " + getCommanderDebugInfo(ship));
        ship.setCustomData("Moci_CombatSquadDeployed", true);
    }

    /**
     * 部署单个僚机
     */
    private boolean deployWingman(ShipAPI commanderShip, Moci_CombatSquadOfficierManager.SquadMember squadMember,
            int index, List<PersonAPI> officerList) {

        if (squadMember == null) {
            logDeploymentWarn("僚机部署失败: 编队成员配置为空, 机师索引=" + index + ", " + getCommanderDebugInfo(commanderShip));
            return false;
        }

        // 检查DP和获取舰船配置
        PersonAPI captain = commanderShip.getCaptain();
        if (captain != null && !captain.isDefault()) {
            String commanderId = captain.getId();

            FleetMemberAPI customShip = Moci_CustomWingmanManager.getStoredCustomShip(commanderId, index);
            if (customShip != null) {
                int commanderDP = getCommanderDeploymentPoints(commanderShip);
                int wingmanDP = (int) customShip.getHullSpec().getSuppliesToRecover();

                if (wingmanDP > commanderDP) {
                    logDeploymentWarn("僚机部署失败: 存储自定义舰船DP超过队长DP, 机师索引=" + index
                            + ", 队长DP=" + commanderDP
                            + ", 僚机DP=" + wingmanDP
                            + ", 预期僚机=" + getFleetMemberDebugInfo(customShip)
                            + ", " + getPilotDebugInfo(officerList, index)
                            + ", " + getCommanderDebugInfo(commanderShip));
                    return false;
                }

                logDeploymentInfo("尝试部署僚机: 来源=存储自定义舰船"
                        + ", 机师索引=" + index
                        + ", 预期僚机=" + getFleetMemberDebugInfo(customShip)
                        + ", " + getPilotDebugInfo(officerList, index)
                        + ", " + getCommanderDebugInfo(commanderShip));
                return deployCustomWingman(commanderShip, customShip, index, officerList, "存储自定义舰船");
            }

            Moci_CustomWingmanManager.CustomWingmanShip customConfig = Moci_CustomWingmanManager
                    .getCustomWingmanShip(commanderId, index);

            if (customConfig != null) {
                int commanderDP = getCommanderDeploymentPoints(commanderShip);
                int wingmanDP = customConfig.deploymentPoints;

                if (wingmanDP > commanderDP) {
                    logDeploymentWarn("僚机部署失败: 自定义配置DP超过队长DP, 机师索引=" + index
                            + ", 队长DP=" + commanderDP
                            + ", 僚机DP=" + wingmanDP
                            + ", 预期hullId=" + safeValue(customConfig.hullId)
                            + ", " + getPilotDebugInfo(officerList, index)
                            + ", " + getCommanderDebugInfo(commanderShip));
                    return false;
                }

                logDeploymentInfo("尝试部署僚机: 来源=自定义配置"
                        + ", 机师索引=" + index
                        + ", 预期hullId=" + safeValue(customConfig.hullId)
                        + ", " + getPilotDebugInfo(officerList, index)
                        + ", " + getCommanderDebugInfo(commanderShip));
                return deployWingmanWithVariant(commanderShip, customConfig.variantId, index, officerList, "自定义配置");
            }
        }

        // 使用默认配置部署
                logDeploymentInfo("尝试部署僚机: 来源=默认配置"
                        + ", 机师索引=" + index
                        + ", 预期hullId=" + getHullIdFromVariantId(squadMember.variantId)
                        + ", " + getPilotDebugInfo(officerList, index)
                        + ", " + getCommanderDebugInfo(commanderShip));
        return deployWingmanWithVariant(commanderShip, squadMember.variantId, index, officerList, "默认配置");
    }

    /**
     * 使用存储的自定义舰船部署僚机
     */
    private boolean deployCustomWingman(ShipAPI commanderShip, FleetMemberAPI customShip,
            int index, List<PersonAPI> officerList, String source) {

        if (USE_FX_DRONE_DEPLOYMENT) {
            return deployCustomWingmanAsFXDrone(commanderShip, customShip, index, officerList, source);
        } else {
            return deployCustomWingmanAsFleetMember(commanderShip, customShip, index, officerList, source);
        }
    }

    /**
     * 使用FX Drone方式部署自定义僚机（实验性）
     */
    private boolean deployCustomWingmanAsFXDrone(ShipAPI commanderShip, FleetMemberAPI customShip,
            int index, List<PersonAPI> officerList, String source) {
        try {
            // 计算部署位置
            Vector2f spawnLocation = getWingmanSpawnLocation(commanderShip, index);

            // 使用createFXDrone创建僚机
            ShipAPI s = Global.getCombatEngine().createFXDrone(customShip.getVariant());
            
            if (s == null) {
                logDeploymentWarn("僚机部署失败: createFXDrone返回null"
                        + ", 来源=" + source
                        + ", 机师索引=" + index
                        + ", 预期僚机=" + getFleetMemberDebugInfo(customShip)
                        + ", " + getPilotDebugInfo(officerList, index)
                        + ", " + getCommanderDebugInfo(commanderShip));
                return false;
            }

            // 设置基本属性
            s.setOwner(commanderShip.getOwner());
            s.setAlly(commanderShip.isAlly());
            s.getLocation().set(spawnLocation);
            s.setFacing(commanderShip.getFacing());
            // s.setCollisionClass(com.fs.starfarer.api.combat.CollisionClass.SHIP);
            s.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
            
            // 指派军官
            if (officerList != null && index < officerList.size()) {
                PersonAPI pilot = officerList.get(index);
                s.setCaptain(pilot);
                Global.getLogger(Moci_CombatSquadCombatPlugin.class).info(
                    "[FX Drone] 指派军官: " + pilot.getNameString() + " (等级: " + pilot.getStats().getLevel() + ")"
                );
            }
            
            // 设置舰船名称
            if (customShip.getShipName() != null) {
                s.setName(customShip.getShipName());
            }
            
            // 添加到引擎
            Global.getCombatEngine().addEntity(s);
            
            // 设置初始速度（朝向队长方向）
            float facing = s.getFacing();
            float speedAmount = 200f;
            Vector2f velocity = new Vector2f(
                (float) Math.cos(Math.toRadians(facing)) * speedAmount,
                (float) Math.sin(Math.toRadians(facing)) * speedAmount
            );
            s.getVelocity().set(velocity);
            
            // 配置AI
            ShipAIConfig aiConfig = createWingmanAIConfig();
            s.setShipAI(Global.getSettings().createDefaultShipAI(s, aiConfig));
            
            // 设置僚机属性
            setupWingman(s, commanderShip, index, officerList);
            
            logDeploymentInfo("僚机部署成功: 来源=" + source
                    + ", 方式=createFXDrone"
                    + ", 机师索引=" + index
                    + ", " + getShipDebugInfo(s)
                    + ", " + getLaunchIntegrationDebugInfo(s)
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip));
            
            return true;

        } catch (Exception e) {
            logDeploymentError("僚机部署异常: 来源=" + source
                    + ", 方式=createFXDrone"
                    + ", 机师索引=" + index
                    + ", 预期僚机=" + getFleetMemberDebugInfo(customShip)
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip), e);
            return false;
        }
    }

    /**
     * 使用FleetMember方式部署自定义僚机（标准方式）
     */
    private boolean deployCustomWingmanAsFleetMember(ShipAPI commanderShip, FleetMemberAPI customShip,
            int index, List<PersonAPI> officerList, String source) {

        CombatFleetManagerAPI manager = Global.getCombatEngine().getFleetManager(commanderShip.getOwner());
        if (manager == null) {
            logDeploymentWarn("僚机部署失败: 无法获取CombatFleetManager"
                    + ", 来源=" + source
                    + ", 机师索引=" + index
                    + ", 预期僚机=" + getFleetMemberDebugInfo(customShip)
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip));
            return false;
        }

        FleetMemberAPI freshShip = null;
        manager.setSuppressDeploymentMessages(true);
        try {
            freshShip = Global.getFactory().createFleetMember(
                    com.fs.starfarer.api.fleet.FleetMemberType.SHIP,
                    customShip.getVariant());

            if (customShip.getShipName() != null) {
                freshShip.setShipName(customShip.getShipName());
            }
            
            // 关键修复：在spawn之前就设置部署点为0
            freshShip.getStats().getDynamic().getMod(Stats.DEPLOYMENT_POINTS_MOD).modifyMult("MociCombatSquad", 0f);
            freshShip.getStats().getSuppliesToRecover().modifyMult("MociCombatSquad", 0f);
            freshShip.getStats().getSuppliesPerMonth().modifyMult("MociCombatSquad", 0f);
            
            // 1. 先指派军官（军官技能会影响统计数据）
            if (officerList != null && index < officerList.size()) {
                PersonAPI pilot = officerList.get(index);
                freshShip.setCaptain(pilot);
            }
            
            // 2. 初始化舰船状态
            freshShip.getStatus().repairFully();
            
            // 3. 更新统计数据（会计算军官技能的影响）
            freshShip.updateStats();
            
            // 4. 设置CR为最大值（此时maxCR已经包含了军官技能的加成）
            freshShip.getRepairTracker().setCR(freshShip.getRepairTracker().getMaxCR());

            ShipAPI s = manager.spawnFleetMember(freshShip, getWingmanSpawnLocation(commanderShip, index),
                    commanderShip.getFacing(), 0f);

            if (s != null) {
                // 设置初始速度（朝向队长方向）
                float facing = s.getFacing();
                float speedAmount = 200f;
                Vector2f velocity = new Vector2f(
                    (float) Math.cos(Math.toRadians(facing)) * speedAmount,
                    (float) Math.sin(Math.toRadians(facing)) * speedAmount
                );
                s.getVelocity().set(velocity);
                
                setupWingman(s, commanderShip, index, officerList);
                logDeploymentInfo("僚机部署成功: 来源=" + source
                        + ", 方式=spawnFleetMember"
                        + ", 机师索引=" + index
                        + ", " + getShipDebugInfo(s)
                        + ", " + getLaunchIntegrationDebugInfo(s)
                        + ", 预期僚机=" + getFleetMemberDebugInfo(freshShip)
                        + ", " + getPilotDebugInfo(officerList, index)
                        + ", " + getCommanderDebugInfo(commanderShip));
                return true;
            }

            logDeploymentWarn("僚机部署失败: spawnFleetMember返回null"
                    + ", 来源=" + source
                    + ", 机师索引=" + index
                    + ", 预期僚机=" + getFleetMemberDebugInfo(freshShip != null ? freshShip : customShip)
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip));
            return false;

        } catch (Exception e) {
            logDeploymentError("僚机部署异常: 来源=" + source
                    + ", 方式=spawnFleetMember"
                    + ", 机师索引=" + index
                    + ", 预期僚机=" + getFleetMemberDebugInfo(freshShip != null ? freshShip : customShip)
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip), e);
            return false;
        } finally {
            manager.setSuppressDeploymentMessages(false);
        }
    }

    /**
     * 使用变体ID部署僚机
     */
    private boolean deployWingmanWithVariant(ShipAPI commanderShip, String variantId,
            int index, List<PersonAPI> officerList, String source) {

        if (variantId == null || variantId.trim().isEmpty()) {
            logDeploymentWarn("僚机部署失败: variantId为空"
                    + ", 来源=" + source
                    + ", 机师索引=" + index
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip));
            return false;
        }

        if (USE_FX_DRONE_DEPLOYMENT) {
            return deployWingmanWithVariantAsFXDrone(commanderShip, variantId, index, officerList, source);
        } else {
            return deployWingmanWithVariantAsFleetMember(commanderShip, variantId, index, officerList, source);
        }
    }

    /**
     * 使用FX Drone方式部署变体僚机（实验性）
     */
    private boolean deployWingmanWithVariantAsFXDrone(ShipAPI commanderShip, String variantId,
            int index, List<PersonAPI> officerList, String source) {
        try {
            // 获取变体
            ShipVariantAPI variant = Global.getSettings().getVariant(variantId).clone();
            if (variant == null) {
                logDeploymentWarn("僚机部署失败: 变体克隆结果为空"
                        + ", 来源=" + source
                        + ", 机师索引=" + index
                        + ", 预期hullId=" + getHullIdFromVariantId(variantId)
                        + ", " + getPilotDebugInfo(officerList, index)
                        + ", " + getCommanderDebugInfo(commanderShip));
                return false;
            }

            // 计算部署位置
            Vector2f spawnLocation = getWingmanSpawnLocation(commanderShip, index);

            // 使用createFXDrone创建僚机
            ShipAPI s = Global.getCombatEngine().createFXDrone(variant);
            
            if (s == null) {
                logDeploymentWarn("僚机部署失败: createFXDrone返回null"
                        + ", 来源=" + source
                        + ", 机师索引=" + index
                        + ", 预期hullId=" + getHullIdFromVariantId(variantId)
                        + ", " + getPilotDebugInfo(officerList, index)
                        + ", " + getCommanderDebugInfo(commanderShip));
                return false;
            }

            // 设置基本属性
            s.setOwner(commanderShip.getOwner());
            s.setAlly(commanderShip.isAlly());
            s.getLocation().set(spawnLocation);
            s.setFacing(commanderShip.getFacing());
            s.setCollisionClass(com.fs.starfarer.api.combat.CollisionClass.SHIP);
            s.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
            
            // 指派军官
            if (officerList != null && index < officerList.size()) {
                PersonAPI pilot = officerList.get(index);
                s.setCaptain(pilot);
                Global.getLogger(Moci_CombatSquadCombatPlugin.class).info(
                    "[FX Drone] 指派军官: " + pilot.getNameString() + " (等级: " + pilot.getStats().getLevel() + ")"
                );
            }
            
            // 添加到引擎
            Global.getCombatEngine().addEntity(s);
            
            // 设置初始速度（朝向队长方向）
            float facing = s.getFacing();
            float speedAmount = 200f;
            Vector2f velocity = new Vector2f(
                (float) Math.cos(Math.toRadians(facing)) * speedAmount,
                (float) Math.sin(Math.toRadians(facing)) * speedAmount
            );
            s.getVelocity().set(velocity);
            
            // 配置AI
            ShipAIConfig aiConfig = createWingmanAIConfig();
            s.setShipAI(Global.getSettings().createDefaultShipAI(s, aiConfig));
            
            // 设置僚机属性
            setupWingman(s, commanderShip, index, officerList);
            
            logDeploymentInfo("僚机部署成功: 来源=" + source
                    + ", 方式=createFXDrone"
                    + ", 机师索引=" + index
                    + ", " + getShipDebugInfo(s)
                    + ", " + getLaunchIntegrationDebugInfo(s)
                    + ", 预期hullId=" + getHullIdFromVariantId(variantId)
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip));
            
            return true;

        } catch (Exception e) {
            logDeploymentError("僚机部署异常: 来源=" + source
                    + ", 方式=createFXDrone"
                    + ", 机师索引=" + index
                    + ", 预期hullId=" + getHullIdFromVariantId(variantId)
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip), e);
            return false;
        }
    }

    /**
     * 使用FleetMember方式部署变体僚机（标准方式）
     */
    private boolean deployWingmanWithVariantAsFleetMember(ShipAPI commanderShip, String variantId,
            int index, List<PersonAPI> officerList, String source) {

        CombatFleetManagerAPI manager = Global.getCombatEngine().getFleetManager(commanderShip.getOwner());
        if (manager == null) {
            logDeploymentWarn("僚机部署失败: 无法获取CombatFleetManager"
                    + ", 来源=" + source
                    + ", 机师索引=" + index
                    + ", 预期hullId=" + getHullIdFromVariantId(variantId)
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip));
            return false;
        }

        manager.setSuppressDeploymentMessages(true);
        try {
            ShipAPI s = manager.spawnShipOrWing(variantId, getWingmanSpawnLocation(commanderShip, index),
                    commanderShip.getFacing());

            if (s != null) {
                // 设置初始速度（朝向队长方向）
                float facing = s.getFacing();
                float speedAmount = 200f;
                Vector2f velocity = new Vector2f(
                    (float) Math.cos(Math.toRadians(facing)) * speedAmount,
                    (float) Math.sin(Math.toRadians(facing)) * speedAmount
                );
                s.getVelocity().set(velocity);
                
                setupWingman(s, commanderShip, index, officerList);
                logDeploymentInfo("僚机部署成功: 来源=" + source
                        + ", 方式=spawnShipOrWing"
                        + ", 机师索引=" + index
                        + ", " + getShipDebugInfo(s)
                        + ", " + getLaunchIntegrationDebugInfo(s)
                        + ", 预期hullId=" + getHullIdFromVariantId(variantId)
                        + ", " + getPilotDebugInfo(officerList, index)
                        + ", " + getCommanderDebugInfo(commanderShip));
                return true;
            }

            logDeploymentWarn("僚机部署失败: spawnShipOrWing返回null"
                    + ", 来源=" + source
                    + ", 机师索引=" + index
                    + ", 预期hullId=" + getHullIdFromVariantId(variantId)
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip));
            return false;
        } catch (Exception e) {
            logDeploymentError("僚机部署异常: 来源=" + source
                    + ", 方式=spawnShipOrWing"
                    + ", 机师索引=" + index
                    + ", 预期hullId=" + getHullIdFromVariantId(variantId)
                    + ", " + getPilotDebugInfo(officerList, index)
                    + ", " + getCommanderDebugInfo(commanderShip), e);
            return false;
        } finally {
            manager.setSuppressDeploymentMessages(false);
        }
    }

    /**
     * 设置僚机的基本属性
     */
    private void setupWingman(ShipAPI wingman, ShipAPI commander, int index, List<PersonAPI> officerList) {
        // 设置僚机不消耗部署点和补给
        if (wingman.getFleetMember() != null) {
            FleetMemberAPI member = wingman.getFleetMember();
            member.getStats().getDynamic().getMod(Stats.DEPLOYMENT_POINTS_MOD).modifyMult("MociCombatSquad", 0f);
            member.getStats().getSuppliesToRecover().modifyMult("MociCombatSquad", 0f);
            member.getStats().getSuppliesPerMonth().modifyMult("MociCombatSquad", 0f);
        }

        // 记录队长信息，用于撤退检查
        wingman.setCustomData("Moci_WingmanCommander", commander);

        // 设置AI
        if (USE_CUSTOM_AI) {
            wingman.setShipAI(new Moci_CombatSquadAIV2(wingman, commander, (index == 0 ? 7 : 11)));
        } else if (wingman.getShipAI() != null && wingman.getShipAI().getConfig() != null) {
            applyWingmanAIConfig(wingman.getShipAI().getConfig());
        }

        // 为预设编队僚机指派军官（双重保险）
        if (officerList != null && index < officerList.size()) {
            PersonAPI pilot = officerList.get(index);
            wingman.setCaptain(pilot);
        }
        if (wingman.getCustomData().get(Moci_AIRestoreUtil.ORIGINAL_HULL_SIZE_KEY) == null) {
            wingman.setCustomData(Moci_AIRestoreUtil.ORIGINAL_HULL_SIZE_KEY, wingman.getHullSize());
        }
        wingman.setCustomData(Moci_AIRestoreUtil.COMBAT_HULL_SIZE_KEY, ShipAPI.HullSize.FIGHTER);
        // CombatSquad 僚机会被 fighter 化，但它们不是稳定的玩家换乘目标。
        // 某些舰队级规则在换乘瞬间会假定 FleetMember 一定存在，因此这里显式禁用 transfer command。
        wingman.setInvalidTransferCommandTarget(true);

        boolean launched = Moci_MSLaunchTravelDrive.launchImmediately(wingman);
        if (!launched) {
            wingman.setCustomData("Moci_CombatSquadLaunchMode", "launchImmediateFailed_noTravelDriveFallback");
            wingman.setCustomData("Moci_ImmediateLaunchUsed", false);
            if (wingman.getTravelDrive() != null) {
                wingman.turnOffTravelDrive();
            }
        } else {
            wingman.setCustomData("Moci_CombatSquadLaunchMode", "MSLaunchTravelDrive");
            wingman.setCustomData("Moci_ImmediateLaunchUsed", true);
        }

        wingman.setHullSize(ShipAPI.HullSize.FIGHTER);
        logDeploymentInfo("僚机整备接入检查: "
                + "hullId=" + getHullId(wingman)
                + ", variantId=" + getVariantId(wingman)
                + ", hasMobileSuits=" + (wingman.getVariant() != null && wingman.getVariant().hasHullMod("Moci_MobileSuits"))
                + ", hasLandingSequence=" + (wingman.getVariant() != null && wingman.getVariant().hasHullMod("Moci_LandingSequence"))
                + ", hasCombatSquad=" + (wingman.getVariant() != null && wingman.getVariant().hasHullMod("Moci_CombatSquad"))
                + ", ai=" + (wingman.getShipAI() != null ? wingman.getShipAI().getClass().getSimpleName() : "null")
                + ", hullSize=" + wingman.getHullSize()
                + ", isFighter=" + wingman.isFighter()
                + ", commander=" + getShipName(commander));
    }

    private ShipAIConfig createWingmanAIConfig() {
        ShipAIConfig aiConfig = new ShipAIConfig();
        applyWingmanAIConfig(aiConfig);
        return aiConfig;
    }

    private Vector2f getWingmanSpawnLocation(ShipAPI commanderShip, int index) {
        return Vector2f.add(commanderShip.getLocation(),
                (Vector2f) Misc.getUnitVectorAtDegreeAngle(
                        Misc.normalizeAngle(commanderShip.getFacing() - 90f + (index == 0 ? 7 : 11) * 30f))
                        .scale(Moci_CombatSquadAIV2.getEscortIdeal()),
                null);
    }

    private String getDeploymentMethodLabel() {
        return USE_FX_DRONE_DEPLOYMENT ? "createFXDrone" : "FleetMember/spawnShipOrWing";
    }

    private int getCommanderDeploymentPoints(ShipAPI commanderShip) {
        if (commanderShip == null || commanderShip.getFleetMember() == null || commanderShip.getFleetMember().getHullSpec() == null) {
            return 0;
        }
        return (int) commanderShip.getFleetMember().getHullSpec().getSuppliesToRecover();
    }

    private void logDeploymentInfo(String message) {
        Global.getLogger(Moci_CombatSquadCombatPlugin.class).info(DEPLOYMENT_LOG_PREFIX + message);
    }

    private void logDeploymentWarn(String message) {
        Global.getLogger(Moci_CombatSquadCombatPlugin.class).warn(DEPLOYMENT_LOG_PREFIX + message);
    }

    private void logDeploymentError(String message, Throwable throwable) {
        Global.getLogger(Moci_CombatSquadCombatPlugin.class).error(DEPLOYMENT_LOG_PREFIX + message, throwable);
    }

    private String getCommanderDebugInfo(ShipAPI commanderShip) {
        return "队长舰船=" + getShipName(commanderShip)
                + ", 指挥官=" + getPersonName(commanderShip != null ? commanderShip.getCaptain() : null)
                + ", 实例ID=" + safeValue(Moci_ShipInstanceManager.getInstanceId(commanderShip))
                + ", hullId=" + getHullId(commanderShip)
                + ", variantId=" + getVariantId(commanderShip);
    }

    private String getShipDebugInfo(ShipAPI ship) {
        return "hullId=" + getHullId(ship)
                + ", variantId=" + getVariantId(ship);
    }

    private String getLaunchIntegrationDebugInfo(ShipAPI ship) {
        if (ship == null) {
            return "起飞接入=unknown";
        }

        Object launchMode = ship.getCustomData().get("Moci_CombatSquadLaunchMode");
        Object immediateLaunchUsed = ship.getCustomData().get("Moci_ImmediateLaunchUsed");
        String systemId = ship.getHullSpec() != null ? ship.getHullSpec().getShipSystemId() : "null";
        return "起飞接入=" + safeValue(launchMode)
                + ", immediateLaunch=" + safeValue(immediateLaunchUsed)
                + ", systemId=" + safeValue(systemId);
    }

    private String getFleetMemberDebugInfo(FleetMemberAPI member) {
        return "舰船=" + getFleetMemberName(member)
                + ", hullId=" + getHullId(member)
                + ", variantId=" + getVariantId(member);
    }

    private String getPilotDebugInfo(List<PersonAPI> officerList, int index) {
        if (officerList == null || index < 0 || index >= officerList.size()) {
            return "机师=未分配(index=" + index + ")";
        }

        PersonAPI pilot = officerList.get(index);
        return "机师=" + getPersonName(pilot) + "(index=" + index + ")";
    }

    private String getShipName(ShipAPI ship) {
        if (ship == null) {
            return "unknown";
        }

        String name = ship.getName();
        return name != null && !name.trim().isEmpty() ? name : "unknown";
    }

    private String getFleetMemberName(FleetMemberAPI member) {
        if (member == null) {
            return "unknown";
        }

        String name = member.getShipName();
        return name != null && !name.trim().isEmpty() ? name : "unknown";
    }

    private String getPersonName(PersonAPI person) {
        if (person == null) {
            return "none";
        }

        String name = person.getNameString();
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }

        return safeValue(person.getId());
    }

    private String getHullId(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return "unknown";
        }

        String hullId = ship.getHullSpec().getDParentHullId();
        if (hullId == null || hullId.isEmpty()) {
            hullId = ship.getHullSpec().getHullId();
        }
        return safeValue(hullId);
    }

    private String getHullId(FleetMemberAPI member) {
        if (member == null || member.getHullSpec() == null) {
            return "unknown";
        }

        String hullId = member.getHullSpec().getDParentHullId();
        if (hullId == null || hullId.isEmpty()) {
            hullId = member.getHullSpec().getHullId();
        }
        return safeValue(hullId);
    }

    private String getHullIdFromVariantId(String variantId) {
        if (variantId == null || variantId.trim().isEmpty()) {
            return "unknown";
        }

        try {
            ShipVariantAPI variant = Global.getSettings().getVariant(variantId);
            if (variant == null || variant.getHullSpec() == null) {
                return "unknown";
            }

            String hullId = variant.getHullSpec().getDParentHullId();
            if (hullId == null || hullId.isEmpty()) {
                hullId = variant.getHullSpec().getHullId();
            }
            return safeValue(hullId);
        } catch (Exception ex) {
            return "unknown";
        }
    }

    private String getVariantId(ShipAPI ship) {
        if (ship == null || ship.getVariant() == null) {
            return "unknown";
        }
        return safeValue(ship.getVariant().getHullVariantId());
    }

    private String getVariantId(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null) {
            return "unknown";
        }
        return safeValue(member.getVariant().getHullVariantId());
    }

    private String safeValue(Object value) {
        if (value == null) {
            return "unknown";
        }

        String text = String.valueOf(value);
        return text != null && !text.trim().isEmpty() ? text : "unknown";
    }

    private void applyWingmanAIConfig(ShipAIConfig aiConfig) {
        if (aiConfig == null) {
            return;
        }

        aiConfig.alwaysStrafeOffensively = WINGMAN_ALWAYS_STRAFE_OFFENSIVELY;
        aiConfig.backingOffWhileNotVentingAllowed = WINGMAN_BACKING_OFF_WHILE_NOT_VENTING_ALLOWED;
        aiConfig.turnToFaceWithUndamagedArmor = WINGMAN_TURN_TO_FACE_WITH_UNDAMAGED_ARMOR;
        aiConfig.burnDriveIgnoreEnemies = WINGMAN_BURN_DRIVE_IGNORE_ENEMIES;
    }

    /**
     * 
     * 将持久化的机师数据同步到战斗引擎临时存储
     */
    private void syncPilotDataToCombat() {
        try {
            for (ShipAPI ship : Global.getCombatEngine().getShips()) {
                if (!ship.getVariant().hasHullMod("Moci_CombatSquad"))
                    continue;

                PersonAPI captain = ship.getCaptain();
                if (captain == null || captain.isDefault())
                    continue;

                String commanderId = captain.getId();
                int squadSize = Moci_PilotManager.getSquadSize(commanderId);
                if (squadSize <= 0)
                    continue;

                String squadName = Moci_PilotManager.getSquadName(commanderId, true);
                if (squadName != null) {
                    Global.getCombatEngine().getCustomData().put("moci_combatSquad_squadName_" + commanderId,
                            squadName);
                }
                Global.getCombatEngine().getCustomData().put("moci_combatSquad_squadSize_" + commanderId, squadSize);

                for (int i = 0; i < squadSize; i++) {
                    PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, i, true);
                    String callsign = Moci_PilotManager.getCallsign(commanderId, i, true);
                    float experience = Moci_PilotManager.getExperience(commanderId, i, true);
                    boolean assigned = Moci_PilotManager.isPilotAssigned(commanderId, i, true);

                    if (pilot != null) {
                        Global.getCombatEngine().getCustomData().put("moci_combatSquad_pilot_" + i + "_" + commanderId,
                                pilot);
                        Global.getCombatEngine().getCustomData()
                                .put("moci_combatSquad_callsign_" + i + "_" + commanderId, callsign);
                        Global.getCombatEngine().getCustomData().put("moci_combatSquad_exp_" + i + "_" + commanderId,
                                experience);
                        Global.getCombatEngine().getCustomData()
                                .put("moci_combatSquad_assigned_" + i + "_" + commanderId, assigned);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 从PilotManager获取机师数据
     */
    private List<PersonAPI> getPilotsFromManager(ShipAPI ship) {
        try {
            PersonAPI captain = ship.getCaptain();
            if (captain == null || captain.isDefault()) {
                return null;
            }

            String instanceId = Moci_ShipInstanceManager.getInstanceId(ship);
            if (instanceId == null) {
                return null;
            }

            Object squadSizeObj = Global.getCombatEngine().getCustomData()
                    .get("moci_combatSquad_squadSize_" + instanceId);
            int squadSize = (squadSizeObj instanceof Integer) ? (Integer) squadSizeObj : 0;

            if (squadSize <= 0) {
                return null;
            }

            List<PersonAPI> pilots = new ArrayList<>();
            for (int i = 0; i < squadSize; i++) {
                String pilotKey = "moci_combatSquad_pilot_" + i + "_" + instanceId;
                Object pilotObj = Global.getCombatEngine().getCustomData().get(pilotKey);
                if (pilotObj instanceof PersonAPI) {
                    pilots.add((PersonAPI) pilotObj);
                }
            }

            return pilots.isEmpty() ? null : pilots;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 获取机师呼号
     */
    private String getPilotCallsign(ShipAPI ship, int index) {
        try {
            String instanceId = Moci_ShipInstanceManager.getInstanceId(ship);
            if (instanceId != null) {
                String callsignKey = "moci_combatSquad_callsign_" + index + "_" + instanceId;
                Object callsignObj = Global.getCombatEngine().getCustomData().get(callsignKey);
                if (callsignObj instanceof String) {
                    String callsign = (String) callsignObj;
                    return (callsign != null && !callsign.isEmpty()) ? callsign : ("机师-" + (index + 1));
                }
            }
        } catch (Exception e) {
        }
        return "机师-" + (index + 1);
    }

    /**
     * 管理保底逻辑（用于不使用自定义AI的情况）
     * 包括：自动撤退和自动开火检查。
     */
    private void manageFallbackLogic(float amount) {
        try {
            for (ShipAPI ship : Global.getCombatEngine().getShips()) {
                if (ship.getCustomData() == null || !ship.getCustomData().containsKey("Moci_WingmanCommander")) {
                    continue;
                }
                 
                // 自动开火检查（只需要执行一次）
                if (!ship.getCustomData().containsKey("Moci_AutoFireConfirmed")) {
                    // 缓存武器组列表，避免在循环中多次调用getWeaponGroupsCopy()
                    List<WeaponGroupAPI> weaponGroups = ship.getWeaponGroupsCopy();
                    for (int i = 0; i < weaponGroups.size(); i++) {
                        if (!weaponGroups.get(i).isAutofiring()) {
                            ship.giveCommand(ShipCommand.TOGGLE_AUTOFIRE, null, i);
                        }
                    }
                    ship.setCustomData("Moci_AutoFireConfirmed", true);
                }

                Object commanderObj = ship.getCustomData().get("Moci_WingmanCommander");
                if (!(commanderObj instanceof ShipAPI)) {
                    continue;
                }

                ShipAPI commander = (ShipAPI) commanderObj;
                boolean commanderUnavailable = commander == null ||
                        !commander.isAlive() ||
                        commander.isHulk();

                // 自动撤退逻辑（立即执行，不受检查间隔限制）
                if (commanderUnavailable && ship.isAlive() && !ship.isHulk()) {
                    if (!ship.getCustomData().containsKey("Moci_ForcedRetreat")) {
                        ship.setCustomData("Moci_ForcedRetreat", true);
                        
                        if (USE_RETREAT_MODULE) {
                            // 创建并激活撤退模块
                            activateRetreatModule(ship, commander);
                        }
                    }

                    if (USE_RETREAT_MODULE) {
                        // 使用RetreatModule处理移动
                        Moci_RetreatModule retreatModule = getRetreatModule(ship);
                        if (retreatModule != null) {
                            retreatModule.advance(amount);
                        }
                    } else {
                        // 使用默认撤退机制
                        ship.setRetreating(true, true);
                        ship.getLocation().set(0, -10000000f);
                    }
                    
                    continue;
                }

                if (!ship.isAlive() || ship.isHulk()) {
                    continue;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 为僚机激活撤退模块
     */
    private void activateRetreatModule(ShipAPI wingman, ShipAPI commander) {
        try {
            // 创建PID控制器（使用与Moci_CombatSquadAIV2相同的参数）
            // 参数：KpX(移动比例), KdX(移动微分), KpR(转向比例), KdR(转向微分)
            PIDController controller = new PIDController(2f, 2f, 6f, 0.5f);
            
            // 创建RetreatModule
            Moci_RetreatModule retreatModule = new Moci_RetreatModule(
                wingman, 
                commander,  // 可以传null，因为队长已经死亡
                controller,
                null  // mainAI可以传null，因为不需要切换回去
            );
            
            // 强制评估环境
            retreatModule.forceEvaluateCircumstance();
            
            // 存储到自定义数据
            wingman.setCustomData("Moci_RetreatModule", retreatModule);
            
            Global.getLogger(Moci_CombatSquadCombatPlugin.class).info(
                "为僚机 " + wingman.getName() + " 激活撤退模块"
            );
            
        } catch (Exception e) {
            Global.getLogger(Moci_CombatSquadCombatPlugin.class).error(
                "激活撤退模块失败: " + e.getMessage(), e
            );
        }
    }
    
    /**
     * 获取僚机的撤退模块
     */
    private Moci_RetreatModule getRetreatModule(ShipAPI wingman) {
        Object moduleObj = wingman.getCustomData().get("Moci_RetreatModule");
        if (moduleObj instanceof Moci_RetreatModule) {
            return (Moci_RetreatModule) moduleObj;
        }
        return null;
    }
}
