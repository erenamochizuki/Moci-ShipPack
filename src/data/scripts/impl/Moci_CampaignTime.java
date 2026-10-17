package data.scripts.impl;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseManager;

/** Campaign age in game time, independent of the displayed cycle or the real-world date. */
public final class Moci_CampaignTime {
    private static final String START_TIMESTAMP_KEY = "moci_campaign_start_timestamp";
    private static final String NEX_START_TIMESTAMP_KEY = "$nex_startTimestamp";
    private static final float DAYS_PER_YEAR = 365f;

    private Moci_CampaignTime() {
    }

    /** Called after new-game world preparation, before the player starts playing. */
    public static void recordNewGameStart() {
        SectorAPI sector = Global.getSector();
        if (!sector.getPersistentData().containsKey(START_TIMESTAMP_KEY)) {
            sector.getPersistentData().put(START_TIMESTAMP_KEY, sector.getClock().getTimestamp());
        }
    }

    public static float getElapsedYears() {
        SectorAPI sector = Global.getSector();
        CampaignClockAPI clock = sector.getClock();
        Object timestamp = sector.getPersistentData().get(START_TIMESTAMP_KEY);
        if (!(timestamp instanceof Number)) {
            timestamp = sector.getMemoryWithoutUpdate().get(NEX_START_TIMESTAMP_KEY);
        }
        float days;
        if (timestamp instanceof Number) {
            days = clock.getElapsedDaysSince(((Number) timestamp).longValue());
        } else {
            // Older vanilla saves have no player-start timestamp. Their core clock includes
            // world preparation, but preserves campaign age when this mod is added mid-save.
            PirateBaseManager manager = PirateBaseManager.getInstance();
            days = manager == null ? 0f : manager.getUnadjustedDaysSinceStart();
        }
        return Float.isFinite(days) ? Math.max(0f, days) / DAYS_PER_YEAR : 0f;
    }
}
