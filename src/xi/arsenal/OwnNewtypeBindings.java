package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.input.Keyboard;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

/** Read-only letter-key conflict probe. Never remaps, polls or writes native controls. */
public final class OwnNewtypeBindings {
    /** Optional extension bridge: Map<Integer,String> of key codes reserved by another project/Mod. */
    public static final String RESERVATIONS_KEY = "xi.arsenal.input.reservations";
    private static final String NATIVE_SEGMENT = "Oo" + "O".repeat(254);
    private static final String NATIVE_BINDINGS = "com.fs.starfarer.title." + NATIVE_SEGMENT + "." + NATIVE_SEGMENT;
    public static final int PREFERRED_KEY = Keyboard.KEY_B;
    private static final int[] LETTERS = {Keyboard.KEY_B, Keyboard.KEY_N, Keyboard.KEY_J, Keyboard.KEY_K,
            Keyboard.KEY_L, Keyboard.KEY_H, Keyboard.KEY_O, Keyboard.KEY_P, Keyboard.KEY_U, Keyboard.KEY_I,
            Keyboard.KEY_Y, Keyboard.KEY_T, Keyboard.KEY_G, Keyboard.KEY_C, Keyboard.KEY_X, Keyboard.KEY_Z,
            Keyboard.KEY_V, Keyboard.KEY_R, Keyboard.KEY_E, Keyboard.KEY_Q, Keyboard.KEY_W, Keyboard.KEY_A,
            Keyboard.KEY_S, Keyboard.KEY_D, Keyboard.KEY_F, Keyboard.KEY_M};
    private OwnNewtypeBindings() { }

    public record Result(boolean inspected, List<String> conflicts, String error, int keyCode) {
        public Result(boolean inspected, List<String> conflicts, String error) { this(inspected, conflicts, error, Keyboard.KEY_NONE); }
        public boolean available() { return inspected && conflicts.isEmpty() && isLetter(keyCode); }
        public String keyName() { return isLetter(keyCode) ? Keyboard.getKeyName(keyCode) : "未分配"; }
    }

    public static Result inspect(CombatEngineAPI engine, ShipAPI player) {
        return select(engine, player, Keyboard.KEY_NONE);
    }

    /** Keep an available current letter stable; only then prefer B or another unused letter. */
    public static int chooseLetter(int current, Set<Integer> occupied) {
        if (isLetter(current) && !occupied.contains(current)) return current;
        for (int key : LETTERS) if (!occupied.contains(key)) return key;
        return Keyboard.KEY_NONE;
    }
    private static boolean isLetter(int key) { for (int letter : LETTERS) if (letter == key) return true; return false; }

