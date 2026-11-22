package data.campaign.gacha;

import java.util.List;

/** Immutable receipt of an already completed draw. This object never grants or rolls rewards. */
public record Moci_GachaReward(String id, String name, String type, String itemId,
                               String param, int quantity, String rarity, String portrait) {
    public Moci_GachaReward {
        if (quantity <= 0) throw new IllegalArgumentException("Reward quantity must be positive");
        name = name == null || name.isBlank() ? itemId : name;
        rarity = rarity == null ? "R" : rarity;
    }

    public int rank() {
        return switch (rarity) { case "SSR" -> 2; case "SR" -> 1; default -> 0; };
    }

    public static List<Moci_GachaReward> presentationOrder(List<Moci_GachaReward> settled) {
        // Stable sort: common rewards first; the jackpot is revealed last. No new RNG.
        return settled.stream().sorted(java.util.Comparator.comparingInt(Moci_GachaReward::rank)).toList();
    }
}
