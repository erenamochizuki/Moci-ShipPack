package data.campaign.fighter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONObject;
import data.hullmods.Moci_TacticalHullmods;

/** Immutable, content-addressed loadout. The free-weapon baseline travels with the LPC. */
public final class Moci_FighterLoadoutSpec {
    public static final String ID_PREFIX = "moci_flo_";
    public static final String TAG = "moci_custom_fighter_loadout";
    /**
     * 指纹版本：v1 只描述原型 + 默认基线 + 当前装配，是历史存档里 wingId 的来源。
     * v2 在末尾追加战术改装 ID；未选战术改装时必须继续用 v1，否则旧存档的 wingId 会全部漂移。
     */
    private static final String CANONICAL_V1 = "moci-fighter-loadout-v1;";
    private static final String CANONICAL_V2 = "moci-fighter-loadout-v2;";
    private final String baseWingId;
    private final Map<String, String> baseline;
    private final Map<String, String> loadout;
    private final String tacticalHullmod;
    private final String wingId;

    public Moci_FighterLoadoutSpec(String baseWingId, Map<String, String> baseline,
            Map<String, String> loadout) {
        this(baseWingId, baseline, loadout, null, false);
    }

    /** 带战术改装的构造：仅接受 null 或已注册且带战术改装 tag 的船插，其它值按未选处理。 */
    public Moci_FighterLoadoutSpec(String baseWingId, Map<String, String> baseline,
            Map<String, String> loadout, String tacticalHullmod) {
        this(baseWingId, baseline, loadout, tacticalHullmod, false);
    }

    /**
     * @param rawTrusted true 时原样保留 tacticalHullmod 文本（只读存档解析用），保证历史定义的 wingId 不变；
     *                   false 时走 normaliseTactical 归一化。
     */
    private Moci_FighterLoadoutSpec(String baseWingId, Map<String, String> baseline,
            Map<String, String> loadout, String tacticalHullmod, boolean rawTrusted) {
        if (baseWingId == null || baseWingId.isBlank() || baseWingId.startsWith(ID_PREFIX)) {
            throw new IllegalArgumentException("Invalid prototype wing");
        }
        this.baseWingId = baseWingId;
        this.baseline = immutable(baseline);
        this.loadout = immutable(loadout);
        if (!this.baseline.keySet().equals(this.loadout.keySet())) {
            throw new IllegalArgumentException("Loadout and baseline must describe the same slots");
        }
        this.tacticalHullmod = rawTrusted ? emptyToNull(tacticalHullmod)
                : normaliseTactical(tacticalHullmod);
        this.wingId = ID_PREFIX + digest(canonical());
    }

    private static Map<String, String> immutable(Map<String, String> value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Empty slot definition");
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : value.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("Invalid slot or weapon");
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 战术改装 tag 归一化：helper 类缺失或船插未定义时一律按“未选战术改装”处理，
     * 绝不让一个过期/非法的船插 ID 阻断存档读取或抛异常。
     */
    private static String normaliseTactical(String value) {
        String id = emptyToNull(value);
        if (id == null) return null;
        try {
            return Moci_TacticalHullmods.isTactical(id) ? id : null;
        } catch (Throwable ignored) {
            // 船插 helper 不可用（类缺失 / 未初始化）时按未选处理。
            return null;
        }
    }

    private String canonical() {
        StringBuilder text = new StringBuilder(tacticalHullmod == null ? CANONICAL_V1 : CANONICAL_V2);
        append(text, baseWingId);
        appendMap(text, baseline);
        appendMap(text, loadout);
        // v2 才追加战术改装；此处的 append 编码与其它字段完全一致，不改变任何既有字段的编码方式。
        if (tacticalHullmod != null) append(text, tacticalHullmod);
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
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(Character.forDigit((b >>> 4) & 15, 16))
                    .append(Character.forDigit(b & 15, 16));
            return result.toString();
        } catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    public String getBaseWingId() { return baseWingId; }
    public String getWingId() { return wingId; }
    public String getVariantId() { return wingId + "_v"; }
    public Map<String, String> getBaseline() { return baseline; }
    public Map<String, String> getLoadout() { return loadout; }
    /** 战术改装船插 ID；null 表示未选。 */
    public String getTacticalHullmod() { return tacticalHullmod; }
    public boolean hasTacticalHullmod() { return tacticalHullmod != null; }
    public boolean sameDefinition(Moci_FighterLoadoutSpec other) {
        return other != null && baseWingId.equals(other.baseWingId)
                && baseline.equals(other.baseline) && loadout.equals(other.loadout)
                && java.util.Objects.equals(tacticalHullmod, other.tacticalHullmod);
    }
    public JSONObject toJSON() throws org.json.JSONException {
        JSONObject json = new JSONObject();
        json.put("id", wingId); json.put("baseWingId", baseWingId);
        json.put("baseline", new JSONObject(baseline));
        json.put("loadout", new JSONObject(loadout));
        // null 时不写字段，旧版本读到的 JSON 与历史文件保持一致。
        if (tacticalHullmod != null) json.put("tacticalHullmod", tacticalHullmod);
        return json;
    }
    public static Moci_FighterLoadoutSpec fromJSON(JSONObject json) throws org.json.JSONException {
        String baseWingId = json.getString("baseWingId");
        Map<String, String> baseline = readMap(json.getJSONObject("baseline"));
        Map<String, String> loadout = readMap(json.getJSONObject("loadout"));
        String storedId = json.getString("id");
        String raw = json.has("tacticalHullmod") && !json.isNull("tacticalHullmod")
                ? json.getString("tacticalHullmod") : null;
        Moci_FighterLoadoutSpec spec = new Moci_FighterLoadoutSpec(baseWingId, baseline, loadout, raw);
        if (!spec.wingId.equals(storedId)) {
            // 归一化可能把“历史/非法船插值”改成 null 从而改变指纹：先按原样文本重算一次，
            // 只要与保存的 id 一致就原样保留，绝不因为战术改装非法而丢掉整条定义或抛到上层。
            if (raw != null) {
                Moci_FighterLoadoutSpec preserved =
                        new Moci_FighterLoadoutSpec(baseWingId, baseline, loadout, raw, true);
                if (preserved.wingId.equals(storedId)) return preserved;
            }
            throw new IllegalArgumentException("Definition digest mismatch");
        }
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
