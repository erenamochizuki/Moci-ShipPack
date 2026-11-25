package data.subsystems;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import org.apache.log4j.Logger;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import data.scripts.util.Moci_PilotAura;
import data.scripts.util.Moci_TextLoader;

/**
 * 新人类（Moci_PlayerSkill1）的手动主动能力，做成 MagicLib 子系统。
 *
 * 按键：默认由 MagicLib 从子系统热键池分配（HUD 显示「新人类 [键]」）；
 * 玩家可在 LunaLib 设置「新人类主动键」里指定固定键，0 = 交给 MagicLib。
 * 不探测原版键位占用，冲突由玩家自己改键解决。
 * getOrder 保持默认 0：舰船自带的子系统先拿热键，新人类排在后面。
 *
 * 效果：消耗 300 软幅能，10 秒（本舰时间）内本舰及子模块时流 ×1.5，世界时流 ×1/1.5。
 * 自动新人类（技能监听器的 MociPlayerSkill1_ 修饰）生效时不能开启；
 * 开启期间如果自动新人类触发，本能力立即让位结束，两者不叠加。
 * 开启期间周身有极光（{@link Moci_PilotAura}），强度恒定所以尺寸不变，结束时淡出。
 *
 * 强化人的主动能力是它的弱化版，见 {@link Moci_AugmentedHumanSubsystem}：
 * 通过受保护的构造器传入强度比例（时流加成按比例缩小）和极光样式，其余逻辑共用。
 */
public class Moci_NewtypeSubsystem extends MagicSubsystem {
    private static final Logger LOG = Global.getLogger(Moci_NewtypeSubsystem.class);
    private static final String LOG_PREFIX = "[MOCI-TIME] ";
    /** 常驻排查日志开关（禁止挂到 devMode 上）。 */
    public static final boolean TRACE = true;

    private static final String TEXT_ID = "Moci_PlayerSkill1";
    /** LunaSettings 键位项；0 = 不指定，由 MagicLib 自动分配。 */
    public static final String KEY_SETTING = "Moci_NewtypeActivationKey";

    public static final float ACTIVE_SECONDS = 10f;
    public static final float SOFT_FLUX_COST = 300f;
    public static final float SHIP_TIME_MULT = 1.5f;
    public static final float WORLD_TIME_MULT = 1f / SHIP_TIME_MULT;
    private static final int MAX_MODULES = 128;

    private static final String BRIEF_FALLBACK = String.format(Locale.ROOT,
            Moci_TextLoader.getText("code_Moci_NewtypeSubsystem", "t001", "消耗 %.0f 软幅能：%.0f 秒内本舰时流 ×%.1f，世界时流 ×%.2f"),
            SOFT_FLUX_COST, ACTIVE_SECONDS, SHIP_TIME_MULT, WORLD_TIME_MULT);

    private final String effectId;
    private final String originalId;
    /** 日志里的能力名（新人类 / 强化人）。 */
    private final String label;
    private final float shipTimeMult;
    private final float worldTimeMult;
    private final Moci_PilotAura aura;
    /** 写过时流的本舰/子模块。模块掉出模块树后仍在场，必须按记账释放。 */
    private final Set<ShipAPI> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ShipAPI> scratch = Collections.newSetFromMap(new IdentityHashMap<>());
    private CombatEngineAPI worldEngine;
    private boolean keyWasDown;
    private boolean guardActive;

    public Moci_NewtypeSubsystem(ShipAPI ship) {
        this(ship, "Moci_Newtype_", Moci_TextLoader.getText("code_Moci_NewtypeSubsystem", "t002", "新人类"), 1f, Moci_PilotAura.Style.NEWTYPE);
    }

    /**
     * @param effectIdPrefix 时流修饰 id 的前缀，每种能力必须不同
     * @param label          日志里的能力名
     * @param strength       相对新人类的强度比例：时流加成（{@link #SHIP_TIME_MULT} - 1）按它缩放
     * @param auraStyle      周身极光样式
     */
    protected Moci_NewtypeSubsystem(ShipAPI ship, String effectIdPrefix, String label, float strength,
                                    Moci_PilotAura.Style auraStyle) {
        super(ship);
        this.effectId = effectIdPrefix + ship.getId();
        this.originalId = "MociPlayerSkill1_" + ship.getId();
        this.label = label;
        this.shipTimeMult = scaledShipTimeMult(strength);
        this.worldTimeMult = 1f / this.shipTimeMult;
        this.aura = new Moci_PilotAura(ship, auraStyle);
    }

