package data.campaign;

import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl;

public class MociRecoverableShipInteractionDialogPlugin extends FleetInteractionDialogPluginImpl {
    public MociRecoverableShipInteractionDialogPlugin() {
        this(null);
    }

    public MociRecoverableShipInteractionDialogPlugin(FIDConfig params) {
        super(params);
        context = new MociRecoverableShipEncounterContext();
    }
}
