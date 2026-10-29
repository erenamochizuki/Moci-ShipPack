package data.campaign.fighter;

/** Refit points are compared with the prototype's unmodified wing OP, never the carrier's stats. */
public final class Moci_FighterRefitBudget {
    public static final int TACTICAL_POINTS = 5;
    public static final int SMALL_POINTS = 5;
    public static final int MEDIUM_POINTS = 10;
    public static final int LARGE_POINTS = 20;

    private Moci_FighterRefitBudget() {}

    public record Budget(float baseOp, int usedPoints) {
        public Budget {
            if (!Float.isFinite(baseOp) || baseOp < 0f || usedPoints < 0) {
                throw new IllegalArgumentException("Invalid refit budget");
            }
        }
        public boolean overLimit() { return usedPoints > baseOp; }
        public float usageRatio() {
            return baseOp > 0f ? usedPoints / baseOp : usedPoints > 0 ? Float.POSITIVE_INFINITY : 0f;
        }
        public float excessRatio() { return Math.max(0f, usageRatio() - 1f); }
        /** base * (1 + excess/base), without rounding or a zero-capacity division. */
        public float opCost() { return Math.max(baseOp, usedPoints); }
    }
}
