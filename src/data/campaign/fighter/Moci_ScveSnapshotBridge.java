package data.campaign.fighter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;

/**
 * SCVE（舰船目录 / 装配编辑器，mod id 见其 jar）软兼容桥。
 *
 * <p>背景：SCVE 在 {@code onApplicationLoad} 里对「当前所有联队规格」（{@code SettingsAPI.getAllFighterWingSpecs()}）
 * 做快照，读档时在 {@code SCVE_FilterUtils.restoreOriginalData(boolean, boolean, boolean)} 里按 id 把快照写回：
 * {@code wing.getTags().clear(); wing.getTags().addAll(ORIGINAL_WING_TAGS_MAP.get(wing.getId()));}。
 * 本功能会在游戏运行期动态注册新的 {@code moci_flo_} 联队规格（见 {@link Moci_FighterLoadoutRegistry}），
 * 这些 id 不在 SCVE 的旧快照里，于是下一次读档 / 进任务时 {@code addAll(null)} 直接 NPE：
 * <pre>
 * java.lang.NullPointerException: Cannot invoke "java.util.Collection.iterator()" because "&lt;parameter1&gt;" is null
 *   at data.scripts.SCVE_FilterUtils.restoreOriginalData(SCVE_FilterUtils.java:83)
 *   at data.scripts.SCVE_ModPlugin.onGameLoad(SCVE_ModPlugin.java:112)
 * </pre>
 *
 * <p>本类只做一件事：每注册成功一个联队，就把该联队补进 SCVE 的快照表（纯增量，不改动其它条目、不重算整份快照），
 * 这样 SCVE 的还原逻辑不会拿到 null。增量模式不可用时退回「重新调用一次 SCVE_FilterUtils.getOriginalData()」。
 * SCVE 未安装 / 类不可见时静默降级为 no-op；接口对不上，或运行期写快照 / 刷新失败时记一条日志后停用兼容桥，
 * 绝不影响本功能自身。
 *
 * <p>约束：脚本类加载器不允许使用 {@code java.lang.reflect.*}，所以这里和 Registry 一样走 {@code java.lang.invoke}。
 * 第三方的类和字段在不在，只能靠加载、查找来确认，这几处 catch 只接 API 声明的具体异常；
 * 唯一的宽口径拦截在 {@link #noteWingRegistered}，因为 MethodHandle 调用在编译期就声明了 throws Throwable。
 * 排查日志统一前缀 {@code [Moci][航空中队管理][SCVE]}，常驻输出、不受 devMode 影响。
 */
public final class Moci_ScveSnapshotBridge {

    private static final String SCVE_FILTER_UTILS = "data.scripts.SCVE_FilterUtils";
    private static final String FIELD_WING_TAGS = "ORIGINAL_WING_TAGS_MAP";
    private static final String FIELD_WING_OP = "ORIGINAL_WING_OP_COST_MAP";
    private static final String METHOD_REFRESH = "getOriginalData";

    private enum Mode { UNRESOLVED, INCREMENTAL, FULL_REFRESH, ABSENT }

    private static Mode mode = Mode.UNRESOLVED;
    private static MethodHandle wingTagsGetter;
    private static MethodHandle wingOpGetter;
    private static MethodHandle refreshHandle;
    private static Class<?> scveClass;

    private Moci_ScveSnapshotBridge() {
    }

    /**
     * 本功能注册完一个新联队后调用。幂等、可重复调用；内部异常全部吞掉并降级，
     * 调用方不需要 try/catch（但调用点仍应放在注册成功之后）。
     */
    public static synchronized void noteWingRegistered(FighterWingSpecAPI wing) {
        if (wing == null || wing.getId() == null) {
            return;
        }
        try {
            if (!resolve()) {
                return;
            }
            if (mode == Mode.INCREMENTAL) {
                addToSnapshot(wing);
            } else if (mode == Mode.FULL_REFRESH) {
                refreshHandle.invokeWithArguments();
            }
        } catch (Throwable t) {
            // 兼容桥边界：MethodHandle 调用在编译期声明 throws Throwable，执行的又是 SCVE 自己的代码，无法事先验证。
            // 失败后不再退回整份刷新，直接停用兼容桥（ABSENT），避免每次注册都刷同一条错误日志。
            logWarn("刷新 SCVE 快照失败（" + mode + "），停用兼容桥：" + t);
            mode = Mode.ABSENT;
        }
    }

