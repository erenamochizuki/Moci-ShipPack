package data.scripts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.Misc;

import data.ai.Moci_CombatSquadAIV2;
import data.ai.Moci_SquadRecallAI;
import data.hullmods.Moci_LandingAI;
import data.hullmods.Moci_MobileSuitRepairTracker;
import data.hullmods.Moci_RefitSession;
import data.scripts.util.Moci_AIRestoreUtil;

/**
 * 作战小队「编队集结」：把队长机的僚机召回到身旁。
 *
 * <p>僚机当前使用原版默认 AI，它每帧都会自己下指令，所以要打断任务只能临时换掉 AI：
 * 召回期间由 {@link Moci_SquadRecallAI} 导航，到位、超时或被打断后恢复原 AI。
 * 加成与清理统一在这里按帧推进（由 {@link Moci_CombatSquadCombatPlugin} 驱动），
 * 不依赖队长机还活着。</p>
 */
public final class Moci_SquadRecallController {
    private static final Logger LOG = Global.getLogger(Moci_SquadRecallController.class);
    private static final String LOG_PREFIX = "[CombatSquad召回] ";
    /** 常驻排查日志开关（禁止挂到 devMode 上）。 */
    public static final boolean TRACE = true;

    public static final String COMMANDER_KEY = "Moci_WingmanCommander";
    /** 僚机的部署序号（0 起），用于分配集结站位。 */
    public static final String WINGMAN_INDEX_KEY = "Moci_WingmanIndex";
    private static final String ENTRIES_KEY = "Moci_SquadRecallEntries";
    private static final String BONUS_ID = "moci_squad_recall_bonus";

    /** 召回期间的最大速度加成（%）与机动性加成（%）。 */
    public static final float SPEED_BONUS_PERCENT = 200f;
    public static final float MANEUVER_BONUS_PERCENT = 50f;
    /** 距站位小于该距离视为到位。 */
    private static final float ARRIVE_DISTANCE = 120f;
    /** 距站位小于该距离后速度加成按距离收敛，避免高速冲过头。 */
    private static final float SPEED_TAPER_DISTANCE = 300f;
    private static final float SPEED_TAPER_MIN = 0.2f;
    /** 单次召回的最长持续时间，超时后僚机恢复原有行为。 */
    private static final float TIMEOUT_SECONDS = 20f;

    private Moci_SquadRecallController() {
    }

    private static final class Entry {
        final ShipAPI wingman;
        final ShipAPI commander;
        final Moci_SquadRecallAI recallAI;
        /** 换 AI 之前舰船上实际安装的 AI 对象（可能是引擎的包装对象）。 */
        final ShipAIPlugin savedAI;
        final ShipAIConfig savedConfig;
        /** 换 AI 之后舰船上实际安装的对象，用于判断 AI 是否又被别人换掉。 */
        ShipAIPlugin installedAI;
        final int slotOrder;
        float elapsed;

        Entry(ShipAPI wingman, ShipAPI commander, Moci_SquadRecallAI recallAI,
              ShipAIPlugin savedAI, ShipAIConfig savedConfig, int slotOrder) {
            this.wingman = wingman;
            this.commander = commander;
            this.recallAI = recallAI;
            this.savedAI = savedAI;
            this.savedConfig = savedConfig;
            this.slotOrder = slotOrder;
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 指定队长机名下仍在场的僚机，按部署序号排序。 */
    public static List<ShipAPI> getWingmenOf(ShipAPI commander) {
        List<ShipAPI> result = new ArrayList<>();
        CombatEngineAPI engine = Global.getCombatEngine();
        if (commander == null || engine == null) return result;
        for (ShipAPI ship : engine.getShips()) {
            if (ship == commander || ship.getCustomData() == null) continue;
            if (ship.getCustomData().get(COMMANDER_KEY) != commander) continue;
            if (!ship.isAlive() || ship.isHulk()) continue;
            result.add(ship);
        }
        Collections.sort(result, new Comparator<ShipAPI>() {
            @Override
            public int compare(ShipAPI a, ShipAPI b) {
                return Integer.compare(getWingmanIndex(a), getWingmanIndex(b));
            }
        });
        return result;
    }

    private static int getWingmanIndex(ShipAPI wingman) {
        Object index = wingman.getCustomData().get(WINGMAN_INDEX_KEY);
        return index instanceof Integer ? (Integer) index : Integer.MAX_VALUE;
    }

    /** 这架僚机现在能不能被召回。正在整备、撤退或已在召回途中的都不打断。 */
    public static boolean isRecallable(ShipAPI wingman) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (wingman == null || engine == null) return false;
        if (!wingman.isAlive() || wingman.isHulk() || !engine.isEntityInPlay(wingman)) return false;
        if (wingman.isRetreating() || wingman.isLanding() || wingman.isFinishedLanding()) return false;
        if (wingman.getCustomData().containsKey("Moci_ForcedRetreat")) return false;
        if (engine.getPlayerShip() == wingman) return false;

        ShipAIPlugin ai = wingman.getShipAI();
        if (ai == null) return false;
        if (isRecalling(wingman)) return false;
        if (ai instanceof Moci_LandingAI || ai instanceof Moci_SquadRecallAI) return false;

        Moci_RefitSession session = Moci_RefitSession.get(wingman);
        if (session != null && session.isActive()) return false;
        Moci_MobileSuitRepairTracker tracker = Moci_MobileSuitRepairTracker.get(wingman);
        if (tracker != null && tracker.isActive()) return false;

        // 刚弹射出舱、旅行驱动还开着的僚机先让它飞完。
        return wingman.getTravelDrive() == null || !wingman.getTravelDrive().isOn();
    }

