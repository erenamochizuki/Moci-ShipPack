package data.campaign.gacha;

import java.util.List;

/** Per-draw presentation and aggregate receipt of one settlement; neither can grant rewards. */
public record Moci_GachaBatchReceipt(List<Draw> draws, List<Moci_GachaReward> totals) {
    public Moci_GachaBatchReceipt {
        draws = List.copyOf(draws);
        totals = List.copyOf(totals);
    }
    public record Draw(int number, Moci_GachaReward reward, String message) {
        public int rank() { return reward == null ? 0 : reward.rank(); }
    }
    public List<Moci_GachaReward> bonuses() {
        return totals.stream().filter(r -> "BONUS".equals(r.rarity())).toList();
    }
}
