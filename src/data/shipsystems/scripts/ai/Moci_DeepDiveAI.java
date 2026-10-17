package data.shipsystems.scripts.ai;

import java.util.ArrayList;
import java.util.List;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
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
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.util.IntervalUtil;

import data.scripts.weapons.Moci_TDD_1_missileAnimEffect;

/**
 * 深潜系统 AI（Moci版）
 *
 * 行为目标：
 * 1. 评估当前 incoming 威胁（弹丸、光束、潜在武器瞄准压力）。
 * 2. 在风险高时自动开启深潜，在风险降低时自动关闭深潜。
 * 3. 通过 keep 机制维持最短持续时间，避免频繁闪开闪关。
 *
 * 说明：
 * - 核心流程按 HSIDeepDiveAI 复刻，保留原行为与决策结构。
 */
public class Moci_DeepDiveAI implements ShipSystemAIScript {
    /** 系统已激活时的威胁阈值倍率，用于降低维持深潜所需的风险门槛。 */
    private static final float ACTIVE_THREAT_THRESHOLD_MULT = 0.75f;
    /** 一次“需要深潜”的判定至少维持多久，避免 AI 抖动。 */
    private static final float MIN_PHASE_KEEP = 1f;
    /** 实际开启深潜后额外延长的最短维持时间。 */
    private static final float MIN_PHASE_KEEP_ON_ACTIVATE = 3f;
    /** 为 TDD 导弹预留的退相位/攻击窗口时长。 */
    private static final float TDD_ATTACK_WINDOW = 2f;
    /** 是否允许 AI 在“导弹已有合法目标”时主动退相位，为 TDD 创造开火机会。 */
    private static final boolean ALLOW_TDD_PREEMPTIVE_UNPHASE = false;
    /** 高硬幅能阈值；超过后会阻止 AI 主动新开深潜。 */
    private static final float HIGH_HARD_FLUX_BLOCK_LEVEL = 0.9f;

    /** 当前执行 AI 的舰船。 */
    private ShipAPI ship;
    /** 战斗引擎引用。 */
    private CombatEngineAPI engine;
    /** 深潜系统引用（优先相位披风槽位）。 */
    private ShipSystemAPI system;

    /** 高频威胁检测计时器。 */
    private final IntervalUtil threatChecker = new IntervalUtil(0.05f, 0.15f);
    /** 低频武器潜在命中扫描计时器。 */
    private final IntervalUtil weaponChecker = new IntervalUtil(0.8f, 1.0f);

    /** 防御方向加权向量，用于累计来袭压力方向（当前类中仅保留原逻辑数据结构）。 */
    private Vector2f weightedDefensiveDir = new Vector2f();
    /** 当前帧是否建议开启深潜。 */
    private boolean shouldDo = false;
    /** 低频扫描得到的“可能打到本舰”的敌方武器缓存。 */
    private final List<WeaponAPI> weaponPotentialHit = new ArrayList<>();

    /** 随机旋向符号（用于构建防御方向扰动）。 */
    private int randomSign = 1;
    /** 保持计时（>0 时继续维持深潜意图）。 */
    private float keep = 0f;
    /** TDD 导弹的攻击窗口计时。>0 时优先给导弹让出开火/退相位窗口。 */
    private float tddAttackWindow = 0f;

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.engine = engine;

        // 与原实现一致：优先读取相位披风槽位，兼容系统槽位作为兜底。
        this.system = ship.getPhaseCloak() != null ? ship.getPhaseCloak() : system;
        this.randomSign = (Math.random() > 0.5f) ? -1 : 1;

        // 强制 AI 采用更积极的侧向机动（原逻辑保留）。
        if (ship.getShipAI() != null) {
            ship.getShipAI().getConfig().alwaysStrafeOffensively = true;
        }
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (engine == null || engine.isPaused() || ship == null || system == null) {
            return;
        }

        threatChecker.advance(amount);
        weaponChecker.advance(amount);
        keep = Math.max(0f, keep - amount);
        tddAttackWindow = Math.max(0f, tddAttackWindow - amount);

        boolean systemActive = system.isOn() || system.isActive() || system.isChargeup() || system.isChargedown();
        updateTddAttackWindow(systemActive);

