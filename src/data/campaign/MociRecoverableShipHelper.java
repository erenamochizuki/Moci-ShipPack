package data.campaign;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.VariantSource;

public final class MociRecoverableShipHelper {
    public static final String KEY_FORCE_RECOVERABLE_MEMBER = "$moci_force_recoverable_member";
    public static final String KEY_FORCE_RECOVERABLE_MEMBER_ID = "$moci_force_recoverable_member_id";

    private MociRecoverableShipHelper() {
    }

    public static void markFleetFlagshipForRecovery(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return;
        }
        markFleetMemberForRecovery(fleet, fleet.getFlagship());
    }

    public static void markFleetMemberForRecovery(CampaignFleetAPI fleet, FleetMemberAPI member) {
        if (fleet == null || member == null) {
            return;
        }
        ensureMemberVariantAlwaysRecoverable(member);
        fleet.getMemoryWithoutUpdate().set(KEY_FORCE_RECOVERABLE_MEMBER, true);
        fleet.getMemoryWithoutUpdate().set(KEY_FORCE_RECOVERABLE_MEMBER_ID, member.getId());
    }

    public static void ensureMemberVariantAlwaysRecoverable(FleetMemberAPI member) {
        if (member == null || member.getVariant() == null) {
            return;
        }
        member.setVariant(member.getVariant().clone(), false, true);
        member.getVariant().setSource(VariantSource.REFIT);
        member.getVariant().addTag(Tags.VARIANT_ALWAYS_RECOVERABLE);
        member.getVariant().removeTag(Tags.VARIANT_UNBOARDABLE);
        member.getVariant().removeTag(Tags.VARIANT_UNRESTORABLE);
    }

    public static boolean isMemberVariantAlwaysRecoverable(FleetMemberAPI member) {
        return member != null
                && member.getVariant() != null
                && member.getVariant().hasTag(Tags.VARIANT_ALWAYS_RECOVERABLE);
    }

    public static boolean isMarkedRecoverableTarget(SectorEntityToken interactionTarget) {
        if (!(interactionTarget instanceof CampaignFleetAPI)) {
            return false;
        }
        return isMarkedRecoverableFleet((CampaignFleetAPI) interactionTarget);
    }

    public static boolean isMarkedRecoverableFleet(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return false;
        }
        return fleet.getMemoryWithoutUpdate().getBoolean(KEY_FORCE_RECOVERABLE_MEMBER)
                && getForcedRecoverableMemberId(fleet) != null;
    }

    public static String getForcedRecoverableMemberId(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return null;
        }
        String id = fleet.getMemoryWithoutUpdate().getString(KEY_FORCE_RECOVERABLE_MEMBER_ID);
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        return id;
    }

    public static FleetMemberAPI findForcedRecoverableMember(CampaignFleetAPI fleet) {
        String id = getForcedRecoverableMemberId(fleet);
        if (fleet == null || id == null) {
            return null;
        }
        for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
            if (id.equals(member.getId())) {
                return member;
            }
        }
        return null;
    }

    public static void clearForcedRecoverableMember(CampaignFleetAPI fleet) {
        if (fleet == null) {
            return;
        }
        fleet.getMemoryWithoutUpdate().unset(KEY_FORCE_RECOVERABLE_MEMBER);
        fleet.getMemoryWithoutUpdate().unset(KEY_FORCE_RECOVERABLE_MEMBER_ID);
    }
}
