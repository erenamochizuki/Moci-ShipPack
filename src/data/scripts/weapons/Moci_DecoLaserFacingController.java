package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import data.shipsystems.scripts.Moci_Lidararray;
import org.lazywizard.lazylib.FastTrig;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class Moci_DecoLaserFacingController implements EveryFrameWeaponEffectPlugin {
    boolean init = false;
    WeaponAPI weapon = null;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        if (!init) {
            init = true;
            weapon.setSuspendAutomaticTurning(true);
            this.weapon = weapon;
            String key = weapon.getSlot().getLocation().x + "_" + weapon.getSlot().getLocation().y;
            Map<String, List<Moci_DecoLaserFacingController>> map = Moci_Lidararray.getDecoWeapons(weapon.getShip());
            if (!map.containsKey(key)) {
                map.put(key, new ArrayList<Moci_DecoLaserFacingController>());
            }
            map.get(key).add(this);
        }
    }

    /**
     * @param effectLevel   系统充能进度
     * @param defaultFacing 飞船默认方向
     * @param rotateArc     武器最大旋转角
     */
    public void run(float effectLevel, float defaultFacing, float rotateArc) {
        // ==== 激光雷达旋转 ====
        defaultFacing += weapon.getSlot().getAngle();
        defaultFacing = normalizeAngle(defaultFacing);
        float mult = (float) FastTrig.sin(Math.toRadians(effectLevel * 180f));
        float angle = mult * rotateArc;
        angle += defaultFacing;
        weapon.setCurrAngle(angle);

        // 根据充能进度选择区间
        if (effectLevel > 0) {
            //if (effectLevel >= 1) {
            //    // 完全充能时保持常亮
            //    weapon.setForceFireOneFrame(true);
            //} else {
            //    // 分段控制：越接近1时关闭激光的区间越密集
            //    boolean shouldTurnOff = false;
//
            //    // 根据充能进度选择区间
            //    if (effectLevel < 0.3) {
            //        // 0-0.3阶段：每0.2秒关闭一次（effectLevel每0.2区间关闭5%范围）
            //        shouldTurnOff = (effectLevel % 0.2) > 0.1;
            //    } else if (effectLevel < 0.6) {
            //        // 0.3-0.6阶段：每0.15秒关闭一次（缩小间隔）
            //        shouldTurnOff = (effectLevel % 0.15) > 0.075;
            //    } else if (effectLevel < 0.9) {
            //        // 0.6-0.9阶段：每0.1秒关闭一次（更快）
            //        shouldTurnOff = (effectLevel % 0.1) > 0.05;
            //    } else {
            //        // 0.9-1阶段：每0.05秒关闭一次（最快闪烁）
            //        shouldTurnOff = (effectLevel % 0.05) > 0.025;
            //    }
//
            //    // 设置激光状态
            //    if (shouldTurnOff) {
            //        weapon.setForceNoFireOneFrame(true);
            //    } else {
                    weapon.setForceFireOneFrame(true);
            //    }
            //}
        } else {
            // 系统未激活时完全停火
            weapon.setForceNoFireOneFrame(true);
        }
    }
//    public void run(float effectLevel, float defaultFacing, float rotateArc) {
//        defaultFacing += weapon.getSlot().getAngle();
//        defaultFacing = normalizeAngle(defaultFacing);
//        if (effectLevel > 0) {
//            weapon.setForceFireOneFrame(true);
//        } else {
//            weapon.setForceNoFireOneFrame(true);
//        }
//        //mult = 1 - mult;
//        //float mult = Math.abs(0.5f - effectLevel) * 2;  //和上面的加一起，线性
//        float mult = (float) FastTrig.sin(Math.toRadians(effectLevel * 180f)); //三角函数
//        //float mult = (float) Math.pow(FastTrig.sin(Math.toRadians(effectLevel * 180f)),2); //变量平方
//        //float mult = (float) FastTrig.sin(Math.toRadians(effectLevel*effectLevel*180f)); //三角函数平方
//        float angle = mult * rotateArc;
//        angle += defaultFacing;
//        weapon.setCurrAngle(angle);
//    }

    private static float normalizeAngle(float angle) {
        while (angle > 180) {
            angle -= 360;
        }
        while (angle < -180) {
            angle += 360;
        }
        return angle;
    }

    /**
     * FROM MagicLib
     *
     * @param x
     * @param start
     * @param end
     * @return
     */
    public static float smoothNormalizeRange(float x, float start, float end) {
        if (x <= start) {
            return 0;
        }
        if (x > end) {
            return 1;
        }
        return 0.5f - (float) (FastTrig.cos((x - start) * (1 / (end - start)) * MathUtils.FPI) / 2);
    }
}
