package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.ARBVertexArrayObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 光剑/战斧共用的接触特效。每个 BeamEffectPlugin 持有一个实例。
 * 火花在反挥砍侧、靠近目标表面的半个夹角内喷射；短暂火线附着舰体，随目标平移和旋转。
 * 同时管理接触减速，不依赖本帧是否还有补伤额度。
 */
public final class Moci_MeleeHitEffects {
    // 调参入口：火花外观看 emitSparks()；火线颜色、宽度看 ScarRenderer.render() 的三次 drawStrip()。
    // 时间单位为游戏内秒；尺寸/距离为战场世界单位（不是随屏幕缩放保持不变的屏幕像素）。
    private static final float SAMPLE_INTERVAL = 1f / 15f; // 接触亮点/火线每秒采样15次；不再控制飞溅火花。
    private static final float SAMPLE_DISTANCE = 10f; // 接触点移动满10单位可提前补一段火线。
    private static final float SPARKS_PER_SECOND = 400; // 火花生成密度；不再决定速度档位。
    private static final float SPARK_RATE = SPARKS_PER_SECOND;
    private static final int MAX_SPARKS_PER_FRAME = 128; // 防止卡顿时补发大量火花。
    private static final float SMOKE_RATE = 2f; // 舰体接触烟雾每秒3团；与火花数量分开控制。
    // 烟雾调参：光剑沿线烟雾与命中烟雾共用，最终尺寸=本团初始尺寸×最终倍率。
    private static final float INITIAL_SIZE_MIN = 45f; // 烟雾初始大小下限，战场单位。
    private static final float INITIAL_SIZE_MAX = 55f; // 烟雾初始大小上限，每团随机取值。
    private static final float END_SIZE_MULT_MIN = 1.75f; // 烟雾最终尺寸倍率下限。
    private static final float END_SIZE_MULT_MAX = 2f; // 烟雾最终尺寸倍率上限，3表示初始大小的3倍。
    private static final float DURATION = 1f; // 烟雾总寿命，秒。
    private static final int ALPHA = 125; // 烟雾颜色透明度，0~255。
    public static final float CHECK_INTERVAL = 1f / 15f; // 卡肉每自身局部秒检查15次，结果保持到下一次检查；随自身时流一起推进。
    public static final float HIT_SPEED_MULT = 0.25f; // 卡肉时刀刃的局部挥砍速度；配合下方全局0.5倍时缓，合成刀速为0.5×0.5=原速的0.25倍。关闭全局时缓后仅保留本倍率；连续命中不叠乘。
    public static boolean ENABLE_HIT_TIME_FLOW = false; // 旧方案：自身加速，玩家再补偿全局时缓；默认关闭，保留原有调参。独立于下方新方案。
    public static final float HIT_TIME_MULT = 1.2f; // 自身局部时流倍率，1关闭加速；AI只获得此倍率。与其他系统的时流正常相乘。
    public static final float HIT_TIME_DURATION = CHECK_INTERVAL; // 每次触发维持的自身局部时间（秒）；默认约0.067秒，连续触发只刷新、不叠加，停止命中后自动到期。
    // 旧方案：玩家同时获得上述自身加速和全局1/HIT_TIME_MULT时缓，自身时流×全局倒数=1，操控时间基本不变，周围变慢。
    // 挥砍动画仍额外乘HIT_SPEED_MULT，因此保留卡肉；此功能并非直接恢复刀速。
    public static boolean ENABLE_HIT_GLOBAL_SLOW = true; // 新方案：仅玩家控制舰船卡肉实际触发时同步放慢全局引擎；不额外加速自身，默认开启，AI命中不会拖慢全场。
    public static final float HIT_GLOBAL_TIME_MULT = 0.4f; // 全局时流倍率，范围大于0且小于1；0.5表示所有舰船、弹丸等均以半速运行，自身移动/转向也受影响。
    public static final float HIT_GLOBAL_SLOW_DURATION = CHECK_INTERVAL; // 持续的自身局部时间（秒）；默认一段卡肉判定间隔，0.5倍全局时缓下约持续0.133现实秒（无其他时流）。
    // 同类命中只刷新时缓时间，不会因双持命中叠成0.25/0.125倍；与其他系统的时流修正正常相乘。
    // 两个时流开关独立；同时开启会叠加旧方案的自身加速/玩家补偿与新方案的全局时缓。
    public static boolean REQUIRE_MISSED_CHECK_BETWEEN_HITS = true; // true时卡肉后跳过下一次检查；持续命中按“触发→跳过→触发”交替，不要求挥空。false时每次命中检查都可触发。
    public static boolean ENABLE_HIT_RECOIL = true; // 命中后轻微推离自身；false仅关闭后推，不影响卡肉与粒子。
    public static boolean ENABLE_HIT_LATERAL_DAMPING = true; // 命中时削弱横向相对速度；独立于后推开关，只减速，不添加横向基础推力。
    public static final float HIT_LATERAL_SPEED_DAMPING = 0.10f; // 每次触发削弱横向相对速度的比例，范围0~1；0.1表示100→90→81，不会减到反向。
    public static final float HIT_RECOIL_SPEED = 10f; // 基础后推速度增量：相对静止、横向移动或正在远离目标时，每次触发使用此值，单位/秒。
    public static final float HIT_RECOIL_CLOSING_SPEED_DAMPING = 0.15f; // 每次命中额外抵消的接近速度比例，范围0~1；0关闭高速增强，0.15表示抵消15%的接近速度。
    public static final float HIT_RECOIL_MAX_SPEED_CHANGE = 60f; // 单次后推速度增量上限，包含基础量和高速增强，避免极高速撞击时突然大幅改变速度。
    public static final float HIT_RECOIL_MAX_RETREAT_SPEED = 50f; // 相对目标、沿后推方向的速度上限；只限制新增冲量，不削减已有速度或侧向速度。
    private static final float MAX_CONTACT_STEP = 120f; // 相邻帧接触点跳跃超过120单位就断开，避免隔空拉出火线。
    private static final float MAX_SCAR_LENGTH = 90f; // 两次采样最多连成90单位长的火线；不是火线宽度。
    private static final float SCAR_LIFETIME = 0.32f; // 每一小段舰体火线存活0.32秒；从生成起逐渐淡出，无单独保持阶段。
    private static final int MAX_SCARS = 128; // 所有近战武器共享，不随战斗时长增长。
    private static final String RENDERER_KEY = "moci_melee_contact_renderer";
    private static final String SPARK_RENDERER_KEY = "moci_melee_spark_renderer";
    public static boolean ENABLE_INSTANCED_SPARKS = true; // 仅切换飞溅火花渲染：true用共享实例化/批绘制，false沿用引擎粒子，方便同场景对比；不影响命中逻辑。
    private static final String HIT_TIME_KEY = "moci_melee_hit_time_flow";
    private static final String HIT_GLOBAL_SLOW_KEY = "moci_melee_hit_global_slow";
    // 火花使用RGBA，取值0~255，依次为红/绿/蓝/透明度；火线颜色另见 render()，不会随这里一起变色。
    private static final Color GOLD = new Color(255, 177, 32, 255); // 普通火花：不透明金橙色。
    private static final Color HOT = new Color(255, 246, 181, 255); // 少量随机火花与接触亮点：偏白的浅黄色。

