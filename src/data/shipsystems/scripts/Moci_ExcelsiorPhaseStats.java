package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import com.fs.starfarer.api.util.IntervalUtil;
import data.scripts.combat.Moci_TimeFlow;
import data.scripts.util.Moci_ExcelsiorUtil;
import java.awt.Color;
import java.util.Random;
import org.lazywizard.lazylib.MathUtils;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

public class Moci_ExcelsiorPhaseStats extends BaseShipSystemScript {

    // ===== 时间泄漏排查开关（常驻，不绑 devMode）=====
    public static final boolean TRACE = true;
    private static final String LOG_PREFIX = "[MOCI-TIME] ";
    private static final Logger LOG = Global.getLogger(Moci_ExcelsiorPhaseStats.class);

    private static final float ARMOR_REPAIR_MULTIPLIER = 300.0f;
    private static final float HULL_REPAIR_MULTIPLIER = 100.0f;
    private static final float MAX_TIME_MULT_MAX = 4f;
    private static final float SHIP_ALPHA_MULT = 0.25f;
    private static final float SPARK_BRIGHTNESS = 0.9f;
    private static final Color SPARK_COLOR = new Color(255, 0, 200);
    private static final Color GLOW_COLOR = new Color(200, 0, 255, 75);
    private static final Color SHADOW_COLOR = new Color(0, 0, 0, 100);
    private static final float SPARK_DURATION = 0.5f;
    private static final float SPARK_MAX_RADIUS = 5f;
    private static final Vector2f ZERO = new Vector2f(0f, 0f);

    public static Vector2f getCellLocation(ShipAPI ship, float x, float y) {
        float xx = x - (ship.getArmorGrid().getGrid().length / 2f);
        float yy = y - (ship.getArmorGrid().getGrid()[0].length / 2f);
        float cellSize = ship.getArmorGrid().getCellSize();
        Vector2f cellLoc = new Vector2f();
        float theta = (float) (((ship.getFacing() - 90f) / 360f) * (Math.PI * 2.0));
        cellLoc.x = (float) (xx * Math.cos(theta) - yy * Math.sin(theta)) * cellSize + ship.getLocation().x;
        cellLoc.y = (float) (xx * Math.sin(theta) + yy * Math.cos(theta)) * cellSize + ship.getLocation().y;

        return cellLoc;
    }

    public static float getMaxTimeMult(MutableShipStatsAPI stats) {
        return 1f + (MAX_TIME_MULT_MAX - 1f) * stats.getDynamic().getValue(Stats.PHASE_TIME_BONUS_MULT);
    }

    private final Object STATUSKEY1 = new Object();
    private final Object STATUSKEY2 = new Object();
    private final Object STATUSKEY3 = new Object();
    private final Object STATUSKEY4 = new Object();
    private final Object STATUSKEY5 = new Object();
    private final IntervalUtil syncInterval = new IntervalUtil(0.4f, 0.8f);
    private final IntervalUtil interval = new IntervalUtil(0.033f, 0.033f);
    private final IntervalUtil afterimageInterval = new IntervalUtil(0.033f, 0.033f);
    private final Random rand = new Random();

    private float killTimer = 0f;

