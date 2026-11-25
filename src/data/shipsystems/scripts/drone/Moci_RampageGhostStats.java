package data.shipsystems.scripts.drone;

import data.scripts.util.Moci_TextLoader;
import java.util.ArrayList;
import java.util.List;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.loading.specs.BaseWeaponSpec;

import data.scripts.util.Moci_Util;

/**
 * 暴走亡灵(Moci_RampageGhost)：围绕目标连续闪现，每次落地用 ARM_R_MAIN 主炮开一炮；
 * 结束后闪回激活时的位置并重装弹药。
 * 每次落地出现向内的径向波；出发点的残影按共享开关飞向落点，或向后淡出并内收。
 * 多残影渲染不可用时，退回原版闪现的观感(一个残像与本体交叉淡化)。
 * 另一个版本见 Moci_RampageGhostArrayStats(舰船不动，由残影列阵开火)。
 *
 * Moci_RampageGhost.system 配了 runScriptWhileIdle，所以 apply() 在待机时也每帧执行，
 * 而 unapply() 只在舰船撤退时被调用。激活/结束因此在 apply() 里靠状态变化自行判定；
 * 结束后的闪回也依赖待机时继续执行，不要去掉这个配置。
 */
public class Moci_RampageGhostStats extends BaseShipSystemScript {
    // ==================== 可调参数 ====================
    // 各阶段时长、冷却、幅能消耗不在这里，在 data/shipsystems/ship_systems.csv 的 Moci_RampageGhost 一行：
    //   charge up = 充能秒数(期间闪现从慢到快)   active = 满功率秒数
    //   down      = 收尾秒数(闪现逐渐变慢)       cooldown = 冷却秒数
    //   f/u (base cap) = 启动时消耗的幅能，按幅能容量的比例

    // ---------- 目标与伤害 ----------
    /** 系统选取目标的基础射程(单位)。实际射程 = 该值 x (1 + 能量武器射程加成%)。调大可以对更远的敌人启动 */
    private static final float BASE_RANGE = 1200f;
    /** 系统期间全舰能量武器的伤害倍率。0.5 = 伤害减半，1 = 不减伤 */
    public static final float DAMAGE_MULT = 0.5f;
    /** 开火武器所在的槽位 ID，即 .ship 文件 weaponSlots 里的 id。改成别的槽位就用那个槽位上的武器开火 */
    public static final String FIRE_SLOT_ID = "ARM_R_MAIN";

    // ---------- 闪现节奏 ----------
    /** 满功率时两次闪现的间隔(秒)，也是两发主炮的间隔。调小 = 闪得更快、打得更多。充能和收尾阶段会按系统强度放慢 */
    private static final float BLINK_INTERVAL = 0.2f;

    // ---------- 残影(每次闪现在原位置留下一个，多个并存) ----------
    /** 残影从留下到完全消失的秒数。同屏残影数约等于 该值 / 闪现间隔，现在满功率时约 2 到 3 个。调大 = 残影留得更久、同屏更多 */
    private static final float AFTERIMAGE_SECONDS = 0.5f;
    /** 残影刚留下时的透明度 0..1，之后匀速淡到 0。调低 = 残影更淡 */
    private static final float AFTERIMAGE_START_ALPHA = 0.85f;
    /** 残影淡出期间向自己身后退开的总距离(单位)，每个残影在下限和上限之间随机取值。两个都设为 0 = 原地渐隐 */
    private static final float AFTERIMAGE_DRIFT_MIN = 75f;
    private static final float AFTERIMAGE_DRIFT_MAX = 150f;

    // 径向波作用于屏幕画面；风痕只画残影自身。
    private static final boolean DISTORTION_ENABLED = true;
    private static final float BODY_RELAX_SECONDS = 0.15f;
    private static final float BODY_ARRIVE_ALPHA = 0.4f;

