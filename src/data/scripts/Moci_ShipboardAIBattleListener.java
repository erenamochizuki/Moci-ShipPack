package data.scripts;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CombatDamageData;
import com.fs.starfarer.api.campaign.EngagementResultForFleetAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;
import data.hullmods.Moci_ShipboardAIConfigLoader;
import data.hullmods.Moci_ShipboardAIManager;

import java.util.List;

/**
 * 舰载AI战斗经验监听器。
 */
public class Moci_ShipboardAIBattleListener extends BaseCampaignEventListener implements EveryFrameScript {

    private transient EngagementResultAPI battleResult;
    private transient int numEngagements = 0;
    private transient boolean isWin = false;

    public Moci_ShipboardAIBattleListener() {
        super(true);
    }

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
        // no-op
    }

    @Override
    public void reportPlayerEngagement(EngagementResultAPI result) {
        battleResult = result;
        numEngagements++;
    }

    @Override
    public void reportBattleOccurred(CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (battleResult == null || battle == null || !battle.isPlayerInvolved()) {
            return;
        }

        isWin = battle.getPlayerSide().contains(primaryWinner);
        EngagementResultForFleetAPI playerResult = battleResult.getWinnerResult().isPlayer()
                ? battleResult.getWinnerResult()
                : battleResult.getLoserResult();

        if (playerResult == null) {
            battleResult = null;
            numEngagements = 0;
            return;
        }

        awardExperience(playerResult);
        battleResult = null;
        numEngagements = 0;
    }

    private void awardExperience(EngagementResultForFleetAPI playerResult) {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) {
            return;
        }
        for (FleetMemberAPI member : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
            if (!Moci_ShipboardAIConfigLoader.hasShipboardAISystem(member)) {
                continue;
            }
            if (!isParticipated(member, playerResult)) {
                continue;
            }

            float damageScore = getDamageScore(member);
            float exp = calculateExpGain(damageScore);

            Moci_ShipboardAIManager.ExperienceGainResult result = Moci_ShipboardAIManager.addExperience(member, exp);
            if (result.applied && result.levelAfter > result.levelBefore) {
                Global.getSector().getCampaignUI().addMessage(
                        member.getShipName() + " 的舰载AI 升级到 " + result.levelAfter + " 级",
                        Misc.getHighlightColor()
                );
            }
        }
    }

    private boolean isParticipated(FleetMemberAPI member, EngagementResultForFleetAPI playerResult) {
        if (member == null || playerResult == null) {
            return false;
        }
        String memberId = member.getId();
        return containsMember(playerResult.getDeployed(), memberId)
                || containsMember(playerResult.getDestroyed(), memberId)
                || containsMember(playerResult.getDisabled(), memberId)
                || containsMember(playerResult.getRetreated(), memberId)
                || hasDamageRecord(member);
    }

    private float getDamageScore(FleetMemberAPI member) {
        float score = 0f;
        if (battleResult != null
                && battleResult.getLastCombatDamageData() != null
                && battleResult.getLastCombatDamageData().getDealtBy(member) != null
                && battleResult.getLastCombatDamageData().getDealtBy(member).getDamage() != null) {
            for (CombatDamageData.DamageToFleetMember dmg :
                    battleResult.getLastCombatDamageData().getDealtBy(member).getDamage().values()) {
                score += dmg.hullDamage;
            }
        }
        if (member.getMemberStrength() > 0) {
            score /= member.getMemberStrength();
        }
        return score;
    }

    private float calculateExpGain(float damageScore) {
        float base = 25f;
        float damageBonus = Math.min(220f, Math.max(0f, damageScore / 6f));
        float winMult = isWin ? 1.35f : 1f;
        float engagementMult = 1f + Math.min(0.3f, numEngagements * 0.05f);
        return (base + damageBonus) * winMult * engagementMult;
    }

    private boolean containsMember(List<FleetMemberAPI> members, String memberId) {
        if (members == null || memberId == null || memberId.trim().isEmpty()) {
            return false;
        }
        for (FleetMemberAPI candidate : members) {
            if (candidate == null) {
                continue;
            }
            if (memberId.equals(candidate.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDamageRecord(FleetMemberAPI member) {
        if (battleResult == null || battleResult.getLastCombatDamageData() == null || member == null) {
            return false;
        }
        CombatDamageData.DealtByFleetMember damageData = battleResult.getLastCombatDamageData().getDealtBy(member);
        return damageData != null && damageData.getDamage() != null && !damageData.getDamage().isEmpty();
    }
}
