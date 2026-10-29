package xi.arsenal;

import com.fs.starfarer.api.combat.ShipAPI;

/** DS柯西蓄力狙击 · V2「六层」版本。
 *
 * <p><b>为什么有 V2</b>：V1（{@code DskRifleCurve}）只解决了"快"——
 * 把总长拉到 2.20 秒、把侧身曲线压成近匀速。但动作仍然单薄，病根不在速度，
 * 在<b>层次</b>：{@code RifleFiringPose.apply()} 里头部、左手、肩甲、背包、腿
 * 全部吃同一个 {@code bodyBlend()}，整台机像一块板在转。
 *
 * <p>V2 在 V1 的节拍上叠六层：
 * <ol>
 *   <li><b>头部先行</b> —— {@link #headCurve} 在 72% 处就到位，身体还在转。</li>
 *   <li><b>预备回拉</b> —— {@link #anticipate} 在停顿段末尾把右手压下 -5%、身体缩 1.5%。</li>
 *   <li><b>后坐余韵</b> —— {@link #recoilPitch} 阻尼振荡：光束点亮瞬间后仰 3.4° 再弹回。</li>
 *   <li><b>左手反向</b> —— {@link #leftCounter} 侧身时张开、甩枪时往后甩。</li>
 *   <li><b>肩甲背包拖尾</b> —— {@link #dragBlend} 让重部件晚到。</li>
 *   <li><b>蓄力微颤</b> —— {@link #shiver} 越接近满蓄，机身 ±0.3° 高频抖。</li>
 * </ol>
 *
 * <p><b>接缝纪律</b>：每段的端点必须和相邻段严丝合缝，否则动作会"跳"。
 * <pre>
 *   anticipate(1) = -.05  ==  whipCurve(0)
 *   whipCurve(1)  = 1.10  ==  settleCurve(0)
 *   turnCurve(1)  = 1.000 （精确，readyForBeam / onNativeFirstLight 的硬闸门）
 *   headCurve(1)  = 1.000 （精确）
 *   settleCurve(1) = 1.000（精确）
 * </pre>
 *
 * <p><b>隔离</b>：只在 {@code hullId/baseHullId == Moci_DSK_105} 时生效。
 * 原柯西 / 佩涅罗佩 / Z 高达 / 海牛重炮一律走 {@code RifleFiringPose} 原实现。
 */
public final class DskRifleCurveV2 {

    /** DS柯西的 skinHullId。 */
    public static final String HULL = "Moci_DSK_105";

    private DskRifleCurveV2() { }

