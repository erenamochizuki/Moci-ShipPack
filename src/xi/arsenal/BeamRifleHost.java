package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import data.scripts.combat.Moci_CombatBus;
import data.scripts.combat.Moci_CombatSink;

import java.util.List;

/**
 * 光束步枪模式的所有者：由步枪武器自己的每帧效果惰性创建，取代原来全局扫描全场的战斗插件。
 *
 * <p>每艘船一份，挂在舰船 customData 上，随舰船销毁或战斗结束自行注销。</p>
 */
public final class BeamRifleHost implements Moci_CombatSink {

    private static final String KEY = "xi.arsenal.rifle.host";
    private static final String UNAVAILABLE = KEY + ".unavailable";

    private final ShipAPI ship;
    private final CombatEngineAPI engine;
    private BeamRifleModes modes;
    private boolean closed;

    private BeamRifleHost(ShipAPI ship, CombatEngineAPI engine) {
        this.ship = ship;
        this.engine = engine;
    }

    /**
     * 幂等创建：装配不满足（缺蓄力步枪）时返回 null 并记标记，避免每帧重复抛异常。
     * 所有权契约：只有本地驾驶机体（或显式远程接管者）才装配 xi 步枪控制器；
     * AI 载机在这里返回 null，保留原生武器语义（不建 host、不注册 sink、不接管禁火/姿态）。
     */
    public static BeamRifleHost ensure(ShipAPI ship) {
        if (Global.getCurrentState() != com.fs.starfarer.api.GameState.COMBAT) return null;
        if (ship == null) return null;
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused() || engine.isCombatOver()) return null;
        Object existing = ship.getCustomData().get(KEY);
        if (existing instanceof BeamRifleHost host) return host.closed ? null : host;
        if (Boolean.TRUE.equals(ship.getCustomData().get(UNAVAILABLE))) return null;
        // P1 修复点 1：装配门槛只问「装没装齐」；这条船该走哪套语义由控制器自己决定
        // （本地驾驶机体 = xi 语义；AI 载机 = 只做禁火/放行的原生语义）。
        if (!BeamRifleModes.hostEligible(ship)) return null;
        BeamRifleHost created = new BeamRifleHost(ship, engine);
        try {
            created.modes = new BeamRifleModes(ship);
        } catch (RuntimeException | LinkageError failure) {
            ship.getCustomData().put(UNAVAILABLE, Boolean.TRUE);
            if (BeamRifleModes.TRACE_OWNERSHIP)
                Global.getLogger(BeamRifleHost.class).warn("[MOCI-RIFLE] host install failed for "
                        + ship.getName() + " (" + ship.getHullSpec().getHullId() + "): " + failure, failure);
            return null;
        }
        ship.getCustomData().put(KEY, created);
        Moci_CombatBus.register(created);
        return created;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (closed || modes == null) return;
        if (Global.getCombatEngine() != engine || !ship.isAlive() || ship.isHulk()
                || !engine.isEntityInPlay(ship) || engine.isCombatOver()) {
            close();
            return;
        }
        // 所有权契约：控制权相对建号时翻转（玩家换乘走 / 远程接管失效）就立刻卸载，
        // 下一帧由武器回调按新语义重建控制器 —— 旧舰不会留着上一套状态机继续否决原生开火。
        boolean owned = BeamRifleModes.controlOwned(ship, engine);
        if (modes.aiMode() == owned) {
            close();
            return;
        }
        float time = ship.getMutableStats() == null ? 1f : ship.getMutableStats().getTimeMult().getModifiedValue();
        modes.advance(amount * (Float.isFinite(time) ? time : 1f));
    }

    @Override
    public void processInput(float amount, List<InputEventAPI> events) {
        if (closed || modes == null || events == null) return;
        modes.processInput(events);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Moci_CombatBus.unregister(this);
        if (modes != null) {
            try {
                modes.close();
            } catch (RuntimeException | LinkageError ignored) {
                // 武器已被回收时关闭可能失败，忽略即可。
            }
            modes = null;
        }
        if (ship.getCustomData().get(KEY) == this) ship.getCustomData().remove(KEY);
    }
}
