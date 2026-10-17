package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.List;

/**
 * BoxUtil SpriteEntity 帧动画工具类
 * 提供简单易用的帧动画播放功能
 * 
 * 关于帧顺序：
 * - topToBottom = true：从上到下排列
 * - topToBottom = false：从下到上排列
 */
public class Moci_SpriteAnimationUtil {
    
    /**
     * 帧顺序枚举
     */
    public enum FrameOrder {
        /** 从上到下（常见图像编辑软件的顺序）：0→1→2→3→4→5→6→7 */
        TOP_TO_BOTTOM,
        /** 从下到上（先播放下面的行）：4→5→6→7→0→1→2→3 */
        BOTTOM_TO_TOP
    }
    
    /**
     * 计算帧索引映射
     * 
     * BoxUtil的瓦片索引从左下角开始，从左到右、从下到上排列
     * 
     * 对于2行4列的精灵图：
     * 精灵图布局（标准，从上到下）：
     * [0] [1] [2] [3]  ← 第1行（顶部）
     * [4] [5] [6] [7]  ← 第2行（底部）
     * 
     * BoxUtil物理索引（从左下角开始）：
     * [4] [5] [6] [7]  ← 物理帧0,1,2,3（底部）
     * [0] [1] [2] [3]  ← 物理帧4,5,6,7（顶部）
     * 
     * @param logicalFrame 逻辑帧号（0开始）
     * @param rows 网格行数
     * @param cols 网格列数
     * @param order 帧顺序
     * @return BoxUtil的物理帧号
     */
    private static int mapFrameIndex(int logicalFrame, int rows, int cols, FrameOrder order) {
        if (order == FrameOrder.BOTTOM_TO_TOP) {
            return logicalFrame;
        }
        
        // TOP_TO_BOTTOM: 反转行顺序
        int logicalRow = logicalFrame / cols;
        int col = logicalFrame % cols;
        int physicalRow = (rows - 1) - logicalRow;
        return physicalRow * cols + col;
    }
    
    /**
     * 播放帧动画（完整参数版本）
     * 
     * @param engine 战斗引擎
     * @param spriteCategory 精灵图类别（如"fx"）
     * @param spriteName 精灵图名称
     * @param location 位置
     * @param facing 朝向（度）
     * @param width 宽度
     * @param height 高度
     * @param rows 网格行数
     * @param cols 网格列数
     * @param totalFrames 总帧数
     * @param duration 持续时间（秒）
     * @param additive 是否使用加法混合
     * @param layer 渲染层级
     * @param frameOrder 帧顺序（TOP_TO_BOTTOM或BOTTOM_TO_TOP）
     * @return 创建的SpriteEntity（可用于进一步配置）
     */
    public static SpriteEntity playAnimation(
            CombatEngineAPI engine,
            String spriteCategory,
            String spriteName,
            Vector2f location,
            float facing,
            float width,
            float height,
            int rows,
            int cols,
            int totalFrames,
            float duration,
            boolean additive,
            CombatEngineLayers layer,
            FrameOrder frameOrder) {
        
        // 创建SpriteEntity
        SpriteAPI sprite = Global.getSettings().getSprite(spriteCategory, spriteName);
        SpriteEntity animation = new SpriteEntity(sprite, false);
        
        // 配置网格
        animation.setTileSize(cols, rows);
        animation.setCurrentTileCount(totalFrames);
        
        // 设置初始帧
        int initialPhysicalFrame = mapFrameIndex(0, rows, cols, frameOrder);
        animation.setStartingFormIndex(initialPhysicalFrame);
        
        // 设置尺寸
        animation.setBaseSizePerTiles(width, height);
        
        // 设置位置和朝向
        animation.setLocation(location);
        animation.setStateVanilla(location, facing);
        
        // 混合模式
        if (additive) {
            animation.setAdditiveBlend();
        } else {
            animation.setNormalBlend();
        }
        
        // 渲染层级
        animation.setLayer(layer);
        
        // 定时器
        animation.setGlobalTimer(0.0f, duration, 0.0f);
        
        // 添加到渲染管理器
        CombatRenderingManager.addEntity(animation);
        
        // 创建并注册控制器
        SpriteAnimationController controller = new SpriteAnimationController(
            animation, 
            rows,
            cols,
            totalFrames, 
            duration,
            frameOrder
        );
        engine.addPlugin(controller);
        
        return animation;
    }
    
