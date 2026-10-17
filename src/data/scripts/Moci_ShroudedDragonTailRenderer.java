package data.scripts;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.util.vector.Vector2f;

/**
 * ShroudedDragon 尾部渲染器
 */
public class Moci_ShroudedDragonTailRenderer implements CombatLayeredRenderingPlugin {
    private static final float TAIL_TIP_CENTER_RATIO = 0.14f; // 尾端旋转中心在贴图顶部靠下14%
    
    // 每个舰船的精灵和长度数据
    private Map<ShipAPI, SpriteAPI[]> shipSprites;
    private Map<ShipAPI, float[]> shipLengths;
    private ConcurrentHashMap<ShipAPI, TailState> tailStates;
    
    // 视口剔除的边距（像素）
    private static final float VIEWPORT_MARGIN = 500f;

    public Moci_ShroudedDragonTailRenderer(
            Map<ShipAPI, SpriteAPI[]> shipSprites,
            Map<ShipAPI, float[]> shipLengths,
            ConcurrentHashMap<ShipAPI, TailState> tailStates) {
        this.shipSprites = shipSprites;
        this.shipLengths = shipLengths;
        this.tailStates = tailStates;
    }

    public static class SegmentData {
        public Vector2f position = new Vector2f();
        public float angle = 0f;
        public float angularVelocity = 0f;  // 用于弹性效果
        public float compression = 0f;      // 压缩状态 (0=最小重叠, 1=最大重叠)
    }

    public static class TailState {
        public List<SegmentData> segments = new ArrayList<>();
    }

    @Override
    public void init(CombatEntityAPI entity) {}

    @Override
    public void advance(float amount) {}

    private float getSegmentLength(ShipAPI ship, int index) {
        float[] lengths = shipLengths.get(ship);
        if (lengths != null && index >= 0 && index < lengths.length) {
            return lengths[index];
        }
        return 0f;
    }

    private SpriteAPI getSegmentSprite(ShipAPI ship, int index) {
        SpriteAPI[] sprites = shipSprites.get(ship);
        if (sprites != null && index >= 0 && index < sprites.length) {
            return sprites[index];
        }
        return null;
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (shipSprites == null || tailStates == null || tailStates.isEmpty()) {
            return;
        }
        if (!getActiveLayers().contains(layer)) {
            return;
        }
        
        // 获取视口边界用于剔除
        float vpX = viewport.getLLX();
        float vpY = viewport.getLLY();
        float vpW = viewport.getVisibleWidth();
        float vpH = viewport.getVisibleHeight();
        
        // 使用线程安全的方式遍历
        for (Map.Entry<ShipAPI, TailState> entry : tailStates.entrySet()) {
            ShipAPI ship = entry.getKey();
            TailState state = entry.getValue();
            
            // 基本有效性检查
            if (ship == null || !ship.isAlive() || ship.isHulk()) {
                continue;
            }
            if (state == null || state.segments == null || state.segments.isEmpty()) {
                continue;
            }
            
            // 检查这个舰船是否有加载的精灵
            if (!shipSprites.containsKey(ship)) {
                continue;
            }
            
            // 视口剔除：检查舰船是否在视口范围内
            Vector2f shipLoc = ship.getLocation();
            float shipRadius = ship.getCollisionRadius() + 200f;  // 额外边距给尾部
            if (!isInViewport(shipLoc.x, shipLoc.y, shipRadius, vpX, vpY, vpW, vpH)) {
                continue;
            }
            
            // 获取舰船颜色和透明度（只获取一次）
            SpriteAPI shipSprite = ship.getSpriteAPI();
            if (shipSprite == null) continue;
            java.awt.Color shipColor = shipSprite.getColor();
            float baseAlpha = viewport.getAlphaMult() * shipSprite.getAlphaMult();
            
            // 获取这个舰船的体节数量
            int numSegments = state.segments.size();
            
            // 从尾部末端开始渲染，确保正确的层叠顺序
            for (int i = numSegments - 1; i >= 0; i--) {
                SegmentData segment = state.segments.get(i);
                if (segment == null || segment.position == null) continue;
                
                SpriteAPI sprite = getSegmentSprite(ship, i);
                if (sprite == null) continue;
                
                // 设置旋转中心
                // 尾端（最后一节）：旋转中心在贴图顶部靠下14%
                // 其他节段：旋转中心在贴图几何中心
                if (i == numSegments - 1) {
                    sprite.setCenter(sprite.getWidth() / 2f, sprite.getHeight() * (1f - TAIL_TIP_CENTER_RATIO));
                } else {
                    sprite.setCenter(sprite.getWidth() / 2f, sprite.getHeight() / 2f);
                }
                
                sprite.setAngle(segment.angle + 90f);
                sprite.setColor(shipColor);
                sprite.setAlphaMult(baseAlpha);
                sprite.renderAtCenter(segment.position.x, segment.position.y);
            }
        }
    }
    
    /**
     * 检查点是否在视口范围内（带边距）
     */
    private boolean isInViewport(float x, float y, float radius, float vpX, float vpY, float vpW, float vpH) {
        float margin = VIEWPORT_MARGIN + radius;
        return x >= vpX - margin && x <= vpX + vpW + margin &&
               y >= vpY - margin && y <= vpY + vpH + margin;
    }

    @Override
    public float getRenderRadius() {
        return Float.MAX_VALUE;
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.BELOW_SHIPS_LAYER);
    }

    @Override
    public boolean isExpired() {
        return false;
    }

    @Override
    public void cleanup() {
        this.shipSprites = null;
        this.shipLengths = null;
        this.tailStates = null;
    }
}
