package data.campaign.intel.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ui.PositionAPI;

/**
 * 自定义界面的布局槽位（契约见 tools/uikit/SPEC.md §11）。
 *
 * <p>位置来自 {@code data/config/Moci_UILayout/<id>.json}：每个槽位 {@code {in, x, y, w, h, pitchX, pitchY, cols}}，
 * 坐标相对父槽位 {@code in} 的左上角（没有 {@code in} = 弹窗根面板），y 向下。重复槽位（列表行、网格格子）用
 * index 推算：有 cols 时 {@code x + (i % cols) * pitchX, y + (i / cols) * pitchY}，否则 {@code x + i * pitchX, y + i * pitchY}。</p>
 *
 * <p>{@link #get(String)} 每次都重读文件，玩家/作者改完 JSON 重开弹窗即可生效。文件缺失或损坏时退回代码里登记的
 * 默认值（{@link #registerDefaults(String, float, float)}），打一行 {@code [Moci_UILayout]} 警告，界面照常可用。</p>
 *
 * <p>{@link #place(PositionAPI, String, int)} 只做 {@code inTL(x, y)}；尺寸由调用方创建组件时取 {@link Rect#w}/{@link Rect#h}。
 * 放置后通知 {@link PlacementListener}：游戏里它永远是 null，只有离线 UI 录制器会通过反射设置，用来把节点对应回槽位。
 * 本类的公开签名被录制器反射依赖，不要改名。</p>
 */
public final class Moci_UILayout {

    /** 放置回调。录制器用 java.lang.reflect.Proxy 实现；position 是 place 返回的 PositionAPI。 */
    public interface PlacementListener {
        void placed(Object position, String layout, String file, String slot, int index);
    }

    public static final String DIR = "data/config/Moci_UILayout/";
    private static final String MOD_ID = "moci_shippack";
    private static final String LOG_PREFIX = "[Moci_UILayout] ";
    private static final Logger LOG = Global.getLogger(Moci_UILayout.class);
    private static final Rect EMPTY = new Rect(0f, 0f, 0f, 0f);

    private static volatile PlacementListener listener;
    private static final Map<String, Defaults> DEFAULTS = new LinkedHashMap<>();

    /** 游戏里永远不设（保持 null）；只有离线录制器会调用。 */
    public static void setPlacementListener(PlacementListener l) {
        listener = l;
    }

    /**
     * 登记某个布局的代码内置默认值（文件缺失/损坏/缺槽位时使用）。重复登记会覆盖旧值。
     * 返回的构建器用 {@link Defaults#slot} / {@link Defaults#repeat} 继续添加槽位。
     */
    public static synchronized Defaults registerDefaults(String layoutId, float dialogW, float dialogH) {
        Defaults defaults = new Defaults(layoutId, dialogW, dialogH);
        DEFAULTS.put(layoutId, defaults);
        return defaults;
    }

