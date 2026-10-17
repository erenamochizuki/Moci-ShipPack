package data.scripts.weapons;

import java.awt.Color;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.boxutil.base.api.RenderDataAPI;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.DistortionEntity;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

import data.scripts.util.Moci_BoxBasedUtil;
import data.scripts.util.Moci_Util;
import data.shipsystems.scripts.Moci_DeepDive;

/**
 * 丹努之子内置反舰导弹的动画与“相位潜射”控制脚本。
 *
 * 当前功能分成两部分：
 * 1. 控制发射器本身的 0-12 帧开盖/闭合动画。
 * 2. 在舰船真正处于相位态时，让新发射的鱼雷进入“相位潜航”状态：
 *    - 导弹在接近敌舰前保持低透明、无碰撞、高 EMP 抗性。
 *    - 导弹尾焰、尾迹和周身辉光会切换成紫色相位风格。
 *    - 当敌舰进入足够近的范围后，导弹开始退相位并恢复正常碰撞。
 *
 * 注意：
 * - 这里的“相位”是导弹级别的自定义视觉/碰撞状态，不是舰船 phase cloak 的原生系统对象。
 * - 现在的 glow 由两部分组成：
 *   1) .proj 自带的基础 glowSprite
 *   2) 相位期间额外挂接到导弹上的 BoxUtil glowSprite 实体
 */
