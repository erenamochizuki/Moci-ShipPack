package data.scripts.weapons;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

import static data.scripts.weapons.Moci_Weapon2Effect.normalizeAngle;

/**
 * MSN02机体头部装饰武器效果 - 7帧版本
 * 帧分配：
 * 0 - 正前方（中间）
 * 1-3 - 右侧（从小到大角度）
 * 4-6 - 左侧（从小到大角度）
 */
public class Moci_MechLightingHeadDeco_MSN02_WeaponEfect extends Moci_MechHeadDecoWeaponEfect {
    private static final float FOLLOW_RATIO = 0.4f; // 跟随角度比例参数
    private static final float ROTATION_SPEED = 8f; // 平滑过渡速度
    private static final float MAX_ROTATION = 180f; // 最大旋转角度
    private static final String HEAD_LIGHT_SLOT_ID = "HEAD_LIGHT"; // 头灯槽位ID
    
    // 角度阈值 - 7帧系统，每个区间约30度
    private static final float ANGLE_STEP = 30f; // 每帧对应的角度范围

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
                
                // 控制头灯的帧动画 (7帧系统: 0-6帧)
                if (headLightWeapon.getAnimation() != null) {
                    headLightWeapon.getAnimation().pause();
                    
                    // 根据角度差设置动画帧
                    // 0帧：正前方，1-3帧：右侧，4-6帧：左侧
                    int frame = 0; // 默认正前方
                    float ang = normalizeAngle(targetRelativeToShip);
                    
                    // 右侧（正角度）：1-3帧
                    if (ang > 0) {
                        if (ang <= ANGLE_STEP) {
                            frame = 3; // 0-30度
                        } else if (ang <= ANGLE_STEP * 2) {
                            frame = 2; // 30-60度
                        } else {
                            frame = 1; // 60度以上
                        }
                    }
                    // 左侧（负角度）：4-6帧
                    else if (ang < 0) {
                        if (ang >= -ANGLE_STEP) {
                            frame = 4; // 0到-30度
                        } else if (ang >= -ANGLE_STEP * 2) {
                            frame = 5; // -30到-60度
                        } else {
                            frame = 6; // -60度以下
                        }
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
                
                // 设置默认动画帧（正前方）
                if (headLightWeapon.getAnimation() != null) {
                    headLightWeapon.getAnimation().pause();
                    headLightWeapon.getAnimation().setFrame(0);
                }
            }
        }
    }
}
