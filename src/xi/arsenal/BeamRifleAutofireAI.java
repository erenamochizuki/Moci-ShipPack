package xi.arsenal;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import java.util.Locale;
import org.lwjgl.util.vector.Vector2f;

/**
 * 三把「臂炮 + 蓄力枪」的武器 AI。
 *
 * <p>为什么必须有它：这三把武器在 {@code weapon_data.csv} 里带 {@code SYSTEM} hint，
 * 而 {@code ARSENAL_RIFLE_CHARGE} 槽还是 {@code HIDDEN} —— 原版舰船 AI 永远不会自发使用它们。
 * 早期版本用「控制器每帧 {@code setForceFireOneFrame}」去硬驱动，抢走了玩家对同一把枪的手动控制权；
 * 现在改成在这里回答「该不该扣扳机」，蓄力、弹道、后坐全部仍由原生武器逻辑执行（双模式互斥由此成立）。</p>
 *
 * <p><b>R5：从「固定节奏机」升级为「按战术态势选模式」。</b>用户裁决：
 * 「追击 / 机动作战用点射，与人拉锯、尤其会被打的时候用照射（照射会举盾，利于防御）」。
 * 于是本类先做一个 {@link AiMode} 决策：
 * <ul>
 *   <li>{@link AiMode#SKIRMISH} —— 点射臂炮（可见的 {@code Moci_fw_xi_beam_rifle}），
 *       沿用 {@link Cadence WINDUP → FIRE → COOLDOWN} 节奏机，只有 {@code FIRE} 段扣扳机。</li>
 *   <li>{@link AiMode#DUEL} —— 蓄力光束（隐藏的 {@code Moci_fw_xi_rifle_charge}）＋ 举盾。
 *       举盾不属于本类：本类只把「期望照射」通过
 *       {@link BeamRifleModes#requestAiMode} 下发给控制器，由
 *       {@link RifleBracedPose} 去做护盾模块位移。</li>
 * </ul>
 * 模式挂在 {@code ship.getCustomData()} 的 {@link #STANCE_KEY} 上，每艘舰只有一份，
 * 由蓄力枪那台实例（leader）负责决策，臂炮那台实例只读 —— 因为引擎会为<b>每把</b>武器各建一台
 * 本类实例，两台各自决策必然互相拉扯。</p>
 *
 * <p>弹药纪律：蓄力枪 {@code ammoPerSecond = 0}，整场只有 36 发，所以 DUEL 一轮最多照
 * {@link #DUEL_BURST_SECONDS} 秒，随后强制歇 {@link #DUEL_REST_SECONDS} 秒回点射；
 * 弹尽之后不再进 DUEL，只打自回弹的臂炮。</p>
 *
 * <p>性能：目标按 {@link #RETARGET_INTERVAL} 秒错峰重选，距离比较全用平方，热点路径零分配
 * （{@link #aim} 是构造期分配一次的复用容器；{@link Stance} 每舰一份）。时钟读引擎总时长，
 * {@code advance} 与 {@code shouldFire} 重复推进也只会得到同一个结果。</p>
 */
public final class BeamRifleAutofireAI implements AutofireAIPlugin {

    private static final String LOG_TAG = "[MOCI-RIFLE-AI] ";

    /** 每舰态势挂载点：本类实例之间唯一的共享状态。 */
    private static final String STANCE_KEY = "xi.arsenal.rifle.aiStance";

    /** 重选目标间隔（秒）。目标丢失时立即重选，不等计时。 */
    private static final float RETARGET_INTERVAL = 0.45f;

    /** 射程余量：贴着最大射程开火会因目标一帧位移而落空，留 2% 余量。 */
    private static final float RANGE_SLACK = 0.98f;

    /** 射界余量：转到位再打，别在弧外擦边扣扳机。 */
    private static final float ARC_SLACK = 0.95f;

    /** 蓄力段时长：AI 必须先「抬枪充能」再照射，不能一帧点火。 */
    private static final float WINDUP_SECONDS = 0.75f;

    /** 照射段时长：与玩家 1.0s 蓄力档的 duration（≈1.0s）同量级，取短一些避免贴脸无限烧。 */
    private static final float FIRE_SECONDS = 0.55f;

