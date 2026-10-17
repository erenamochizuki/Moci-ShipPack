package data.scripts.weapons;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

import static data.scripts.weapons.Moci_Weapon2Effect.normalizeAngle;

public class Moci_MechLightingHeadDecoWeaponEfect extends Moci_MechHeadDecoWeaponEfect {
    private static final float FOLLOW_RATIO = 0.1f; // 跟随角度比例参数
    private static final float ROTATION_SPEED = 8f; // 平滑过渡速度
    private static final float MAX_ROTATION = 180f; // 最大旋转角度

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 不调用父类的advance方法，避免跟随效果冲突
        if (engine == null || engine.isPaused()) {
            return;
        }

        if (weapon.getAnimation() == null) {
            return;
        }
        weapon.getAnimation().pause();
        ShipAPI ship = weapon.getShip();
        if (!ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 获取舰船的目标
        ShipAPI target = ship.getShipTarget();
        if (ship.getShipAI() != null && ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            target = (ShipAPI) ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM);
        }

        // 如果有目标，处理灯光动画和跟随
        if (target != null) {
            // 计算目标相对于武器的角度
            float targetAngle = VectorUtils.getAngle(weapon.getLocation(), target.getLocation());
            float shipFacing = ship.getFacing();
            
            // 计算目标相对于舰船的角度
            float targetRelativeToShip = MathUtils.getShortestRotation(shipFacing, targetAngle);
            
            // 只跟随目标相对于舰船角度的一部分
            float newRelativeAngle = targetRelativeToShip * FOLLOW_RATIO;
            float newAngle = shipFacing + newRelativeAngle;

            // 平滑过渡到新角度
            float currentAngle = weapon.getCurrAngle();
            float rotation = MathUtils.getShortestRotation(currentAngle, newAngle);
            
            // 限制最大旋转速度
            float maxRotation = ROTATION_SPEED * amount;
            if (Math.abs(rotation) > maxRotation) {
                rotation = Math.signum(rotation) * maxRotation;
            }
            
            // 应用旋转
            weapon.setCurrAngle(currentAngle + rotation);

            // 根据角度差设置动画帧
            int frame = 5; // 默认中间帧
            float ang = normalizeAngle(targetRelativeToShip);
            if(ang<-20){
                frame=9;
            }else if(ang<-15){
                frame=8;
            }else if(ang<-10){
                frame=7;
            }else if(ang<-5){
                frame=6;
            }else if(ang>20){
                frame=1;
            }else if(ang>15){
                frame=2;
            }else if(ang>10){
                frame=3;
            }else if(ang>5){
                frame=4;
            }
            weapon.getAnimation().setFrame(frame);
        } else {
            // 没有目标时，缓慢回到舰船朝向
            float shipFacing = ship.getFacing();
            float currentAngle = weapon.getCurrAngle();
            float rotation = MathUtils.getShortestRotation(currentAngle, shipFacing);
            
            // 限制最大旋转速度
            float maxRotation = ROTATION_SPEED * amount;
            if (Math.abs(rotation) > maxRotation) {
                rotation = Math.signum(rotation) * maxRotation;
            }
            
            // 应用旋转
            weapon.setCurrAngle(currentAngle + rotation);
            
            // 设置默认动画帧
            weapon.getAnimation().setFrame(0);
        }
    }
}