    public static int countRecallable(ShipAPI commander) {
        int count = 0;
        for (ShipAPI wingman : getWingmenOf(commander)) {
            if (isRecallable(wingman)) count++;
        }
        return count;
    }

    /** 该队长机名下正在召回途中的僚机数量。 */
    public static int countRecalling(ShipAPI commander) {
        int count = 0;
        Map<ShipAPI, Entry> entries = entries(false);
        if (entries == null) return 0;
        for (Entry entry : entries.values()) {
            if (entry.commander == commander) count++;
        }
        return count;
    }

    public static boolean isRecalling(ShipAPI wingman) {
        Map<ShipAPI, Entry> entries = entries(false);
        return entries != null && entries.containsKey(wingman);
    }

    // ------------------------------------------------------------------ 召回

    /**
     * 召回队长机名下所有可召回的僚机。
     *
     * @return 实际开始召回的僚机数量
     */
    public static int recall(ShipAPI commander) {
        if (commander == null || !commander.isAlive() || commander.isHulk()) return 0;
        Map<ShipAPI, Entry> entries = entries(true);
        if (entries == null) return 0;

        List<ShipAPI> wingmen = getWingmenOf(commander);
        int started = 0;
        for (int order = 0; order < wingmen.size(); order++) {
            ShipAPI wingman = wingmen.get(order);
            if (!isRecallable(wingman)) continue;
            try {
                ShipAIPlugin savedAI = wingman.getShipAI();
                ShipAIConfig savedConfig = savedAI.getConfig();
                Moci_SquadRecallAI recallAI = new Moci_SquadRecallAI(wingman, savedAI, savedConfig);
                Entry entry = new Entry(wingman, commander, recallAI, savedAI, savedConfig, order);
                recallAI.setDestination(computeSlot(entry), commander.getFacing());

                wingman.setShipAI(recallAI);
                entry.installedAI = wingman.getShipAI();
                applyBonus(entry);
                entries.put(wingman, entry);
                started++;
            } catch (RuntimeException e) {
                LOG.error(LOG_PREFIX + "召回僚机失败 wingman=" + wingman.getId(), e);
            }
        }
        if (TRACE) {
            LOG.info(LOG_PREFIX + "发起召回 commander=" + commander.getId()
                    + " 僚机总数=" + wingmen.size() + " 开始召回=" + started);
        }
        return started;
    }

    /** 每帧推进全部召回：更新站位与加成，处理到位、超时和被打断。 */
    public static void advance(float amount) {
        Map<ShipAPI, Entry> entries = entries(false);
        if (entries == null || entries.isEmpty()) return;
        CombatEngineAPI engine = Global.getCombatEngine();

        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            String endReason = getEndReason(engine, entry);
            if (endReason == null) {
                entry.elapsed += amount;
                Vector2f slot = computeSlot(entry);
                entry.recallAI.setDestination(slot, entry.commander.getFacing());
                applyBonus(entry);
                if (Misc.getDistance(entry.wingman.getLocation(), slot) <= ARRIVE_DISTANCE) {
                    endReason = "到位";
                } else if (entry.elapsed >= TIMEOUT_SECONDS) {
                    endReason = "超时";
                }
            }
            if (endReason != null) {
                finish(entry, endReason);
                iterator.remove();
            }
        }
    }

