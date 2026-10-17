package data.scripts.weapons;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;

import data.scripts.util.Moci_Util;

public class Moci_MechProjDecoWeaponEffect_shield_arm_old implements EveryFrameWeaponEffectPlugin {
    ShipAPI ship;
    boolean init = false;
    boolean shieldisOff = false;
    private ShipAPI shield = null;
    private final float rotationSpeed = 35f; // 转向速度
    private final float defaultOffset = 0f; // 默认角度

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        // 初始化
        if (!init) {
            init = true;
            ship = weapon.getShip();
            String slotId = weapon.getSlot().getId();
            String shieldId = "SHIELD_" + slotId.substring(slotId.lastIndexOf("_") + 1);

            // 查找对应的盾牌
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() != null && module.getStationSlot().getId().equals(shieldId)) {
                    shield = module;
                    break;
                }
            }
        }

        shieldisOff = !ship.getShield().isOn();
        // 控制动画
        if (weapon.getAnimation() != null) {
            if (shield != null && shield.isAlive()) {
                weapon.getAnimation().setFrame(1);
            } else {
                weapon.getAnimation().setFrame(0);
            }
        }

        // 计算目标角度
        float targetOffset = defaultOffset;
        CombatEntityAPI target = findNearestThreat();
        if (target != null) {
            float targetAngle = VectorUtils.getAngle(ship.getLocation(), target.getLocation());
            float relativeAngle = MathUtils.getShortestRotation(ship.getFacing(), targetAngle);
            targetOffset = relativeAngle - 50f;
        }

        // 平滑转向
        float currentOffset = weapon.getCurrAngle() - ship.getFacing();
        while (currentOffset > 180) currentOffset -= 360;
        while (currentOffset < -180) currentOffset += 360;

        if (currentOffset != targetOffset) {
            if (currentOffset < targetOffset) {
                currentOffset = Math.min(currentOffset + rotationSpeed * amount, targetOffset);
            } else {
                currentOffset = Math.max(currentOffset - rotationSpeed * amount, targetOffset);
            }
        }

        // 设置最终角度
        float finalAngle = ship.getFacing() + currentOffset;
        weapon.setCurrAngle(finalAngle);
    }

    // 查找最近的威胁目标
    private CombatEntityAPI findNearestThreat() {
        // 如果护盾开启，则不查找目标
        if (ship.getShield().isOn()) {
            return null;
        }

        CombatEntityAPI nearestThreat = null;
        float nearestDistance = Float.MAX_VALUE;

        // 检查敌方舰船
        for (ShipAPI enemy : Global.getCombatEngine().getShips()) {
            if (enemy.isAlive() && enemy.getOwner() != ship.getOwner()) {
                float distance = MathUtils.getDistance(ship, enemy);
                if (distance < 600f && distance < nearestDistance) {
                    nearestThreat = enemy;
                    nearestDistance = distance;
                }
            }
        }

        // 使用工具类获取范围内的敌方弹幕和导弹
        List<DamagingProjectileAPI> threats = Moci_Util.getEnemyProjectilesAndMissilesWithinRange(
            ship.getLocation(), 400f, ship.getOwner());
        
        for (DamagingProjectileAPI threat : threats) {
            float distance = MathUtils.getDistance(ship, threat);
            if (distance < nearestDistance) {
                nearestThreat = threat;
                nearestDistance = distance;
            }
        }

        return nearestThreat;
    }
}