    /**
     * 播放帧动画（完整参数版本 - 默认从上到下）
     * 
     * @param engine 战斗引擎
     * @param spriteCategory 精灵图类别（如"fx"）
     * @param spriteName 精灵图名称
     * @param location 位置
     * @param facing 朝向（度）
     * @param width 宽度
     * @param height 高度
     * @param rows 网格行数
     * @param cols 网格列数
     * @param totalFrames 总帧数
     * @param duration 持续时间（秒）
     * @param additive 是否使用加法混合
     * @param layer 渲染层级
     * @return 创建的SpriteEntity（可用于进一步配置）
     */
    public static SpriteEntity playAnimation(
            CombatEngineAPI engine,
            String spriteCategory,
            String spriteName,
            Vector2f location,
            float facing,
            float width,
            float height,
            int rows,
            int cols,
            int totalFrames,
            float duration,
            boolean additive,
            CombatEngineLayers layer) {
        
        // 默认使用从上到下的顺序（更符合直觉）
        return playAnimation(
            engine, spriteCategory, spriteName,
            location, facing, width, height,
            rows, cols, totalFrames,
            duration, additive, layer,
            FrameOrder.TOP_TO_BOTTOM
        );
    }
    
    /**
     * 播放帧动画（简化版本 - 正方形尺寸）
     * 
     * @param engine 战斗引擎
     * @param spriteCategory 精灵图类别
     * @param spriteName 精灵图名称
     * @param location 位置
     * @param facing 朝向（度）
     * @param size 尺寸（正方形）
     * @param rows 网格行数
     * @param cols 网格列数
     * @param totalFrames 总帧数
     * @param duration 持续时间（秒）
     * @param additive 是否使用加法混合
     * @param layer 渲染层级
     * @return 创建的SpriteEntity
     */
    public static SpriteEntity playAnimation(
            CombatEngineAPI engine,
            String spriteCategory,
            String spriteName,
            Vector2f location,
            float facing,
            float size,
            int rows,
            int cols,
            int totalFrames,
            float duration,
            boolean additive,
            CombatEngineLayers layer) {
        
        return playAnimation(
            engine, spriteCategory, spriteName,
            location, facing,
            size, size,  // 正方形
            rows, cols, totalFrames,
            duration, additive, layer
        );
    }
    
    /**
     * 播放4x4网格动画（最简化版本）
     * 
     * @param engine 战斗引擎
     * @param spriteCategory 精灵图类别
     * @param spriteName 精灵图名称
     * @param location 位置
     * @param facing 朝向（度）
     * @param size 尺寸
     * @param duration 持续时间（秒）
     * @return 创建的SpriteEntity
     */
    public static SpriteEntity play4x4Animation(
            CombatEngineAPI engine,
            String spriteCategory,
            String spriteName,
            Vector2f location,
            float facing,
            float size,
            float duration) {
        
        return playAnimation(
            engine, spriteCategory, spriteName,
            location, facing, size,
            4, 4, 16,  // 4x4网格，16帧
            duration,
            true,  // 加法混合（默认）
            CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER
        );
    }
    
    /**
     * 播放4x4网格动画（可指定混合模式）
     * 
     * @param engine 战斗引擎
     * @param spriteCategory 精灵图类别
     * @param spriteName 精灵图名称
     * @param location 位置
     * @param facing 朝向（度）
     * @param size 尺寸
     * @param duration 持续时间（秒）
     * @param additive 是否使用加法混合
     * @return 创建的SpriteEntity
     */
    public static SpriteEntity play4x4Animation(
            CombatEngineAPI engine,
            String spriteCategory,
            String spriteName,
            Vector2f location,
            float facing,
            float size,
            float duration,
            boolean additive) {
        
        return playAnimation(
            engine, spriteCategory, spriteName,
            location, facing, size,
            4, 4, 16,  // 4x4网格，16帧
            duration,
            additive,  // 可指定混合模式
            CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER
        );
    }
    
