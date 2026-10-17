package data.scripts.campaign.bar;

import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEventCreator;

public class Moci_YurikaBarEventCreator extends BaseBarEventCreator {
    @Override
    public PortsideBarEvent createBarEvent() {
        return new Moci_YurikaBarEvent();
    }

    @Override
    public float getBarEventFrequencyWeight() {
        return 1000000f;
    }
}