    // ---------- 备用观感(只在多残影渲染不可用时生效：原版闪现那种一个残像与本体交叉淡化) ----------
    /** "瞬移前"的最长秒数：本体留在原地淡到半透明，目的地的残像淡入。原版闪现是 0.25。实际取 min(该值, 当前间隔的一半) */
    private static final float BLINK_IN_MAX = 0.25f;
    /** "瞬移后"的最长秒数：本体在新位置恢复不透明，原位置的残像淡出。原版闪现是 0.25。实际取 min(该值, 当前间隔的一半) */
    private static final float BLINK_OUT_MAX = 0.25f;
    /** jitter(船体抖散成多重叠影)强度。0 = 关闭，只剩残像；1 = 原版闪现的强度 */
    private static final float JITTER_INTENSITY = 0f;

    // ---------- 落点 ----------
    /** 落点围绕站位点随机散布的最大半径(单位)。调大 = 跳得更散。落点偏向中心，平均偏离约为该值的三分之一 */
    private static final float SCATTER_RADIUS = 500f;
    /** 相邻两次落点的最小距离(单位)，防止几乎原地闪现 */
    private static final float MIN_TELEPORT_DISTANCE = 100f;
    /** 每次闪现最多尝试多少个随机落点。调大更容易找到全部达标的落点，全部不达标时会退而求其次 */
    private static final int BLINK_SPOT_ATTEMPTS = 12;
    /** 站位点到目标距离的上限，按主炮射程的比例。大型、迟钝的目标取这个值。调小 = 贴得更近、更容易命中、也更危险 */
    private static final float STANDOFF_MAX_FRACTION = 0.75f;
    /** 站位点到目标距离的下限，按主炮射程的比例。小型、灵活的目标取这个值 */
    private static final float STANDOFF_MIN_FRACTION = 0.5f;
    /** 落点要求"弹体飞到目标的距离"不超过主炮射程的这个比例。小于 1 是给目标远离留余量；调大 = 允许从更远处开火 */
    private static final float MAX_FIRE_RANGE_FRACTION = 0.9f;
    /** 落点与目标之间，除双方碰撞半径外再留出的距离(单位)。调大 = 不会贴脸 */
    private static final float MIN_GAP_TO_TARGET = 100f;
    /** 瞄准角迭代次数。炮口不在舰船中心，转向后炮口位置会变，需要反复修正；3 次已足够，一般不用改 */
    private static final int AIM_ITERATIONS = 3;

    // ---------- 结束时 ----------
    /** true = 系统结束后闪回激活时的位置和朝向(原位被占时落在附近的空位)；false = 留在最后一次闪现的位置 */
    private static final boolean RETURN_TO_START_ON_END = true;
    /** true = 系统结束时把所有使用弹药的武器装满 */
    private static final boolean REFILL_AMMO_ON_END = true;

    private ShipAPI ship = null;
    private Moci_Util.SkimmerBlink blink = null;
    private final List<WeaponAPI> fireWeapons = new ArrayList<>();

    private boolean active = false;
    private float blinkTimer = 0f;
    private boolean fireOnArrival = false;
    private boolean pendingReturn = false;
    private ShipAPI lockedTarget = null;
    private Vector2f lastKnownTargetLocation = null;
    private Vector2f startLocation = null;
    private float startFacing = 0f;

    /** 留在闪现出发点的一个残影 */
    private static final class Afterimage {
        final Vector2f origin = new Vector2f();
        final Moci_GhostVisuals.Exit exit = new Moci_GhostVisuals.Exit();
        Moci_GhostVisuals.Pulse pulse;
        float facing;
        float elapsed;
        /** 淡出期间后退的总距离，留下时随机确定 */
        float drift;
        Moci_Util.ShipCopyRenderer.ShipCopy copy;
    }

    private Moci_Util.ShipCopyRenderer renderer = null;
    private final List<Afterimage> afterimages = new ArrayList<>();
    /** 本体落地过渡期间代替本体显示的副本；不在过渡期时为 null */
    private Moci_Util.ShipCopyRenderer.ShipCopy bodyCopy = null;
    private Moci_GhostVisuals.Pulse bodyPulse;
    private float bodyElapsed = 0f;
    /** 每帧记下瞬移前的位置和朝向，残影要留在这里 */
    private final Vector2f departLocation = new Vector2f();
    private float departFacing = 0f;

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        if (!(stats.getEntity() instanceof ShipAPI)) return;
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        init((ShipAPI) stats.getEntity());