    /** 冷却下限：蓄力枪的真实 chargedown 只有 0.15s（CSV 第 1296 行 Moci_fw_xi_rifle_charge；1.35s 是
     *  第 1278 行臂炮的值，不是抄错数据）。原生 chargedown 0.15s 不足以构成节奏，故以 1.0s 为下限，
     *  给 AI 一个可读的呼吸窗口。 */
    private static final float MIN_COOLDOWN_SECONDS = 1.0f;

    /** 读不到 chargedown 时的兜底冷却。 */
    private static final float COOLDOWN_FALLBACK = 1.0f;

    /** DUEL 最短驻留：进照射后至少照这么久，避免一帧一换模式抖成噪音。 */
    private static final float DUEL_MIN_SECONDS = 1.2f;

    /** SKIRMISH 最短驻留：点射档位同样要稳。 */
    private static final float SKIRMISH_MIN_SECONDS = 0.6f;

    /** DUEL 单轮照射上限：蓄力枪整场只有 36 发且不回复，一次别烧穿。 */
    private static final float DUEL_BURST_SECONDS = 2.5f;

    /** 一轮照射之后的强制休息：这段时间只打点射，给弹仓和节奏都留口气。 */
    private static final float DUEL_REST_SECONDS = 1.0f;

    /** 受击窗口：最近这么久之内掉过结构，就当作「正在挨打」。 */
    private static final float DUEL_HIT_WINDOW = 1.5f;

    /** 结构采样周期（秒）：0.25s 一次就够判「最近挨过打」，且各舰按 id 错峰。 */
    private static final float HULL_SAMPLE_SECONDS = 0.25f;

    /** 拉锯距离带：目标落在 [0.55, 0.98] × 蓄力射程之间，说明双方都能打到对方。 */
    private static final float DUEL_NEAR_RANGE = 0.55f, DUEL_FAR_RANGE = 0.98f;

    /** 拉锯的相对速度判据：相对速度 / 目标速度 低于它，且绝对相对速度也低，才算「对住不动」。 */
    private static final float DUEL_RELATIVE_SPEED = 0.75f, DUEL_RELATIVE_ABS = 150f;

    /** 已退役（m01502 裁决）：蓄力档位改由 {@link BeamRifleModes#AI_CHARGE_SECONDS}（1.5 秒）承载，
     *  蓄力时长与伤害倍率同取该档。任何新代码都不得再引用本常量。 */
    @Deprecated
    private static final float CHARGE_FULL_SECONDS = 3.0f;

    /** 常驻诊断开关：置 false 只关闭本类的日志，不影响任何开火判定。 */
    /** AI 意图追踪开关：默认关闭（战斗里按射击节奏持续写日志，交付期只留一次性告警）。
     *  需要复现 AI 语义问题时改回 true 重新构建即可，不依赖 devMode。 */
    private static final boolean TRACE = false;

    /** 一台「扳机时钟」的三个相位：只有 FIRE 允许扣扳机。 */
    private enum Cadence {
        WINDUP, FIRE, COOLDOWN
    }

    /** 战术态势：点射（机动/追击）或照射（拉锯/挨打，附带举盾）。 */
    private enum AiMode {
        SKIRMISH, DUEL
    }

    /** 每舰一份的态势黑板。由蓄力枪实例（leader）写，臂炮实例只读。 */
    private static final class Stance {
        AiMode mode = AiMode.SKIRMISH;
        double modeSince = Double.NaN;
        double hullSampleAt = Double.NaN;
        float hullFraction = 1f;
        double lastHullDrop = Double.NEGATIVE_INFINITY;
        double duelStartedAt = Double.NaN;
        double duelRestUntil = Double.NaN;
        /** 上一轮 DUEL 是怎么结束的：BURST = 照满一轮主动收手；EMPTY = 弹仓见底。 */
        boolean chargeEmpty;
    }

    private final WeaponAPI weapon;
    /** 复用容器：引擎需要 aim 点时直接指向它，避免每帧 new Vector2f。 */
    private final Vector2f aim = new Vector2f();
    /** 只有蓄力枪那台实例做态势决策，其余实例只读黑板 —— 每把枪一台实例，各判各的必然打架。 */
    private final boolean leader;

    private ShipAPI target;
    private float retargetTimer;
    private boolean loggedDrive;
    private boolean loggedAcquire;
    private boolean loggedFire;

