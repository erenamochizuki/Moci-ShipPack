package data.shipsystems.scripts;

import java.awt.Color;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.impl.combat.MineStrikeStats;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 相位潜伏空雷系统。
 *
 * 这个类基于原版 MineStrikeStats：
 * 1. 仍然使用原版的目标点选择、射程限制、AI 选点和空雷生成方式。
 * 2. 生成的弹体仍然是原版 "minelayer2"，因此贴图来自：
 *    - starsector-core/data/weapons/minelayer2.wpn
 *    - starsector-core/data/weapons/proj/minelayer_mine_heavy.proj
 *    - graphics/missiles/heavy_mine3.png
 *    - graphics/missiles/heavy_mine3_glow.png
 * 3. 本脚本没有额外挂载新的相位贴图；相位感由透明度、抖动、颜色和碰撞/武装状态控制。
 *
 * 行为流程：
 * - 空雷传送到目标点后，先稳定存在 PRE_PHASE_DELAY 秒。
 * - 随后进入相位潜伏：中立、无碰撞、未武装、低透明、高 EMP 抗性、高生命锁定。
 * - 敌舰进入原版近炸触发距离后，空雷退相位并恢复原 owner/碰撞/武装状态。
 * - 退相位完成后交还给原版 PROXIMITY_FUSE 行为处理 ping、windup 和爆炸。
 */
public class Moci_PhaseMineStrikeStats extends MineStrikeStats {
    /** 生成后等待多久再潜入相位层。 */
    private static final float PRE_PHASE_DELAY = 2f;
    /** 触发退相位的距离；175f 对应原版 minelayer_mine_heavy.proj 的 proximity range。 */
    private static final float PHASE_BREAK_RANGE = 175f;
    /** 进入/退出相位的淡入淡出时间。 */
    private static final float PHASE_TRANSIT_TIME = 0.25f;
    /** 相位潜伏期间的空雷本体透明度。 */
    private static final float PHASE_ALPHA = 0.16f;
    /** 潜伏期间把生命值锁高，避免相位态空雷被普通伤害清掉。 */
    private static final float PHASE_HP_MULT = 100f;
    /** 潜伏期间的 EMP 抗性，基本等同免疫 EMP arc 熄火。 */
    private static final int PHASE_EMP_RESISTANCE = 10000;
    /** 100 是 Starsector 常用的中立 owner，避免潜伏中的空雷被敌我逻辑提前触发。 */
    private static final int NEUTRAL_OWNER = 100;

    /** 相位抖动颜色；不是贴图，只是 setJitter/fadeToOtherColor 用的颜色。 */
    private static final Color PHASE_JITTER_COLOR = new Color(170, 115, 255, 120);
    /** 进入/退出相位瞬间的短促闪光。 */
    private static final Color PHASE_FLASH_COLOR = new Color(210, 245, 255, 190);

    /**
     * 记录每颗空雷被脚本改写前的原始状态。
     *
     * 退相位完成后要恢复这些值，否则空雷会继续保持中立、无碰撞或未武装状态。
     */
    private static class PhaseMineData {
        private final int originalOwner;
        private final CollisionClass originalCollisionClass;
        private final int originalEmpResistance;
        private final float originalArmingTime;
        private final float originalHitpoints;

        /** 是否已经从初始稳定阶段进入相位潜伏阶段。 */
        private boolean phased = false;
        /** 是否已经侦测到敌舰并开始退相位。 */
        private boolean emerging = false;
        /** 从生成后开始累计的时间，用来判断何时进入相位。 */
        private float elapsed = 0f;
        /** 退相位动画累计时间。 */
        private float emergeElapsed = 0f;

        private PhaseMineData(MissileAPI mine) {
            this.originalOwner = mine.getOwner();
            this.originalCollisionClass = mine.getCollisionClass();
            this.originalEmpResistance = mine.getEmpResistance();
            this.originalArmingTime = mine.getArmingTime();
            this.originalHitpoints = mine.getHitpoints();
        }
    }

