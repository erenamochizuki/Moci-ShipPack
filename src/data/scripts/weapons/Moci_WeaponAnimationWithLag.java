package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 带有角度滞留效果的动画控制武器
 * 功能：
 * 1. 控制帧动画：前进时+帧数，后退时-帧数
 * 2. 武器角度滞留：延迟跟随舰船转向
 */
public class Moci_WeaponAnimationWithLag implements EveryFrameWeaponEffectPlugin {
    
    /** 是否已初始化 */
    private boolean initialized = false;
    
    /** 是否是帧动画武器 */
    private boolean hasFrameAnimation = false;
    
    /** 武器最大帧数 */
    private int maxFrames = 0;
    
    /** 当前帧 */
    private int currentFrame = 0;
    
    /** 动画计时器 */
    private float animationTimer = 0f;
    
    /** 武器当前角度 */
    private float weaponAngle = 0f;
    
    /** 角度更新计时器 - 每0.1秒更新一次角度 */
    private final IntervalUtil angleUpdateInterval = new IntervalUtil(0.1f, 0.1f);
    
    /** 角度转向速度：每0.1秒2度 */
    private static final float ANGLE_TURN_SPEED = 2f;
    
    /** 直接设置角度的阈值：小于5度时直接设置 */
    private static final float DIRECT_SET_THRESHOLD = 5f;
    
    /** 最大角度差：不超过90度 */
    private static final float MAX_ANGLE_DIFF = 90f;
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            if (weapon.getAnimation() != null) {
                weapon.getAnimation().pause();
            }
            return;
        }
        
        // 初始化检查
        if (!initialized) {
            initializeWeapon(weapon, ship);
        }
        
        // 处理帧动画
        if (hasFrameAnimation) {
            handleFrameAnimation(amount, weapon, ship);
        }
        
        // 处理武器角度滞留效果
        handleAngleLag(amount, weapon, ship);
    }
    
    /**
     * 初始化武器参数
     */
    private void initializeWeapon(WeaponAPI weapon, ShipAPI ship) {
        // 检查是否有帧动画
        if (weapon.getAnimation() != null) {
            hasFrameAnimation = true;
            maxFrames = weapon.getAnimation().getNumFrames() - 1;
            currentFrame = 0; // 从第0帧开始
            Global.getLogger(this.getClass()).info("武器 " + weapon.getId() + " 检测到帧动画，总帧数：" + (maxFrames + 1));
        } else {
            hasFrameAnimation = false;
            Global.getLogger(this.getClass()).info("武器 " + weapon.getId() + " 无帧动画");
        }
        
        // 初始化武器角度为舰船当前角度
        weaponAngle = ship.getFacing();
        
        initialized = true;
    }
    
    /**
     * 处理帧动画逻辑
     */
    private void handleFrameAnimation(float amount, WeaponAPI weapon, ShipAPI ship) {
        // 检测引擎状态
        boolean isAccelerating = ship.getEngineController().isAccelerating();
        boolean isDecelerating = ship.getEngineController().isDecelerating();
        boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();
        
        // 更新动画计时器
        animationTimer += amount * weapon.getAnimation().getFrameRate();
        
        // 每帧检查一次是否需要更新帧数
        while (animationTimer >= 1f) {
            animationTimer -= 1f;
            
            // 前进时增加帧数
            if (isAccelerating) {
                currentFrame++;
                // 超过最大帧数时循环到0
                if (currentFrame > maxFrames) {
                    currentFrame = 0;
                }
            }
            // 后退时减少帧数  
            else if (isDecelerating || isBackwards) {
                currentFrame--;
                // 小于0时循环到最大帧
                if (currentFrame < 0) {
                    currentFrame = maxFrames;
                }
            }
            // 无操作时保持当前帧不变
        }
        
        // 设置当前帧
        weapon.getAnimation().setFrame(currentFrame);
        
        // 控制动画播放状态
        if (isAccelerating || isDecelerating || isBackwards) {
            weapon.getAnimation().play();
        } else {
            weapon.getAnimation().pause();
        }
    }
    
    /**
     * 处理武器角度滞留效果
     */
    private void handleAngleLag(float amount, WeaponAPI weapon, ShipAPI ship) {
        // 更新角度计时器
        angleUpdateInterval.advance(amount);
        
        if (angleUpdateInterval.intervalElapsed()) {
            float shipAngle = ship.getFacing();
            float angleDiff = calculateShortestAngleDiff(weaponAngle, shipAngle);
            
            // 限制最大角度差不超过90度
            if (Math.abs(angleDiff) > MAX_ANGLE_DIFF) {
                // 如果超过90度，将武器角度设置为距离舰船角度90度的位置
                if (angleDiff > 0) {
                    weaponAngle = normalizeAngle(shipAngle + MAX_ANGLE_DIFF);
                } else {
                    weaponAngle = normalizeAngle(shipAngle - MAX_ANGLE_DIFF);
                }
                angleDiff = calculateShortestAngleDiff(weaponAngle, shipAngle);
            }
            
            // 如果角度差小于阈值，直接设置为目标角度
            if (Math.abs(angleDiff) < DIRECT_SET_THRESHOLD) {
                weaponAngle = shipAngle;
            } else {
                // 按照固定速度向目标角度转向
                if (angleDiff > 0) {
                    weaponAngle += ANGLE_TURN_SPEED;
                } else {
                    weaponAngle -= ANGLE_TURN_SPEED;
                }
                weaponAngle = normalizeAngle(weaponAngle);
            }
            
            // 设置武器角度
            weapon.setCurrAngle(weaponAngle);
        }
    }
    
    /**
     * 计算两个角度之间的最短角度差
     * 返回值：正数表示需要顺时针转动，负数表示需要逆时针转动
     */
    private float calculateShortestAngleDiff(float currentAngle, float targetAngle) {
        float diff = targetAngle - currentAngle;
        
        // 将角度差标准化到-180到180度之间
        while (diff > 180f) {
            diff -= 360f;
        }
        while (diff < -180f) {
            diff += 360f;
        }
        
        return diff;
    }
    
    /**
     * 将角度标准化到-180到180度之间
     */
    private float normalizeAngle(float angle) {
        while (angle > 180f) {
            angle -= 360f;
        }
        while (angle < -180f) {
            angle += 360f;
        }
        return angle;
    }
} 