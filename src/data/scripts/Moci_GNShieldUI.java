package data.scripts;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import data.hullmods.Moci_GNShield_Script;
import data.scripts.util.Moci_TextLoader;
import org.lazywizard.lazylib.ui.FontException;
import org.lazywizard.lazylib.ui.LazyFont;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

/**
 * GN护盾UI渲染插件
 * 显示力场状态文本（力场：开启/关闭）+ 方块指示器
 */
public class Moci_GNShieldUI extends BaseEveryFrameCombatPlugin {
    
    private static final String UI_KEY = "Moci_GNShieldUI";
    private static final String TEXT_ID = "Moci_GNShieldUI";
    
    // 指示器大小
    private static final float INDICATOR_SIZE = 8f;
    
    // 力场状态颜色
    private static final Color FIELD_ON_COLOR = new Color(100, 200, 255, 255);  // 开启：蓝色
    private static final Color FIELD_OFF_COLOR = new Color(255, 50, 50, 255);   // 关闭：红色
    
    // LazyFont 字體
    private static LazyFont labelFont = null;
    private static boolean fontInitialized = false;
    
    public static void register() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null && !engine.getCustomData().containsKey(UI_KEY)) {
            Moci_GNShieldUI ui = new Moci_GNShieldUI();
            engine.addPlugin(ui);
            engine.getCustomData().put(UI_KEY, ui);
        }
    }
    
    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (Global.getCurrentState().equals(GameState.TITLE)) {
            return;
        }
        
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        
        // 检查UI状态：HUD隐藏或显示对话框时不渲染
        if (!engine.isUIShowingHUD() || engine.isUIShowingDialog()) {
            return;
        }
        
        ViewportAPI viewport = engine.getViewport();
        if (viewport == null) {
            return;
        }
        
        // 为所有有GN护盾的船只显示状态指示器
        for (ShipAPI ship : engine.getShips()) {
            if (ship.isFighter()) continue;
            if (!ship.isAlive() || ship.isHulk()) continue;
            if (ship.isStationModule()) continue; // 模块不显示指示器
            
            if (Moci_GNShield_Script.hasShield(ship)) {
                // 检查护盾是否激活
                boolean isShieldOn = ship.getShield() != null && ship.getShield().isOn();
                renderStatusText(ship, isShieldOn, viewport, engine);
            }
        }
    }
    
    /**
     * 檢查是否應該顯示狀態指示器
     * @return -1 表示不顯示，0 表示完整模式，1 表示小型模式
     */
    private int getStatusDisplayMode(ShipAPI ship, CombatEngineAPI engine) {
        CombatUIAPI combatUI = engine.getCombatUI();
        if (combatUI != null) {
            // 戰術地圖打開時不顯示
            if (combatUI.isShowingCommandUI()) {
                return -1;
            }
            // 部署對話框打開時不顯示
            if (combatUI.isShowingDeploymentDialog()) {
                return -1;
            }
        }
        
        ShipAPI playerShip = engine.getPlayerShip();
        if (playerShip == null) return -1;
        
        // 玩家鎖定的目標：完整模式
        ShipAPI playerTarget = playerShip.getShipTarget();
        if (playerTarget == ship) return 0;
        
        // 玩家艦船：小型模式
        if (ship == playerShip) return 1;
        
        // 檢查鼠標是否懸停在艦船上
        ViewportAPI vp = engine.getViewport();
        Vector2f mouseWorld = new Vector2f(
            vp.convertScreenXToWorldX(Global.getSettings().getMouseX()),
            vp.convertScreenYToWorldY(Global.getSettings().getMouseY())
        );
        float dist = Vector2f.sub(mouseWorld, ship.getLocation(), null).length();
        float threshold = 50f + ship.getCollisionRadius();
        if (dist < threshold) return 1;
        
        // 友方艦船在視野內：小型模式
        if (ship.getOwner() == playerShip.getOwner() && 
            vp.isNearViewport(ship.getLocation(), -(ship.getCollisionRadius() * 1.25f))) {
            return 1;
        }
        
        return -1;
    }
    
    /**
     * 渲染浮動狀態文本（力场：开启/关闭）+ 方块指示器
     */
    private void renderStatusText(ShipAPI ship, boolean isFieldOn, ViewportAPI viewport, CombatEngineAPI engine) {
        int displayMode = getStatusDisplayMode(ship, engine);
        if (displayMode < 0) {
            return;
        }
        boolean isSmallMode = (displayMode == 1);
        
        if (!viewport.isNearViewport(ship.getLocation(), ship.getCollisionRadius() + 500f)) {
            return;
        }
        
        float shipX = ship.getLocation().x;
        float shipY = ship.getLocation().y;
        
        float screenX = viewport.convertWorldXtoScreenX(shipX);
        float screenY = viewport.convertWorldYtoScreenY(shipY);
        
        float shipRadius = ship.getCollisionRadius();
        float viewMult = viewport.getViewMult();
        float screenRadius = shipRadius / viewMult;
        
        float maxRadius = 150f;
        if (screenRadius > maxRadius) screenRadius = maxRadius;
        if (screenRadius < 50f && !isSmallMode) screenRadius = 50f;
        
        if (isSmallMode) {
            screenRadius *= 0.66f;
        }
        
        float offsetX = screenRadius * 0.6f * 1.6666f;
        float offsetY = screenRadius * 0.6f * 1.6666f;
        
        float lineHeight = 11f;
        
        float panelX = screenX + offsetX;
        float panelY = screenY + offsetY + 3f;
        
        // 状态文本显示在 flux 条上方
        float baseY = panelY + lineHeight + 7f;
        float baseX = panelX + 5f;
        
        // 对坐标取整，确保在不同缩放级别下位置一致
        int intBaseX = (int)baseX;
        int intBaseY = (int)baseY;
        
        Color statusColor = isFieldOn ? FIELD_ON_COLOR : FIELD_OFF_COLOR;
        float alpha = ship.getAlphaMult();
        
        float screenHeight = Global.getSettings().getScreenHeight();
        
        // 先渲染方块指示器（使用取整后的坐标）
        renderIndicator(intBaseX, intBaseY, isFieldOn, statusColor, alpha, screenHeight);
        
        // 然后渲染文本（在方块右侧，并且相对方块再向上2像素）
        float textOffsetX = INDICATOR_SIZE + 3f; // 方块宽度 + 3像素间距
        float textOffsetY = 2f; // 文字相对方块向上2像素
        renderFieldStatusText(intBaseX + textOffsetX, intBaseY + textOffsetY, isFieldOn, statusColor, alpha, screenHeight);
    }
    
    /**
     * 初始化 LazyFont 字體
     */
    private static void initFont() {
        if (fontInitialized) return;
        fontInitialized = true;
        
        try {
            labelFont = LazyFont.loadFont("graphics/fonts/victor10.fnt");
        } catch (FontException e) {
            labelFont = null;
        }
    }
    
    /**
     * 渲染方块指示器
     * 开启：实心方块
     * 关闭：空心方框
     */
    private void renderIndicator(float x, float y, boolean isOn, Color statusColor, float alpha, float screenHeight) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        
        GL11.glOrtho(0, Global.getSettings().getScreenWidth(), 
                     0, screenHeight, -1, 1);
        
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        
        // 移动到指示器位置（对齐文本基线）
        float indicatorX = x - 1f;
        float indicatorY = y + 2f; // 稍微上移以对齐文本
        
        Color indicatorColor = new Color(
            statusColor.getRed(), 
            statusColor.getGreen(), 
            statusColor.getBlue(), 
            (int)(255 * alpha)
        );
        
        if (isOn) {
            // 开启：绘制实心方块
            GL11.glColor4ub(
                (byte)indicatorColor.getRed(), 
                (byte)indicatorColor.getGreen(), 
                (byte)indicatorColor.getBlue(), 
                (byte)indicatorColor.getAlpha()
            );
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(indicatorX, indicatorY);
            GL11.glVertex2f(indicatorX, indicatorY + INDICATOR_SIZE);
            GL11.glVertex2f(indicatorX + INDICATOR_SIZE, indicatorY + INDICATOR_SIZE);
            GL11.glVertex2f(indicatorX + INDICATOR_SIZE, indicatorY);
            GL11.glEnd();
        } else {
            // 关闭：绘制空心方框（4条线）
            GL11.glColor4ub(
                (byte)indicatorColor.getRed(), 
                (byte)indicatorColor.getGreen(), 
                (byte)indicatorColor.getBlue(), 
                (byte)indicatorColor.getAlpha()
            );
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(indicatorX, indicatorY);
            GL11.glVertex2f(indicatorX, indicatorY + INDICATOR_SIZE);
            GL11.glVertex2f(indicatorX + INDICATOR_SIZE, indicatorY + INDICATOR_SIZE);
            GL11.glVertex2f(indicatorX + INDICATOR_SIZE, indicatorY);
            GL11.glEnd();
        }
        
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        
        GL11.glPopAttrib();
    }
    
    /**
     * 渲染 "力场：开启/关闭" 文本
     */
    private void renderFieldStatusText(float x, float y, boolean isOn, Color statusColor, float alpha, float screenHeight) {
        if (!fontInitialized) {
            initFont();
        }
        
        if (labelFont == null) return;
        
        // 状态颜色（带透明度）
        Color textColor = new Color(
            statusColor.getRed(), 
            statusColor.getGreen(), 
            statusColor.getBlue(), 
            (int)(255 * alpha)
        );
        
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        
        GL11.glOrtho(0, Global.getSettings().getScreenWidth(), 
                     0, screenHeight, -1, 1);
        
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        
        String text = Moci_TextLoader.getText(TEXT_ID,
            isOn ? "status.active" : "status.inactive",
            isOn ? "GN: ON" : "GN: OFF");
        float fontSize = labelFont.getBaseHeight();
        float charX = x - 1f;
        float charY = y + fontSize;
        
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            LazyFont.DrawableString drawable = labelFont.createText(ch, textColor, fontSize);
            drawable.setAnchor(LazyFont.TextAnchor.TOP_LEFT);
            drawable.draw(charX, charY);
            float charWidth = labelFont.calcWidth(ch, fontSize);
            charX += charWidth * 0.8f;
        }
        
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
}
