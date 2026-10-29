package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;

/** DSZ高达专用军刀动作曲线 —— 按动作语义设计速度，不套通用缓动。
 *
 * <h3>设计原则</h3>
 * 原版 {@link ZetaSaberMotion} 三段挥砍各自用 smoothstep（t²(3-2t)）插值：两端速度归零、
 * 峰值斜率仅 1.5，观感是"匀速滑过去"，没有发力感。本类按每一段的物理意图重新配速度：
 *
 * <table>
 *   <tr><th>阶段</th><th>动作语义</th><th>速度设计</th></tr>
 *   <tr><td>DRAW 收刀</td><td>蓄势，慢慢把刀抬起来</td><td>t^1.5 缓起，留出"还没打"的呼吸</td></tr>
 *   <tr><td>WARMUP 提刀前刺</td><td><b>先顿住蓄力，再一下刺出去</b></td><td>前 55% 路程完全冻结，后 45% 用 u^4 爆发</td></tr>
 *   <tr><td>STAB 刺中保持</td><td>刺到底的瞬间定住</td><td>零位移，纯停顿</td></tr>
 *   <tr><td>RIGHT_CUT 右切</td><td>借回身之势横切</td><td>t^3 加速度曲线，角速度 3t²</td></tr>
 *   <tr><td>LEFT_SLASH 左大划砍</td><td>最重的一刀，越挥越快</td><td>t^4 加速度曲线，角速度 4t³</td></tr>
 *   <tr><td>RECOVERY 收势</td><td>惯性收住</td><td>1-(1-t)² 减速曲线</td></tr>
 * </table>
 *
 * <p>关键不是"加了缓动"，而是<b>每段的速度曲线由动作意图决定</b>：
 * 该停的地方停死，该爆的地方爆发，该收的地方自然减速。
 *
 * <p>隔离原则：原类一个字节都不改，非 DSZ 机体立刻回落原实现。
 */
public final class DszSaberCurve {

    /** DSZ高达的 skinHullId。 */
    public static final String HULL = "DSZ_006";

    /** 曲线档位：0=原版对照 1=按动作逻辑（默认） 2=统一加速 3=统一爆发 */
    public static final int LEGACY = 0, LOGIC = 1, ACCEL = 2, BURST = 3;

    private static final int DEFAULT_MODE = LOGIC;

    // 分段点与原版一致，只换速度曲线，不改节拍
    private static final float STAB_END = .22f, CUT_END = .57f, HOLD_END = .63f;
    private static final float SWEEP_END = .88f;

    /** 前刺爆发点：这段进度前完全冻结（蓄力停顿）。 */
    private static final float THRUST_HOLD = .40f;

    /** 刺出时身体只转到侧身极限的这个比例，给右切的"继续右转"留余量。 */
    private static final float STAB_BODY_RATIO = 1.35f;

    /** 左划砍收尾时躯干停在中间偏左（不是原来的满侧身）。 */
    private static final float SWEEP_BODY = 28f;

    /** 左砍刀的终点角（相对船体，正数=向左）。
     *  原本用满 halfArc(47.5)，叠加躯干后会甩到世界角 ~157°，指到左后方去过；
     *  收到 8° 后落点约 104°，正好在"左上、不过头"的位置。 */
    private static final float SWEEP_BLADE_END = 72f;   // 比刀槽默认 47.5 更大，左砍更远
    /** 右切时躯干能压到的右极限（大于骨架默认 28，专门为扩大挥砍范围放开）。 */
    private static final float CUT_BODY_MAX = 56f;

    private DszSaberCurve() { }

    public static int mode() {
        String raw = System.getProperty("dsz.saber.curve");
        if (raw == null || raw.isEmpty()) return DEFAULT_MODE;
        try {
            int v = Integer.parseInt(raw.trim());
            return v >= LEGACY && v <= BURST ? v : DEFAULT_MODE;
        } catch (NumberFormatException ignored) {
            return DEFAULT_MODE;
        }
    }

