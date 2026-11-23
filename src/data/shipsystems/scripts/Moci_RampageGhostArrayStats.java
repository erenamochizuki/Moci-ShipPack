package data.shipsystems.scripts;

import data.scripts.util.Moci_TextLoader;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;

import data.scripts.util.Moci_Util;

/**
 * 暴走亡灵·列阵(Moci_RampageGhostArray)：暴走亡灵的第二版本。
 * 舰船本身不闪现，而是原地隐匿(不可见、无碰撞、不受伤害、减速)，
 * 残影在以舰船为中心的矩形区域内接连出现(多个并存)，每个残影从自己的炮口位置用 ARM_R_MAIN 主炮开一炮。
 *
 * 平时所有残影都沿舰船朝向开火，弹道互相平行；区域随舰船移动和转向而移动。
 * 多个残影并存靠 Moci_Util.ShipCopyRenderer；它不可用时退回引擎自带的单个传送副本。
 * 锁定的目标进入矩形区域时，矩形弯成以目标为圆心、挖掉中心的圆弧，瞄准区域收拢成目标身上的一个圆。
 *
 * 禁止开火、禁止排幅和每秒幅能由 ship_systems.csv 配置。
 * Moci_RampageGhostArray.system 没有配 runScriptWhileIdle，系统结束时引擎会调用 unapply() 做清理。
 */
public class Moci_RampageGhostArrayStats extends BaseShipSystemScript {
    // ==================== 可调参数 ====================
    // 以下几项不在这里，在 data/shipsystems/ship_systems.csv 的 Moci_RampageGhostArray 一行：
    //   charge up = 充能秒数(期间残影从稀到密)   active = 满功率秒数
    //   down      = 收尾秒数(本体重新显现)       cooldown = 冷却秒数
    //   flux/second = 持续期间每秒产生的幅能      f/u (base cap) = 启动时消耗的幅能比例
    //   noFiring = 禁止开火                       noVent = 禁止排幅

    // ---------- 残影区域 ----------
    /** 残影区域的半宽(单位)：舰船左右各这么远。调大 = 列阵更宽、弹幕更稀 */
    public static final float HALF_WIDTH = 1000f;
    /** 残影区域的半深(单位)：舰船前后各这么远 */
    public static final float HALF_DEPTH = 250f;
    /** 矩形状态下瞄准点在炮口正前方的距离(单位)。纯矩形时弹道恒与舰船朝向平行，这个值只影响矩形向圆弧过渡时弹道偏转的快慢 */
    static final float AIM_DISTANCE = 1000f;

    // ---------- 残影节奏与伤害 ----------
    /** 满功率时每秒生成的残影数，也是每秒开火数。充能阶段从 0 逐渐加到该值。调大 = 弹幕更密 */
    private static final float PHANTOMS_PER_SECOND = 15f;
    private static final float PHANTOM_INTERVAL = 1f / PHANTOMS_PER_SECOND;
    /** 单个残影的淡入秒数，淡入结束的那一帧开火。调大 = 残影现身后等更久才开火 */
    private static final float PHANTOM_IN_SECONDS = 0.25f;
    /** 单个残影开火后的淡出秒数。同屏残影数约等于 每秒生成数 x (淡入 + 淡出)，现在约 7 个 */
    private static final float PHANTOM_OUT_SECONDS = 0.25f;
    /** 残影开火后淡出期间向自己身后退开的总距离(单位)，每个残影在下限和上限之间随机取一个值。两个都设为 0 = 原地渐隐 */
    private static final float PHANTOM_FADE_DRIFT_MIN = 75f;
    private static final float PHANTOM_FADE_DRIFT_MAX = 150f;
    /** 残影的 jitter(船体抖散成多重叠影)强度。0 = 关闭；1 = 原版闪现的强度 */
    private static final float PHANTOM_JITTER_INTENSITY = 0f;

