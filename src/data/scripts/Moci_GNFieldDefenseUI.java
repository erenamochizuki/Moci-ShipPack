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

import data.hullmods.Moci_GNFieldDefense_Script;
import data.scripts.util.Moci_TextLoader;
import org.lazywizard.lazylib.ui.FontException;
import org.lazywizard.lazylib.ui.LazyFont;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.util.Locale;

/**
 * GN力场防御系统UI渲染插件
 * 使用Advance版本的漂亮护盾条渲染（参考原版StructuralShieldRenderer）
 */
public class Moci_GNFieldDefenseUI extends BaseEveryFrameCombatPlugin {
    
    private static final String UI_KEY = "Moci_GNFieldDefenseUI";
    private static final String TEXT_ID = "Moci_GNFieldDefenseUI";
    
    // 條形渲染參數（類似原版flux/hull條）
    private static final float BAR_WIDTH = 60f;
    private static final float BAR_HEIGHT = 5f;
    
    // 護盾顏色
    private static final Color SHIELD_COLOR_FULL = new Color(100, 200, 255, 180);
    private static final Color SHIELD_COLOR_HIGH = new Color(100, 255, 150, 180);
    private static final Color SHIELD_COLOR_MID = new Color(255, 255, 100, 180);
    private static final Color SHIELD_COLOR_LOW = new Color(255, 150, 50, 180);
    private static final Color SHIELD_COLOR_CRITICAL = new Color(255, 50, 50, 180);
    
    // LazyFont 字體
    private static LazyFont labelFont = null;
    private static boolean fontInitialized = false;
    
    // 原版 UI 標籤顏色（黃綠色，與 hull/flux 標籤一致）
    private static final Color LABEL_COLOR = new Color(155, 255, 0);
    
