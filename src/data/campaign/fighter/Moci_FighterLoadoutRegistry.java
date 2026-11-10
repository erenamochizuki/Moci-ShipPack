package data.campaign.fighter;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.MethodDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;

/**
 * 原版战机变体表 / 联队规格表的运行时写入封装：变体在 com.fs.starfarer.loading.B（键 HullVariantSpec.getHullVariantId()），
 * 联队在 com.fs.starfarer.loading.K（键 wing id）；入口是 B.put(HullVariantSpec)（带 boolean 的重载等价 false）与 K.put(String, FighterWingSpec)。
 * 重名会抛 already exists，注册前必须判重；SettingsAPI.putSpec 写的是 SpecStore 分桶表，与这两张私有 Map 不通，只能直接写。
 * 目标方法名被混淆（当前均为 o00000），故按精确 MethodType 签名匹配（含返回值），内省不可用时退回候选名 FALLBACK_NAMES。
 * 脚本类引用 java.lang.reflect.* 会被脚本类加载器抛 SecurityException，因此调用改用 java.lang.invoke、取名改用 java.beans。
 * 私有字段 autofitCategory / autofitPriorityCategories / sourceMod 用 privateLookupIn 取句柄；任一字段不能继承时，服务阻止提交。
 *
 * <p>异常处理：混淆类、方法和私有字段在不在，只能靠加载和查找来确认，这些查找只接 API 声明的具体异常；
 * 句柄调用（invokeWithArguments）在编译期声明 throws Throwable，执行的是引擎内部代码，
 * 是本类仅有的宽口径拦截，失败一律按「不可用」处理，由服务阻止提交。
 */
public class Moci_FighterLoadoutRegistry {

    /** Package-local port lets the regression harness exercise real transaction code. */
    interface Backend {
        boolean registerVariant(ShipVariantAPI variant);
        boolean registerWing(String id, FighterWingSpecAPI wing);
        FighterWingSpecAPI createWing();
        default boolean inheritWingFields(FighterWingSpecAPI target, FighterWingSpecAPI source) { return true; }
    }
    static Backend backend;

    private static final String LOG_PREFIX = "Moci-AirSquadron|loadout|";

    private static final String VARIANT_STORE_CLASS = "com.fs.starfarer.loading.B";
    private static final String WING_STORE_CLASS = "com.fs.starfarer.loading.K";
    private static final String VARIANT_SPEC_CLASS = "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String WING_SPEC_CLASS = "com.fs.starfarer.loading.specs.FighterWingSpec";

    /** Introspector 不可用时的兜底候选名（0.98a-RC8 实测值）。 */
    private static final String[] FALLBACK_NAMES = { "o00000" };

    private static boolean initialized = false;
    private static boolean available = false;
    private static String unavailableReason = "尚未初始化";

    private static Class<?> variantSpecClass;
    private static MethodHandle variantPut;          // (HullVariantSpec)void 或 (HullVariantSpec, boolean)void
    private static boolean variantPutTakesFlag = false;
    private static MethodHandle wingPut;             // (String, FighterWingSpec)void
    private static MethodHandle wingSpecConstructor; // ()FighterWingSpec

    private static boolean wingFieldHandlesResolved = false;
    private static boolean wingFieldHandlesAvailable = false;
    private static Class<?> wingSpecImplClass;
    private static MethodHandle autofitCategoryGetter;
    private static MethodHandle autofitCategorySetter;
    private static MethodHandle autofitPriorityGetter;
    private static MethodHandle autofitPrioritySetter;
    private static MethodHandle sourceModGetter;
    private static MethodHandle sourceModSetter;

    private Moci_FighterLoadoutRegistry() {
    }

    /** 探测一次即可，重复调用安全。 */
    public static synchronized boolean init() {
        if (backend != null) return true;
        if (initialized) {
            return available;
        }
        initialized = true;

        try {
            variantSpecClass = Class.forName(VARIANT_SPEC_CLASS);
            Class<?> wingSpecClass = Class.forName(WING_SPEC_CLASS);
            Class<?> variantStoreClass = Class.forName(VARIANT_STORE_CLASS);
            Class<?> wingStoreClass = Class.forName(WING_STORE_CLASS);

            // 单参数版本等价于 flag=false（重名即抛），优先用它。
            variantPut = findStatic(variantStoreClass, MethodType.methodType(void.class, variantSpecClass));
            variantPutTakesFlag = false;
            if (variantPut == null) {
                variantPut = findStatic(variantStoreClass,
                        MethodType.methodType(void.class, variantSpecClass, boolean.class));
                variantPutTakesFlag = variantPut != null;
            }
            if (variantPut == null) {
                unavailableReason = "未找到战机变体注册方法（B.put(HullVariantSpec)）";
                return false;
            }

            wingPut = findStatic(wingStoreClass, MethodType.methodType(void.class, String.class, wingSpecClass));
            if (wingPut == null) {
                unavailableReason = "未找到联队规格注册方法（K.put(String, FighterWingSpec)）";
                return false;
            }

            wingSpecConstructor = MethodHandles.lookup()
                    .findConstructor(wingSpecClass, MethodType.methodType(void.class));

            available = true;
            unavailableReason = null;
        } catch (ReflectiveOperationException | LinkageError | SecurityException ex) {
            // 混淆类与构造器只能靠加载和查找确认是否存在（游戏版本变化时会缺失），无法事先验证；
            // 找不到就把整个编辑器标记为不可用。
            available = false;
            unavailableReason = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            logWarn("战机改装注册入口初始化失败：" + unavailableReason);
        }
        return available;
    }

