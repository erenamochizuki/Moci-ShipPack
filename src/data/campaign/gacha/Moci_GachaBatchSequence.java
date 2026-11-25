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
    // 【常用调节入口】列表与 SR／SSR 特写之间的交叉淡化时长（秒）。
    public static final float FADE = .35f;
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
    // focusAge：当前特写开始后的时间；returnAge：上一段特写结束、回到列表后的时间。
    // lastFocus 保留刚结束的特写，供回到列表时在它上面淡入列表。
    private float focusAge, returnAge = 99f;
    private Moci_GachaRevealSequence lastFocus;
    private int lastFocusIndex = -1;
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
        returnAge += dt;
        if (lastFocus != null && returnAge < FADE) lastFocus.advance(dt, rankAt(lastFocusIndex));
        if (manualDetail >= 0) return;
        if (active >= 0) {
            focusAge += dt;
            focus.advance(dt, rankAt(active));
            // 暂停自动推进时，已开始的高稀有度动画仍会播放并停留完，再回到列表。
            // focus 关闭自身自动换张，由本控制器按统一的停留规则决定 finishFocus()。
            float hold = nextRequested || fastBatch ? Moci_GachaRevealSequence.minimumHold(rankAt(active)) : Moci_GachaRevealSequence.displayHold(rankAt(active));
            if (focus.revealed() && focus.age() - focus.revealAt() + .00001f >= hold) finishFocus();
            return;
        }
        if (age < LANDING) return;
        sinceReveal += dt;
        if (fastBatch) { advanceFast(); return; }
        if ((automatic || nextRequested) && revealed < pageEnd() && !holdingOrdinary()) beginNext();
    }
    /**
     * 快速揭晓：普通／R 一次性全部显示，遇到 SR／SSR 照常播放完整特写（不跳过蓄力），
     * 特写结束后继续。不自动翻页，当前页停在原地，结束后可自行翻页查看。
     */
    private void advanceFast() {
        boolean any = false;
        while (revealed < draws.size() && rankAt(revealed) == 0) { revealed++; any = true; }
        if (any) sinceReveal = 99;
        if (revealed < draws.size()) startFocus(revealed);
        else fastBatch = false;
    }
    /** 启动下一张：普通／R 立即提交，高稀有度创建独立时间轴，保持卡位隐藏。 */
    private void beginNext() {
        if (revealed >= pageEnd()) return;
        nextRequested = false;
        if (rankAt(revealed) == 0) { revealed++; sinceReveal = 0; }
        else startFocus(revealed);
    }
    /** 为 SR／SSR 建立独立特写时间轴，从蓄力开始完整播放。 */
    private void startFocus(int index) {
        active = index;
        focusAge = 0;
        focus = new Moci_GachaRevealSequence(1, rankAt(active));
        focus.toggleAutomatic();
    }
    // SR／SSR 已在独立画面中停留完，不再回列表重复等待同样时长，因此重置 sinceReveal。
    private void finishFocus() {
        lastFocus = focus; lastFocusIndex = active; returnAge = 0;
        revealed = active + 1; active = -1; focus = null; sinceReveal = 99; nextRequested = false;
    }
    private void clearFade() { lastFocus = null; lastFocusIndex = -1; returnAge = 99; }
    /**
     * 玩家点击“揭晓下一张”／点击画面：立即推进，不等当前卡的停留时间。
     * SR／SSR 还在蓄力时先直接显露；已经显露时立即结束特写并揭晓下一张。
     * 本页已全部揭晓时翻到下一页；刚关掉的特写是本页最后一张时先停在本页。
     */
    public void next() {
        if (manualDetail >= 0) { manualDetail = -1; return; }
        nextRequested = false;
        boolean closedFocus = false;
        if (active >= 0) {
            if (!focus.revealed()) { focus.next(); return; }
            finishFocus();
            closedFocus = true;
            if (fastBatch) return; // 快速揭晓会在下一帧自己继续
        }
        age = Math.max(age, LANDING);
        if (revealed >= pageEnd()) {
            sinceReveal = 99;
            if (!closedFocus && page + 1 < pageCount()) nextPage();
            return;
        }
        beginNext();
    }
    /** 快速揭晓：见 advanceFast()。正在播放的特写照常播完（最短停留），之后继续。 */
    public void fastForwardAll() {
        if (revealed >= draws.size() && active < 0) return;
        fastBatch = true;
        age = Math.max(age, LANDING);
    }
    // 只检查本页最近一张普通／R 的停留时间；SR／SSR 的停留由 focus 分支负责。
    private boolean holdingOrdinary() {
        if (revealed <= pageStart() || rankAt(revealed - 1) != 0) return false;
        float hold = nextRequested || fastBatch ? Moci_GachaRevealSequence.minimumHold(0) : Moci_GachaRevealSequence.displayHold(0);
        return sinceReveal + .00001f < hold;
    }
    public boolean fastForwarding() { return fastBatch; }
    /**
     * 立即把所有奖品标为已揭晓：用于渲染失败兜底、测试构造和结果截图。
     * 播放中点“合并清单”也调用这里，直接跳过动画；声音只补一次末张揭晓音和总结音。
     */
    public void revealAll() {
        age = Math.max(age, LANDING); revealed = draws.size();
        nextRequested = fastBatch = false;
        sinceReveal = 99; manualDetail = active = -1; focus = null;
        clearFade();
    }
    public void nextPage() {
        if (page + 1 < pageCount() && pageComplete()) { page++; manualDetail = -1; sinceReveal = 99; age = 0; clearFade(); }
    }
    public void previousPage() {
        if (page > 0 && pageComplete()) { page--; manualDetail = -1; age = LANDING; sinceReveal = 99; nextRequested = false; clearFade(); }
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
    /**
     * 结果列表（卡片、文字）当前的可见度 0～1：特写开始时从 1 淡到 0，特写结束回到列表时从 0 淡到 1。
     * 渲染器和窗口的卡片文字都按这个值淡化，保证画面与文字同步。
     */
    public float cardsVisibility() {
        if (inspecting()) return 0f;
        if (active >= 0) return 1f - Moci_GachaRevealSequence.clamp(focusAge / FADE);
        if (lastFocus != null && returnAge < FADE) return Moci_GachaRevealSequence.clamp(returnAge / FADE);
        return 1f;
    }
    /** 回到列表的淡入期间，垫在列表下面的上一段特写；其余时间为 null。 */
    public Moci_GachaRevealSequence fadingFocus() { return active < 0 && lastFocus != null && returnAge < FADE ? lastFocus : null; }
    public int fadingFocusIndex() { return lastFocusIndex; }
    public Moci_GachaRevealSequence ambient() { return ambient; }
}
