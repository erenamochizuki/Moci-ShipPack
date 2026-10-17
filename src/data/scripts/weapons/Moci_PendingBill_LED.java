package data.scripts.weapons;

import java.awt.Color;

import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.FaderUtil;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * LED效果插件 - 为武器提供周期性的闪烁效果
 * 支持光效和动态帧数动画，特别针对MAINGUN_前缀的武器槽位提供帧动画控制
 * 使用过渡帧实现平滑的帧切换效果，避免突变
 * 
 * 动态帧支持：
 * - 自动检测武器的总帧数
 * - 第0帧作为非激活状态
 * - 最后一帧作为激活状态
 * - 中间所有帧作为过渡帧，实现平滑过渡
 * 
 * @author HSI团队
 */
public class Moci_PendingBill_LED implements EveryFrameWeaponEffectPlugin {
    // 初始化标志，确保初始化只执行一次
    private boolean once = true;
    
    // 闪烁渐变控制器，控制闪烁的亮度变化
    private FaderUtil blinker;
    
    // 计时器，控制闪烁间隔
    private IntervalUtil timer;
    
    // 是否启用光效
    private final boolean withLight = true; // 默认启用光效
    
    // 光源对象，用于创建动态光效
    private StandardLight light;
    
    // 武器的发光颜色
    private Color glowColor;
    
    // 帧动画相关变量
    private int currentFrame = 0;           // 当前帧
    private int targetFrame = 0;            // 目标帧
    private float frameTimer = 0f;          // 帧计时器
    private float weaponFrameRate = 10f;    // 武器的帧率（从wpn文件获取）
    
    // 动态帧数支持
    private int maxFrame = 0;               // 武器的最大帧索引（总帧数-1）
    private int inactiveFrame = 0;          // 非激活状态帧（固定为0）
    private int activeFrame = 0;            // 激活状态帧（最后一帧）
    
    // 默认配置参数
    private static final float DEFAULT_INTERVAL = 4f;          // 默认闪烁间隔（秒）
    private static final float DEFAULT_BLINK_TIME = 4f;        // 默认闪烁持续时间（秒）
    private static final float DEFAULT_LIGHT_INTENSITY = 0.75f; // 默认光源强度
    private static final float DEFAULT_LIGHT_SIZE = 250f;      // 默认光源大小
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 首次运行时进行初始化
        if (once) {
            initializeBlinker(weapon);
            once = false;
        }
        
        // 默认暂停动画，脚本将手动控制每一帧的播放
        weapon.getAnimation().pause();
        
        // 如果启用了光效但武器所属舰船已死亡，则清理光源
        if (withLight && weapon.getShip() != null && !weapon.getShip().isAlive()) {
            cleanupLight();
            return;
        }
        
        // 计算时间推进量，如果舰船过载或排热则加速闪烁
        float timeAdvancement = calculateTimeAdvancement(amount, weapon);
        
        // 检查MAINGUN状态，用于控制闪烁和帧动画
        boolean hasActiveMaingun = checkForActiveMaingun(weapon);
        
        // 处理闪烁逻辑（考虑MAINGUN状态）
        processBlinkerLogic(timeAdvancement, weapon, hasActiveMaingun);
        
