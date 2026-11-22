package data.campaign.gacha;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 抽卡声音的统一路由：游戏窗口与离屏演示视频共用，修改声音优先看本文件。
 *
 * 常用入口：OPEN 选择开窗音效 ID；OPEN_VOLUME / COMPLETE_VOLUME 控制开窗和总结音量；
 * revealVolume(rank) 控制揭晓音量；revealId(reward) 控制按奖品类型／稀有度选什么声音。
 * 声音 ID 对应 MociShipPack/data/config/sounds.json，实际文件在 sounds/moci/gacha/。
 *
 * 本类只输出“音效 ID、调用音量”事件，不加载音频、不决定画面时长、不扣费发奖。
 * 每个窗口创建一个实例，避免 opened / sounded / completed 的去重状态跨窗口串用。
 */
public final class Moci_GachaAudio {
    // 【常用调节入口】开窗使用 open3。切换素材可改这个已注册 ID，例如 moci_gacha_open_02。
    public static final String OPEN = "moci_gacha_open_03";
    // 【常用调节入口】0=静音，0.5=50%，1=原调用音量；揭晓声音另看 revealVolume()。
    // 当前所有阶段均为 0.5。注册配置中的 volume 仍是 1.0，避免重复乘 0.5 后变成 25%。
    // 最终听感还会受到原始素材响度与游戏全局音效音量影响，0.5 不保证主观响度正好减半。
    public static final float OPEN_VOLUME = .5f, COMPLETE_VOLUME = .5f;
    // 【常用调节入口】十连全部展示完以后，再延迟多少秒播放总结音。
    // 计时起点是 allRevealed()，含最后一张的最短停留；不是最后一张刚显露时。
    // 单抽总结不使用此延迟：窗口 buildSummary() 时调用 completeSingle() 立即播放。
    public static final float COMPLETE_DELAY = .45f;
    // 游戏窗口传入 playUISound(id, 1f, volume)，其中 1f 是原音高；视频导出则记录事件。
    private final BiConsumer<String, Float> output;
    // 去重：opened 防止重复开窗音，sounded 记住已发声的奖品，completed 防止重复总结音。
    private int sounded = -1;
    private boolean opened, completed;
    private float completeAge;

    public Moci_GachaAudio(BiConsumer<String, Float> output) { this.output = output; }
    public void open() {
        if (opened) return;
        opened = true;
        output.accept(OPEN, OPEN_VOLUME);
    }
    /**
     * 十连每帧调用，须在 sequence.advance() 之后，让声音读取本帧最新的显露状态。
     * 普通／R 读取 revealedCount，SR／SSR 读取 focusRevealed，声音均跟随奖品首次显露。
     * 回看详情、翻页、切换已抽清单不重复播放。
     */
    public void updateBatch(Moci_GachaBatchSequence sequence, List<Moci_GachaBatchReceipt.Draw> draws, float amount) {
        int index = sequence.focusIndex() >= 0 && sequence.focusRevealed() ? sequence.focusIndex() : sequence.revealedCount() - 1;
        if (index >= 0 && index > sounded && !sequence.inspecting()) {
            sounded = index;
            reveal(draws.get(index).reward());
        }
        // 整批尚未展示完时重置总结倒计时，最后一张的声音因此有完整的停留窗口。
        if (!sequence.allRevealed()) { completeAge = 0; return; }
        sounded = Math.max(sounded, draws.size() - 1);
        if (Float.isFinite(amount) && amount > 0) completeAge += Math.min(amount, .1f);
        if (completeAge < COMPLETE_DELAY || completed) return;
        int rank = 0;
        for (Moci_GachaBatchReceipt.Draw draw : draws) if (draw.reward() != null) rank = Math.max(rank, draw.rank());
        complete(rank);
    }
    /** 单抽在 revealed() 第一次变为 true 时播放；画面仍在蓄力／剪影阶段时不发揭晓音。 */
    public void updateSingle(Moci_GachaRevealSequence sequence, List<Moci_GachaReward> rewards) {
        if (sequence.summary() || !sequence.revealed() || sounded == sequence.index()) return;
        sounded = sequence.index();
        reveal(rewards.get(sequence.index()));
    }
    /** 单抽汇总由窗口主动调用；多条奖励按最高稀有度选择总结音，重复调用只响一次。 */
    public void completeSingle(List<Moci_GachaReward> rewards) {
        int rank = 0;
        for (Moci_GachaReward reward : rewards) rank = Math.max(rank, reward.rank());
        complete(rank);
    }
    private void reveal(Moci_GachaReward reward) {
        if (reward != null) output.accept(revealId(reward), revealVolume(reward.rank()));
    }
    private void complete(int rank) {
        if (completed) return;
        completed = true;
        output.accept(completionId(rank), COMPLETE_VOLUME);
    }
    /**
     * 【常用调节入口】选择揭晓音效：实际军官 OFFICER 使用 character_r/sr/ssr，
     * 机体、武器、物资、星币等使用 reveal_r/sr/ssr；军官转为 AI 核心后按实际物品处理。
     * 已注册的 type_ship / type_weapon / type_item / type_character 目前是备选，不会叠加。
     * 若以后按类型分配声音，可在这里调整路由，并在 sounds.json 注册对应 ID。
     */
    public static String revealId(Moci_GachaReward reward) {
        boolean character = "OFFICER".equalsIgnoreCase(reward.type());
        return "moci_gacha_" + (character ? "character_" : "reveal_") + tier(reward.rank());
    }
    // 总结音使用整批奖励的最高档位：complete_r / complete_sr / complete_ssr。
    public static String completionId(int rank) { return "moci_gacha_complete_" + tier(rank); }
    /**
     * 【常用调节入口】各档物品和角色共用的揭晓音量，目前一律为 50%。
     * 如需恢复分档音量，可按 rank（0=普通／R、1=SR、2=SSR）返回不同的 0～1 数值。
     * 这里只控制声音响度；修改播放时长要去 RevealSequence 的两个 hold 方法。
     */
    public static float revealVolume(int rank) { return .5f; }
    private static String tier(int rank) { return rank <= 0 ? "r" : rank == 1 ? "sr" : "ssr"; }
}