    @Override
    public void spawnMine(ShipAPI source, Vector2f mineLoc) {
        CombatEngineAPI engine = Global.getCombatEngine();

        // 以下选点逻辑基本复制原版 MineStrikeStats：
        // 在目标点周围找一个不与其他空雷过近的位置，避免多颗空雷完全重叠。
        Vector2f currLoc = Misc.getPointAtRadius(mineLoc, 30f + (float) Math.random() * 30f);
        float start = (float) Math.random() * 360f;

        for (float angle = start; angle < start + 390; angle += 30f) {
            if (angle != start) {
                Vector2f loc = Misc.getUnitVectorAtDegreeAngle(angle);
                loc.scale(50f + (float) Math.random() * 30f);
                currLoc = Vector2f.add(mineLoc, loc, new Vector2f());
            }
            for (MissileAPI other : engine.getMissiles()) {
                if (!other.isMine()) continue;

                float dist = Misc.getDistance(currLoc, other.getLocation());
                if (dist < other.getCollisionRadius() + 40f) {
                    currLoc = null;
                    break;
                }
            }
            if (currLoc != null) {
                break;
            }
        }
        if (currLoc == null) {
            currLoc = Misc.getPointAtRadius(mineLoc, 30f + (float) Math.random() * 30f);
        }

        // 仍然生成原版重型感应空雷。视觉贴图与爆炸行为都由 minelayer2/minelayer_mine_heavy 定义。
        MissileAPI mine = (MissileAPI) engine.spawnProjectile(source, null,
                "minelayer2",
                currLoc,
                (float) Math.random() * 360f,
                null);

        if (source != null) {
            // 保留原版逻辑：让母舰的导弹伤害加成应用到这颗由系统生成的空雷上。
            engine.applyDamageModifiersToSpawnedProjectileWithNullWeapon(
                    source, WeaponType.MISSILE, false, mine.getDamage());
        }

        // 传送出现时的原版淡入和抖动效果。
        float fadeInTime = 0.5f;
        mine.getVelocity().scale(0f);
        mine.fadeOutThenIn(fadeInTime);

        engine.addPlugin(createMissileJitterPlugin(mine, fadeInTime));
        // 新增的相位潜伏控制插件，后续每帧管理这颗空雷的潜伏/退相位流程。
        engine.addPlugin(createPhaseDormantMinePlugin(mine));

        Global.getSoundPlayer().playSound("mine_teleport", 1f, 1f, mine.getLocation(), mine.getVelocity());
    }