    public static Result select(CombatEngineAPI engine, ShipAPI player, int current) {
        return select(engine, player, current, Map.of());
    }
    /** Additional confirmed content controls, for hooks not reported by the native/MagicLib key registry. */
    public static Result select(CombatEngineAPI engine, ShipAPI player, int current, Map<Integer,String> extraControls) {
        java.util.Map<Integer, List<String>> occupied = new java.util.LinkedHashMap<>();
        try {
            // Exact names from the retained native binding implementation/NativeControls, not a method scan.
                        Class<?> bindingsType = Class.forName(NATIVE_BINDINGS);
            MethodHandle bindingsGetter = MethodHandles.publicLookup()
                    .findStatic(bindingsType, "Ø00000", MethodType.methodType(Object.class));
            Object value = invokeHandle(bindingsGetter);
            if (!(value instanceof Map<?, ?> bindings) || bindings.isEmpty() || bindings.size() > 2048)
                return new Result(false, List.of(), "原生键位表尚未就绪，未接管字母键");
            int assigned = 0;
            for (Map.Entry<?, ?> action : bindings.entrySet()) {
                Object pair = action.getValue();
                if (pair == null) continue;
                for (String field : new String[]{"one", "two"}) {
                    MethodHandle fieldGetter = MethodHandles.publicLookup()
                            .findGetter(pair.getClass(), field, Object.class);
                    Object binding = invokeHandle(fieldGetter, pair);
                    if (binding == null) continue;
                    int key = ((Number) plainValue(binding.getClass(), binding, "class")).intValue();
                    if (key > Keyboard.KEY_NONE && key < Keyboard.KEYBOARD_SIZE) assigned++;
                    if (isLetter(key) && activeBattleAction(action.getKey())) reserve(occupied,key,"原生操作 " + action.getKey());
                }
            }
            if (assigned == 0) return new Result(false, List.of(), "原生键位未初始化，未接管字母键");
            reserve(occupied,MagicSubsystemsManager.INSTANCE.getInfoHotkey(),"子系统信息显示");
            List<Integer> reserved = MagicSubsystemsManager.INSTANCE.getHotkeyList();
            if (reserved != null) for (Integer key : reserved) if (key != null) reserve(occupied,key,"MagicLib 子系统预留键位");
            List<MagicSubsystem> subsystems = MagicSubsystemsManager.getSubsystemsForShipCopy(player);
            if (subsystems != null) for (MagicSubsystem subsystem : subsystems)
                if (subsystem != null) reserve(occupied,subsystem.getAssignedKey(),"已装子系统 " + subsystem.getClass().getSimpleName());
            Object registered = engine.getCustomData().get(RESERVATIONS_KEY);
            if (registered instanceof Map<?, ?> map) {
                if (map.size() > 2048) return new Result(false,List.of(),"扩展键位登记超过上限，未接管");
                for (var entry : map.entrySet()) if (entry.getKey() instanceof Integer key) reserve(occupied,key,"扩展操作 " + entry.getValue());
            }
            if(extraControls!=null)for(var entry:extraControls.entrySet())if(entry.getKey()!=null)reserve(occupied,entry.getKey(),entry.getValue());
            int chosen=chooseLetter(current,occupied.keySet());
            if (chosen != Keyboard.KEY_NONE) return new Result(true,List.of(),"",chosen);
            List<String> conflicts=new ArrayList<>();
            for (int key : LETTERS) conflicts.add(Keyboard.getKeyName(key) + "：" + String.join("、",occupied.get(key)));
            return new Result(true,List.copyOf(conflicts),"没有空闲字母键");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return new Result(false, List.of(), "无法核实字母键占用（" + error.getClass().getSimpleName() + "），未接管");
        }
    }
    private static void reserve(Map<Integer,List<String>> occupied,int key,String reason) {
        if(!isLetter(key))return;
        List<String> reasons=occupied.computeIfAbsent(key,k->new ArrayList<>());
        if(reasons.size()<8)reasons.add(reason);
    }

    /** 统一把 MethodHandle 调用里的 Throwable 归一成 ReflectiveOperationException，供上层按原逻辑降级。 */
    private static Object invokeHandle(MethodHandle handle, Object... arguments) throws ReflectiveOperationException {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable throwable) {
            throw new ReflectiveOperationException(throwable);
        }
    }

    /** 以 java.lang.invoke 调用无参方法：脚本类加载器禁 java.lang.reflect，但允许 java.lang.invoke。 */
    private static Object plainValue(Class<?> type, Object receiver, String name) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle handle;
        try {
            handle = lookup.findVirtual(type, name, MethodType.methodType(Object.class));
        } catch (NoSuchMethodException primitiveReturn) {
            handle = lookup.findVirtual(type, name, MethodType.methodType(int.class));
        }
        Object value;
        try {
            value = handle.invoke(receiver);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable throwable) {
            throw new ReflectiveOperationException(throwable);
        }
        return value instanceof Number number ? number.intValue() : value;
    }

    private static boolean activeBattleAction(Object action) throws ReflectiveOperationException {
        if (!(action instanceof Enum<?> nativeAction)) throw new IllegalStateException("Unknown native action metadata");
        // Exact public native metadata getter: action.new() returns EnumSet<Bindings.O0>.
        Object categories = plainValue(action.getClass(), action, "new");
        if (!(categories instanceof Set<?> contexts) || contexts.isEmpty()) throw new IllegalStateException("Missing native action context");
        Set<String> known = Set.of("GENERAL", "FLIGHT", "C2", "C2_WAYPOINT", "C2_FRIENDLY", "C2_HOSTILE", "CAMPAIGN", "UNCONFIGURABLE");
        Set<String> names = new java.util.HashSet<>();
        for (Object context : contexts) {
            if (!(context instanceof Enum<?> value) || !known.contains(value.name())) throw new IllegalStateException("Unknown native action context");
            names.add(value.name());
        }
        if (names.contains("GENERAL") || names.contains("FLIGHT")) return true;
        // Command/deployment UI is rejected by the caller. Its C2 actions cannot fire in open ship control.
        if (!names.contains("UNCONFIGURABLE")) return false;
        // The native action metadata groups refit shortcuts as UNCONFIGURABLE, not FLIGHT.
        // Preserve unknown unconfigurable actions as conflicts; exclude only the named native refit family.
        String name = nativeAction.name();
        return !(names.size() == 1 && (name.startsWith("REFIT_") || name.equals("MISSION_REFIT")));
    }
}