    // 现身时由内收恢复；关闭风痕后，退场末端也叠加同方向的径向内收。
    private static final boolean DISTORTION_ENABLED = true;
    private static final float DISTORTION_RELAX_SECONDS = 0.15f;
    /** 系统期间全舰能量武器的伤害倍率。0.5 = 伤害减半，1 = 不减伤 */
    private static final float DAMAGE_MULT = 0.5f;
    /** 一帧内最多补生成的残影数，防止卡顿后瞬间刷出一大批。一般不用改 */
    private static final int MAX_SPAWNS_PER_FRAME = 3;
    /** 相邻两个残影在区域内的最小间距(单位)，避免连续两个出现在几乎同一位置 */
    private static final float MIN_PHANTOM_SPACING = 150f;
    /** 为满足最小间距，每个残影最多重新随机几次位置 */
    private static final int PHANTOM_SPOT_ATTEMPTS = 6;
    /** 残影现身音效的最小间隔(秒)。调小 = 音效更密集 */
    private static final float APPEAR_SOUND_GAP = 0.2f;
    /** 开火音效的最小间隔(秒)。每秒十几炮全部播放会糊成一片，所以限流 */
    private static final float FIRE_SOUND_GAP = 0.1f;

    // ---------- 舰船本体 ----------
    /** 隐匿期间的最大航速倍率。0.5 = 航速 -50% */
    private static final float SPEED_MULT = 0.5f;
    /** 隐匿期间加速、减速、转向加速度、最大转速的倍率。0.7 = 机动性 -30% */
    private static final float MANEUVER_MULT = 0.7f;
    /** 启动时本体隐去、收尾时本体重新显现各需要的秒数 */
    private static final float HIDE_SECONDS = 0.3f;
    /** 隐匿后本体的透明度。0 = 完全不可见；调到 0.2 左右可以留一个淡影方便玩家操控 */
    private static final float HIDDEN_SHIP_ALPHA = 0f;

    // ---------- 锁定目标进入区域时弯成圆弧 ----------
    /** 矩形与圆弧互相过渡需要的秒数。调小 = 变形更快 */
    private static final float BEND_SECONDS = 0.5f;
    /** 目标要离开矩形这么远(单位)才开始变回矩形，避免目标在边缘时来回切换 */
    private static final float BEND_EXIT_MARGIN = 150f;
    /** 圆弧内缘与目标之间，除双方碰撞半径外再留出的距离(单位)。调大 = 中间挖掉的空洞更大 */
    static final float ARC_CLEARANCE = 100f;
    /** 圆弧相对中线向两侧各展开的最大角度(弧度)。150 度 = 最多绕目标 300 度，留一个缺口 */
    static final float ARC_MAX_HALF_SPAN = (float) Math.toRadians(150f);
    /** 圆弧状态下瞄准圆的半径，按目标碰撞半径的比例。调小 = 火力更集中在目标中心 */
    static final float AIM_CIRCLE_RADIUS_FRACTION = 0.5f;
    /** 瞄准角迭代次数。炮口不在残影中心，朝向变了炮口位置也变，需要反复修正；3 次已足够，一般不用改 */
    private static final int AIM_ITERATIONS = 3;

    /** 一个残影：在区域内的归一化位置固定，世界坐标每帧随舰船和目标重新计算 */
    private static final class Phantom {
        /** 横向位置，-1(最左)..1(最右) */
        float u;
        /** 纵向位置，-1(最后)..1(最前) */
        float v;
        /** 圆弧上横向展开的方向，生成时确定，避免目标越过舰船正侧方时残影左右翻转 */
        float arcSign;
        float elapsed;
        boolean fired;
        /** 开火那一刻的朝向；开火后朝向不再变化，沿它的反方向后退 */
        float firedAngle;
        /** 这个残影淡出时后退的总距离，生成时在上下限之间随机确定 */
        float fadeDrift;
        final Moci_GhostVisuals.Exit exit = new Moci_GhostVisuals.Exit();
        final Moci_GhostVisuals.Pulse pulse = new Moci_GhostVisuals.Pulse();
        /** 渲染器里对应的副本；多副本渲染不可用时为 null */
        Moci_Util.ShipCopyRenderer.ShipCopy copy;
    }

    private ShipAPI ship = null;
    private final List<WeaponAPI> fireWeapons = new ArrayList<>();
    private final Object jitterKey = new Object();
    private final Vector2f copyLocation = new Vector2f();

