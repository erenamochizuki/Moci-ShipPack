package data.scripts;

import java.awt.Color;
import java.util.EnumSet;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

/**
 * 导弹防御雷达视觉效果渲染插件
 * 负责渲染雷达面板、扫描指针和扫描波纹效果
 */
public class Moci_RadarVisualPlugin extends BaseCombatLayeredRenderingPlugin {
    
    // ========== 渲染参数配置 ==========
    
    /** 雷达面板基础尺寸 */
    private static final float RADAR_PANEL_SIZE = 120f;
    
    /** 扫描指针尺寸 */
    private static final float RADAR_POINTER_SIZE = 100f;
    
    /** 扫描波纹最大尺寸 */
    private static final float SCAN_WAVE_MAX_SIZE = 300f;
    
    /** 雷达面板颜色 */
    private static final Color RADAR_PANEL_COLOR = new Color(100, 200, 255, 150);
    
    /** 扫描指针颜色 */
    private static final Color RADAR_POINTER_COLOR = new Color(100, 255, 100, 200);
    
    /** 扫描波纹颜色 */
    private static final Color SCAN_WAVE_COLOR = new Color(100, 200, 255, 100);
    
    // ========== 渲染状态 ==========
    
    /** 绑定的舰船 */
    private final ShipAPI ship;
    
    /** 雷达面板贴图 */
    private SpriteAPI radarPanelSprite;
    
    /** 扫描指针贴图 */
    private SpriteAPI radarPointerSprite;
    
    /** 扫描波纹贴图 */
    private SpriteAPI scanWaveSprite;
    
    /** 是否已加载贴图 */
    private boolean spritesLoaded = false;
    
    /** 雷达指针角度 */
    private float pointerAngle = 0f;
    
    /** 扫描波纹角度 */
    private float waveAngle = 0f;
    
    /** 扫描波纹缩放 */
    private float waveScale = 1f;
    
    /** 是否显示主动效果 */
    private boolean showActiveEffects = false;
    
    /** 是否显示扫描波纹 */
    private boolean showScanWave = false;
    
    /** 扫描波纹持续时间 */
    private float scanWaveTimer = 0f;
    
    public Moci_RadarVisualPlugin(ShipAPI ship) {
        this.ship = ship;
    }
    
    @Override
    public void advance(float amount) {
        if (Global.getCombatEngine().isPaused()) return;
        
        // 检查舰船状态
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }
        
        // 检查系统状态
        if (ship.getSystem() != null) {
            boolean systemActive = ship.getSystem().isActive() || ship.getSystem().isChargeup() || ship.getSystem().isChargedown();
            showActiveEffects = systemActive;
            
            // 更新雷达指针旋转
            if (systemActive) {
                pointerAngle += 120f * amount; // 每秒120度
                if (pointerAngle > 360f) {
                    pointerAngle -= 360f;
                }
            }
        }
        
        // 更新扫描波纹
        waveAngle += 30f * amount; // 每秒30度
        if (waveAngle > 360f) {
            waveAngle -= 360f;
        }
        