    private static boolean resolve() {
        if (mode != Mode.UNRESOLVED) {
            return mode != Mode.ABSENT;
        }
        scveClass = loadScveClass();
        if (scveClass == null) {
            mode = Mode.ABSENT;
            return false;
        }

        MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
        MethodHandles.Lookup privateLookup = privateLookup(scveClass);
        try {
            wingTagsGetter = findStaticGetter(publicLookup, privateLookup, FIELD_WING_TAGS, Map.class);
            wingOpGetter = findStaticGetter(publicLookup, privateLookup, FIELD_WING_OP, Map.class);
            mode = Mode.INCREMENTAL;
            return true;
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            // 字段改名、改类型或不允许访问：没有 java.lang.reflect 就不能预先检查，只能查找失败后退回整份刷新。
            logWarn("SCVE 快照表不可直接访问（" + ex + "），退回 getOriginalData() 整份刷新。");
        }

        try {
            refreshHandle = findRefreshMethod(publicLookup, privateLookup);
            mode = Mode.FULL_REFRESH;
            return true;
        } catch (NoSuchMethodException | IllegalAccessException ex) {
            // 同上：刷新方法也对不上时只能停用。
            mode = Mode.ABSENT;
            logWarn("SCVE 存在但接口不匹配，兼容桥停用：" + ex);
            return false;
        }
    }

    private static Class<?> loadScveClass() {
        try {
            return Class.forName(SCVE_FILTER_UTILS);
        } catch (ClassNotFoundException | LinkageError ignored) {
            // 第三方类在不在只能靠加载来确认：没装 SCVE 是 ClassNotFoundException，
            // 版本或依赖对不上是 LinkageError。继续尝试脚本类加载器。
        }
        SettingsAPI settings = Global.getSettings();
        ClassLoader loader = settings == null ? null : settings.getScriptClassLoader();
        if (loader == null) {
            return null;
        }
        try {
            return loader.loadClass(SCVE_FILTER_UTILS);
        } catch (ClassNotFoundException | LinkageError ignored) {
            // 同上：没装 SCVE，或类与当前版本不兼容。
            return null;
        }
    }

    private static MethodHandles.Lookup privateLookup(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException ignored) {
            // SCVE 的包是否向本 mod 开放，只有请求时才知道；拿不到就只用 publicLookup。
            return null;
        }
    }

    private static MethodHandle findStaticGetter(MethodHandles.Lookup publicLookup,
            MethodHandles.Lookup privateLookup, String name, Class<?> type)
            throws NoSuchFieldException, IllegalAccessException {
        // privateLookup 能访问 SCVE 类的全部成员，覆盖 publicLookup 的访问范围，拿得到就只用它。
        MethodHandles.Lookup lookup = privateLookup != null ? privateLookup : publicLookup;
        if (type != Map.class) {
            return lookup.findStaticGetter(scveClass, name, type);
        }
        // findStaticGetter 要求类型与字段声明类型（擦除后）完全一致，SCVE 声明的实际类型是 HashMap，而不是 Map。
        // 没有 java.lang.reflect 查不到声明类型，只能先按 HashMap 找，类型不符（NoSuchFieldException）再按 Map 找。
        try {
            return lookup.findStaticGetter(scveClass, name, HashMap.class);
        } catch (NoSuchFieldException declaredAsMap) {
            return lookup.findStaticGetter(scveClass, name, Map.class);
        }
    }

    private static MethodHandle findRefreshMethod(MethodHandles.Lookup publicLookup,
            MethodHandles.Lookup privateLookup) throws NoSuchMethodException, IllegalAccessException {
        // 同 findStaticGetter：privateLookup 的访问范围覆盖 publicLookup。
        MethodHandles.Lookup lookup = privateLookup != null ? privateLookup : publicLookup;
        return lookup.findStatic(scveClass, METHOD_REFRESH, MethodType.methodType(void.class));
    }

    @SuppressWarnings("unchecked")
    private static void addToSnapshot(FighterWingSpecAPI wing) throws Throwable {
        Object tagsRaw = wingTagsGetter.invokeWithArguments();
        if (!(tagsRaw instanceof Map)) {
            throw new IllegalStateException("ORIGINAL_WING_TAGS_MAP 不是 Map");
        }
        Map<String, Set<String>> tags = (Map<String, Set<String>>) tagsRaw;
        Set<String> copy = new HashSet<String>();
        Set<String> live = wing.getTags();
        if (live != null) {
            copy.addAll(live);
        }
        tags.put(wing.getId(), copy);

        Object opRaw = wingOpGetter.invokeWithArguments();
        if (opRaw instanceof Map) {
            ((Map<String, Float>) opRaw).put(wing.getId(), wing.getOpCost(null));
        }
    }

    private static void logWarn(String message) {
        if (Global.getLogger(Moci_ScveSnapshotBridge.class) != null) {
            Global.getLogger(Moci_ScveSnapshotBridge.class).warn("[Moci][航空中队管理][SCVE] " + message);
        }
    }
}