    private boolean active = false;
    private CollisionClass originalCollisionClass = null;
    private float hideLevel = 0f;
    private float bend = 0f;
    private boolean bending = false;
    private float phantomTimer = 0f;
    private final List<Phantom> phantoms = new ArrayList<>();
    private Moci_Util.ShipCopyRenderer renderer = null;
    private float lastU = 0f;
    private float lastV = 0f;
    private float sinceAppearSound = 0f;
    private float sinceFireSound = 0f;

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        if (!(stats.getEntity() instanceof ShipAPI)) return;
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        if (state != State.IN && state != State.ACTIVE && state != State.OUT) return;
        init((ShipAPI) stats.getEntity());
        if (!active) onActivate();

        // 舰船：无碰撞、不受伤害、减速
        ship.setCollisionClass(CollisionClass.NONE);
        stats.getShieldDamageTakenMult().modifyMult(id, 0f);
        stats.getArmorDamageTakenMult().modifyMult(id, 0f);
        stats.getHullDamageTakenMult().modifyMult(id, 0f);
        stats.getMaxSpeed().modifyMult(id, SPEED_MULT);
        stats.getAcceleration().modifyMult(id, MANEUVER_MULT);
        stats.getDeceleration().modifyMult(id, MANEUVER_MULT);
        stats.getTurnAcceleration().modifyMult(id, MANEUVER_MULT);
        stats.getMaxTurnRate().modifyMult(id, MANEUVER_MULT);
        stats.getEnergyWeaponDamageMult().modifyMult(id + "_damage_debuff", DAMAGE_MULT);

        float amount = engine.getElapsedInLastFrame();

        // 舰船本体：启动后迅速隐去，收尾阶段重新显现
        float hideGoal = state == State.OUT ? 0f : 1f;
        hideLevel = approach(hideLevel, hideGoal, amount / HIDE_SECONDS);
        ship.setExtraAlphaMult(1f - hideLevel * (1f - HIDDEN_SHIP_ALPHA));
        // 主炮相对舰体摆正，残影上的炮管才会指向残影的朝向
        for (WeaponAPI weapon : fireWeapons) {
            weapon.setCurrAngle(ship.getFacing());
        }

        // 区域形状：锁定目标在矩形内时弯成圆弧
        ShipAPI target = getLockedTarget();
        bending = target != null && isInsideRectangle(ship, target, bending ? BEND_EXIT_MARGIN : 0f);
        bend = approach(bend, bending ? 1f : 0f, amount / BEND_SECONDS);

        // 残影节奏：随着系统的chargeup积累越来越快；收尾阶段不再生成新的
        sinceAppearSound += amount;
        sinceFireSound += amount;
        if (state != State.OUT) {
            phantomTimer += amount * effectLevel;
            for (int i = 0; i < MAX_SPAWNS_PER_FRAME && phantomTimer >= PHANTOM_INTERVAL; i++) {
                phantomTimer -= PHANTOM_INTERVAL;
                spawnPhantom(target);
            }
            phantomTimer = Math.min(phantomTimer, PHANTOM_INTERVAL);
        }
        advancePhantoms(amount, target);
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        stats.getShieldDamageTakenMult().unmodify(id);
        stats.getArmorDamageTakenMult().unmodify(id);
        stats.getHullDamageTakenMult().unmodify(id);
        stats.getMaxSpeed().unmodify(id);
        stats.getAcceleration().unmodify(id);
        stats.getDeceleration().unmodify(id);
        stats.getTurnAcceleration().unmodify(id);
        stats.getMaxTurnRate().unmodify(id);
        stats.getEnergyWeaponDamageMult().unmodify(id + "_damage_debuff");

