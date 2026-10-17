package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 背包装饰武器动画控制插件 - 仅右手版本
 * 只根据右手武器情况设置动画帧
 * 0=右手是光剑，1=右手不是光剑，2=右手是火箭筒
 */
public class Moci_MechBackPackDecoWeaponEffect_RightHandOnly implements EveryFrameWeaponEffectPlugin {
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        ShipAPI ship = weapon.getShip();
        if (ship == null) return;
        ShipVariantAPI variant = ship.getVariant();
        if (variant == null) return;

        // 获取右手当前安装的武器ID
        String rightWeaponId = variant.getWeaponId("ARM_R_SOURCE");

        boolean rightIsSabre = rightWeaponId != null && rightWeaponId.contains("Sabre");
        boolean rightIsBazooka = rightWeaponId != null && rightWeaponId.endsWith("bazooka");

        // 右手是光剑为第0帧，火箭筒为第2帧，其他为第1帧
        int frame;
        if (rightIsSabre) {
            frame = 0;
        } else if (rightIsBazooka) {
            frame = 2;
        } else {
            frame = 1;
        }

        // 设置背包装饰武器的动画帧，并暂停动画，保证静止在该帧
        if (weapon.getAnimation() != null) {
            // 安全性检测：确保帧数不超过动画的总帧数
            int numFrames = weapon.getAnimation().getNumFrames();
            if (frame >= numFrames) {
                // 如果目标帧超出范围，使用最后一帧
                frame = Math.max(0, numFrames - 1);
            }
            
            weapon.getAnimation().setFrame(frame);
            weapon.getAnimation().pause();
        }
    }
}
