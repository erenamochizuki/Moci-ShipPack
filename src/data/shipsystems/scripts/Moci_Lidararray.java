package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import data.scripts.weapons.Moci_DecoLaserFacingController;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.*;
import java.util.List;

public class Moci_Lidararray extends BaseShipSystemScript {
    private static final Set<Vector2f> ENGINE_LOC = new HashSet<>();
    private final Set<ShipEngineControllerAPI.ShipEngineAPI> ENGINES = new HashSet<>();

    static {
        // 将引擎坐标写入
        ENGINE_LOC.add(new Vector2f(31, 126));
        ENGINE_LOC.add(new Vector2f(28, 123));
        ENGINE_LOC.add(new Vector2f(26, 120));
        ENGINE_LOC.add(new Vector2f(-34, 127));
        ENGINE_LOC.add(new Vector2f(31, -126));
        ENGINE_LOC.add(new Vector2f(28, -123));
        ENGINE_LOC.add(new Vector2f(26, -120));
        ENGINE_LOC.add(new Vector2f(-34, -127));
    }

    private static final float LASER_MAX_ARC_HALF = 2f; // 光束左右各偏转度数
    private static final int ADD_AMMO = 3; // 每次激活加3弹药
    private static final String MAIN_GUN_SLOT = "MAINGUN_";
    private static final String SMOKE_SLOT = "SMOKE_";
    private static final float SMOKE_SPEED = 80; // 烟雾粒子速度
    private static final float SMOKE_SIZE = 20; // 烟雾粒子大小
    private static final float SMOKE_SIZE_MULT = 2.5f; // 烟雾消散前的大小倍率
    private static final float SMOKE_DURATION = 0.8f;// 烟雾持续时间
    private static final Color SMOKE_COLOR = new Color(255, 255, 255, 202); // 烟雾颜色
    // 引擎特效
    private static final float ENGINE_GLOW_LENGTH_MULT = 1f; // 引擎长度
    private static final float ENGINE_GLOW_WIDTH_MULT = 1f; // 引擎宽度
    private static final float ENGINE_GLOW_GLOW_MULT = 1.5f; // 引擎光效
    // BUFF相关
    private static final float FULL_FLUX_BUFF_DURATION = 25; // 满幅能V排buff持续时间
    private static final float VENTING_SPEED_INCREASE = 50; // 速度buff
    private static final float VENTING_ACC_INCREASE = 20; // 加速度buff
    private static final float VENTING_DEC_INCREASE = 20; // 减速度buff
    private static final float VENTING_TURN_RATE_INCREASE = 20; // 转向buff
    private static final float VENTING_TURN_ACC_INCREASE = 20; // 转向加速度buff
    private static final float VENTING_DAMAGE_TAKEN_MULT = 0.5f; // 受伤倍率
    private static final float REPAIR_RATE_MULT = 10f; // 维修速度
    private static final Color VENTING_JITTER_COLOR = new Color(255, 255, 255, 45);// 抖动颜色
    private static final Color VENTING_JITTER_UNDER_COLOR = new Color(100, 165, 255, 155);// 抖动颜色2
    IntervalUtil smokeInterval = new IntervalUtil(0.1f, 0.1f); // 烟雾间隔0.1
    boolean init = false;
    Set<WeaponAPI> mainGuns = new HashSet<>();
    boolean addedAmmo = true; // 默认true可以让飞船刚部署的时候执行的unapply去消除主炮的弹药
    boolean shouldVent = false; // 默认true可以让飞船刚部署的时候执行的unapply去消除主炮的弹药
    // ===== 状态提示配置 =====
    private static final Object Moci_STATUS_KEY = new Object(); // 唯一状态键
    private static final String STATUS_ICON = "graphics/icons/hullsys/damper_field.png"; // 沿用DamperField图标
    private static final String STATUS_NAME = "幅能装甲";
    private static final String STATUS_DESC_FORMAT = "最高速度+ %s | 机动性提高 | 减伤 %s | 剩余: %s ";

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        if (stats.getEntity() instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) stats.getEntity();
            if (!init) {
                init(ship);
            }
            if (state == State.ACTIVE) {
                if (!addedAmmo) {
                    addedAmmo = true;
                    for (WeaponAPI weapon : mainGuns) {
                        if (weapon.getMaxAmmo() != Integer.MAX_VALUE) {
                            weapon.setAmmo(ADD_AMMO);
                            shouldVent = true;
                        }
                    }
                }
                boolean outOfAmmo = true;
                for (WeaponAPI weapon : mainGuns) {
                    if (weapon.getChargeLevel() > 0 || weapon.getAmmo() > 0) {
                        outOfAmmo = false;
                        // weapon.setForceFireOneFrame(true); //强制开火时注释掉下一行，否则注释掉这一行
                        break;
                    }
                }
                if (outOfAmmo) {
                    // ship.getSystem().forceState(ShipSystemAPI.SystemState.OUT, 0);

                    if (shouldVent) {
                        ship.getFluxTracker().ventFlux(); // 在弹药为0的时候也进行强制排幅
                        shouldVent = false; // 重置排幅标志
                    }
                }
            } else if (state == State.OUT) {
                addedAmmo = false;
                for (WeaponAPI weapon : mainGuns) {
                    if (weapon.getMaxAmmo() != Integer.MAX_VALUE) {
                        weapon.setAmmo(0);
                    }
                }
                CombatEngineAPI engine = Global.getCombatEngine();
                smokeInterval.advance(engine.getElapsedInLastFrame());
                if (smokeInterval.intervalElapsed()) {
                    for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
                        if (slot.getId().startsWith(SMOKE_SLOT)) {
                            Vector2f loc = OffSetToLocation(ship, slot.getLocation());
                            float angle = Misc.normalizeAngle(slot.getAngle() + ship.getFacing());
                            Vector2f vel = VectorUtils.getDirectionalVector(loc,
                                    MathUtils.getPointOnCircumference(loc, 10f, angle));
                            vel.scale(SMOKE_SPEED);
                            Vector2f.add(ship.getVelocity(), vel, vel);
                            engine.addNebulaSmokeParticle(loc, vel, SMOKE_SIZE, SMOKE_SIZE_MULT, 0.1f, 0.1f,
                                    SMOKE_DURATION, SMOKE_COLOR);
                        }
                    }
                }
            }
        } else {
            return;
        }
        if (state == State.OUT) {
            advance(stats, 1);
        } else {
            advance(stats, effectLevel);
        }

    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        advance(stats, 0);
        if (addedAmmo) {
            addedAmmo = false;
        }
        if (stats.getEntity() instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) stats.getEntity();
            if (!init) {
                init(ship);
            }
            for (WeaponAPI weapon : mainGuns) {
                if (weapon.getMaxAmmo() != Integer.MAX_VALUE) {
                    weapon.setAmmo(0);
                }
            }
            if (shouldVent) {
                ship.getFluxTracker().ventFlux(); // 战术系统停止时强制排幅
                shouldVent = false; // 重置排幅标志
            }
        }

        stats.getBeamPDWeaponRangeBonus().unmodify("Moci_Lidarrage_Lidar_Range"); // 还原对于激光雷达的射程修改

    }

    public static Vector2f OffSetToLocation(CombatEntityAPI entity, Vector2f offset) {
        Vector2f loc = new Vector2f(offset);
        VectorUtils.rotate(loc, entity.getFacing(), loc);
        Vector2f.add(loc, entity.getLocation(), loc);
        return loc;
    }

    private void init(ShipAPI ship) {
        init = true;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot().getId().startsWith(MAIN_GUN_SLOT)) {
                mainGuns.add(weapon);
            }
        }
        if (!ship.hasListenerOfClass(Moci_Lidararray.ventBuff.class)) {
            ship.addListener(new ventBuff(ship));
        }
        for (ShipEngineControllerAPI.ShipEngineAPI eng : ship.getEngineController().getShipEngines()) {
            for (Vector2f offset : ENGINE_LOC) {
                Vector2f loc = OffSetToLocation(ship, offset);
                if (Misc.getDistanceSq(loc, eng.getLocation()) < 1f) {
                    ENGINES.add(eng);
                }
            }
        }
    }

    /**
     * 控制激光的部分
     *
     * @param stats
     * @param effectLevel
     */
    private void advance(MutableShipStatsAPI stats, float effectLevel) {
        if (stats.getEntity() instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) stats.getEntity();

            // ==== 获取主武器最大射程 ====
            float maxRange = 0f;
            for (WeaponAPI weapon : mainGuns) {
                // 获取所有MAIN_GUN_SLOT武器的最大射程
                if (weapon.getSlot().getId().startsWith(MAIN_GUN_SLOT)) {
                    maxRange = Math.max(maxRange, weapon.getRange());
                }
            }

            // ==== 同步装饰武器射程（通过PD标签） ====
            stats.getBeamPDWeaponRangeBonus().modifyFlat("Moci_Lidarrage_Lidar_Range", maxRange - 1000f);

            // ==== 控制激光偏转 ====
            Map<String, List<Moci_DecoLaserFacingController>> map = getDecoWeapons(ship);
            for (String key : map.keySet()) {
                int i = 1;
                for (Moci_DecoLaserFacingController weapon : map.get(key)) {
                    weapon.run(effectLevel, ship.getFacing(), LASER_MAX_ARC_HALF * i);
                    i *= -1;
                }
            }
        }
    }

    // Map<String, List<Moci_DecoLaserFacingController>> map = getDecoWeapons(ship);
    // for (String key : map.keySet()) {
    // int i = 1;
    // for (Moci_DecoLaserFacingController weapon : map.get(key)) {
    // weapon.run(effectLevel, ship.getFacing(), LASER_MAX_ARC_HALF * i);
    // i *= -1;
    // }
    // }
    // }

    // }

    public static Map<String, List<Moci_DecoLaserFacingController>> getDecoWeapons(ShipAPI ship) {
        if (ship.getCustomData().containsKey("Moci_DragonlikeLaserWeapon")) {
            return (Map<String, List<Moci_DecoLaserFacingController>>) ship.getCustomData()
                    .get("Moci_DragonlikeLaserWeapon");
        } else {
            Map<String, List<Moci_DecoLaserFacingController>> map = new HashMap<>();
            ship.setCustomData("Moci_DragonlikeLaserWeapon", map);
            return map;
        }
    }

    private static final String buffId = "Moci_Lidararray_ventBuff";

    private class ventBuff implements AdvanceableListener {
        final ShipAPI ship;
        boolean venting = false;
        float duration = 0;

        private ventBuff(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public void advance(float amount) {
            CombatEngineAPI engine = Global.getCombatEngine();
            // ===== 防null检查 =====
            if (engine == null || engine.isPaused())
                return;
            if (ship == null || !ship.isAlive() || ship.isHulk())
                return; // 检查舰船是否有效

            // ===== 状态提示逻辑 =====
            if (duration > 0) {
                // 检查玩家舰船是否存在
                ShipAPI playerShip = engine.getPlayerShip();
                if (playerShip == null)
                    return;

                if (ship != playerShip)
                    return;

                if (STATUS_ICON == null || STATUS_NAME == null || STATUS_DESC_FORMAT == null) {
                    return;
                }

                // 计算减伤比例
                float damageReduction = (1f - VENTING_DAMAGE_TAKEN_MULT) * 100f;

                String desc;
                try {
                    desc = String.format(
                            STATUS_DESC_FORMAT,
                            (int) VENTING_SPEED_INCREASE,
                            (int) damageReduction + "%",
                            (int) duration);
                } catch (Exception e) {
                    desc = "BUFF激活中";
                }

                // 最终状态更新
                engine.maintainStatusForPlayerShip(
                        Moci_STATUS_KEY,
                        STATUS_ICON,
                        STATUS_NAME,
                        desc,
                        false);
            }
            // Buff计算逻辑
            if (!venting) {
                if (ship.getFluxTracker().isVenting()) {
                    venting = true;
                    addBuff(ship.getFluxLevel() * FULL_FLUX_BUFF_DURATION);
                }
            } else {
                if (!ship.getFluxTracker().isVenting()) {
                    venting = false;
                }
            }
            if (duration > 0) {
                duration -= amount;
                // 和荧光纹身效果关联
                ship.setCustomData("LIDAR_BUFF_DURATION", duration);
                // 抖动光效
                ship.setJitterUnder(ship, VENTING_JITTER_UNDER_COLOR, 1, 25, 0, 9); // 抖动底色
                ship.setJitter(ship, VENTING_JITTER_COLOR, 1, 2, 0, 5); // 抖动覆盖色
                // 设置引擎光效
                ship.getEngineController().extendFlame(
                        this,
                        ENGINE_GLOW_LENGTH_MULT,
                        ENGINE_GLOW_WIDTH_MULT,
                        ENGINE_GLOW_GLOW_MULT);
                if (duration <= 0) {
                    removeBuff();
                }
            } else {
                for (ShipEngineControllerAPI.ShipEngineAPI eng : ENGINES) {
                    ship.getEngineController().setFlameLevel(eng.getEngineSlot(), 0);
                }
            }
        }

        void addBuff(float duration) {
            this.duration = duration;
            // 关联荧光纹身
            ship.setCustomData("LIDAR_BUFF_DURATION", duration);
            // BUFF效果
            MutableShipStatsAPI stats = ship.getMutableStats();
            stats.getMaxSpeed().modifyFlat(buffId, VENTING_SPEED_INCREASE);
            stats.getAcceleration().modifyFlat(buffId, VENTING_ACC_INCREASE);
            stats.getDeceleration().modifyFlat(buffId, VENTING_DEC_INCREASE);
            stats.getTurnAcceleration().modifyFlat(buffId, VENTING_TURN_ACC_INCREASE);
            stats.getMaxTurnRate().modifyFlat(buffId, VENTING_TURN_RATE_INCREASE);
            stats.getHullDamageTakenMult().modifyMult(buffId, VENTING_DAMAGE_TAKEN_MULT);
            stats.getArmorDamageTakenMult().modifyMult(buffId, VENTING_DAMAGE_TAKEN_MULT);
            stats.getEmpDamageTakenMult().modifyMult(buffId, VENTING_DAMAGE_TAKEN_MULT);
            stats.getCombatEngineRepairTimeMult().modifyMult(buffId, 1f / (1f + (REPAIR_RATE_MULT - 1f)));
            stats.getCombatWeaponRepairTimeMult().modifyMult(buffId, 1f / (1f + (REPAIR_RATE_MULT - 1f)));
        }

        void removeBuff() {
            // 移除荧光纹身效果
            ship.getCustomData().remove("LIDAR_BUFF_DURATION");
            MutableShipStatsAPI stats = ship.getMutableStats();
            stats.getMaxSpeed().unmodify(buffId);
            stats.getAcceleration().unmodify(buffId);
            stats.getDeceleration().unmodify(buffId);
            stats.getTurnAcceleration().unmodify(buffId);
            stats.getMaxTurnRate().unmodify(buffId);
            stats.getHullDamageTakenMult().unmodify(buffId);
            stats.getArmorDamageTakenMult().unmodify(buffId);
            stats.getEmpDamageTakenMult().unmodify(buffId);
            stats.getCombatEngineRepairTimeMult().unmodify(buffId);
            stats.getCombatWeaponRepairTimeMult().unmodify(buffId);
        }
    }
}
