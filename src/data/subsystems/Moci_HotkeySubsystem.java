package data.subsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import java.util.List;
import java.util.function.Function;
import org.apache.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

/**
 * 只占一个 MagicLib 子系统热键的「键位托管」子系统。
 *
 * 按键怎么来：LunaLib 里对应的 Keycode 项为 0（默认）时，由 MagicLib 从子系统热键池
 * （L-ALT / B / H / J / N，可在 MagicLib 的 LunaLib 设置里改）里自动分配；玩家填了键就用那个键。
 * 子系统之间的撞键由 MagicLib 自己避开；和原版键位撞了由玩家自己改键。
 *
 * 按下之后做什么：MagicLib 不会触发它（canActivate 恒为 false）。功能自己照旧处理输入事件
 * （按下 / 松开 / 长按 / 冷却 / 条件判断 / consume），只通过 {@link #binding()} 拿当前键位。
 * 子系统栏显示「名称 [键]」和功能用 {@link #setStatus(String)} 推送的状态文字。
 *
 * MagicSubsystemsManager 按类登记子系统，所以每个功能要有自己的子类。
 */
public abstract class Moci_HotkeySubsystem extends MagicSubsystem {
    private static final Logger LOG = Global.getLogger(Moci_HotkeySubsystem.class);
    private static final String LOG_PREFIX = "[MOCI-KEY] ";
    /** 常驻排查日志开关（禁止挂到 devMode 上）。 */
    public static final boolean TRACE = true;
    private static final String MOD_ID = "moci_shippack";

    /** 键位查询结果：keyCode ≤ 0 表示当前没有可用键，error 说明原因。 */
    public record Binding(int keyCode, String keyName, String error) {
        public Binding {
            if (keyName == null) keyName = "未绑定";
            if (error == null) error = "";
        }

        public static Binding none(String reason) {
            return new Binding(Keyboard.KEY_NONE, "未绑定", reason);
        }

        public boolean available() {
            return keyCode > 0;
        }
    }

    private String status = "";
    private float fill = 1f;

    protected Moci_HotkeySubsystem(ShipAPI ship) {
        super(ship);
    }

    /** LunaSettings 里的 Keycode 项 id；0 = 交给 MagicLib 分配。 */
    protected abstract String keySettingId();

    /** 没有推送状态时显示的操作提示，按住 MagicLib 信息键时也显示它。 */
    protected abstract String hintText();

    @Override public float getBaseActiveDuration() { return 0f; }
    @Override public float getBaseCooldownDuration() { return 0f; }
    @Override public boolean canAssignKey() { return true; }
    @Override public boolean shouldActivateAI(float amount) { return false; }
    /** 按键由功能自己处理，MagicLib 永远不触发这个子系统。 */
    @Override public boolean canActivate() { return false; }

    @Override
    public String getStateText() {
        // 热键池用完时 MagicLib 不给键，提示玩家去 LunaLib 指定。
        if (getAssignedKey() <= 0) return "未分配键，去 LunaLib 设置";
        return status.isEmpty() ? hintText() : status;
    }

    @Override
    public String getBriefText() {
        return hintText();
    }

    /** 进度条：默认满格，冷却等由功能通过 {@link #setStatus(String, float)} 推送。 */
    @Override
    public float getBarFill() {
        return fill;
    }

    /** 功能每帧推送的状态文字（如冷却、剩余弹匣）；空串时显示操作提示。进度条满格。 */
    public void setStatus(String text) {
        setStatus(text, 1f);
    }

    /** 同上，并给出进度条 0~1（冷却进度、弹匣余量等）。 */
    public void setStatus(String text, float barFill) {
        status = text == null ? "" : text;
        fill = Float.isFinite(barFill) ? Math.max(0f, Math.min(1f, barFill)) : 1f;
    }

    /** 当前分配到的键；没有分配到（热键池用完）时给出改键提示。 */
    public Binding binding() {
        int code = getAssignedKey();
        if (code > 0) return new Binding(code, getKeyText(), "");
        return Binding.none("没有分到按键，请在 LunaLib → Moci 里给「" + getDisplayText() + "」设置按键");
    }

    /** 查某艘船上某个托管子系统的当前键位。 */
    public static Binding binding(ShipAPI ship, Class<? extends Moci_HotkeySubsystem> type) {
        Moci_HotkeySubsystem holder = find(ship, type);
        return holder == null ? Binding.none("这台机体没有挂载对应的按键") : holder.binding();
    }

    public static <T extends Moci_HotkeySubsystem> T find(ShipAPI ship, Class<T> type) {
        if (ship == null) return null;
        List<MagicSubsystem> subsystems = MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
        if (subsystems == null) return null;
        for (MagicSubsystem subsystem : subsystems) {
            if (type.isInstance(subsystem)) return type.cast(subsystem);
        }
        return null;
    }

    /**
     * 给舰船挂上托管子系统（同一艘船同一类只挂一次）并返回它；失败时记日志并返回 null，
     * 调用方按「没有键」处理，不影响功能本身的创建。
     */
    public static <T extends Moci_HotkeySubsystem> T attach(ShipAPI ship, Class<T> type, Function<ShipAPI, T> factory) {
        if (ship == null) return null;
        try {
            T existing = find(ship, type);
            if (existing != null) return existing;
            T holder = factory.apply(ship);
            String keyName = configuredKeyName(holder.keySettingId());
            if (keyName != null) holder.setKey(keyName);
            MagicSubsystemsManager.addSubsystemToShip(ship, holder);
            if (TRACE) {
                LOG.info(LOG_PREFIX + holder.getDisplayText() + " 已挂载 ship=" + ship.getId() + " key=" + holder.getKeyText()
                        + (keyName == null ? "（MagicLib 自动分配）" : "（LunaLib 指定）"));
            }
            return holder;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn(LOG_PREFIX + "按键子系统挂载失败 " + type.getSimpleName() + " ship=" + ship.getId(), e);
            return null;
        }
    }

    /** 读 LunaLib 的 Keycode 项；没装 LunaLib、未设置（0）或键码无效时返回 null，交给 MagicLib。 */
    public static String configuredKeyName(String settingId) {
        int code = 0;
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                Integer value = lunalib.lunaSettings.LunaSettings.getInt(MOD_ID, settingId);
                if (value != null) code = value;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 读取失败按未设置处理
        }
        if (code <= 0) return null;
        try {
            String name = Keyboard.getKeyName(code);
            return name == null || name.isEmpty() ? null : name;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
