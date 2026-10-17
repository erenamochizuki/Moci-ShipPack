package data.scripts.weapons;

import com.fs.starfarer.api.combat.*;
import org.lwjgl.util.vector.Vector2f;

public class Moci_MechWINGAnimationController implements EveryFrameWeaponEffectPlugin {

    private final float rotationSpeed = 6f;
    private final float maxtrunArc = 10f;
    private WingSide wingSide = WingSide.NONE; // 使用枚举表示机翼位置

    // 机翼位置枚举
    private enum WingSide {
        LEFT, RIGHT, NONE
    }

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 初始化判断机翼位置
        String slotId = weapon.getSlot().getId();
        if (slotId.endsWith("_L")) {
            wingSide = WingSide.LEFT;
        } else if (slotId.endsWith("_R")) {
            wingSide = WingSide.RIGHT;
        } else {
            wingSide = WingSide.NONE;
            return; // 非机翼武器不处理
        }

        // 获取舰船当前朝向
        float shipFacing = ship.getFacing();

        // 计算当前武器相对于舰船的偏移角度
        float currentOffset = weapon.getCurrAngle() - shipFacing;
        while (currentOffset > 180) currentOffset -= 360;
        while (currentOffset < -180) currentOffset += 360;

        // 根据运动状态计算目标偏移角度
        float targetOffset = 0f;
        if (ship.getEngineController().isDecelerating() ||
                ship.getEngineController().isAcceleratingBackwards()) {
            targetOffset = wingSide == WingSide.LEFT ? -maxtrunArc : maxtrunArc;
        } else if (ship.getEngineController().isAccelerating()) {
            targetOffset = wingSide == WingSide.LEFT ? maxtrunArc : -maxtrunArc;
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
        weapon.setCurrAngle(finalAngle);
    }
}