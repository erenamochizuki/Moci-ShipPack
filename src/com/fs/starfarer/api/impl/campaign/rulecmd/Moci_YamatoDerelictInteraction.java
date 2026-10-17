package com.fs.starfarer.api.impl.campaign.rulecmd;

import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.SalvageDefenderInteraction;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Misc.Token;
import data.scripts.util.Moci_TextLoader;
import data.scripts.impl.Moci_CampaignFeedback;

/**
 * 大和号残骸交互脚本
 * 处理守卫舰队生成和打捞逻辑
 */
public class Moci_YamatoDerelictInteraction extends BaseCommandPlugin {
    private static final String TEXT_ID = "Moci_CampaignText";
    private static final String YAMATO_BATTLE_COMPLETED_KEY = "$moci_yamato_battle_completed";
    private static final String YAMATO_BATTLE_COMPLETED_PERSISTENT_KEY = "Moci_YamatoBattleCompleted";
    private static final String YAMATO_CREW_LOG_ACQUIRED_KEY = "$moci_yamato_crew_log_acquired";
    private static final String YAMATO_CREW_LOG_ACQUIRED_PERSISTENT_KEY = "Moci_YamatoCrewLogAcquired";

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params,
            Map<String, MemoryAPI> memoryMap) {
        if (dialog == null) {
            return false;
        }

        // 检查参数列表是否为空
        if (params == null || params.isEmpty()) {
            return false;
        }

        String command = params.get(0).getString(memoryMap);
        SectorEntityToken entity = dialog.getInteractionTarget();
        MemoryAPI memory = entity.getMemoryWithoutUpdate();

        if ("initDefenders".equals(command)) {
            // 初始化守卫舰队（仅生成，不触发战斗）
            return initDefenders(entity, memory);

        } else if ("triggerBattle".equals(command)) {
            // 触发战斗交互
            return triggerBattle(dialog, memoryMap);

        } else if ("salvage".equals(command)) {
            // 执行打捞
            return performSalvage(entity, dialog);

        } else {
            return false;
        }
    }

    /**
     * 初始化守卫舰队（仅生成，不触发战斗）
     * 
     * @param entity 残骸实体
     * @param memory 实体内存
     * @return 是否成功初始化
     */
    private boolean initDefenders(SectorEntityToken entity, MemoryAPI memory) {
        // 检查是否已经初始化过守卫舰队
        if (memory.contains("$defenderFleet")) {
            return true;
        }

        // 创建守卫舰队
        CampaignFleetAPI defenders = createDefenderFleet();
        if (defenders == null || defenders.isEmpty()) {
            return false;
        }

        // 配置守卫舰队属性
        defenders.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
        defenders.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_NO_REP_IMPACT, true);
        defenders.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_ALLOW_DISENGAGE, false);
        defenders.setLocation(entity.getLocation().x, entity.getLocation().y);

        // 将舰队存储到实体内存中
        memory.set("$defenderFleet", defenders, 0);
        memory.set("$hasDefenders", true);

        return true;
    }

    /**
     * 创建守卫舰队（6艘 Moci_PendingBill_Defense，配备 Alpha 核心军官）
     * 参考 PKDefenderPluginImpl 的实现，添加自动化船插和相关标签
     * 
     * @return 创建的舰队，如果失败则返回 null
     */
    private CampaignFleetAPI createDefenderFleet() {
        // 创建空舰队
        CampaignFleetAPI defenders = Global.getFactory().createEmptyFleet(
                Factions.REMNANTS, // 使用残党势力（自动防御）
                text("yamato.defender_fleet_name"),
                true);

        if (defenders == null) {
            return null;
        }

        java.util.Random random = new java.util.Random();

        // 手动添加6艘 Moci_PendingBill_Defense
        for (int i = 0; i < 6; i++) {
            FleetMemberAPI member = Global.getFactory().createFleetMember(
                    com.fs.starfarer.api.fleet.FleetMemberType.SHIP,
                    "Moci_PendingBill_Defense");

            if (member == null) {
                continue; // 创建失败，跳过这艘船
            }

            // 设置唯一ID
            member.setId("yamato_guard_" + random.nextLong());

            // 使用 AICoreOfficerPlugin 创建 Alpha 核心军官
            com.fs.starfarer.api.campaign.AICoreOfficerPlugin plugin = Misc
                    .getAICoreOfficerPlugin(Commodities.ALPHA_CORE);

            if (plugin != null) {
                com.fs.starfarer.api.characters.PersonAPI officer = plugin.createPerson(
                        Commodities.ALPHA_CORE,
                        defenders.getFaction().getId(),
                        random);

                if (officer != null) {
                    member.setCaptain(officer);
                }
            }

            // 克隆变体并设置为可改装
            ShipVariantAPI variant = member.getVariant().clone();
            variant.setSource(VariantSource.REFIT);

            // 添加自动化船插（永久）
            variant.addPermaMod(HullMods.AUTOMATED);

            // 添加标签：禁止自动装配、自动化无惩罚
            variant.addTag(Tags.TAG_NO_AUTOFIT);
            variant.addTag(Tags.TAG_AUTOMATED_NO_PENALTY);

            // 非旗舰添加不可恢复标签（玩家无法打捞）
            if (i > 0) {
                variant.addTag(Tags.VARIANT_UNBOARDABLE);
                variant.addTag(Tags.VARIANT_UNRESTORABLE);
            } else {
                // 旗舰（第一艘）添加总是可回收标签
                variant.addTag(Tags.VARIANT_ALWAYS_RECOVERABLE);
            }

            // 应用变体
            member.setVariant(variant, false, true);

            // 设置为完好状态
            member.getRepairTracker().setCR(member.getRepairTracker().getMaxCR());

            defenders.getFleetData().addFleetMember(member);
        }

        return defenders;
    }

    /**
     * 触发战斗交互
     * 使用SalvageDefenderInteraction来处理战斗，它会自动配置：
     * - aiRetreatAllowed = false（AI不允许撤退）
     * - objectivesAllowed = false（不允许占领目标点）
     * - enemyDeployAll = true（敌方全部部署）
     * 
     * @param dialog    对话接口
     * @param memoryMap 内存映射
     * @return 是否成功触发战斗
     */
    private boolean triggerBattle(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        SectorEntityToken entity = dialog.getInteractionTarget();
        MemoryAPI memory = entity.getMemoryWithoutUpdate();

        // 验证守卫舰队是否存在
        if (!memory.contains("$defenderFleet")) {
            return false;
        }

        // 调用原版的 SalvageDefenderInteraction 触发战斗
        // 它会从内存中读取 $defenderFleet 并启动战斗交互
        SalvageDefenderInteraction interaction = new SalvageDefenderInteraction();
        return interaction.execute(null, dialog, null, memoryMap);
    }

    /**
     * 执行打捞（原 salvageYamato）
     * 
     * @param entity 残骸实体
     * @param dialog 对话接口
     * @return 是否成功打捞
     */
    private boolean performSalvage(SectorEntityToken entity, InteractionDialogAPI dialog) {
        // 检查是否已经打捞过（防止重复打捞）
        if (entity.getMemoryWithoutUpdate().getBoolean("$Moci_YamatoSalvaged")) {
            dialog.getTextPanel().addPara(text("yamato.already_salvaged"), Misc.getGrayColor());
            return false;
        }

        // 创建大和号舰船实例
        FleetMemberAPI yamato = Global.getFactory().createFleetMember(
                com.fs.starfarer.api.fleet.FleetMemberType.SHIP,
                "Moci_Yamato_Base");

        // 检查舰船创建是否成功
        if (yamato == null) {
            dialog.getTextPanel().addPara(
                    text("yamato.error_create_ship"),
                    Misc.getNegativeHighlightColor());
            return false;
        }

        // 设置舰船为满 CR 状态
        yamato.getRepairTracker().setCR(yamato.getRepairTracker().getMaxCR());

        // 添加舰船到玩家舰队
        Global.getSector().getPlayerFleet().getFleetData().addFleetMember(yamato);

        // 解锁 Moci_PendingBill 舰船蓝图
        boolean newBlueprint = !Global.getSector().getPlayerFaction().knowsShip("Moci_PendingBill");
        Global.getSector().getPlayerFaction().addKnownShip("Moci_PendingBill", false);

        // 显示成功消息（使用高亮颜色）
        dialog.getTextPanel().addPara(
                text("yamato.salvage_success"),
                Misc.getHighlightColor());
        dialog.getTextPanel().addPara(
                text("yamato.blueprint_gained"),
                Misc.getPositiveHighlightColor());

        // 设置 $Moci_YamatoSalvaged 为 true
        entity.getMemoryWithoutUpdate().set("$Moci_YamatoSalvaged", true);

        // 标记大和号流程已完成（供 Tulip 联络员任务链使用）
        Global.getSector().getMemoryWithoutUpdate().set(YAMATO_BATTLE_COMPLETED_KEY, true);
        Global.getSector().getPersistentData().put(YAMATO_BATTLE_COMPLETED_PERSISTENT_KEY, true);
        Global.getSector().getMemoryWithoutUpdate().set(YAMATO_CREW_LOG_ACQUIRED_KEY, true);
        Global.getSector().getPersistentData().put(YAMATO_CREW_LOG_ACQUIRED_PERSISTENT_KEY, true);

        // 移除标签，防止再次触发交互（关键！）
        entity.removeTag("moci_yamato_derelict");

        // 从星系中移除残骸实体
        entity.getContainingLocation().removeEntity(entity);

        // One sound for the combined ship/log/blueprint reward, before closing the dialog.
        if (newBlueprint) {
            Moci_CampaignFeedback.playBlueprintReceived();
        } else {
            Moci_CampaignFeedback.playRewardReceived();
        }

        // 关闭对话框
        dialog.dismiss();

        return true;
    }

    private static String text(String key) {
        return Moci_TextLoader.getText(TEXT_ID, key);
    }
}
