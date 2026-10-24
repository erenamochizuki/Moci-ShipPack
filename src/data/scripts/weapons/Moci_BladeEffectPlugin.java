package data.scripts.weapons;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 光剑武器基础效果插件
 * 处理光剑的基础功能：
 * 1. 统一的近战距离衰减
 * 2. 斩切敌方弹丸（需要武器有MOCI_BLADE_CUT标签）
 * 3. 直接伤害导弹（使用真正的Grid遍历优化性能）
 * 4. 统一的距离衰减管理系统
 *
 * 实际射程由武器与引擎决定，伤害衰减统一在此类中管理
 * 其他BeamEffect插件应调用calculateDistanceDamageMultiplier方法
 *
 * 衰减参数配置：
 * - DAMAGE_FALLOFF_RANGE_MULTIPLIER: 衰减范围倍数
 * - MINIMUM_DAMAGE_RATIO: 保底伤害比例
 */
public class Moci_BladeEffectPlugin implements EveryFrameWeaponEffectPlugin {
    protected WeaponAPI weapon; // 武器实例
    protected BeamAPI beam = null;

    // 斩切弹丸相关参数
    protected final float WIDTH_MULTIPLIER = 1.0f; // 斩切宽度倍率（相对于光束宽度）
    protected final IntervalUtil checkInterval = new IntervalUtil(0.1f, 0.15f); // 检查间隔

    // Beam 实例随一次爆发创建/销毁；按实例保留两刀账本，不扫描全场维护状态。
    private final Map<BeamAPI, InterceptionState> interceptionStates = new IdentityHashMap<>();

    private static final class InterceptionState {
        int phase;
        boolean phasePublished;
        boolean observedByWeapon;
        final Map<MissileAPI, float[]> damageByTarget = new IdentityHashMap<>();
    }

    /** 发布回调结束后生成的光线阶段，供下一次武器扫描使用：0=停顿，1/2=两次挥砍。 */
    public static void setAttackPhase(BeamAPI beam, int phase) {
        if (beam == null || beam.getWeapon() == null) return;
        if (beam.getWeapon().getEffectPlugin() instanceof Moci_BladeEffectPlugin) {
            Moci_BladeEffectPlugin effect = (Moci_BladeEffectPlugin) beam.getWeapon().getEffectPlugin();
            InterceptionState state = effect.stateFor(beam);
            state.phase = phase;
            state.phasePublished = true;
        }
    }

    private InterceptionState stateFor(BeamAPI beam) {
        InterceptionState state = interceptionStates.get(beam);
        if (state == null) {
            state = new InterceptionState();
            interceptionStates.put(beam, state);
        }
        return state;
    }

    // ========== 统一距离衰减系统配置 ==========
    /** 衰减开始的射程倍数（超过基础射程的多少倍开始衰减到保底伤害） */
    public static final float DAMAGE_FALLOFF_RANGE_MULTIPLIER = 3.0f;

    /** 保底伤害比例（0.0 = 完全衰减到0, 0.3 = 保留30%伤害） */
    public static final float MINIMUM_DAMAGE_RATIO = 0.3f;

    // 是否能够斩切弹丸的标签
    protected final String CUT_TAG = "MOCI_BLADE_CUT";

    // 光剑武器标签（用于直接伤害导弹）
    protected final String BLADE_TAG = "MOCI_BLADE";

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        this.weapon = weapon;
        if (engine == null || engine.isPaused()) return;

        List<BeamAPI> beams = weapon.getBeams();
        interceptionStates.keySet().removeIf(oldBeam -> !beams.contains(oldBeam));
        for (BeamAPI currentBeam : beams) {
            if (currentBeam == null) continue;
            InterceptionState state = stateFor(currentBeam);
            // EveryFrameWeaponEffect 先于光束回调：新光束先等待一次发布，避免归位时抢先拦截。
            // 不发布阶段的旧版光剑在下一帧退回普通单刀行为。
            if (!state.phasePublished) state.phase = state.observedByWeapon ? 1 : 0;
            state.observedByWeapon = true;
        }
        if (weapon.getChargeLevel() <= 0f || beams.isEmpty()) return;

