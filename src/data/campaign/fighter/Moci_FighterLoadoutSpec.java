package data.campaign.fighter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import org.json.JSONArray;
import java.util.TreeMap;
import org.json.JSONObject;
import data.hullmods.Moci_TacticalHullmods;

/** Immutable, content-addressed loadout. The free-weapon baseline travels with the LPC. */
public final class Moci_FighterLoadoutSpec {
    public static final String ID_PREFIX = "moci_flo_";
    private static final String ID_SUFFIX = "_wing";
    public static final String TAG = "moci_custom_fighter_loadout";
    /**
     * 指纹版本：v1 只描述原型 + 默认基线 + 当前装配，是历史存档里 wingId 的来源。
     * v2 追加单个战术改装 ID，v3 追加按 ID 排序的两组改装；相同装配始终得到相同 ID。
     */
    private static final String CANONICAL_V1 = "moci-fighter-loadout-v1;";
    private static final String CANONICAL_V2 = "moci-fighter-loadout-v2;";
    private static final String CANONICAL_V3 = "moci-fighter-loadout-v3;";
    private final String baseWingId;
    private final Map<String, String> baseline;
    private final Map<String, String> loadout;
    private final List<String> tacticalHullmods;
    private final String wingId;

    public Moci_FighterLoadoutSpec(String baseWingId, Map<String, String> baseline,
            Map<String, String> loadout) {
        this(baseWingId, baseline, loadout, List.of(), false);
    }

    /**
     * @param rawTrusted 读取保存定义时保留原始 ID；新建时校验已注册的战术改装及分组。
     */
    private Moci_FighterLoadoutSpec(String baseWingId, Map<String, String> baseline,
            Map<String, String> loadout, List<String> tacticalHullmods, boolean rawTrusted) {
        if (baseWingId == null || baseWingId.isBlank() || baseWingId.startsWith(ID_PREFIX)) {
            throw new IllegalArgumentException("Invalid prototype wing");
        }
        this.baseWingId = baseWingId;
        this.baseline = immutable(baseline);
        this.loadout = immutable(loadout);
        if (!this.baseline.keySet().equals(this.loadout.keySet())) {
            throw new IllegalArgumentException("Loadout and baseline must describe the same slots");
        }
        this.tacticalHullmods = rawTrusted ? tacticalHullmods.stream().sorted().toList()
                : Moci_TacticalHullmods.normalize(tacticalHullmods);
        this.wingId = ID_PREFIX + digest(canonical()) + ID_SUFFIX;
    }

    private static Map<String, String> immutable(Map<String, String> value) {
        if (value == null) throw new IllegalArgumentException("Empty slot definition");
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : value.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("Invalid slot or weapon");
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    public static Moci_FighterLoadoutSpec withTacticals(String baseWingId,
            Map<String, String> baseline, Map<String, String> loadout, List<String> ids) {
        if (!Moci_TacticalHullmods.validSelection(ids)) throw new IllegalArgumentException("Invalid tactical selection");
        return new Moci_FighterLoadoutSpec(baseWingId, baseline, loadout, ids, false);
    }

    private String canonical() {
        StringBuilder text = new StringBuilder(tacticalHullmods.isEmpty() ? CANONICAL_V1 : tacticalHullmods.size() == 1 ? CANONICAL_V2 : CANONICAL_V3);
        append(text, baseWingId);
        appendMap(text, baseline);
        appendMap(text, loadout);
        // v2/v3 追加排序后的战术改装 ID。
        for (String id : tacticalHullmods) append(text, id);
        return text.toString();
    }
    private static void append(StringBuilder text, String value) {
        text.append(value.length()).append(':').append(value).append(';');
    }
    private static void appendMap(StringBuilder text, Map<String, String> value) {
        text.append(value.size()).append(';');
        for (Map.Entry<String, String> entry : value.entrySet()) {
            append(text, entry.getKey()); append(text, entry.getValue());
        }
    }
    private static String digest(String value) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            // getInstance 声明了受检异常，但 SHA-256 是每个 Java 运行时都必须提供的算法，这里实际不会走到。
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        byte[] bytes = sha256.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte b : bytes) result.append(Character.forDigit((b >>> 4) & 15, 16))
                .append(Character.forDigit(b & 15, 16));
        return result.toString();
    }
    public String getBaseWingId() { return baseWingId; }
    public String getWingId() { return wingId; }
    public String getVariantId() { return wingId + "_v"; }
    public Map<String, String> getBaseline() { return baseline; }
    public Map<String, String> getLoadout() { return loadout; }
    public List<String> getTacticalHullmods() { return tacticalHullmods; }
    public boolean hasTacticalHullmod() { return !tacticalHullmods.isEmpty(); }
    public boolean sameDefinition(Moci_FighterLoadoutSpec other) {
        return other != null && baseWingId.equals(other.baseWingId)
                && baseline.equals(other.baseline) && loadout.equals(other.loadout)
                && tacticalHullmods.equals(other.tacticalHullmods);
    }
    public JSONObject toJSON() throws org.json.JSONException {
        JSONObject json = new JSONObject();
        json.put("id", wingId); json.put("baseWingId", baseWingId);
        json.put("baseline", new JSONObject(baseline));
        json.put("loadout", new JSONObject(loadout));
        // null 时不写字段，旧版本读到的 JSON 与历史文件保持一致。
        if (tacticalHullmods.size() == 1) json.put("tacticalHullmod", tacticalHullmods.get(0));
        else if (!tacticalHullmods.isEmpty()) json.put("tacticalHullmods", new JSONArray(tacticalHullmods));
        return json;
    }
    public static Moci_FighterLoadoutSpec fromJSON(JSONObject json) throws org.json.JSONException {
        String baseWingId = json.getString("baseWingId");
        Map<String, String> baseline = readMap(json.getJSONObject("baseline"));
        Map<String, String> loadout = readMap(json.getJSONObject("loadout"));
        String storedId = json.getString("id");
        if (json.has("tacticalHullmod") && json.has("tacticalHullmods")) {
            throw new IllegalArgumentException("Conflicting tactical fields");
        }
        List<String> raw = new ArrayList<>();
        if (json.has("tacticalHullmods")) {
            JSONArray array = json.getJSONArray("tacticalHullmods");
            if (array.length() != 2) throw new IllegalArgumentException("Invalid tactical count");
            for (int i = 0; i < array.length(); i++) raw.add(array.getString(i));
        } else if (json.has("tacticalHullmod") && !json.isNull("tacticalHullmod")) {
            String id = json.getString("tacticalHullmod");
            if (!id.isBlank()) raw.add(id);
        }
        if (raw.stream().anyMatch(String::isBlank) || raw.stream().distinct().count() != raw.size()) {
            throw new IllegalArgumentException("Invalid tactical IDs");
        }
        Moci_FighterLoadoutSpec spec = new Moci_FighterLoadoutSpec(baseWingId, baseline, loadout, raw, true);
        if (!spec.wingId.equals(storedId)) throw new IllegalArgumentException("Definition digest mismatch");
        return spec;
    }
    private static Map<String, String> readMap(JSONObject json) throws org.json.JSONException {
        Map<String, String> result = new LinkedHashMap<>();
        for (java.util.Iterator<?> it = json.keys(); it.hasNext();) {
            String key = String.valueOf(it.next());
            Object value = json.get(key);
            if (!(value instanceof String)) throw new IllegalArgumentException("Weapon ID must be text");
            result.put(key, (String) value);
        }
        return result;
    }
}