        // 低频统计：缓存“潜在威胁武器”。
        if (weaponChecker.intervalElapsed()) {
            weaponPotentialHit.clear();

            // 注意：以下逻辑沿用原脚本结构（包括条件方向），保持行为一致。
            for (ShipAPI enemy : AIUtils.getNearbyEnemies(ship, ship.getCollisionRadius() + 10000f)) {
                for (WeaponAPI weapon : enemy.getAllWeapons()) {
                    if (weapon.isDecorative()) {
                        continue;
                    }
                    if (weapon.isDisabled()) {
                        continue;
                    }
                    float distanceSq = MathUtils.getDistanceSquared(ship.getLocation(), enemy.getLocation());
                    if (weapon.getRange() * weapon.getRange() * 1.21f < distanceSq) {
                        continue;
                    }
                    if (Math.abs(weapon.distanceFromArc(ship.getLocation())) > 0f) {
                        continue;
                    }
                    weaponPotentialHit.add(weapon);
                }
            }
        }

        // 高频统计：评估当前威胁总势能 potential，并与阈值 lim 比较。
        if (threatChecker.intervalElapsed()) {
            weightedDefensiveDir = new Vector2f();
            if (collisionDangerDir != null) {
                Vector2f.add(weightedDefensiveDir, collisionDangerDir, weightedDefensiveDir);
            }

            float potential = 0f;

            // 1) 弹丸威胁评估
            for (DamagingProjectileAPI proj : engine.getProjectiles()) {
                if (proj.getOwner() == ship.getOwner()) {
                    continue;
                }

                float distSq = MathUtils.getDistanceSquared(proj.getLocation(), ship.getLocation());
                float speed = proj.getMoveSpeed() == 0f ? 1f : proj.getMoveSpeed();
                if (distSq / speed > getSystemCooldown()) {
                    continue;
                }

                if (distSq < 2.5f * ship.getCollisionRadius() * ship.getCollisionRadius()) {
                    float fragMult = proj.getDamageType() == DamageType.FRAGMENTATION ? 0.25f : 1f;
                    potential += fragMult * proj.getDamage().getDamage();
                }

                Vector2f dir = VectorUtils.getDirectionalVector(ship.getLocation(), proj.getLocation());
                VectorUtils.rotate(dir, 70f + 20f * (float) Math.random() * randomSign, dir);
                dir.scale(proj.getDamageAmount() / 10f);
                Vector2f.add(weightedDefensiveDir, dir, weightedDefensiveDir);
            }

            // 2) 光束威胁评估
            for (BeamAPI beam : engine.getBeams()) {
                if (beam.getSource() != null && beam.getSource().getOwner() == ship.getOwner()) {
                    continue;
                }

                Vector2f nearest = MathUtils.getNearestPointOnLine(ship.getLocation(), beam.getFrom(), beam.getTo());
                float beamThreatRange = ship.getCollisionRadius() * 1.2f;
                if (MathUtils.getDistanceSquared(ship.getLocation(), nearest) < beamThreatRange * beamThreatRange) {
                    potential += beam.getDamage().getDamage() / 2f;
                }

                Vector2f dir = VectorUtils.getDirectionalVector(nearest, ship.getLocation());
                dir.scale(beam.getDamage().getDamage() / 10f);
                Vector2f.add(weightedDefensiveDir, dir, weightedDefensiveDir);
            }

            // 3) 潜在武器威胁评估（转向时间 + 武器伤害）
            for (WeaponAPI weapon : weaponPotentialHit) {
                float mult = weapon.isBeam() ? 0.5f : 0.1f;
                float turnRate = MathUtils.clamp(weapon.getTurnRate(), 1f, 100f);
                float targetAngle = VectorUtils.getAngle(weapon.getLocation(), ship.getLocation());
                float turnTime = Math.abs(MathUtils.getShortestRotation(weapon.getCurrAngle(), targetAngle)) / turnRate;

                potential += MathUtils.clamp(5f - turnTime, 0f, 5f) * weapon.getDamage().getDamage() * mult;

                Vector2f dir = VectorUtils.getDirectionalVector(ship.getLocation(), weapon.getLocation());
                VectorUtils.rotate(dir, 70f + 20f * (float) Math.random() * randomSign, dir);
                dir.scale(weapon.getDamage().getDamage() / 10f);
                Vector2f.add(weightedDefensiveDir, dir, weightedDefensiveDir);
            }

            // 4) 基础阈值：舰船越残，阈值越低（更容易触发深潜）。
            float lim = Math.min(700f, ship.getMaxHitpoints() * ship.getHullLevel());

            // 引擎熄火时提高保命倾向。
            if (ship.getEngineController().isFlamedOut()) {
                lim *= 0.1f;
            }

            float threshold = systemActive ? lim * ACTIVE_THREAT_THRESHOLD_MULT : lim;
            shouldDo = potential >= threshold;

            // 5) 武器状态触发：失能比例高时也可触发深潜（原逻辑保留）。
            int totalScale = 1;
            float cooldownScale = 1f; // 保留原变量（与原代码一致，当前不参与最终判断）
            float repairScale = 1f;
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (weapon.isDecorative()) {
                    continue;
                }
                totalScale += 2 * weapon.getSize().ordinal();
                if (weapon.getCooldownRemaining() > 2f) {
                    cooldownScale += 1.5f * weapon.getSize().ordinal();
                }
                if (weapon.isDisabled()) {
                    repairScale += 2f * weapon.getSize().ordinal();
                }
            }
            shouldDo = shouldDo || (repairScale / totalScale > 0.7f);