    /** 召回必须中止的原因；返回 null 表示继续。 */
    private static String getEndReason(CombatEngineAPI engine, Entry entry) {
        ShipAPI wingman = entry.wingman;
        ShipAPI commander = entry.commander;
        if (!wingman.isAlive() || wingman.isHulk() || !engine.isEntityInPlay(wingman)) return "僚机阵亡或离场";
        if (!commander.isAlive() || commander.isHulk() || !engine.isEntityInPlay(commander)) return "队长机阵亡或离场";
        if (wingman.isRetreating() || wingman.getCustomData().containsKey("Moci_ForcedRetreat")) return "僚机撤退";
        // 整备流程或玩家接管换掉了 AI：让位，不去抢。
        if (wingman.getShipAI() != entry.installedAI) return "AI 被接管";
        return null;
    }

    private static void finish(Entry entry, String reason) {
        ShipAPI wingman = entry.wingman;
        removeBonus(wingman);
        entry.recallAI.markFinished();

        boolean restored = false;
        // 只有 AI 还是我们装上去的那一个才恢复；被别人接管时不能把对方的 AI 顶掉。
        if (wingman.getShipAI() == entry.installedAI && wingman.isAlive() && !wingman.isHulk()) {
            restored = Moci_AIRestoreUtil.restoreSavedOrDefaultAI(wingman, entry.savedAI, entry.savedConfig);
        }
        if (TRACE) {
            LOG.info(LOG_PREFIX + "召回结束 wingman=" + wingman.getId() + " 原因=" + reason
                    + " 用时=" + String.format(java.util.Locale.US, "%.1f", entry.elapsed) + "s"
                    + " 恢复AI=" + restored);
        }
    }

    // ------------------------------------------------------------------ 站位与加成

    /**
     * 集结站位：前两架沿用部署时的左后 / 右后位置，更多的僚机在正后方扇形补位。
     * 站位随队长机移动与转向每帧更新。
     */
    private static Vector2f computeSlot(Entry entry) {
        ShipAPI commander = entry.commander;
        float distance = Math.max(Moci_CombatSquadAIV2.getEscortIdeal(),
                commander.getCollisionRadius() + entry.wingman.getCollisionRadius() + 60f);
        float angle = Misc.normalizeAngle(commander.getFacing() + slotOffsetDegrees(entry.slotOrder));
        Vector2f offset = Misc.getUnitVectorAtDegreeAngle(angle);
        offset.scale(distance);
        return Vector2f.add(commander.getLocation(), offset, null);
    }

    /** 相对队长机朝向的站位角度。0 号 = 左后（+120°），1 号 = 右后（+240°），其余向正后方两侧展开。 */
    private static float slotOffsetDegrees(int order) {
        if (order <= 0) return 120f;
        if (order == 1) return 240f;
        int n = order - 2;
        if (n == 0) return 180f;
        float magnitude = 25f * ((n + 1) / 2);
        return 180f + (n % 2 == 1 ? -magnitude : magnitude);
    }

    private static void applyBonus(Entry entry) {
        float distance = Misc.getDistance(entry.wingman.getLocation(), entry.recallAI.getDestination());
        float taper = Math.max(SPEED_TAPER_MIN, Math.min(1f, distance / SPEED_TAPER_DISTANCE));
        MutableShipStatsAPI stats = entry.wingman.getMutableStats();
        stats.getMaxSpeed().modifyPercent(BONUS_ID, SPEED_BONUS_PERCENT * taper);
        stats.getAcceleration().modifyPercent(BONUS_ID, MANEUVER_BONUS_PERCENT);
        stats.getDeceleration().modifyPercent(BONUS_ID, MANEUVER_BONUS_PERCENT);
        stats.getMaxTurnRate().modifyPercent(BONUS_ID, MANEUVER_BONUS_PERCENT);
        stats.getTurnAcceleration().modifyPercent(BONUS_ID, MANEUVER_BONUS_PERCENT);
    }

    private static void removeBonus(ShipAPI wingman) {
        MutableShipStatsAPI stats = wingman.getMutableStats();
        stats.getMaxSpeed().unmodify(BONUS_ID);
        stats.getAcceleration().unmodify(BONUS_ID);
        stats.getDeceleration().unmodify(BONUS_ID);
        stats.getMaxTurnRate().unmodify(BONUS_ID);
        stats.getTurnAcceleration().unmodify(BONUS_ID);
    }

    @SuppressWarnings("unchecked")
    private static Map<ShipAPI, Entry> entries(boolean create) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return null;
        Object existing = engine.getCustomData().get(ENTRIES_KEY);
        if (existing instanceof Map) {
            return (Map<ShipAPI, Entry>) existing;
        }
        if (!create) return null;
        Map<ShipAPI, Entry> created = new IdentityHashMap<>();
        engine.getCustomData().put(ENTRIES_KEY, created);
        return created;
    }
}
