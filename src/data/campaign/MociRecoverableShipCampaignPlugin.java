package data.campaign;

import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.BaseCampaignPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;

public class MociRecoverableShipCampaignPlugin extends BaseCampaignPlugin {
    public static final String PLUGIN_ID = "Moci_RecoverableShipCampaignPlugin";

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public boolean isTransient() {
        return true;
    }

    @Override
    public PluginPick<InteractionDialogPlugin> pickInteractionDialogPlugin(SectorEntityToken interactionTarget) {
        if (MociRecoverableShipHelper.isMarkedRecoverableTarget(interactionTarget)) {
            return new PluginPick<InteractionDialogPlugin>(
                    new MociRecoverableShipInteractionDialogPlugin(),
                    PickPriority.MOD_SPECIFIC
            );
        }
        return super.pickInteractionDialogPlugin(interactionTarget);
    }
}
