package data.campaign.gacha;

/**
 * 单个奖品的展示时间轴，也是十连中 SR／SSR 独立特效的计时器。
 *
 * 调节奏优先看三个入口：
 * 1. revealAt()：奖品在动画开始后第几秒显露，同时触发揭晓音效；此前属于蓄力阶段。
 * 2. displayHold(rank)：显露后自动停留多少秒。
 * 3. minimumHold(rank)：玩家点击快进后，显露至少停留多少秒。
 * 总耗时通常为 revealAt() + 停留时间；两个阶段不要混为同一个时间。
 *
 * 所有时间单位均为秒，rank 的约定是 0=普通／R、1=SR、2=SSR。
 * 本类仅控制画面流程，不扣费、不随机抽奖、不发奖，不使用战役时间倍率。
 */
public final class Moci_GachaRevealSequence {
    // SSR 的揭晓时刻：本时间轴的第一个奖品用 FIRST_REVEAL，后续奖品用 FOLLOWING_REVEAL。
    // 十连的每个 SR／SSR 都会新建一个 count=1 的时间轴，因此每张 SSR 都用 FIRST_REVEAL。
    public static final float FIRST_REVEAL = 3.50f;
    public static final float FOLLOWING_REVEAL = 1.10f;
    private final int count;
    private int rank;
    private int index;
    // age 是当前奖品动画已走过的时间；totalAge 是本时间轴累计推进的时间。
    // 切换奖品时 age 清零，totalAge 用于连续运动的背景与粒子。
    private float age;
    private float totalAge;
    private boolean automatic = true;
    // nextRequested：用户已经要求继续，采用快进最短停留；不是立刻跳到下一张。
    // summaryRequested：当前奖品满足最短停留后进入汇总；用于单抽窗口的“查看汇总”。
    private boolean summary, nextRequested, summaryRequested;

    public Moci_GachaRevealSequence(int count) { this(count, 2); }

    public Moci_GachaRevealSequence(int count, int rank) {
        this.rank = Math.max(0, Math.min(2, rank));
        if (count < 1) throw new IllegalArgumentException("No rewards to reveal");
        this.count = count;
    }

    /**
     * 每帧推进。调用者须让本次 rank 与 setRank() 保存的当前奖品档位一致。
     * amount 来自 UI 帧时间；暂停自动只阻止自动换奖品，已开始的动画与计时仍继续。
     */
    public void advance(float amount, int rank) {
        if (summary || !Float.isFinite(amount) || amount <= 0f) return;
        // 单帧最多推进 0.1 秒，避免卡顿后一帧跨过整张奖品，玩家来不及看到。
        float step = Math.min(amount, 0.1f);
        age += step;
        totalAge += step;
        // 揭晓前的蓄力不占用停留时间；减去 revealAt() 后才是实际展示时长。
        // 有快进／汇总请求时用 minimumHold，否则用 displayHold。
        float hold = nextRequested || summaryRequested ? minimumHold(rank) : displayHold(rank);
        if (revealed() && age - revealAt() + .00001f >= hold && (automatic || nextRequested || summaryRequested)) finishReward();
    }

    /**
     * 点击“揭晓／继续”：尚未显露时只提前到揭晓时刻；已经显露时记录继续请求。
     * 高频连点不会反复加速计时，也不能绕过 minimumHold()。
     */
    public void next() {
        if (summary) return;
        if (!revealed()) { age = revealAt(); return; }
        nextRequested = true;
        if (age - revealAt() + .00001f >= minimumHold(rank)) finishReward();
    }
    private void finishReward() {
        if (summaryRequested || index + 1 >= count) summary = true;
        else { index++; age = 0f; nextRequested = false; }
    }
    /** 单抽汇总请求也先保留当前奖品的最短展示时间，再跳到汇总。 */
    public void showSummary() {
        if (summary) return;
        summaryRequested = nextRequested = true;
        if (!revealed()) age = revealAt();
        if (age - revealAt() + .00001f >= minimumHold(rank)) finishReward();
    }
    // 仅供美术加载／渲染失败时的文字汇总兜底；正常按钮不能用它绕过展示时间。
    public void forceSummary() { summary = true; }
    public boolean fastForwarding() { return nextRequested || summaryRequested; }
    /**
     * 【常用调节入口】自动模式的“揭晓后停留”，顺序为普通／R、SR、SSR。
     * 当前为 3.5 / 4.2 / 5.2 秒，单抽和十连共用。
     * 如只想让玩家多看一会儿奖品，修改这里即可，不需要改蓄力或粒子代码。
     */
    public static float displayHold(int rank) { return rank <= 0 ? 3.0f : rank == 1 ? 4.0f : 5.0f; }
    /**
     * 【常用调节入口】点击快进后的“揭晓后最短停留”，当前为 2.2 / 3.5 / 4.8 秒。
     * 建议每档不大于 displayHold，并根据替换音效的有效声音尾部设置，避免下一张抢声。
     * 修改这些值后，tools/tests/MociGachaRevealRegression.java 中的预期时长也需核对。
     */
    public static float minimumHold(int rank) { return rank <= 0 ? 2.0f : rank == 1 ? 3.5f : 4.8f; }
    public void toggleAutomatic() { automatic = !automatic; }
    public boolean automatic() { return automatic; }
    public boolean summary() { return summary; }
    public boolean revealed() { return age >= revealAt(); }
    public boolean silhouette() { return age >= revealAt() - 0.65f && !revealed(); }
    public int index() { return index; }
    public int count() { return count; }
    public float age() { return age; }
    public float totalAge() { return totalAge; }
    public void setRank(int rank) { this.rank = Math.max(0, Math.min(2, rank)); }
    /**
     * 【常用调节入口】奖品显露时刻：普通／R=0.45 秒，SR=1.65 秒，SSR 见顶部常量。
     * 音效路由观察 revealed() 的首次变化来发声；改这里会同时移动画面与揭晓声音。
     * 十连普通／R 在结果列表直接揭晓，不走此处的 0.45 秒蓄力。
     */
    public float revealAt() {
        return rank == 0 ? .45f : rank == 1 ? 1.65f : index == 0 ? FIRST_REVEAL : FOLLOWING_REVEAL;
    }
    // 下面是画面内部的节奏曲线。改它们会影响蓄力、白闪、物品渐显，不改变停留规则。
    // SR 的蓄力结束比显露早 0.60 秒，其余档位早 1.05 秒，最短蓄力限制为 0.15 秒。
    public float chargeDuration() { return Math.max(.15f, revealAt() - (rank == 1 ? .60f : 1.05f)); }
    public float charge() { return clamp(age / chargeDuration()); }
    // 0.55 秒是奖品从开始显露到完全显露的过渡长度，包含在揭晓后的停留时间里。
    public float revealProgress() { return clamp((age - revealAt()) / 0.55f); }
    // 白闪从 chargeDuration() 结束时开始：0.10 秒升亮、0.10 秒后转入约 0.44 秒的衰减。
    public float flash() {
        float t = age - chargeDuration();
        return clamp(t / 0.10f) * (1f - clamp((t - 0.10f) / 0.44f));
    }
    public static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
}
