package data.scripts.weapons;

import java.awt.Color;

import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * LED效果插件 - 根据舰船幅能水平动态调整武器亮度和光效
 * 幅能水平越高，武器越亮（透明度越低），光效越强
 */
public class Moci_Engine_LED implements EveryFrameWeaponEffectPlugin {
    // 初始化标志
    private boolean once = true;
    
    // 是否启用光效
    private final boolean withLight = true;
    
    // 光源对象
    private StandardLight light;
    
    // 武器的发光颜色
    private Color glowColor;
    
    // 默认光效参数
    private static final float DEFAULT_LIGHT_INTENSITY = 0.8f;
    private static final float DEFAULT_LIGHT_SIZE = 150f;
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 首次运行时初始化
        if (once) {
            initialize(weapon);
            once = false;
        }
        
        // 检查舰船是否有效
        if (weapon.getShip() == null) {
            return;
        }
        
        // 如果启用了光效但舰船已死亡，清理光源
        if (withLight && !weapon.getShip().isAlive()) {
            cleanupLight();
            return;
        }
        
        // 获取当前幅能水平 (0~1)
        float fluxLevel = weapon.getShip().getFluxTracker().getFluxLevel();
        
        // 根据幅能水平更新武器透明度和光效
        updateWeaponAlpha(weapon, fluxLevel);
        updateLight(weapon, fluxLevel);
    }
    
    /**
     * 初始化组件
     * @param weapon 武器对象
     */
    private void initialize(WeaponAPI weapon) {
        // 获取武器的发光颜色
        glowColor = weapon.getSpec().getGlowColor();
        
        // 如果启用光效，创建光源
        if (withLight) {
            createLight(weapon);
        }
    }
    
    /**
     * 创建动态光源
     * @param weapon 武器对象
     */
    private void createLight(WeaponAPI weapon) {
        light = new StandardLight(
            weapon.getLocation(),
            new Vector2f(0, 0),
            new Vector2f(0, 0),
            weapon.getShip()
        );
        light.makePermanent();
        LightShader.addLight(light);
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
     * 根据幅能水平更新武器精灵的透明度
     * @param weapon 武器对象
     * @param fluxLevel 幅能水平 (0~1)
     */
    private void updateWeaponAlpha(WeaponAPI weapon, float fluxLevel) {
        
        // 获取当前颜色，只修改 alpha 通道
        Color currentColor = weapon.getSprite().getColor();
        int alpha = (int) (fluxLevel * 255);
        Color newColor = new Color(
            currentColor.getRed(),
            currentColor.getGreen(),
            currentColor.getBlue(),
            alpha
        );
        
        weapon.getSprite().setColor(newColor);
    }
    
    /**
     * 根据幅能水平更新光源效果
     * @param weapon 武器对象
     * @param fluxLevel 幅能水平 (0~1)
     */
    private void updateLight(WeaponAPI weapon, float fluxLevel) {
        if (!withLight || light == null) return;
        
        // 光效强度与大小随幅能线性变化
        float intensity = DEFAULT_LIGHT_INTENSITY * fluxLevel;
        float size = DEFAULT_LIGHT_SIZE * fluxLevel;
        
        if (glowColor != null) {
            light.setColor(glowColor);
        }
        light.setIntensity(intensity);
        light.setSize(size);
        light.setLocation(weapon.getLocation());
    }
}