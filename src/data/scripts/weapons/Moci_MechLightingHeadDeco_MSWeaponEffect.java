package data.scripts.weapons;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

import static data.scripts.weapons.Moci_Weapon2Effect.normalizeAngle;

public class Moci_MechLightingHeadDeco_MSWeaponEffect extends Moci_MechHeadDecoWeaponEfect {
    private static final float FOLLOW_RATIO = 0.4f; // 跟随角度比例参数
    private static final float ROTATION_SPEED = 8f; // 平滑过渡速度
    private static final float MAX_ROTATION = 180f; // 最大旋转角度
    private static final String HEAD_LIGHT_SLOT_ID = "HEAD_LIGHT"; // 头灯槽位ID

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 不调用父类的advance方法，避免跟随效果冲突
        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (!ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 查找HEAD_LIGHT槽位的武器
        WeaponAPI headLightWeapon = null;
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (HEAD_LIGHT_SLOT_ID.equals(w.getSlot().getId())) {
                headLightWeapon = w;
                break;
            }
        }

        // 获取舰船的目标
        ShipAPI target = ship.getShipTarget();
        if (ship.getShipAI() != null && ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM)) {
            target = (ShipAPI) ship.getAIFlags().getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM);
        }

        // 如果有目标，处理头部朝向和头灯动画
        if (target != null) {
            // 计算目标相对于武器的角度
            float targetAngle = VectorUtils.getAngle(weapon.getLocation(), target.getLocation());
            float shipFacing = ship.getFacing();
            
            // 计算目标相对于舰船的角度
            float targetRelativeToShip = MathUtils.getShortestRotation(shipFacing, targetAngle);
            
            // 只跟随目标相对于舰船角度的一部分
            float newRelativeAngle = targetRelativeToShip * FOLLOW_RATIO;
            float newAngle = shipFacing + newRelativeAngle;

            // 平滑过渡到新角度（头部武器）
            float currentAngle = weapon.getCurrAngle();
            float rotation = MathUtils.getShortestRotation(currentAngle, newAngle);
            
            // 限制最大旋转速度
            float maxRotation = ROTATION_SPEED * amount;
            if (Math.abs(rotation) > maxRotation) {
                rotation = Math.signum(rotation) * maxRotation;
            }
            
            // 应用旋转到头部武器
            weapon.setCurrAngle(currentAngle + rotation);

            // 如果找到头灯武器，控制其动画和朝向
            if (headLightWeapon != null) {
                // 让头灯武器朝向与头部武器保持一致
                headLightWeapon.setCurrAngle(weapon.getCurrAngle());
                
                // 控制头灯的帧动画
                if (headLightWeapon.getAnimation() != null) {
                    headLightWeapon.getAnimation().pause();
                    
                    // 根据角度差设置动画帧 (6帧系统: 0-5帧)
                    // 0帧：默认帧，1帧：-30度，2帧：-15度，3帧：0度，4帧：15度，5帧：30度
                    int frame = 0; // 默认帧
                    float ang = normalizeAngle(targetRelativeToShip);
                    if (ang < -45f) {        // -30度范围
                        frame = 5;
                    } else if (ang < -20f) {  // -15度范围
                        frame = 4;
                    } else if (ang < 20f) {   // 0度范围
                        frame = 3;
                    } else if (ang < 45) {  // 15度范围
                        frame = 2;
                    } else {                   // 30度范围
                        frame = 1;
                    }
                    headLightWeapon.getAnimation().setFrame(frame);
                }
            }
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
            
            // 应用旋转到头部武器
            weapon.setCurrAngle(currentAngle + rotation);
            
            // 如果找到头灯武器，让其与头部保持一致
            if (headLightWeapon != null) {
                // 让头灯武器朝向与头部武器保持一致
                headLightWeapon.setCurrAngle(weapon.getCurrAngle());
                
                // 设置默认动画帧
                if (headLightWeapon.getAnimation() != null) {
                    headLightWeapon.getAnimation().pause();
                    headLightWeapon.getAnimation().setFrame(0);
                }
            }
        }
    }
} 