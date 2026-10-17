package data.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.DModManager;
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.util.Misc;

import java.util.List;
import java.util.Random;

public class MociRecoverableShipEncounterContext extends FleetEncounterContext {
    @Override
    public List<FleetMemberAPI> getRecoverableShips(BattleAPI battle, CampaignFleetAPI winningFleet, CampaignFleetAPI otherFleet) {
        List<FleetMemberAPI> recoverableShips = super.getRecoverableShips(battle, winningFleet, otherFleet);

        if (Misc.isPlayerOrCombinedContainingPlayer(otherFleet)) {
            return recoverableShips;
        }

        if (!MociRecoverableShipHelper.isMarkedRecoverableFleet(otherFleet)) {
            return recoverableShips;
        }

        float playerContribFraction = computePlayerContribFraction();
        if (playerContribFraction <= 0f) {
            return recoverableShips;
        }

        DataForEncounterSide winnerData = getDataFor(winningFleet);
        List<FleetMemberData> enemyCasualties = winnerData.getEnemyCasualties();

        for (FleetMemberData data : enemyCasualties) {
            FleetMemberAPI member = data.getMember();
            if (member == null) {
                continue;
            }
            boolean explicitlyForced = member.getId() != null
                    && member.getId().equals(MociRecoverableShipHelper.getForcedRecoverableMemberId(otherFleet));
            boolean taggedAlwaysRecoverable = MociRecoverableShipHelper.isMemberVariantAlwaysRecoverable(member);
            if (!explicitlyForced && !taggedAlwaysRecoverable) {
                continue;
            }
            if (Misc.isUnboardable(member)) {
                continue;
            }
            if ((data.getStatus() != Status.DISABLED) && (data.getStatus() != Status.DESTROYED)) {
                continue;
            }
            if (recoverableShips.contains(member) || getStoryRecoverableShips().contains(member)) {
                continue;
            }

            PersonAPI placeholderCaptain = Global.getFactory().createPerson();
            member.setCaptain(placeholderCaptain);

            ShipVariantAPI variant = member.getVariant();
            if (variant != null) {
                variant = variant.clone();
                variant.setSource(VariantSource.REFIT);
                variant.setOriginalVariant(null);
                member.setVariant(variant, false, true);
            }

            Random dModRandom = new Random(1000000L * member.getId().hashCode() + Global.getSector().getPlayerBattleSeed());
            dModRandom = Misc.getRandom(dModRandom.nextLong(), 5);
            DModManager.addDMods(data, false, Global.getSector().getPlayerFleet(), dModRandom);
            if (variant != null && DModManager.getNumDMods(variant) > 0) {
                DModManager.setDHull(variant);
            }

            float weaponProb = Global.getSettings().getFloat("salvageWeaponProb");
            float wingProb = Global.getSettings().getFloat("salvageWingProb");
            prepareShipForRecovery(member, false, true, false, weaponProb, wingProb, getSalvageRandom());

            getStoryRecoverableShips().add(member);
            Global.getLogger(MociRecoverableShipEncounterContext.class)
                    .info("Added forced recoverable ship to salvage list: " + member.getShipName());
        }

        return recoverableShips;
    }
}