    /** 在候选名里找签名（MethodType 含返回值）完全匹配的 public static 方法。 */
    private static MethodHandle findStatic(Class<?> owner, MethodType type) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (String name : candidateNames(owner)) {
            try {
                return lookup.findStatic(owner, name, type);
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
                // 方法名被混淆，只能逐个候选名试探签名；不符就试下一个，全部失败由调用方判定。
            }
        }
        return null;
    }

    /** 候选名 = java.beans 内省结果 + 硬编码兜底值。 */
    private static List<String> candidateNames(Class<?> owner) {
        Set<String> names = new LinkedHashSet<String>();
        names.addAll(introspectedNames(owner));
        for (String fallback : FALLBACK_NAMES) {
            names.add(fallback);
        }
        return new ArrayList<String>(names);
    }

    /** 用 java.beans 只列 public 方法名当"名字字典"（MethodDescriptor 不带参数类型），匹配交给 findStatic。 */
    private static List<String> introspectedNames(Class<?> owner) {
        List<String> names = new ArrayList<String>();
        try {
            BeanInfo info = Introspector.getBeanInfo(owner);
            MethodDescriptor[] descriptors = info == null ? null : info.getMethodDescriptors();
            if (descriptors == null) {
                return names;
            }
            for (MethodDescriptor descriptor : descriptors) {
                if (descriptor != null && descriptor.getName() != null) {
                    names.add(descriptor.getName());
                }
            }
        } catch (IntrospectionException | LinkageError ex) {
            // getBeanInfo 声明 IntrospectionException；运行时缺 java.desktop 模块时是 LinkageError。
            // 内省只是给候选名，失败时退回兜底候选名，不影响后面的签名匹配。
            logWarn("内省 " + owner.getName() + " 的 public 方法名失败，改用兜底候选名：" + ex);
        }
        return names;
    }

    public static boolean isAvailable() {
        return init();
    }

    public static String getUnavailableReason() {
        init();
        return unavailableReason == null ? "" : unavailableReason;
    }

    /** 是否已经注册过该 id 的变体（原版表为准）；引擎对未知 id 返回 false，不抛异常。 */
    public static boolean isVariantRegistered(String variantId) {
        if (variantId == null || Global.getSettings() == null) {
            return false;
        }
        return Global.getSettings().doesVariantExist(variantId);
    }

    /** 是否已经注册过该 id 的联队（原版表为准）；引擎 getFighterWingSpec 对未知 id 返回 null，不抛异常。 */
    public static boolean isWingRegistered(String wingId) {
        if (wingId == null || Global.getSettings() == null) {
            return false;
        }
        return Global.getSettings().getFighterWingSpec(wingId) != null;
    }

    /** 把一个新建战机变体写入全局表；返回 true 表示写入成功或该 id 已存在。 */
    public static boolean registerVariant(ShipVariantAPI variant) {
        if (backend != null) return backend.registerVariant(variant);
        if (!init() || variant == null) {
            return false;
        }
        if (variantSpecClass != null && !variantSpecClass.isInstance(variant)) {
            logWarn("变体实现类不是 HullVariantSpec，无法注册：" + variant.getClass().getName());
            return false;
        }

        String variantId = variant.getHullVariantId();
        if (isVariantRegistered(variantId)) {
            return true;
        }

        try {
            if (variantPutTakesFlag) {
                variantPut.invokeWithArguments(variant, Boolean.FALSE);
            } else {
                variantPut.invokeWithArguments(variant);
            }
            return true;
        } catch (Throwable t) {
            // 句柄调用在编译期声明 throws Throwable，执行的是引擎内部的注册表代码；
            // 重名已在上面排除，其余失败只能按注册失败处理，由服务阻止提交。
            logWarn("注册战机变体失败：" + variantId + " -> " + t);
            return false;
        }
    }

    /** 把一个新建的联队规格写入全局表。 */
    public static boolean registerWing(String wingId, FighterWingSpecAPI spec) {
        if (backend != null) return backend.registerWing(wingId, spec);
        if (!init() || wingId == null || spec == null) {
            return false;
        }
        if (isWingRegistered(wingId)) {
            return true;
        }

        try {
            wingPut.invokeWithArguments(wingId, spec);
        } catch (Throwable t) {
            // 同 registerVariant：句柄调用声明 throws Throwable，重名已排除，其余失败按注册失败处理。
            logWarn("注册联队规格失败：" + wingId + " -> " + t);
            return false;
        }
        // 运行期新增的联队不在 SCVE 的应用启动快照里，不同步会导致它读档时 addAll(null) 崩溃。
        // 兼容桥自己拦截全部异常并降级，不会让已经成功的注册被判为失败。
        Moci_ScveSnapshotBridge.noteWingRegistered(spec);
        return true;
    }

    /** 构造空联队规格：混淆类有 public 无参构造（方法句柄调用），其余字段走 FighterWingSpecAPI setter。 */
    public static FighterWingSpecAPI createWingSpec() {
        if (backend != null) return backend.createWing();
        if (!init()) {
            return null;
        }

        Object instance;
        try {
            instance = wingSpecConstructor.invokeWithArguments();
        } catch (Throwable t) {
            // 句柄调用声明 throws Throwable，执行的是引擎类的构造器；失败时不构造，由服务阻止提交。
            logWarn("构造联队规格失败：" + t);
            return null;
        }
        if (instance instanceof FighterWingSpecAPI) {
            return (FighterWingSpecAPI) instance;
        }
        logWarn("构造出的对象不是 FighterWingSpecAPI：" + instance);
        return null;
    }

    /** 继承原型中 API 无 setter 的三个字段；只有全部复制成功才返回 true。 */
    public static boolean inheritUnsettableWingFields(FighterWingSpecAPI target, FighterWingSpecAPI source) {
        if (backend != null) return backend.inheritWingFields(target, source);
        if (target == null || source == null || !initWingFieldHandles()) {
            return false;
        }
        // 句柄绑定的是混淆实现类，先确认两边都是它的实例，句柄调用就不会因类型不符而失败。
        if (!wingSpecImplClass.isInstance(target) || !wingSpecImplClass.isInstance(source)) {
            logWarn("联队规格实现类不是 FighterWingSpec，无法继承私有字段：" + target.getClass().getName()
                    + " / " + source.getClass().getName());
            return false;
        }

        try {
            Object priority = autofitPriorityGetter.invokeWithArguments(source);
            if (priority != null && !(priority instanceof List)) {
                logWarn("原型联队的 autofitPriorityCategories 不是列表：" + priority.getClass().getName());
                return false;
            }
            List<String> priorityCopy = null;
            if (priority != null) {
                priorityCopy = new ArrayList<String>();
                for (Object item : (List<?>) priority) {
                    if (item != null) {
                        priorityCopy.add(String.valueOf(item));
                    }
                }
            }
            autofitCategorySetter.invokeWithArguments(target, autofitCategoryGetter.invokeWithArguments(source));
            autofitPrioritySetter.invokeWithArguments(target, (Object) priorityCopy);
            sourceModSetter.invokeWithArguments(target, sourceModGetter.invokeWithArguments(source));
            return true;
        } catch (Throwable t) {
            // 句柄调用在编译期声明 throws Throwable；类型已在上面校验，这里只剩引擎内部异常，
            // 按「不能继承」处理，服务会丢弃这份联队规格并阻止提交。
            logWarn("继承联队规格私有字段失败：" + t);
            return false;
        }
    }

    /** 私有字段句柄只探一次；invokeWithArguments 收窄接口到混淆实现类，无需源码引用其类名。 */
    private static synchronized boolean initWingFieldHandles() {
        if (wingFieldHandlesResolved) {
            return wingFieldHandlesAvailable;
        }
        wingFieldHandlesResolved = true;

        try {
            Class<?> wingSpecClass = Class.forName(WING_SPEC_CLASS);
            MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(wingSpecClass,
                    MethodHandles.lookup());
            autofitCategoryGetter = privateLookup.findGetter(wingSpecClass, "autofitCategory",
                    String.class);
            autofitCategorySetter = privateLookup.findSetter(wingSpecClass, "autofitCategory",
                    String.class);
            autofitPriorityGetter = privateLookup.findGetter(wingSpecClass,
                    "autofitPriorityCategories", List.class);
            autofitPrioritySetter = privateLookup.findSetter(wingSpecClass,
                    "autofitPriorityCategories", List.class);
            sourceModGetter = privateLookup.findGetter(wingSpecClass, "sourceMod", ModSpecAPI.class);
            sourceModSetter = privateLookup.findSetter(wingSpecClass, "sourceMod", ModSpecAPI.class);
            wingSpecImplClass = wingSpecClass;
            wingFieldHandlesAvailable = true;
        } catch (ReflectiveOperationException | LinkageError | SecurityException ex) {
            // 私有字段只能靠查找确认是否存在（字段名或类型随游戏版本变化），无法事先验证；
            // 找不到时服务阻止提交。
            wingFieldHandlesAvailable = false;
            logWarn("联队规格私有字段入口不可用，装配提交将被阻止：" + ex);
        }
        return wingFieldHandlesAvailable;
    }

    private static void logWarn(String message) {
        if (Global.getLogger(Moci_FighterLoadoutRegistry.class) != null) {
            Global.getLogger(Moci_FighterLoadoutRegistry.class).warn(LOG_PREFIX + message);
        }
    }
}