    private final Vector2f previousFrom = new Vector2f();
    private float previousAngle;
    private boolean hasMotion;
    private CombatEntityAPI contactTarget;
    private final Vector2f previousLocal = new Vector2f();
    private final Vector2f sampledLocal = new Vector2f();
    private boolean hasSample;
    private boolean previousHullContact;
    private float sampleTimer;
    private float sparkCredit;
    private float smokeCredit;
    private float untilCheck;
    private float speedMult = 1f;
    private boolean previousCheckTriggered;

    /** 返回卡肉后的挥砍时间，并在同次命中判定施加时流和后推；离屏也生效，不依赖伤害额度。 */
    public float advanceSlowdown(float amount, CombatEngineAPI engine, WeaponAPI weapon, BeamAPI beam, boolean active) {
        if (amount <= 0f) return 0f;
        if (!active) {
            resetSlowdown();
            return amount;
        }
        CombatEntityAPI target = beam.getDamageTarget();
        boolean enemyContact = target instanceof ShipAPI && weapon.getShip() != null
                && target.getOwner() != weapon.getShip().getOwner();
        // 卡顿帧不重放历史检查，只按当前接触确定一次速度。
        if (amount > 0.1f) {
            checkHit(engine, weapon, beam, enemyContact);
            untilCheck = CHECK_INTERVAL;
            return amount * speedMult;
        }
        float remaining = amount;
        float animationTime = 0f;
        while (remaining > 0f) {
            if (untilCheck <= 0.000001f) {
                checkHit(engine, weapon, beam, enemyContact);
                untilCheck = CHECK_INTERVAL;
            }
            // 跨越检查边界时分段计时，保持不同帧率下的减速时长一致。
            float step = Math.min(remaining, untilCheck);
            animationTime += step * speedMult;
            remaining -= step;
            untilCheck -= step;
        }
        return animationTime;
    }

    public void resetSlowdown() {
        untilCheck = 0f;
        speedMult = 1f;
        previousCheckTriggered = false;
    }

    private boolean shouldSlowForHit(boolean enemyContact) {
        boolean slow = enemyContact && (!REQUIRE_MISSED_CHECK_BETWEEN_HITS || !previousCheckTriggered);
        // 记录“实际触发”，而不是“碰到了敌人”；跳过一次后即恢复资格，持续接触也不会锁死。
        previousCheckTriggered = slow;
        return slow;
    }

    private void checkHit(CombatEngineAPI engine, WeaponAPI weapon, BeamAPI beam, boolean enemyContact) {
        boolean triggered = shouldSlowForHit(enemyContact);
        speedMult = triggered ? HIT_SPEED_MULT : 1f;
        // 与卡肉共用间隔开关；同一次命中不能逐帧重复施加冲量。
        if (triggered && (ENABLE_HIT_RECOIL || ENABLE_HIT_LATERAL_DAMPING)) {
            applyHitVelocityEffects(weapon.getShip(), beam);
        }
        if (!triggered || engine == null || engine.isPaused()) return;
        boolean timeFlow = ENABLE_HIT_TIME_FLOW && HIT_TIME_MULT > 1f && HIT_TIME_DURATION > 0f;
        boolean globalSlow = ENABLE_HIT_GLOBAL_SLOW && HIT_GLOBAL_TIME_MULT > 0f && HIT_GLOBAL_TIME_MULT < 1f
                && HIT_GLOBAL_SLOW_DURATION > 0f && weapon.getShip() == engine.getPlayerShip();
        if (timeFlow || globalSlow) {
            HitTimeFlow flow = (HitTimeFlow) engine.getCustomData().get(HIT_TIME_KEY);
            if (flow == null) {
                flow = new HitTimeFlow(engine);
                engine.getCustomData().put(HIT_TIME_KEY, flow);
                engine.addPlugin(flow);
            }
            flow.refresh(weapon.getShip(), timeFlow, globalSlow);
        }
    }

    /** 每场战斗共用一个管理器，只遍历正处于命中时流中的舰船；不扫描全场，也不静态持有战斗对象。 */
    private static final class HitTimeFlow extends BaseEveryFrameCombatPlugin {
        private final CombatEngineAPI engine;
        private final Map<ShipAPI, HitTimeState> activeShips = new IdentityHashMap<>();

        private HitTimeFlow(CombatEngineAPI engine) {
            this.engine = engine;
        }

