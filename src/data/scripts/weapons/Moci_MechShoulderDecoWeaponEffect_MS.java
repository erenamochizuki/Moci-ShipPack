package data.scripts.weapons;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * MS版机甲肩膀装饰武器效果
 * 继承基础类，增加翅膀角度控制功能
 */
public class Moci_MechShoulderDecoWeaponEffect_MS extends Moci_MechShoulderDecoWeaponEffect {
    
    // 翅膀控制相关变量
    private final float rotationSpeed = 9f;
    private final float maxtrunArc = 27f;
    private boolean isLeftWing = false;
    private boolean isRightWing = false;

    @Override
    protected void onInitialized(WeaponAPI weapon) {
        // 判断翅膀左右
        String slotId = weapon.getSlot().getId();
        if (slotId.endsWith("_L")) {
            isLeftWing = true;
        } else if (slotId.endsWith("_R")) {
            isRightWing = true;
        }
    }

    @Override
    protected void handleWingWeapon(WeaponAPI weapon, float amount) {
        // MS特有的翅膀角度控制
        if (isLeftWing || isRightWing) {
            ShipAPI ship = weapon.getShip();
            float shipFacing = ship.getFacing();

            // 计算当前武器相对于舰船的偏移角度
            float currentOffset = wing.getCurrAngle() - shipFacing;
            while (currentOffset > 180) currentOffset -= 360;
            while (currentOffset < -180) currentOffset += 360;

            // 根据运动状态计算目标偏移角度
            float targetOffset = 0f;
            if (ship.getEngineController().isDecelerating() ||
                    ship.getEngineController().isAcceleratingBackwards()) {
                targetOffset = isLeftWing ? -maxtrunArc : maxtrunArc;
            } else if (ship.getEngineController().isAccelerating()) {
                targetOffset = isLeftWing ? maxtrunArc : -maxtrunArc;
            }

            // 仅对目标偏移角度应用平滑过渡
            if (currentOffset != targetOffset) {
                if (currentOffset < targetOffset) {
                    currentOffset = Math.min(currentOffset + rotationSpeed * amount, targetOffset);
                } else {
                    currentOffset = Math.max(currentOffset - rotationSpeed * amount, targetOffset);
                }
            }

            // 计算最终角度(舰船朝向+偏移角度)
            float finalAngle = shipFacing + currentOffset;
            wing.setCurrAngle(finalAngle);
        } else {
            // 调用父类的默认翅膀处理逻辑
            super.handleWingWeapon(weapon, amount);
        }
    }
}
