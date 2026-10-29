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
    /** 武器改装是否要求 acceptedWingTags（默认 moci_ms_fighter）；改为 false 后开放给所有 LPC。 */
    public static boolean RESTRICT_WEAPON_REFIT_BY_TAG = true;
    /** 战术改装独立控制；默认不要求 moci_ms_fighter。 */
    public static boolean RESTRICT_TACTICAL_REFIT_BY_TAG = false;
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
        return acceptsWeapons(prototype);
    }
    public static boolean acceptsWeapons(FighterWingSpecAPI prototype) {
        return accepts(prototype, RESTRICT_WEAPON_REFIT_BY_TAG);
    }
    public static boolean acceptsTactical(FighterWingSpecAPI prototype) {
        return accepts(prototype, RESTRICT_TACTICAL_REFIT_BY_TAG);
    }
    private static boolean accepts(FighterWingSpecAPI prototype, boolean restricted) {
        if (prototype == null || failure != null) return false;
        if (!restricted) return true;
        for (String tag : acceptedTags) if (prototype.hasTag(tag)) return true;
        return false;
    }
}