        private void refresh(ShipAPI ship, boolean timeFlow, boolean globalSlow) {
            if (!ship.isAlive() || !engine.isEntityInPlay(ship)) return;
            HitTimeState state = activeShips.get(ship);
            if (state == null) {
                state = new HitTimeState();
                activeShips.put(ship, state);
            }
            if (timeFlow) {
                state.flowRemaining = Math.max(state.flowRemaining, HIT_TIME_DURATION);
                // 所有近战武器共用同一个舰船修正项；双持命中不会重复叠加局部时流。
                ship.getMutableStats().getTimeMult().modifyMult(HIT_TIME_KEY, HIT_TIME_MULT);
            }
            if (globalSlow) state.slowRemaining = Math.max(state.slowRemaining, HIT_GLOBAL_SLOW_DURATION);
            state.refreshedAt = engine.getTotalElapsedTime(false);
            updateGlobalTime();
        }

        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            if (engine.isPaused() || activeShips.isEmpty()) return;
            float now = engine.getTotalElapsedTime(false);
            Iterator<Map.Entry<ShipAPI, HitTimeState>> iterator = activeShips.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<ShipAPI, HitTimeState> entry = iterator.next();
                ShipAPI ship = entry.getKey();
                HitTimeState state = entry.getValue();
                // 自身时流越快，消耗同一段局部持续时间所需的战场时间越短；暂停不计时。
                // 刷新当帧不扣掉命中前已过去的时间，避免插件调用顺序导致效果刚添加就过期。
                if (now != state.refreshedAt) {
                    float localAmount = Math.max(0f, amount) * ship.getMutableStats().getTimeMult().getModifiedValue();
                    state.flowRemaining -= localAmount;
                    state.slowRemaining -= localAmount;
                }
                boolean invalid = !ship.isAlive() || !engine.isEntityInPlay(ship);
                if (!ENABLE_HIT_TIME_FLOW || invalid || state.flowRemaining <= 0f) {
                    ship.getMutableStats().getTimeMult().unmodify(HIT_TIME_KEY);
                    state.flowRemaining = 0f;
                }
                if (!ENABLE_HIT_GLOBAL_SLOW || invalid || ship != engine.getPlayerShip() || state.slowRemaining <= 0f) {
                    state.slowRemaining = 0f;
                }
                if (state.flowRemaining <= 0f && state.slowRemaining <= 0f) iterator.remove();
            }
            // 每帧重新核对玩家控制对象；换船后旧船保留局部时流，全局时缓只跟随当前玩家船。
            updateGlobalTime();
        }

        private void updateGlobalTime() {
            HitTimeState playerState = activeShips.get(engine.getPlayerShip());
            if (ENABLE_HIT_TIME_FLOW && playerState != null && playerState.flowRemaining > 0f) {
                engine.getTimeMult().modifyMult(HIT_TIME_KEY, 1f / HIT_TIME_MULT);
            } else {
                engine.getTimeMult().unmodify(HIT_TIME_KEY);
            }
            if (ENABLE_HIT_GLOBAL_SLOW && playerState != null && playerState.slowRemaining > 0f) {
                // 与旧方案使用不同的修正项，开关/到期互不覆盖；自身没有新方案的局部加速补偿。
                engine.getTimeMult().modifyMult(HIT_GLOBAL_SLOW_KEY, HIT_GLOBAL_TIME_MULT);
            } else {
                engine.getTimeMult().unmodify(HIT_GLOBAL_SLOW_KEY);
            }
        }
    }

    private static final class HitTimeState {
        private float flowRemaining;
        private float slowRemaining;
        private float refreshedAt;
    }

    private void applyHitVelocityEffects(ShipAPI ship, BeamAPI beam) {
        Vector2f velocity = ship.getVelocity();
        float awayX = ship.getLocation().x - beam.getTo().x;
        float awayY = ship.getLocation().y - beam.getTo().y;
        float length = (float) Math.sqrt(awayX * awayX + awayY * awayY);
        if (length > 0.001f) {
            awayX /= length;
            awayY /= length;
        } else {
            // 命中点恰好与自身中心重合时，回退到舰船正后方。
            float facing = (float) Math.toRadians(ship.getFacing());
            awayX = -(float) Math.cos(facing);
            awayY = -(float) Math.sin(facing);
        }
        Vector2f targetVelocity = beam.getDamageTarget().getVelocity();
        float retreatSpeed = (velocity.x - targetVelocity.x) * awayX
                + (velocity.y - targetVelocity.y) * awayY;
        float closingSpeed = Math.max(0f, -retreatSpeed); // 只取朝命中点的相对速度；同速追随、横向飞行和正在远离都为0。
        float damping = Math.max(0f, Math.min(1f, HIT_RECOIL_CLOSING_SPEED_DAMPING));
        // 先保留低速时的基础后推，再按接近速度增强；每次判定都用当前速度重新计算，接近越慢阻尼越弱。
        // 当前数值示例：接近速度0/100/200时，期望抵消10/25/40；更高速度受单次上限60约束。
        float requestedImpulse = Math.min(Math.max(0f, HIT_RECOIL_MAX_SPEED_CHANGE),
                Math.max(0f, HIT_RECOIL_SPEED) + closingSpeed * damping);
        float impulse = Math.min(requestedImpulse,
                Math.max(0f, HIT_RECOIL_MAX_RETREAT_SPEED - retreatSpeed));
        // 与参考代码的 applyForce 一样叠加速度，不瞬移；直接限定速度增量，避免轻机体被大目标弹飞。
        if (ENABLE_HIT_RECOIL) {
            velocity.x += awayX * impulse;
            velocity.y += awayY * impulse;
        }
        if (ENABLE_HIT_LATERAL_DAMPING) {
            // 横向=垂直于“自身中心→命中点”的方向；使用相对目标的速度，同速跟随不会被额外刹停。
            float tangentX = -awayY;
            float tangentY = awayX;
            float lateralSpeed = (velocity.x - targetVelocity.x) * tangentX
                    + (velocity.y - targetVelocity.y) * tangentY;
            float lateralDamping = Math.max(0f, Math.min(1f, HIT_LATERAL_SPEED_DAMPING));
            // 只按比例消除现有横向速度；横向相对静止时为0，不会凭空侧推，也不改变正面后推分量。
            velocity.x -= tangentX * lateralSpeed * lateralDamping;
            velocity.y -= tangentY * lateralSpeed * lateralDamping;
        }
    }

    /** 光剑沿线烟雾与命中烟雾共用的生成入口。 */
    public static void emitSmoke(CombatEngineAPI engine, Vector2f point, Vector2f velocity, Color fringe) {
        // 两个0分别是淡入占比与保持峰值占比；true沿用原光剑的绘制选项。
        engine.addNebulaParticle(new Vector2f(point), new Vector2f(velocity),
                INITIAL_SIZE_MIN + (float) Math.random() * (INITIAL_SIZE_MAX - INITIAL_SIZE_MIN),
                END_SIZE_MULT_MIN + (float) Math.random() * (END_SIZE_MULT_MAX - END_SIZE_MULT_MIN),
                0f, 0f, DURATION,
                new Color(fringe.getRed(), fringe.getGreen(), fringe.getBlue(), ALPHA), true);
    }

    /** active 必须只在真实挥砍时为 true，归位/两刀停顿时也应调用本方法。 */
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam, boolean active, float swingProgress) {
        if (engine.isPaused() || amount <= 0f) return;

        Vector2f from = beam.getFrom();
        Vector2f hit = beam.getTo();
        float dx = hit.x - from.x;
        float dy = hit.y - from.y;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        float angle = (float) Math.atan2(dy, dx);
        boolean usableMotion = hasMotion && amount <= 0.1f && length > 1f; // 超过0.1秒的卡顿帧不估算火花方向，避免异常高速。
        float angularSpeed = usableMotion ? wrapRadians(angle - previousAngle) / amount : 0f;
        float bladeVX = usableMotion ? (from.x - previousFrom.x) / amount - angularSpeed * dy : 0f;
        float bladeVY = usableMotion ? (from.y - previousFrom.y) / amount + angularSpeed * dx : 0f;
        // 不活跃时仍跟踪光束运动，避免下一刀把整段停顿压缩成一帧速度。
        previousFrom.set(from);
        previousAngle = angle;
        hasMotion = length > 1f;

        CombatEntityAPI target = beam.getDamageTarget();
        // 只在真实挥砍、光束亮度至少0.6、命中点位于视野外160单位以内时显示接触效果。
        if (!active || target == null || beam.getBrightness() < 0.6f || length <= 1f
                || !engine.getViewport().isNearViewport(hit, 160f)) {
            resetContact();
            return;
        }

        Vector2f local = toLocal(target, hit);
        boolean hullContact = target instanceof ShipAPI && !hitsShield(target, hit); // 护盾也有火花；只有舰体接触才留下火线。
        boolean continuous = contactTarget == target && amount <= 0.1f
                && distanceSquared(local, previousLocal) <= MAX_CONTACT_STEP * MAX_CONTACT_STEP
                && previousHullContact == hullContact;
        if (!continuous) {
            resetContact();
            contactTarget = target;
            sampleTimer = SAMPLE_INTERVAL;
            smokeCredit = hullContact ? 1f : 0f; // 首次切入立即有少量烟雾，不等待整段间隔。
        }
        Vector2f frameStart = continuous ? new Vector2f(previousLocal) : new Vector2f(local);
        // 逐帧插值只连接本帧真实划过的短线段；换目标、护盾切换、卡顿或跳点都不能隔空补线。
        if (distanceSquared(frameStart, local) > MAX_SCAR_LENGTH * MAX_SCAR_LENGTH) frameStart.set(local);
        previousLocal.set(local);
        previousHullContact = hullContact;
        sampleTimer = Math.min(2f * SAMPLE_INTERVAL, sampleTimer + amount);

        float targetOmega = (float) Math.toRadians(target.getAngularVelocity());
        float targetVX = target.getVelocity().x - targetOmega * (hit.y - target.getLocation().y);
        float targetVY = target.getVelocity().y + targetOmega * (hit.x - target.getLocation().x);
        float relativeVX = usableMotion ? bladeVX - targetVX : 0f;
        float relativeVY = usableMotion ? bladeVY - targetVY : 0f;
        // 投影到刀刃切向，避免光束碰撞终点沿刀身滑动导致火花错误地沿束轴喷射。
        float tangentX = -dy / length;
        float tangentY = dx / length;
        float cuttingSpeed = relativeVX * tangentX + relativeVY * tangentY;
        Vector2f surfaceTangent = surfaceTangent(target, hit, hullContact);
        if (Math.abs(cuttingSpeed) >= 12f) {
            sparkCredit = Math.min(MAX_SPARKS_PER_FRAME, sparkCredit + Math.min(amount, 0.1f) * SPARK_RATE);
            int count = (int) sparkCredit;
            sparkCredit -= count; // 保留小数额度，30/60/144帧下单位时间总量一致。
            emitSparks(engine, target, frameStart, local, targetOmega, tangentX, tangentY, cuttingSpeed,
                    surfaceTangent, -dx / length, -dy / length, count, swingProgress);
        } else {
            sparkCredit = 0f; // 静止/无运动历史时不积欠，下次挥动不会突然补发。
        }
        if (hullContact) {
            smokeCredit = Math.min(2f, smokeCredit + Math.min(amount, 0.1f) * SMOKE_RATE);
            int count = (int) smokeCredit;
            smokeCredit -= count;
            for (int i = 0; i < count; i++) {
                Vector2f point = pointOnTrack(target, frameStart, local, (i + 0.5f) / count);
                Vector2f velocity = new Vector2f(
                        target.getVelocity().x - targetOmega * (point.y - target.getLocation().y),
                        target.getVelocity().y + targetOmega * (point.x - target.getLocation().x));
                // 接触烟雾继承目标表面运动，再叠加光剑同款半径50的随机漂移。
                double driftAngle = Math.random() * Math.PI * 2;
                float driftSpeed = 50f * (float) Math.sqrt(Math.random());
                velocity.x += (float) Math.cos(driftAngle) * driftSpeed;
                velocity.y += (float) Math.sin(driftAngle) * driftSpeed;
                emitSmoke(engine, point, velocity, beam.getFringeColor());
            }
        }

        boolean moved = hasSample && distanceSquared(local, sampledLocal) >= SAMPLE_DISTANCE * SAMPLE_DISTANCE;
        if (sampleTimer < SAMPLE_INTERVAL && !moved) return;
        sampleTimer = 0f;

        Vector2f start = hasSample ? new Vector2f(sampledLocal) : new Vector2f(local);
        boolean connect = hasSample && distanceSquared(start, local) <= MAX_SCAR_LENGTH * MAX_SCAR_LENGTH;
        // 接触亮点独立于飞溅火花，保留你调整的大小7、亮度0.9、寿命0.2秒。
        engine.addHitParticle(new Vector2f(hit), new Vector2f(targetVX, targetVY), 7f, 0.9f, 0.2f, HOT);
        if (hullContact && connect && distanceSquared(start, local) >= 1f) {
            scars(engine).add(target, start, local);
        }
        sampledLocal.set(local);
        hasSample = true;
    }

    private void resetContact() {
        contactTarget = null;
        hasSample = false;
        sampleTimer = 0f;
        sparkCredit = 0f;
        smokeCredit = 0f;
    }

    private void emitSparks(CombatEngineAPI engine, CombatEntityAPI target, Vector2f start, Vector2f end, float targetOmega,
                                   float tangentX, float tangentY, float cuttingSpeed,
                                   Vector2f surfaceTangent, float towardBladeX, float towardBladeY, int count,
                                   float swingProgress) {
        if (count <= 0) return;
        SparkRenderer renderer = ENABLE_INSTANCED_SPARKS ? sparks(engine) : null;
        float speed = Math.abs(cuttingSpeed);
        float direction = cuttingSpeed > 0f ? -1f : 1f; // 刀刃相对目标沿切线正向运动，就沿负向喷射；反之亦然。
        float backwardsX = tangentX * direction;
        float backwardsY = tangentY * direction;
        // 舰体边缘是一条直线，有两个方向；取与反挥砍方向同侧的那条射线。
        float surfaceX = surfaceTangent.x;
        float surfaceY = surfaceTangent.y;
        if (surfaceX * backwardsX + surfaceY * backwardsY < 0f) {
            surfaceX = -surfaceX;
            surfaceY = -surfaceY;
        }
        float surfaceAngle = (float) Math.atan2(surfaceY, surfaceX);
        // 光束的另一条边从接触点指向刀柄/发射源，避免火花朝舰体内部钻。
        float corner = wrapRadians((float) Math.atan2(towardBladeY, towardBladeX) - surfaceAngle);
        // 几乎贴着舰体掠过时夹角可能是180度，选择经过反挥砍侧的半圆。
        if (Math.abs(Math.abs(corner) - (float) Math.PI) < 0.0001f) {
            float side = surfaceX * backwardsY - surfaceY * backwardsX;
            corner = Math.copySign((float) Math.PI, side);
        }
        float baseSpeed = Math.min(35f, 10f + speed * 0.01f); // 保留你的基准速度：10+切割速度×0.01，上限35。
        float progress = Math.max(0f, Math.min(1f, swingProgress));
        float swingSpeedMultiplier = 0.45f + 3.5f * progress; // 一整刀从0.45档平滑增至3.60档，不再每0.066秒循环。
        for (int i = 0; i < count; i++) {
            // 按等长小格的中点铺满本帧划过的轨迹，不把一组火花都放在接触终点。
            Vector2f point = pointOnTrack(target, start, end, (i + 0.5f) / count);
            float surfaceVX = target.getVelocity().x - targetOmega * (point.y - target.getLocation().y);
            float surfaceVY = target.getVelocity().y + targetOmega * (point.x - target.getLocation().x);
            // 0=沿舰体表面，0.5=夹角平分线；只取靠近舰体的那一半，不再使用固定±25度。
            // 例如舰体与光束形成90度夹角，火花只在距舰体表面0~45度范围内随机飞溅。
            float sparkAngle = surfaceAngle + corner * (float) Math.random() * 0.5f;
            float sparkSpeed = baseSpeed * swingSpeedMultiplier * (0.96f + (float) Math.random() * 0.08f); // 当前速度档上下轻微随机，避免同帧火花完全一致。
            float vx = (float) Math.cos(sparkAngle) * sparkSpeed;
            float vy = (float) Math.sin(sparkAngle) * sparkSpeed;
            // 寿命随相对喷射速度严格递减；目标自身飞行速度不影响火花冷却时间。
            // 保留你的寿命公式，约0.539~0.717秒；仍然是速度越快、寿命越短。
            float lifetime = 0.4f + 30f / (sparkSpeed + 90f);
            // 速度叠加目标表面运动，使火花不会因目标高速飞行而被甩到错误的一边。
            // 每颗有4%概率为大小8（其余4），有6%概率为浅黄色（其余金橙色）；亮度均为1。
            float size = Math.random() < 0.04f ? 10f : 6f;
            Color color = Math.random() < 0.06f ? HOT : GOLD;
            if (renderer != null) {
                // 只替换渲染出口：位置、速度、随机调用次序、尺寸、寿命和颜色保持原样。
                renderer.add(point.x, point.y, surfaceVX + vx, surfaceVY + vy, size, lifetime, color);
            } else {
                engine.addHitParticle(new Vector2f(point), new Vector2f(surfaceVX + vx, surfaceVY + vy),
                        size, 1f, lifetime, color);
            }
        }
        if (renderer != null) renderer.updateHostBounds();
    }

    private static Vector2f pointOnTrack(CombatEntityAPI target, Vector2f start, Vector2f end, float fraction) {
        return toWorld(target, new Vector2f(start.x + (end.x - start.x) * fraction,
                start.y + (end.y - start.y) * fraction));
    }

    /** 命中处的表面切线：舰体取最近轮廓边，护盾取圆弧切线，无轮廓时退回圆形近似。 */
    private static Vector2f surfaceTangent(CombatEntityAPI target, Vector2f hit, boolean hullContact) {
        if (hullContact) {
            BoundsAPI bounds = target.getExactBounds();
            if (bounds != null) {
                bounds.update(target.getLocation(), target.getFacing());
                float nearest = Float.MAX_VALUE;
                Vector2f tangent = null;
                for (BoundsAPI.SegmentAPI segment : bounds.getSegments()) {
                    Vector2f a = segment.getP1();
                    Vector2f b = segment.getP2();
                    float x = b.x - a.x;
                    float y = b.y - a.y;
                    float lengthSquared = x * x + y * y;
                    if (lengthSquared < 0.0001f) continue;
                    float t = Math.max(0f, Math.min(1f, ((hit.x - a.x) * x + (hit.y - a.y) * y) / lengthSquared));
                    float ex = hit.x - a.x - t * x;
                    float ey = hit.y - a.y - t * y;
                    float distance = ex * ex + ey * ey;
                    if (distance < nearest) {
                        nearest = distance;
                        if (tangent == null) tangent = new Vector2f();
                        tangent.set(x, y); // 只用于计算角度/正负方向，无需归一化。
                    }
                }
                if (tangent != null) return tangent;
            }
        }
        ShieldAPI shield = hullContact ? null : target.getShield();
        Vector2f center = shield != null ? shield.getLocation() : target.getLocation();
        float x = hit.x - center.x;
        float y = hit.y - center.y;
        if (x * x + y * y < 0.0001f) return new Vector2f(0f, 1f);
        return new Vector2f(-y, x);
    }

    private static boolean hitsShield(CombatEntityAPI target, Vector2f point) {
        ShieldAPI shield = target.getShield();
        if (shield == null || !shield.isOn() || !shield.isWithinArc(point)) return false;
        float innerRadius = Math.max(0f, shield.getRadius() - 6f);
        return distanceSquared(point, shield.getLocation()) >= innerRadius * innerRadius;
    }

    private static float wrapRadians(float angle) {
        while (angle > Math.PI) angle -= (float) (Math.PI * 2);
        while (angle < -Math.PI) angle += (float) (Math.PI * 2);
        return angle;
    }

    private static float distanceSquared(Vector2f a, Vector2f b) {
        float x = a.x - b.x;
        float y = a.y - b.y;
        return x * x + y * y;
    }

    private static Vector2f toLocal(CombatEntityAPI target, Vector2f world) {
        double angle = Math.toRadians(target.getFacing());
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float x = world.x - target.getLocation().x;
        float y = world.y - target.getLocation().y;
        return new Vector2f(x * cos + y * sin, -x * sin + y * cos);
    }

    private static Vector2f toWorld(CombatEntityAPI target, Vector2f local) {
        double angle = Math.toRadians(target.getFacing());
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        return new Vector2f(target.getLocation().x + local.x * cos - local.y * sin,
                target.getLocation().y + local.x * sin + local.y * cos);
    }

    private static SparkRenderer sparks(CombatEngineAPI engine) {
        Object old = engine.getCustomData().get(SPARK_RENDERER_KEY);
        if (old instanceof SparkRenderer) return (SparkRenderer) old;
        SparkRenderer renderer = new SparkRenderer(engine);
        engine.getCustomData().put(SPARK_RENDERER_KEY, renderer);
        engine.addLayeredRenderingPlugin(renderer); // init由引擎调用；不手动重复初始化。
        return renderer;
    }

    /**
     * 所有近战武器共用的纯视觉批次，不参与命中/卡肉/伤害结算。
     * CPU用连续数组保存粒子，不为每颗创建引擎粒子和速度向量；GPU共用一个四边形和实例VBO。
     * 每帧只上传当前可见实例，复用直接内存，不在render里重新创建FloatBuffer。
     */
    private static final class SparkRenderer extends BaseCombatLayeredRenderingPlugin {
        // 每颗依次保存x/y、vx/vy、大小、年龄、寿命、RGBA；此处只储存emitSparks已经算好的结果。
        private static final int PARTICLE_FLOATS = 11;
        private static final int INSTANCE_FLOATS = 7; // GPU每实例：中心xy、大小、RGBA，共28字节。
        private final CombatEngineAPI engine;
        private final Vector2f cullPoint = new Vector2f();
        private float[] particles = new float[1024 * PARTICLE_FLOATS];
        private int count;
        private float minX, minY, maxX, maxY, radius;
        private SpriteAPI texture; // 只读取原版贴图，不修改共享Sprite的颜色/尺寸，也不释放共享纹理。
        private FloatBuffer instances;
        private boolean gpuAttempted, gpuReady;
        private int quadVbo, instanceVbo, vao, program, texSizeUniform, alphaUniform;

        private SparkRenderer(CombatEngineAPI engine) {
            this.engine = engine;
            layer = CombatEngineLayers.ABOVE_PARTICLES_LOWER;
        }

        private void add(float x, float y, float vx, float vy, float size, float life, Color color) {
            int offset = count * PARTICLE_FLOATS;
            if (offset + PARTICLE_FLOATS > particles.length) particles = Arrays.copyOf(particles, particles.length * 2);
            particles[offset] = x;
            particles[offset + 1] = y;
            particles[offset + 2] = vx;
            particles[offset + 3] = vy;
            particles[offset + 4] = size;
            particles[offset + 5] = 0f;
            particles[offset + 6] = life;
            particles[offset + 7] = color.getRed() / 255f;
            particles[offset + 8] = color.getGreen() / 255f;
            particles[offset + 9] = color.getBlue() / 255f;
            particles[offset + 10] = color.getAlpha() / 255f;
            if (count++ == 0) {
                minX = minY = Float.POSITIVE_INFINITY;
                maxX = maxY = Float.NEGATIVE_INFINITY;
            }
            includeBounds(offset);
        }

        @Override
        public void init(CombatEntityAPI entity) {
            super.init(entity);
            updateHostBounds();
        }

        @Override
        public void advance(float amount) {
            if (engine.isPaused() || amount <= 0f || count == 0) return;
            minX = minY = Float.POSITIVE_INFINITY;
            maxX = maxY = Float.NEGATIVE_INFINITY;
            int index = 0;
            while (index < count) {
                int offset = index * PARTICLE_FLOATS;
                particles[offset + 5] += amount;
                if (particles[offset + 5] >= particles[offset + 6]) {
                    // 加色混合不依赖粒子先后顺序；末尾补位，避免ArrayList.remove造成整段搬移。
                    count--;
                    if (index != count) System.arraycopy(particles, count * PARTICLE_FLOATS, particles, offset, PARTICLE_FLOATS);
                    continue;
                }
                // 沿用引擎HitParticle的匀速运动与按寿命线性淡出，不改变飞溅速度。
                particles[offset] += particles[offset + 2] * amount;
                particles[offset + 1] += particles[offset + 3] * amount;
                includeBounds(offset);
                index++;
            }
            updateHostBounds();
        }

        private void includeBounds(int offset) {
            float extent = particles[offset + 4] * 4f; // 沿用引擎粒子的视口边缘余量。
            minX = Math.min(minX, particles[offset] - extent);
            minY = Math.min(minY, particles[offset + 1] - extent);
            maxX = Math.max(maxX, particles[offset] + extent);
            maxY = Math.max(maxY, particles[offset + 1] + extent);
        }

        private void updateHostBounds() {
            if (count == 0) {
                radius = 0f;
                return;
            }
            float halfX = (maxX - minX) * 0.5f;
            float halfY = (maxY - minY) * 0.5f;
            radius = (float) Math.sqrt(halfX * halfX + halfY * halfY);
            if (entity != null) entity.getLocation().set(minX + halfX, minY + halfY);
        }

        @Override
        public float getRenderRadius() {
            return radius;
        }

        private boolean visible(int offset, ViewportAPI viewport) {
            cullPoint.set(particles[offset], particles[offset + 1]);
            return viewport.isNearViewport(cullPoint, particles[offset + 4] * 4f);
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (count == 0) return;
            if (texture == null) texture = Global.getSettings().getSprite("graphics/fx/hit_glow.png");
            ContextCapabilities caps = GLContext.getCapabilities();
            boolean hasVao = caps.OpenGL30 || caps.GL_ARB_vertex_array_object;
            int oldProgram = caps.OpenGL20 ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
            int oldBuffer = caps.OpenGL15 ? GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) : 0;
            int oldVao = hasVao ? GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) : 0;
            int oldActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT
                    | GL11.GL_TEXTURE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            try {
                initGpu(caps);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
                GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                if (gpuReady) {
                    drawInstanced(viewport);
                } else {
                    // 无实例化/VAO支持、着色器编译失败：一个glBegin批次绘制，仍使用相同贴图、尺寸和淡出。
                    if (caps.OpenGL20) GL20.glUseProgram(0);
                    drawFallback(viewport);
                }
            } finally {
                // 自己的VAO隔离attribute/divisor，恢复调用者程序、VAO、VBO、纹理单元和固定管线状态。
                if (caps.OpenGL20) GL20.glUseProgram(oldProgram);
                if (hasVao) ARBVertexArrayObject.glBindVertexArray(oldVao);
                if (caps.OpenGL15) GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldBuffer);
                GL11.glPopAttrib();
                GL13.glActiveTexture(oldActiveTexture);
            }
        }

        private void initGpu(ContextCapabilities caps) {
            if (gpuAttempted) return;
            gpuAttempted = true; // 每场战斗仅尝试一次，空闲时也保留批次，避免反复编译/分配。
            if (!caps.OpenGL20 || !caps.OpenGL15 || !(caps.OpenGL30 || caps.GL_ARB_vertex_array_object)
                    || !caps.GL_ARB_instanced_arrays || !caps.GL_ARB_draw_instanced) return;
            int vertexShader = 0, fragmentShader = 0;
            try {
                vertexShader = compileShader(GL20.GL_VERTEX_SHADER,
                        "#version 120\n"
                        + "attribute vec2 aCorner;\nattribute vec3 iPositionSize;\nattribute vec4 iColor;\n"
                        + "uniform vec2 uTexSize;\nuniform float uAlpha;\n"
                        + "varying vec2 vUV;\nvarying vec4 vColor;\n"
                        + "void main(){\n"
                        + " gl_Position=gl_ModelViewProjectionMatrix*vec4(iPositionSize.xy+aCorner*iPositionSize.z,0.0,1.0);\n"
                        + " vUV=(aCorner+vec2(0.5))*uTexSize;\n vColor=vec4(iColor.rgb,iColor.a*uAlpha);\n}\n");
                fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER,
                        "#version 120\nuniform sampler2D uTexture;\nvarying vec2 vUV;\nvarying vec4 vColor;\n"
                        + "void main(){gl_FragColor=texture2D(uTexture,vUV)*vColor;}\n");
                program = GL20.glCreateProgram();
                GL20.glAttachShader(program, vertexShader);
                GL20.glAttachShader(program, fragmentShader);
                GL20.glBindAttribLocation(program, 0, "aCorner");
                GL20.glBindAttribLocation(program, 1, "iPositionSize");
                GL20.glBindAttribLocation(program, 2, "iColor");
                GL20.glLinkProgram(program);
                if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                    throw new IllegalStateException(GL20.glGetProgramInfoLog(program, 4096));
                }
                GL20.glUseProgram(program);
                GL20.glUniform1i(GL20.glGetUniformLocation(program, "uTexture"), 0);
                texSizeUniform = GL20.glGetUniformLocation(program, "uTexSize");
                alphaUniform = GL20.glGetUniformLocation(program, "uAlpha");
                vao = ARBVertexArrayObject.glGenVertexArrays();
                ARBVertexArrayObject.glBindVertexArray(vao);
                quadVbo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
                FloatBuffer quad = BufferUtils.createFloatBuffer(8);
                quad.put(new float[]{-0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f}).flip();
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
                GL20.glEnableVertexAttribArray(0);
                GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
                instanceVbo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
                GL20.glEnableVertexAttribArray(1);
                GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, INSTANCE_FLOATS * 4, 0L);
                ARBInstancedArrays.glVertexAttribDivisorARB(1, 1);
                GL20.glEnableVertexAttribArray(2);
                GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, INSTANCE_FLOATS * 4, 12L);
                ARBInstancedArrays.glVertexAttribDivisorARB(2, 1);
                gpuReady = true;
            } catch (RuntimeException ex) {
                deleteGpu();
                Global.getLogger(Moci_MeleeHitEffects.class).warn("近战火花实例化初始化失败，改用批量绘制", ex);
            } finally {
                if (vertexShader != 0) GL20.glDeleteShader(vertexShader);
                if (fragmentShader != 0) GL20.glDeleteShader(fragmentShader);
            }
        }

        private static int compileShader(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                String log = GL20.glGetShaderInfoLog(shader, 4096);
                GL20.glDeleteShader(shader);
                throw new IllegalStateException(log);
            }
            return shader;
        }

        private void drawInstanced(ViewportAPI viewport) {
            int required = count * INSTANCE_FLOATS;
            if (instances == null || instances.capacity() < required) {
                int capacity = instances == null ? 1024 * INSTANCE_FLOATS : instances.capacity();
                while (capacity < required) capacity *= 2;
                instances = BufferUtils.createFloatBuffer(capacity);
            }
            instances.clear();
            for (int offset = 0; offset < count * PARTICLE_FLOATS; offset += PARTICLE_FLOATS) {
                if (!visible(offset, viewport)) continue;
                instances.put(particles[offset]).put(particles[offset + 1]).put(particles[offset + 4]);
                instances.put(particles[offset + 7]).put(particles[offset + 8]).put(particles[offset + 9]);
                instances.put(particles[offset + 10] * (1f - particles[offset + 5] / particles[offset + 6]));
            }
            int visibleCount = instances.position() / INSTANCE_FLOATS;
            if (visibleCount == 0) return;
            instances.flip();
            ARBVertexArrayObject.glBindVertexArray(vao);
            GL20.glUseProgram(program);
            GL20.glUniform2f(texSizeUniform, texture.getTextureWidth(), texture.getTextureHeight());
            GL20.glUniform1f(alphaUniform, viewport.getAlphaMult());
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceVbo);
            // orphan旧存储再上传，避免等待上一帧GPU读完；直接内存缓冲复用，不逐帧分配。
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) instances.capacity() * 4L, GL15.GL_STREAM_DRAW);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, instances);
            ARBDrawInstanced.glDrawArraysInstancedARB(GL11.GL_TRIANGLE_STRIP, 0, 4, visibleCount);
        }

        private void drawFallback(ViewportAPI viewport) {
            float tw = texture.getTextureWidth(), th = texture.getTextureHeight();
            GL11.glBegin(GL11.GL_QUADS);
            try {
                for (int offset = 0; offset < count * PARTICLE_FLOATS; offset += PARTICLE_FLOATS) {
                    if (!visible(offset, viewport)) continue;
                    float x = particles[offset], y = particles[offset + 1], half = particles[offset + 4] * 0.5f;
                    float alpha = particles[offset + 10] * (1f - particles[offset + 5] / particles[offset + 6]) * viewport.getAlphaMult();
                    GL11.glColor4f(particles[offset + 7], particles[offset + 8], particles[offset + 9], alpha);
                    GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(x - half, y - half);
                    GL11.glTexCoord2f(0f, th); GL11.glVertex2f(x - half, y + half);
                    GL11.glTexCoord2f(tw, th); GL11.glVertex2f(x + half, y + half);
                    GL11.glTexCoord2f(tw, 0f); GL11.glVertex2f(x + half, y - half);
                }
            } finally {
                GL11.glEnd();
            }
        }

        private void deleteGpu() {
            if (vao != 0) { ARBVertexArrayObject.glDeleteVertexArrays(vao); vao = 0; }
            if (quadVbo != 0) { GL15.glDeleteBuffers(quadVbo); quadVbo = 0; }
            if (instanceVbo != 0) { GL15.glDeleteBuffers(instanceVbo); instanceVbo = 0; }
            if (program != 0) { GL20.glDeleteProgram(program); program = 0; }
            gpuReady = false;
        }

        @Override
        public void cleanup() {
            deleteGpu(); // 引擎清理回调中释放本批次自建GPU资源；共享原版纹理归引擎管理。
            count = 0;
            radius = 0f;
            instances = null;
            if (engine.getCustomData().get(SPARK_RENDERER_KEY) == this) engine.getCustomData().remove(SPARK_RENDERER_KEY);
        }
    }

    private static ScarRenderer scars(CombatEngineAPI engine) {
        Object old = engine.getCustomData().get(RENDERER_KEY);
        if (old instanceof ScarRenderer && !((ScarRenderer) old).isExpired()) return (ScarRenderer) old;
        ScarRenderer renderer = new ScarRenderer(engine);
        engine.getCustomData().put(RENDERER_KEY, renderer);
        engine.addLayeredRenderingPlugin(renderer);
        return renderer;
    }

    private static final class Scar {
        private final CombatEntityAPI target;
        private final Vector2f from;
        private final Vector2f to;
        private float age;

        private Scar(CombatEntityAPI target, Vector2f from, Vector2f to) {
            this.target = target;
            this.from = new Vector2f(from);
            this.to = new Vector2f(to);
        }
    }

    /** 一场战斗只共用一个小渲染器，无新贴图；超过上限时淘汰最旧的火线。 */
    private static final class ScarRenderer extends BaseCombatLayeredRenderingPlugin {
        private final CombatEngineAPI engine;
        private final List<Scar> marks = new ArrayList<Scar>(MAX_SCARS);
        private boolean expired;

        private ScarRenderer(CombatEngineAPI engine) {
            this.engine = engine;
            layer = CombatEngineLayers.ABOVE_SHIPS_LAYER; // 火线绘制在舰体上方，端点保存在目标局部坐标中，随其移动/旋转。
        }

        private void add(CombatEntityAPI target, Vector2f from, Vector2f to) {
            if (marks.size() == MAX_SCARS) marks.remove(0);
            marks.add(new Scar(target, from, to));
        }

        @Override
        public void advance(float amount) {
            if (engine.isPaused()) return;
            for (int i = marks.size() - 1; i >= 0; i--) {
                Scar scar = marks.get(i);
                scar.age += amount;
                if (scar.age >= SCAR_LIFETIME || !engine.isEntityInPlay(scar.target)) marks.remove(i);
            }
            if (marks.isEmpty()) {
                expired = true;
                if (engine.getCustomData().get(RENDERER_KEY) == this) engine.getCustomData().remove(RENDERER_KEY);
            }
        }

        @Override
        public boolean isExpired() {
            return expired;
        }

        @Override
        public float getRenderRadius() {
            // 火线散布在不同目标上；每条线在 render 内单独裁剪。
            return Float.MAX_VALUE;
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (expired) return;
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE); // 加色混合：颜色叠加发亮，重叠线段更亮，不是黑烟的常规透明混合。
            GL11.glBegin(GL11.GL_QUADS);
            for (Scar scar : marks) {
                Vector2f from = toWorld(scar.target, scar.from);
                Vector2f to = toWorld(scar.target, scar.to);
                if (!viewport.isNearViewport(from, MAX_SCAR_LENGTH + 10f)) continue;
                float dx = to.x - from.x;
                float dy = to.y - from.y;
                float length = (float) Math.sqrt(dx * dx + dy * dy);
                if (length < 0.01f) continue;
                float fade = Math.max(0f, 1f - scar.age / SCAR_LIFETIME); // 寿命开始时1，0.32秒后0；火线宽度不变，仅透明度降低。
                float alpha = fade * viewport.getAlphaMult();
                if (scar.target instanceof ShipAPI) alpha *= ((ShipAPI) scar.target).getAlphaMult();
                float nx = -dy / length;
                float ny = dx / length;
                // drawStrip最后5项：半宽、红、绿、蓝、透明度。RGB/透明度用0~1；实际总宽度=半宽×2。
                // 外层橙红光晕：总宽9，RGB约(255,82,5)，初始透明度0.22，之后随fade线性淡出。
                drawStrip(from, to, nx, ny, 4.5f, 1f, 0.32f, 0.02f, alpha * 0.22f);
                // 中层金色火线：总宽3.6，RGB约(255,171,20)，初始透明度0.8，之后随fade线性淡出。
                drawStrip(from, to, nx, ny, 1.8f, 1f, 0.67f, 0.08f, alpha * 0.8f);
                // 内层白黄热芯：总宽1.3，RGB约(255,250,184)，初始透明度1；额外乘fade，即按fade²更快暗下去。
                drawStrip(from, to, nx, ny, 0.65f, 1f, 0.98f, 0.72f, alpha * fade);
            }
            GL11.glEnd();
            GL11.glPopAttrib();
        }

        private static void drawStrip(Vector2f from, Vector2f to, float nx, float ny, float halfWidth,
                                      float r, float g, float b, float alpha) {
            float x = nx * halfWidth;
            float y = ny * halfWidth;
            GL11.glColor4f(r, g, b, alpha);
            GL11.glVertex2f(from.x + x, from.y + y);
            GL11.glVertex2f(from.x - x, from.y - y);
            GL11.glVertex2f(to.x - x, to.y - y);
            GL11.glVertex2f(to.x + x, to.y + y);
        }
    }
}