    public static void register() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null && !engine.getCustomData().containsKey(UI_KEY)) {
            Moci_GNFieldDefenseUI ui = new Moci_GNFieldDefenseUI();
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
        
        // 为玩家船只显示界面状态条（使用MagicLib）
        ShipAPI playerShip = engine.getPlayerShip();
        if (playerShip != null && Moci_GNFieldDefense_Script.hasShield(playerShip)) {
            Moci_GNFieldDefense_Script script = Moci_GNFieldDefense_Script.getInstance(playerShip);
            if (script != null) {
                float shieldLevel = script.getShield().getShieldLevel();
                float shieldCurrent = script.getShield().getCurrent();
                float shieldExtra = script.getShield().getExtra();
                float shieldCap = script.getShield().getShieldCap();
                
                // 使用MagicLib绘制界面状态条
                org.magiclib.util.MagicUI.drawInterfaceStatusBar(playerShip, shieldLevel, null, null,
                    shieldExtra / (shieldExtra + shieldCap),
                    Moci_TextLoader.getText(TEXT_ID, "status.interface_label", "GN Field"),
                    (int) (shieldCurrent + shieldExtra));
            }
        }
        
        // 为所有有GN力场防御的船只显示护盾条或重启状态
        for (ShipAPI ship : engine.getShips()) {
            if (ship.isFighter()) continue;
            if (!ship.isAlive() || ship.isHulk()) continue;
            if (ship.isStationModule()) continue; // 模块不显示护盾条
            
            if (Moci_GNFieldDefense_Script.hasShield(ship)) {
                Moci_GNFieldDefense_Script script = Moci_GNFieldDefense_Script.getInstance(ship);
                if (script != null) {
                    // 检查护盾是否被击破
                    if (script.getShield().isShieldBroken()) {
                        // 显示重启状态
                        float restartTime = script.getShield().getRestartTimeRemaining();
                        renderRestartStatus(ship, restartTime, viewport, engine);
                    } else {
                        // 显示护盾条
                        float shieldLevel = script.getShield().getShieldLevel();
                        if (shieldLevel > 0f) {
                            renderShieldBar(ship, shieldLevel, viewport, engine);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 檢查是否應該顯示護盾條，並返回是否為小型顯示模式
     * 
     * 原版邏輯（來自 CombatState 和 targetReticleRenderer / _null.java）：
     * - 戰術地圖打開時：不顯示
     * - 部署對話框打開時：不顯示
     * - 玩家艦船：始終顯示，小型模式（screenRadius *= 0.66）
     * - 玩家鎖定的目標：顯示，完整模式
     * - 鼠標懸停的艦船（距離 < 50 + collisionRadius）：顯示，小型模式
     * - 友方艦船在視野內：顯示，小型模式
     * - 敵方艦船：如果是玩家目標則顯示
     * 
     * @return -1 表示不顯示，0 表示完整模式，1 表示小型模式
     */
    private int getShieldBarDisplayMode(ShipAPI ship, CombatEngineAPI engine) {
        CombatUIAPI combatUI = engine.getCombatUI();
        if (combatUI != null) {
            // 戰術地圖打開時不顯示護盾條
            if (combatUI.isShowingCommandUI()) {
                return -1;
            }
            // 部署對話框打開時不顯示護盾條
            if (combatUI.isShowingDeploymentDialog()) {
                return -1;
            }
        }
        
        ShipAPI playerShip = engine.getPlayerShip();
        if (playerShip == null) return -1;
        
        // 玩家鎖定的目標：完整模式（包括敵方艦船）
        ShipAPI playerTarget = playerShip.getShipTarget();
        if (playerTarget == ship) return 0;
        
        // 玩家艦船：小型模式
        if (ship == playerShip) return 1;
        
        // 檢查鼠標是否懸停在艦船上（距離 < 50 + collisionRadius）
        ViewportAPI vp = engine.getViewport();
        Vector2f mouseWorld = new Vector2f(
            vp.convertScreenXToWorldX(Global.getSettings().getMouseX()),
            vp.convertScreenYToWorldY(Global.getSettings().getMouseY())
        );
        float dist = Vector2f.sub(mouseWorld, ship.getLocation(), null).length();
        // 原版使用世界坐標距離，不乘以 viewMult
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
     * 渲染浮動護盾條（類似原版flux/hull條，放在flux條上方）
     * 
     * 原版的懸浮信息面板渲染流程（來自 _null.java 的 o00000 方法）：
     * 1. 獲取舰船護盾半徑（shieldSpec.getRadius()），除以 viewMult 得到屏幕半徑
     * 2. 限制半徑範圍：最大 50 + 100 * fader（約150），最小 50（非小型模式）
     * 3. 小型顯示模式乘以 0.66
     * 4. 位置計算：(radius * 0.6, radius * 0.6) * 1.6666 - 這是右上角偏移
     * 5. 條形大小：60x5 像素，標籤寬度 25 像素
     * 6. hull 條在基準位置，flux 條在 hull 上方 lineHeight
     * 7. 護盾條應該在 flux 條上方 lineHeight
     */
    private void renderShieldBar(ShipAPI ship, float shieldLevel, ViewportAPI viewport, CombatEngineAPI engine) {
        // 檢查是否應該顯示護盾條
        int displayMode = getShieldBarDisplayMode(ship, engine);
        if (displayMode < 0) {
            return;
        }
        boolean isSmallMode = (displayMode == 1);
        
        // 檢查舰船是否在視野內
        if (!viewport.isNearViewport(ship.getLocation(), ship.getCollisionRadius() + 500f)) {
            return;
        }
        
        // 獲取舰船世界坐標
        float shipX = ship.getLocation().x;
        float shipY = ship.getLocation().y;
        
        // 將世界坐標轉換為屏幕坐標
        float screenX = viewport.convertWorldXtoScreenX(shipX);
        float screenY = viewport.convertWorldYtoScreenY(shipY);
        
        // 計算舰船半徑在屏幕上的大小
        // 原版使用 shieldSpec.getRadius()，但我們使用 collisionRadius 作為近似
        float shipRadius = ship.getCollisionRadius();
        float viewMult = viewport.getViewMult();
        float screenRadius = shipRadius / viewMult;
        
        // 限制半徑範圍（原版：最大 50 + 100 * fader ≈ 150，最小 50）
        float maxRadius = 150f;
        if (screenRadius > maxRadius) screenRadius = maxRadius;
        if (screenRadius < 50f && !isSmallMode) screenRadius = 50f;
        
        // 小型顯示模式乘以 0.66（原版邏輯）
        if (isSmallMode) {
            screenRadius *= 0.66f;
        }
        
        // 原版位置計算
        // Vector2f vector2f = new Vector2f(f2 * 0.6f, f2 * 0.6f);  // 右上方向
        // Vector2f vector2f2 = vector2f.scale(1.6666666f);
        float offsetX = screenRadius * 0.6f * 1.6666f;
        float offsetY = screenRadius * 0.6f * 1.6666f;
        
        // 原版佈局（從 _null 類分析）：
        // - 標籤 "hull" 寬度 25，高度 lineHeight（約11）
        // - 標籤 "flux" 在 hull 上方（aboveLeft）
        // - 條形在標籤右側 3 像素處（rightOfMid），寬度 60，高度 5
        // - 條形 Y 偏移 -2（setYAlignOffset(-2.0f)）
        float lineHeight = 11f;
        float labelWidth = 25f;
        float labelBarGap = 3f;
        
        // 基準點：原版面板位置
        // 原版：this.d200000.getPosition().setLocation(vector2f2.x, vector2f2.y + f4);
        // f4 = 3.0f
        float panelX = screenX + offsetX;
        float panelY = screenY + offsetY + 3f;
        
        // 原版佈局：
        // - hull 標籤在 inBL(0, 0)，即面板左下角
        // - flux 標籤在 hull 上方（aboveLeft）
        // - 條形在標籤右側 3px，Y 偏移 -2
        // 
        // 護盾條應該在 flux 條上方 lineHeight
        // hull 條 Y = panelY + 0（基準）
        // flux 條 Y = panelY + lineHeight
        // 力场 條 Y = panelY + lineHeight * 2
        float barY = panelY + lineHeight * 2f;
        float barX = panelX + labelWidth + labelBarGap;
        
        Color shieldColor = getShieldColor(shieldLevel);
        float alpha = ship.getAlphaMult();
        
        // 保存當前 OpenGL 狀態和矩陣
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        
        // 保存並重置投影矩陣
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        
        // 設置屏幕坐標系正交投影
        float screenWidth = Global.getSettings().getScreenWidth();
        float screenHeight = Global.getSettings().getScreenHeight();
        GL11.glOrtho(0, screenWidth, 0, screenHeight, -1, 1);
        
        // 保存並重置模型視圖矩陣
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        
        // 設置渲染狀態
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        
        // ========== 渲染條形 ==========
        // 移動到條形位置
        GL11.glTranslatef((int)barX, (int)(barY - 2f), 0f);  // 條形 Y 偏移 -2
        
        float fillWidth = (BAR_WIDTH - 1f) * shieldLevel;
        
        // 繪製黑色背景陰影（原版風格，偏移 +1, -1）
        GL11.glColor4ub((byte)0, (byte)0, (byte)0, (byte)(int)(127 * alpha));
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(1f, -1f);
        GL11.glVertex2f(1f, -1f + BAR_HEIGHT);
        GL11.glVertex2f(1f + fillWidth, -1f + BAR_HEIGHT);
        GL11.glVertex2f(1f + fillWidth, -1f);
        GL11.glEnd();
        
        // 繪製右側端點陰影
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(BAR_WIDTH, -1f);
        GL11.glVertex2f(BAR_WIDTH, -1f + BAR_HEIGHT);
        GL11.glVertex2f(BAR_WIDTH + 1f, -1f + BAR_HEIGHT);
        GL11.glVertex2f(BAR_WIDTH + 1f, -1f);
        GL11.glEnd();
        
        // 繪製主條形（護盾顏色）
        GL11.glColor4ub(
            (byte)shieldColor.getRed(), 
            (byte)shieldColor.getGreen(), 
            (byte)shieldColor.getBlue(), 
            (byte)(int)(shieldColor.getAlpha() * alpha)
        );
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0f, 0f);
        GL11.glVertex2f(0f, BAR_HEIGHT);
        GL11.glVertex2f(fillWidth, BAR_HEIGHT);
        GL11.glVertex2f(fillWidth, 0f);
        GL11.glEnd();
        
        // 繪製右側端點（表示最大值位置）
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(BAR_WIDTH - 1f, 0f);
        GL11.glVertex2f(BAR_WIDTH - 1f, BAR_HEIGHT);
        GL11.glVertex2f(BAR_WIDTH, BAR_HEIGHT);
        GL11.glVertex2f(BAR_WIDTH, 0f);
        GL11.glEnd();
        
        // 恢復模型視圖矩陣（在渲染標籤前恢復，因為標籤需要獨立的坐標系）
        GL11.glPopMatrix();
        
        // 恢復投影矩陣
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        
        // 恢復到模型視圖矩陣模式
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        
        // ========== 渲染 "力场" 標籤 ==========
        // 標籤在恢復矩陣後渲染，使用獨立的坐標系
        float labelX = panelX;
        float labelY = barY - 4f;  // 下移以對齊條形
        renderShieldLabel(labelX, labelY, alpha, screenHeight, shieldColor);
        
        // 恢復所有 OpenGL 狀態
        GL11.glPopAttrib();
    }
    
    /**
     * 渲染重启状态文本（力场 重启中 剩余时间）
     */
    private void renderRestartStatus(ShipAPI ship, float restartTime, ViewportAPI viewport, CombatEngineAPI engine) {
        int displayMode = getShieldBarDisplayMode(ship, engine);
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
        
        // 重启文本位置与"力场"标签位置一致
        float textY = panelY + lineHeight * 2f - 4f;  // 与标签对齐
        float textX = panelX;
        
        float alpha = ship.getAlphaMult();
        float screenHeight = Global.getSettings().getScreenHeight();
        
        // 重启状态使用红色
        Color restartColor = new Color(255, 50, 50, 255);
        
        // 渲染重启状态文本
        renderRestartText(textX, textY, restartTime, restartColor, alpha, screenHeight);
    }
    
    /**
     * 渲染 "力场 重启中 X.Xs" 文本
     */
    private void renderRestartText(float x, float y, float restartTime, Color textColor, float alpha, float screenHeight) {
        if (!fontInitialized) {
            initFont();
        }
        
        if (labelFont == null) return;
        
        Color displayColor = new Color(
            textColor.getRed(), 
            textColor.getGreen(), 
            textColor.getBlue(), 
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
        
        String text = String.format(Locale.ROOT,
            Moci_TextLoader.getText(TEXT_ID, "status.restarting", "GN  Restarting %.1fs"),
            restartTime);
        float fontSize = labelFont.getBaseHeight();
        float charX = x - 1f;
        float charY = y + fontSize;
        
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            LazyFont.DrawableString drawable = labelFont.createText(ch, displayColor, fontSize);
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
    
    /**
     * 初始化 LazyFont 字體
     */
    private static void initFont() {
        if (fontInitialized) return;
        fontInitialized = true;
        
        try {
            // 使用原版的 victor10 字體
            labelFont = LazyFont.loadFont("graphics/fonts/victor10.fnt");
        } catch (FontException e) {
            labelFont = null;
        }
    }
    
    /**
     * 渲染 "力场" 標籤（使用 LazyFont，逐字符渲染）
     * 标签颜色随护盾等级变化
     */
    private void renderShieldLabel(float x, float y, float alpha, float screenHeight, Color shieldColor) {
        if (!fontInitialized) {
            initFont();
        }
        
        if (labelFont == null) return;
        
        // 使用护盾颜色（随护盾状态变色）
        Color labelColor = new Color(
            shieldColor.getRed(), 
            shieldColor.getGreen(), 
            shieldColor.getBlue(), 
            (int)(255 * alpha)
        );
        
        // 保存當前投影矩陣
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        
        // 使用標準 OpenGL 坐標系（Y 從下往上）
        GL11.glOrtho(0, Global.getSettings().getScreenWidth(), 
                     0, screenHeight, -1, 1);
        
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        
        String text = Moci_TextLoader.getText(TEXT_ID, "status.label", "GN");
        float fontSize = labelFont.getBaseHeight();
        float charX = x - 1f;  // 左移 1px
        float charY = y + fontSize;
        
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            LazyFont.DrawableString drawable = labelFont.createText(ch, labelColor, fontSize);
            drawable.setAnchor(LazyFont.TextAnchor.TOP_LEFT);
            drawable.draw(charX, charY);
            // 使用字體計算的字符寬度，間距降低20%
            float charWidth = labelFont.calcWidth(ch, fontSize);
            charX += charWidth * 0.8f;
        }
        
        // 恢復矩陣
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
    
    /**
     * 获取护盾颜色（根据护盾等级）
     * 100% → 蓝色
     * 50% → 黄色
     * 0% → 红色
     */
    private Color getShieldColor(float shieldLevel) {
        if (shieldLevel >= 0.5f) {
            // 100% → 50%: 蓝色 → 黄色
            float t = (shieldLevel - 0.5f) / 0.5f; // 0.0 (50%) to 1.0 (100%)
            Color yellow = new Color(255, 255, 100, 180);
            Color blue = new Color(100, 200, 255, 180);
            return interpolateColor(yellow, blue, t);
        } else {
            // 50% → 0%: 黄色 → 红色
            float t = shieldLevel / 0.5f; // 0.0 (0%) to 1.0 (50%)
            Color red = new Color(255, 50, 50, 180);
            Color yellow = new Color(255, 255, 100, 180);
            return interpolateColor(red, yellow, t);
        }
    }
    
    /**
     * 颜色插值
     */
    private Color interpolateColor(Color c1, Color c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int)(c1.getRed() + (c2.getRed() - c1.getRed()) * t);
        int g = (int)(c1.getGreen() + (c2.getGreen() - c1.getGreen()) * t);
        int b = (int)(c1.getBlue() + (c2.getBlue() - c1.getBlue()) * t);
        int a = (int)(c1.getAlpha() + (c2.getAlpha() - c1.getAlpha()) * t);
        return new Color(r, g, b, a);
    }
}