    /** 按强度比例缩放后的本舰时流倍率：1 + (1.5 - 1) × strength。 */
    public static float scaledShipTimeMult(float strength) {
        return 1f + (SHIP_TIME_MULT - 1f) * Math.max(0f, strength);
    }

    /** 由新人类技能监听器在本舰成为玩家座舰时调用；同一艘船只挂一次。 */
    public static void attach(ShipAPI ship) {
        attachWith(ship, Moci_NewtypeSubsystem::new);
    }

    /**
     * 挂载本类或其子类的子系统。同一艘船上这一族能力只挂一个：
     * 已经有了（不论是新人类还是强化人）就不再挂，两者共用一个按键设置。
     */
    protected static void attachWith(ShipAPI ship, Function<ShipAPI, ? extends Moci_NewtypeSubsystem> factory) {
        if (ship == null || ship.isShuttlePod()) return;
        List<MagicSubsystem> existing = MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
        if (existing != null) {
            for (MagicSubsystem subsystem : existing) {
                if (subsystem instanceof Moci_NewtypeSubsystem) return;
            }
        }
        Moci_NewtypeSubsystem subsystem = factory.apply(ship);
        String keyName = Moci_HotkeySubsystem.configuredKeyName(KEY_SETTING);
        if (keyName != null) subsystem.setKey(keyName);
        MagicSubsystemsManager.addSubsystemToShip(ship, subsystem);
        if (TRACE) {
            LOG.info(LOG_PREFIX + subsystem.label + "子系统已挂载 ship=" + ship.getId() + " key=" + subsystem.getKeyText()
                    + (keyName == null ? "（MagicLib 自动分配）" : "（LunaLib 指定）"));
        }
    }

    @Override public float getBaseInDuration() { return 0f; }
    @Override public float getBaseActiveDuration() { return ACTIVE_SECONDS; }
    @Override public float getBaseOutDuration() { return 0f; }
    @Override public float getBaseCooldownDuration() { return 0f; }
    @Override public float getFluxCostFlatOnActivation() { return SOFT_FLUX_COST; }
    @Override public boolean isHardFluxForActivation() { return false; }

    /** 只给玩家手动用；AI 驾驶时不开。 */
    @Override public boolean shouldActivateAI(float amount) { return false; }