        // 处理MAINGUN_武器槽位的帧动画（带过渡效果）
        processMaingunFrameAnimation(timeAdvancement, weapon, hasActiveMaingun);
    }
    
    /**
     * 初始化闪烁器和相关组件
     * @param weapon 武器对象
     */
    private void initializeBlinker(WeaponAPI weapon) {
        // 获取武器的发光颜色
        glowColor = weapon.getSpec().getGlowColor();
        
        // 初始化计时器，使用默认间隔
        timer = new IntervalUtil(DEFAULT_INTERVAL, DEFAULT_INTERVAL);
        
        // 初始化闪烁渐变器
        // 参数：最小值、渐入时间、渐出时间、是否循环、是否从最小值开始
        blinker = new FaderUtil(0f, DEFAULT_BLINK_TIME / 2f, DEFAULT_BLINK_TIME / 2f, false, true);
        blinker.fadeIn(); // 开始渐入
        
        // 初始化帧动画参数
        initializeFrameAnimation(weapon);
        
        // 如果启用光效，则创建光源
        if (withLight) {
            createLight(weapon);
        }
    }
    
    /**
     * 初始化帧动画参数
     * @param weapon 武器对象
     */
    private void initializeFrameAnimation(WeaponAPI weapon) {
        // 从武器配置文件获取帧率和帧数信息
        if (weapon.getAnimation() != null) {
            weaponFrameRate = weapon.getAnimation().getFrameRate();
            if (weaponFrameRate <= 0) {
                weaponFrameRate = 10f; // 如果没有设置或设置错误，使用默认值
            }
            
            // 获取武器的总帧数并设置状态帧
            int totalFrames = weapon.getAnimation().getNumFrames();
            if (totalFrames > 0) {
                maxFrame = totalFrames - 1;          // 最大帧索引
                inactiveFrame = 0;                   // 非激活状态固定为第0帧
                activeFrame = maxFrame;              // 激活状态为最后一帧
            } else {
                // 如果没有帧数信息，使用单帧模式
                maxFrame = 0;
                inactiveFrame = 0;
                activeFrame = 0;
            }
        } else {
            // 没有动画时的默认设置
            weaponFrameRate = 10f;
            maxFrame = 0;
            inactiveFrame = 0;
            activeFrame = 0;
        }
        
        // 检查初始状态并设置相应的帧
        boolean hasActiveMaingun = checkForActiveMaingun(weapon);
        
        // 初始状态设置
        currentFrame = inactiveFrame;
        targetFrame = hasActiveMaingun ? activeFrame : inactiveFrame;
        frameTimer = 0f;
        weapon.getAnimation().setFrame(currentFrame);
    }
    
    /**
     * 创建动态光源
     * @param weapon 武器对象
     */
    private void createLight(WeaponAPI weapon) {
        // 创建标准光源：位置、速度、加速度、所属舰船
        light = new StandardLight(
            weapon.getLocation(), 
            new Vector2f(0, 0), 
            new Vector2f(0, 0), 
            weapon.getShip()
        );
        light.makePermanent(); // 设置为永久光源
        LightShader.addLight(light); // 添加到光源系统
    }
    
    /**
     * 清理光源资源
     */
    private void cleanupLight() {
        if (light != null) {
            LightShader.removeLight(light);
            light = null;
        }
    }
    
    /**
     * 计算时间推进量，考虑舰船状态
     * @param amount 基础时间推进量
     * @param weapon 武器对象
     * @return 调整后的时间推进量
     */
    private float calculateTimeAdvancement(float amount, WeaponAPI weapon) {
        float timeAdvancement = amount;
        
        // 如果舰船存在且处于过载或排热状态，则加速闪烁
        if (weapon.getShip() != null && weapon.getShip().getFluxTracker().isOverloadedOrVenting()) {
            timeAdvancement = 2 * amount; // 双倍速度
        }
        
        return timeAdvancement;
    }
    
    /**
     * 处理闪烁逻辑（考虑MAINGUN状态）
     * @param timeAdvancement 时间推进量
     * @param weapon 武器对象
     * @param hasActiveMaingun 是否有活跃的MAINGUN武器
     */
    private void processBlinkerLogic(float timeAdvancement, WeaponAPI weapon, boolean hasActiveMaingun) {
        if (hasActiveMaingun) {
            // MAINGUN激活时，强制LED进入满亮状态
            if (!blinker.isFadedIn() || blinker.getBrightness() < 1.0f) {
                // 如果当前不是满亮状态，强制设置为满亮度并设置为空闲状态
                blinker.forceIn(); // 强制设置为满亮度（亮度=1.0，状态=IDLE）
            }
            // 重置计时器，这样当MAINGUN停止激活时会有正常的间隔等待
            timer = new IntervalUtil(DEFAULT_INTERVAL, DEFAULT_INTERVAL);
        } else {
            // MAINGUN未激活时，执行正常的闪烁逻辑
            if (blinker.isFadedOut()) {
                // 如果闪烁器已渐出，则推进计时器
                timer.advance(timeAdvancement);
                if (timer.intervalElapsed()) {
                    // 计时器到期，开始新的闪烁周期
                    blinker.fadeIn();
                }
            } else {
                // 如果正在闪烁过程中，则更新亮度
                blinker.advance(timeAdvancement);
            }
        }
        
        // 无论在哪种状态下，都要更新武器外观和光效
        updateWeaponSprite(weapon);
        updateLight(weapon);
    }
    
    /**
     * 更新武器精灵的透明度
     * @param weapon 武器对象
     */
    private void updateWeaponSprite(WeaponAPI weapon) {
        Color currentColor = weapon.getSprite().getColor();
        int alpha = (int) (blinker.getBrightness() * 255);
        
        // 创建新颜色，保持RGB不变，只改变透明度
        Color newColor = new Color(
            currentColor.getRed(), 
            currentColor.getGreen(), 
            currentColor.getBlue(), 
            alpha
        );
        
        weapon.getSprite().setColor(newColor);
    }
    
    /**
     * 更新光源效果
     * @param weapon 武器对象
     */
    private void updateLight(WeaponAPI weapon) {
        if (withLight && light != null) {
            // 根据闪烁亮度计算光源强度和大小
            float brightness = blinker.getBrightness();
            float intensity = DEFAULT_LIGHT_INTENSITY + DEFAULT_LIGHT_INTENSITY * brightness;
            float size = DEFAULT_LIGHT_SIZE + DEFAULT_LIGHT_SIZE * brightness;
            
            // 设置光源属性
            if (glowColor != null) {
                light.setColor(glowColor);
            }
            light.setIntensity(intensity);
            light.setSize(size);
            light.setLocation(weapon.getLocation());
        }
    }
    
    /**
     * 处理MAINGUN_前缀武器槽位的帧动画（带过渡效果）
     * 遍历舰船上的MAINGUN_武器，根据其状态调整当前武器的帧动画
     * 动态支持任意帧数：第0帧（非激活）-> 中间帧（过渡）-> 最后一帧（激活）
     * @param timeAdvancement 时间推进量
     * @param weapon 当前武器对象
     * @param hasActiveMaingun 是否有活跃的MAINGUN武器（已检查过）
     */
    private void processMaingunFrameAnimation(float timeAdvancement, WeaponAPI weapon, boolean hasActiveMaingun) {
        // 确保武器有所属舰船
        if (weapon.getShip() == null) {
            return;
        }
        
        // 确定目标帧（使用动态检测的帧）
        int newTargetFrame = hasActiveMaingun ? activeFrame : inactiveFrame;
        
        // 如果目标帧发生变化，更新目标
        if (newTargetFrame != targetFrame) {
            targetFrame = newTargetFrame;
        }
        
        // 处理帧过渡逻辑
        handleFrameTransition(timeAdvancement, weapon);
    }
    
    /**
     * 检查是否有活跃的MAINGUN武器
     * @param weapon 当前武器对象
     * @return 是否有活跃的MAINGUN武器
     */
    private boolean checkForActiveMaingun(WeaponAPI weapon) {
        // 遍历舰船上的所有武器，查找MAINGUN_前缀的武器
        for (WeaponAPI shipWeapon : weapon.getShip().getAllWeapons()) {
            if (shipWeapon.getSlot() != null && shipWeapon.getSlot().getId() != null) {
                String slotId = shipWeapon.getSlot().getId();
                
                // 检查是否为MAINGUN_前缀的武器槽位
                if (slotId.startsWith("MAINGUN_")) {
                    // 检查该MAINGUN武器是否处于主动充能状态
                    // 条件：充能等级大于0，并且武器当前不处于冷却状态
                    if (shipWeapon.getChargeLevel() > 0 && 
                        (shipWeapon.getCooldownRemaining() == 0 || shipWeapon.isInBurst())) {
                        return true; // 找到一个活跃的MAINGUN就足够了
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * 处理帧过渡逻辑
     * @param timeAdvancement 时间推进量
     * @param weapon 武器对象
     */
    private void handleFrameTransition(float timeAdvancement, WeaponAPI weapon) {
        // 如果当前帧已经是目标帧，无需过渡
        if (currentFrame == targetFrame) {
            return;
        }
        
        // 更新帧计时器
        frameTimer += timeAdvancement * weaponFrameRate;
        
        // 检查是否需要切换到下一帧
        if (frameTimer >= 1.0f) {
            frameTimer = 0f;
            
            // 确定下一帧
            int nextFrame = calculateNextFrame();
            
            // 切换到下一帧
            currentFrame = nextFrame;
            weapon.getAnimation().setFrame(currentFrame);
        }
    }
    
    /**
     * 计算下一帧（动态支持任意帧数）
     * @return 下一帧的索引
     */
    private int calculateNextFrame() {
        // 动态过渡：从非激活帧到激活帧，或反向
        // 例如：0 -> 1 -> 2 -> ... -> maxFrame 或 maxFrame -> ... -> 2 -> 1 -> 0
        
        if (currentFrame < targetFrame) {
            // 向上过渡：逐帧递增到目标帧
            return currentFrame + 1;
        } else if (currentFrame > targetFrame) {
            // 向下过渡：逐帧递减到目标帧
            return currentFrame - 1;
        }
        
        // 已经是目标帧
        return currentFrame;
    }
}