        boolean running = state == State.IN || state == State.ACTIVE || state == State.OUT;
        if (running && !active) {
            onActivate();
        } else if (!running && active) {
            onDeactivate();
        }

        // 系统结束时若还有一次闪现没落地，减伤保留到那一炮打出去为止
        if (running || (fireOnArrival && blink.isBusy())) {
            stats.getEnergyWeaponDamageMult().modifyMult(id + "_damage_debuff", DAMAGE_MULT);
        } else {
            stats.getEnergyWeaponDamageMult().unmodify(id + "_damage_debuff");
        }

        float amount = engine.getElapsedInLastFrame();
        if (running) {
            // 随着系统的chargeup积累，闪现速度越来越快
            blinkTimer += amount * effectLevel;
            if (blinkTimer >= BLINK_INTERVAL && blink.getPhase() != Moci_Util.SkimmerBlink.Phase.IN) {
                blinkTimer = Math.min(blinkTimer - BLINK_INTERVAL, BLINK_INTERVAL);
                startBlink(effectLevel);
            }
        } else if (pendingReturn && blink.getPhase() != Moci_Util.SkimmerBlink.Phase.IN) {
            pendingReturn = false;
            fireOnArrival = false;
            if (startLocation != null) {
                beginBlink(Moci_Util.findFreeTeleportSpot(ship, startLocation), startFacing,
                        BLINK_IN_MAX, BLINK_OUT_MAX);
            }
        }

