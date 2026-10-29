package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import data.scripts.combat.Moci_CombatBus;
import data.scripts.combat.Moci_CombatSink;
import java.util.Objects;

/** 夜莺翼浮游炮「自有 AI」的宿主：谁发射浮游炮，谁负责把 coordinator 装上。
 *
 *  <p>上游把 NightFunnelCoordinator 的创建放在 UnicornNtdEnhancement（独角兽 NTD 叠加层）里，
 *  但 coordinator 的筛选条件其实是 {@link PsychoframeEnhancement#supports}（hull 仅
 *  Moci_MSN_04_2 / Moci_RX_93_v2），与独角兽无关，所以那条路径上它从未真正装上过。
 *  现改为由翼浮游炮发射器每帧 {@link #ensure(CombatEngineAPI)} 驱动，不依赖任何船插。 */
public final class NightFunnelHost implements Moci_CombatSink {
    static final String OWNER_KEY = NightFunnelCoordinator.KEY + ".host";
    private final CombatEngineAPI engine;
    private final NightFunnelCoordinator coordinator;
    private boolean closed;
    private NightFunnelHost(CombatEngineAPI engine) {
        this.engine = Objects.requireNonNull(engine);
        this.coordinator = new NightFunnelCoordinator(engine);
    }
    /** 幂等安装：同一场战斗只有一个宿主；非战斗上下文直接跳过。 */
    public static NightFunnelHost ensure(CombatEngineAPI engine) {
        if (engine == null) return null;
        if (Global.getCurrentState() != com.fs.starfarer.api.GameState.COMBAT) return null;
        if (Global.getCombatEngine() != engine) return null;
        Object existing = engine.getCustomData().get(OWNER_KEY);
        if (existing instanceof NightFunnelHost host) return host;
        try {
            NightFunnelHost host = new NightFunnelHost(engine);
            engine.getCustomData().put(OWNER_KEY, host);
            Moci_CombatBus.register(host);
            return host;
        } catch (RuntimeException | LinkageError failure) {
            Global.getLogger(NightFunnelHost.class).warn("Night funnel host unavailable: " + failure, failure);
            return null;
        }
    }
    @Override public void advance(float amount, java.util.List<com.fs.starfarer.api.input.InputEventAPI> events) {
        if (closed) return;
        if (Global.getCombatEngine() != engine || engine.isCombatOver()) { close(); return; }
        coordinator.advance(amount);
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try { Moci_CombatBus.unregister(this); } catch (RuntimeException ignored) { }
        try { coordinator.close(); } catch (RuntimeException | LinkageError ignored) { }
        engine.getCustomData().remove(OWNER_KEY);
    }
}
