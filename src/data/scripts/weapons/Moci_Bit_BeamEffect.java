package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import java.util.Random;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

public class Moci_Bit_BeamEffect implements BeamEffectPlugin {
    // 电弧触发间隔控制 (0.2-0.3秒随机间隔)
    private IntervalUtil fireInterval = new IntervalUtil(0.2f, 0.3f);
    // 视觉效果电弧间隔控制 (0.2-0.25秒随机间隔)
    private IntervalUtil arcIntervalUtil = new IntervalUtil(0.2f, 0.25f);
    // 标记上一帧是否没有造成伤害
    private boolean wasZero = true;

    // 护盾穿透系数 (15%的基础穿透率)
    private float ignore = 0.15f;

    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused()) return;

        CombatEntityAPI target = beam.getDamageTarget();

        // 处理对飞船目标的伤害
        if (target instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) target;

            // 护盾穿透机制
            boolean hitShield = ship.getShield() != null && ship.getShield().isWithinArc(beam.getTo());
            if (hitShield) {
                float shieldEfficiency = Math.min(1,
                        ship.getShield().getFluxPerPointOfDamage()
                                * ship.getMutableStats().getShieldDamageTakenMult().getModifiedValue()
                                * ship.getMutableStats().getShieldAbsorptionMult().getModifiedValue());
                float reduction = beam.getDamage().computeDamageDealt(amount) * (1 - shieldEfficiency);
                float pierce = reduction * ignore;
                ship.getFluxTracker().increaseFlux(pierce, true);
            }

            // 当光束亮度足够高时
            if (beam.getBrightness() >= 0.8f) {
                float dur = beam.getDamage().getDpsDuration();
                // 重置持续时间计数 (处理游戏时间加速的情况)
                if (!wasZero) dur = 0;
                wasZero = beam.getDamage().getDpsDuration() <= 0;

                // 更新电弧触发计时器
                fireInterval.advance(dur);

                // 当达到触发间隔时
                if (fireInterval.intervalElapsed()) {
                    // 计算护盾穿透概率 (基于目标硬通量水平)
                    float pierceChance = ship.getHardFluxLevel() - 0.1f;
                    pierceChance *= ship.getMutableStats().getDynamic().getValue(Stats.SHIELD_PIERCED_MULT);

                    boolean piercedShield = hitShield && (float) Math.random() < pierceChance;

                    // 如果未命中护盾或成功穿透护盾
                    if (!hitShield || piercedShield) {
                        Vector2f point = beam.getRayEndPrevFrame();
                        // 计算EMP伤害 (基础值的50%)
                        float emp = beam.getDamage().getFluxComponent() * 0.5f;
                        // 计算实际伤害 (基础值的25%)
                        float dam = beam.getDamage().getDamage() * 0.25f;

                        // 生成EMP电弧效果
                        engine.spawnEmpArc(
                                beam.getSource(),  // 电弧来源
                                point,             // 起始点
                                beam.getDamageTarget(), // 目标
                                beam.getDamageTarget(), // 电弧目标
                                DamageType.ENERGY, // 伤害类型
                                dam,               // 伤害值
                                emp,               // EMP值
                                100000f,           // 最大射程 (确保能命中)
                                "tachyon_lance_emp_impact", // 视觉效果ID
                                beam.getWidth() + 5f, // 电弧宽度 (比光束宽5单位)
                                beam.getFringeColor(), // 边缘颜色
                                beam.getCoreColor()    // 核心颜色
                        );
                    }
                }
            }
        }

        // 当光束亮度较高时生成视觉效果电弧
        if(beam.getBrightness() > 0.5f){
            arcIntervalUtil.advance(amount);
            if(arcIntervalUtil.intervalElapsed())
                createVisualArc(beam, beam.getWidth());
        }
    }

    // 创建视觉效果电弧
    public void createVisualArc(BeamAPI beam, float r) {
        float dist = Misc.getDistance(beam.getFrom(), beam.getRayEndPrevFrame());
        Vector2f direction = VectorUtils.getDirectionalVector(beam.getFrom(), beam.getRayEndPrevFrame());
        float gap = 100f;
        float gap_random = 50f;
        float next = gap + (1 - 0.5f) * gap_random;
        float currentDist = 0;
        Vector2f last = new Vector2f(beam.getFrom());

        while ((dist - currentDist) > next) {
            currentDist += next;
            Vector2f n = new Vector2f(direction);
            n.scale(currentDist);
            n = Vector2f.add(beam.getFrom(), n, n);
            n = Misc.getPointWithinRadiusUniform(n, r, r * 1.5f, new Random());

            EmpArcEntityAPI arc = Global.getCombatEngine().spawnEmpArcVisual(
                    last, beam.getSource(), n, beam.getSource(),
                    beam.getWidth() / 3f + beam.getWidth() / 8f * (float) Math.random(),
                    beam.getFringeColor(), beam.getCoreColor());
            arc.setSingleFlickerMode();
            last = new Vector2f(n);
            next = gap + (1 - 0.5f) * gap_random;
        }

        EmpArcEntityAPI arc = Global.getCombatEngine().spawnEmpArcVisual(
                last, beam.getSource(), beam.getRayEndPrevFrame(), beam.getSource(),
                beam.getWidth() / 3f + beam.getWidth() / 8f * (float) Math.random(),
                beam.getFringeColor(), beam.getCoreColor());
        arc.setSingleFlickerMode();
    }
}