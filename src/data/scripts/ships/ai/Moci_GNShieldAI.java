package data.scripts.ships.ai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.combat.ai.BasicShipAI;
import com.fs.starfarer.combat.entities.Ship;
import data.hullmods.Moci_GNShield_Script;
import data.scripts.Moci_FilmShieldIntegration;
import org.apache.log4j.Logger;

/**
 * GN 贴膜盾舰船的"固定全向盾"感知 AI。
 *
 * 贴膜盾把原生护盾弧压到接近 0°，原生盾只作开关源，实际由贴膜盾 360° 吸收伤害。
 * 原版 BasicShipAI 只读原生护盾弧，会把舰船当成几乎没有护盾：装甲硬抗判断只扫正前方 20°，
 * 机动要求威胁正对船头，侧翼与碰撞规避判断也不认护盾。
 *
 * 本类只在自身 super.advance() 期间把原生护盾弧临时设为 360°，结束后立即还原。
 * 引擎每帧先推进全部 AI，再推进实体与碰撞，所以敌方 AI、伤害结算与碰撞仍看到真实弧度。
 *
 * 实验功能：受 LunaSettings 开关 Moci_gnShieldOmniPerceptionAI 控制，默认关闭。
 * 关闭时既不替换 AI 也不改动护盾弧度，行为与未加该功能时完全一致。
 */
public class Moci_GNShieldAI extends BasicShipAI {
    private static final Logger LOG = Global.getLogger(Moci_GNShieldAI.class);
    private static final String LOG_PREFIX = "[Moci_GNShieldAI] ";
    private static final String GUARD_KEY = "Moci_GNShieldAI_SwapGuard";
    private static final float GUARD_WINDOW = 1f;
    private static final int GUARD_MAX_SWAPS = 5;

    private final Perception perception;

    public Moci_GNShieldAI(ShipAPI ship, ShipAIConfig config) {
        super((Ship) ship, config);
        perception = new Perception(ship, "GNShieldAI");
    }

    @Override
    public void advance(float amount) {
        perception.open();
        try {
            super.advance(amount);
        } finally {
            perception.close();
        }
    }

    /**
     * 由 GN 贴膜盾控制器每帧调用。部署、自动驾驶、resetDefaultAI、createDefaultShipAI
     * 装上的都是原版 BasicShipAI，这里统一在下一帧换成本类。
     * 只替换类型恰好为 BasicShipAI 的 AI：玩家手控(null)、其他自定义 AI、包装 AI、
     * 战机与无人机(浮游炮控制器依赖精确类型判断)一律不动。
     */
    public static void ensureInstalled(ShipAPI ship) {
        if (!Moci_FilmShieldIntegration.omniPerceptionAIEnabled()) return;
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused() || ship == null) return;
        if (!ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isDrone()) return;
        ShipAIPlugin current = ship.getShipAI();
        if (current == null || current.getClass() != BasicShipAI.class) return;
        if (!Perception.supports(ship.getShield()) || !Moci_GNShield_Script.hasShield(ship)) return;
        if (!SwapGuard.allow(ship, engine)) return;
        ShipAIConfig config = current.getConfig() != null ? current.getConfig() : new ShipAIConfig();
        Moci_GNShieldAI ai = new Moci_GNShieldAI(ship, config);
        ship.setShipAI(ai);
        ai.forceCircumstanceEvaluation();
        LOG.info(LOG_PREFIX + "installed on " + describe(ship) + ", nativeArc=" + ship.getShield().getArc());
    }

    static String describe(ShipAPI ship) {
        if (ship == null) return "?";
        String hull = ship.getHullSpec() != null ? ship.getHullSpec().getHullId() : "?";
        return hull + "#" + ship.getId() + " owner=" + ship.getOwner();
    }

    /**
     * 单帧感知窗口：open() 与 close() 必须在同一次 AI advance 内成对调用(用 try/finally)。
     * 只对挂着 GN 贴膜盾、原生盾为 FRONT/OMNI、原生弧在 (0, 360) 内的舰船生效。
     * 窗口内同时按弧度比例补偿展开速率，让 AI 读到的展开时间等于真实展开时间(贴膜盾近乎瞬开)。
     */
    public static final class Perception {
        public static final float PERCEIVED_ARC = 360f;
        private static final String UNFOLD_MOD_ID = "Moci_GNShieldAI_perception";
        private final ShipAPI ship;
        private final String owner;
        private ShieldAPI openShield;
        private MutableStat openUnfoldStat;
        private float realArc;
        private boolean logged;

        public Perception(ShipAPI ship, String owner) {
            this.ship = ship;
            this.owner = owner;
        }

        static boolean supports(ShieldAPI shield) {
            return shield != null
                    && (shield.getType() == ShieldAPI.ShieldType.FRONT || shield.getType() == ShieldAPI.ShieldType.OMNI);
        }

        public void open() {
            if (openShield != null || ship == null) return;
            if (!Moci_FilmShieldIntegration.omniPerceptionAIEnabled()) return;
            ShieldAPI shield = ship.getShield();
            if (!supports(shield)) return;
            float arc = shield.getArc();
            if (!(arc > 0f && arc < PERCEIVED_ARC)) return;
            if (!Moci_GNShield_Script.hasShield(ship)) return;
            realArc = arc;
            openShield = shield;
            openUnfoldStat = ship.getMutableStats().getShieldUnfoldRateMult();
            shield.setArc(PERCEIVED_ARC);
            openUnfoldStat.modifyMult(UNFOLD_MOD_ID, PERCEIVED_ARC / arc);
            if (!logged) {
                logged = true;
                LOG.info(LOG_PREFIX + owner + " perception window active on " + describe(ship)
                        + ", realArc=" + arc + " -> " + PERCEIVED_ARC);
            }
        }

        public void close() {
            ShieldAPI shield = openShield;
            if (shield == null) return;
            openShield = null;
            if (openUnfoldStat != null) {
                openUnfoldStat.unmodifyMult(UNFOLD_MOD_ID);
                openUnfoldStat = null;
            }
            // 窗口内若有其他逻辑改过弧度，尊重它，不覆盖。
            if (shield.getArc() == PERCEIVED_ARC) shield.setArc(realArc);
        }
    }

    /** 防止与其他脚本逐帧抢装 AI：1 秒内替换超过 5 次即停止对该舰替换，并只警告一次。正常的自动驾驶开关、整备、停靠切换远达不到该频率。 */
    private static final class SwapGuard {
        float windowStart;
        int swaps;
        boolean disabled;

        static boolean allow(ShipAPI ship, CombatEngineAPI engine) {
            Object raw = ship.getCustomData().get(GUARD_KEY);
            SwapGuard guard;
            if (raw instanceof SwapGuard) {
                guard = (SwapGuard) raw;
            } else {
                guard = new SwapGuard();
                ship.setCustomData(GUARD_KEY, guard);
            }
            if (guard.disabled) return false;
            float now = engine.getTotalElapsedTime(false);
            if (guard.swaps == 0 || now - guard.windowStart > GUARD_WINDOW) {
                guard.windowStart = now;
                guard.swaps = 0;
            }
            guard.swaps++;
            if (guard.swaps > GUARD_MAX_SWAPS) {
                guard.disabled = true;
                LOG.warn(LOG_PREFIX + "AI replaced " + GUARD_MAX_SWAPS + "+ times within " + GUARD_WINDOW
                        + "s on " + describe(ship) + "; another script keeps installing BasicShipAI, swap disabled for this ship");
                return false;
            }
            return true;
        }
    }
}
