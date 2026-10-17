package data.scripts.weapons;

import org.magiclib.util.MagicAnim;

import com.fs.starfarer.api.AnimationAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 带动画的光剑武器控制器
 * 扩展基础光剑效果，添加动画控制功能
 */
public class Moci_Blade_AnimationController extends Moci_BladeEffectPlugin {
    // 动画控制变量
    private float idleTimer = 0f; // 空闲计时器
    private final float IDLE_THRESHOLD = 5f; // 空闲时间阈值（5秒）
    private boolean isReversing = false; // 是否正在反向播放动画
    private float extendLevel = 0f; // 武器伸出程度
    private boolean extending = false; // 是否正在伸出
    private boolean charged = false; // 武器是否弹出
    private float originalExtendPos = 0f; // 武器的原始位置

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 首先调用父类方法处理基础光剑功能
        super.advance(amount, engine, weapon);
        
        if (engine == null || engine.isPaused()) return;

        // 处理武器动画
        if (weapon.getAnimation() != null) {
            updateWeaponAnimation(amount);
        }
    }

    /**
     * 更新武器动画状态
     */
    private void updateWeaponAnimation(float amount) {
        AnimationAPI anim = weapon.getAnimation();
        if (!charged && weapon.getChargeLevel() <= 0f) {
            anim.setFrame(6);
            anim.pause();
        }

        // 充能状态处理
        if (!charged && weapon.getChargeLevel() > 0f) {
            idleTimer = 0f;
            isReversing = false;

            // 如果当前帧是第6帧，则重置到第1帧
            if (anim.getFrame() == 6) {
                anim.setFrame(1);
            }

            // 如果当前帧小于5，则继续播放
            if (anim.getFrame() < 5) {
                anim.setFrame(anim.getFrame() + 1);
            } else {
                // 到达第5帧后暂停
                anim.setFrame(5);
                anim.pause();
                charged = true;
            }
        }
        // 非充能状态处理
        else if (charged && weapon.getChargeLevel() <= 0f) {
            // 如果武器未充能，则开始计时
            idleTimer += amount;

            // 如果空闲时间超过阈值且未在反向播放
            if (idleTimer >= IDLE_THRESHOLD && !isReversing) {
                isReversing = true; // 开始反向播放
            }

            // 如果正在反向播放
            if (isReversing) {
                if (anim.getFrame() > 1) { // 从第5帧回到第1帧
                    anim.setFrame(anim.getFrame() - 1);
                } else {
                    // 回到第1帧后，直接切换到第6帧并停留
                    anim.setFrame(6);
                    anim.pause();
                    isReversing = false;
                    idleTimer = 0f;
                    charged = false;
                }
            }
        }
    }

    /**
     * 处理武器伸缩动画
     */
    private void handleWeaponExtend(float amount) {
        // 如果武器正在攻击且未伸出
        if (weapon.isFiring() && !extending) {
            extending = true;
            extendLevel = 0f;
        }

        // 存储原始位置（仅在首次运行时）
        if (originalExtendPos == 0f && weapon.getSprite() != null) {
            originalExtendPos = weapon.getSprite().getCenterY();
        }

        // 处理伸出动画
        if (extending) {
            extendLevel += amount * 3f; // 控制伸出速度
            if (extendLevel >= 1f) {
                extendLevel = 1f;
                extending = false;
            }

            // 使用MagicAnim的平滑插值
            float extendAmount = MagicAnim.smoothNormalizeRange(extendLevel, 0f, 1f) * 6f; // 伸出距离为6像素
            weapon.getSprite().setCenterY(originalExtendPos - extendAmount);
        } else if (extendLevel > 0f) {
            // 如果伸出结束，则缓慢收回
            extendLevel -= amount * 1f; // 控制收回速度
            if (extendLevel < 0f) {
                extendLevel = 0f;
            }

            // 使用MagicAnim的平滑插值
            float extendAmount = MagicAnim.smoothNormalizeRange(extendLevel, 0f, 1f) * 6f;
            weapon.getSprite().setCenterY(originalExtendPos - extendAmount);
        }
    }
}