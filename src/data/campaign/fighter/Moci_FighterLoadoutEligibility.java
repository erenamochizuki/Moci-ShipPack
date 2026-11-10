package data.campaign.fighter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.util.Misc;

/** All eligibility policy lives here; custom wings are judged by their prototype. */
public final class Moci_FighterLoadoutEligibility {
    public static final String CONFIG_PATH = "data/config/Moci_FighterLoadout.json";
    /** 武器改装是否要求 acceptedWingTags（默认 moci_ms_fighter）；配置为 false 后开放给所有 LPC。 */
    static final String WEAPON_RESTRICTION_KEY = "restrictWeaponRefitByTag";
    /** 战术改装独立控制；默认不要求 acceptedWingTags。 */
    static final String TACTICAL_RESTRICTION_KEY = "restrictTacticalRefitByTag";
    static final String ACCEPTED_TAGS_KEY = "acceptedWingTags";
    private static final boolean DEFAULT_WEAPON_RESTRICTION = true;
    private static final boolean DEFAULT_TACTICAL_RESTRICTION = false;
    private static final Set<String> DEFAULT_ACCEPTED_TAGS = Set.of("moci_ms_fighter");
    private static boolean restrictWeaponRefitByTag = DEFAULT_WEAPON_RESTRICTION;
    private static boolean restrictTacticalRefitByTag = DEFAULT_TACTICAL_RESTRICTION;
    private static Set<String> acceptedTags = DEFAULT_ACCEPTED_TAGS;
    private static String failure;
    private Moci_FighterLoadoutEligibility() {}
    static void initialize() {
        failure = null;
        restrictWeaponRefitByTag = DEFAULT_WEAPON_RESTRICTION;
        restrictTacticalRefitByTag = DEFAULT_TACTICAL_RESTRICTION;
        acceptedTags = DEFAULT_ACCEPTED_TAGS;
        JSONObject config;
        try {
            config = Global.getSettings().loadJSON(CONFIG_PATH);
        } catch (IOException | JSONException | RuntimeException ex) {
            // 文件 IO 与 JSON 解析无法事先验证：SettingsAPI 没有查询 mod 资源是否存在的入口，
            // 缺文件时引擎资源加载器（com.fs.util.C）抛 RuntimeException("Error loading [...] resource, not found in [...]")。
            failure = "航空中队配置无法读取：" + ex.getMessage();
            return;
        }
        failure = apply(config);
    }
    /** Validates every key before publishing it, so a malformed file never leaves a half-applied policy. */
    private static String apply(JSONObject config) {
        if (config == null) return "航空中队配置无法读取：配置为空";
        boolean weapons = DEFAULT_WEAPON_RESTRICTION;
        boolean tactical = DEFAULT_TACTICAL_RESTRICTION;
        Set<String> tags = DEFAULT_ACCEPTED_TAGS;
        if (config.has(WEAPON_RESTRICTION_KEY)) {
            if (!(config.opt(WEAPON_RESTRICTION_KEY) instanceof Boolean value)) {
                return "航空中队配置无法读取：" + WEAPON_RESTRICTION_KEY + " 必须是 true 或 false";
            }
            weapons = value;
        }
        if (config.has(TACTICAL_RESTRICTION_KEY)) {
            if (!(config.opt(TACTICAL_RESTRICTION_KEY) instanceof Boolean value)) {
                return "航空中队配置无法读取：" + TACTICAL_RESTRICTION_KEY + " 必须是 true 或 false";
            }
            tactical = value;
        }
        if (config.has(ACCEPTED_TAGS_KEY)) {
            JSONArray array = config.optJSONArray(ACCEPTED_TAGS_KEY);
            if (array == null) return "航空中队配置无法读取：" + ACCEPTED_TAGS_KEY + " 必须是数组";
            LinkedHashSet<String> parsed = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object entry = array.opt(i);
                String tag = entry instanceof String text ? text.trim() : "";
                if (tag.isEmpty()) return "航空中队配置无法读取：" + ACCEPTED_TAGS_KEY + " 含空项或非字符串项";
                parsed.add(tag);
            }
            tags = Set.copyOf(parsed);
        }
        restrictWeaponRefitByTag = weapons;
        restrictTacticalRefitByTag = tactical;
        acceptedTags = tags;
        return null;
    }
    static String getFailure() { return failure; }
    /** 配置键 restrictWeaponRefitByTag：武器改装是否只开放给带 acceptedWingTags 的原型。 */
    public static boolean isWeaponRefitRestrictedByTag() { return restrictWeaponRefitByTag; }
    /** 配置键 restrictTacticalRefitByTag：战术改装是否只开放给带 acceptedWingTags 的原型。 */
    public static boolean isTacticalRefitRestrictedByTag() { return restrictTacticalRefitByTag; }
    /** Uses the game's automated hullmod and variant/hull tag policy; captains are optional. */
    public static String getCarrierLockReason(ShipVariantAPI variant) {
        return Misc.isAutomated(variant) ? "自动化舰船不能使用航空中队管理" : null;
    }
    public static boolean accepts(FighterWingSpecAPI prototype) {
        return acceptsWeapons(prototype);
    }
    public static boolean acceptsWeapons(FighterWingSpecAPI prototype) {
        return accepts(prototype, restrictWeaponRefitByTag);
    }
    public static boolean acceptsTactical(FighterWingSpecAPI prototype) {
        return accepts(prototype, restrictTacticalRefitByTag);
    }
    private static boolean accepts(FighterWingSpecAPI prototype, boolean restricted) {
        if (prototype == null || failure != null) return false;
        if (!restricted) return true;
        for (String tag : acceptedTags) if (prototype.hasTag(tag)) return true;
        return false;
    }
}