        // 扫描波纹计时器
        if (showScanWave) {
            scanWaveTimer += amount;
            waveScale = 1f + scanWaveTimer * 0.5f; // 逐渐放大
            
            if (scanWaveTimer > 2f) { // 2秒后消失
                showScanWave = false;
                scanWaveTimer = 0f;
                waveScale = 1f;
            }
        }
    }
    
    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (layer != CombatEngineLayers.ABOVE_SHIPS_LAYER) return;
        
        if (ship == null || !ship.isAlive() || ship.isHulk()) return;
        
        // 加载贴图
        if (!spritesLoaded) {
            loadSprites();
        }
        
        Vector2f shipLocation = ship.getLocation();
        if (!viewport.isNearViewport(shipLocation, SCAN_WAVE_MAX_SIZE)) return;
        
        // 渲染扫描波纹效果（被动扫描触发时显示）
        if (showScanWave && scanWaveSprite != null) {
            renderScanWave(shipLocation, viewport);
        }
        
        // 渲染雷达面板（主动状态时显示）
        if (showActiveEffects) {
            if (radarPanelSprite != null) {
                renderRadarPanel(shipLocation, viewport);
            }
            
            if (radarPointerSprite != null) {
                renderRadarPointer(shipLocation, viewport);
            }
        }
    }
    
    /**
     * 加载贴图资源
     */
    private void loadSprites() {
        try {
            // 使用游戏内置的贴图资源
            radarPanelSprite = Global.getSettings().getSprite("graphics/fx/explosion_ring.png");
            radarPointerSprite = Global.getSettings().getSprite("graphics/fx/beam_start.png");
            scanWaveSprite = Global.getSettings().getSprite("graphics/fx/explosion_ring.png");
            
            spritesLoaded = true;
            Global.getLogger(this.getClass()).info("雷达视觉效果贴图加载完成");
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error("雷达视觉效果贴图加载失败", e);
        }
    }
    
    /**
     * 渲染雷达面板
     */
    private void renderRadarPanel(Vector2f location, ViewportAPI viewport) {
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        
        // 设置颜色和透明度
        GL11.glColor4f(
            RADAR_PANEL_COLOR.getRed() / 255f,
            RADAR_PANEL_COLOR.getGreen() / 255f,
            RADAR_PANEL_COLOR.getBlue() / 255f,
            RADAR_PANEL_COLOR.getAlpha() / 255f
        );
        
        // 移动到舰船位置
        GL11.glTranslatef(location.x, location.y, 0f);
        
        // 绑定贴图
        radarPanelSprite.bindTexture();
        
        float size = RADAR_PANEL_SIZE / viewport.getViewMult();
        
        // 渲染四边形
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 0f);
        GL11.glVertex2f(-size / 2f, -size / 2f);
        GL11.glTexCoord2f(1f, 0f);
        GL11.glVertex2f(size / 2f, -size / 2f);
        GL11.glTexCoord2f(1f, 1f);
        GL11.glVertex2f(size / 2f, size / 2f);
        GL11.glTexCoord2f(0f, 1f);
        GL11.glVertex2f(-size / 2f, size / 2f);
        GL11.glEnd();
        
        GL11.glPopMatrix();
    }
    
    /**
     * 渲染雷达指针
     */
    private void renderRadarPointer(Vector2f location, ViewportAPI viewport) {
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        
        // 设置颜色和透明度
        GL11.glColor4f(
            RADAR_POINTER_COLOR.getRed() / 255f,
            RADAR_POINTER_COLOR.getGreen() / 255f,
            RADAR_POINTER_COLOR.getBlue() / 255f,
            RADAR_POINTER_COLOR.getAlpha() / 255f
        );
        
        // 移动到舰船位置并旋转
        GL11.glTranslatef(location.x, location.y, 0f);
        GL11.glRotatef(pointerAngle, 0f, 0f, 1f);
        
        // 绑定贴图
        radarPointerSprite.bindTexture();
        
        float size = RADAR_POINTER_SIZE / viewport.getViewMult();
        
        // 渲染扇形指针
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 0f);
        GL11.glVertex2f(-size / 8f, 0f);
        GL11.glTexCoord2f(1f, 0f);
        GL11.glVertex2f(size / 8f, 0f);
        GL11.glTexCoord2f(1f, 1f);
        GL11.glVertex2f(size / 8f, size);
        GL11.glTexCoord2f(0f, 1f);
        GL11.glVertex2f(-size / 8f, size);
        GL11.glEnd();
        
        GL11.glPopMatrix();
    }
    
    /**
     * 渲染扫描波纹
     */
    private void renderScanWave(Vector2f location, ViewportAPI viewport) {
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        
        // 动态透明度（随时间衰减）
        float alpha = Math.max(0f, 1f - scanWaveTimer / 2f);
        
        // 设置颜色和透明度
        GL11.glColor4f(
            SCAN_WAVE_COLOR.getRed() / 255f,
            SCAN_WAVE_COLOR.getGreen() / 255f,
            SCAN_WAVE_COLOR.getBlue() / 255f,
            SCAN_WAVE_COLOR.getAlpha() / 255f * alpha
        );
        
        // 移动到舰船位置并旋转
        GL11.glTranslatef(location.x, location.y, 0f);
        GL11.glRotatef(waveAngle, 0f, 0f, 1f);
        
        // 绑定贴图
        scanWaveSprite.bindTexture();
        
        float size = (SCAN_WAVE_MAX_SIZE * waveScale) / viewport.getViewMult();
        
        // 渲染波纹
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 0f);
        GL11.glVertex2f(-size / 2f, -size / 2f);
        GL11.glTexCoord2f(1f, 0f);
        GL11.glVertex2f(size / 2f, -size / 2f);
        GL11.glTexCoord2f(1f, 1f);
        GL11.glVertex2f(size / 2f, size / 2f);
        GL11.glTexCoord2f(0f, 1f);
        GL11.glVertex2f(-size / 2f, size / 2f);
        GL11.glEnd();
        
        GL11.glPopMatrix();
    }
    
    /**
     * 触发扫描波纹效果
     */
    public void triggerScanWave() {
        showScanWave = true;
        scanWaveTimer = 0f;
        waveScale = 1f;
    }
    
    @Override
    public float getRenderRadius() {
        return SCAN_WAVE_MAX_SIZE * 2f;
    }
    
    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_LAYER);
    }
    
    @Override
    public boolean isExpired() {
        return ship == null || !ship.isAlive();
    }
} 