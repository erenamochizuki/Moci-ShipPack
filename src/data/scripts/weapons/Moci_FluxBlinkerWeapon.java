package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.FaderUtil;
import com.fs.starfarer.api.util.IntervalUtil;
import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;

public class Moci_FluxBlinkerWeapon implements EveryFrameWeaponEffectPlugin {
    
    // 初始化标志，确保只执行一次初始化
    private boolean once = true;
    
    // 闪烁淡入淡出控制器
    private FaderUtil blinker;
    
    // 闪烁间隔计时器
    private IntervalUtil timer;
    
    // 是否启用光照效果（暂未使用）
    private boolean withL = false;
    
    // 光照对象（暂未使用）
    private StandardLight light;
    
    // 武器原始颜色
    private Color c;
    
    // 闪烁间隔配置标签
    private static final String SHORT_INTERVAL_BLINKER = "short_interval_blinker";
    private static final String LONG_INTERVAL_BLINKER = "long_interval_blinker";
    private static final String RAPID_BLINKER = "rapid_blinker";
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 初始化配置（只执行一次）
        if (once) {
            initializeWeaponBlinker(weapon);
            once = false;
        }
        
        // 计算时间推进速度
        float timeAdvancement = calculateTimeAdvancement(amount, weapon);
        
        // 处理闪烁逻辑
        processBlinkerLogic(timeAdvancement, weapon);
    }
    
    /**
     * 初始化武器闪烁器配置
     * @param weapon 武器对象
     */
    private void initializeWeaponBlinker(WeaponAPI weapon) {
        // 获取武器原始发光颜色
        c = weapon.getSpec().getGlowColor();
        
        // 根据标签设置闪烁间隔
        float interval = getBlinkInterval(weapon);
        timer = new IntervalUtil(interval, interval);
        
        // 根据标签设置闪烁持续时间
        float blinkTime = getBlinkDuration(weapon);
        
        // 创建淡入淡出控制器
        // 参数：最小值, 淡入时间, 淡出时间, 是否循环, 是否从淡入开始
        blinker = new FaderUtil(0f, blinkTime / 2f, blinkTime / 2f, false, true);
        blinker.fadeIn(); // 开始淡入
    }
    
    /**
     * 根据武器标签获取闪烁间隔
     * @param weapon 武器对象
     * @return 闪烁间隔（秒）
     */
    private float getBlinkInterval(WeaponAPI weapon) {
        if (weapon.getSpec().getTags().contains(SHORT_INTERVAL_BLINKER)) {
            return 2f; // 短间隔：2秒
        } else if (weapon.getSpec().getTags().contains(LONG_INTERVAL_BLINKER)) {
            return 8f; // 长间隔：8秒
        } else {
            return 4f; // 默认间隔：4秒
        }
    }
    
    /**
     * 根据武器标签获取闪烁持续时间
     * @param weapon 武器对象
     * @return 闪烁持续时间（秒）
     */
    private float getBlinkDuration(WeaponAPI weapon) {
        if (weapon.getSpec().getTags().contains(RAPID_BLINKER)) {
            return 0.8f; // 快速闪烁：0.8秒
        } else {
            return 2f; // 默认持续时间：2秒
        }
    }
    
    /**
     * 计算时间推进速度
     * 考虑舰船状态对闪烁速度的影响
     * @param amount 基础时间增量
     * @param weapon 武器对象
     * @return 调整后的时间增量
     */
    private float calculateTimeAdvancement(float amount, WeaponAPI weapon) {
        float advancement = amount;
        
        // 检查舰船是否存在
        if (weapon.getShip() != null) {
            // 如果舰船正在过载或散热，闪烁速度加倍
            if (weapon.getShip().getFluxTracker().isOverloadedOrVenting()) {
                advancement = 2 * amount;
            }
        }
        
        return advancement;
    }
    
    /**
     * 处理闪烁逻辑
     * @param timeAdvancement 时间推进量
     * @param weapon 武器对象
     */
    private void processBlinkerLogic(float timeAdvancement, WeaponAPI weapon) {
        if (blinker.isFadedOut()) {
            // 闪烁器已淡出，等待下次闪烁
            processWaitingState(timeAdvancement, weapon);
        } else {
            // 闪烁器正在淡入/淡出，更新武器透明度
            processBlinkingState(timeAdvancement, weapon);
        }
    }
    
    /**
     * 处理等待状态（闪烁器已淡出）
     * @param timeAdvancement 时间推进量
     * @param weapon 武器对象
     */
    private void processWaitingState(float timeAdvancement, WeaponAPI weapon) {
        // 根据能量流量等级调整等待时间
        // 能量流量越高，闪烁越频繁
        float fluxMultiplier = 1f + weapon.getShip().getFluxLevel();
        timer.advance(timeAdvancement * fluxMultiplier);
        
        // 计时器到期，开始新的闪烁
        if (timer.intervalElapsed()) {
            blinker.fadeIn();
        }
    }
    
    /**
     * 处理闪烁状态（闪烁器正在淡入/淡出）
     * @param timeAdvancement 时间推进量
     * @param weapon 武器对象
     */
    private void processBlinkingState(float timeAdvancement, WeaponAPI weapon) {
        // 推进闪烁器状态
        blinker.advance(timeAdvancement);
        
        // 计算基于能量流量的额外透明度
        float fluxAlpha = 0.3f * weapon.getShip().getFluxLevel();
        
        // 计算最终透明度
        // 闪烁器亮度 * 0.7 + 能量流量透明度
        float finalAlpha = blinker.getBrightness() * 0.7f + fluxAlpha;
        
        // 更新武器精灵颜色和透明度
        updateWeaponColor(weapon, finalAlpha);
    }
    
    /**
     * 更新武器颜色和透明度
     * @param weapon 武器对象
     * @param alpha 透明度值 (0-1)
     */
    private void updateWeaponColor(WeaponAPI weapon, float alpha) {
        Color currentColor = weapon.getSprite().getColor();
        
        // 创建新颜色，保持RGB值不变，只修改透明度
        Color newColor = new Color(
            currentColor.getRed(),
            currentColor.getGreen(), 
            currentColor.getBlue(),
            (int) (alpha * 255) // 将0-1的透明度转换为0-255的整数值
        );
        
        weapon.getSprite().setColor(newColor);
    }
}