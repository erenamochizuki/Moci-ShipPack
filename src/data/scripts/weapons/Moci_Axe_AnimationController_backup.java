package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicAnim;
import org.magiclib.util.MagicRender;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

/**
 * 斧头武器动画控制器 - 备份版本
 * 扩展基础效果，添加Y轴伸出/收回动画
 * 基于Moci_System1Effect.java实现可靠的残影效果
 */
public class Moci_Axe_AnimationController_backup extends Moci_BladeEffectPlugin {
    // 动画控制变量
    private float extendLeftLevel = 0f;  // 左侧伸出程度
    private float extendRightLevel = 0f; // 右侧伸出程度
    private boolean extendingLeft = false;  // 是否正在左侧伸出
    private boolean extendingRight = false; // 是否正在右侧伸出
    private float originalExtendPos = 0f; // 武器的原始位置
    
    // 阶段控制
    private int axeSwingPhase = 0; // 0: 空闲, 1: 左伸, 2: 左->右劈砍, 3: 右伸, 4: 右->左劈砍
    private float phaseTimer = 0f; // 阶段计时器
    
    // 配置参数
    private final float EXTEND_SPEED = 5.0f;    // 伸出速度乘数，越大越快
    private final float RETRACT_SPEED = 3.0f;   // 收回速度乘数，越大越快
    private final float MAX_EXTEND = 10.0f;      // 最大伸出距离（像素）
    // 注意：斧头动画控制器使用固定时间参数，因为它是EveryFrameWeaponEffectPlugin
    // 而不是BeamEffectPlugin，无法像光束武器那样获取chargeup时间
    private final float PHASE_DURATION = 0.4f;  // 每个阶段持续时间（秒）
    private final float TRANSITION_PAUSE = 0.2f; // 阶段间短暂停顿（秒）

    // 残影效果参数（基于Moci_System1Effect.java的成功实现）
    private final float AFTERIMAGE_INTERVAL = 0.06f; // 残影间隔
    private final float AFTERIMAGE_FADE_IN = 0f;
    private final float AFTERIMAGE_FADE_OUT = 0.4f;
    private final float AFTERIMAGE_ALPHA_START = 0.7f;
    private final float AFTERIMAGE_ALPHA_END = 0f;
    private float afterimageAccumulator = 0f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 首先调用父类方法处理基础功能
        super.advance(amount, engine, weapon);
        
        if (engine == null || engine.isPaused()) return;

        // 处理武器伸缩动画
        handleAxeExtendAnimation(amount, weapon);
        
