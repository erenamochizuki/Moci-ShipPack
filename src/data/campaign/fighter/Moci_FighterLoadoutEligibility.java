package data.campaign.fighter;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.util.Misc;

/** All eligibility policy lives here; custom wings are judged by their prototype. */
public final class Moci_FighterLoadoutEligibility {
    public static final String CONFIG_PATH = "data/config/Moci_FighterLoadout.json";
    private static Set<String> acceptedTags = Set.of("moci_ms_fighter");
    private static String failure;
    private Moci_FighterLoadoutEligibility() {}
    static void initialize() {
        failure = null;
        acceptedTags = Set.of("moci_ms_fighter");
        try {
            JSONObject config = Global.getSettings().loadJSON(CONFIG_PATH);
            if (!config.has("acceptedWingTags")) return;
            JSONArray tags = config.getJSONArray("acceptedWingTags");
            LinkedHashSet<String> parsed = new LinkedHashSet<>();
            for (int i = 0; i < tags.length(); i++) {
                String tag = tags.getString(i).trim();
                if (tag.isEmpty()) throw new IllegalArgumentException("Empty accepted wing tag");
                parsed.add(tag);
            }
            acceptedTags = Set.copyOf(parsed);
        } catch (Exception ex) {
            failure = "航空中队配置无法读取：" + ex.getMessage();
        }
    }
    static String getFailure() { return failure; }
    /** Uses the game's automated hullmod and variant/hull tag policy; captains are optional. */
    public static String getCarrierLockReason(ShipVariantAPI variant) {
        return Misc.isAutomated(variant) ? "自动化或无人舰船不能使用航空中队管理" : null;
    }
    public static boolean accepts(FighterWingSpecAPI prototype) {
        if (prototype == null || failure != null) return false;
        for (String tag : acceptedTags) if (prototype.hasTag(tag)) return true;
        return false;
    }
}