    /**
     * 播放2x2网格动画
     * 
     * @param engine 战斗引擎
     * @param spriteCategory 精灵图类别
     * @param spriteName 精灵图名称
     * @param location 位置
     * @param facing 朝向（度）
     * @param size 尺寸
     * @param duration 持续时间（秒）
     * @return 创建的SpriteEntity
     */
    public static SpriteEntity play2x2Animation(
            CombatEngineAPI engine,
            String spriteCategory,
            String spriteName,
            Vector2f location,
            float facing,
            float size,
            float duration) {
        
        return playAnimation(
            engine, spriteCategory, spriteName,
            location, facing, size,
            2, 2, 4,  // 2x2网格，4帧
            duration,
            true,  // 加法混合
            CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER
        );
    }
    
    /**
     * 播放3x3网格动画
     * 
     * @param engine 战斗引擎
     * @param spriteCategory 精灵图类别
     * @param spriteName 精灵图名称
     * @param location 位置
     * @param facing 朝向（度）
     * @param size 尺寸
     * @param duration 持续时间（秒）
     * @return 创建的SpriteEntity
     */
    public static SpriteEntity play3x3Animation(
            CombatEngineAPI engine,
            String spriteCategory,
            String spriteName,
            Vector2f location,
            float facing,
            float size,
            float duration) {
        
        return playAnimation(
            engine, spriteCategory, spriteName,
            location, facing, size,
            3, 3, 9,  // 3x3网格，9帧
            duration,
            true,  // 加法混合
            CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER
        );
    }
    
    /**
     * 帧动画控制器
     * 负责自动播放SpriteEntity的帧动画
     */
    private static class SpriteAnimationController implements EveryFrameCombatPlugin {
        private final SpriteEntity animation;
        private final Vector2f location;  // 保存位置用于显示文字
        private final int rows;
        private final int cols;
        private final int totalFrames;
        private final float frameDuration;  // 每帧持续时间
        private final FrameOrder frameOrder;
        private float elapsed = 0.0f;
        private int currentLogicalFrame = 0;  // 当前逻辑帧（从上到下计数）
        private int currentPhysicalFrame = 0;  // 当前物理帧
        private boolean finished = false;
        
        public SpriteAnimationController(SpriteEntity animation, int rows, int cols, 
                                        int totalFrames, float totalDuration, FrameOrder frameOrder) {
            this.animation = animation;
            this.location = new Vector2f(animation.getModelMatrix().m30, animation.getModelMatrix().m31);
            this.rows = rows;
            this.cols = cols;
            this.totalFrames = totalFrames;
            this.frameDuration = totalDuration / totalFrames;
            this.frameOrder = frameOrder;
            
            // 设置初始物理帧
            this.currentPhysicalFrame = mapFrameIndex(0, rows, cols, frameOrder);
        }
        
        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            if (finished || animation.hasDelete()) {
                return;
            }
            
            // 检查游戏是否暂停
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine != null && engine.isPaused()) {
                return;  // 暂停时不更新动画
            }
            
            elapsed += amount;
            
            // 计算当前应该显示的逻辑帧
            int targetLogicalFrame = (int) (elapsed / frameDuration);
            
            // 如果需要切换到下一帧
            while (currentLogicalFrame < targetLogicalFrame && currentLogicalFrame < totalFrames - 1) {
                currentLogicalFrame++;
                currentPhysicalFrame = mapFrameIndex(currentLogicalFrame, rows, cols, frameOrder);
                animation.setStartingFormIndex(currentPhysicalFrame);
            }
            
            // 动画播放完毕
            if (currentLogicalFrame >= totalFrames - 1) {
                finished = true;
            }
        }
        
        @Override
        public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        }
        
        @Override
        public void renderInWorldCoords(ViewportAPI viewport) {
        }
        
        @Override
        public void renderInUICoords(ViewportAPI viewport) {
        }
        
        @Override
        public void init(CombatEngineAPI engine) {
        }
    }
}