    /**
     * 时间申请的名字。迁移到 Moci_TimeFlow 后它只作 Handle 名，不再参与任何属性键 ——
     * 原实现 apply 用 {@code id + "_" + shipId}、unapply 用基 id 的「id 分裂」缺陷因此按构造消失：
     * 句柄自己带着两半申请，释放时不需要重新推导任何字符串。
     */
    private static final String TIME_HANDLE_ID = "Moci_ExcelsiorPhaseStats";
    /** 本场生效中的时间申请；null = 当前没有申请。 */
    private Moci_TimeFlow.Handle timeHandle;
    /**
     * 当前申请是否带「世界 ×1/倍率」这一半（= 玩家舰的子弹时间形态）。
     * player 判定每帧都可能翻转，形态一变必须重开申请，不能只 set()。
     */
    private boolean timeHandleIsBullet;

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        CombatEngineAPI engine = Global.getCombatEngine();
        ShipAPI ship;
        boolean player;
        String actualId = id;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
            player = ship == engine.getPlayerShip();
            actualId = actualId + "_" + ship.getId();
        } else {
            return;
        }

        if (player) {
            maintainStatus(ship, effectLevel);
        }

        if (engine.isPaused()) {
            return;
        }

        if (state == State.COOLDOWN || state == State.IDLE) {
            // 传基 id：真正的复合 id 由 unapply 自己按实体重算（对称，且对它重复调用也安全）
            unapply(stats, id);
            return;
        }

        float speedPercentMod = stats.getDynamic().getMod(Stats.PHASE_CLOAK_SPEED_MOD).computeEffective(0f);

        float fluxLevel = ship.getFluxTracker().getCurrFlux() / stats.getFluxCapacity().getBaseValue();

        if (ship == engine.getPlayerShip()) {
            engine.maintainStatusForPlayerShip(STATUSKEY1, "graphics/icons/hullsys/phase_cloak.png",
                    "Phase Drive", "Draining flux", true);

            boolean display3 = false;
            String displayStr3 = "Repairing ";
            ArmorGridAPI armorGrid = ship.getArmorGrid();
            OUT:
            for (int x = armorGrid.getGrid().length - 1; x >= 0; x--) {
                for (int y = armorGrid.getGrid()[x].length - 1; y >= 0; y--) {
                    if (armorGrid.getArmorValue(x, y) < armorGrid.getMaxArmorInCell()) {
                        displayStr3 += "armor";
                        display3 = true;
                        break OUT;
                    }
                }
            }

            if (ship.getHitpoints() < ship.getMaxHitpoints()) {
                if (display3) {
                    displayStr3 += "/";
                }
                displayStr3 += "hull";
                display3 = true;
            }

            if (display3) {
                engine.maintainStatusForPlayerShip(STATUSKEY3, "graphics/icons/hullsys/phase_cloak.png",
                        "Phase Drive", displayStr3, false);
            }
        }

        float levelForAlpha = effectLevel;
        ship.setPhased(true);

        ship.setExtraAlphaMult(1f - (1f - SHIP_ALPHA_MULT) * levelForAlpha);
        ship.setApplyExtraAlphaToEngines(true);

        float shipTimeMult = Math.min(Math.max(1f + ((getMaxTimeMult(stats) - 1f) * effectLevel * Math.max(0f, fluxLevel)), 1f), getMaxTimeMult(stats));
        // 时间倍率交给 Moci_TimeFlow：玩家舰成对（船 ×shipTimeMult、世界 ×1/shipTimeMult），
        // 非玩家舰只压船侧。数值与原实现一字不差，只是「挂」不再分散在本类的属性表直写上。
        applyTimeFlow(ship, player, shipTimeMult);

        interval.advance(engine.getElapsedInLastFrame() * stats.getTimeMult().getModifiedValue());
        if (interval.intervalElapsed()) {
            ArmorGridAPI armorGrid = ship.getArmorGrid();
            int x = rand.nextInt(armorGrid.getGrid().length);
            int y = rand.nextInt(armorGrid.getGrid()[0].length);
            float newArmor = armorGrid.getArmorValue(x, y);
            float cellSize = armorGrid.getCellSize();

            if (Float.compare(newArmor, armorGrid.getMaxArmorInCell()) < 0) {
                newArmor += ARMOR_REPAIR_MULTIPLIER * interval.getIntervalDuration() * fluxLevel;
                armorGrid.setArmorValue(x, y, Math.min(armorGrid.getMaxArmorInCell(), newArmor));

                Vector2f cellLoc = getCellLocation(ship, x, y);
                cellLoc.x += cellSize * 0.1f - cellSize * (float) Math.random();
                cellLoc.y += cellSize * 0.1f - cellSize * (float) Math.random();
                engine.addHitParticle(cellLoc, ship.getVelocity(), SPARK_MAX_RADIUS * (float) Math.random()
                        + SPARK_MAX_RADIUS, SPARK_BRIGHTNESS, SPARK_DURATION,
                        SPARK_COLOR);
            }

            ship.setHitpoints(Math.min(ship.getHitpoints() + interval.getIntervalDuration() * HULL_REPAIR_MULTIPLIER * fluxLevel, ship.getMaxHitpoints()));
        }

        syncInterval.advance(engine.getElapsedInLastFrame());
        if (syncInterval.intervalElapsed()) {
            ship.syncWithArmorGridState();
            ship.syncWeaponDecalsWithArmorDamage();

            if ((ship.getHitpoints() / ship.getMaxHitpoints()) >= 0.9f) {
                ArmorGridAPI armorGrid = ship.getArmorGrid();
                float totalArmor = 0f;
                float currArmor = 0f;
                for (int x = armorGrid.getGrid().length - 1; x >= 0; x--) {
                    for (int y = armorGrid.getGrid()[x].length - 1; y >= 0; y--) {
                        totalArmor += armorGrid.getMaxArmorInCell();
                        currArmor += armorGrid.getArmorValue(x, y);
                    }
                }
                if ((currArmor / totalArmor) >= 0.9f) {
                    ship.clearDamageDecals();
                }
            }
        }

        killTimer -= engine.getElapsedInLastFrame() * stats.getTimeMult().getModifiedValue();
        if ((ship.getFluxTracker().getCurrFlux() < 100f) && (killTimer <= 0f) && (state == State.ACTIVE)) {
            ship.getFluxTracker().setCurrFlux(100f);
            killTimer = 0.5f;
            ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
        }

        if (state == ShipSystemStatsScript.State.OUT) {
            stats.getMaxSpeed().unmodify(actualId);
            stats.getMaxTurnRate().unmodify(actualId);
        } else {
            float level = (1f + fluxLevel * 0.5f) * effectLevel;
            stats.getMaxSpeed().modifyFlat(actualId, 50f * level);
            stats.getMaxSpeed().modifyPercent(actualId, speedPercentMod * effectLevel);
            stats.getMaxTurnRate().modifyPercent(actualId, 50f * level);
            stats.getAcceleration().modifyFlat(actualId, 200f * level);
            stats.getDeceleration().modifyFlat(actualId, 200f * level);
            stats.getTurnAcceleration().modifyPercent(actualId, 75f * level);
            if (ship == engine.getPlayerShip()) {
                engine.maintainStatusForPlayerShip(STATUSKEY4, "graphics/icons/hullsys/phase_cloak.png",
                        "Phase Drive", "+" + (int) (50f * level) + " speed", false);
                engine.maintainStatusForPlayerShip(STATUSKEY5, "graphics/icons/hullsys/phase_cloak.png",
                        "Phase Drive", "+" + (int) (125f * level) + "% maneuverability",
                        false);
            }
            if (ship.getFluxTracker().getCurrFlux() <= 50f) {
                ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
            }
            afterimageInterval.advance(engine.getElapsedInLastFrame() * shipTimeMult);
            if (afterimageInterval.intervalElapsed()) {
                float randRange = 15f * level;
                float randAngle = MathUtils.getRandomNumberInRange(0f, 360f);
                float randRadiusFrac = (float) (Math.random() + Math.random());
                randRadiusFrac = (randRadiusFrac > 1f ? 2f - randRadiusFrac : randRadiusFrac);
                Vector2f randLoc = MathUtils.getPointOnCircumference(ZERO, randRange * randRadiusFrac, randAngle);
                ship.addAfterimage(GLOW_COLOR, randLoc.x, randLoc.y, 0f, 0f,
                        randRange, 0f, 0f, 0.1f, true, false, false);

                Color afterimageColor = Moci_ExcelsiorUtil.fadeColor255(SPARK_COLOR, fluxLevel);
                ship.addAfterimage(SHADOW_COLOR, 0f, 0f, 0f, 0f,
                        0, 0.033f, 0f, 0.033f, false, false, false);
                ship.addAfterimage(afterimageColor, 0f, 0f, -ship.getVelocity().x, -ship.getVelocity().y,
                        0, 0f, 0f, 0.1f * level, true, false, false);
            }
        }
    }

    /**
     * 申请/续期本系统的时间倍率。
     *
     * <p>原语义：船侧 {@code ×shipTimeMult}；玩家舰额外把世界压到 {@code 1/shipTimeMult}，
     * 非玩家舰把世界那一半摘掉。迁移后玩家舰用 {@code openBulletTime}（船 ×f、世界 ×1/f，
     * 成对出现、成对消失），非玩家舰用 {@code openShip}（绝不触碰世界）。</p>
     *
     * <p>player 每帧都可能翻转（换乘/交出驾驶权），形态一变就重开申请，因此不需要
     * 原来那种「非玩家时 unmodify 一次」的显式摘除分支 —— 那句正是 id 分裂缺陷的现场。</p>
     */
    private void applyTimeFlow(ShipAPI ship, boolean player, float shipTimeMult) {
        if (timeHandle == null || !timeHandle.active() || timeHandleIsBullet != player) {
            releaseTimeFlow("重建时间申请 形态=" + (player ? "bullet" : "ship"));
            timeHandle = player
                    ? Moci_TimeFlow.openBulletTime(ship, TIME_HANDLE_ID, shipTimeMult)
                    : Moci_TimeFlow.openShip(ship, TIME_HANDLE_ID, shipTimeMult);
            timeHandleIsBullet = player;
            if (TRACE && timeHandle != null && timeHandle.active()) {
                LOG.info(LOG_PREFIX + "ExcelsiorPhase 申请时间 形态=" + (player ? "bullet" : "ship")
                        + " 船×" + shipTimeMult + (player ? " 世界×" + (1f / shipTimeMult) : ""));
            }
        } else {
            timeHandle.set(shipTimeMult);
        }
    }

    /**
     * 收回本类唯一的时间申请（幂等）。
     * 句柄自带船侧与世界侧两半，所以这里一次就是原来的两条 unmodify，且不可能只成功一半。
     */
    private void releaseTimeFlow(String why) {
        Moci_TimeFlow.Handle handle = timeHandle;
        if (handle == null) {
            return;
        }
        timeHandle = null;
        boolean wasActive = handle.active();
        try {
            handle.release();
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn(LOG_PREFIX + "ExcelsiorPhase 收回时间申请失败 id=" + TIME_HANDLE_ID + " -> " + failure);
        }
        if (TRACE && wasActive) {
            LOG.info(LOG_PREFIX + "ExcelsiorPhase 收回时间申请 id=" + TIME_HANDLE_ID + " 原因=" + why);
        }
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        ShipAPI ship;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        } else {
            return;
        }

        // 时间申请先收：句柄成对持有船侧与世界侧，一次 release 等价于原来的
        // stats.getTimeMult().unmodify(actualId) + engine.getTimeMult().unmodify(actualId)。
        // 原实现的 id 分裂泄漏（apply 写复合 id、unapply 传基 id → 两条 unmodify 全是 no-op，
        // 第一次激活就把 1/shipTimeMult 永久留给世界）迁移后不可能再发生：
        // 释放路径不再依赖任何重新推导出来的字符串。
        releaseTimeFlow("unapply");

        // 剩下的 maxSpeed / 转向 / 加减速仍是普通属性修饰，按 apply 实际写入的复合 id 摘除。
        // actualIdOf 得以保留是因为它现在只服务这些非时间属性；时间路径已完全不经它。
        if (id != null) {
            releaseStats(stats, actualIdOf(id, ship));
        }

        ship.setPhased(false);
        ship.setExtraAlphaMult(1f);
    }

    /** 把基 id 还原成 apply 真正写入的 id；已经是复合 id 时原样返回（幂等） */
    private static String actualIdOf(String id, ShipAPI ship) {
        if (id == null) {
            return null;
        }
        String suffix = "_" + ship.getId();
        return id.endsWith(suffix) ? id : id + suffix;
    }

    /**
     * 释放某个 id 在本舰上的**非时间**属性修饰（逐项兜底，一项失败不影响后续）。
     * 时间倍率已不在这里摘：它由 Moci_TimeFlow 的申请独占，见 {@link #releaseTimeFlow(String)}。
     */
    private void releaseStats(MutableShipStatsAPI stats, String modifierId) {
        if (modifierId == null) {
            return;
        }
        releaseQuietly(() -> stats.getMaxSpeed().unmodify(modifierId), "max speed", modifierId);
        releaseQuietly(() -> stats.getMaxTurnRate().unmodify(modifierId), "max turn rate", modifierId);
        releaseQuietly(() -> stats.getTurnAcceleration().unmodify(modifierId), "turn accel", modifierId);
        releaseQuietly(() -> stats.getAcceleration().unmodify(modifierId), "accel", modifierId);
        releaseQuietly(() -> stats.getDeceleration().unmodify(modifierId), "decel", modifierId);
    }

    /** 单步释放：一步失败只记一条日志，不影响后续步骤 */
    private void releaseQuietly(Runnable step, String what, String modifierId) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn(LOG_PREFIX + "ExcelsiorPhase 释放失败 " + what + " id=" + modifierId
                    + " -> " + failure);
        }
    }

    @Override
    public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
        return (ship.getFluxTracker().getCurrFlux() > 125f) || ship.getPhaseCloak().isActive();
    }

    private void maintainStatus(ShipAPI playerShip, float effectLevel) {
        ShipSystemAPI cloak = playerShip.getPhaseCloak();
        if (cloak == null) {
            cloak = playerShip.getSystem();
        }
        if (cloak == null) {
            return;
        }

        if (effectLevel > 0f) {
            Global.getCombatEngine().maintainStatusForPlayerShip(STATUSKEY2,
                    cloak.getSpecAPI().getIconSpriteName(),
                    cloak.getDisplayName(), "time flow altered", false);
        }
    }
}