public class Moci_TDD_1_missileAnimEffect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    /** 挂在 TDD 发射器 WeaponAPI.custom 上的攻击窗口标记。 */
    private static final String TDD_ATTACK_WINDOW_MARKER = "moci_tdd_attack_window";
    /** 测试开关：是否启用 BoxUtil 手动 glowSprite 实体。 */
    private static final boolean ENABLE_MANUAL_GLOW_SPRITE = true;
    /** 抖动 key，便于和其他视觉抖动效果隔离。 */
    private static final String PHASE_STATE_ID = "moci_tdd1_phase_sublaunch";
    /** 当敌舰外缘进入该距离时，导弹开始退相位。 */
    private static final float PHASE_BREAK_RANGE = 600f;
    /** 检索敌舰时给一个更大的搜索外框，减少“刚进范围一帧内没查到”的边缘问题。 */
    private static final float PHASE_BREAK_SEARCH_PADDING = 1000f;
    /** 从相位态恢复到正常飞行状态所需的退相位时间。 */
    private static final float PHASE_TRANSIT_TIME = 0.2f;
    /** 相位潜航期间的基础透明度。 */
    private static final float PHASE_ALPHA = 0.15f;
    /** 相位期间把导弹生命值锁到一个极高值，用来近似实现“普通伤害几乎无效”。 */
    private static final float PHASE_HP_MULT = 100f;
    /** 相位期间的 EMP 抗性。10000 基本可以视作完全免疫。 */
    private static final int PHASE_EMP_RESISTANCE = 10000;
    /** 相位切入/切出时的闪光颜色。 */
    private static final Color PHASE_FLASH_COLOR = new Color(210, 245, 255, 190);
    /** 相位期间推进器染色为紫色。 */
    private static final Color PHASE_ENGINE_COLOR = new Color(176, 96, 255, 220);
    /** 相位期间引擎尾迹同步切换为更亮的紫色。 */
    private static final Color PHASE_ENGINE_CONTRAIL_COLOR = new Color(170, 115, 255, 185);
    /** 是否启用“脱离相位”瞬间的扭曲波。 */
    private static final boolean ENABLE_PHASE_EXIT_DISTORTION = true;
    /** 脱离相位扭曲的淡入时间。0 表示生成后立刻到达目标状态。 */
    private static final float PHASE_EXIT_DISTORTION_FADE_IN = 0f;
    /** 脱离相位扭曲的满强度持续时间。 */
    private static final float PHASE_EXIT_DISTORTION_DURATION = 0f;
    /** 脱离相位扭曲的淡出时间。 */
    private static final float PHASE_EXIT_DISTORTION_FADE_OUT = 0.25f;
    /** 脱离相位扭曲在“满状态”时的尺寸。 */
    private static final float PHASE_EXIT_DISTORTION_SIZE = 120;
    /** 脱离相位扭曲在“满状态”时的强度。 */
    private static final float PHASE_EXIT_DISTORTION_POWER = 0.75f;
    /** 脱离相位扭曲外圈尺寸倍率。用于让波纹向外再扩一圈。 */
    private static final float PHASE_EXIT_DISTORTION_OUT_SIZE_MULT = 2f;
    /** 脱离相位扭曲内圈满尺寸比例。值越大，中心未扭曲区域越大。 */
    private static final float PHASE_EXIT_DISTORTION_INNER_FULL = 0.2f;
    /** 脱离相位扭曲内圈边缘硬度。 */
    private static final float PHASE_EXIT_DISTORTION_INNER_HARDNESS = 0.5f;
    /** 脱离相位扭曲外圈边缘硬度。 */
    private static final float PHASE_EXIT_DISTORTION_RING_HARDNESS = 0.5f;
    /** 相位 glow 贴图路径。这里复用现有的紫色 glow 资源。 */
    private static final String PHASE_GLOW_SPRITE_PATH = "graphics/missiles/Moci_TDD_1_Missile_glow.png";
    /** 手动 glow 贴图基础宽度。这里直接与导弹本体 size[0] 对齐。 */
    private static final float PHASE_GLOW_SPRITE_WIDTH = 12f;
    /** 手动 glow 贴图基础高度。这里直接与导弹本体 size[1] 对齐。 */
    private static final float PHASE_GLOW_SPRITE_HEIGHT = 57f;
    /** 手动 glow 贴图相对导弹朝向的修正角度。当前贴图需要额外顺时针转 90 度。 */
    private static final float PHASE_GLOW_SPRITE_FACING_OFFSET = -90f;
    /** 手动 glow 贴图宽度脉动幅度。 */
    private static final float PHASE_GLOW_SPRITE_PULSE_WIDTH = 1.25f;
    /** 手动 glow 贴图高度脉动幅度。 */
    private static final float PHASE_GLOW_SPRITE_PULSE_HEIGHT = 1.25f;
    /** 手动 glow 贴图的基础 alpha。控制这层贴图本身有多亮。 */
    private static final float PHASE_GLOW_SPRITE_ALPHA = 0.55f;
    /** 引擎染色混合强度。 */
    private static final float PHASE_ENGINE_COLOR_BLEND = 1f;
    /** 引擎尾迹颜色混合强度。纯相位态下直接压到目标色，退相位时再逐步恢复。 */
    private static final float PHASE_ENGINE_CONTRAIL_BLEND = 1f;
    /** 相位期间尾焰长度缩短量。 */
    private static final float PHASE_ENGINE_FLAME_LENGTH_MOD = -0.75f;
    /** 相位期间尾焰宽度缩小量。 */
    private static final float PHASE_ENGINE_FLAME_WIDTH_MOD = -0.18f;
    /** 相位期间引擎 glow 缩小量。 */
    private static final float PHASE_ENGINE_GLOW_MOD = -0.55f;
    /** 外层相位辉光颜色。用于手动 glow 的 diffuse 层。 */
    private static final Color PHASE_GLOW_COLOR = new Color(170, 115, 255, 110);
    /** 核心相位辉光颜色。用于手动 glow 的 emissive 层。 */
    private static final Color PHASE_GLOW_CORE_COLOR = new Color(225, 205, 255, 145);

    /**
     * 每枚进入相位态的导弹都需要缓存一些原始数据，
     * 以便退相位完成后恢复到导弹配置文件中的默认状态。
     */
    private static class PhaseLaunchData {
        /** 导弹原始碰撞类型，一般来自 .proj 里的 collisionClass。 */
        private final CollisionClass originalCollisionClass;
        /** 导弹原始 EMP 抗性，退相位后恢复。 */
        private final int originalEmpResistance;
        /** 是否已经开始执行退相位动画。 */
        private boolean emerging = false;
        /** 退相位动画已过去的时间。 */
        private float emergeElapsed = 0f;
        /** 绑定在导弹上的 glow 贴图实体。 */
        private SpriteEntity glowSpriteEntity = null;
        /** glow 贴图脉动相位。 */
        private float glowPulsePhase = 0f;

        private PhaseLaunchData(CollisionClass originalCollisionClass, int originalEmpResistance) {
            this.originalCollisionClass = originalCollisionClass;
            this.originalEmpResistance = originalEmpResistance;
            this.glowPulsePhase = (float) (Math.random() * Math.PI * 2.0);
        }
    }

    /**
     * 让手动 glow 跟随导弹位置，同时带一个固定角度偏移来对齐贴图。
     */
    private static class PhaseGlowControlData extends Moci_BoxBasedUtil.EntityBasedControlData {
        private PhaseGlowControlData(MissileAPI missile) {
            super(missile);
        }

        @Override
        public void controlAdvance(RenderDataAPI renderEntity, float amount) {
            if (entity != null) {
                renderEntity.setLocation(entity.getLocation());
                if (withFacing) {
                    renderEntity.setFacingScale(
                            MathUtils.clampAngle(entity.getFacing() + PHASE_GLOW_SPRITE_FACING_OFFSET),
                            1f,
                            1f
                    );
                }

                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine == null || !isEntityValid(engine, entity)) {
                    float[] timer = renderEntity.getGlobalTimer();
                    if (timer[0] > 1) {
                        timer[0] = 1;
                    }
                }
            }
        }
    }

    /** 当前动画帧。 */
    int current = 0;
    /** 充能/开盖动画区间。 */
    int[] chargeUpSequence = new int[]{0, 8};
    /** 发射后闭合/回位动画区间。 */
    int[] chargeDownSequence = new int[]{9, 12};
    /** 动画播放计时器。 */
    float timer = 0;
    /** 上一帧记录到的弹药数，用于在真正消耗弹药时关闭攻击窗口标记。 */
    private int lastAmmo = -1;
    /** 上一帧是否处于“真正的 charge 阶段”，避免冷却期伪 charge 重复打开标记。 */
    private boolean lastChargeStage = false;
    /** 当前这门发射器是否已经对外宣告“正在攻击流程中”。 */
    private boolean attackWindowMarked = false;
    /** 当前是否已经进入一轮 AI 强制开火流程。 */
    private boolean forceFireCycleActive = false;
    /** 当前这轮 AI 强制开火是否已经真正进入过 attack window。 */
    private boolean forceFireSawAttackWindow = false;
    /** 当前是否需要等 chargeLevel 归零后，才允许开启下一轮 AI 强制开火。 */
    private boolean forceFireWaitingForReset = false;
    /** 当前仍在相位潜航流程里的导弹。WeakHashMap 可避免失效导弹残留引用。 */
    private final Map<MissileAPI, PhaseLaunchData> phasedMissiles = new WeakHashMap<>();

    /**
     * Deep Dive AI 通过这个静态方法读取 TDD 武器当前是否处于“需要让出开火窗口”的状态。
     */
    public static boolean isAttackWindowMarked(WeaponAPI weapon) {
        return weapon != null && TDD_ATTACK_WINDOW_MARKER.equals(weapon.getCustom());
    }

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础保护：没有引擎、暂停中、武器/母舰失效时直接退出。
        if (engine == null || engine.isPaused() || weapon == null || weapon.getShip() == null
                || weapon.getAnimation() == null || !weapon.getShip().isAlive()) {
            clearAttackFlowState(weapon);
            return;
        }

        updateAttackWindowMarker(weapon);
        updateAiForcedFire(engine, weapon);

        // 发射器动画由脚本手动逐帧控制，因此先暂停武器自带动画。
        weapon.getAnimation().pause();

        // 判定当前是否处于开盖/发射准备阶段。
        boolean chargeUp = weapon.getChargeLevel() > 0 && (weapon.getCooldownRemaining() == 0 || weapon.isInBurst());

        if (chargeUp) {
            // 充能时直接把 chargeLevel 映射到 0-8 帧，形成线性开盖效果。
            float chargeLevel = weapon.getChargeLevel();
            int to = chargeUpSequence[1];
            current = (int) (chargeLevel * to);
            timer = 0;
        } else {
            if (current > 0) {
                // 非充能状态下，如果发射器仍处于非初始帧，就按帧率逐步回到收纳状态。
                timer += amount * weapon.getAnimation().getFrameRate();

                while (timer >= 1) {
                    if (current == chargeDownSequence[1] || current == 0) {
                        current = 0;
                        break;
                    }

                    timer--;

                    if (current >= chargeUpSequence[1]) {
                        current++;
                    } else {
                        current--;
                    }
                }
            }
        }

        // 应用最终计算出的发射器动画帧。
        weapon.getAnimation().setFrame(current);
        // 然后处理所有已经登记到相位潜航流程中的导弹。
        handlePhaseSubLaunch(amount, engine);
    }

    /**
     * 用武器脚本自己维护一个“攻击窗口标记”。
     *
     * 设计目标：
     * - 进入真正 charge 阶段时打开标记；
     * - 发射导致 ammo 减少后立刻关闭；
     * - 若 charge 被取消并回到 0，也关闭；
     * - 冷却期即便 chargeLevel 仍卡在 >0，也不会重新打开。
     */
    private void updateAttackWindowMarker(WeaponAPI weapon) {
        int currentAmmo = weapon.getAmmo();
        if (lastAmmo < 0) {
            lastAmmo = currentAmmo;
        }

        boolean chargeStage = weapon.getChargeLevel() > 0f
                && (weapon.getCooldownRemaining() <= 0f || weapon.isInBurst());

        if (!attackWindowMarked && chargeStage && !lastChargeStage) {
            setAttackWindowMarker(weapon, true);
        }

        if (attackWindowMarked && (weapon.getChargeLevel() <= 0f || currentAmmo < lastAmmo)) {
            setAttackWindowMarker(weapon, false);
        }

        lastChargeStage = chargeStage;
        lastAmmo = currentAmmo;
    }

    private void setAttackWindowMarker(WeaponAPI weapon, boolean marked) {
        attackWindowMarked = marked;
        if (weapon == null) {
            return;
        }
        if (marked) {
            weapon.setCustom(TDD_ATTACK_WINDOW_MARKER);
        } else if (TDD_ATTACK_WINDOW_MARKER.equals(weapon.getCustom())) {
            weapon.setCustom(null);
        }
    }

    private void clearAttackWindowMarker(WeaponAPI weapon) {
        lastChargeStage = false;
        lastAmmo = weapon != null ? weapon.getAmmo() : -1;
        setAttackWindowMarker(weapon, false);
    }

    private void clearAttackFlowState(WeaponAPI weapon) {
        clearAttackWindowMarker(weapon);
        resetAiForcedFireState();
    }

    /**
     * 在非玩家直接操作时，如果深潜已启用且存在锁定目标，并且目标已经进入射程，
     * 就持续对 TDD 发射器施加一帧强制开火。
     *
     * 为了避免一发打出去后立刻重新拉起下一轮：
     * - 一旦本轮强制开火已经进入过 attack window，就等它结束；
     * - attack window 结束后，暂停继续强制；
     * - 直到 chargeLevel 回到 0，才允许进入下一轮。
     */
    private void updateAiForcedFire(CombatEngineAPI engine, WeaponAPI weapon) {
        if (weapon == null || engine == null || weapon.getShip() == null) {
            resetAiForcedFireState();
            return;
        }

        if (forceFireWaitingForReset && weapon.getChargeLevel() <= 0f) {
            forceFireWaitingForReset = false;
            forceFireSawAttackWindow = false;
        }

        boolean shouldForceFireNow = shouldAiForceFire(engine, weapon);

        if (forceFireCycleActive) {
            if (attackWindowMarked) {
                forceFireSawAttackWindow = true;
            } else if (forceFireSawAttackWindow) {
                forceFireCycleActive = false;
                forceFireWaitingForReset = true;
                return;
            } else if (!shouldForceFireNow) {
                forceFireCycleActive = false;
                forceFireSawAttackWindow = false;
                return;
            }

            weapon.setForceFireOneFrame(true);
            return;
        }

        if (forceFireWaitingForReset || !shouldForceFireNow) {
            return;
        }

        forceFireCycleActive = true;
        forceFireSawAttackWindow = attackWindowMarked;
        weapon.setForceFireOneFrame(true);
    }

    private boolean shouldAiForceFire(CombatEngineAPI engine, WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        if (ship == null || ship.isHulk() || !ship.isAlive()) {
            return false;
        }
        if (!isAiControlled(engine, ship)) {
            return false;
        }
        if (!Moci_DeepDive.isDeepDiveActive(ship)) {
            return false;
        }
        if (weapon.isDisabled() || weapon.getAmmo() <= 0) {
            return false;
        }
        if (weapon.getCooldownRemaining() > 0f && !weapon.isInBurst()) {
            return false;
        }

        ShipAPI lockedTarget = findLockedTarget(weapon);
        return lockedTarget != null && isLockedTargetInRange(weapon, lockedTarget);
    }

    /**
     * “非玩家操作”在这里按“有 AI 接管”或“该舰并不是玩家当前直控舰”处理。
     * 这样 NPC 与托管中的玩家舰都会触发，而手操玩家舰不会被脚本抢开火。
     */
    private boolean isAiControlled(CombatEngineAPI engine, ShipAPI ship) {
        return ship.getShipAI() != null || ship != engine.getPlayerShip();
    }

    /**
     * 优先读取当前武器所在武器组的 autofire 锁定；没有的话回退到舰船的 ship target。
     */
    private ShipAPI findLockedTarget(WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return null;
        }

        ShipAPI target = null;
        WeaponGroupAPI weaponGroup = ship.getWeaponGroupFor(weapon);
        if (weaponGroup != null) {
            AutofireAIPlugin autofireAI = weaponGroup.getAutofirePlugin(weapon);
            if (autofireAI != null) {
                target = autofireAI.getTargetShip();
            }
        }

        if (!isValidLockedTarget(ship, target)) {
            target = ship.getShipTarget();
        }

        return isValidLockedTarget(ship, target) ? target : null;
    }

    private boolean isValidLockedTarget(ShipAPI ship, ShipAPI target) {
        return ship != null
                && target != null
                && target.isAlive()
                && !target.isHulk()
                && target.getOwner() != ship.getOwner();
    }

    /**
     * 追踪导弹不检查射界，只看目标外缘是否已经进入武器射程。
     */
    private boolean isLockedTargetInRange(WeaponAPI weapon, ShipAPI target) {
        if (weapon == null || target == null || weapon.getLocation() == null || target.getLocation() == null) {
            return false;
        }

        float edgeDistance = MathUtils.getDistance(weapon.getLocation(), target.getLocation()) - target.getCollisionRadius();
        return edgeDistance <= weapon.getRange();
    }

    private void resetAiForcedFireState() {
        forceFireCycleActive = false;
        forceFireSawAttackWindow = false;
        forceFireWaitingForReset = false;
    }

    /**
     * 每帧更新所有处于“相位潜航”追踪表中的导弹。
     *
     * 导弹状态分两段：
     * 1. 纯相位态：完全保持无碰撞、紫色引擎、低透明、辉光与尾迹。
     * 2. 退相位态：当目标靠近后，开始一段持续 0.15 秒的淡入/恢复过程。
     */
    private void handlePhaseSubLaunch(float amount, CombatEngineAPI engine) {
        Iterator<Map.Entry<MissileAPI, PhaseLaunchData>> iterator = phasedMissiles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MissileAPI, PhaseLaunchData> entry = iterator.next();
            MissileAPI missile = entry.getKey();
            PhaseLaunchData data = entry.getValue();

            // 导弹已经命中、过期或被移出战场时，直接停止追踪。
            if (missile == null || missile.didDamage() || missile.isExpired() || !engine.isEntityInPlay(missile)) {
                cleanupGlowSprite(data);
                iterator.remove();
                continue;
            }

            if (!data.emerging) {
                // 仍处于纯相位态，持续维持各种外观与抗性。
                maintainPhasedState(amount, engine, missile, data);
                if (shouldBreakPhase(missile)) {
                    // 一旦侦测到敌舰足够接近，就启动退相位流程。
                    data.emerging = true;
                    data.emergeElapsed = 0f;
                    // fadeOutThenIn 用于制造“从相位层浮现回实空间”的感觉。
                    missile.fadeOutThenIn(PHASE_TRANSIT_TIME);
                    createPhaseExitDistortionBurst(missile);
                    engine.addHitParticle(missile.getLocation(), new Vector2f(), 52f, 1f, 0.15f, PHASE_FLASH_COLOR);
                    Global.getSoundPlayer().playSound("system_phase_cloak_deactivate", 1f, 1f, missile.getLocation(), missile.getVelocity());
                }
            } else {
                // 退相位中：逐步把导弹从相位视觉还原为正常导弹。
                data.emergeElapsed += amount;
                float progress = Math.min(1f, data.emergeElapsed / PHASE_TRANSIT_TIME);
                maintainEmergingState(amount, engine, missile, progress, data);

                if (progress >= 1f) {
                    // 退相位完成后，恢复原始碰撞和 EMP 参数，并把超高生命收回正常上限。
                    missile.setCollisionClass(data.originalCollisionClass);
                    missile.setEmpResistance(data.originalEmpResistance);
                    missile.setHitpoints(Math.min(missile.getHitpoints(), missile.getMaxHitpoints()));
                    resetPhaseSpriteAlpha(missile);
                    if (missile.getEngineController() != null) {
                        missile.getEngineController().fadeToOtherColor(this, PHASE_ENGINE_COLOR, PHASE_ENGINE_CONTRAIL_COLOR, 0f, 0.2f);
                    }
                    cleanupGlowSprite(data);
                    iterator.remove();
                }
            }
        }
    }

    /**
     * 由 onFire 触发一次，登记这枚新导弹进入“相位潜航”。
     * 这是整个流程真正的入口，比每帧扫全场导弹更准确也更便宜。
     */
    private void startPhaseSubLaunch(CombatEngineAPI engine, MissileAPI missile) {
        PhaseLaunchData data = new PhaseLaunchData(missile.getCollisionClass(), missile.getEmpResistance());
        if (ENABLE_MANUAL_GLOW_SPRITE) {
            data.glowSpriteEntity = createPhaseGlowSpriteEntity(missile);
        }
        phasedMissiles.put(missile, data);
        // 登记后立刻应用第一帧相位外观，避免刚发射出来时闪一帧普通导弹。
        maintainPhasedState(0f, engine, missile, data);
        engine.addHitParticle(missile.getLocation(), new Vector2f(), 42f, 1f, 0.16f, PHASE_FLASH_COLOR);
        Global.getSoundPlayer().playSound("system_phase_cloak_activate", 1f, 1f, missile.getLocation(), missile.getVelocity());
    }

    /**
     * 维持导弹的“纯相位态”。
     *
     * 这里做的事情包括：
     * - 关闭碰撞
     * - 提高 EMP 抗性
     * - 将生命值锁到很高，近似实现“常规伤害几乎无效”
     * - 降低透明度
     * - 染紫引擎颜色
     * - 生成相位辉光和紫色尾迹
     */
    private void maintainPhasedState(float amount, CombatEngineAPI engine, MissileAPI missile, PhaseLaunchData data) {
        missile.setCollisionClass(CollisionClass.NONE);
        missile.setEmpResistance(PHASE_EMP_RESISTANCE);
        missile.setHitpoints(Math.max(missile.getHitpoints(), missile.getMaxHitpoints() * PHASE_HP_MULT));

        applyPhaseSpriteAlpha(missile, PHASE_ALPHA);

        if (missile.getEngineController() != null) {
            missile.getEngineController().fadeToOtherColor(
                    this,
                    PHASE_ENGINE_COLOR,
                    PHASE_ENGINE_CONTRAIL_COLOR,
                    PHASE_ENGINE_COLOR_BLEND,
                    PHASE_ENGINE_CONTRAIL_BLEND
            );
            missile.getEngineController().extendFlame(this, PHASE_ENGINE_FLAME_LENGTH_MOD, PHASE_ENGINE_FLAME_WIDTH_MOD, PHASE_ENGINE_GLOW_MOD);
        }

        if (ENABLE_MANUAL_GLOW_SPRITE) {
            updatePhaseGlowSprite(data, amount, 1f);
        }
    }

    /**
     * 维持导弹的“退相位过程”。
     *
     * progress 越接近 1，说明导弹越接近完全回到实空间：
     * - 透明度会逐渐提高
     * - 相位抖动、紫色引擎染色、辉光、尾迹会逐渐减弱
     * - 在流程结束前仍然保持无碰撞和高生存性，避免半相位状态被异常击落
     */
    private void maintainEmergingState(float amount, CombatEngineAPI engine, MissileAPI missile, float progress, PhaseLaunchData data) {
        float phaseStrength = Math.max(0f, 1f - progress);
        missile.setCollisionClass(CollisionClass.NONE);
        missile.setEmpResistance(PHASE_EMP_RESISTANCE);
        missile.setHitpoints(Math.max(missile.getHitpoints(), missile.getMaxHitpoints() * PHASE_HP_MULT));

        applyPhaseSpriteAlpha(missile, Math.max(PHASE_ALPHA, progress));

        if (missile.getEngineController() != null) {
            missile.getEngineController().fadeToOtherColor(
                    this,
                    PHASE_ENGINE_COLOR,
                    PHASE_ENGINE_CONTRAIL_COLOR,
                    phaseStrength * PHASE_ENGINE_COLOR_BLEND,
                    phaseStrength * PHASE_ENGINE_CONTRAIL_BLEND
            );
            missile.getEngineController().extendFlame(this,
                    PHASE_ENGINE_FLAME_LENGTH_MOD * phaseStrength,
                    PHASE_ENGINE_FLAME_WIDTH_MOD * phaseStrength,
                    PHASE_ENGINE_GLOW_MOD * phaseStrength);
        }

        if (ENABLE_MANUAL_GLOW_SPRITE) {
            updatePhaseGlowSprite(data, amount, phaseStrength);
        }
    }

    /**
     * 判断导弹是否需要开始退相位。
     *
     * 这里不是看“导弹飞了多久”，而是看导弹周围有没有足够接近的敌舰。
     * 使用 Moci_Util 里的网格检索只取附近敌舰，比遍历战场全部舰船便宜得多。
     *
     * 判定方式：
     * - 检查导弹周围一大圈的敌舰
     * - 用“导弹到敌舰外缘的距离”而不是中心点距离
     * - 只要外缘距离 <= 600，就开始退相位
     */
    private boolean shouldBreakPhase(MissileAPI missile) {
        List<ShipAPI> enemies = Moci_Util.getEnemyShipsInRange(
                missile.getLocation(),
                PHASE_BREAK_RANGE + PHASE_BREAK_SEARCH_PADDING,
                missile.getOwner()
        );

        for (ShipAPI enemy : enemies) {
            if (enemy == null || enemy.isHulk() || enemy.isFighter() || enemy.isDrone()) {
                continue;
            }
            float edgeDistance = MathUtils.getDistance(missile.getLocation(), enemy.getLocation()) - enemy.getCollisionRadius();
            if (edgeDistance <= PHASE_BREAK_RANGE) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在导弹开始脱离相位的那一刻生成一次扭曲波。
     */
    private void createPhaseExitDistortionBurst(MissileAPI missile) {
        if (!ENABLE_PHASE_EXIT_DISTORTION || missile == null || missile.getLocation() == null) {
            return;
        }

        DistortionEntity distortion = new DistortionEntity();
        distortion.setGlobalTimer(
                PHASE_EXIT_DISTORTION_FADE_IN,
                PHASE_EXIT_DISTORTION_DURATION,
                PHASE_EXIT_DISTORTION_FADE_OUT
        );
        distortion.setInnerFull(
                PHASE_EXIT_DISTORTION_INNER_FULL,
                PHASE_EXIT_DISTORTION_INNER_FULL
        );
        distortion.setInnerHardness(PHASE_EXIT_DISTORTION_INNER_HARDNESS);
        distortion.setRingHardness(PHASE_EXIT_DISTORTION_RING_HARDNESS);
        distortion.setSizeIn(0f, 0f);
        distortion.setSizeFull(PHASE_EXIT_DISTORTION_SIZE, PHASE_EXIT_DISTORTION_SIZE);
        distortion.setSizeOut(
                PHASE_EXIT_DISTORTION_SIZE * PHASE_EXIT_DISTORTION_OUT_SIZE_MULT,
                PHASE_EXIT_DISTORTION_SIZE * PHASE_EXIT_DISTORTION_OUT_SIZE_MULT
        );
        distortion.setPowerIn(0f);
        distortion.setPowerFull(PHASE_EXIT_DISTORTION_POWER);
        distortion.setPowerOut(0f);
        distortion.setLocation(new Vector2f(missile.getLocation()));
        CombatRenderingManager.addEntity(distortion);
    }

    /**
     * 创建绑定在导弹身上的 glow 贴图实体。
     *
     * 这个方案走“真正的贴图壳层”：
     * - 使用一张独立的 glow 贴图作为 diffuse
     * - 再把同一张贴图挂到 emissive 层
     * - 由 BoxUtil 的 SpriteEntity 绑定到导弹身上并自动跟随
     *
     * 它的优点是轮廓更稳定、更像真正罩在导弹外面的相位壳层；
     * 为了避免轮廓显得太死板，后续会在 updatePhaseGlowSprite() 里给它加轻微脉动。
     */
    private SpriteEntity createPhaseGlowSpriteEntity(MissileAPI missile) {
        // 读取 glow 贴图。
        // 当前直接使用导弹专用的 glow 资源。
        // 以后如果你换图，只需要改 PHASE_GLOW_SPRITE_PATH。
        SpriteAPI glowSprite = Global.getSettings().getSprite(PHASE_GLOW_SPRITE_PATH);
        if (glowSprite == null) {
            return null;
        }

        // 用 BoxUtil 把这张贴图绑定到导弹目标上。
        // in = 0f：进入相位时立刻出现，不做单独淡入。
        // full = 9999f：几乎无限保持，实际结束时由 cleanupGlowSprite() 手动删除。
        // out = 0.2f：如果实体自身进入淡出，会在 0.2 秒内消失。
        // layer = ABOVE_PARTICLES_LOWER：
        // 放在较低的粒子层之上，避免完全被尾迹盖住，同时也不会压过过多前景特效。
        SpriteEntity spriteEntity = Moci_BoxBasedUtil.addSpriteToTarget(
                glowSprite,
                missile,
                0f,
                9999f,
                0.2f,
                CombatEngineLayers.ABOVE_PARTICLES_LOWER
        );
        spriteEntity.setControlData(new PhaseGlowControlData(missile));

        // additive blend：
        // 使用加法混合，让亮部叠加到背景上，形成更典型的“发光”效果。
        spriteEntity.setAdditiveBlend();

        // emissiveSprite = glowSprite：
        // emissive 层也用同一张贴图，这样可以让这层 glow 不受普通光照影响，
        // 看起来更像相位系统的能量外壳。
        spriteEntity.setEmissiveSprite(glowSprite);

        // 基础尺寸：
        // 这里直接使用与导弹本体一致的宽高（12 x 57），
        // 让 glow 贴图轮廓与细长鱼雷本体保持一致。
        spriteEntity.setBaseSizePerTiles(PHASE_GLOW_SPRITE_WIDTH, PHASE_GLOW_SPRITE_HEIGHT);

        // diffuse 层颜色：
        // 使用较深的紫色半透明，决定 glow 外圈整体的主色调。
        spriteEntity.getMaterialData().setColor(PHASE_GLOW_COLOR);

        // diffuse alpha：
        // 0.55f 是这层贴图的基础可见度。越大越实，越小越像淡薄能量层。
        spriteEntity.getMaterialData().setColorAlpha(PHASE_GLOW_SPRITE_ALPHA);

        // emissive 颜色：
        // 使用更亮、更偏白的紫色，决定 glow 内层“发热”的感觉。
        spriteEntity.getMaterialData().setEmissiveColor(PHASE_GLOW_CORE_COLOR);

        // emissive alpha：
        // 比 diffuse alpha 再高一点，让中心发光更明显。
        // 这里使用 alpha + 0.18f，能保持“外圈淡、核心亮”的层次。
        spriteEntity.getMaterialData().setEmissiveColorAlpha(Math.min(1f, PHASE_GLOW_SPRITE_ALPHA + 0.18f));

        // colorToEmissive = 1f：
        // diffuse 层颜色完全参与 emissive 表现，保证整张贴图都是“亮的”。
        spriteEntity.getMaterialData().setColorToEmissive(1f);

        // alphaToEmissive = 1f：
        // 透明度也完整传给 emissive 层，这样后续只改 alpha 就能让整层 glow 一起淡出。
        spriteEntity.getMaterialData().setAlphaToEmissive(1f);

        // ignoreIllumination = true：
        // 不受场景光照影响，确保它始终表现为“自发光相位壳层”。
        spriteEntity.getMaterialData().setIgnoreIllumination(true);
        return spriteEntity;
    }

    /**
     * 每帧更新 glow 贴图的尺寸和透明度。
     *
     * 由于 glowSprite 是独立贴图层，我们可以让它轻微脉动，
     * 这会比单纯的固定发光圈更接近 phase cloak 的“能量壳层”感觉。
     */
    private void updatePhaseGlowSprite(PhaseLaunchData data, float amount, float phaseStrength) {
        if (data == null || data.glowSpriteEntity == null || data.glowSpriteEntity.hasDelete()) {
            return;
        }

        data.glowPulsePhase += amount * 4f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(data.glowPulsePhase);
        float width = PHASE_GLOW_SPRITE_WIDTH + PHASE_GLOW_SPRITE_PULSE_WIDTH * pulse * phaseStrength;
        float height = PHASE_GLOW_SPRITE_HEIGHT + PHASE_GLOW_SPRITE_PULSE_HEIGHT * pulse * phaseStrength;
        float alpha = PHASE_GLOW_SPRITE_ALPHA * Math.max(0.15f, phaseStrength);

        data.glowSpriteEntity.setBaseSizePerTiles(width, height);
        data.glowSpriteEntity.getMaterialData().setColorAlpha(alpha);
        data.glowSpriteEntity.getMaterialData().setEmissiveColorAlpha(Math.min(1f, alpha + 0.18f * phaseStrength));
    }


    /**
     * 导弹本体渲染不会稳定读取 SpriteAPI.setAlphaMult()；
     * 正确做法是使用 MissileAPI 自带的 sprite alpha override。
     * 这里额外乘上 currentBaseAlpha，保留 fadeOutThenIn()/fizzle 的原生淡化。
     */
    private void applyPhaseSpriteAlpha(MissileAPI missile, float alpha) {
        if (missile == null) {
            return;
        }

        float clampedAlpha = Math.max(0f, Math.min(1f, alpha));
        missile.setSpriteAlphaOverride(clampedAlpha * missile.getCurrentBaseAlpha());
    }

    /**
     * 清除相位期间写入的 alpha override，恢复导弹原生透明度计算。
     */
    private void resetPhaseSpriteAlpha(MissileAPI missile) {
        if (missile == null) {
            return;
        }
        missile.setSpriteAlphaOverride(-1f);
    }

    /**
     * 删除导弹附着的 glow 贴图实体。
     */
    private void cleanupGlowSprite(PhaseLaunchData data) {
        if (data == null || data.glowSpriteEntity == null) {
            return;
        }
        if (!data.glowSpriteEntity.hasDelete()) {
            data.glowSpriteEntity.delete();
        }
        data.glowSpriteEntity = null;
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 只处理这两门内置反舰导弹实际发射出的导弹弹体。
        if (!(projectile instanceof MissileAPI) || weapon == null || engine == null || weapon.getShip() == null) {
            return;
        }

        if (!Moci_DeepDive.isDeepDiveActive(weapon.getShip())) {
            return;
        }

        startPhaseSubLaunch(engine, (MissileAPI) projectile);
    }
}