    @Override
    public boolean canActivate() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (!hasCommand(engine)) return false;
        if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) return false;
        if (ship.controlsLocked() || engine.isUIShowingDialog()) return false;
        if (engine.getCombatUI() != null
                && (engine.getCombatUI().isShowingCommandUI() || engine.getCombatUI().isShowingDeploymentDialog())) {
            return false;
        }
        if (originalActive()) return false;
        return finitePositive(engine.getTimeMult().getModifiedValue())
                && finitePositive(ship.getMutableStats().getTimeMult().getModifiedValue());
    }

    /** 只认「新按下」：冷却为 0，长按不会在效果结束后立刻再开一次。 */
    @Override
    public boolean isKeyDown() {
        return super.isKeyDown() && !keyWasDown;
    }

    @Override
    public void onActivate() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null && !guardActive) {
            guardActive = true;
            engine.addPlugin(new RetreatGuard());
        }
        if (TRACE) LOG.info(LOG_PREFIX + label + "开启 ship=" + ship.getId() + " 软幅能+" + SOFT_FLUX_COST);
    }

    /** 正常到时、以及舰船被毁（默认 onShipDeath 会调这里）都走这里释放。 */
    @Override
    public void onFinished() {
        clearEffects();
        if (TRACE) LOG.info(LOG_PREFIX + label + "结束 ship=" + ship.getId());
    }

    @Override
    public void advance(float amount, boolean isPaused) {
        keyWasDown = super.isKeyDown();
        if (isPaused) return;
        if (!isOn()) {
            if (!touched.isEmpty() || worldEngine != null) clearEffects();
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (!hasCommand(engine)) {
            forceStop("玩家换乘");
            return;
        }
        if (originalActive()) {
            forceStop("自动新人类触发，让位");
            return;
        }
        applyEffects(engine);
    }

    private boolean hasCommand(CombatEngineAPI engine) {
        return engine != null && !ship.isShuttlePod() && engine.getPlayerShip() == ship
                && engine.getShipPlayerIsTransferringCommandFrom() == null;
    }

    @Override
    public String getDisplayText() {
        return Moci_TextLoader.getText(TEXT_ID, "name", "新人类");
    }

    @Override
    public String getBriefText() {
        return Moci_TextLoader.getText(TEXT_ID, "subsystem.brief", BRIEF_FALLBACK);
    }

    @Override
    public String getExtraInfoText() {
        if (state == State.READY && originalActive()) {
            return Moci_TextLoader.getText(TEXT_ID, "subsystem.auto_active", "自动N.T.生效中");
        }
        return super.getExtraInfoText();
    }

    /** 开启时显示剩余时间；进入/退出/冷却都是 0 秒，MagicLib 默认算法会除以 0。 */
    @Override
    public float getBarFill() {
        if (state != State.ACTIVE || stateInterval == null) return 0f;
        float total = getActiveDuration();
        if (!(total > 0f)) return 0f;
        float remaining = 1f - stateInterval.getElapsed() / total;
        return Math.max(0f, Math.min(1f, remaining));
    }

    private void applyEffects(CombatEngineAPI engine) {
        scratch.clear();
        collectTree(ship, scratch);
        for (ShipAPI target : scratch) {
            target.getMutableStats().getTimeMult().modifyMult(effectId, shipTimeMult);
        }
        for (ShipAPI old : touched) {
            if (!scratch.contains(old)) old.getMutableStats().getTimeMult().unmodify(effectId);
        }
        touched.clear();
        touched.addAll(scratch);
        scratch.clear();
        if (worldEngine != null && worldEngine != engine) worldEngine.getTimeMult().unmodify(effectId);
        engine.getTimeMult().modifyMult(effectId, worldTimeMult);
        worldEngine = engine;
        // 手动能力开启期间强度恒定，极光保持满尺寸
        aura.update(1f);
    }

    private void clearEffects() {
        aura.fade();
        for (ShipAPI target : touched) {
            try {
                target.getMutableStats().getTimeMult().unmodify(effectId);
            } catch (RuntimeException | LinkageError e) {
                LOG.warn(LOG_PREFIX + label + "：释放舰船时流失败 ship=" + ship.getId(), e);
            }
        }
        touched.clear();
        ship.getMutableStats().getTimeMult().unmodify(effectId);
        if (worldEngine != null) worldEngine.getTimeMult().unmodify(effectId);
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null && engine != worldEngine) engine.getTimeMult().unmodify(effectId);
        worldEngine = null;
    }

    private void forceStop(String reason) {
        clearEffects();
        activeElapsed = false;
        if (state != State.READY) setState(State.READY);
        if (TRACE) LOG.info(LOG_PREFIX + label + "提前结束（" + reason + "） ship=" + ship.getId());
    }

    /** 自动新人类的修饰（技能监听器写在本舰上，1.5 → 1.0 线性回落）还在起作用。 */
    private boolean originalActive() {
        MutableStat.StatMod mod = ship.getMutableStats().getTimeMult().getMultStatMod(originalId);
        return mod != null && Math.abs(mod.getValue() - 1f) > 0.0001f;
    }

    private static void collectTree(ShipAPI root, Set<ShipAPI> out) {
        ArrayDeque<ShipAPI> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty() && out.size() < MAX_MODULES) {
            ShipAPI current = queue.poll();
            if (current == null || !out.add(current)) continue;
            List<ShipAPI> children = current.getChildModulesCopy();
            if (children == null) continue;
            for (ShipAPI child : children) {
                if (child != null && child != current) queue.add(child);
            }
        }
    }

    private static boolean finitePositive(float value) {
        return value > 0f && !Float.isNaN(value) && !Float.isInfinite(value);
    }

    /**
     * 撤离的舰船会被移出 engine.ships，MagicLib 不再推进它的子系统，onFinished 不会到来，
     * 世界时流那一笔就会留到战斗结束。这个守卫只在能力开着时存在，兜住这种情况后自行移除。
     */
    private final class RetreatGuard extends BaseEveryFrameCombatPlugin {
        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null) return;
            boolean gone = !engine.isEntityInPlay(ship);
            boolean dead = !ship.isAlive() || ship.isHulk();
            if (isOn() && !gone && !dead && hasCommand(engine)) return;
            if (isOn()) forceStop(gone ? "舰船离场" : dead ? "舰船被毁" : "玩家换乘");
            guardActive = false;
            engine.removePlugin(this);
        }
    }
}
