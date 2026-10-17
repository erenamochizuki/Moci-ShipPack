package data.scripts;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CombatDamageData;
import com.fs.starfarer.api.campaign.EngagementResultForFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import data.hullmods.Moci_PilotManager;
import data.hullmods.Moci_PilotTraining;

/**
 * 仿照骤雨wingcom的armaa_wingmanPromotion设计的战斗监听器
 * 专门处理CombatSquad机师的战斗后经验奖励
 */
public class Moci_CombatSquadBattleListener extends BaseCampaignEventListener implements EveryFrameScript {
    
    public Moci_CombatSquadBattleListener() { 
        super(true); 
    }
    
    private int numEngagements = 0;
    private transient boolean isWin = false;
    private transient EngagementResultAPI battleResult;
    
    @Override
    public boolean runWhilePaused() {
        return false;
    }
    
    @Override
    public boolean isDone() { 
        return false; 
    }
    
    @Override
    public void advance(float amount) {
        // 无需处理
    }
    
    @Override
    public void reportPlayerEngagement(EngagementResultAPI result) {
        battleResult = result;
        numEngagements++;
    }
    
    @Override
    public void reportBattleOccurred(CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (!battle.isPlayerInvolved() || battleResult == null) {
            return;
        }
        
        // 判断是否获胜
        isWin = battle.getPlayerSide().contains(primaryWinner);
        
        // 获取玩家战斗结果
        EngagementResultForFleetAPI playerResult = battleResult.getWinnerResult().isPlayer() ? 
                                                   battleResult.getWinnerResult() : battleResult.getLoserResult();
        
        // 处理每个参战舰船的机师经验
        processCombatSquadExperience(playerResult, battle);
        
        // 清理
        battleResult = null;
        numEngagements = 0;
    }
    
    /**
     * 处理CombatSquad机师的战斗经验奖励
     */
    private void processCombatSquadExperience(EngagementResultForFleetAPI playerResult, BattleAPI battle) {
        // 遍历玩家舰队中的所有舰船
        for (FleetMemberAPI member : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
            // 检查是否安装了CombatSquad (使用正确的船插名称)
            if (!member.getVariant().hasHullMod("Moci_CombatSquad")) {
                continue;
            }

            // 检查是否有指挥官
            PersonAPI commander = member.getCaptain();
            if (commander == null || commander.isDefault()) {
                continue;
            }

            if (!isParticipated(member, playerResult)) {
                continue;
            }

            try {
                // 计算伤害贡献
                float damageScore = calculateDamageContribution(member);
                float expGain = calculateExpGain(damageScore);

                // 奖励机师经验
                awardPilotExperience(commander, expGain);
            } catch (Exception e) {
                // 静默处理错误
            }
        }
    }

    private boolean isParticipated(FleetMemberAPI member, EngagementResultForFleetAPI playerResult) {
        return playerResult.getDeployed().contains(member)
                || playerResult.getDestroyed().contains(member)
                || playerResult.getDisabled().contains(member)
                || playerResult.getRetreated().contains(member);
    }

    /**
     * 计算舰船的伤害贡献
     */
    private float calculateDamageContribution(FleetMemberAPI member) {
        float damageScore = 0f;

        if (battleResult.getLastCombatDamageData() != null
                && battleResult.getLastCombatDamageData().getDealtBy(member).getDamage() != null) {
            for (CombatDamageData.DamageToFleetMember dmg :
                    battleResult.getLastCombatDamageData().getDealtBy(member).getDamage().values()) {
                damageScore += dmg.hullDamage;
            }
        }
        if (member.getMemberStrength() > 0) {
            damageScore /= member.getMemberStrength();
        }

        return damageScore;
    }

    /**
     * 经验计算公式与舰载AI保持一致
     */
    private float calculateExpGain(float damageScore) {
        float base = 25f;
        float damageBonus = Math.min(220f, Math.max(0f, damageScore / 6f));
        float winMult = isWin ? 1.35f : 1f;
        float engagementMult = 1f + Math.min(0.3f, numEngagements * 0.05f);
        return (base + damageBonus) * winMult * engagementMult;
    }

    /**
     * 为机师编队奖励经验
     */
    private void awardPilotExperience(PersonAPI commander, float expGain) {
        String commanderId = commander.getId();

        // 获取编队规模
        Integer squadSize = Moci_PilotManager.getSquadSize(commanderId);
        if (squadSize == null || squadSize <= 0) {
            return;
        }

        float baseBattleExp = Moci_PilotTraining.ExperienceEvent.BATTLE_VICTORY.getBaseExperience();
        if (baseBattleExp <= 0f || expGain <= 0f) {
            return;
        }

        // 为每个机师奖励经验
        for (int pilotIndex = 0; pilotIndex < squadSize; pilotIndex++) {
            PersonAPI pilot = Moci_PilotManager.getPilot(commanderId, pilotIndex, true);
            if (pilot == null) {
                continue;
            }

            try {
                Moci_PilotTraining.addExperience(
                        commanderId,
                        pilotIndex,
                        Moci_PilotTraining.ExperienceEvent.BATTLE_VICTORY,
                        expGain / baseBattleExp,
                        true
                );
            } catch (Exception e) {
                // 静默处理错误
            }
        }
    }
} 
