package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;

/**
 * 精灵图帧动画渲染器
 * 
 * 用于渲染多帧精灵图动画（sprite sheet animation）
 * 支持宫格布局的精灵图（如2x2、3x3等）
 * 
 * 使用方法：
 * 1. 创建渲染器实例，指定精灵图路径、宫格布局、大小等参数
 * 2. 通过 engine.addLayeredRenderingPlugin() 添加到战斗引擎
 * 3. 渲染器会自动播放帧动画并在结束后自动移除
 */
public class Moci_SpriteAnimationRenderer extends BaseCombatLayeredRenderingPlugin {
    
    // ========== 动画参数 ==========
    private final Vector2f location;           // 动画位置
    private final Vector2f velocity;           // 动画速度（用于移动效果）
    private final SpriteAPI sprite;            // 精灵图
    private final int rows;                    // 宫格行数
    private final int cols;                    // 宫格列数
    private final int totalFrames;             // 总帧数
    private final float frameDuration;         // 每帧持续时间
    private final float size;                  // 渲染大小
    private final float rotation;              // 旋转角度
    private final Color tintColor;             // 着色颜色
    private final float alpha;                 // 透明度
    private final boolean additive;            // 是否使用加法混合
    
    // ========== 动画状态 ==========
    private float elapsed = 0f;                // 已过时间
    private int currentFrame = 0;              // 当前帧
    private boolean finished = false;          // 是否完成
    
    /**
     * 创建精灵图帧动画渲染器
     * 
     * @param location 动画位置
     * @param velocity 动画速度（可以为null表示静止）
     * @param spriteCategory 精灵图类别（如"fx"）
     * @param spriteName 精灵图名称
     * @param rows 宫格行数
     * @param cols 宫格列数
     * @param totalFrames 总帧数（如果小于rows*cols，则只播放前N帧）
     * @param duration 动画总持续时间（秒）
     * @param size 渲染大小（像素）
     * @param rotation 旋转角度（度）
     * @param tintColor 着色颜色（null表示不着色）
     * @param alpha 透明度（0-1）
     * @param additive 是否使用加法混合模式
     */
    public Moci_SpriteAnimationRenderer(
            Vector2f location,
            Vector2f velocity,
            String spriteCategory,
            String spriteName,
            int rows,
            int cols,
            int totalFrames,
            float duration,
            float size,
            float rotation,
            Color tintColor,
            float alpha,
            boolean additive) {
        
        this.location = new Vector2f(location);
        this.velocity = velocity != null ? new Vector2f(velocity) : new Vector2f(0, 0);
        this.rows = rows;
        this.cols = cols;
        this.totalFrames = totalFrames;
        this.frameDuration = duration / totalFrames;
        this.size = size;
        this.rotation = rotation;
        this.tintColor = tintColor;
        this.alpha = alpha;
        this.additive = additive;
        
        // 加载精灵图
        this.sprite = Global.getSettings().getSprite(spriteCategory, spriteName);
        
        // 设置渲染层级
        this.layer = CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER;
        
        // 设置混合模式
        if (additive) {
            sprite.setAdditiveBlend();
        }
        
        // 初始化第一帧
        updateFrame();
    }
    
    /**
     * 更新当前帧的纹理坐标和精灵图属性
     */
    private void updateFrame() {
        if (sprite == null) {
            return;
        }
        
        // 计算当前帧在宫格中的位置
        int row = currentFrame / cols;
        int col = currentFrame % cols;
        
        // 计算单个帧的实际像素大小（假设精灵图是正方形）
        float frameWidth = sprite.getWidth() / cols;
        float frameHeight = sprite.getHeight() / rows;
        
        // 设置精灵图的渲染大小
        sprite.setSize(size, size);
        
        // 关键：设置center为当前帧在整个精灵图中的中心位置
        // 这样renderRegionAtCenter才能正确定位当前帧
        float centerX = (col + 0.5f) * frameWidth * (size / sprite.getWidth());
        float centerY = (row + 0.5f) * frameHeight * (size / sprite.getHeight());
        sprite.setCenter(centerX, centerY);
        
        // 设置旋转
        sprite.setAngle(rotation);
        
        // 设置颜色和透明度
        if (tintColor != null) {
            sprite.setColor(tintColor);
        }
        sprite.setAlphaMult(alpha);
    }
    
    @Override
    public void advance(float amount) {
        if (finished) {
            return;
        }
        
        // 只在游戏未暂停时推进动画
        if (!Global.getCombatEngine().isPaused()) {
            elapsed += amount;
            
            // 更新位置（如果有速度）
            location.x += velocity.x * amount;
            location.y += velocity.y * amount;
            
            // 计算当前应该显示的帧
            int newFrame = (int)(elapsed / frameDuration);
            
            // 检查是否需要更新帧
            if (newFrame != currentFrame && newFrame < totalFrames) {
                currentFrame = newFrame;
                updateFrame();
            }
            
            // 检查动画是否完成
            if (currentFrame >= totalFrames - 1 && elapsed >= frameDuration * totalFrames) {
                finished = true;
            }
        }
    }
    
    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (sprite != null && !finished) {
            // 计算当前帧在宫格中的位置
            int row = currentFrame / cols;
            int col = currentFrame % cols;
            
            // 计算纹理坐标（0-1范围）
            float texX = (float)col / cols;
            float texY = (float)row / rows;
            float texWidth = 1.0f / cols;
            float texHeight = 1.0f / rows;
            
            // 使用renderRegionAtCenter渲染指定区域
            sprite.renderRegionAtCenter(location.x, location.y, texX, texY, texWidth, texHeight);
        }
    }
    
    @Override
    public float getRenderRadius() {
        return size;
    }
    
    @Override
    public boolean isExpired() {
        return finished;
    }
    
    @Override
    public void init(CombatEntityAPI entity) {
        this.entity = entity;
        if (entity != null) {
            entity.getLocation().set(location);
        }
    }
}