        // 一把武器每帧只推进一次计时器，多炮口也按相同频率检查。
        checkInterval.advance(amount);
        if (!checkInterval.intervalElapsed()) return;
        boolean blade = isBladeWeapon();
        boolean cut = canCutProjectiles();
        if (!blade && !cut) return;

        for (BeamAPI currentBeam : beams) {
            if (currentBeam == null || currentBeam.getBrightness() < 0.7f
                    || stateFor(currentBeam).phase == 0) continue;
            this.beam = currentBeam;
            float deflectionWidth = currentBeam.getWidth() * WIDTH_MULTIPLIER;
            if (blade) {
                damageProjectilesDirectlyWithGrid(engine, currentBeam, deflectionWidth);
            }
            if (cut) {
                deflectProjectiles(engine, currentBeam.getFrom(), currentBeam.getTo(), deflectionWidth);
            }
        }
    }

    /**
     * 检查武器是否具有斩切弹丸的能力
     */
    protected boolean canCutProjectiles() {
        if (weapon == null || weapon.getSpec() == null) return false;
        return weapon.getSpec().hasTag(CUT_TAG);
    }

    /**
     * 检查是否为光剑武器
     */
    protected boolean isBladeWeapon() {
        if (weapon == null || weapon.getSpec() == null) return false;
        return weapon.getSpec().hasTag(BLADE_TAG);
    }

    /**
     * 统一的光剑距离衰减计算方法
     * 供所有光剑BeamEffect插件调用，确保衰减逻辑一致
     *
     * @param distance 实际攻击距离
     * @param baseRange 武器的基础射程
     * @return 伤害倍数 (MINIMUM_DAMAGE_RATIO 到 1.0)
     */
    public static float calculateDistanceDamageMultiplier(float distance, float baseRange) {
        if (distance <= baseRange) {
            // 在基础射程内，伤害不衰减
            return 1.0f;
        } else if (distance <= baseRange * DAMAGE_FALLOFF_RANGE_MULTIPLIER) {
            // 在基础射程到衰减终点之间，线性衰减到保底伤害
            float excessDistance = distance - baseRange;
            float maxExcessDistance = baseRange * (DAMAGE_FALLOFF_RANGE_MULTIPLIER - 1.0f);
            float invMaxExcessDistance = 1.0f / maxExcessDistance; // 预计算倒数
            float decayRatio = excessDistance * invMaxExcessDistance;
            // 从1.0线性衰减到MINIMUM_DAMAGE_RATIO
            return 1.0f - decayRatio * (1.0f - MINIMUM_DAMAGE_RATIO);
        } else {
            // 超过衰减范围，使用保底伤害
            return MINIMUM_DAMAGE_RATIO;
        }
    }

    /**
     * 检测并斩切光剑路径上的敌方弹丸（使用Grid遍历优化）
     */
    protected void deflectProjectiles(CombatEngineAPI engine, Vector2f bladeStart, Vector2f bladeEnd, float deflectionWidth) {
        ShipAPI ship = weapon.getShip();
        if (ship == null) return;

        // 计算光剑的方向向量
        Vector2f bladeVector = Vector2f.sub(bladeEnd, bladeStart, null);
        float bladeLength = bladeVector.length();

        // 预先计算检查范围
        float checkRadius = bladeLength + deflectionWidth + 100f; // 保守估算，覆盖整个光剑长度范围
        Vector2f bladeCenter = new Vector2f((bladeStart.x + bladeEnd.x)*0.5f, (bladeStart.y + bladeEnd.y)*0.5f);

        // ========== 使用Grid遍历优化 ==========
        // 使用全对象网格进行高效检索，可以检测到所有类型的弹丸
        Iterator<Object> objIter = engine.getAllObjectGrid().getCheckIterator(
            bladeCenter,
            checkRadius * 2f,  // 宽度
            checkRadius * 2f   // 高度
        );

        List<DamagingProjectileAPI> projectilesToRemove = new ArrayList<>();

        // 第一阶段：Grid遍历 + 粗筛选
        while (objIter.hasNext()) {
            Object obj = objIter.next();
            if (!(obj instanceof DamagingProjectileAPI)) continue;

            DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;

            // 双标签武器的导弹由伤害分支处理，切弹分支只移除普通弹丸。
            if (proj instanceof MissileAPI && isBladeWeapon()) continue;

            // 基础筛选：敌方、存活的弹丸
            if (proj.getOwner() == ship.getOwner()) continue;
            if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;

            // 粗距离检查：快速排除明显超出范围的弹丸（使用平方距离避免开方）
            float roughDistSq = MathUtils.getDistanceSquared(bladeCenter, proj.getLocation());
            float checkRadiusSq = checkRadius * checkRadius;
            if (roughDistSq > checkRadiusSq) continue;

            // 第二阶段：精确距离计算
            // 计算弹丸到光剑线段的最短距离
            Vector2f v = Vector2f.sub(bladeEnd, bladeStart, null);
            Vector2f w = Vector2f.sub(proj.getLocation(), bladeStart, null);
            float c1 = Vector2f.dot(w, v);
            float distance;
            if (c1 <= 0) {
                // 投影点在起点之前，使用起点距离
                distance = MathUtils.getDistance(proj.getLocation(), bladeStart);
            } else {
                float c2 = Vector2f.dot(v, v);
                if (c2 <= c1) {
                    // 投影点在终点之后，使用终点距离
                    distance = MathUtils.getDistance(proj.getLocation(), bladeEnd);
                } else {
                    // 投影点在线段上，计算垂直距离
                    float invC2 = 1.0f / c2; // 预计算倒数
                    float b = c1 * invC2;
                    Vector2f pb = new Vector2f(bladeStart.x + b * v.x, bladeStart.y + b * v.y);
                    distance = MathUtils.getDistance(proj.getLocation(), pb);
                }
            }

            // 如果距离小于斩切宽度，则斩切该弹丸
            if (distance <= deflectionWidth + proj.getCollisionRadius()) {
                projectilesToRemove.add(proj);
            }
        }

        // 移除被斩切的弹丸
        for (DamagingProjectileAPI proj : projectilesToRemove) {
            engine.removeEntity(proj);
        }
    }

    /**
     * 计算投影点（弹丸在光剑上的最近点）
     */
    protected Vector2f getProjectionPoint(Vector2f bladeStart, Vector2f bladeVector, Vector2f projectileLocation) {
        Vector2f projToStart = Vector2f.sub(projectileLocation, bladeStart, null);
        float invLengthSquared = 1.0f / bladeVector.lengthSquared(); // 预计算倒数
        float dot = Vector2f.dot(projToStart, bladeVector) * invLengthSquared;
        dot = Math.max(0, Math.min(1, dot)); // 限制在光剑线段内

        Vector2f projectionPoint = new Vector2f(bladeVector);
        projectionPoint.scale(dot);
        Vector2f.add(bladeStart, projectionPoint, projectionPoint);

        return projectionPoint;
    }

    /**
     * 直接对导弹造成伤害（使用Grid遍历优化性能）
     * 解决光束伤害间隔问题，确保光剑能够有效对抗导弹
     */
    protected void damageProjectilesDirectlyWithGrid(CombatEngineAPI engine, BeamAPI beam, float damageWidth) {
        if (weapon == null || weapon.getShip() == null || beam.getBrightness() < 0.7f) return;

        InterceptionState state = stateFor(beam);
        if (state.phase == 0) return;
        state.damageByTarget.keySet().removeIf(missile -> missile.isExpired() || !engine.isEntityInPlay(missile));

        ShipAPI ship = weapon.getShip();
        Vector2f bladeStart = beam.getFrom();
        Vector2f bladeEnd = beam.getTo();

        // 计算光剑的方向向量和长度
        Vector2f bladeVector = Vector2f.sub(bladeEnd, bladeStart, null);
        float bladeLength = bladeVector.length();

        // 预先计算检查范围
        float checkRadius = bladeLength + damageWidth + 100f; // 保守估算，覆盖整个光剑长度范围
        Vector2f bladeCenter = new Vector2f((bladeStart.x + bladeEnd.x)*0.5f, (bladeStart.y + bladeEnd.y)*0.5f);

        // ========== 使用真正的Grid遍历 ==========
        // 使用导弹专用网格进行高效检索
        Iterator<Object> missileIter = engine.getMissileGrid().getCheckIterator(
            bladeCenter,
            checkRadius * 1.5f,  // 宽度
            checkRadius * 3f   // 高度
        );

        List<MissileAPI> validTargets = new ArrayList<>();

        // 第一阶段：Grid遍历 + 粗筛选
        while (missileIter.hasNext()) {
            Object obj = missileIter.next();
            if (!(obj instanceof MissileAPI)) continue;

            MissileAPI missile = (MissileAPI) obj;

            // 基础筛选：敌方、存活的导弹
            if (missile.getOwner() == ship.getOwner()) continue;
            if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;
            // if (missile.isFlare()) continue; // 排除干扰弹

            // 粗距离检查：快速排除明显超出范围的导弹（使用平方距离避免开方）
            float roughDistSq = MathUtils.getDistanceSquared(bladeCenter, missile.getLocation());
            float checkRadiusSq = checkRadius * checkRadius;
            if (roughDistSq > checkRadiusSq) continue;

            validTargets.add(missile);
        }

        // 第二阶段：精确距离计算和伤害应用
        for (MissileAPI missile : validTargets) {
            // 计算导弹到光剑线段的精确最短距离
            float distance = calculateDistanceToBeam(bladeStart, bladeEnd, missile.getLocation());

            // 如果距离在伤害范围内，则造成伤害
            if (distance <= damageWidth + missile.getCollisionRadius()) {
                float fullDamage = calculateBladeDamageWithMissileBonus(beam, missile);
                float[] dealt = state.damageByTarget.get(missile);
                if (dealt == null) {
                    dealt = new float[2];
                    state.damageByTarget.put(missile, dealt);
                }
                int swing = state.phase == 2 ? 1 : 0;
                float limit = fullDamage * (swing == 1 && dealt[0] > 0f ? 0.5f : 1f);
                float damage = Math.max(0f, limit - dealt[swing]);

                // 对导弹不应用距离衰减（参考原版逻辑）
                if (damage > 0) {
                    // 先记账，避免伤害监听器重入时重复消费同一份额度。
                    dealt[swing] += damage;
                    Moci_MeleeDamage.applyDamage(engine, beam, missile, missile.getLocation(), damage, damage);
                }
            }
        }
    }

    /**
     * 计算点到光束线段的最短距离
     */
    protected float calculateDistanceToBeam(Vector2f beamStart, Vector2f beamEnd, Vector2f point) {
        Vector2f v = Vector2f.sub(beamEnd, beamStart, null);
        Vector2f w = Vector2f.sub(point, beamStart, null);
        float c1 = Vector2f.dot(w, v);

        if (c1 <= 0) {
            // 投影点在起点之前，使用起点距离
            return MathUtils.getDistance(point, beamStart);
        } else {
            float c2 = Vector2f.dot(v, v);
            if (c2 <= c1) {
                // 投影点在终点之后，使用终点距离
                return MathUtils.getDistance(point, beamEnd);
            } else {
                // 投影点在线段上，计算垂直距离
                float invC2 = 1.0f / c2; // 预计算倒数
                float b = c1 * invC2;
                Vector2f pb = new Vector2f(beamStart.x + b * v.x, beamStart.y + b * v.y);
                return MathUtils.getDistance(point, pb);
            }
        }
    }

    /**
     * 计算光剑对导弹的伤害值
     */
    protected float calculateBladeDamageWithMissileBonus(BeamAPI beam, DamagingProjectileAPI target) {
        if (weapon == null || weapon.getShip() == null) return 0f;

        // 特意保留设计：本舰导弹武器倍率也增强光剑的导弹拦截。
        // 真正的 getDamageToMissiles() 由引擎根据来源舰船自动应用，不在此重复计算。
        float missileDamageMult = weapon.getShip().getMutableStats().getMissileWeaponDamageMult().computeMultMod();
        return Math.max(0f, Moci_MeleeDamage.calculateFullDamage(weapon) * missileDamageMult);
    }

}
