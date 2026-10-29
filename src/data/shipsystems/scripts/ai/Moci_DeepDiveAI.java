package data.shipsystems.scripts.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.util.IntervalUtil;

import data.shipsystems.scripts.Moci_DeepDiveMissileManager;
import data.shipsystems.scripts.Moci_DeepDive;

/**
 * 深潜系统 AI（Moci版）
 *
 * 行为目标：
 * 评估弹丸、正在照射及即将对准本舰的光束；备弹就绪时也可主动潜航接敌。
 * 接敌机动通过原版 PHASE_ATTACK_RUN 标记实现，索敌与开火仍由舰船 AI 负责。
 */
public class Moci_DeepDiveAI implements ShipSystemAIScript {
    /** 系统已激活时的威胁阈值倍率，用于降低维持深潜所需的风险门槛。 */
    private static final float ACTIVE_THREAT_THRESHOLD_MULT = 0.75f;
    /** 一次“需要深潜”的判定至少维持多久，避免 AI 抖动。 */
    private static final float MIN_PHASE_KEEP = 1f;
    /** 实际开启深潜后额外延长的最短维持时间。 */
    private static final float MIN_PHASE_KEEP_ON_ACTIVATE = 5f;
    /** 上浮后留出充能/瞄准窗口，避免刚能开火就再次下潜。 */
    private static final float WEAPON_ATTACK_WINDOW = 2f;
    /** 高硬幅能阈值；超过后会阻止 AI 主动新开深潜。 */
    private static final float HIGH_HARD_FLUX_BLOCK_LEVEL = 0.9f;
    private static final float ATTACK_AMMO_FRACTION = 0.3f;
    private static final float ATTACK_START_FLUX_LEVEL = 0.6f;
    private static final float ATTACK_ABORT_FLUX_LEVEL = 0.8f;
    private static final float MAX_ATTACK_RUN_TIME = 15f;
    /** 仅限制失败进攻的重试，不限制防御下潜或上浮后正常再次下潜。 */
    private static final float ATTACK_RETRY_DELAY = 4f;
    private static final float BEAM_REACTION_TIME = 0.25f;
    private static final float BEAM_DAMAGE_HORIZON = 2f;

    /** 当前执行 AI 的舰船。 */
    private ShipAPI ship;
    /** 战斗引擎引用。 */
    private CombatEngineAPI engine;
    /** 深潜系统引用（优先相位披风槽位）。 */
    private ShipSystemAPI system;
    private ShipwideAIFlags flags;

    /** 高频威胁检测计时器。 */
    private final IntervalUtil threatChecker = new IntervalUtil(0.05f, 0.15f);
    /** 低频武器潜在命中扫描计时器。 */
    private final IntervalUtil weaponChecker = new IntervalUtil(0.2f, 0.3f);
    private boolean weaponsScanned;

    /** 当前帧是否建议开启深潜。 */
    private boolean shouldDo = false;
    /** 低频扫描得到的“可能打到本舰”的敌方武器缓存。 */
    private final List<WeaponAPI> weaponPotentialHit = new ArrayList<>();

    /** 避免同一束正在命中的激光又按炮口朝向重复累计。 */
    private final Set<WeaponAPI> hittingBeamWeapons = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 保持计时（>0 时继续维持深潜意图）。 */
    private float keep = 0f;
    /** 普通武器攻击窗口计时，避免系统在开火前一帧重新开启深潜。 */
    private float weaponAttackWindow = 0f;
    private float submergedTime = 0f;
    private boolean emergencyDive = false;
    private ShipAPI attackRunTarget;
    private float attackRunTime;
    private float attackRetry;
    private float attackRange;
    private boolean ownsAttackFlag;

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.engine = engine;
        this.flags = flags;

        // 与原实现一致：优先读取相位披风槽位，兼容系统槽位作为兜底。
        this.system = ship.getPhaseCloak() != null ? ship.getPhaseCloak() : system;

