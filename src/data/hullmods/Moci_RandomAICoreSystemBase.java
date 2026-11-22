package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public abstract class Moci_RandomAICoreSystemBase extends Moci_InstallableAISystemBase {
    protected abstract String getRequiredCoreCommodityId();

    @Override
    protected boolean shouldHideAIDetailsBeforeAssignment() {
        return true;
    }

    @Override
    public CargoStackAPI getRequiredItem() {
        return Global.getSettings().createCargoStack(CargoAPI.CargoItemType.RESOURCES, getRequiredCoreCommodityId(), null);
    }

    static boolean isRandomCoreType(String typeId) {
        return Moci_AlphaCoreAISystem.AI_TYPE_ID.equals(typeId) || Moci_OmegaCoreAISystem.AI_TYPE_ID.equals(typeId);
    }

    static String resolveInitialType(String typeId) {
        if (!isRandomCoreType(typeId)) return typeId;
        List<String> pool = Moci_AlphaCoreAISystem.AI_TYPE_ID.equals(typeId)
                ? Moci_AlphaCoreAISystem.AI_POOL : Moci_OmegaCoreAISystem.AI_POOL;
        if (pool.isEmpty()) return null;
        int start = ThreadLocalRandom.current().nextInt(pool.size());
        for (int offset = 0; offset < pool.size(); offset++) {
            String candidate = pool.get((start + offset) % pool.size());
            if (Moci_ShipboardAIConfigLoader.getAiType(candidate) != null) return candidate;
        }
        return null;
    }
}
