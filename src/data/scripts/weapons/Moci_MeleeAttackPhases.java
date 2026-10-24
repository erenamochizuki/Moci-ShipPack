package data.scripts.weapons;

/** Keeps manually animated melee phases aligned with the engine's beam update order. */
public final class Moci_MeleeAttackPhases {
    private int collisionPhase;
    private int animationPhase;
    private float collisionProgress;
    private float animationProgress;
    private float lastCollisionProgress;

    /**
     * The callback sees the previous ray's collision. The engine has already
     * cached the current weapon angle for the next ray before this callback;
     * the angle changed by this callback only affects the following ray.
     *
     * @return the phase belonging to the collision currently exposed by BeamAPI
     */
    public int advance(int nextAnimationPhase) {
        return advance(nextAnimationPhase, nextAnimationPhase == 0 ? 0f : 1f);
    }

    /** Publishes phase and normalized swing progress together for the next ray. */
    public int advance(int nextAnimationPhase, float nextProgress) {
        int currentCollisionPhase = collisionPhase;
        lastCollisionProgress = collisionProgress;
        collisionPhase = animationPhase;
        collisionProgress = animationProgress;
        animationPhase = nextAnimationPhase;
        animationProgress = nextAnimationPhase == 0 ? 0f : Math.max(0f, Math.min(1f, nextProgress));
        return currentCollisionPhase;
    }

    /** Phase of the ray the engine will write after the current beam callback. */
    public int getRayPhase() {
        return collisionPhase;
    }

    /** 当前回调即将读取的碰撞阶段；只读取，不提前推进阶段。 */
    public int getCollisionPhase() {
        return collisionPhase;
    }

    /** Normalized progress for the collision returned by the most recent advance(). */
    public float getLastCollisionProgress() {
        return lastCollisionProgress;
    }

    public void reset() {
        collisionPhase = 0;
        animationPhase = 0;
        collisionProgress = 0f;
        animationProgress = 0f;
        lastCollisionProgress = 0f;
    }
}
