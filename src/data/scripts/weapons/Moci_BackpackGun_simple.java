package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 背包武器简化版帧动画控制器
 * 仅包含核心的帧动画机制
 * 
 * 动画规则：
 * - 默认状态：最后一帧（自动获取）
 * - 充能到阈值时：第0帧
 * - 充能降低时逐帧过渡回默认帧
 */
public class Moci_BackpackGun_simple implements EveryFrameWeaponEffectPlugin {
    
    // 动画帧配置
    private int defaultFrame = 0;                       // 默认帧（最后一帧，初始化时获取）
    private static final int MAX_CHARGE_FRAME = 0;      // 最大充能时第0帧
    private static final float CHARGE_THRESHOLD = 0.5f; // 充能阈值，超过此值切换到第0帧
    
    // 帧切换参数
    private int currentFrame = 0;                       // 当前帧（整数）
    private float frameTimer = 0f;                      // 帧切换计时器
    
    // 状态变量
    private boolean initialized = false;
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础检查：确保游戏和武器状态正常
        if (engine == null || engine.isPaused() || weapon.getAnimation() == null) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            // 舰船死亡时暂停动画
            weapon.getAnimation().pause();
            return;
        }
        
        // 初始化：获取武器的总帧数并设置默认帧为最后一帧
        if (!initialized) {
            int maxFrames = weapon.getAnimation().getNumFrames() - 1;
            defaultFrame = maxFrames; // 默认帧为最后一帧
            currentFrame = defaultFrame; // 初始帧也设为默认帧
            initialized = true;
        }
        
        // 暂停动画自动播放，由脚本手动控制
        weapon.getAnimation().pause();
        
        // 获取当前武器充能等级（0.0 - 1.0）
        float chargeLevel = weapon.getChargeLevel();
        
        // 计算目标帧
        int targetFrame = calculateTargetFrame(chargeLevel);
        
        // 逐帧过渡到目标帧
        transitionToFrame(targetFrame, amount, weapon);
        
        // 设置当前动画帧
        weapon.getAnimation().setFrame(currentFrame);
    }
    
    /**
     * 根据充能等级计算目标帧
     * @param chargeLevel 武器充能等级 (0.0 - 1.0)
     * @return 目标帧数（整数）
     */
    private int calculateTargetFrame(float chargeLevel) {
        if (chargeLevel >= CHARGE_THRESHOLD) {
            // 充能达到阈值时显示第0帧
            return MAX_CHARGE_FRAME;
        } else {
            // 线性插值计算帧数
            // chargeLevel 0.0 -> defaultFrame (最后一帧)
            // chargeLevel 0.5 -> MAX_CHARGE_FRAME (0)
            float normalizedCharge = chargeLevel / CHARGE_THRESHOLD;
            int frame = Math.round(defaultFrame - normalizedCharge * (defaultFrame - MAX_CHARGE_FRAME));
            // 确保帧数在有效范围内
            return Math.max(MAX_CHARGE_FRAME, Math.min(frame, defaultFrame));
        }
    }
    
    /**
     * 逐帧过渡到目标帧
     * 参考 Moci_MechLegAnimationController 的实现
     * @param targetFrame 目标帧数
     * @param amount 时间增量
     */
    private void transitionToFrame(int targetFrame, float amount, WeaponAPI weapon) {
        // 如果已经在目标帧，直接返回
        if (currentFrame == targetFrame) {
            frameTimer = 0f;
            return;
        }
        
        // 累积帧切换计时器，使用武器自身的帧率
        frameTimer += amount * weapon.getAnimation().getFrameRate();
        
        // 每当计时器达到1，切换一帧
        while (frameTimer >= 1f) {
            frameTimer -= 1f;
            
            // 逐帧向目标帧移动
            if (currentFrame < targetFrame) {
                currentFrame++;
            } else if (currentFrame > targetFrame) {
                currentFrame--;
            }
            
            // 确保不超过目标帧
            if (currentFrame == targetFrame) {
                break;
            }
        }
        
        // 确保帧数在有效范围内
        currentFrame = Math.max(MAX_CHARGE_FRAME, Math.min(currentFrame, defaultFrame));
    }
}