        // 引擎在系统从未启动过时也会调用一次 unapply()
        if (!active || ship == null) return;
        active = false;
        clearPhantoms();
        if (renderer != null) renderer.expire();
        renderer = null;
        if (originalCollisionClass != null) ship.setCollisionClass(originalCollisionClass);
        originalCollisionClass = null;
        ship.setExtraAlphaMult(1f);
        ship.setCopyLocation(null, 1f, ship.getFacing());
        Moci_RampageGhostStats.refillAmmo(ship);
    }

    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (index == 0) return new StatusData(Moci_TextLoader.getText("code_Moci_RampageGhostArrayStats", "t001", "机体隐匿，不受伤害"), false);
        if (index == 1) return new StatusData(Moci_TextLoader.getText("code_Moci_RampageGhostArrayStats", "t002", "航速与机动性下降"), true);
        return null;
    }

    private void init(ShipAPI owner) {
        if (ship == owner) return;
        ship = owner;
        fireWeapons.clear();
        fireWeapons.addAll(Moci_RampageGhostStats.findFireWeapons(owner));
    }

    private void onActivate() {
        active = true;
        originalCollisionClass = ship.getCollisionClass();
        hideLevel = 0f;
        bend = 0f;
        bending = false;
        phantomTimer = 0f;
        clearPhantoms();
        sinceAppearSound = APPEAR_SOUND_GAP;
        sinceFireSound = FIRE_SOUND_GAP;
        if (renderer != null) renderer.expire();
        renderer = Moci_Util.createShipCopyRenderer(ship);
    }

    // ==================== 残影 ====================

    private void spawnPhantom(ShipAPI target) {
        Phantom next = new Phantom();
        for (int i = 0; i < PHANTOM_SPOT_ATTEMPTS; i++) {
            next.u = Misc.random.nextFloat() * 2f - 1f;
            next.v = Misc.random.nextFloat() * 2f - 1f;
            float du = (next.u - lastU) * HALF_WIDTH;
            float dv = (next.v - lastV) * HALF_DEPTH;
            if (du * du + dv * dv >= MIN_PHANTOM_SPACING * MIN_PHANTOM_SPACING) break;
        }
        lastU = next.u;
        lastV = next.v;
        next.arcSign = getArcSign(ship, target);
        next.fadeDrift = PHANTOM_FADE_DRIFT_MIN
                + (PHANTOM_FADE_DRIFT_MAX - PHANTOM_FADE_DRIFT_MIN) * Misc.random.nextFloat();
        if (renderer != null && renderer.isWorking()) {
            next.copy = renderer.addCopy();
            next.copy.facing = ship.getFacing();
            next.copy.alpha = 0f;
        }

        Vector2f position = computePosition(ship.getLocation(), ship.getFacing(), ship.getCollisionRadius(),
                next.u, next.v, next.arcSign, target != null ? target.getLocation() : null,
                target != null ? target.getCollisionRadius() : 0f, bend);
        if (Moci_GhostVisuals.USE_GHOST_WIND_EXIT && !phantoms.isEmpty()) {
            phantoms.get(phantoms.size() - 1).exit.link(position);
        }
        phantoms.add(next);
        if (sinceAppearSound >= APPEAR_SOUND_GAP) {
            sinceAppearSound = 0f;
            Global.getSoundPlayer().playSound("system_phase_skimmer", 1f, 1f, position, ship.getVelocity());
        }
    }

    private void updateDistortion(Phantom phantom, Vector2f position) {
        if (!DISTORTION_ENABLED) return;
        float life = PHANTOM_IN_SECONDS + PHANTOM_OUT_SECONDS;
        float level = phantom.pulse.relax(phantom.elapsed, DISTORTION_RELAX_SECONDS);
        if (!Moci_GhostVisuals.USE_GHOST_WIND_EXIT && phantom.fired) {
            level = phantom.pulse.contract((phantom.elapsed - PHANTOM_IN_SECONDS) / PHANTOM_OUT_SECONDS);
        }
        phantom.pulse.show(position, ship.getCollisionRadius(), level, life);
    }

    /** 移除一个残影占用的副本和扭曲(不从列表里删) */
    private void releasePhantom(Phantom phantom) {
        if (phantom.copy != null && renderer != null) renderer.removeCopy(phantom.copy);
        phantom.copy = null;
        phantom.pulse.clear();
    }

    private void clearPhantoms() {
        for (Phantom phantom : phantoms) {
            releasePhantom(phantom);
        }
        phantoms.clear();
    }

    private void advancePhantoms(float amount, ShipAPI target) {
        Vector2f targetLoc = target != null ? target.getLocation() : null;
        float targetRadius = target != null ? target.getCollisionRadius() : 0f;
        WeaponAPI weapon = fireWeapons.isEmpty() ? null : fireWeapons.get(0);
        float projSpeed = weapon != null ? Moci_RampageGhostStats.getProjectileSpeed(weapon) : 1000f;
        boolean multiCopy = renderer != null && renderer.isWorking();

        // 多副本渲染不可用时，退回引擎自带的单个副本，只显示最新的那个残影
        Phantom newest = phantoms.isEmpty() ? null : phantoms.get(phantoms.size() - 1);
        boolean showedFallbackCopy = false;

        for (int index = phantoms.size() - 1; index >= 0; index--) {
            Phantom phantom = phantoms.get(index);
            phantom.elapsed += amount;
            if (phantom.fired && phantom.elapsed >= PHANTOM_IN_SECONDS + PHANTOM_OUT_SECONDS) {
                releasePhantom(phantom);
                phantoms.remove(index);
                continue;
            }

            Vector2f position = computePosition(ship.getLocation(), ship.getFacing(), ship.getCollisionRadius(),
                    phantom.u, phantom.v, phantom.arcSign, targetLoc, targetRadius, bend);

            float fireAngle;
            if (phantom.fired) {
                // 已经开过火：朝向定格，不再跟踪目标
                fireAngle = phantom.firedAngle;
            } else {
                // 残影朝向 = 开火方向。炮口位置随朝向变化，迭代到收敛；纯矩形状态下恒等于舰船朝向
                fireAngle = ship.getFacing();
                Vector2f muzzle = new Vector2f(position);
                for (int i = 0; i < AIM_ITERATIONS; i++) {
                    muzzle = weapon != null ? computeMuzzle(weapon, position, fireAngle) : new Vector2f(position);
                    Vector2f aimCenter = null;
                    if (target != null) {
                        aimCenter = Moci_Util.getInterceptPoint(muzzle, ship.getVelocity(), projSpeed,
                                targetLoc, target.getVelocity());
                        // 目标快到追不上时退化为直瞄
                        if (aimCenter == null) aimCenter = targetLoc;
                    }
                    Vector2f aim = computeAim(muzzle, ship.getFacing(), phantom.u, phantom.v, aimCenter,
                            targetRadius, bend);
                    fireAngle = Moci_RampageGhostStats.getExactAngle(muzzle, aim);
                }
                if (weapon != null) muzzle = computeMuzzle(weapon, position, fireAngle);

                // 淡入结束的那一帧开火
                if (phantom.elapsed >= PHANTOM_IN_SECONDS) {
                    phantom.fired = true;
                    phantom.firedAngle = fireAngle;
                    fire(muzzle, fireAngle);
                    phantom.exit.begin(position); // 只移动开火后的画面，固定世界坐标起点。
                }
            }

            float level;
            if (!phantom.fired) {
                level = phantom.elapsed / PHANTOM_IN_SECONDS;
            } else {
                // 淡出进度独立于开火、生成节奏。
                float fade = (phantom.elapsed - PHANTOM_IN_SECONDS) / PHANTOM_OUT_SECONDS;
                fade = Math.max(0f, Math.min(1f, fade));
                level = 1f - fade;
                if (Moci_GhostVisuals.USE_GHOST_WIND_EXIT) phantom.exit.sample(position, phantom.copy, fade, ship.getCollisionRadius());
                else {
                    float drift = phantom.fadeDrift * fade * (2f - fade);
                    double back = Math.toRadians(fireAngle + 180f);
                    position.x += drift * (float) Math.cos(back);
                    position.y += drift * (float) Math.sin(back);
                }
            }
            level = Math.max(0f, Math.min(1f, level));

            // 扭曲跟着残影实际显示的位置(含淡出时的后退)
            updateDistortion(phantom, position);

            if (multiCopy && phantom.copy != null) {
                phantom.copy.location.set(position);
                phantom.copy.facing = fireAngle;
                phantom.copy.alpha = level;
            } else if (!multiCopy && phantom == newest) {
                copyLocation.set(position);
                ship.setCopyLocation(copyLocation, level, fireAngle);
                showedFallbackCopy = true;
            }
        }

        if (!showedFallbackCopy) ship.setCopyLocation(null, 1f, ship.getFacing());
        // jitter 是整船的设置，本体不可见时只会显示在残影上，强度随各残影自己的透明度变化
        if (PHANTOM_JITTER_INTENSITY > 0f && !phantoms.isEmpty()) {
            ship.setJitter(jitterKey, Moci_Util.SKIMMER_JITTER_COLOR, hideLevel * PHANTOM_JITTER_INTENSITY, 10, 0f,
                    ship.getCollisionRadius() * 0.5f);
        }
    }

    private void fire(Vector2f muzzle, float fireAngle) {
        CombatEngineAPI engine = Global.getCombatEngine();
        for (WeaponAPI weapon : fireWeapons) {
            if (sinceFireSound >= FIRE_SOUND_GAP && Moci_RampageGhostStats.playShotSound(weapon, muzzle, ship.getVelocity())) {
                sinceFireSound = 0f;
            }
            engine.spawnProjectile(ship, weapon, weapon.getId(), muzzle, fireAngle, ship.getVelocity());
            engine.spawnMuzzleFlashOrSmoke(ship, muzzle, weapon.getSpec(), fireAngle);
        }
    }

    /** 残影位于 position、朝向和炮管都指向 facing 时，该武器 0 号炮口的世界坐标 */
    private static Vector2f computeMuzzle(WeaponAPI weapon, Vector2f position, float facing) {
        WeaponSlotAPI slot = weapon.getSlot();
        float x = slot.getLocation().x;
        float y = slot.getLocation().y;
        List<Vector2f> offsets = slot.isHardpoint() ? weapon.getSpec().getHardpointFireOffsets()
                : weapon.getSpec().getTurretFireOffsets();
        if (offsets != null && !offsets.isEmpty()) {
            x += offsets.get(0).x;
            y += offsets.get(0).y;
        }
        float cos = (float) Math.cos(Math.toRadians(facing));
        float sin = (float) Math.sin(Math.toRadians(facing));
        return new Vector2f(position.x + x * cos - y * sin, position.y + x * sin + y * cos);
    }

    // ==================== 目标 ====================

    /** 玩家或 AI 当前锁定的敌舰；没有锁定时返回 null，区域保持矩形 */
    private ShipAPI getLockedTarget() {
        ShipAPI target = ship.getShipTarget();
        if (Moci_RampageGhostStats.isValidTarget(ship, target)) return target;
        if (ship.getShipAI() != null) {
            Object test = ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.MANEUVER_TARGET);
            if (test instanceof ShipAPI && Moci_RampageGhostStats.isValidTarget(ship, (ShipAPI) test)) {
                return (ShipAPI) test;
            }
        }
        return null;
    }

    /** 目标(按碰撞半径外扩)是否与舰船当前的残影矩形相交；margin 为额外放宽的距离 */
    public static boolean isInsideRectangle(ShipAPI ship, ShipAPI target, float margin) {
        double facing = Math.toRadians(ship.getFacing());
        float fx = (float) Math.cos(facing);
        float fy = (float) Math.sin(facing);
        float dx = target.getLocation().x - ship.getLocation().x;
        float dy = target.getLocation().y - ship.getLocation().y;
        float lateral = dx * fy - dy * fx;
        float forward = dx * fx + dy * fy;
        float extra = target.getCollisionRadius() + margin;
        return Math.abs(lateral) <= HALF_WIDTH + extra && Math.abs(forward) <= HALF_DEPTH + extra;
    }

    /** 目标在舰船前方时圆弧向右展开对应矩形的右侧，在后方时相反，保证弯曲过程连续 */
    private static float getArcSign(ShipAPI ship, ShipAPI target) {
        if (target == null) return 1f;
        double facing = Math.toRadians(ship.getFacing());
        float forward = (target.getLocation().x - ship.getLocation().x) * (float) Math.cos(facing)
                + (target.getLocation().y - ship.getLocation().y) * (float) Math.sin(facing);
        return forward >= 0f ? 1f : -1f;
    }

    // ==================== 区域几何(纯计算) ====================

    /**
     * 残影的世界坐标。
     * 矩形状态：舰船位置 + 横向 u、纵向 v。
     * 圆弧状态：以目标为圆心、过舰船的圆弧，u 沿弧长展开，v 朝圆心方向收放；
     * 弧的内缘始终在目标碰撞圈之外，所以中心是空的。
     * bend 在 0(矩形)与 1(圆弧)之间时取两者的平滑插值。
     *
     * @param u       横向归一化位置 -1..1，正值为舰船右侧
     * @param v       纵向归一化位置 -1..1，正值为舰船前方
     * @param arcSign 圆弧上 u 的展开方向，1 或 -1
     */
    static Vector2f computePosition(Vector2f shipLoc, float shipFacing, float shipRadius, float u, float v,
            float arcSign, Vector2f targetLoc, float targetRadius, float bend) {
        double facing = Math.toRadians(shipFacing);
        float fx = (float) Math.cos(facing);
        float fy = (float) Math.sin(facing);
        // 舰船右侧 = 朝向顺时针转 90 度
        float rx = fy;
        float ry = -fx;
        float lateral = u * HALF_WIDTH;
        float forward = v * HALF_DEPTH;
        float rectX = shipLoc.x + lateral * rx + forward * fx;
        float rectY = shipLoc.y + lateral * ry + forward * fy;

        float k = smooth(bend);
        if (k <= 0f || targetLoc == null) return new Vector2f(rectX, rectY);

        float dx = shipLoc.x - targetLoc.x;
        float dy = shipLoc.y - targetLoc.y;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        // 弧的中线方向：从目标指向舰船；两者重合时取舰船正后方
        double midAngle = distance > 1f ? Math.atan2(dy, dx) : facing + Math.PI;
        float midRadius = Math.max(distance, targetRadius + shipRadius + HALF_DEPTH + ARC_CLEARANCE);
        float halfSpan = Math.min(HALF_WIDTH / midRadius, ARC_MAX_HALF_SPAN);
        double angle = midAngle + arcSign * u * halfSpan;
        float radius = midRadius - forward;
        float arcX = targetLoc.x + radius * (float) Math.cos(angle);
        float arcY = targetLoc.y + radius * (float) Math.sin(angle);

        return new Vector2f(rectX + (arcX - rectX) * k, rectY + (arcY - rectY) * k);
    }

    /**
     * 炮口应当瞄准的世界坐标。
     * 矩形状态：炮口正前方 AIM_DISTANCE 处，即前方一个相同矩形里的对应点，所有弹道平行。
     * 圆弧状态：以 aimCenter 为圆心的圆内与 (u, v) 对应的点。
     *
     * @param aimCenter 瞄准圆的圆心(已含提前量)；为 null 时始终按矩形状态处理
     */
    static Vector2f computeAim(Vector2f muzzle, float shipFacing, float u, float v, Vector2f aimCenter,
            float targetRadius, float bend) {
        double facing = Math.toRadians(shipFacing);
        float fx = (float) Math.cos(facing);
        float fy = (float) Math.sin(facing);
        float rectX = muzzle.x + AIM_DISTANCE * fx;
        float rectY = muzzle.y + AIM_DISTANCE * fy;

        float k = smooth(bend);
        if (k <= 0f || aimCenter == null) return new Vector2f(rectX, rectY);

        // 把正方形 [-1,1]^2 压成单位圆，保持 (u, v) 与落点一一对应
        float discX = u * (float) Math.sqrt(1f - v * v * 0.5f);
        float discY = v * (float) Math.sqrt(1f - u * u * 0.5f);
        float aimRadius = targetRadius * AIM_CIRCLE_RADIUS_FRACTION;
        float rx = fy;
        float ry = -fx;
        float arcX = aimCenter.x + aimRadius * (discX * rx + discY * fx);
        float arcY = aimCenter.y + aimRadius * (discX * ry + discY * fy);

        return new Vector2f(rectX + (arcX - rectX) * k, rectY + (arcY - rectY) * k);
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static float approach(float value, float goal, float step) {
        if (value < goal) return Math.min(goal, value + step);
        return Math.max(goal, value - step);
    }
}
