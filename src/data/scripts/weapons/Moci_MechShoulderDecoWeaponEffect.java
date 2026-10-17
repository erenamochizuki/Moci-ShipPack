package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 机甲肩膀装饰武器效果基础类
 * 提供通用的肩膀装饰武器功能，支持子类继承和扩展
 */
public class Moci_MechShoulderDecoWeaponEffect implements EveryFrameWeaponEffectPlugin {
    // 基础状态变量
    protected boolean init = false;
    protected WeaponAPI source = null;
    protected WeaponAPI arm = null;
    protected WeaponAPI wing = null;
    protected static final float SHOULDER_FOLLOW_RATIO = 0.5f; // 肩膀跟随比例参数
    protected boolean wasFiring = false;
    protected boolean isAnimating = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        
        // 初始化
        if (!init) {
            init = true;
            initializeWeapons(weapon);
            if (source != null) {
                setupSourceWeapon(weapon);
            }
            if (wing != null) {
                wing.setSuspendAutomaticTurning(true);
            }
            // 允许子类进行额外初始化
            onInitialized(weapon);
        }
        
        // 处理SOURCE武器
        if (source != null) {
            handleSourceWeapon(weapon, amount);
        }
        
        // 处理翼部武器
        if (wing != null) {
            handleWingWeapon(weapon, amount);
        }
        
        // 处理手臂武器
        if (arm != null) {
            handleArmWeapon(weapon, amount);
        }
        
        // 允许子类进行自定义处理
        onAdvance(weapon, amount, engine);
    }
    
    /**
     * 初始化关联武器
     */
    protected void initializeWeapons(WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        String slotId = weapon.getSlot().getId();
        
        // 通过武器槽添加后缀寻找关联武器
        String sourceId = slotId + "_SOURCE";
        String armId = "ARM" + slotId.substring(slotId.length() - 2);
        String wingId = "WING" + slotId.substring(slotId.length() - 2);
        
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (sourceId.equals(w.getSlot().getId())) {
                source = w;
            }
            if (armId.equals(w.getSlot().getId())) {
                arm = w;
            }
            if (wingId.equals(w.getSlot().getId())) {
                wing = w;
            }
            if (source != null && arm != null && wing != null) {
                break;
            }
        }
    }
    
    /**
     * 设置SOURCE武器
     */
    protected void setupSourceWeapon(WeaponAPI weapon) {
        // 计算装饰武器和关联武器的武器槽坐标差值
        Vector2f diff = new Vector2f();
        Vector2f.sub(weapon.getSlot().getLocation(), source.getSlot().getLocation(), diff);
        
        // 取装饰武器的炮塔模式第一个开火点坐标
        Vector2f firePoint = weapon.getSpec().getTurretFireOffsets().get(0);
        Moci_MechProjDecoWeaponEffect.syncFirePoint(source, diff, firePoint);
        
        // 隐藏SOURCE武器
        hideSourceWeapon();
        source.setSuspendAutomaticTurning(true);
    }
    
    /**
     * 隐藏SOURCE武器
     * 参考骤雨mod的方法：每帧都设置透明颜色，包括所有贴图组件
     */
    protected void hideSourceWeapon() {
        if (source == null) return;
        
        // 透明颜色，参考骤雨mod的实现
        Color transparent = new Color(0f, 0f, 0f, 0f);
        
        // 设置所有贴图组件为透明，包括发光贴图
        if (source.getSprite() != null) {
            source.getSprite().setColor(transparent);
        }
        if (source.getBarrelSpriteAPI() != null) {
            source.getBarrelSpriteAPI().setColor(transparent);
        }
        if (source.getUnderSpriteAPI() != null) {
            source.getUnderSpriteAPI().setColor(transparent);
        }
        if (source.getGlowSpriteAPI() != null) {
            source.getGlowSpriteAPI().setColor(transparent);
        }
    }
    
    /**
     * 处理SOURCE武器逻辑
     */
    protected void handleSourceWeapon(WeaponAPI weapon, float amount) {
        // 武器绑定肩膀的角度
        source.setCurrAngle(weapon.getCurrAngle());
        
        // 开火与动画联动
        if (weapon.getAnimation() != null) {
            boolean isFiring = source.isFiring() && source.getCooldownRemaining() == 0;
            
            if (isFiring) {
                // 重置动画到第一帧
                if (!wasFiring) {
                    setSafeFrame(weapon, 0);
                }
                weapon.getAnimation().play();
                wasFiring = true;
                isAnimating = true;
            } else if (wasFiring) {
                // 检查是否到达最后一帧
                if (weapon.getAnimation().getFrame() >= weapon.getAnimation().getNumFrames() - 1) {
                    weapon.getAnimation().pause();
                    setSafeFrame(weapon, 0);  // 播放完成后回到第0帧
                    wasFiring = false;
                    isAnimating = false;
                } else if (isAnimating) {
                    // 继续播放直到完成
                    weapon.getAnimation().play();
                }
            } else {
                weapon.getAnimation().pause();
                isAnimating = false;
            }
        }
    }
    
    /**
     * 处理翼部武器逻辑（基础版本）
     */
    protected void handleWingWeapon(WeaponAPI weapon, float amount) {
        wing.setCurrAngle(weapon.getCurrAngle());
    }
    
    /**
     * 处理手臂武器逻辑
     */
    protected void handleArmWeapon(WeaponAPI weapon, float amount) {
        // 计算手臂相对于飞船的角度
        float shipFacing = weapon.getShip().getFacing();
        float armAngle = arm.getCurrAngle() - shipFacing;
        
        // 规范化角度到[-180,180]范围
        while (armAngle > 180) armAngle -= 360;
        while (armAngle < -180) armAngle += 360;
        
        // 应用跟随比例并加回飞船朝向
        float targetAngle = shipFacing + armAngle * SHOULDER_FOLLOW_RATIO;
        weapon.setCurrAngle(targetAngle);
    }
    
    /**
     * 子类可重写此方法进行额外初始化
     */
    protected void onInitialized(WeaponAPI weapon) {
        // 默认空实现
    }
    
    /**
     * 子类可重写此方法进行自定义处理
     */
    protected void onAdvance(WeaponAPI weapon, float amount, CombatEngineAPI engine) {
        // 默认空实现
    }

    protected void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        int safeFrame = Math.max(0, Math.min(frame, maxFrame));
        weapon.getAnimation().setFrame(safeFrame);
    }
}