    private Cadence cadence = Cadence.WINDUP;
    /** 当前相位进入时的引擎总时长；NaN = 还没初始化。 */
    private double cadenceSince = Double.NaN;
    private boolean loggedWindup;
    private boolean loggedCadenceFire;
    private boolean loggedCooldown;

    public BeamRifleAutofireAI(WeaponAPI weapon) {
        this.weapon = weapon;
        this.leader = BeamRifleModes.isCharge(weapon);
        if (TRACE && weapon != null) {
            Global.getLogger(BeamRifleAutofireAI.class).info(LOG_TAG + "installed weapon="
                    + weapon.getId() + " ship=" + describe(weapon.getShip())
                    + " role=" + (leader ? "stance-leader" : "stance-follower"));
        }
    }

    @Override
    public void advance(float amount) {
        WeaponAPI held = weapon;
        if (held == null) {
            target = null;
            return;
        }
        ShipAPI ship = held.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            target = null;
            return;
        }
        if (TRACE && !loggedDrive) {
            loggedDrive = true;
            Global.getLogger(BeamRifleAutofireAI.class).info(LOG_TAG + "drive " + describe(ship)
                    + " weapon=" + held.getId());
        }
        if (amount > 0f) {
            retargetTimer -= amount;
        }
        if (target != null && usable(ship, target)) {
            // 已锁定且仍有效：只刷新瞄点，不重新遍历战场。
            aim.set(target.getLocation());
            if (retargetTimer > 0f) {
                refreshStance(held, ship);
                tick(held, ship);
                return;
            }
        }
        retargetTimer = RETARGET_INTERVAL;
        target = pick(ship, held);
        if (target != null) {
            aim.set(target.getLocation());
            if (TRACE && !loggedAcquire) {
                loggedAcquire = true;
                Global.getLogger(BeamRifleAutofireAI.class).info(LOG_TAG
                        + "acquire " + describe(ship) + " weapon=" + held.getId()
                        + " target=" + describe(target)
                        + " range=" + Math.round(held.getRange()));
            }
        }
        refreshStance(held, ship);
        tick(held, ship);
    }

    @Override
    public boolean shouldFire() {
        WeaponAPI held = weapon;
        if (held == null) {
            return false;
        }
        ShipAPI ship = held.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk() || ship.wasRemoved()) {
            return false;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return false;
        }
        /* 铁律：玩家亲自操作的武器永远不由我们扣扳机。
           判据是 ShipAI —— 玩家亲手驾驶的舰 getShipAI()==null；按 U 之后引擎会给它挂上
           ShipAI，那一刻它与非玩家舰同构，开火就该由武器 AI 接管（这也正是玩家按下 U 的意图）。
           手动蓄力与处决动作因此始终由玩家输入驱动，不会被自动开火打断。 */
        if (ship.getShipAI() == null) {
            return false;
        }
        // 态势可能在本帧由 leader 改写，读的是同一份黑板。
        AiMode stance = stance(ship).mode;
        if (leader || BeamRifleModes.isCharge(held)) {
            return shouldFireCharge(held, ship, stance);
        }
        return shouldFirePoint(held, ship, stance);
    }

    /** 照射档：拉锯/挨打期间走同一台节奏机，蓄力固定为
     *  {@link BeamRifleModes#AI_CHARGE_SECONDS}（1.5 秒，m01502 裁决）——
     *  WINDUP 段绝不扣扳机，蓄满才允许发射，照射时长与伤害倍率同取这一档。 */
    private boolean shouldFireCharge(WeaponAPI held, ShipAPI ship, AiMode stance) {
        if (stance != AiMode.DUEL) {
            // 退出照射档：把扳机时钟压回冷却起点，下次进档必须重新蓄满 1.5 秒。
            cadence = Cadence.COOLDOWN;
            cadenceSince = Double.NaN;
            BeamRifleModes.noteAiCharge(ship, 0f);
            BeamRifleModes.noteAiFiring(ship, false);
            return false;
        }
        if (!canEngage(held, ship)) {
            BeamRifleModes.noteAiCharge(ship, 0f);
            BeamRifleModes.noteAiFiring(ship, false);
            return false;
        }
        // 蓄力枪与臂炮共用节奏机：没有蓄满 1.5 秒，这里恒 false。
        tick(held, ship);
        if (cadence != Cadence.FIRE) {
            return false;
        }
        if (TRACE && !loggedFire) {
            loggedFire = true;
            Global.getLogger(BeamRifleAutofireAI.class).info(LOG_TAG
                    + "fire " + describe(ship) + " weapon=" + held.getId()
                    + " target=" + describe(target) + " mode=DUEL");
        }
        return true;
    }

    /** 点射档：沿用 R4 的 WINDUP/FIRE/COOLDOWN 节奏机，只有 FIRE 段扣扳机。 */
    private boolean shouldFirePoint(WeaponAPI held, ShipAPI ship, AiMode stance) {
        if (stance != AiMode.SKIRMISH) {
            return false;
        }
        // 时钟以引擎总时长为基准，advance 与本方法谁先被调用都不会重复计时。
        tick(held, ship);
        // 蓄力段与冷却段一律不扣扳机 —— 这就是「AI 也得蓄力」。
        if (cadence != Cadence.FIRE) {
            return false;
        }
        if (!canEngage(held, ship)) {
            return false;
        }
        if (TRACE && !loggedFire) {
            loggedFire = true;
            float dx = target.getLocation().x - held.getLocation().x;
            float dy = target.getLocation().y - held.getLocation().y;
            Global.getLogger(BeamRifleAutofireAI.class).info(LOG_TAG
                    + "fire " + describe(ship) + " weapon=" + held.getId()
                    + " target=" + describe(target)
                    + " dist=" + Math.round(Math.sqrt(dx * dx + dy * dy)) + " mode=SKIRMISH");
        }
        return true;
    }

    @Override
    public void forceOff() {
        target = null;
        retargetTimer = 0f;
        // 被强制停火后重新从蓄力开始，别让下一次交火直接跳过 WINDUP。
        cadence = Cadence.COOLDOWN;
        cadenceSince = Double.NaN;
    }

    @Override
    public Vector2f getTarget() {
        return target == null ? null : aim;
    }

    @Override
    public ShipAPI getTargetShip() {
        return target;
    }

    @Override
    public WeaponAPI getWeapon() {
        return weapon;
    }

    @Override
    public MissileAPI getTargetMissile() {
        return null;
    }

    // ------------------------------------------------------------------ 态势决策

    /** 取（必要时建立）本舰态势黑板。每舰一份，两台武器实例共享。 */
    private static Stance stance(ShipAPI ship) {
        Object existing = ship.getCustomData().get(STANCE_KEY);
        if (existing instanceof Stance known) {
            return known;
        }
        Stance fresh = new Stance();
        ship.getCustomData().put(STANCE_KEY, fresh);
        return fresh;
    }

    /** 只有 leader（蓄力枪那台实例）推进态势；跟随者直接读黑板。 */
    private void refreshStance(WeaponAPI held, ShipAPI ship) {
        if (!leader) {
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        double now = engine.getTotalElapsedTime(false);
        Stance state = stance(ship);
        if (Double.isNaN(state.modeSince) || now < state.modeSince) {
            state.modeSince = now;
        }
        sampleHull(ship, state, now);
        state.chargeEmpty = held.usesAmmo() && held.getAmmo() <= 0;

        boolean resting = now < state.duelRestUntil;
        String reason = "";
        boolean duelWanted = false;
        if (!state.chargeEmpty && !resting) {
            reason = duelReason(ship, state, now);
            duelWanted = !reason.isEmpty();
        }
        AiMode next = duelWanted ? AiMode.DUEL : AiMode.SKIRMISH;
        if (next == state.mode) {
            // 照满一轮就强制收手：一个 DUEL 周期不允许无限续杯。
            if (state.mode == AiMode.DUEL && now - state.duelStartedAt >= DUEL_BURST_SECONDS) {
                state.duelRestUntil = now + DUEL_REST_SECONDS;
                switchTo(state, AiMode.SKIRMISH, now, "burst-complete", ship);
            }
            publishStance(ship, state);
            return;
        }
        if (!dwellElapsed(state, now)) {
            publishStance(ship, state);
            return;
        }
        switchTo(state, next, now, reason.isEmpty() ? "no-threat" : reason, ship);
        publishStance(ship, state);
    }

    /** 迟滞：当前模式没走满最短驻留时间就不许换。 */
    private static boolean dwellElapsed(Stance state, double now) {
        float minimum = state.mode == AiMode.DUEL ? DUEL_MIN_SECONDS : SKIRMISH_MIN_SECONDS;
        return Double.isNaN(state.modeSince) || now - state.modeSince >= minimum;
    }

    private void switchTo(Stance state, AiMode next, double now, String reason, ShipAPI ship) {
        AiMode previous = state.mode;
        state.mode = next;
        state.modeSince = now;
        if (next == AiMode.DUEL) {
            state.duelStartedAt = now;
        }
        if (TRACE) {
            Global.getLogger(BeamRifleAutofireAI.class).info(LOG_TAG + "mode "
                    + previous + "->" + next + " reason=" + reason + " ship=" + describe(ship));
        }
    }

    /** 把期望模式下发控制器：举盾/蓄力语义由 {@link BeamRifleModes} 持有，本类只表达意图。 */
    private static void publishStance(ShipAPI ship, Stance state) {
        BeamRifleModes.requestAiMode(ship, state.mode == AiMode.DUEL);
    }

    /** 结构采样（0.25s 一次，按舰 id 错峰）+ 差分判断「最近有没有挨打」。 */
    private static void sampleHull(ShipAPI ship, Stance state, double now) {
        if (Double.isNaN(state.hullSampleAt)) {
            // 错峰：不同舰的采样相位拉开到采样周期之内，避免整场同帧一起算。
            double stagger = (Math.floorMod(ship.getId().hashCode(), 16) / 16.0) * HULL_SAMPLE_SECONDS;
            state.hullSampleAt = now - HULL_SAMPLE_SECONDS + stagger;
            state.hullFraction = hullFraction(ship);
            return;
        }
        if (now - state.hullSampleAt < HULL_SAMPLE_SECONDS) {
            return;
        }
        state.hullSampleAt = now;
        float current = hullFraction(ship);
        if (current < state.hullFraction - 1e-4f) {
            state.lastHullDrop = now;
        }
        state.hullFraction = current;
    }

    private static float hullFraction(ShipAPI ship) {
        float max = ship.getMaxHitpoints();
        if (!(max > 0f)) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, ship.getHitpoints() / max));
    }

    /** 返回非空字符串 = 该进 DUEL，字符串就是日志里的可读原因。 */
    private String duelReason(ShipAPI ship, Stance state, double now) {
        if (now - state.lastHullDrop <= DUEL_HIT_WINDOW) {
            return "taking-hits";
        }
        ShipAPI current = target;
        if (current == null || !usable(ship, current)) {
            return "";
        }
        if (targetIsEngagingUs(ship, current)) {
            return "aimed-at";
        }
        return inDuelBand(ship, current) ? "standoff" : "";
    }

    /** 对方正在朝我们开火：它锁的是我们，且已经选了武器组（原生 AI 只有准备动手才选组）。 */
    private static boolean targetIsEngagingUs(ShipAPI ship, ShipAPI other) {
        return other.getShipTarget() == ship && other.getSelectedGroupAPI() != null;
    }

    /** 拉锯判据：距离落在双方都能打到的带子里，且相对速度小（不是追击而是对射）。 */
    private boolean inDuelBand(ShipAPI ship, ShipAPI other) {
        float range = weapon == null ? 0f : weapon.getRange();
        if (!(range > 0f)) {
            return false;
        }
        float dx = other.getLocation().x - ship.getLocation().x;
        float dy = other.getLocation().y - ship.getLocation().y;
        float squared = dx * dx + dy * dy;
        float near = range * DUEL_NEAR_RANGE, far = range * DUEL_FAR_RANGE;
        if (squared < near * near || squared > far * far) {
            return false;
        }
        Vector2f mine = ship.getVelocity(), theirs = other.getVelocity();
        if (mine == null || theirs == null) {
            return true;
        }
        float rx = mine.x - theirs.x, ry = mine.y - theirs.y;
        float relative = (float) Math.sqrt(rx * rx + ry * ry);
        if (relative > DUEL_RELATIVE_ABS) {
            return false;
        }
        float theirSpeed = (float) Math.sqrt(theirs.x * theirs.x + theirs.y * theirs.y);
        return relative <= Math.max(30f, theirSpeed) * DUEL_RELATIVE_SPEED;
    }

    // ------------------------------------------------------------------ 点射节奏

    /** 推进扳机时钟：WINDUP(蓄力) → FIRE(照射) → COOLDOWN(冷却) → WINDUP。 */
    private void tick(WeaponAPI held, ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        /* 蓄力枪与臂炮共用这台节奏机，只是档位不同（m01502 裁决）：
           蓄力枪必须蓄满 AI_CHARGE_SECONDS(1.5s)，照射时长取同一档位的 estimate().duration()，
           于是「蓄力时间」与「伤害倍率」被绑定在同一个数上，AI 不可能无蓄力开火。 */
        boolean charging = BeamRifleModes.isCharge(held);
        float windup = charging ? BeamRifleModes.AI_CHARGE_SECONDS : WINDUP_SECONDS;
        float fireWindow = charging
                ? (float) BeamRifleModes.estimate(BeamRifleModes.AI_CHARGE_SECONDS).duration()
                : FIRE_SECONDS;
        double now = engine.getTotalElapsedTime(false);
        if (Double.isNaN(cadenceSince) || now < cadenceSince) {
            cadenceSince = now;
        }
        boolean engageable = canEngage(held, ship);
        switch (cadence) {
            case WINDUP -> {
                if (!engageable) {
                    // 没有可打的目标就不攒蓄力：把计时压在 0，目标一进射界立刻开始充能。
                    cadenceSince = now;
                    chargeRatio(held, ship, 0f);
                    return;
                }
                float progress = (float) ((now - cadenceSince) / windup);
                if (progress >= 1f) {
                    enter(Cadence.FIRE, held, ship, now);
                    return;
                }
                chargeRatio(held, ship, progress);
            }
            case FIRE -> {
                // 目标失效 / 出射程 / 出射界 / 弹尽：当帧掉回冷却，不卡在 FIRE。
                if (!engageable || now - cadenceSince >= fireWindow) {
                    enter(Cadence.COOLDOWN, held, ship, now);
                    return;
                }
                chargeRatio(held, ship, 1f);
            }
            case COOLDOWN -> {
                float span = cooldownSeconds(held);
                float elapsed = (float) (now - cadenceSince);
                if (elapsed >= span) {
                    enter(Cadence.WINDUP, held, ship, now);
                    return;
                }
                chargeRatio(held, ship, 1f - elapsed / span);
            }
            default -> throw new IllegalStateException("Unknown rifle cadence " + cadence);
        }
    }

    /** 切相：重置计时、写一次倍率、每状态最多打一条日志。 */
    private void enter(Cadence next, WeaponAPI held, ShipAPI ship, double now) {
        cadence = next;
        cadenceSince = now;
        float ratio = next == Cadence.WINDUP ? 0f : 1f;
        chargeRatio(held, ship, ratio);
        // 只对蓄力枪有意义：告诉光学层「现在是真的在照射」还是「还在蓄」。
        if (BeamRifleModes.isCharge(held)) {
            BeamRifleModes.noteAiFiring(ship, next == Cadence.FIRE);
        }
        if (TRACE) {
            logCadence(held, ship, next, ratio);
        }
    }

    /** 只有蓄力枪需要把进度写回控制器；臂炮不吃这条倍率。 */
    private void chargeRatio(WeaponAPI held, ShipAPI ship, float ratio) {
        if (held == null || !BeamRifleModes.isCharge(held)) {
            return;
        }
        BeamRifleModes.noteAiCharge(ship, ratio);
    }

    /** 冷却时长：优先原生 chargedown，读不到用 1.0s；低于 {@link #MIN_COOLDOWN_SECONDS} 按它算。 */
    private static float cooldownSeconds(WeaponAPI held) {
        float spec = specChargedown(held);
        return Math.max(MIN_COOLDOWN_SECONDS, spec);
    }

    private static float specChargedown(WeaponAPI held) {
        WeaponSpecAPI spec = held == null ? null : held.getSpec();
        if (spec == null) {
            return COOLDOWN_FALLBACK;
        }
        if (spec instanceof BeamWeaponSpecAPI beam) {
            float down = beam.getChargedownTime();
            if (Float.isFinite(down) && down > 0f) {
                return down;
            }
        }
        float shared = spec.getBeamChargedownTime();
        if (Float.isFinite(shared) && shared > 0f) {
            return shared;
        }
        return COOLDOWN_FALLBACK;
    }

    /** 当前档位对应的蓄力秒数：现场核对「AI 到底蓄了多久」的唯一入口。 */
    private static float windupSeconds(WeaponAPI held) {
        return BeamRifleModes.isCharge(held) ? BeamRifleModes.AI_CHARGE_SECONDS : WINDUP_SECONDS;
    }

    /** 每武器每状态只打一条：cadence 状态切换本身不会被刷屏。 */
    private void logCadence(WeaponAPI held, ShipAPI ship, Cadence state, float ratio) {
        switch (state) {
            case WINDUP -> {
                if (loggedWindup) {
                    return;
                }
                loggedWindup = true;
            }
            case FIRE -> {
                if (loggedCadenceFire) {
                    return;
                }
                loggedCadenceFire = true;
            }
            case COOLDOWN -> {
                if (loggedCooldown) {
                    return;
                }
                loggedCooldown = true;
            }
            default -> {
                return;
            }
        }
        Global.getLogger(BeamRifleAutofireAI.class).info(LOG_TAG + "cadence " + state
                + String.format(Locale.ROOT, " charge=%.2f", ratio)
                + " weapon=" + held.getId() + " ship=" + describe(ship));
    }

    // ------------------------------------------------------------------ 目标与射界

    /** 开火条件（不含节奏）：武器可用、目标仍是可打的舰、在射程与射界内。 */
    private boolean canEngage(WeaponAPI held, ShipAPI ship) {
        if (held.isDisabled() || held.isForceDisabled() || held.isDecorative()) {
            return false;
        }
        if (held.getSlot() == null) {
            return false;
        }
        if (ship.getFluxTracker().isOverloadedOrVenting()) {
            return false;
        }
        if (held.usesAmmo() && held.getAmmo() <= 0) {
            return false;
        }
        ShipAPI current = target;
        if (current == null || !usable(ship, current)) {
            return false;
        }
        float range = held.getRange() * RANGE_SLACK;
        if (range <= 0f) {
            return false;
        }
        float dx = current.getLocation().x - held.getLocation().x;
        float dy = current.getLocation().y - held.getLocation().y;
        if (dx * dx + dy * dy > range * range) {
            return false;
        }
        return inArc(held, dx, dy);
    }

    /** 先认原生锁定（玩家/AI 已经选中的那个），再退化为射程内最近的敌对舰。 */
    private ShipAPI pick(ShipAPI ship, WeaponAPI held) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return null;
        }
        float range = held.getRange();
        float bestSquared = range * range;
        if (bestSquared <= 0f) {
            return null;
        }
        ShipAPI locked = ship.getShipTarget();
        if (locked != null && usable(ship, locked)) {
            float dx = locked.getLocation().x - held.getLocation().x;
            float dy = locked.getLocation().y - held.getLocation().y;
            if (dx * dx + dy * dy <= bestSquared) {
                return locked;
            }
        }
        ShipAPI best = null;
        Vector2f origin = held.getLocation();
        for (ShipAPI other : engine.getShips()) {
            if (!usable(ship, other)) {
                continue;
            }
            float dx = other.getLocation().x - origin.x;
            float dy = other.getLocation().y - origin.y;
            float squared = dx * dx + dy * dy;
            if (squared > bestSquared) {
                continue;
            }
            if (!inArc(held, dx, dy)) {
                continue;
            }
            bestSquared = squared;
            best = other;
        }
        return best;
    }

    /** 可打的舰：活着的、敌对阵营的、不是自己的、不是残骸。 */
    private boolean usable(ShipAPI ship, ShipAPI other) {
        if (other == null || other == ship) {
            return false;
        }
        if (!other.isAlive() || other.isHulk() || other.wasRemoved()) {
            return false;
        }
        if (other.getOwner() == ship.getOwner()) {
            return false;
        }
        return other.getOwner() != 100;
    }

    private boolean inArc(WeaponAPI held, float dx, float dy) {
        float arc = held.getArc();
        if (arc <= 0f || arc >= 360f) {
            return true;
        }
        double absolute = Math.toDegrees(Math.atan2(dy, dx));
        double delta = absolute - held.getCurrAngle();
        delta = ((delta + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return Math.abs(delta) <= arc * 0.5f * ARC_SLACK;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : value > 1f ? 1f : value;
    }

    private static String describe(ShipAPI ship) {
        if (ship == null) {
            return "none";
        }
        return ship.getHullSpec().getHullId() + "(" + ship.getName() + ")";
    }
}
