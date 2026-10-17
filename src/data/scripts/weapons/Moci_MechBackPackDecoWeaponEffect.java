package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * RX-78-2背包装饰武器动画控制插件
 * 只根据舰船当前船插安装的武器情况设置动画帧
 * 0=两手都不是光剑，1=两手都是光剑，2=只有左手是光剑，3=只有右手是光剑
 */
public class Moci_MechBackPackDecoWeaponEffect implements EveryFrameWeaponEffectPlugin {
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        ShipAPI ship = weapon.getShip();
        if (ship == null) return;
        ShipVariantAPI variant = ship.getVariant();
        if (variant == null) return;

        // 获取左手和右手当前安装的武器ID
        String leftWeaponId = variant.getWeaponId("ARM_L_SOURCE");
        String rightWeaponId = variant.getWeaponId("ARM_R_SOURCE");

        boolean leftIsSabre = leftWeaponId != null && leftWeaponId.contains("Sabre");
        boolean rightIsSabre = rightWeaponId != null && rightWeaponId.contains("Sabre");

        int frame = 0;
        if (leftIsSabre && rightIsSabre) {
            frame = 1; // 两手都是光剑
        } else if (leftIsSabre) {
            frame = 2; // 只有左手是光剑
        } else if (rightIsSabre) {
            frame = 3; // 只有右手是光剑
        } else {
            frame = 0; // 两手都不是光剑
        }

        // 设置背包装饰武器的动画帧，并暂停动画，保证静止在该帧
        if (weapon.getAnimation() != null) {
            weapon.getAnimation().setFrame(frame);
            weapon.getAnimation().pause();
        }
    }
} 