        departLocation.set(ship.getLocation());
        departFacing = ship.getFacing();
        if (blink.advance(amount)) {
            onTeleported();
            if (fireOnArrival) {
                fireOnArrival = false;
                fireAfterTeleport();
            }
        }
        advanceVisuals(amount);
        // 系统结束、闪回完成、残影都消失后，才把渲染器撤掉
        if (!running && !pendingReturn && !blink.isBusy() && bodyPulse == null && afterimages.isEmpty()) {
            releaseVisuals();
        }
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        // 仅在舰船撤退时(或日后去掉 runScriptWhileIdle 后的系统结束时)被调用：只做清理，不再瞬移或开火
        stats.getEnergyWeaponDamageMult().unmodify(id + "_damage_debuff");
        if (blink != null) blink.cancel();
        releaseVisuals();
        if (active && REFILL_AMMO_ON_END && ship != null) refillAmmo(ship);
        active = false;
        fireOnArrival = false;
        pendingReturn = false;
        lockedTarget = null;
        lastKnownTargetLocation = null;
        startLocation = null;
    }

    private void init(ShipAPI owner) {
        if (ship == owner && blink != null) return;
        ship = owner;
        blink = new Moci_Util.SkimmerBlink(owner);
        blink.jitterIntensity = JITTER_INTENSITY;
        fireWeapons.clear();
        fireWeapons.addAll(findFireWeapons(owner));
    }

    private void onActivate() {
        active = true;
        blinkTimer = 0f;
        fireOnArrival = false;
        pendingReturn = false;
        lockedTarget = null;
        lastKnownTargetLocation = null;
        startLocation = new Vector2f(ship.getLocation());
        startFacing = ship.getFacing();
        resolveTarget();

        if (renderer == null || !renderer.isWorking()) {
            if (renderer != null) renderer.expire();
            renderer = Moci_Util.createShipCopyRenderer(ship);
        }
    }

    private void onDeactivate() {
        active = false;
        lockedTarget = null;
        if (REFILL_AMMO_ON_END) refillAmmo(ship);
        pendingReturn = RETURN_TO_START_ON_END;
    }

    // ==================== 残影与本体落地 ====================

    /** 多残影渲染是否可用；不可用时整套表现退回原版闪现的残像交叉淡化 */
    private boolean useMultiAfterimages() {
        return renderer != null && renderer.isWorking();
    }

    /**
     * 发起一次闪现。多残影可用时瞬移没有前摇，残影和本体落地由本类自己画；
     * 不可用时交给 SkimmerBlink 画原版的残像，用传入的前后时长。
     */
    private boolean beginBlink(Vector2f destination, float facing, float legacyInSeconds, float legacyOutSeconds) {
        boolean multi = useMultiAfterimages();
        blink.renderCopy = !multi;
        return blink.begin(destination, facing, multi ? 0f : legacyInSeconds, multi ? 0f : legacyOutSeconds);
    }

    /** 刚完成一次瞬移：在出发点留下残影，本体开始落地过渡 */
    private void onTeleported() {
        boolean multi = useMultiAfterimages();
        Afterimage image = new Afterimage();
        image.origin.set(departLocation);
        image.facing = departFacing;
        image.drift = AFTERIMAGE_DRIFT_MIN + (AFTERIMAGE_DRIFT_MAX - AFTERIMAGE_DRIFT_MIN) * Misc.random.nextFloat();
        image.exit.begin(image.origin);
        image.exit.link(ship.getLocation()); // 闪现已完成，包含避障后的实际落点和最终闪回。
        if (multi) {
            image.copy = renderer.addCopy();
            image.copy.location.set(image.origin);
            image.copy.facing = image.facing;
            image.copy.alpha = AFTERIMAGE_START_ALPHA;
        }
        if (DISTORTION_ENABLED && !Moci_GhostVisuals.USE_GHOST_WIND_EXIT) image.pulse = new Moci_GhostVisuals.Pulse();
        if (multi || image.pulse != null) afterimages.add(image);
        if (DISTORTION_ENABLED) {
            endBodyArrival();
            bodyPulse = new Moci_GhostVisuals.Pulse();
            bodyElapsed = 0f;
            if (multi) bodyCopy = renderer.addCopy();
        }
    }

    private void advanceVisuals(float amount) {
        if (bodyPulse != null) {
            bodyElapsed += amount;
            if (bodyElapsed >= BODY_RELAX_SECONDS) endBodyArrival();
            else {
                float p = bodyElapsed / BODY_RELAX_SECONDS;
                bodyPulse.show(ship.getLocation(), ship.getCollisionRadius(), bodyPulse.relax(bodyElapsed, BODY_RELAX_SECONDS), BODY_RELAX_SECONDS);
                if (bodyCopy != null && useMultiAfterimages()) {
                    bodyCopy.location.set(ship.getLocation());
                    bodyCopy.facing = ship.getFacing();
                    bodyCopy.alpha = BODY_ARRIVE_ALPHA + (1f - BODY_ARRIVE_ALPHA) * p;
                    ship.setExtraAlphaMult(0f);
                } else if (bodyCopy != null) endBodyArrival();
            }
        }
        for (int i = afterimages.size() - 1; i >= 0; i--) {
            Afterimage image = afterimages.get(i);
            image.elapsed += amount;
            if (image.elapsed >= AFTERIMAGE_SECONDS) {
                releaseImage(image);
                afterimages.remove(i);
                continue;
            }
            float p = image.elapsed / AFTERIMAGE_SECONDS;
            Vector2f position = image.origin;
            if (image.copy != null) {
                position = image.copy.location;
                if (Moci_GhostVisuals.USE_GHOST_WIND_EXIT) image.exit.sample(position, image.copy, p, ship.getCollisionRadius());
                else {
                    float drift = image.drift * p * (2f - p);
                    double back = Math.toRadians(image.facing + 180f);
                    position.set(image.origin.x + drift * (float) Math.cos(back), image.origin.y + drift * (float) Math.sin(back));
                }
                image.copy.alpha = AFTERIMAGE_START_ALPHA * (1f - p);
            }
            if (image.pulse != null) image.pulse.show(position, ship.getCollisionRadius(),
                    image.pulse.contract(p), AFTERIMAGE_SECONDS);
        }
    }

    private void endBodyArrival() {
        if (bodyPulse != null) bodyPulse.clear();
        bodyPulse = null;
        if (bodyCopy == null) return;
        if (renderer != null) renderer.removeCopy(bodyCopy);
        bodyCopy = null;
        if (ship != null) ship.setExtraAlphaMult(1f);
    }

    private void releaseImage(Afterimage image) {
        if (image.pulse != null) image.pulse.clear();
        if (renderer != null && image.copy != null) renderer.removeCopy(image.copy);
    }

    private void releaseVisuals() {
        endBodyArrival();
        for (Afterimage image : afterimages) releaseImage(image);
        afterimages.clear();
        if (renderer != null) renderer.expire();
        renderer = null;
    }


    // ==================== 两个版本共用 ====================

    // fireSoundTwo 是实际出膛声；不要把蓄力声 fireSoundOne 当作备用枪声。
    static String shotSound(WeaponSpecAPI spec) {
        return spec instanceof BaseWeaponSpec nativeSpec ? nativeSpec.getFireSoundTwo() : null;
    }

    static boolean playShotSound(WeaponAPI weapon, Vector2f muzzle, Vector2f velocity) {
        String sound = shotSound(weapon.getSpec());
        if (sound == null || sound.isBlank()) return false;
        Global.getSoundPlayer().playSound(sound, 1f, 0.8f, muzzle, velocity);
        return true;
    }

    /** 装在 FIRE_SLOT_ID 槽位上的武器 */
    public static List<WeaponAPI> findFireWeapons(ShipAPI ship) {
        List<WeaponAPI> result = new ArrayList<>();
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot() != null && FIRE_SLOT_ID.equals(weapon.getSlot().getId())) {
                result.add(weapon);
            }
        }
        return result;
    }

    /** 把所有使用弹药的武器装满 */
    public static void refillAmmo(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.usesAmmo() && weapon.getMaxAmmo() != Integer.MAX_VALUE) {
                weapon.setAmmo(weapon.getMaxAmmo());
            }
        }
    }

    /** 弹体实际速度，已含舰船的弹速加成，与引擎生成弹体时用的值一致 */
    public static float getProjectileSpeed(WeaponAPI weapon) {
        float speed = weapon.getProjectileSpeed();
        // 光束类武器会返回一个天文数字，这里只接受正常弹速
        if (speed > 0f && speed < 100000f) return speed;
        return 1000f;
    }

    /**
     * from 指向 to 的精确角度(0..360)。开火角不用 VectorUtils.getAngle：
     * 它走快速反正切，误差可达 0.3 度，1000 距离上会偏出约 5 个单位，平行弹道也会因此不平行。
     */
    public static float getExactAngle(Vector2f from, Vector2f to) {
        return Misc.normalizeAngle(Misc.getAngleInDegreesStrict(from, to));
    }

    /** 存活、在场的敌方舰船 */
    public static boolean isValidTarget(ShipAPI ship, ShipAPI target) {
        if (target == null || target == ship) return false;
        if (!target.isAlive() || target.isHulk()) return false;
        if (target.getOwner() == ship.getOwner()) return false;
        CombatEngineAPI engine = Global.getCombatEngine();
        return engine != null && engine.isEntityInPlay(target);
    }

    // ==================== 闪现 ====================

    private void startBlink(float effectLevel) {
        ShipAPI target = resolveTarget();
        if (target == null && lastKnownTargetLocation == null) return;

        // 备用观感用的前后时长：相邻两次闪现的实际间隔各占一半，上限为原版的 0.25 秒
        float period = BLINK_INTERVAL / Math.max(effectLevel, 0.05f);
        float inSeconds = Math.min(BLINK_IN_MAX, period * 0.5f);
        float outSeconds = Math.min(BLINK_OUT_MAX, period * 0.5f);
        // 从现在到落地的延迟：多残影可用时是立即瞬移
        float delay = useMultiAfterimages() ? 0f : inSeconds;

        // 落地时目标大致所在的位置
        Vector2f targetLoc;
        if (target != null) {
            targetLoc = new Vector2f(target.getLocation());
            targetLoc.x += target.getVelocity().x * delay;
            targetLoc.y += target.getVelocity().y * delay;
        } else {
            targetLoc = new Vector2f(lastKnownTargetLocation);
        }

        Vector2f destination = pickBlinkSpot(target, targetLoc);
        if (!beginBlink(destination, VectorUtils.getAngle(destination, targetLoc), inSeconds, outSeconds)) return;
        fireOnArrival = true;
        Global.getSoundPlayer().playSound("system_phase_skimmer", 1f, 1f, ship.getLocation(), ship.getVelocity());
    }

    /**
     * 在"目标朝向本舰一侧、距离为站位距离"的站位点附近随机取落点。
     * 落点需同时满足：不与其他舰船/小行星重叠、离目标不过近、主炮从这里开火打得到。
     */
    private Vector2f pickBlinkSpot(ShipAPI target, Vector2f targetLoc) {
        WeaponAPI weapon = fireWeapons.isEmpty() ? null : fireWeapons.get(0);
        float weaponRange = weapon != null ? weapon.getRange() : getMaxRange(ship);
        float projSpeed = weapon != null ? getProjectileSpeed(weapon) : 1000f;
        float targetRadius = target != null ? target.getCollisionRadius() : 0f;
        Vector2f targetVel = target != null ? target.getVelocity() : null;

        // 空间站一类的大目标，碰撞半径可能超过站位距离，站位点至少要在它的碰撞圈外
        float minGap = targetRadius + ship.getCollisionRadius() + MIN_GAP_TO_TARGET;
        float standoff = Math.max(getStandoffDistance(target, weaponRange, projSpeed), minGap);
        float side = VectorUtils.getAngle(targetLoc, ship.getLocation());
        Vector2f anchor = MathUtils.getPointOnCircumference(targetLoc, standoff, side);

        float maxFlight = weaponRange * MAX_FIRE_RANGE_FRACTION + targetRadius * 0.5f;
        Vector2f fallback = null;

        for (int i = 0; i < BLINK_SPOT_ATTEMPTS; i++) {
            Vector2f candidate = MathUtils.getRandomPointInCircle(anchor, SCATTER_RADIUS * Misc.random.nextFloat());
            if (MathUtils.getDistanceSquared(ship.getLocation(), candidate) < MIN_TELEPORT_DISTANCE * MIN_TELEPORT_DISTANCE) {
                continue;
            }
            if (!Moci_Util.isTeleportSpotFree(ship, candidate)) continue;
            if (fallback == null) fallback = candidate;
            if (MathUtils.getDistanceSquared(candidate, targetLoc) < minGap * minGap) continue;

            float time = Moci_Util.getInterceptTime(candidate, ship.getVelocity(), projSpeed, targetLoc, targetVel);
            if (time < 0f || time * projSpeed > maxFlight) continue;
            return candidate;
        }
        // 没有全部达标的落点：退而求其次用不重叠的落点，再不行就在站位点附近找空位
        return fallback != null ? fallback : Moci_Util.findFreeTeleportSpot(ship, anchor);
    }

    /**
     * 站位距离：目标越小、机动越灵活就站得越近，让弹体飞行时间内目标全力变向也大致偏不出自身的碰撞半径。
     */
    private float getStandoffDistance(ShipAPI target, float weaponRange, float projSpeed) {
        float max = weaponRange * STANDOFF_MAX_FRACTION;
        float min = weaponRange * STANDOFF_MIN_FRACTION;
        if (target == null) return max;
        float acceleration = Math.max(target.getAcceleration(), 1f);
        float flightTime = (float) Math.sqrt(2f * target.getCollisionRadius() / acceleration);
        return Math.max(min, Math.min(max, projSpeed * flightTime));
    }

    // ==================== 开火 ====================

    private void fireAfterTeleport() {
        CombatEngineAPI engine = Global.getCombatEngine();
        ShipAPI target = resolveTarget();

        for (WeaponAPI weapon : fireWeapons) {
            float projSpeed = getProjectileSpeed(weapon);
            // 完全没有目标信息时沿武器当前朝向开火
            float fireAngle = weapon.getCurrAngle();

            Vector2f targetLoc = target != null ? target.getLocation() : lastKnownTargetLocation;
            Vector2f targetVel = target != null ? target.getVelocity() : null;
            if (targetLoc != null) {
                // 炮口不在舰船中心，位置随舰船朝向变化：先转向、再取炮口、再算提前量，迭代到收敛
                fireAngle = getExactAngle(ship.getLocation(), targetLoc);
                for (int i = 0; i < AIM_ITERATIONS; i++) {
                    ship.setFacing(fireAngle);
                    weapon.setCurrAngle(fireAngle);
                    Vector2f muzzle = weapon.getFirePoint(0);
                    Vector2f aimPoint = Moci_Util.getInterceptPoint(muzzle, ship.getVelocity(), projSpeed,
                            targetLoc, targetVel);
                    // 目标快到追不上时退化为直瞄
                    if (aimPoint == null) aimPoint = targetLoc;
                    fireAngle = getExactAngle(muzzle, aimPoint);
                }
            }

            // 舰船、武器和弹体统一朝向实际开火方向
            ship.setFacing(fireAngle);
            weapon.setCurrAngle(fireAngle);
            Vector2f firePoint = weapon.getFirePoint(0);

            playShotSound(weapon, firePoint, ship.getVelocity());
            engine.spawnProjectile(ship, weapon, weapon.getId(), firePoint, fireAngle, ship.getVelocity());
            engine.spawnMuzzleFlashOrSmoke(ship, weapon.getSlot(), weapon.getSpec(), 0, fireAngle);
        }
    }

    // ==================== 目标 ====================

    /**
     * 本次激活期间闪现和开火共用的目标。优先用 findTarget() 的结果(玩家可中途换目标)，
     * 找不到时沿用上一个仍然存活的目标；都没有时返回 null，调用方改用最后已知位置。
     */
    private ShipAPI resolveTarget() {
        ShipAPI found = findTarget(ship);
        if (isValidTarget(ship, found)) {
            lockedTarget = found;
        } else if (!isValidTarget(ship, lockedTarget)) {
            lockedTarget = null;
        }
        if (lockedTarget != null) {
            lastKnownTargetLocation = new Vector2f(lockedTarget.getLocation());
        }
        return lockedTarget;
    }

    private static boolean isInRange(ShipAPI ship, ShipAPI target, float range) {
        float allowedRange = range + ship.getCollisionRadius() + target.getCollisionRadius();
        return Misc.getDistanceSq(ship.getLocation(), target.getLocation()) <= allowedRange * allowedRange;
    }

    public static float getMaxRange(ShipAPI ship) {
        // 最终范围 = 基础范围 * (1 + 能量武器射程加成)
        float energyRangeBonus = ship.getMutableStats().getEnergyWeaponRangeBonus().getPercentMod();
        return BASE_RANGE * (1f + energyRangeBonus * 0.01f);
    }

    protected ShipAPI findTarget(ShipAPI ship) {
        float range = getMaxRange(ship);
        boolean player = ship == Global.getCombatEngine().getPlayerShip();

        ShipAPI target = ship.getShipTarget();
        if (ship.getShipAI() != null && ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            Object flagged = ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM);
            if (flagged instanceof ShipAPI) target = (ShipAPI) flagged;
        }

        // 明确选中了一个敌舰：只认它，超出射程就算没有目标
        if (isValidTarget(ship, target)) {
            return isInRange(ship, target, range) ? target : null;
        }

        // 没选目标，或选中的是友军/残骸：自动找
        target = null;
        if (player) {
            target = Misc.findClosestShipEnemyOf(ship, ship.getMouseTarget(), ShipAPI.HullSize.FIGHTER, range, true);
        } else {
            Object test = ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
            if (test instanceof ShipAPI && isValidTarget(ship, (ShipAPI) test)
                    && isInRange(ship, (ShipAPI) test, range)) {
                target = (ShipAPI) test;
            }
        }
        if (target == null) {
            target = Misc.findClosestShipEnemyOf(ship, ship.getLocation(), ShipAPI.HullSize.FIGHTER, range, true);
        }
        return target;
    }

    @Override
    public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
        if (system.isOutOfAmmo())
            return null;
        if (system.getState() != ShipSystemAPI.SystemState.IDLE)
            return null;

        ShipAPI target = findTarget(ship);
        if (target != null && target != ship) {
            return Moci_TextLoader.getText("code_Moci_RampageGhostStats", "t001", "就绪");
        }
        if (isValidTarget(ship, ship.getShipTarget())) {
            return Moci_TextLoader.getText("code_Moci_RampageGhostStats", "t002", "射程外");
        }
        return Moci_TextLoader.getText("code_Moci_RampageGhostStats", "t003", "没有目标");
    }

    @Override
    public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
        ShipAPI target = findTarget(ship);
        return target != null && target != ship;
    }
}
