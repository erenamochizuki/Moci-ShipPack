package data.campaign.gacha;

import java.util.List;

/**
 * 连续抽取的顺序、分页和揭晓状态控制器。
 * 普通／R 在结果列表原位显露；SR／SSR 隐藏该奖品，先播放独立特效，再回到结果列表。
 *
 * 常用停留时长统一在 Moci_GachaRevealSequence.displayHold / minimumHold 修改，
 * 本类只负责何时启动下一张，避免单抽和十连各维护一套不同数值。
 * 本类接收已经结算好的 draws，只负责展示，不重新抽取或发放奖励。
 */
public final class Moci_GachaBatchSequence {
    // 每页 10 抽。若改变数量，需要同时调整 PortalRenderer 与 BatchDialog 的卡位布局。
    public static final int PAGE_SIZE = 10;
    // 【常用调节入口】LANDING：本页入场时间，也是自动揭晓第一张前的等待（秒）。
    // OPENING 为保留的常量，当前流程没有读取它，修改 OPENING 不会改变入场节奏。
    public static final float OPENING = 0f, LANDING = .60f;
    private final List<Moci_GachaBatchReceipt.Draw> draws;
    private final Moci_GachaRevealSequence ambient = new Moci_GachaRevealSequence(1);
    // focus 仅供正在播放独立特效的 SR／SSR；ambient 供结果页背景运动使用。
    private Moci_GachaRevealSequence focus;
    // revealed 为已提交到结果列表的数量；active 为尚在特效中、未提交的奖品下标。
    // manualDetail 为已揭晓奖品的详情浏览下标，-1 表示没有；浏览期间不推进下一张。
    private int page, revealed, active = -1, manualDetail = -1;
    // age 为当前页入场时间；sinceReveal 为上一张普通／R 揭晓后的时间。
    // 99 是“本来没有需要等待的普通卡”的哨兵值，不是某个展示阶段要等待 99 秒。
    private float age, sinceReveal = 99f;
    private boolean automatic = true;
    // nextRequested 只加快当前一张；fastBatch 持续以最短停留依次揭晓剩余奖品。
    private boolean nextRequested, fastBatch;
    public Moci_GachaBatchSequence(List<Moci_GachaBatchReceipt.Draw> draws) {
        if (draws.isEmpty()) throw new IllegalArgumentException("Empty batch");
        this.draws = List.copyOf(draws);
        // 背景停在已点亮阶段；44 次小步仅用于预热背景状态，不是给玩家增加 4.4 秒等待。
        ambient.toggleAutomatic();
        for (int i = 0; i < 44; i++) ambient.advance(.1f, 0);
    }
    public void advance(float amount) {
        if (!Float.isFinite(amount) || amount <= 0) return;
        float dt = Math.min(amount, .1f);
        age += dt; ambient.advance(dt, 0);
        if (manualDetail >= 0) return;
        if (active >= 0) {
            focus.advance(dt, rankAt(active));
            // 暂停自动推进时，已开始的高稀有度动画仍会播放并停留完，再回到列表。
            // focus 关闭自身自动换张，由本控制器按统一的停留规则决定 finishFocus()。
            float hold = nextRequested || fastBatch ? Moci_GachaRevealSequence.minimumHold(rankAt(active)) : Moci_GachaRevealSequence.displayHold(rankAt(active));
            if (focus.revealed() && focus.age() - focus.revealAt() + .00001f >= hold) finishFocus();
            return;
        }
        if (age < LANDING) return;
        sinceReveal += dt;
        // 只有仍有未揭晓奖品时，快速模式才自动翻页；抽完后浏览旧页面不会被拉回末页。
        if (fastBatch && revealed < draws.size() && pageComplete() && page + 1 < pageCount()) { nextPage(); return; }
        if ((automatic || nextRequested || fastBatch) && revealed < pageEnd() && !holdingOrdinary()) beginNext();
    }
    /** 启动下一张：普通／R 立即提交，高稀有度创建独立时间轴，保持卡位隐藏。 */
    private void beginNext() {
        if (revealed >= pageEnd()) return;
        nextRequested = false;
        if (rankAt(revealed) == 0) { revealed++; sinceReveal = 0; }
        else {
            active = revealed;
            focus = new Moci_GachaRevealSequence(1, rankAt(active));
            focus.toggleAutomatic();
            // 快速模式可跳过蓄力直接显露，但显露后的 minimumHold 仍由 advance() 保证。
            if (fastBatch) focus.next();
        }
    }
    // SR／SSR 已在独立画面中停留完，不再回列表重复等待同样时长，因此重置 sinceReveal。
    private void finishFocus() { revealed = active + 1; active = -1; focus = null; sinceReveal = 99; nextRequested = false; }
    /**
     * 玩家点击继续：高稀有度可提前揭晓；已经显露的奖品只记录快进请求。
     * 普通／R 同样等到 minimumHold(0) 才进入下一张，不直接增加 revealed。
     */
    public void next() {
        if (manualDetail >= 0) { manualDetail = -1; return; }
        if (active >= 0) {
            if (!focus.revealed()) focus.next();
            nextRequested = true;
            return;
        }
        if (pageComplete()) { nextPage(); return; }
        age = Math.max(age, LANDING);
        nextRequested = true;
        if (!holdingOrdinary() && revealed < pageEnd()) beginNext();
    }
    /** “快速揭晓”仍逐张展示并保留最短时间；正常 UI 的快速按钮调用这个方法。 */
    public void fastForwardAll() {
        fastBatch = nextRequested = true;
        if (active >= 0 && !focus.revealed()) focus.next();
    }
    // 只检查本页最近一张普通／R 的停留时间；SR／SSR 的停留由 focus 分支负责。
    private boolean holdingOrdinary() {
        if (revealed <= pageStart() || rankAt(revealed - 1) != 0) return false;
        float hold = nextRequested || fastBatch ? Moci_GachaRevealSequence.minimumHold(0) : Moci_GachaRevealSequence.displayHold(0);
        return sinceReveal + .00001f < hold;
    }
    public boolean fastForwarding() { return nextRequested || fastBatch; }
    /**
     * 立即把所有奖品标为已揭晓：仅供渲染失败兜底、测试构造和结果截图。
     * 不要把正常“快速揭晓／合并清单”按钮接到这里，否则会绕过声音与最短展示时间。
     */
    public void revealAll() {
        age = Math.max(age, LANDING); revealed = draws.size();
        nextRequested = fastBatch = false;
        sinceReveal = 99; manualDetail = active = -1; focus = null;
    }
    public void nextPage() {
        if (page + 1 < pageCount() && pageComplete()) { page++; manualDetail = -1; sinceReveal = 99; age = 0; }
    }
    public void previousPage() {
        if (page > 0 && pageComplete()) { page--; manualDetail = -1; age = LANDING; sinceReveal = 99; nextRequested = false; }
    }
    public void inspect(int index) {
        if (index >= pageStart() && index < pageEnd() && visible(index) && pageComplete()) manualDetail = index;
    }
    public void closeDetail() { manualDetail = -1; }
    public void toggleAutomatic() { automatic = !automatic; }
    public int rankAt(int index) { return draws.get(index).rank(); }
    public boolean automatic() { return automatic; }
    public boolean intro() { return age < LANDING; }
    public float landing() { return age >= LANDING ? 1f : Moci_GachaRevealSequence.clamp(age / LANDING); }
    public int page() { return page; }
    public int pageCount() { return (draws.size() + PAGE_SIZE - 1) / PAGE_SIZE; }
    public int pageStart() { return page * PAGE_SIZE; }
    public int pageEnd() { return Math.min(draws.size(), pageStart() + PAGE_SIZE); }
    public int revealedCount() { return revealed; }
    public boolean visible(int index) { return index < revealed; }
    // “已经看见最后一张”不等于“展示完毕”：还必须等待最后一张普通／R 的停留结束。
    // Audio 据此决定何时开始计算总结音延迟，窗口据此开放再次抽取和汇总。
    public boolean allRevealed() { return revealed == draws.size() && focusIndex() < 0 && !holdingOrdinary(); }
    public boolean pageComplete() { return revealed >= pageEnd() && focusIndex() < 0 && !holdingOrdinary(); }
    public int focusIndex() { return manualDetail >= 0 ? manualDetail : active; }
    public boolean inspecting() { return manualDetail >= 0; }
    public boolean focusRevealed() { return inspecting() || focus != null && focus.revealed(); }
    public float focusProgress() { return inspecting() ? 1 : focus == null ? 0 : focus.revealProgress(); }
    // 0.22 秒仅控制最新普通／R 卡片的显露过渡，不是卡片的总展示时间。
    public float cardProgress(int index) { return index == revealed - 1 ? Moci_GachaRevealSequence.clamp(sinceReveal / .22f) : 1f; }
    public Moci_GachaRevealSequence focusSequence() { return focus; }
    public Moci_GachaRevealSequence ambient() { return ambient; }
}
