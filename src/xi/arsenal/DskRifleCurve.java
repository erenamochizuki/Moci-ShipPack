package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;

/** DS柯西专用蓄力狙击速度曲线。
 *
 * <h3>动作语义（按设计口径）</h3>
 * <ol>
 *   <li><b>蓄力期间不动</b>——姿态层在 HOLDING 阶段主动清场，这段不归本类管。</li>
 *   <li><b>松手瞬间</b>开始表演：先向左侧身，同时右手往左收（TURN_CROSS）。</li>
 *   <li><b>然后右手往右甩出</b>对准敌舰（WHIP_AIM）——这是全程最快的一拍。</li>
 *   <li><b>锁定</b>（AIM_HOLD），光束在这一拍之后才点亮。</li>
 * </ol>
 *
 * <p>本类只改**速度曲线形状**，不改任何角度、时长、相位。原版 {@code smooth()}
 * 的峰值斜率 1.5，看起来是匀速滑过去；这里让每拍有自己的加速段。
 *
 * <p><b>隔离原则</b>：只在 {@code hullId == Moci_DSK_105} 时生效，其余机体（原柯西 / 佩涅罗佩 /
 * Z 高达 / 海牛重炮）一律走 {@code RifleFiringPose} 原实现。
 */
public final class DskRifleCurve {

    /** DS柯西的 skinHullId。 */
    public static final String HULL = "Moci_DSK_105";

    private DskRifleCurve() { }

    public static boolean isDsk(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) return false;
        if (HULL.equals(ship.getHullSpec().getHullId())) return true;
        return HULL.equals(ship.getHullSpec().getBaseHullId());
    }

    /** DS柯西专用节拍：**十八米级的重量**（总长 .92 → 2.20，发布版 2.4 倍）。
     *
     * <p>上一版 1.80 还是快，问题不在总长，在<b>峰值</b>：侧身只有 0.42 秒里有明显角速度。
     *  这一版两头都改了——段更长，曲线也更平（见 turnCurve）。
     * <ul>
     *   <li>TURN .82 —— 侧身，近一秒的时间里慢慢把 28° 转完，中间没有"窜"的加速段。</li>
     *   <li>HOLD .46 —— 身体到位后<b>完全静止半秒</b>，右手还没抬。第一拍停顿。</li>
     *   <li>WHIP .34 —— 最短的一拍。绷住 18% 再甩到 <b>1.10 冲过头</b>。</li>
     *   <li>AIM .58 —— 1.10 → .990 → 1.000 慢慢回弹，最后 .20 秒（34%）<b>绝对静止</b>，
     *       光束在这一段里点亮。</li>
     * </ul>
     * 慢段 1.28 / 快段 0.34 = 3.8:1。整段约 0.66 秒是真正的不动。 */
    public static final float TURN = .82f, HOLD = .46f, WHIP = .34f, AIM = .58f;

    /** 越限（破限）时按 power 拉长。**放大系数比发布版温和**：大机基础时长已经翻倍，
     *  再按发布版 1.75~3.0 倍 pause 会把破限拖到四秒以上，节奏就散了。 */
    public static float[] presentation(float power) {
        float p = Float.isFinite(power) ? Math.max(0, Math.min(1, power)) : 0f;
        float move = p > 0 ? 1.10f + .25f * p : 1f;
        float pause = p > 0 ? 1.20f + .35f * p : 1f;
        return new float[]{ TURN * move, HOLD * pause, WHIP * move, AIM * pause };
    }
    /** 转成姿态层的 Presentation，供 RifleFiringPose 按 hull 分流时使用。 */
    public static RifleFiringPose.Presentation toPresentation(float power) {
        float[] p = presentation(power);
        return new RifleFiringPose.Presentation(p[0], p[1], p[2], p[3]);
    }
    /** 拍1 侧身（大机）：前 22% 只走 3% 的启动迟滞 → 中段<b>接近匀速</b>（30% S 形 + 70% 直线，
     *  峰值角速度比纯 smooth 低 23%）→ 顶到 1.04 → 最后 .16 秒压实到精确 1.000。
     *
     * <p>为什么用近直线而不是 S 形：十八米的身体转起来是<b>恒定角速度</b>，中间不该有"窜"的加速。
     *  S 形曲线中间陡、两头空，看起来像小机在抽搐。
     *
     *  f(0)=0、f(1)=1.000（精确，不能渐近，否则 readyForBeam / onNativeFirstLight 会卡死）。 */
    public static float turnCurve(float t) {
        float u = clamp01(t);
        if (u < .22f) return .030f * (float) Math.pow(u / .22f, 3);
        if (u < .84f) {
            float v = (u - .22f) / .62f;
            return .030f + (1.04f - .030f) * (.30f * smooth(v) + .70f * v);
        }
        return 1.04f - .04f * smooth((u - .84f) / .16f);
    }

    /** 拍3 甩枪（大机）：前 18% 绷住只走 2.8%（蓄势），之后 easeOut 2.2 甩到 1.10 冲过头。
     *  这一拍就是要比侧身陡——重的东西慢慢转身，但手臂一甩是快的。
     *  f(0)=0、f(1)=1.10。 */
    public static float whipCurve(float t) {
        float u = clamp01(t);
        if (u < .18f) return 1.10f * .028f * (float) Math.pow(u / .18f, 3);
        float v = (u - .18f) / .82f;
        return .0308f + (1.10f - .0308f) * (1 - (float) Math.pow(1 - v, 2.2));
    }

    /** 拍4 锁定（大机）：1.10 慢慢落到 .990 → 再微调到精确 1.000 → 之后 34% 时间<b>绝对静止</b>。
     *  回弹占 42%+24%，比小机慢得多——大机的惯性要花时间才停得下来；停住之后一动不动。
     *  f(0)=1.10（与 whipCurve(1) 连续，接缝不跳）、f(1)=1.000 精确。 */
    public static float settleCurve(float t) {
        float u = clamp01(t);
        if (u < .42f) return 1.10f - .110f * smooth(u / .42f);
        if (u < .66f) return .990f + .010f * smooth((u - .42f) / .24f);
        return 1f;
    }

    private static float clamp01(float n) { return Float.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0; }
    private static float smooth(float x) { float t = clamp01(x); return t * t * (3 - 2 * t); }
}