            // 如果本轮确认需要深潜，至少维持 0.5 秒意图。
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

        // 若 TDD 导弹正在尝试攻击，则优先给其让出退相位/开火窗口。
        if (tddAttackWindow > 0f) {
            shouldDo = false;
        }

        // 执行开关操作（PHASE_CLOAK 使用 TOGGLE 指令）。
        if (shouldDo) {
            if (!system.isOn()) {
                ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, ship.getMouseTarget(), 0);
                keep = Math.max(keep, MIN_PHASE_KEEP_ON_ACTIVATE);
            }
        } else {
            if (system.isOn()) {
                ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, ship.getMouseTarget(), 0);
            }
        }
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

    private void updateTddAttackWindow(boolean systemActive) {
        if (isAnyTddMissileAttackWindowMarked()) {
            tddAttackWindow = Math.max(tddAttackWindow, TDD_ATTACK_WINDOW);
            return;
        }

        if (!systemActive) {
            return;
        }

        if (!ALLOW_TDD_PREEMPTIVE_UNPHASE) {
            return;
        }

        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!isTddMissileWeapon(weapon)) {
                continue;
            }
            if (weapon.isDisabled() || weapon.getAmmo() <= 0 || weapon.getCooldownRemaining() > 0f) {
                continue;
            }
            if (hasTddLockedTargetInRange(weapon)) {
                tddAttackWindow = Math.max(tddAttackWindow, TDD_ATTACK_WINDOW);
                return;
            }
        }
    }

    private boolean isAnyTddMissileAttackWindowMarked() {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!isTddMissileWeapon(weapon)) {
                continue;
            }
            if (Moci_TDD_1_missileAnimEffect.isAttackWindowMarked(weapon)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTddMissileWeapon(WeaponAPI weapon) {
        if (weapon == null) {
            return false;
        }
        String id = weapon.getId();
        return "Moci_TDD_1_MISSILE_L".equals(id) || "Moci_TDD_1_MISSILE_R".equals(id);
    }

    private boolean hasTddLockedTargetInRange(WeaponAPI weapon) {
        ShipAPI target = findTddLockedTarget(weapon);
        if (weapon == null || target == null || weapon.getLocation() == null || target.getLocation() == null) {
            return false;
        }

        float edgeDistance = MathUtils.getDistance(weapon.getLocation(), target.getLocation()) - target.getCollisionRadius();
        return edgeDistance <= weapon.getRange();
    }

    private ShipAPI findTddLockedTarget(WeaponAPI weapon) {
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

        if (!isValidTddTarget(source, target)) {
            target = source.getShipTarget();
        }

        return isValidTddTarget(source, target) ? target : null;
    }

    private boolean isValidTddTarget(ShipAPI source, ShipAPI target) {
        return source != null
                && target != null
                && target.isAlive()
                && !target.isHulk()
                && target.getOwner() != source.getOwner();
    }
}
