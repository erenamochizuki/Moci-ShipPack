package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.awt.Color;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

/**
 * EMP爆炸光环效果
 * 用于渲染EMP爆炸的视觉效果
 * 
 * 特点：
 * - 双层旋转光环（核心+边缘）
 * - 淡入淡出动画
 * - 可配置大小、持续时间、旋转速度
 * 
 * 使用方法：
 * <pre>
 * EMPBlast empBlast = new EMPBlast(point, 1.25f, 350f, 0.65f, 25f, 0.2f, 0.75f);
 * empBlast.init(Global.getCombatEngine().addLayeredRenderingPlugin(empBlast));
 * </pre>
 */
public class Moci_EMPBlast extends BaseCombatLayeredRenderingPlugin {
    // 核心与边缘的固定色调：核心更浅，边缘更深
    private static final Color CORE_TINT = new Color(165, 220, 255, 255);
    private static final Color FRINGE_TINT = new Color(90, 155, 235, 255);

    private final Vector2f point;              // 爆炸位置
    private final float brightness;            // 亮度
    private final float radius;                // 最大半径
    private final float duration;              // 持续时间
    private final float rotationRate;          // 旋转速度（度/秒）
    private final float coreSizeFractionStart; // 核心起始尺寸比例
    private final float coreSizeFractionEnd;   // 核心结束尺寸比例
    private final SpriteAPI core;              // 核心精灵
    private final SpriteAPI fringe;            // 边缘精灵
    private final boolean flip;                // 是否翻转旋转方向

    private float coreAngle;                   // 核心旋转角度
    private float fringeAngle;                 // 边缘旋转角度
    private float elapsed = 0f;                // 已过时间

    /**
     * 创建EMP爆炸效果
     * 
     * @param point 爆炸位置
     * @param brightness 亮度（0-1）
     * @param radius 最大半径
     * @param duration 持续时间（秒）
     * @param rotationRate 旋转速度（度/秒）
     * @param coreSizeFractionStart 核心起始尺寸比例（相对于radius）
     * @param coreSizeFractionEnd 核心结束尺寸比例（相对于radius）
     */
    public Moci_EMPBlast(Vector2f point, float brightness, float radius, float duration, 
                    float rotationRate, float coreSizeFractionStart, float coreSizeFractionEnd) {
        this.point = new Vector2f(point);
        this.brightness = brightness;
        this.radius = radius;
        this.duration = duration;
        this.rotationRate = rotationRate;
        this.coreSizeFractionStart = coreSizeFractionStart;
        this.coreSizeFractionEnd = coreSizeFractionEnd;

        // 设置渲染层级
        layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER;
        
        // 加载精灵
        core = Global.getSettings().getSprite("fx", "Moci_housya_d");
        fringe = Global.getSettings().getSprite("fx", "Moci_housya_f");
        
        // 设置加法混合模式（发光效果）
        core.setAdditiveBlend();
        fringe.setAdditiveBlend();
        core.setColor(CORE_TINT);
        fringe.setColor(FRINGE_TINT);
        
        // 随机翻转旋转方向
        flip = Math.random() > 0.5;

        // 随机初始角度
        coreAngle = MathUtils.getRandomNumberInRange(-180f, 180f);
        fringeAngle = MathUtils.getRandomNumberInRange(-180f, 180f);
    }

    @Override
    public void advance(float amount) {
        // 暂停时不更新
        if (!Global.getCombatEngine().isPaused()) {
            elapsed += amount;
        }

        // 旋转动画（核心和边缘反向旋转）
        float sign = flip ? 1f : -1f;
        coreAngle += sign * amount * rotationRate;
        fringeAngle -= sign * amount * rotationRate;
        
        // 计算动画进度（0-1）
        float level = Math.min(1f, Math.max(0f, elapsed / duration));
        
        // 核心尺寸：从起始比例到结束比例
        float coreSize = lerp(radius * coreSizeFractionStart, radius * coreSizeFractionEnd, level) * 2f;
        
        // 边缘尺寸：从0扩展到最大半径
        float fringeSize = lerp(0f, radius, level) * 2f;
        
        // 透明度：使用幂函数实现快速淡出
        float alpha = Math.min(1f, Math.max(0f, lerp(brightness, 0f, (float) Math.pow(level, 1.5))));

        // 更新核心精灵
        core.setAlphaMult(alpha);
        core.setAngle(coreAngle);
        core.setSize(coreSize, coreSize);
        
        // 更新边缘精灵
        fringe.setAlphaMult(alpha);
        fringe.setAngle(fringeAngle);
        fringe.setSize(fringeSize, fringeSize);
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        // 在爆炸位置渲染核心和边缘
        core.renderAtCenter(point.x, point.y);
        fringe.renderAtCenter(point.x, point.y);
    }

    @Override
    public float getRenderRadius() {
        return radius;
    }

    @Override
    public boolean isExpired() {
        return elapsed >= duration;
    }

    @Override
    public void init(CombatEntityAPI entity) {
        this.entity = entity;
        entity.getLocation().set(point.x, point.y);
        advance(0f);
    }
    
    /**
     * 线性插值
     * @param a 起始值
     * @param b 结束值
     * @param t 插值参数（0-1）
     * @return 插值结果
     */
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