        // 强制 AI 采用更积极的侧向机动（原逻辑保留）。
        if (ship.getShipAI() != null) {
            ship.getShipAI().getConfig().alwaysStrafeOffensively = true;
        }
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine == null || ship == null || system == null) {
            finishAttackRun(false);
            return;
        }
        if (engine.isPaused()) {
            return;
        }
        if (!ship.isAlive() || ship.isHulk() || engine.isCombatOver() || !engine.isEntityInPlay(ship)) {
            finishAttackRun(false);
            return;
        }

        threatChecker.advance(amount);
        weaponChecker.advance(amount);
        keep = Math.max(0f, keep - amount);
        weaponAttackWindow = Math.max(0f, weaponAttackWindow - amount);
        attackRetry = Math.max(0f, attackRetry - amount);

        boolean systemActive = system.isOn() || system.isActive() || system.isChargeup() || system.isChargedown();
        submergedTime = systemActive ? submergedTime + amount : 0f;

        // 低频统计：缓存“潜在威胁武器”。
        if (weaponChecker.intervalElapsed() || !weaponsScanned) {
            weaponsScanned = true;
            weaponPotentialHit.clear();
            // 范围很大的候选扫描沿用舰船列表；高频朝向检查只处理缓存武器。
            for (ShipAPI enemy : engine.getShips()) {
                if (!isThreatSource(enemy)) {
                    continue;
                }
                for (WeaponAPI weapon : enemy.getAllWeapons()) {
                    if (isPotentialWeaponThreat(weapon)) {
                        weaponPotentialHit.add(weapon);
                    }
                }
            }
        }

        // 高频统计：评估当前威胁总势能 potential，并与阈值 lim 比较。
        boolean evaluate = threatChecker.intervalElapsed();
        if (evaluate) {
            float potential = 0f;
            float beamPotential = 0f;
            hittingBeamWeapons.clear();

            // 1) 弹丸威胁评估
            for (DamagingProjectileAPI proj : engine.getProjectiles()) {
                if (proj.getOwner() == ship.getOwner()) {
                    continue;
                }

                float distSq = MathUtils.getDistanceSquared(proj.getLocation(), ship.getLocation());
                float speed = proj.getMoveSpeed() == 0f ? 1f : proj.getMoveSpeed();
                float horizon = getSystemCooldown();
                if (distSq > speed * speed * horizon * horizon) {
                    continue;
                }

                if (distSq < 2.5f * ship.getCollisionRadius() * ship.getCollisionRadius()) {
                    float fragMult = proj.getDamageType() == DamageType.FRAGMENTATION ? 0.25f : 1f;
                    potential += fragMult * proj.getDamage().getDamage();
                }
            }

            // 2) 光束威胁评估
            for (BeamAPI beam : engine.getBeams()) {
                // 已经照到本舰的光束本身就是证据，不要求其源舰仍在视野中或尚未排幅。
                ShipAPI source = beam.getSource();
                if (source == null || source.getOwner() == ship.getOwner() || source.getOwner() == 100
                        || beam.getBrightness() <= 0f
                        || beam.getDamage() == null || !beamIntersectsShip(beam)) {
                    continue;
                }
                beamPotential += beam.getDamage().getDamage() * BEAM_DAMAGE_HORIZON
                        * Math.min(1f, beam.getBrightness()) * damageThreatMult(beam.getDamage().getType());
                hittingBeamWeapons.add(beam.getWeapon());
            }

            // 3) 潜在武器威胁评估（转向时间 + 武器伤害）
            for (WeaponAPI weapon : weaponPotentialHit) {
                if (!isPotentialWeaponThreat(weapon) || weapon.getDamage() == null) {
                    continue;
                }
                if (weapon.isBeam()) {
                    if (!hittingBeamWeapons.contains(weapon) && isBeamAimedAtShip(weapon)
                            && (weapon.isFiring() || weapon.isInBurst()
                                || weapon.getCooldownRemaining() <= BEAM_REACTION_TIME)) {
                        beamPotential += weapon.getDamage().getDamage() * BEAM_DAMAGE_HORIZON
                                * damageThreatMult(weapon.getDamageType());
                    }
                    continue;
                }
                float turnRate = MathUtils.clamp(weapon.getTurnRate(), 1f, 100f);
                float targetAngle = VectorUtils.getAngle(weapon.getLocation(), ship.getLocation());
                float turnTime = Math.abs(MathUtils.getShortestRotation(weapon.getCurrAngle(), targetAngle)) / turnRate;

                potential += MathUtils.clamp(5f - turnTime, 0f, 5f) * weapon.getDamage().getDamage() * 0.1f;
            }
            potential += beamPotential;

            // 4) 基础阈值：舰船越残，阈值越低（更容易触发深潜）。
            float lim = Math.min(700f, ship.getMaxHitpoints() * ship.getHullLevel());

            // 引擎熄火时提高保命倾向。
            if (ship.getEngineController().isFlamedOut()) {
                lim *= 0.1f;
            }

            float threshold = systemActive ? lim * ACTIVE_THREAT_THRESHOLD_MULT : lim;
            shouldDo = potential >= threshold;
            // 足以穿过阈值的直射光束不应被普通武器的攻击窗口压掉。
            emergencyDive = potential >= lim * 2f || beamPotential >= lim
                    || ship.getEngineController().isFlamedOut();

            // 5) 武器状态触发：失能比例高时也可触发深潜（原逻辑保留）。
            int totalScale = 1;
            float repairScale = 1f;
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (weapon.isDecorative()) {
                    continue;
                }
                totalScale += 2 * weapon.getSize().ordinal();
                if (weapon.isDisabled()) {
                    repairScale += 2f * weapon.getSize().ordinal();
                }
            }
            shouldDo = shouldDo || (repairScale / totalScale > 0.7f);

            // 如果本轮确认需要深潜，至少维持一秒意图。
            if (shouldDo) {
                keep = Math.max(keep, MIN_PHASE_KEEP);
            }
        }

        // keep 机制：短时间维持决策，降低抖动。
        shouldDo = shouldDo || keep > 0f;

        // 高硬幅能只阻止“新进入”深潜，不强制已深潜状态立刻上浮。
        if (!systemActive && ship.getHardFluxLevel() >= HIGH_HARD_FLUX_BLOCK_LEVEL) {
            shouldDo = false;
        }

        // 普通武器（包括深潜期间被禁止的 source 导弹）有合法目标时，
        // 让出深潜状态，由默认武器组 AI 负责索敌和开火。
        boolean weaponAttackOpportunity = hasNormalWeaponAttackOpportunity(target);
        updateAttackRun(amount, target, systemActive, evaluate);
        boolean reachedAttackRange = attackRunTarget != null && isAtAttackRange();
        if (attackRunTarget != null) {
            shouldDo = true;
        }
        if (!emergencyDive && ((weaponAttackOpportunity
                && (!systemActive || submergedTime >= MIN_PHASE_KEEP_ON_ACTIVATE))
                || (reachedAttackRange && system.isOn()
                    && submergedTime >= MIN_PHASE_KEEP_ON_ACTIVATE))) {
            finishAttackRun(false);
            weaponAttackWindow = WEAPON_ATTACK_WINDOW;
            shouldDo = false;
            keep = 0f;
        } else if (!emergencyDive && weaponAttackWindow > 0f && attackRunTarget == null) {
            shouldDo = false;
        }

        // 最终门控覆盖威胁消失和旧攻击窗口，防止刚下潜就上浮。
        // 只约束 AI 主动切换；玩家操作、幅能过载等引擎强制退出不受影响。
        if (system.isOn() && submergedTime < MIN_PHASE_KEEP_ON_ACTIVATE) {
            shouldDo = true;
        }
        if (attackRunTarget != null && system.isOn() && !emergencyDive) {
            flags.setFlag(AIFlags.PHASE_ATTACK_RUN, 0.3f, this);
            ownsAttackFlag = true;
        } else {
            releaseAttackFlag();
        }

        // 执行开关操作（PHASE_CLOAK 使用 TOGGLE 指令）。
        if (shouldDo) {
            if (!system.isOn() && !systemActive) {
                ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, ship.getMouseTarget(), 0);
                keep = Math.max(keep, MIN_PHASE_KEEP_ON_ACTIVATE);
            }
        } else {
            if (system.isOn()) {
                ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, ship.getMouseTarget(), 0);
            }
        }
    }

    private boolean isThreatSource(ShipAPI source) {
        return source != null && source.isAlive() && !source.isHulk()
                && source.getOwner() != ship.getOwner() && source.getOwner() != 100
                && !source.isPhased() && !Moci_DeepDive.isDeepDiveActive(source)
                && engine.isEntityInPlay(source)
                && (source.getFluxTracker() == null || !source.getFluxTracker().isOverloadedOrVenting())
                && (engine.getFogOfWar(ship.getOwner()) == null
                    || engine.getFogOfWar(ship.getOwner()).isVisible(source));
    }

    private boolean isPotentialWeaponThreat(WeaponAPI weapon) {
        if (weapon == null || weapon.isDecorative() || weapon.isDisabled() || weapon.isForceDisabled()
                || !isThreatSource(weapon.getShip()) || weapon.getLocation() == null
                || weapon.getRange() <= 0f || (weapon.usesAmmo() && weapon.getAmmo() <= 0)
                || (!ship.isFighter() && weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY))) {
            return false;
        }
        float range = weapon.getRange() * 1.1f + ship.getCollisionRadius();
        return MathUtils.getDistanceSquared(weapon.getLocation(), ship.getLocation()) <= range * range
                && weapon.distanceFromArc(ship.getLocation()) <= 0f;
    }

    /** 光束必须实际穿过本舰附近的有限线段，不能把射向别处的延长线算成命中。 */
    private boolean beamIntersectsShip(BeamAPI beam) {
        Vector2f from = beam.getFrom();
        Vector2f to = beam.getTo();
        if (from == null || to == null) {
            return false;
        }
        float dx = to.x - from.x;
        float dy = to.y - from.y;
        float lengthSq = dx * dx + dy * dy;
        if (lengthSq <= 0f) {
            return false;
        }
        float t = MathUtils.clamp(((ship.getLocation().x - from.x) * dx
                + (ship.getLocation().y - from.y) * dy) / lengthSq, 0f, 1f);
        float offsetX = ship.getLocation().x - from.x - t * dx;
        float offsetY = ship.getLocation().y - from.y - t * dy;
        float radius = ship.getCollisionRadius() + beam.getWidth() * 0.5f + 15f;
        return offsetX * offsetX + offsetY * offsetY <= radius * radius;
    }

    /** 用炮口当前朝向预判瞬发光束，并留出很短的炮塔转向预警量。 */
    private boolean isBeamAimedAtShip(WeaponAPI weapon) {
        float radius = ship.getCollisionRadius() + 15f;
        float distanceSq = MathUtils.getDistanceSquared(weapon.getLocation(), ship.getLocation());
        float range = weapon.getRange() + radius;
        if (distanceSq > range * range) {
            return false;
        }
        float targetAngle = VectorUtils.getAngle(weapon.getLocation(), ship.getLocation());
        float offset = Math.abs(MathUtils.getShortestRotation(weapon.getCurrAngle(), targetAngle));
        float hullAngle = (float) Math.toDegrees(Math.atan2(radius, Math.sqrt(distanceSq)));
        float leadAngle = Math.min(8f, Math.max(0f, weapon.getTurnRate()) * BEAM_REACTION_TIME);
        return offset <= hullAngle + leadAngle;
    }

    private float damageThreatMult(DamageType type) {
        return type == DamageType.FRAGMENTATION ? 0.25f : 1f;
    }

    private boolean isAttackWeapon(WeaponAPI weapon) {
        return weapon != null && !weapon.isDecorative() && !weapon.isDisabled() && !weapon.isForceDisabled()
                && ship.getWeaponGroupFor(weapon) != null && weapon.getRange() > 0f
                && !weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY);
    }

    private boolean attackOrdersAllowApproach() {
        return flags != null && ship.getShipAI() != null && !ship.isRetreating()
                && !flags.hasFlag(AIFlags.BACK_OFF) && !flags.hasFlag(AIFlags.BACKING_OFF)
                && !flags.hasFlag(AIFlags.DO_NOT_PURSUE) && !flags.hasFlag(AIFlags.RUN_QUICKLY);
    }

    private void updateAttackRun(float amount, ShipAPI target, boolean systemActive, boolean evaluate) {
        ShipAPI currentTarget = isValidLockedTarget(ship, target) ? target : ship.getShipTarget();
        if (attackRunTarget != null) {
            attackRunTime += amount;
            if (!isValidLockedTarget(ship, attackRunTarget) || attackRunTarget != currentTarget
                    || !attackOrdersAllowApproach() || system.isChargedown()
                    || ship.getFluxTracker().isOverloadedOrVenting()
                    || ship.getFluxLevel() >= ATTACK_ABORT_FLUX_LEVEL
                    || ship.getHardFluxLevel() >= ATTACK_ABORT_FLUX_LEVEL
                    || (evaluate && !hasAttackPayload())
                    || attackRunTime >= MAX_ATTACK_RUN_TIME
                    || (!systemActive && attackRunTime > 0.5f)) {
                finishAttackRun(true);
            }
            return;
        }
        if (!evaluate || systemActive || emergencyDive || shouldDo || weaponAttackWindow > 0f
                || attackRetry > 0f || !attackOrdersAllowApproach() || !system.canBeActivated()
                || !isValidLockedTarget(ship, currentTarget) || currentTarget.isFighter()
                || ship.getFluxTracker().isOverloadedOrVenting()
                || ship.getFluxLevel() >= ATTACK_START_FLUX_LEVEL
                || ship.getHardFluxLevel() >= ATTACK_START_FLUX_LEVEL || ship.getHullLevel() < 0.35f) {
            return;
        }

        float surfaceRange = 0f;
        float longestRange = 0f;
        float volleyFlux = 0f;
        boolean hasSubLaunchWeapon = false;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!isAttackWeapon(weapon)) {
                continue;
            }
            // 只在攻击武器准备好时启动；潜航期间允许内置导弹正常发射、冷却。
            if (weapon.isFiring() || weapon.isInBurst() || weapon.getCooldownRemaining() > 0.1f
                    || (weapon.usesAmmo() && weapon.getAmmo() < Math.max(1,
                        (int) Math.ceil(weapon.getMaxAmmo() * ATTACK_AMMO_FRACTION)))) {
                return;
            }
            boolean subLaunch = Moci_DeepDiveMissileManager.isAllowedWeapon(weapon);
            hasSubLaunchWeapon |= subLaunch;
            longestRange = Math.max(longestRange, weapon.getRange());
            if (!subLaunch) {
                surfaceRange = Math.max(surfaceRange, weapon.getRange());
            }
            volleyFlux += Math.max(0f, weapon.getFluxCostToFire());
        }
        if (!hasSubLaunchWeapon || longestRange <= 0f) {
            return;
        }
        float freeFlux = ship.getMaxFlux() - ship.getCurrFlux();
        float requiredFlux = system.getFluxPerUse() + system.getFluxPerSecond()
                * MIN_PHASE_KEEP_ON_ACTIVATE + volleyFlux;
        if (freeFlux <= requiredFlux) {
            return;
        }
        float preferredRange = (surfaceRange > 0f ? surfaceRange : longestRange) * 0.85f;
        float inner = Math.max(preferredRange + currentTarget.getCollisionRadius(),
                ship.getCollisionRadius() + currentTarget.getCollisionRadius() + 150f);
        float estimatedSpeed = Math.max(ship.getMaxSpeed(), ship.getMutableStats().getMaxSpeed().getModifiedValue());
        float outer = Math.max(longestRange * 1.5f, inner + estimatedSpeed * MAX_ATTACK_RUN_TIME)
                + currentTarget.getCollisionRadius();
        float distanceSq = MathUtils.getDistanceSquared(ship.getLocation(), currentTarget.getLocation());
        if (distanceSq <= (inner + 150f) * (inner + 150f) || distanceSq > outer * outer) {
            return;
        }
        attackRunTarget = currentTarget;
        attackRange = inner;
        attackRunTime = 0f;
    }

    private boolean isAtAttackRange() {
        float distanceSq = MathUtils.getDistanceSquared(ship.getLocation(), attackRunTarget.getLocation());
        float safeSeparation = ship.getCollisionRadius() + attackRunTarget.getCollisionRadius() + 50f;
        return distanceSq <= attackRange * attackRange && distanceSq >= safeSeparation * safeSeparation;
    }

    private boolean hasAttackPayload() {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (isAttackWeapon(weapon) && (!weapon.usesAmmo() || weapon.getAmmo() > 0)) {
                return true;
            }
        }
        return false;
    }

    private void finishAttackRun(boolean failed) {
        if (attackRunTarget != null && failed) {
            attackRetry = ATTACK_RETRY_DELAY;
        }
        attackRunTarget = null;
        attackRunTime = 0f;
        releaseAttackFlag();
    }

    private void releaseAttackFlag() {
        if (ownsAttackFlag && flags != null && flags.getCustom(AIFlags.PHASE_ATTACK_RUN) == this) {
            flags.unsetFlag(AIFlags.PHASE_ATTACK_RUN);
        }
        ownsAttackFlag = false;
    }

    /**
     * 获取系统冷却时长。
     * 若相位披风不存在则回退到系统槽位，并给出最小兜底值避免除零或空指针。
     */
    private float getSystemCooldown() {
        ShipSystemAPI phase = ship.getPhaseCloak() != null ? ship.getPhaseCloak() : system;
        if (phase == null) {
            return 1f;
        }
        return Math.max(phase.getCooldown(), 0.0001f);
    }

    /**
     * 找出深潜期间不能发射、但当前已经具备攻击条件的普通武器。
     * 允许潜射的内置/隐藏导弹不会触发退潜；source 导弹和所有非导弹武器会触发。
     */
    private boolean hasNormalWeaponAttackOpportunity(ShipAPI fallbackTarget) {
        if (ship.getFluxTracker().isOverloadedOrVenting()) {
            return false;
        }
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!isAttackWeapon(weapon)) {
                continue;
            }
            if (weapon.getType() == WeaponAPI.WeaponType.MISSILE
                    && Moci_DeepDiveMissileManager.isAllowedWeapon(weapon)) {
                continue;
            }
            if (weapon.usesAmmo() && weapon.getAmmo() <= 0) {
                continue;
            }
            if (weapon.getCooldownRemaining() > 0f && !weapon.isInBurst()) {
                continue;
            }
            if (weapon.getFluxCostToFire() > ship.getMaxFlux() - ship.getCurrFlux()) {
                continue;
            }

            ShipAPI target = findWeaponTarget(weapon, fallbackTarget);
            if (!isValidLockedTarget(ship, target) || !isWeaponTargetInRange(weapon, target)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private ShipAPI findWeaponTarget(WeaponAPI weapon, ShipAPI fallbackTarget) {
        if (weapon == null || weapon.getShip() == null) {
            return null;
        }

        ShipAPI source = weapon.getShip();
        ShipAPI target = null;
        WeaponGroupAPI weaponGroup = source.getWeaponGroupFor(weapon);
        if (weaponGroup != null) {
            AutofireAIPlugin autofireAI = weaponGroup.getAutofirePlugin(weapon);
            if (autofireAI != null) {
                target = autofireAI.getTargetShip();
            }
        }

        if (!isValidLockedTarget(source, target)) {
            target = source.getShipTarget();
        }
        if (!isValidLockedTarget(source, target)) {
            target = fallbackTarget;
        }
        return isValidLockedTarget(source, target) ? target : null;
    }

    private boolean isValidLockedTarget(ShipAPI source, ShipAPI target) {
        return source != null
                && target != null
                && target.isAlive()
                && !target.isHulk()
                && target.getOwner() != 100
                && target.getOwner() != source.getOwner()
                && !target.isPhased()
                && !Moci_DeepDive.isDeepDiveActive(target)
                && engine.isEntityInPlay(target)
                && (engine.getFogOfWar(source.getOwner()) == null
                    || engine.getFogOfWar(source.getOwner()).isVisible(target));
    }

    private boolean isWeaponTargetInRange(WeaponAPI weapon, ShipAPI target) {
        if (weapon == null || target == null || weapon.getLocation() == null || target.getLocation() == null) {
            return false;
        }
        float range = weapon.getRange() + target.getCollisionRadius();
        if (MathUtils.getDistanceSquared(weapon.getLocation(), target.getLocation()) > range * range) {
            return false;
        }
        return weapon.distanceFromArc(target.getLocation()) <= 0f;
    }
}
