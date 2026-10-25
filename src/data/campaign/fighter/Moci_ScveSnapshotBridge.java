package data.campaign.fighter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
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
 * SCVE 未安装 / 类不可见 / 字段改名时全部静默降级为 no-op，绝不影响本功能自身。
 *
 * <p>约束：脚本类加载器不允许使用 {@code java.lang.reflect.*}，所以这里和 Registry 一样走 {@code java.lang.invoke}。
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
        if (!resolve()) {
            return;
        }
        try {
            if (mode == Mode.INCREMENTAL) {
                addToSnapshot(wing);
            } else if (mode == Mode.FULL_REFRESH) {
                refreshHandle.invokeWithArguments();
            }
        } catch (Throwable t) {
            // 增量失败时再退回整份刷新一次；仍然失败就彻底停用，避免每次注册都刷错误日志。
            logWarn("刷新 SCVE 快照失败（" + mode + "），停用兼容桥：" + t);
            mode = Mode.ABSENT;
        }
    }

    /** 供排查/自检读取当前生效的兼容模式。 */
    public static synchronized String getActiveMode() {
        resolve();
        return mode.name();
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
        } catch (Throwable t) {
            // 字段签名变了或不允许访问：退回整份刷新。
            logWarn("SCVE 快照表不可直接访问（" + t + "），退回 getOriginalData() 整份刷新。");
        }

        try {
            refreshHandle = findRefreshMethod(publicLookup, privateLookup);
            mode = Mode.FULL_REFRESH;
            return true;
        } catch (Throwable t) {
            mode = Mode.ABSENT;
            logWarn("SCVE 存在但接口不匹配，兼容桥停用：" + t);
            return false;
        }
    }

    private static Class<?> loadScveClass() {
        try {
            return Class.forName(SCVE_FILTER_UTILS);
        } catch (Throwable ignored) {
            // 继续尝试脚本类加载器。
        }
        try {
            ClassLoader loader = Global.getSettings().getScriptClassLoader();
            return loader == null ? null : loader.loadClass(SCVE_FILTER_UTILS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandles.Lookup privateLookup(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle findStaticGetter(MethodHandles.Lookup publicLookup,
            MethodHandles.Lookup privateLookup, String name, Class<?> type) throws Throwable {
        // findStaticGetter 要求类型与字段声明类型（擦除后）完全一致，
        // SCVE 声明的实际类型是 HashMap，而不是 Map，所以两种都试一遍。
        Class<?>[] candidates = type == java.util.Map.class
                ? new Class<?>[] {type, HashMap.class}
                : new Class<?>[] {type};
        Throwable last = null;
        for (Class<?> candidate : candidates) {
            try {
                return publicLookup.findStaticGetter(scveClass, name, candidate);
            } catch (Throwable first) {
                last = first;
                if (privateLookup != null) {
                    try {
                        return privateLookup.findStaticGetter(scveClass, name, candidate);
                    } catch (Throwable second) {
                        last = second;
                    }
                }
            }
        }
        throw last == null ? new NoSuchFieldException(name) : last;
    }

    private static MethodHandle findRefreshMethod(MethodHandles.Lookup publicLookup,
            MethodHandles.Lookup privateLookup) throws Throwable {
        try {
            return publicLookup.findStatic(scveClass, METHOD_REFRESH, MethodType.methodType(void.class));
        } catch (Throwable first) {
            if (privateLookup != null) {
                return privateLookup.findStatic(scveClass, METHOD_REFRESH, MethodType.methodType(void.class));
            }
            throw first;
        }
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