    /** 读取布局：每次调用都重读 JSON；失败时用默认值并打一行警告。不会抛异常。 */
    public static Moci_UILayout get(String layoutId) {
        String id = layoutId == null ? "" : layoutId;
        String file = DIR + id + ".json";
        Defaults defaults;
        synchronized (Moci_UILayout.class) {
            defaults = DEFAULTS.get(id);
        }
        Map<String, Slot> base = defaults == null ? new LinkedHashMap<String, Slot>() : defaults.slots;
        float w = defaults == null ? 0f : defaults.dialogW;
        float h = defaults == null ? 0f : defaults.dialogH;

        JSONObject json;
        try {
            json = load(file);
        } catch (Exception ex) {
            LOG.warn(LOG_PREFIX + "布局文件读取失败，使用代码默认值: " + file + " | " + describe(ex)
                    + (defaults == null ? " | 且没有登记默认值" : ""));
            return new Moci_UILayout(id, file, w, h, new LinkedHashMap<>(base), "defaults");
        }

        try {
            JSONObject dialog = json.optJSONObject("dialog");
            if (dialog != null) {
                w = (float) dialog.optDouble("w", w);
                h = (float) dialog.optDouble("h", h);
            }
            int version = json.optInt("version", 1);
            if (version > 1) {
                LOG.warn(LOG_PREFIX + file + " 的 version=" + version + " 高于本代码支持的 1，按 1 解析");
            }
            JSONObject slotsJson = json.optJSONObject("slots");
            if (slotsJson == null) {
                LOG.warn(LOG_PREFIX + file + " 缺少 slots 对象，使用代码默认值");
                return new Moci_UILayout(id, file, w, h, new LinkedHashMap<>(base), "defaults");
            }
            Map<String, Slot> merged = new LinkedHashMap<>(base);
            List<String> problems = new ArrayList<>();
            Iterator<?> keys = slotsJson.keys();
            while (keys.hasNext()) {
                String name = String.valueOf(keys.next());
                if (name.startsWith("_")) continue;
                JSONObject o = slotsJson.optJSONObject(name);
                if (o == null) {
                    problems.add(name + "(不是对象)");
                    continue;
                }
                merged.put(name, parseSlot(o, base.get(name)));
            }
            List<String> missing = new ArrayList<>();
            for (String name : base.keySet()) {
                if (!slotsJson.has(name)) missing.add(name);
            }
            if (!missing.isEmpty() || !problems.isEmpty()) {
                LOG.warn(LOG_PREFIX + file + (missing.isEmpty() ? "" : " 缺少槽位(用代码默认值): " + String.join(", ", missing))
                        + (problems.isEmpty() ? "" : " 无效槽位(已忽略): " + String.join(", ", problems)));
            }
            return new Moci_UILayout(id, file, w, h, merged, "file");
        } catch (RuntimeException ex) {
            LOG.warn(LOG_PREFIX + "布局文件解析失败，使用代码默认值: " + file + " | " + describe(ex));
            return new Moci_UILayout(id, file, defaults == null ? 0f : defaults.dialogW,
                    defaults == null ? 0f : defaults.dialogH, new LinkedHashMap<>(base), "defaults");
        }
    }

