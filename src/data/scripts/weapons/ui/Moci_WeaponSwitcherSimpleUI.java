package data.scripts.weapons.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import data.scripts.util.Moci_BoxBasedUtil;
import org.lazywizard.lazylib.ui.FontException;
import org.lazywizard.lazylib.ui.LazyFont;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Moci_WeaponSwitcherSimpleUI {
    private static final Color BOX_FILL_COLOR = new Color(15, 22, 34, 180);
    private static final Color BOX_BORDER_COLOR = new Color(120, 210, 255, 220);
    private static final Color BOX_FILL_HIGHLIGHT_COLOR = new Color(32, 58, 88, 220);
    private static final Color BOX_BORDER_HIGHLIGHT_COLOR = new Color(255, 225, 120, 255);
    private static final Color KEY_TEXT_COLOR = new Color(140, 230, 255, 255);
    private static final Color NAME_TEXT_COLOR = new Color(245, 245, 245, 255);
    private static final Color KEY_TEXT_HIGHLIGHT_COLOR = new Color(255, 236, 150, 255);

    private static final float PADDING_X = 8f;
    private static final float PADDING_Y = 6f;
    private static final float BOX_GAP = 6f;
    private static final float LINE_GAP = 3f;
    private static final float FONT_SIZE = 11f;
    private static final float MIN_BOX_WIDTH = 110f;

    private static LazyFont labelFont = null;
    private static boolean fontInitialized = false;

    private final ShipAPI ship;

    private Map<String, WeaponAPI> current = new HashMap<>();
    private Map<String, Integer> keyMap = new HashMap<>();
    private List<String> tagList = new ArrayList<>();
    private String currentTag = null;
    private float remaining = 0f;

    public Moci_WeaponSwitcherSimpleUI(ShipAPI ship) {
        this.ship = ship.getParentStation() == null ? ship : ship.getParentStation();
    }

    public void updateUIData(Map<String, WeaponAPI> current, Map<String, Integer> keyMap, float remaining,
                             List<String> tagList, String currentTag) {
        this.current = current != null ? current : new HashMap<String, WeaponAPI>();
        this.keyMap = keyMap != null ? keyMap : new HashMap<String, Integer>();
        this.tagList = tagList != null ? tagList : new ArrayList<String>();
        this.currentTag = currentTag;
        this.remaining = remaining;
    }

    public void advance(float amount) {
        // 目前简易 UI 没有额外动画状态，保留空实现以对齐旧 UI 接口。
    }

    public void render(float centerX, float centerY, Vector2f mouseLocation, float angle, boolean mouseClicked, float alpha) {
        if (Global.getCombatEngine().getPlayerShip() != ship || !Global.getCombatEngine().isUIShowingHUD()) {
            return;
        }

        float fade = Math.min(Math.max(0f, remaining), 1f);
        float alphaMult = fade * alpha * ship.getAlphaMult();
        if (alphaMult <= 0f || tagList.isEmpty()) {
            return;
        }

        if (!fontInitialized) {
            initFont();
        }
        if (labelFont == null) {
            return;
        }

        float screenX = Global.getCombatEngine().getViewport().convertWorldXtoScreenX(centerX);
        float screenY = Global.getCombatEngine().getViewport().convertWorldYtoScreenY(centerY);
        float screenRadius = ship.getCollisionRadius() / Math.max(Global.getCombatEngine().getViewport().getViewMult(), 0.01f);
        screenRadius = Math.max(48f, Math.min(screenRadius, 150f));

        float anchorX = Math.round(screenX + screenRadius * 0.9f + 18f);
        float anchorY = Math.round(screenY + screenRadius * 0.55f);

        beginUiDraw();
        try {
            float currentY = anchorY;
            for (String tag : tagList) {
                String keyText = getKeyText(tag);
                String nameText = getWeaponName(tag);
                boolean highlighted = tag.equals(currentTag);

                float keyWidth = calcTextWidth(keyText, FONT_SIZE);
                float nameWidth = calcTextWidth(nameText, FONT_SIZE);
                float boxWidth = Math.max(MIN_BOX_WIDTH, Math.max(keyWidth, nameWidth) + PADDING_X * 2f);
                float boxHeight = FONT_SIZE * 2f + LINE_GAP + PADDING_Y * 2f;

                drawBox(anchorX, currentY, boxWidth, boxHeight, alphaMult, highlighted);
                drawTextLine(anchorX + PADDING_X, currentY + PADDING_Y + FONT_SIZE, keyText,
                        Moci_BoxBasedUtil.scaleAlpha(highlighted ? KEY_TEXT_HIGHLIGHT_COLOR : KEY_TEXT_COLOR, alphaMult), FONT_SIZE);
                drawTextLine(anchorX + PADDING_X, currentY + PADDING_Y + FONT_SIZE * 2f + LINE_GAP, nameText,
                        Moci_BoxBasedUtil.scaleAlpha(NAME_TEXT_COLOR, alphaMult), FONT_SIZE);

                currentY -= boxHeight + BOX_GAP;
            }
        } finally {
            endUiDraw();
        }
    }

    private String getKeyText(String tag) {
        Integer keyCode = keyMap.get(tag);
        if (keyCode == null) {
            return "按键: 未知";
        }
        return "按键: " + Keyboard.getKeyName(keyCode);
    }

    private String getWeaponName(String tag) {
        WeaponAPI weapon = current.get(tag);
        if (weapon == null) {
            return "未绑定武器";
        }
        String name = weapon.getDisplayName();
        return name == null || name.isEmpty() ? weapon.getId() : name;
    }

    private static void initFont() {
        fontInitialized = true;
        try {
            labelFont = LazyFont.loadFont("graphics/fonts/victor10.fnt");
        } catch (FontException e) {
            labelFont = null;
        }
    }

    private static void beginUiDraw() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, Global.getSettings().getScreenWidth(),
                0, Global.getSettings().getScreenHeight(), -1, 1);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void endUiDraw() {
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopAttrib();
    }

    private static void drawBox(float x, float y, float width, float height, float alphaMult, boolean highlighted) {
        Color fill = Moci_BoxBasedUtil.scaleAlpha(highlighted ? BOX_FILL_HIGHLIGHT_COLOR : BOX_FILL_COLOR, alphaMult);
        GL11.glColor4ub((byte) fill.getRed(), (byte) fill.getGreen(), (byte) fill.getBlue(), (byte) fill.getAlpha());
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();

        Color border = Moci_BoxBasedUtil.scaleAlpha(highlighted ? BOX_BORDER_HIGHLIGHT_COLOR : BOX_BORDER_COLOR, alphaMult);
        GL11.glColor4ub((byte) border.getRed(), (byte) border.getGreen(), (byte) border.getBlue(), (byte) border.getAlpha());
        GL11.glLineWidth(highlighted ? 2.5f : 1.5f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();
    }

    private static void drawTextLine(float x, float baselineY, String text, Color color, float fontSize) {
        float charX = x;
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            LazyFont.DrawableString drawable = labelFont.createText(ch, color, fontSize);
            drawable.setAnchor(LazyFont.TextAnchor.TOP_LEFT);
            drawable.draw(charX, baselineY);
            charX += labelFont.calcWidth(ch, fontSize) * 0.8f;
        }
    }

    private static float calcTextWidth(String text, float fontSize) {
        float width = 0f;
        for (int i = 0; i < text.length(); i++) {
            width += labelFont.calcWidth(text.substring(i, i + 1), fontSize) * 0.8f;
        }
        return width;
    }
}
