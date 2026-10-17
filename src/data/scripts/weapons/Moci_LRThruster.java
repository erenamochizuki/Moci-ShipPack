package data.scripts.weapons;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.Misc;

public class Moci_LRThruster implements EveryFrameWeaponEffectPlugin {
    private float angularVel = 0; // 存储飞船的角速度
    private boolean noEngine = false; // 标记是否找到对应的引擎
    private float enginenowLenght = 0f; // 当前引擎火焰长度
    ShipEngineControllerAPI.ShipEngineAPI CombinedEngine = null; // 关联的引擎对象

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused()) {
            return;
        }
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }
        if (CombinedEngine == null && !noEngine) {
            // 遍历飞船的所有引擎
            for (ShipEngineControllerAPI.ShipEngineAPI eng : weapon.getShip().getEngineController().getShipEngines()) {
                // 如果引擎位置与武器位置距离小于1
                if (Misc.getDistanceSq(weapon.getLocation(), eng.getLocation()) < 1f) {
                    CombinedEngine = eng; // 关联该引擎
                    // 在飞船的自定义数据中存储引擎信息
                    ship.setCustomData(weapon.getSlot().getId().substring(0, 2) + "CTBEngine", this);
                    enginenowLenght = 0.4f;
                    break;
                }
            }
            // 如果未找到合适的引擎，设置标记
            if (CombinedEngine == null) {
                noEngine = true;
            }
            // 记录当前飞船的角速度
            angularVel = ship.getAngularVelocity();
        }
        // 如果没有找到引擎，直接返回
        if (CombinedEngine == null) {
            return;
        }
        // 如果引擎被禁用，直接返回
        if (CombinedEngine.isDisabled()) {
            return;
        }
        // 获取飞船的引擎控制器
        ShipEngineControllerAPI Controller = ship.getEngineController();
        float needLength = 0.4f; // 目标火焰长度
        boolean stopTurnLeft = false; // 标记是否停止左转
        boolean stopTurnRight = false; // 标记是否停止右转

        // 判断飞船是否在减速转向
        if (angularVel >= 0) {
            if (ship.getAngularVelocity() < angularVel) {
                stopTurnLeft = true;
            }
        } else {
            if (ship.getAngularVelocity() > angularVel) {
                stopTurnRight = true;
            }
        }
        // 更新当前角速度
        angularVel = ship.getAngularVelocity();
        if (ship.getTravelDrive().isActive()) {
            needLength = 0;
        } else {
            // 根据武器标签和飞船动作调整火焰长度
            if (weapon.getSpec().hasTag("moci_engine_TurnLeft")) {
                if (Controller.isTurningLeft() || stopTurnRight) {
                    needLength += 0.6f;
                }
                if (Controller.isTurningRight() || stopTurnLeft) {
                    needLength -= 0.2f;
                }
            }
            if (weapon.getSpec().hasTag("moci_engine_TurnRight")) {
                if (Controller.isTurningLeft() || stopTurnRight) {
                    needLength -= 0.2f;
                }
                if (Controller.isTurningRight() || stopTurnLeft) {
                    needLength += 0.6f;
                }
            }
            if (weapon.getSpec().hasTag("moci_engine_Acc")) {
                if (Controller.isAccelerating()) {
                    needLength += 0.6f;
                }
                if (Controller.isDecelerating() || Controller.isAcceleratingBackwards()) {
                    needLength -= 0.2f;
                }
            }
            if (weapon.getSpec().hasTag("moci_engine_Dcc")) {
                if (Controller.isAccelerating()) {
                    needLength -= 0.2f;
                }
                if (Controller.isDecelerating() || Controller.isAcceleratingBackwards()) {
                    needLength += 0.6f;
                }
            }
            if (weapon.getSpec().hasTag("moci_engine_StrafingLeft")) {
                if (Controller.isStrafingRight()) {
                    needLength -= 0.2f;
                }
                if (Controller.isStrafingLeft()) {
                    needLength += 0.6f;
                }
            }
            if (weapon.getSpec().hasTag("moci_engine_StrafingRight")) {
                if (Controller.isStrafingRight()) {
                    needLength += 0.6f;
                }
                if (Controller.isStrafingLeft()) {
                    needLength -= 0.2f;
                }
            }
            needLength = Math.max(0.2f, Math.min(1, needLength));
        }
        if (Math.abs(enginenowLenght - needLength) < amount * 2) {
            enginenowLenght = needLength;
        } else {
            if (needLength - enginenowLenght > 0) {
                enginenowLenght += amount;
            } else {
                enginenowLenght -= amount;
            }
        }
        ship.getEngineController().setFlameLevel(CombinedEngine.getEngineSlot(), enginenowLenght);
    }

}
