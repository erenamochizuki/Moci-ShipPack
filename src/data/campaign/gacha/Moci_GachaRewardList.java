package data.campaign.gacha;

import java.awt.Color;
import java.util.List;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.campaign.fighter.ui.Moci_AirSquadronArt;

/**
 * 抽卡汇总清单（单抽结束、十连“合并清单”共用）。
 * 每行左边是固定大小的图标格，右边是文字列：不论图标原图多宽，图标和文字都各自纵向对齐。
 * 舰船／舰载机用完整装配图（含武器，按实机隐藏规则略去 _SOURCE／_FOLLOW），其余用物品图标或头像。
 */
final class Moci_GachaRewardList {
    // 行高、图标格边长、文字列起点（UI 像素）。
    static final float ROW = 68f, ICON = 60f, TEXT_X = 84f;
    private static final Color DIM = new Color(137, 151, 157), FRAME = new Color(12, 20, 26), RULE = new Color(48, 62, 71);

    private Moci_GachaRewardList() { }

    static TooltipMakerAPI build(CustomPanelAPI parent, float width, float height, String heading, Color headingColor,
                                 List<Moci_GachaReward> rewards, List<Moci_GachaRevealDialog.Asset> assets) {
        TooltipMakerAPI list = parent.createUIElement(width, height, true);
        list.addSectionHeading(heading, headingColor, new Color(20, 29, 36), Alignment.MID, 0);
        float rowWidth = width - 12;
        for (int i = 0; i < rewards.size(); i++) {
            Moci_GachaReward r = rewards.get(i);
            Moci_GachaRevealDialog.Asset a = assets.get(i);
            boolean faceDown = "ShipID".equals(r.type()) || "Fighter".equals(r.type());
            CustomPanelAPI row = parent.createCustomPanel(rowWidth, ROW, new Icon(a, faceDown));
            TooltipMakerAPI text = row.createUIElement(rowWidth - TEXT_X, ROW, false);
            Color color = Moci_GachaPortalRenderer.rarityColor(r.rank());
            text.addPara(r.rarity() + " / " + a.name() + "  ×" + Misc.getWithDGS(r.quantity()), color, 0);
            text.addPara(Moci_GachaRevealDialog.typeLabel(r.type()), DIM, 6);
            row.addUIElement(text).inTL(TEXT_X, 14);
            list.addCustom(row, i == 0 ? 10 : 4);
        }
        return list;
    }

    /** 图标格：深色底框 + 按比例缩放到格内的模型或贴图，不放大超过原图 1.5 倍以免糊。 */
    private static final class Icon extends BaseCustomUIPanelPlugin {
        private final Moci_GachaRevealDialog.Asset asset;
        private final boolean faceDown;
        private PositionAPI position;

        Icon(Moci_GachaRevealDialog.Asset asset, boolean faceDown) { this.asset = asset; this.faceDown = faceDown; }

        @Override public void positionChanged(PositionAPI position) { this.position = position; }

        @Override public void renderBelow(float alphaMult) {
            if (position == null || alphaMult <= 0) return;
            float x = position.getX() + 4, y = position.getY() + (position.getHeight() - ICON) / 2f;
            float cx = x + ICON / 2f, cy = y + ICON / 2f;
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
            try {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                rect(x, y, ICON, ICON, FRAME, alphaMult);
                rect(x, y, ICON, 1, RULE, alphaMult);
                float box = ICON - 8;
                Moci_AirSquadronArt.Model model = asset.model();
                SpriteAPI sprite = asset.sprite();
                if (model != null) {
                    float w = model.nativeWidth() * asset.displayScale(), h = model.nativeHeight() * asset.displayScale();
                    float scale = fit(w, h, box);
                    GL11.glPushMatrix();
                    try {
                        GL11.glTranslatef(cx, cy, 0);
                        if (faceDown) GL11.glRotatef(180f, 0, 0, 1);
                        model.renderCentered(0, 0, w * scale, h * scale, alphaMult, Color.WHITE);
                    } finally { GL11.glPopMatrix(); }
                } else if (sprite != null) {
                    float w = sprite.getWidth() * asset.displayScale(), h = sprite.getHeight() * asset.displayScale();
                    float scale = fit(w, h, box);
                    float oldW = sprite.getWidth(), oldH = sprite.getHeight(), oldAngle = sprite.getAngle(), oldAlpha = sprite.getAlphaMult();
                    try {
                        sprite.setSize(w * scale, h * scale);
                        sprite.setAngle(faceDown ? 180f : 0f);
                        sprite.setAlphaMult(alphaMult);
                        sprite.setNormalBlend();
                        sprite.renderAtCenter(cx, cy);
                    } finally { sprite.setSize(oldW, oldH); sprite.setAngle(oldAngle); sprite.setAlphaMult(oldAlpha); }
                }
            } finally { GL11.glPopAttrib(); }
        }

        private static float fit(float w, float h, float box) {
            if (w <= 0 || h <= 0) return 1f;
            return Math.min(1.5f, Math.min(box / w, box / h));
        }

        private static void rect(float x, float y, float w, float h, Color c, float alphaMult) {
            GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, alphaMult);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y); GL11.glVertex2f(x + w, y);
            GL11.glVertex2f(x + w, y + h); GL11.glVertex2f(x, y + h);
            GL11.glEnd();
        }
    }
}