        // 添加武器残像效果（基于Moci_System1Effect.java的成功实现）
        handleWeaponAfterimage(amount, weapon);
    }

    /**
     * 处理武器残像效果
     * 完全基于Moci_System1Effect.java中的成功实现
     */
    private void handleWeaponAfterimage(float amount, WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        
        // 在武器攻击时生成残像效果
        if (weapon.isFiring() && weapon.getChargeLevel() > 0f && !ship.getFluxTracker().isOverloaded()) {
            afterimageAccumulator += amount;
            
            if (afterimageAccumulator >= AFTERIMAGE_INTERVAL) {
                createWeaponAfterimage(weapon);
                afterimageAccumulator = 0f;
            }
        }
    }
    
    /**
     * 创建武器残影
     */
    private void createWeaponAfterimage(WeaponAPI weapon) {
        if (weapon.getSprite() == null) return;
        
        ShipAPI ship = weapon.getShip();
        
        // 获取舰船偏移量
        SpriteAPI shipSprite = ship.getSpriteAPI();
        float offsetX = shipSprite.getWidth()/2 - shipSprite.getCenterX();
        float offsetY = shipSprite.getHeight()/2 - shipSprite.getCenterY();

        // 计算旋转后的偏移量
        float trueOffsetX = (float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetX -
                (float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetY;
        float trueOffsetY = (float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetX +
                (float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetY;
        
        // 检查武器是否被设置为完全透明
        boolean isHidden = false;
        if (weapon.getSprite() != null && weapon.getSprite().getColor().getAlpha() == 0) {
            isHidden = true;
        }
        if (weapon.getBarrelSpriteAPI() != null && weapon.getBarrelSpriteAPI().getColor().getAlpha() == 0) {
            isHidden = true;
        }
        if (weapon.getUnderSpriteAPI() != null && weapon.getUnderSpriteAPI().getColor().getAlpha() == 0) {
            isHidden = true;
        }
        if (weapon.getGlowSpriteAPI() != null && weapon.getGlowSpriteAPI().getColor().getAlpha() == 0) {
            isHidden = true;
        }

        if (isHidden) return;

        String spritePath;
        if (weapon.getAnimation() != null) {
            // 对于动画武器，获取当前帧的贴图路径
            int frame = weapon.getAnimation().getFrame();
            String basePath = weapon.getSlot().isTurret() ?
                    weapon.getSpec().getTurretSpriteName() :
                    weapon.getSpec().getHardpointSpriteName();
            // 移除.png后缀
            if (basePath.endsWith(".png")) {
                basePath = basePath.substring(0, basePath.length() - 4);
            }
            // 添加帧号和后缀
            spritePath = String.format("%s_%02d.png", basePath, frame);
        } else {
            // 无动画的武器使用默认贴图
            spritePath = weapon.getSlot().isTurret() ?
                    weapon.getSpec().getTurretSpriteName() :
                    weapon.getSpec().getHardpointSpriteName();
        }

        try {
            // 获取新的Sprite实例
            SpriteAPI weaponSprite = Global.getSettings().getSprite(spritePath);
            if (weaponSprite != null) {
                weaponSprite.setSize(weapon.getSprite().getWidth(), weapon.getSprite().getHeight());

                // 斧头武器使用橙红色残像，根据阶段调整强度
                float alphaMultiplier = 1.0f;
                if (axeSwingPhase == 2 || axeSwingPhase == 4) {
                    // 在劈砍阶段增强残像效果
                    alphaMultiplier = 1.3f;
                }
                
                Color axeAfterimageColor = new Color(255, 150, 50, 
                    (int)(AFTERIMAGE_ALPHA_START * 255 * alphaMultiplier));

                // 创建残影
                MagicRender.battlespace(
                        weaponSprite,
                        new Vector2f(weapon.getLocation().x + trueOffsetX, weapon.getLocation().y + trueOffsetY),
                        new Vector2f(0, 0),
                        new Vector2f(weapon.getSprite().getWidth(), weapon.getSprite().getHeight()),
                        new Vector2f(0, 0),
                        weapon.getCurrAngle()-90f,
                        0f,
                        axeAfterimageColor,
                        true,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        AFTERIMAGE_FADE_IN,
                        AFTERIMAGE_FADE_OUT,
                        AFTERIMAGE_ALPHA_END,
                        CombatEngineLayers.BELOW_SHIPS_LAYER
                );
            }
        } catch (Exception e) {
            // 静默处理sprite加载失败，不影响游戏进行
        }
    }

    /**
     * 处理斧头伸缩动画
     */
    private void handleAxeExtendAnimation(float amount, WeaponAPI weapon) {
        // 存储原始位置（仅在首次运行时）
        if (originalExtendPos == 0f && weapon.getSprite() != null) {
            originalExtendPos = weapon.getSprite().getCenterY();
        }
        
        // 更新计时器和阶段
        if (weapon.getChargeLevel() > 0f) {
            // 前两个阶段: 左侧伸出并从左向右劈砍
            if (axeSwingPhase <= 2) {
                // 第一阶段: 左侧伸出
                if (axeSwingPhase == 0) {
                    axeSwingPhase = 1;
                    extendingLeft = true;
                    extendLeftLevel = 0f;
                }
                
                // 处理左侧伸出动画
                if (extendingLeft) {
                    extendLeftLevel += amount * EXTEND_SPEED;
                    if (extendLeftLevel >= 1f) {
                        extendLeftLevel = 1f;
                        // 开始从左向右劈砍，同时收回左侧伸出
                        axeSwingPhase = 2;
                        phaseTimer = 0f;
                    }
                }
                
                // 从左向右劈砍时逐渐收回左侧伸出
                if (axeSwingPhase == 2) {
                    phaseTimer += amount;
                    float progress = Math.min(phaseTimer / PHASE_DURATION, 1.0f);
                    // 收回速度与挥砍进度同步
                    extendLeftLevel = Math.max(0f, 1f - progress);
                    
                    // 第一次劈砍结束后，准备开始第二次
                    if (progress >= 1.0f && !extendingRight) {
                        phaseTimer = 0f;
                        axeSwingPhase = 3;
                        extendingRight = true;
                        extendRightLevel = 0f;
                    }
                }
            }
            // 后两个阶段: 右侧伸出并从右向左劈砍
            else if (axeSwingPhase >= 3) {
                // 第三阶段: 右侧伸出
                if (axeSwingPhase == 3) {
                    extendRightLevel += amount * EXTEND_SPEED;
                    if (extendRightLevel >= 1f) {
                        extendRightLevel = 1f;
                        // 开始从右向左劈砍，同时收回右侧伸出
                        axeSwingPhase = 4;
                        phaseTimer = 0f;
                    }
                }
                
                // 从右向左劈砍时逐渐收回右侧伸出
                if (axeSwingPhase == 4) {
                    phaseTimer += amount;
                    float progress = Math.min(phaseTimer / PHASE_DURATION, 1.0f);
                    // 收回速度与挥砍进度同步
                    extendRightLevel = Math.max(0f, 1f - progress);
                    
                    // 全部动画结束后重置
                    if (progress >= 1.0f) {
                        phaseTimer = 0f;
                        axeSwingPhase = 0;
                        extendingLeft = false;
                        extendingRight = false;
                    }
                }
            }
            
            // 应用伸出效果 - 左侧伸出和右侧伸出使用不同方向
            float leftExtendAmount = MagicAnim.smoothNormalizeRange(extendLeftLevel, 0f, 1f) * MAX_EXTEND;
            float rightExtendAmount = MagicAnim.smoothNormalizeRange(extendRightLevel, 0f, 1f) * MAX_EXTEND;
            
            // 基于当前阶段决定是左伸还是右伸
            if (axeSwingPhase <= 2) {
                // 左侧伸出（向前）
                weapon.getSprite().setCenterY(originalExtendPos - leftExtendAmount);
            } else {
                // 右侧伸出（向前）
                weapon.getSprite().setCenterY(originalExtendPos - rightExtendAmount);
            }
        } 
        // 武器不在充能状态时
        else {
            // 平滑恢复到原始位置
            float currentPos = weapon.getSprite().getCenterY();
            if (Math.abs(currentPos - originalExtendPos) > 0.1f) {
                float newPos = originalExtendPos * 0.1f + currentPos * 0.9f;
                weapon.getSprite().setCenterY(newPos);
            } else {
                weapon.getSprite().setCenterY(originalExtendPos);
            }
            
            // 重置所有状态
            axeSwingPhase = 0;
            phaseTimer = 0f;
            extendingLeft = false;
            extendingRight = false;
            extendLeftLevel = 0f;
            extendRightLevel = 0f;
        }
    }
} 