package data.subsystems;

import java.util.List;

import org.apache.log4j.Logger;
import org.lazywizard.lazylib.combat.AIUtils;
import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import data.ai.Moci_CombatSquadAIV2;
import data.scripts.combatsquad.Moci_SquadRecallController;
import data.scripts.util.Moci_TextLoader;

/**
 * 作战小队队长机子系统「编队集结」。
 *
 * <p>使用后打断全部僚机当前的行动并把它们召回到队长机身旁，
 * 僚机到位前获得最大速度与机动性加成。冷却 60 秒。</p>
 *
 * 按键：默认由 MagicLib 从子系统热键池分配；玩家可在 LunaLib「编队集结键」里指定固定键，0 = 交给 MagicLib。
 * 召回的执行与清理在 {@link Moci_SquadRecallController}。
 */
public class Moci_SquadRecallSubsystem extends MagicSubsystem {
    private static final Logger LOG = Global.getLogger(Moci_SquadRecallSubsystem.class);
    private static final String LOG_PREFIX = "[CombatSquad召回] ";
    /** 常驻排查日志开关（禁止挂到 devMode 上）。 */
    public static final boolean TRACE = true;

    private static final String TEXT_ID = "Moci_SquadRecallSubsystem";
    /** LunaSettings 键位项；0 = 不指定，由 MagicLib 自动分配。 */
    public static final String KEY_SETTING = "Moci_SquadRecallKey";

    public static final float COOLDOWN_SECONDS = 60f;
    /** 激活本身是瞬发的，这段时间只用于 HUD 上显示一下「已使用」。 */
    private static final float ACTIVE_SECONDS = 0.25f;

    // AI 队长的使用判断
    private static final float AI_FAR_WINGMAN_DISTANCE = Moci_CombatSquadAIV2.getEscortRangeCombat();
    private static final float AI_DANGER_ENEMY_RANGE = 1500f;
    private static final float AI_DANGER_WINGMAN_DISTANCE = 800f;
    private static final float AI_DANGER_HULL_LEVEL = 0.5f;
    private static final float AI_DANGER_FLUX_LEVEL = 0.8f;

    private final IntervalUtil aiInterval = new IntervalUtil(0.8f, 1.2f);

    public Moci_SquadRecallSubsystem(ShipAPI ship) {
        super(ship);
    }

    /** 给队长机挂上子系统；同一艘船只挂一次。 */
    public static void attach(ShipAPI ship) {
        if (ship == null) return;
        List<MagicSubsystem> existing = MagicSubsystemsManager.getSubsystemsForShipCopy(ship);
        if (existing != null) {
            for (MagicSubsystem subsystem : existing) {
                if (subsystem instanceof Moci_SquadRecallSubsystem) return;
            }
        }
        Moci_SquadRecallSubsystem subsystem = new Moci_SquadRecallSubsystem(ship);
        String keyName = Moci_HotkeySubsystem.configuredKeyName(KEY_SETTING);
        if (keyName != null) subsystem.setKey(keyName);
        MagicSubsystemsManager.addSubsystemToShip(ship, subsystem);
        if (TRACE) {
            LOG.info(LOG_PREFIX + "编队集结子系统已挂载 ship=" + ship.getId() + " key=" + subsystem.getKeyText()
                    + (keyName == null ? "（MagicLib 自动分配）" : "（LunaLib 指定）"));
        }
    }

    @Override public float getBaseInDuration() { return 0f; }
    @Override public float getBaseActiveDuration() { return ACTIVE_SECONDS; }
    @Override public float getBaseOutDuration() { return 0f; }
    @Override public float getBaseCooldownDuration() { return COOLDOWN_SECONDS; }

    /** 没有可召回的僚机时不可用，也不会白白进入冷却。 */
    @Override
    public boolean canActivate() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || !ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) return false;
        return Moci_SquadRecallController.countRecallable(ship) > 0;
    }

    @Override
    public void onActivate() {
        int started = Moci_SquadRecallController.recall(ship);
        if (TRACE) LOG.info(LOG_PREFIX + "编队集结启动 ship=" + ship.getId() + " 召回=" + started);
    }

    /**
     * AI 队长的使用规则：有僚机跑得太远，或自己处境危险而僚机不在身边时召回。
     * 判断带节流，不是每帧都扫全场。
     */
    @Override
    public boolean shouldActivateAI(float amount) {
        aiInterval.advance(amount);
        if (!aiInterval.intervalElapsed()) return false;
        if (state != State.READY) return false;

        float farthest = 0f;
        for (ShipAPI wingman : Moci_SquadRecallController.getWingmenOf(ship)) {
            if (!Moci_SquadRecallController.isRecallable(wingman)) continue;
            farthest = Math.max(farthest, Misc.getDistance(ship.getLocation(), wingman.getLocation()));
        }
        if (farthest <= 0f) return false;
        if (farthest >= AI_FAR_WINGMAN_DISTANCE) return true;

        boolean inDanger = ship.getHullLevel() < AI_DANGER_HULL_LEVEL || ship.getFluxLevel() > AI_DANGER_FLUX_LEVEL;
        if (!inDanger || farthest < AI_DANGER_WINGMAN_DISTANCE) return false;
        ShipAPI nearestEnemy = AIUtils.getNearestEnemy(ship);
        return nearestEnemy != null
                && Misc.getDistance(ship.getLocation(), nearestEnemy.getLocation()) <= AI_DANGER_ENEMY_RANGE;
    }

    @Override
    public String getDisplayText() {
        return Moci_TextLoader.getText(TEXT_ID, "display", "编队集结");
    }

    @Override
    public String getBriefText() {
        return Moci_TextLoader.getText(TEXT_ID, "brief", "召回全部僚机至本舰身旁");
    }

    @Override
    public String getExtraInfoText() {
        int recalling = Moci_SquadRecallController.countRecalling(ship);
        if (recalling > 0) {
            return String.format(Moci_TextLoader.getText(TEXT_ID, "extra.recalling", "集结中 %s 架"),
                    String.valueOf(recalling));
        }
        if (state == State.READY && Moci_SquadRecallController.countRecallable(ship) <= 0) {
            return Moci_TextLoader.getText(TEXT_ID, "extra.no_wingman", "无可召回僚机");
        }
        return super.getExtraInfoText();
    }
}