    public static boolean isDsz(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) return false;
        String id = ship.getHullSpec().getHullId();
        if (HULL.equals(id)) return true;
        return HULL.equals(ship.getHullSpec().getBaseHullId());
    }

    // ---------- 速度曲线：每段一个，按动作语义 ----------

    /** 收刀：缓起。刀慢慢抬，给玩家"要来了"的预告。 */
    private static float drawCurve(float t) {
        float u = clamp01(t);
        return (float) Math.pow(u, 1.5);
    }

    /** 前刺：先顿住蓄力，再爆发刺出。前 THRUST_HOLD 冻结，之后 u^4 猛推。 */
    private static float thrustCurve(float t) {
        float u = clamp01(t);
        if (u < THRUST_HOLD) return 0f;
        float v = (u - THRUST_HOLD) / (1 - THRUST_HOLD);
        // smootherstep：起步有蓄、中段最猛、末端稳落，比纯平方自然
        return v * v * v * (v * (v * 6 - 15) + 10);
    }

    /** 右切是蓄力段：刀和身体一起慢慢压紧，不能抢在身体前面。
     *  用 t^1.4 而非 t³ —— t³ 会让刀冲到身体前面，蓄力感就散了。 */
    private static float cutCurve(float t) {
        return bodyPress(t);
    }

    /** 左大划砍：最重的一刀，t⁴，角速度 4t³。 */
    private static float sweepCurve(float t) {
        float u = clamp01(t);
        float u2 = u * u;
        return u2 * u2;
    }

    /** 右压蓄力：身体比刀慢半拍，慢慢把角度压到极限（近似匀速偏后重）。 */
    private static float bodyPress(float t) {
        float u = clamp01(t);
        return (float) Math.pow(u, 1.4);
    }
    /** 身体甩出（鞭子）：前 15% 绷住只走 5%，随后 easeOut4 一帧跨掉七成。
     *  躯干是鞭柄、刀是鞭梢——柄先到，梢后追。 */
    private static float bodyLead(float t) {
        float u = clamp01(t);
        if (u < .15f) return (float) Math.pow(u / .15f, 4) * .05f;
        float v = (u - .15f) / .85f;
        return .05f + .95f * (1 - (float) Math.pow(1 - v, 4.0));
    }

    /** 刀（鞭梢）：被身体拖着走，前 35% 只走 3%，之后 easeOut2.5 追上极限。 */
    private static float bladeFollow(float t) {
        float u = clamp01(t);
        if (u < .35f) return (float) Math.pow(u / .35f, 4) * .03f;
        float v = (u - .35f) / .65f;
        return .03f + .97f * (1 - (float) Math.pow(1 - v, 2.5));
    }
    /** 收势：惯性减速 1-(1-t)²，刀被"收住"而不是硬停。 */
    private static float recoverCurve(float t) {
        float u = clamp01(t);
        float r = 1 - u;
        float r2 = r * r;
        return 1 - r2 * r;   // easeOut3：前半段就回到七成，后面只做微收，收势不拖
    }

    /** 对照档位用的通用曲线。 */
    private static float generic(int mode, float t) {
        float u = clamp01(t);
        switch (mode) {
            case LEGACY: return u * u * (3 - 2 * u);
            case ACCEL:  return u * u;
            case BURST:  return 1 - (1 - u) * (1 - u);
            default:     return u;
        }
    }

    // ---------- 姿态接口 ----------

    /** 刀刃相对剑柄的夹角系数。
     *  1 = 完全跟随躯干（斜）；0 = 与剑柄共线（直）。
     *  在 left-slash 的后半段用 smoothstep 归零，让两套规则平滑交接。 */
    private static float tiltFade(float progress) {
        float p = clamp01(progress);
        if (p <= .63f) return 1f;          // 挥砍段：斜
        if (p >= .85f) return 0f;          // 接近收势：共线
        float u = (p - .63f) / .22f;
        return 1f - u * u * (3 - 2 * u);   // 0.63 → 0.85 归零，无跳变
    }
    /** 起手准备（收刀 → 提刀前刺）。非 DSZ 回落原实现。 */
    public static ZetaSaberMotion.Frame preparation(ShipAPI ship, float charge, float side,
                                                    float aim, float halfArc, float start) {
        if (!isDsz(ship)) return ZetaSaberMotion.preparation(charge, side, aim, halfArc, start);
        if (!finite(charge, side, aim, halfArc, start) || halfArc <= 0 || halfArc > 47.5f
                || side > 0 || side < -ZetaSaberMotion.BODY_SIDE) {
            throw new IllegalArgumentException("Finite native saber preparation required");
        }
        int m = mode();
        float c = clamp01(charge);
        float target = clampAngle(aim, halfArc);
        float draw = clampAngle(target - ZetaSaberMotion.DRAW_BLADE, halfArc);
        float body = Math.min(ZetaSaberMotion.DRAW_BODY,
                Math.max(0, ZetaSaberMotion.MAX_SHOULDER_RELATIVE + draw));
        float drawFraction = ZetaSaberMotion.DRAW_FRACTION;

        if (c < drawFraction) {
            // 收刀：慢
            float t = c / drawFraction;
            float q = (m == LOGIC) ? drawCurve(t) : generic(m, t);
            return new ZetaSaberMotion.Frame(ZetaSaberMotion.Stage.DRAW, body * q,
                    mix(clampAngle(start, halfArc), draw, q), c);
        }
        // 提刀前刺：只有 LOGIC 档才走"停顿 + 爆发"
        float t = (c - drawFraction) / (1 - drawFraction);
        float q = (m == LOGIC) ? thrustCurve(t) : generic(m, t);
        // LOGIC 档：刺出停在部分侧身，右切才有"继续右转"的空间
        float endBody = (m == LOGIC) ? side * STAB_BODY_RATIO : side;
        return new ZetaSaberMotion.Frame(ZetaSaberMotion.Stage.WARMUP, mix(body, endBody, q),
                mix(draw, target, q), c);
    }

    /** 主挥砍：前刺保持 → 右切 → 短停 → 左划砍。非 DSZ 回落原实现。 */
    public static ZetaSaberMotion.Frame sequence(ShipAPI ship, float progress, float side,
                                                 float aim, float halfArc) {
        if (!isDsz(ship)) return ZetaSaberMotion.sequence(progress, side, aim, halfArc);
        if (!finite(progress, side, aim, halfArc) || halfArc <= 0 || halfArc > 47.5f
                || side > 0 || side < -ZetaSaberMotion.BODY_SIDE) {
            throw new IllegalArgumentException("Finite original saber articulation required");
        }
        int m = mode();
        float p = clamp01(progress);
        float start = clampAngle(aim, halfArc);

        boolean logic = m == LOGIC;
        float stabBody = logic ? side * STAB_BODY_RATIO : side;
        float bodyMax = CUT_BODY_MAX;

        // ① 前刺保持：刀已经刺出去，定住不动
        if (p < STAB_END) {
            return new ZetaSaberMotion.Frame(ZetaSaberMotion.Stage.STAB, stabBody, start, p);
        }
        // ② 右切：刀向右扫的同时，躯干继续缓慢右压——像把弓弦再拉深一截。
        //    速度比刀慢，所以观感是"刀先动、身体随后压紧"，蓄力感全靠这一段攒。
        if (p < CUT_END) {
            float t = (p - STAB_END) / (CUT_END - STAB_END);
            if (logic) {
                return new ZetaSaberMotion.Frame(ZetaSaberMotion.Stage.RIGHT_CUT,
                        mix(stabBody, -bodyMax, bodyPress(t)), mix(start, -halfArc, cutCurve(t)), p);
            }
            float q = generic(m, t);
            return new ZetaSaberMotion.Frame(ZetaSaberMotion.Stage.RIGHT_CUT,
                    side * (1 - q), mix(start, -halfArc, q), p);
        }
        if (p < HOLD_END) {
            // 短停：身体已压到最右、刀在胸前朝右，张力拉满
            float holdBody = logic ? -bodyMax : 0f;
            return new ZetaSaberMotion.Frame(ZetaSaberMotion.Stage.RIGHT_CUT, holdBody, -halfArc, p);
        }
        // ③ 左划砍：躯干先甩过去，手臂滞后追赶，最后手臂才砍到底
        float t = (p - HOLD_END) / (SWEEP_END - HOLD_END);
        if (logic) {
            float bq = bodyLead(t), fq = bladeFollow(t);
            return new ZetaSaberMotion.Frame(ZetaSaberMotion.Stage.LEFT_SLASH,
                    mix(-bodyMax, SWEEP_BODY, bq), mix(-halfArc, SWEEP_BLADE_END, fq), p);
        }
        float q = generic(m, t);
        return new ZetaSaberMotion.Frame(ZetaSaberMotion.Stage.LEFT_SLASH,
                ZetaSaberMotion.FINAL_LEFT_BODY * q, mix(-halfArc, halfArc, q), p);
    }

    /** 手臂世界瞄准角。
     *
     * <p>原实现传 {@code facing + bladeRelative}，而 {@code ZetaTetherPose.armAngle} 在
     * 满权重时直接返回该值 —— 躯干转多少都不影响手臂，挥砍范围永远被刀槽 ±47.5° 锁死。
     * DSZ 这里把躯干角一并加进世界角，躯干往右压 52° 时刀的世界可达角同步外扩 52°，
     * 这才是"身体带动刀"真正扩大范围。
     */
    /** 剑柄（手臂）世界角。**与刀刃特效同角**，两者永远一条线。
     *
     * <p>由特效方向反推剑柄：挥砍段叠加 body×fade（所以剑柄也跟着倾斜），
     * 前刺/收势 fade=0（所以刺得直、收得正）。剑柄不再"自己走自己的"。
     */
    public static float armAim(ShipAPI ship, float body, float bladeRelative,
                               ZetaSaberMotion.Stage stage, float progress) {
        float facing = ship == null ? 0f : ship.getFacing();
        boolean tilts = stage == ZetaSaberMotion.Stage.RIGHT_CUT
                || stage == ZetaSaberMotion.Stage.LEFT_SLASH;
        // 和刀刃特效共用同一个 tiltFade，两者夹角恒为零、永不脱节
        return facing + bladeRelative + (tilts ? body * tiltFade(progress) : 0f);
    }
    /** 刀身视觉角（世界）。**只给渲染层用，不改武器判定角。**
     *
     * <p>手臂骨架保持自己的瞄准角（facing+blade，独立于躯干），但刀刃光束要跟着躯干一起转，
     * 这样挥砍弧才会随侧身外扩。前刺/准备/收势阶段不加躯干角，保证刺出是笔直的。
     */
    public static float bladeVisualAngle(WeaponAPI weapon) {
        if (weapon == null) return 0f;
        ShipAPI ship = weapon.getShip();
        float base = weapon.getCurrAngle();
        if (ship == null || !isDsz(ship)) return base;
        Object owner = ship.getCustomData().get(ZetaSaberMotion.KEY + ".owner");
        if (!(owner instanceof ZetaSaberMotion saber)) return base;
        java.util.Map<String, Object> status = saber.status();
        String stage = String.valueOf(status.get("stage"));
        // 挥砍段刀刃跟着躯干倾（这个倾斜感是对的）；收势不加，保证收尾回到笔直，
        // 也不会再出现"最后一刀往外偏"。
        if (!"RIGHT_CUT".equals(stage) && !"LEFT_SLASH".equals(stage)) return base;
        Object body = status.get("bodyDegrees");
        float b = body instanceof Number n ? n.floatValue() : 0f;
        Object prog = status.get("burstProgress");
        float p = prog instanceof Number n2 ? n2.floatValue() : 1f;
        // 两套规则在同一个公式里连续切换：
        //   挥砍前段 → 夹角系数 1（刀比剑柄更斜，观感好看）
        //   砍向终点 → smoothstep 归 0（自然融回共线，不跳变）
        return base + b * tiltFade(p);
    }
    /** 军刀槽原始角度记忆（每把武器一份），用于恢复。 */
    private static final java.util.Map<WeaponAPI, Float> SLOT_BASE_ANGLE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    /** 让军刀槽的**射界弧（黄色扇形）**跟着躯干转。
     *
     * <p>引擎里 {@code getArcFacing()} 直接返回 {@code slot.getAngle()}，与武器
     * {@code getCurrAngle()} 无关 —— 这就是"身体扭了、扇形不动"的根因。
     * 只能改槽位角度本身，扇形才会跟。
     */
    public static void applySlotArc(WeaponAPI weapon, float body) {
        if (weapon == null) return;
        ShipAPI ship = weapon.getShip();
        if (ship == null || !isDsz(ship)) return;
        WeaponSlotAPI slot = weapon.getSlot();
        if (slot == null) return;
        Float base = SLOT_BASE_ANGLE.get(weapon);
        if (base == null) { base = slot.getAngle(); SLOT_BASE_ANGLE.put(weapon, base); }
        float want = base + body;
        if (Math.abs(slot.getAngle() - want) > .0001f) slot.setAngle(want);
    }

    /** 归还槽位角度，避免跨局残留。 */
    public static void clearSlotArc(WeaponAPI weapon) {
        if (weapon == null) return;
        Float base = SLOT_BASE_ANGLE.remove(weapon);
        WeaponSlotAPI slot = weapon.getSlot();
        if (base != null && slot != null) slot.setAngle(base);
    }
    /** 收势终点刀角。DSZ 回 0（相对正前），收完是笔直的；
     *  原版仍回 {@code startBlade}（军刀静态角，约 -9.7°），会停在略偏的位置。 */
    public static float recoveryEndBlade(ShipAPI ship, float startBlade) {
        return isDsz(ship) ? 0f : startBlade;
    }
    /** 收势曲线，供原类 RECOVERY 段使用。非 DSZ 返回原 smoothstep。 */
    public static float recovery(ShipAPI ship, float t) {
        if (!isDsz(ship)) {
            float u = clamp01(t);
            return u * u * (3 - 2 * u);
        }
        int m = mode();
        return (m == LOGIC) ? recoverCurve(t) : generic(m, t);
    }

    public static String describe(int mode) {
        switch (mode) {
            case LEGACY: return "原版平滑（对照）";
            case LOGIC:  return "动作逻辑曲线（停顿·爆发·递进加速）";
            case ACCEL:  return "统一 t² 加速";
            case BURST:  return "统一爆发减速";
            default: return "未命名";
        }
    }

    private static float clamp01(float n) { return Float.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0; }
    private static float clampAngle(float n, float half) { return Math.max(-half, Math.min(half, n)); }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static boolean finite(float... values) {
        for (float v : values) if (!Float.isFinite(v)) return false;
        return true;
    }
}