    public static boolean isDsk(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) return false;
        if (HULL.equals(ship.getHullSpec().getHullId())) return true;
        return HULL.equals(ship.getHullSpec().getBaseHullId());
    }

    // ── 节拍 ────────────────────────────────────────────────────────────────
    /** 四拍总长 2.20 秒。预备回拉不单独占拍，塞在 HOLD 段的后 45%。 */
    public static final float TURN = .82f, HOLD = .46f, WHIP = .34f, AIM = .58f;

    /** 破限时按 power 拉长；系数比发布版温和（基础时长已经够长了）。 */
    public static float[] presentation(float power) {
        float p = Float.isFinite(power) ? Math.max(0, Math.min(1, power)) : 0f;
        float move = p > 0 ? 1.10f + .25f * p : 1f;
        float pause = p > 0 ? 1.20f + .35f * p : 1f;
        return new float[]{ TURN * move, HOLD * pause, WHIP * move, AIM * pause };
    }
    public static RifleFiringPose.Presentation toPresentation(float power) {
        float[] p = presentation(power);
        return new RifleFiringPose.Presentation(p[0], p[1], p[2], p[3]);
    }

    // ── 第 1 层：身体 ───────────────────────────────────────────────────────
    /** 侧身：前 22% 迟滞 3%，中段近匀速（30% S + 70% 直线），末尾压实 1.04 → 1.000。 */
    public static float turnCurve(float t) {
        float u = clamp01(t);
        if (u < .22f) return .030f * (float) Math.pow(u / .22f, 3);
        if (u < .84f) {
            float v = (u - .22f) / .62f;
            return .030f + (1.04f - .030f) * (.30f * smooth(v) + .70f * v);
        }
        return 1.04f - .04f * smooth((u - .84f) / .16f);
    }

    /** 第 1 层（头）：72% 处就转到位，剩下的时间身体还在转 —— "头先看，身后来"。
     *  f(0)=0、f(1)=1.000 精确。 */
    public static float headCurve(float t) {
        float u = clamp01(t);
        float v = Math.min(1f, u / .72f);
        return .35f * smooth(v) + .65f * v;
    }

    // ── 第 2 层：预备回拉 ───────────────────────────────────────────────────
    /** 停顿段末尾的"缩"：右手从 0 压到 -5%，身体在别处同步缩 1.5°。
     *  没有这一下，甩枪就是平白无故甩出去的。
     *  f(0)=0、f(1)=-.05（与 {@link #whipCurve} 起点连续）。 */
    public static float anticipate(float t) {
        return -.05f * smooth(clamp01(t));
    }

    // ── 第 3 层：甩枪 ───────────────────────────────────────────────────────
    /** 从 -5% 继续压到 -7%（蓄势），然后 easeOut 2.2 甩到 1.10 冲过头。
     *  f(0)=-.05、f(1)=1.10。 */
    public static float whipCurve(float t) {
        float u = clamp01(t);
        if (u < .20f) return -.05f - .02f * smooth(u / .20f);
        float v = (u - .20f) / .80f;
        return -.07f + (1.10f - -.07f) * (1 - (float) Math.pow(1 - v, 2.2));
    }

    /** 锁定：1.10 → .990 → 1.000，最后 34% 绝对静止。
     *  f(0)=1.10（接缝）、f(1)=1.000 精确。 */
    public static float settleCurve(float t) {
        float u = clamp01(t);
        if (u < .42f) return 1.10f - .110f * smooth(u / .42f);
        if (u < .66f) return .990f + .010f * smooth((u - .42f) / .24f);
        return 1f;
    }

    // ── 第 5 层：肩甲 / 背包拖尾 ────────────────────────────────────────────
    /** 重的部件跟不上身体：中段落后，末端刚好收口（不超过 1.000，否则会永久偏）。
     *  f(0)=0、f(1)=1.000 精确。 */
    public static float dragBlend(float bodyBlend) {
        float b = clamp01(bodyBlend);
        return Math.min(1f, (float) Math.pow(b, 1.25) * 1.02f);
    }

    // ── 第 4 层：左手反向平衡 ───────────────────────────────────────────────
    /** t = presentElapsed / 总时长（0..1）。
     *  侧身段张开到 -9°（反向配重），甩枪段往后甩到 +6°，锁定段收到 +2°。
     *  f(0)=0、f(1)=2°（收尾残差，不归零更自然——手臂还挂着劲）。 */
    public static float leftCounter(float t) {
        float u = clamp01(t);
        if (u < .37f) return -9f * smooth(u / .37f);
        if (u < .63f) return -9f;
        if (u < .78f) return -9f + 15f * smooth((u - .63f) / .15f);
        return 6f - 4f * smooth((u - .78f) / .22f);
    }

    // ── 第 3 层：后坐余韵 ───────────────────────────────────────────────────
    /** 光束点亮瞬间机身后仰，阻尼振荡回位。
     *  age = FIRING 阶段已过秒数。返回角度（负 = 后仰）。 */
    public static float recoilPitch(float age) {
        if (!Float.isFinite(age) || age < 0) return 0f;
        if (age > 1.2f) return 0f;
        float rise = Math.min(1f, age / .05f);
        float damp = (float) Math.exp(-5.5 * age);
        return -3.4f * rise * damp * (float) Math.cos(age * 13.0);
    }

    // ── 第 6 层：蓄力电容震颤 ───────────────────────────────────────────────
    /** 越接近满蓄，机身抖得越明显。视觉上仍然"没动"，但能看出能量在堆。
     *  @param hold 已蓄力秒数；@param ratio 蓄力比例 0..1 */
    public static float shiver(float hold, float ratio) {
        if (!Float.isFinite(hold) || hold <= 0) return 0f;
        float gain = Math.min(1f, Math.max(0f, Float.isFinite(ratio) ? ratio : 0f));
        float amp = .30f * gain;
        return amp * (float) Math.sin(hold * 47.0) * (.60f + .40f * (float) Math.sin(hold * 11.0));
    }

    // ── 第 7 层：俯视角专用（腿站住 / 分层延迟 / 反向预备）────────────────────
    /** 腿跟随躯干的比例。躯干转满、腿只跟这么多 —— 纯俯视角里这是「扭腰」，
     *  也是机甲最好看的姿态。取值保守（.55）是为了不让腰部和腿部贴图裂开，
     *  如果实拍不穿模可以继续往下压到 .40。 */
    public static final float LEG_FOLLOW = .55f;

    /** 分层延迟（秒）：肩甲 / 背包比躯干晚到。重的部件跟不上身体。 */
    public static final float SHOULDER_DELAY = .10f, BACKPACK_DELAY = .14f;

    /** 反向预备的最大偏航（度，负 = 与侧身方向相反）。甩枪前先把腰往反方向拧。 */
    public static final float ANTICIPATE_YAW = -3.5f;

    /** 反向预备曲线：0 → -1，乘 {@link #ANTICIPATE_YAW} 使用。
     *  停顿段前 55% 不动，后 45% 拧到 -1；甩枪段前 30% 弹回 0（借这股回弹起甩）。 */
    public static float anticipateYaw(float holdProgress) {
        return -smooth(clamp01(holdProgress));
    }

    /** 分层延迟的归一化进度：elapsed 已过 delay 秒时才开始走，且在 turnCross 处刚好到 1。 */
    public static float delayedProgress(float elapsed, float turnCross, float delay) {
        if (!(turnCross > 0)) return 0f;
        float span = Math.max(Float.MIN_NORMAL, turnCross - delay);
        return clamp01((elapsed - delay) / span);
    }

    // ── 归位（CLOSING）─────────────────────────────────────────────────────
    /** 收招时长。发布版 {@code CLOSE_SECONDS = .18 s}——2.2 秒攒出来的侧身姿态 0.18 秒就拉回去，
     *  那是"闪回去"不是"收回来"。这里放到 .90 秒，和摆出去的 .82 秒同量级。 */
    public static final float RECOVERY = .90f;

    /** 归位曲线：0 → 1（1 = 完全归位）。
     *
     * <p>三段：前 18% 只回 6%（卸力迟滞，机体先"松"一下才动）→ 中段 35% S + 65% 直线
     *（近匀速，十八米的身体不会加速甩回来）→ 末尾 14% 压实到精确 1.000。
     *
     * <p>f(0)=0、f(1)=1.000 精确。 */
    public static float recoveryCurve(float t) {
        float u = clamp01(t);
        if (u < .18f) return .06f * smooth(u / .18f);
        if (u < .86f) {
            float v = (u - .18f) / .68f;
            return .06f + (.985f - .06f) * (.35f * smooth(v) + .65f * v);
        }
        return .985f + .015f * smooth((u - .86f) / .14f);
    }

    private static float clamp01(float n) { return Float.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0; }
    private static float smooth(float x) { float t = clamp01(x); return t * t * (3 - 2 * t); }
}