    /**
     * 为单颗空雷创建一个每帧插件。
     *
     * 这里不全局扫描战场空雷，而是在 spawnMine() 时精确绑定刚生成的那一颗，逻辑更干净也更省。
     */
    private EveryFrameCombatPlugin createPhaseDormantMinePlugin(final MissileAPI mine) {
        return new BaseEveryFrameCombatPlugin() {
            private final PhaseMineData data = new PhaseMineData(mine);

            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine == null || engine.isPaused()) return;

                if (!isMineValid(engine, mine)) {
                    engine.removePlugin(this);
                    return;
                }

                if (!data.phased) {
                    // 初始稳定阶段：空雷可见，但已经先被中立化和禁武装，避免刚生成就触发近炸倒计时。
                    data.elapsed += amount;
                    keepMineDormant(mine, data, 1f);
                    if (data.elapsed >= PRE_PHASE_DELAY) {
                        // 到达预备时间后进入低透明相位潜伏。
                        data.phased = true;
                        mine.fadeOutThenIn(PHASE_TRANSIT_TIME);
                        engine.addHitParticle(mine.getLocation(), new Vector2f(), 42f, 1f, 0.16f, PHASE_FLASH_COLOR);
                        Global.getSoundPlayer().playSound("system_phase_cloak_activate", 1f, 0.85f, mine.getLocation(), mine.getVelocity());
                    }
                    return;
                }

                if (!data.emerging) {
                    // 相位潜伏阶段：持续保持中立、无碰撞、未武装和低透明。
                    keepMineDormant(mine, data, PHASE_ALPHA);
                    mine.setJitter(this, PHASE_JITTER_COLOR, 0.55f, 8, 0f, 18f);

                    if (shouldBreakPhase(engine, mine, data.originalOwner)) {
                        // 敌舰进入引爆范围，开始退相位。此时仍暂时保持休眠，等淡入完成再恢复原版引信。
                        data.emerging = true;
                        data.emergeElapsed = 0f;
                        mine.fadeOutThenIn(PHASE_TRANSIT_TIME);
                        engine.addHitParticle(mine.getLocation(), new Vector2f(), 58f, 1f, 0.18f, PHASE_FLASH_COLOR);
                        Global.getSoundPlayer().playSound("system_phase_cloak_deactivate", 1f, 0.9f, mine.getLocation(), mine.getVelocity());
                    }
                    return;
                }

                // 退相位阶段：透明度从潜伏值逐渐回到 1，碰撞和武装仍等到最后一帧再恢复。
                data.emergeElapsed += amount;
                float progress = Math.min(1f, data.emergeElapsed / PHASE_TRANSIT_TIME);
                float alpha = PHASE_ALPHA + (1f - PHASE_ALPHA) * progress;
                keepMineDormant(mine, data, alpha);
                mine.setJitter(this, PHASE_JITTER_COLOR, 0.55f * (1f - progress), 8, 0f, 18f * (1f - progress));

                if (progress >= 1f) {
                    // 恢复后移除插件，后续完全交给原版 PHASE_MINE/PROXIMITY_FUSE 行为。
                    restoreMine(mine, data);
                    engine.removePlugin(this);
                }
            }
        };
    }

    /** 判断空雷是否还值得继续管理；爆炸、过期、熄火或离场后移除插件。 */
    private boolean isMineValid(CombatEngineAPI engine, MissileAPI mine) {
        return mine != null
                && engine.isEntityInPlay(mine)
                && !mine.didDamage()
                && !mine.isExpired()
                && !mine.isFizzling();
    }

    /**
     * 维持空雷的休眠/相位状态。
     *
     * 注意：这里没有替换贴图。alpha 会作用在原版 heavy_mine3.png/heavy_mine3_glow.png 的渲染上。
     */
    private void keepMineDormant(MissileAPI mine, PhaseMineData data, float alpha) {
        mine.setOwner(NEUTRAL_OWNER);
        mine.setCollisionClass(CollisionClass.NONE);
        mine.setArmingTime(9999f);
        mine.setForceAlwaysArmed(false);
        mine.setEmpResistance(PHASE_EMP_RESISTANCE);
        mine.setHitpoints(Math.max(mine.getHitpoints(), mine.getMaxHitpoints() * PHASE_HP_MULT));
        mine.getVelocity().scale(0f);
        mine.setSpriteAlphaOverride(Math.max(0f, Math.min(1f, alpha)) * mine.getCurrentBaseAlpha());

        if (mine.getEngineController() != null) {
            // 空雷本身几乎没有常规推进表现；这里主要是给可能存在的 glow/尾焰通道染一点相位色。
            mine.getEngineController().fadeToOtherColor(this, PHASE_JITTER_COLOR, PHASE_JITTER_COLOR, 1f, 1f);
            mine.getEngineController().extendFlame(this, -0.6f, -0.25f, -0.5f);
        }
    }

    /** 恢复原始状态，让空雷重新属于发射方，并允许原版近炸引信接管。 */
    private void restoreMine(MissileAPI mine, PhaseMineData data) {
        mine.setOwner(data.originalOwner);
        mine.setCollisionClass(data.originalCollisionClass);
        mine.setArmingTime(data.originalArmingTime);
        mine.setForceAlwaysArmed(true);
        mine.setEmpResistance(data.originalEmpResistance);
        mine.setHitpoints(Math.min(Math.max(data.originalHitpoints, mine.getMaxHitpoints()), mine.getMaxHitpoints()));
        mine.setSpriteAlphaOverride(-1f);
    }

    /**
     * 检测是否有敌舰进入退相位距离。
     *
     * 使用“空雷到敌舰外缘距离”，这样大舰不会因为中心点太远而无法触发。
     */
    private boolean shouldBreakPhase(CombatEngineAPI engine, MissileAPI mine, int originalOwner) {
        for (ShipAPI enemy : engine.getShips()) {
            if (enemy == null || !enemy.isAlive() || enemy.isHulk()) continue;
            if (enemy.getOwner() == originalOwner || enemy.getOwner() == NEUTRAL_OWNER) continue;

            float edgeDistance = Misc.getDistance(mine.getLocation(), enemy.getLocation()) - enemy.getCollisionRadius();
            if (edgeDistance <= PHASE_BREAK_RANGE) {
                return true;
            }
        }
        return false;
    }
}
