package data.campaign;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

public final class MociFleetFlagshipHelper {
    private MociFleetFlagshipHelper() {
    }

    public static FleetMemberAPI syncFlagshipAndCommander(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return null;
        }
        return syncFlagshipAndCommander(fleet, fleet.getFlagship(), fleet.getCommander());
    }

    public static FleetMemberAPI syncFlagshipAndCommander(CampaignFleetAPI fleet, FleetMemberAPI desiredFlagship) {
        if (fleet == null) {
            return null;
        }
        return syncFlagshipAndCommander(fleet, desiredFlagship, fleet.getCommander());
    }

    public static FleetMemberAPI syncFlagshipAndCommander(CampaignFleetAPI fleet, FleetMemberAPI desiredFlagship, PersonAPI desiredCommander) {
        if (fleet == null) {
            return null;
        }

        FleetMemberAPI flagship = desiredFlagship != null ? desiredFlagship : fleet.getFlagship();
        if (flagship == null) {
            return null;
        }

        try {
            fleet.getFleetData().setFlagship(flagship);
        } catch (Throwable ignored) {
            flagship.setFlagship(true);
        }

        for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
            if (member == null) {
                continue;
            }
            if (member == flagship) {
                if (!member.isFlagship()) {
                    member.setFlagship(true);
                }
            } else if (member.isFlagship()) {
                member.setFlagship(false);
            }
        }

        PersonAPI commander = desiredCommander;
        if (commander == null) {
            commander = flagship.getCaptain();
        }
        if (commander == null) {
            commander = fleet.getCommander();
        }

        if (commander != null) {
            flagship.setCaptain(commander);
            fleet.setCommander(flagship.getCaptain());
        } else if (flagship.getCaptain() != null) {
            fleet.setCommander(flagship.getCaptain());
        }

        return flagship;
    }
}
