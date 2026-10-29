package xi.arsenal;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.CampaignPlugin;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/** Owned application content mappings and guidance for this pack's unique missile. */
public final class PsychoModPlugin extends BaseModPlugin {
    @Override public void onApplicationLoad() {
        PsychoframeSystem.installHullSystems();
        ZetaContent.install();
        UnicornMagnumContent.install();
    }
    @Override
    public PluginPick<MissileAIPlugin> pickMissileAI(MissileAPI missile, ShipAPI launchingShip) {
        if (!PsychoMissileAI.PROJECTILE_ID.equals(missile.getProjectileSpecId())) return null;
        return new PluginPick<MissileAIPlugin>(new PsychoMissileAI(missile, launchingShip),
                CampaignPlugin.PickPriority.MOD_SPECIFIC);
    }
}
