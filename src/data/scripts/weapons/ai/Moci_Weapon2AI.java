package data.scripts.weapons.ai;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.Misc;

public class Moci_Weapon2AI implements AutofireAIPlugin {
    private static final float TRIGGER_RANGE = 500f; // 触发打桩的距离
    private static final float ARC = 10f; // 前方角度范围
    private final WeaponAPI weapon;
    private final ShipAPI ship;
    private boolean shouldFire = false;
    private ShipAPI targetShip = null;
    private Vector2f target = null;

    public Moci_Weapon2AI(WeaponAPI weapon) {
        this.weapon = weapon;
        this.ship = weapon.getShip();
    }

    @Override
    public void advance(float amount) {
        shouldFire = false;
        if (ship == null || !ship.isAlive() || ship.isHulk() || weapon == null) return;
        // 只在AI控制时生效，玩家驾驶时不工作
        if (ship.getShipAI() == null) return;
        // 查找最近敌舰
        ShipAPI nearest = Misc.findClosestShipEnemyOf(ship, ship.getLocation(), ShipAPI.HullSize.FRIGATE, 1200f, true);
        if (nearest == null || !nearest.isAlive()) return;
        // 预测目标位置（简单线性预测）
        Vector2f toTarget = Vector2f.sub(nearest.getLocation(), ship.getLocation(), null);
        float distance = toTarget.length();
        float speed = ship.getMaxSpeed();
        Vector2f predicted = new Vector2f(nearest.getLocation());
        if (speed > 0) {
            float timeToReach = distance / speed;
            predicted.x += nearest.getVelocity().x * timeToReach;
            predicted.y += nearest.getVelocity().y * timeToReach;
        }
        float angleTo = Misc.getAngleInDegrees(ship.getLocation(), predicted);
        float facing = ship.getFacing();
        float diff = Math.abs(MathUtils.getShortestRotation(facing, angleTo));
        // 满足距离和角度条件
        if (distance < TRIGGER_RANGE && diff < ARC) {
            // 强制加速并朝向预测点
            ship.giveCommand(com.fs.starfarer.api.combat.ShipCommand.ACCELERATE, null, 0);
            // 禁用停止，后退指令，防止舰船减速
            ship.blockCommandForOneFrame(com.fs.starfarer.api.combat.ShipCommand.ACCELERATE_BACKWARDS);
            ship.blockCommandForOneFrame(com.fs.starfarer.api.combat.ShipCommand.DECELERATE);
            
            if (diff > 2f) {
                if (MathUtils.getShortestRotation(facing, angleTo) > 0) {
                    ship.giveCommand(com.fs.starfarer.api.combat.ShipCommand.TURN_RIGHT, null, 0);
                } else {
                    ship.giveCommand(com.fs.starfarer.api.combat.ShipCommand.TURN_LEFT, null, 0);
                }
            }
            shouldFire = true;
            targetShip = nearest;
            target = predicted;
        }
    }

    @Override
    public boolean shouldFire() {
        return shouldFire;
    }

    @Override
    public void forceOff() {
        shouldFire = false;
    }

    @Override
    public Vector2f getTarget() {
        return target;
    }

    @Override
    public ShipAPI getTargetShip() {
        return targetShip;
    }

    @Override
    public WeaponAPI getWeapon() {
        return weapon;
    }

    @Override
    public com.fs.starfarer.api.combat.MissileAPI getTargetMissile() {
        return null;
    }
} 