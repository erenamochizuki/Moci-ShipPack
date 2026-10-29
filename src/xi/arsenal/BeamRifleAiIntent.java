package xi.arsenal;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;

/** AI 载机的开火意图：只回答「这一帧该打谁」，不碰任何 xi 状态机。
 *
 *  性能契约（Lead 第二条任务书的硬约束）：
 *  <ul>
 *    <li>只用平方距离比较，开方只发生在真正选中目标之后；</li>
 *    <li>按船 id 哈希错峰，每 10~15 帧才重算一次；其余帧直接吃缓存；</li>
 *    <li>缓存目标失效（死亡/出射程/换阵营）时立即重算，不等到窗口。</li>
 *  </ul>
 *  本类无状态副作用：不改武器、不改舰船、不写 custom data。 */
final class BeamRifleAiIntent {
    /** 错峰窗口下限 / 上限（帧）。 */
    private static final int MIN_FRAMES = 10, MAX_FRAMES = 15;
    /** 目标缓存的有效射程余量：略大于武器射程，避免目标在边界抖动。 */
    private static final float RANGE_SLACK = 1.05f;

    private ShipAPI target;
    private int frames;
    private int window = MIN_FRAMES;

    /** 返回这一帧应该打的目标（可能为 null）；内部按错峰窗口重算。 */
    ShipAPI target(ShipAPI ship, WeaponAPI beam, CombatEngineAPI engine) {
        if (ship == null || beam == null || engine == null) return null;
        if (frames++ >= window) {
            frames = 0;
            window = staggerWindow(ship);
            target = acquire(ship, beam, engine);
        } else if (target != null && !usable(ship, target, beam)) {
            target = acquire(ship, beam, engine); // 缓存目标已失效：立即重算，不等窗口。
        }
        return target;
    }

    private static int staggerWindow(ShipAPI ship) {
        String id = ship.getId();
        int hash = id == null ? 0 : id.hashCode();
        return MIN_FRAMES + Math.floorMod(hash, MAX_FRAMES - MIN_FRAMES + 1);
    }

    private ShipAPI acquire(ShipAPI ship, WeaponAPI beam, CombatEngineAPI engine) {
        ShipAPI locked = ship.getShipTarget();
        if (usable(ship, locked, beam) && inArc(beam, locked)) return locked;
        List<ShipAPI> candidates = engine.getShips();
        if (candidates == null || candidates.isEmpty()) return null;
        ShipAPI best = null;
        float range = Math.max(1f, beam.getRange() * RANGE_SLACK);
        float limit = range * range;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0, size = candidates.size(); i < size; i++) {
            ShipAPI other = candidates.get(i);
            if (other == null || other == ship) continue;
            if (!usable(ship, other, beam)) continue;
            float distance = squaredDistance(ship.getLocation(), other.getLocation());
            if (distance > limit || distance >= bestDistance) continue;
            if (!inArc(beam, other)) continue;
            best = other;
            bestDistance = distance;
        }
        return best;
    }

    /** 目标是否还能打：活着、敌对、在（略放大的）射程内。不做开方。 */
    static boolean usable(ShipAPI ship, ShipAPI candidate, WeaponAPI beam) {
        if (ship == null || candidate == null || beam == null) return false;
        if (candidate == ship) return false;
        if (!candidate.isAlive() || candidate.isHulk() || candidate.wasRemoved()) return false;
        if (candidate.getOwner() == ship.getOwner()) return false;
        float range = Math.max(1f, beam.getRange() * RANGE_SLACK);
        return squaredDistance(ship.getLocation(), candidate.getLocation()) <= range * range;
    }

    /** 粗射界判定：以武器当前角为中心的原始 arc，留 1.5 倍余量给转塔跟踪。 */
    static boolean inArc(WeaponAPI beam, ShipAPI candidate) {
        if (beam == null || candidate == null) return false;
        Vector2f from = beam.getLocation(), to = candidate.getLocation();
        if (from == null || to == null) return false;
        float dx = to.x - from.x, dy = to.y - from.y;
        if (Math.abs(dx) < 1e-3f && Math.abs(dy) < 1e-3f) return true;
        float bearing = (float) Math.toDegrees(Math.atan2(dy, dx));
        float delta = ((bearing - beam.getCurrAngle()) % 360f + 540f) % 360f - 180f;
        float arc = Math.max(15f, beam.getArc() * .75f);
        return Math.abs(delta) <= arc;
    }

    static float squaredDistance(Vector2f a, Vector2f b) {
        if (a == null || b == null) return Float.MAX_VALUE;
        float dx = a.x - b.x, dy = a.y - b.y;
        return dx * dx + dy * dy;
    }
}