    private static JSONObject load(String file) throws Exception {
        try {
            return Global.getSettings().loadJSON(file, MOD_ID);
        } catch (Exception first) {
            try {
                return Global.getSettings().loadJSON(file);
            } catch (Exception second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private static Slot parseSlot(JSONObject o, Slot d) {
        String in;
        if (o.has("in")) {
            in = o.isNull("in") ? null : o.optString("in", null);
            if (in != null && in.isEmpty()) in = null;
        } else {
            in = d == null ? null : d.in;
        }
        return new Slot(in,
                num(o, "x", d == null ? 0f : d.x),
                num(o, "y", d == null ? 0f : d.y),
                num(o, "w", d == null ? 0f : d.w),
                num(o, "h", d == null ? 0f : d.h),
                num(o, "pitchX", d == null ? 0f : d.pitchX),
                num(o, "pitchY", d == null ? 0f : d.pitchY),
                o.has("cols") ? Math.max(0, o.optInt("cols", 0)) : (d == null ? 0 : d.cols));
    }

    private static float num(JSONObject o, String key, float fallback) {
        if (!o.has(key) || o.isNull(key)) return fallback;
        double v = o.optDouble(key, fallback);
        return Double.isNaN(v) || Double.isInfinite(v) ? fallback : (float) v;
    }

    private static String describe(Throwable ex) {
        String msg = ex.getMessage();
        return ex.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private final String id;
    private final String file;
    private final float dialogW;
    private final float dialogH;
    private final Map<String, Slot> slots;
    private final String source;
    private final Set<String> warnedUnknown = new HashSet<>();

    private Moci_UILayout(String id, String file, float dialogW, float dialogH, Map<String, Slot> slots, String source) {
        this.id = id;
        this.file = file;
        this.dialogW = dialogW;
        this.dialogH = dialogH;
        this.slots = slots;
        this.source = source;
    }

    public String id() { return id; }
    /** 模组目录相对路径，如 data/config/Moci_UILayout/combat_squad_dialog.json。 */
    public String file() { return file; }
    public float dialogW() { return dialogW; }
    public float dialogH() { return dialogH; }
    /** "file" = 读到了 JSON；"defaults" = 用的代码默认值。 */
    public String source() { return source; }
    public boolean has(String slot) { return slot != null && slots.containsKey(slot); }
    /** 槽位的父槽位名；根槽位返回 null。 */
    public String parentOf(String slot) {
        Slot s = slot == null ? null : slots.get(slot);
        return s == null ? null : s.in;
    }

    public Rect rect(String slot) {
        return rect(slot, 0);
    }

    /** 相对父槽位的矩形；未知槽位返回 0×0 并警告一次。 */
    public Rect rect(String slot, int index) {
        Slot s = slot == null ? null : slots.get(slot);
        if (s == null) {
            if (warnedUnknown.add(String.valueOf(slot))) {
                LOG.warn(LOG_PREFIX + file + " 没有槽位 " + slot + "（代码默认值里也没有），按 0×0 处理");
            }
            return EMPTY;
        }
        float x = s.x;
        float y = s.y;
        int i = Math.max(0, index);
        if (i > 0) {
            if (s.cols > 0) {
                x += (i % s.cols) * s.pitchX;
                y += (i / s.cols) * s.pitchY;
            } else {
                x += i * s.pitchX;
                y += i * s.pitchY;
            }
        }
        return new Rect(x, y, s.w, s.h);
    }

    public PositionAPI place(PositionAPI pos, String slot) {
        return place(pos, slot, 0);
    }

    /** pos.inTL(rect.x, rect.y)，通知监听器，返回 pos。 */
    public PositionAPI place(PositionAPI pos, String slot, int index) {
        if (pos == null) return null;
        Rect r = rect(slot, index);
        pos.inTL(r.x, r.y);
        PlacementListener l = listener;
        if (l != null) {
            try {
                l.placed(pos, id, file, slot, Math.max(0, index));
            } catch (RuntimeException ex) {
                LOG.warn(LOG_PREFIX + "放置监听器异常: " + slot + "#" + index + " | " + describe(ex));
            }
        }
        return pos;
    }

    /** 相对父槽位的矩形（y 向下）。 */
    public static final class Rect {
        public final float x, y, w, h;

        public Rect(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        @Override public String toString() {
            return "Rect(" + x + ", " + y + ", " + w + "x" + h + ")";
        }
    }

    /** 代码内置默认值的构建器。 */
    public static final class Defaults {
        private final String id;
        private final float dialogW;
        private final float dialogH;
        private final Map<String, Slot> slots = new LinkedHashMap<>();

        private Defaults(String id, float dialogW, float dialogH) {
            this.id = id;
            this.dialogW = dialogW;
            this.dialogH = dialogH;
        }

        public String id() { return id; }

        /** 普通槽位；in = 父槽位名，null 表示弹窗根面板。 */
        public Defaults slot(String name, String in, float x, float y, float w, float h) {
            return repeat(name, in, x, y, w, h, 0f, 0f, 0);
        }

        /** 重复槽位：cols > 0 时按网格排，否则按 pitchX/pitchY 线性排。 */
        public Defaults repeat(String name, String in, float x, float y, float w, float h,
                float pitchX, float pitchY, int cols) {
            slots.put(name, new Slot(in, x, y, w, h, pitchX, pitchY, Math.max(0, cols)));
            return this;
        }
    }

    private static final class Slot {
        final String in;
        final float x, y, w, h, pitchX, pitchY;
        final int cols;

        Slot(String in, float x, float y, float w, float h, float pitchX, float pitchY, int cols) {
            this.in = in;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.pitchX = pitchX;
            this.pitchY = pitchY;
            this.cols = cols;
        }
    }
}
