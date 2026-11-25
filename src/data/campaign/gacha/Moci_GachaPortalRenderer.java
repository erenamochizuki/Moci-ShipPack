package data.campaign.gacha;

import java.awt.Color;
import java.nio.IntBuffer;
import java.util.Random;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.graphics.SpriteAPI;
import data.campaign.fighter.ui.Moci_AirSquadronArt;

/**
 * 抽卡实际画面的 OpenGL 渲染器：背景、圆形光斑、纺锤粒子、白闪与奖品展示。
 * 素材与随机种子在创建窗口时准备；每帧读取 Sequence 提供的进度绘制，不负责推进流程或发声。
 * 改“画面长什么样”看本类；改“展示多久／什么时候揭晓”优先看 RevealSequence / BatchSequence。
 */
public final class Moci_GachaPortalRenderer {
    public static final String BACKGROUND = "graphics/illustrations/moci_gacha_portal.png";
    public static final float WIDTH = 1280f, HEIGHT = 720f;
    private static final float CX = 640f, CY = 316f;
    private static final int[] GLASS_COLORS = {0xa3ff66, 0xdcff8c, 0x5ce5c9, 0x70badf, 0xd4aaef, 0xeb9b72};
    private static final int[] RARITY_COLORS = {0xbaff9b, 0x9cd9ff, 0xffdf8a};
    private static final int[] PANEL_COLORS = {0xa5c2a7, 0x89c2d8, 0xe5c679};
    private int selectedIndex = -1;
    private final SpriteAPI background;
    private final SpriteAPI[] icons;
    private final Moci_AirSquadronArt.Model[] models;
    private final float[] iconRatios, nativeWidths, nativeHeights;
    // 固定显示尺寸（UI 像素，0 表示不固定）：军官头像在特写和重点展示里一律按这个边长显示。
    private final float[] fixedUiSizes;
    /** 军官头像在揭晓特写与左侧重点展示中的显示边长（UI 像素）。 */
    public static final float PORTRAIT_UI_SIZE = 128f;
    private final boolean[] faceDown;
    // 三类特效数量：中心细粒子、前景纺锤形粒子、圆形散景光斑；改变数量影响绘制成本。
    private static final int INNER_SPARKS = 320, FOREGROUND_SPINDLES = 120, ROUND_BOKEH = 180;
    private final float[] phases = new float[INNER_SPARKS + FOREGROUND_SPINDLES + ROUND_BOKEH];
    private final float[] angles = new float[INNER_SPARKS + FOREGROUND_SPINDLES + ROUND_BOKEH];
    private final float[] speeds = new float[INNER_SPARKS + FOREGROUND_SPINDLES + ROUND_BOKEH];
    private final float[] sizes = new float[INNER_SPARKS + FOREGROUND_SPINDLES + ROUND_BOKEH];
    private final IntBuffer scissor = BufferUtils.createIntBuffer(16);
    private float alpha, stageScaleX = 1f, stageScaleY = 1f;

    public Moci_GachaPortalRenderer(SpriteAPI background, SpriteAPI[] icons) {
        this(background, icons, new Moci_AirSquadronArt.Model[icons.length]);
    }

    public Moci_GachaPortalRenderer(SpriteAPI background, SpriteAPI[] icons, Moci_AirSquadronArt.Model[] models) {
        this(background, icons, models, modelDirections(models));
    }

    public Moci_GachaPortalRenderer(SpriteAPI background, SpriteAPI[] icons, Moci_AirSquadronArt.Model[] models, boolean[] faceDown) {
        this(background, icons, models, faceDown, null);
    }

    public Moci_GachaPortalRenderer(SpriteAPI background, SpriteAPI[] icons, Moci_AirSquadronArt.Model[] models, boolean[] faceDown, float[] displayScales) {
        if (icons.length != models.length || icons.length != faceDown.length) throw new IllegalArgumentException("Reward asset lengths differ");
        if (displayScales != null && displayScales.length != icons.length) throw new IllegalArgumentException("Reward scale lengths differ");
        this.faceDown = faceDown.clone();
        this.background = background;
        this.models = models.clone();
        this.icons = icons.clone();
        this.iconRatios = new float[icons.length];
        this.fixedUiSizes = new float[icons.length];
        this.nativeWidths = new float[icons.length];
        this.nativeHeights = new float[icons.length];
        for (int i = 0; i < icons.length; i++) {
            nativeWidths[i] = models[i] != null ? models[i].nativeWidth() : icons[i] == null ? 0 : icons[i].getWidth();
            nativeHeights[i] = models[i] != null ? models[i].nativeHeight() : icons[i] == null ? 0 : icons[i].getHeight();
            iconRatios[i] = nativeWidths[i] / Math.max(1f, nativeHeights[i]);
            float displayScale = displayScales == null ? 1f : displayScales[i];
            if (!Float.isFinite(displayScale) || displayScale <= 0 || displayScale > 1) throw new IllegalArgumentException("Reward display scale must be in (0, 1]");
            nativeWidths[i] *= displayScale; nativeHeights[i] *= displayScale;
        }
        Random random = new Random(0x4d4f4349504f5254L);
        for (int i = 0; i < phases.length; i++) {
            phases[i] = random.nextFloat();
            angles[i] = random.nextFloat() * (float) (Math.PI * 2);
            speeds[i] = 0.7f + random.nextFloat() * 0.8f;
            sizes[i] = 1.5f + random.nextFloat() * 4.5f;
        }
    }

    private static boolean[] modelDirections(Moci_AirSquadronArt.Model[] models) {
        boolean[] directions = new boolean[models.length];
        for (int i = 0; i < models.length; i++) directions[i] = models[i] != null;
        return directions;
    }

    /** Coordinates are Starsector virtual UI pixels; scale is SettingsAPI.getScreenScaleMult(). */
    public void render(float x, float y, float width, float height, float screenScale,
                       float alphaMult, Moci_GachaRevealSequence sequence, int rank) {
        renderRegion(x, y, width, height, screenScale, alphaMult, sequence, rank, null);
    }

    public void renderBatch(float x, float y, float width, float height, float screenScale,
                            float alphaMult, Moci_GachaBatchSequence batch) {
        renderBatch(x, y, width, height, screenScale, alphaMult, batch, -1);
    }

    public void renderBatch(float x, float y, float width, float height, float screenScale,
                            float alphaMult, Moci_GachaBatchSequence batch, int selected) {
        selectedIndex = selected;
        renderRegion(x, y, width, height, screenScale, alphaMult, batch.ambient(), 0, batch);
    }

    private void renderRegion(float x, float y, float width, float height, float screenScale, float alphaMult,
                              Moci_GachaRevealSequence sequence, int rank, Moci_GachaBatchSequence batch) {
        if (width <= 0 || height <= 0 || alphaMult <= 0 || sequence.summary()) return;
        alpha = Moci_GachaRevealSequence.clamp(alphaMult);
        stageScaleX = width / WIDTH; stageScaleY = height / HEIGHT;
        int previousMatrix = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            // Intersect the parent's clip, then restore it along with all other GL attributes.
            int left = (int) Math.ceil(x * screenScale), bottom = (int) Math.ceil(y * screenScale);
            int right = (int) Math.floor((x + width) * screenScale), top = (int) Math.floor((y + height) * screenScale);
            if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
                scissor.clear();
                GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissor);
                left = Math.max(left, scissor.get(0)); bottom = Math.max(bottom, scissor.get(1));
                right = Math.min(right, scissor.get(0) + scissor.get(2));
                top = Math.min(top, scissor.get(1) + scissor.get(3));
            }
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
            GL11.glTranslatef(x, y, 0);
            GL11.glScalef(width / WIDTH, height / HEIGHT, 1f);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            if (batch == null) draw(sequence, Math.max(0, Math.min(2, rank)), true, sequence.index());
            else {
                // 列表与 SR／SSR 特写之间交叉淡化：特写开始时列表淡出，特写结束后列表在最后一帧特写上淡入。
                float cards = batch.cardsVisibility();
                if (batch.focusIndex() >= 0) {
                    int index = batch.focusIndex();
                    draw(batch.inspecting() ? batch.ambient() : batch.focusSequence(), batch.rankAt(index), true, index);
                    if (cards > 0) withAlpha(cards, () -> { draw(batch.ambient(), 0, false, 0); drawCards(batch); });
                } else {
                    Moci_GachaRevealSequence fading = batch.fadingFocus();
                    if (fading != null && cards < 1) {
                        int index = batch.fadingFocusIndex();
                        draw(fading, batch.rankAt(index), true, index);
                        withAlpha(cards, () -> { draw(batch.ambient(), 0, false, 0); drawCards(batch); });
                    } else {
                        draw(batch.ambient(), 0, false, 0);
                        drawCards(batch);
                    }
                }
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(previousMatrix);
            GL11.glPopAttrib();
        }
    }

    private void withAlpha(float factor, Runnable body) {
        float saved = alpha;
        alpha = saved * Moci_GachaRevealSequence.clamp(factor);
        try { if (alpha > 0) body.run(); } finally { alpha = saved; }
    }

    /**
     * 舰船／图标贴图走 SpriteAPI 渲染，结束时会关闭 GL_BLEND。不恢复的话后面画的星星光晕会变成实心圆。
     * 每次画完贴图都调用一次。
     */
    private static void restoreBlend() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** 圆形粒子的分段数：屏幕上只有几个像素的光点不需要 24 段，减少每帧顶点数。 */
    private int segments(float radius) {
        float onScreen = radius * Math.max(stageScaleX, stageScaleY);
        return onScreen < 4 ? 8 : onScreen < 12 ? 12 : 24;
    }

    private void draw(Moci_GachaRevealSequence s, int rank, boolean drawHero, int assetIndex) {
        float t = s.age(), charge = s.charge(), reveal = smooth(s.revealProgress());
        float entry = Moci_GachaRevealSequence.clamp(t / 0.32f);
        boolean showing = s.revealed();
        int accent = RARITY_COLORS[rank];
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        quad(640, 360, 1280, 720, 0, 0x06100c, 1f);
        float brightness = showing ? .22f : (.32f + charge * .55f) * entry;
        float zoom = showing ? 1.065f : 1f + charge * .065f;
        // Only SSR exposes the whole painting. SR fades a central patch into the black field.
        if (rank == 2) texture(background, 640, 360, 1280 * zoom, 720 * zoom, 0, brightness, brightness, brightness, 1);
        else if (rank == 1) centralPortal(zoom, brightness, entry);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        if (rank > 0) {
            if (!showing) {
                glow(CX, CY, rank == 2 ? 420 + charge * 90 : 220 + charge * 30, 0x83ff65, (rank == 2 ? .12f : .06f) + charge * (rank == 2 ? .18f : .07f));
                glow(CX, CY, rank == 2 ? 240 + charge * 85 : 100 + charge * 50, 0xfaffec, (rank == 2 ? .35f : .15f) + charge * (rank == 2 ? .45f : .12f));
            } else glow(CX, CY, rank == 2 ? 315 : 210, accent, .12f + (1 - reveal) * .12f);
        }
        float motion = s.totalAge();
        if (rank > 0) {
            ring(CX, CY, rank == 2 ? 274 : 174, .24f, motion * .12f, 0x9aff7a, (showing ? .08f : rank == 2 ? .35f : .16f) * entry);
            if (rank == 2) ring(CX, CY, 320, .18f, -motion * .09f, accent, (showing ? .12f : .28f) * entry);
        }
        // Round inner sparks travel inward; tapered foreground lights drift toward the camera.
        for (int i = 0, count = rank == 2 ? INNER_SPARKS : rank == 1 ? 72 : 0; i < count; i++) {
            float depth = fract(phases[i] + motion * (0.12f + charge * 0.09f) * speeds[i]);
            float radius = rank == 2 ? 205 + 160 * (1f - depth) : 90 + 100 * (1f - depth);
            float angle = angles[i] + motion * (0.07f + speeds[i] * 0.04f) + depth * 0.32f;
            float px = CX + cos(angle) * radius, py = CY + sin(angle) * radius;
            float opacity = sin(depth * (float) Math.PI) * (0.28f + charge * 0.53f) * entry;
            if (showing) opacity *= 0.20f;
            int c = i % 4 == 0 ? 0xf4ffc3 : i % 3 == 0 ? 0xb8ff79 : 0x67e48d;
            glow(px, py, sizes[i] * 3.4f, c, opacity * 0.32f);
            glow(px, py, sizes[i] * 0.85f, c, opacity);
        }
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (int i = INNER_SPARKS, end = INNER_SPARKS + (rank == 2 ? FOREGROUND_SPINDLES : rank == 1 ? 24 : 0); i < end; i++) {
            float depth = fract(phases[i] + motion * 0.14f * speeds[i]);
            float radius = rank == 2 ? 240 + depth * depth * 565 : 85 + depth * depth * 200;
            float angle = angles[i] - motion * 0.055f + depth * 0.17f;
            float px = CX + cos(angle) * radius, py = CY + sin(angle) * radius * 0.8f;
            if (px < -35 || px > WIDTH + 35 || py < -35 || py > HEIGHT + 35) continue;
            float opacity = (float) Math.sin(depth * Math.PI) * (showing ? 0.17f : rank == 2 ? 0.67f : 0.35f) * entry;
            float size = sizes[i] * (1f + depth * 3f);
            int c = GLASS_COLORS[i % GLASS_COLORS.length];
            float tilt = angle + motion * speeds[i] * 0.3f;
            spindle(px, py, size * 3.2f, size * 0.85f, tilt, c, opacity);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            spindle(px, py, size * 4.5f, size * 1.8f, tilt, c, opacity * 0.22f);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        // Separate circular bokeh layer: broad soft discs, with no rectangular particle cores.
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        for (int i = INNER_SPARKS + FOREGROUND_SPINDLES; i < phases.length; i++) {
            float depth = fract(phases[i] + motion * 0.095f * speeds[i]);
            float radius = 230 + depth * depth * 620;
            float angle = angles[i] + motion * 0.025f + depth * 0.12f;
            float px = CX + cos(angle) * radius, py = CY + sin(angle) * radius * 0.85f;
            float size = sizes[i] * (1.2f + depth * 3.8f);
            if (px < -size || px > WIDTH + size || py < -size || py > HEIGHT + size) continue;
            float opacity = sin(depth * (float) Math.PI) * (rank == 0 ? .22f : showing ? .10f : rank == 1 ? .16f : .36f) * entry;
            int c = GLASS_COLORS[i % GLASS_COLORS.length];
            bokeh(px, py, size, c, opacity);
        }
        float burstAge = t - s.chargeDuration();
        if (rank == 2 && burstAge >= 0 && burstAge < 1.2f) {
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            expandingRing(burstAge, accent);
        }
        // White-out precedes the dark silhouette. Never cover the window's native buttons.
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        float flash = s.flash();
        if (rank == 2 && flash > 0) quad(640, 360, 1280, 720, 0, 0xf6ffdf, flash * .9f);
        else if (rank == 1 && flash > 0) { GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE); glow(CX, CY, 220, 0xddfff1, flash * .5f); GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA); }
        float emergence = rank == 0 ? (showing ? reveal : 0) : Moci_GachaRevealSequence.clamp((t - s.chargeDuration() - .15f) / (rank == 1 ? .55f : .9f));
        if (drawHero && emergence > 0) {
            float size = showing ? (0.9f + 0.1f * backOut(s.revealProgress())) : 0.35f + 0.55f * smooth(emergence);
            float ratio = iconRatios[assetIndex];
            float width = ratio >= 1 ? 390 : 330 * ratio;
            float height = ratio >= 1 ? 390 / ratio : 330;
            float nativeLimit = nativeLimit(assetIndex, width, height);
            width *= nativeLimit; height *= nativeLimit;
            if (fixedUiSizes[assetIndex] > 0) {
                float f = fixedUiSizes[assetIndex];
                width = (ratio >= 1 ? f : f * ratio) / stageScaleX;
                height = (ratio >= 1 ? f / ratio : f) / stageScaleY;
            }
            size = Math.min(1f, size); // The reveal bounce must never enlarge a source pixel.
            float rise = showing ? (1f - reveal) * -16f : -28 * (1f - emergence);
            if (showing) {
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                if (rank > 0) stars(motion, rank, accent, reveal);
                glow(CX, CY, 180, accent, (1f - reveal) * 0.3f);
            }
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (models[assetIndex] != null) {
                float value = showing ? 0.12f + 0.88f * reveal : 0.025f;
                // Fixed facing from the first silhouette frame; this never animates the heading.
                GL11.glPushMatrix();
                try {
                    GL11.glTranslatef(CX, CY + rise, 0);
                    if (faceDown[assetIndex]) GL11.glRotatef(180f, 0, 0, 1);
                    models[assetIndex].renderCentered(0, 0, width * size, height * size,
                            emergence * alpha, new Color(value, value, value));
                } finally { GL11.glPopMatrix(); restoreBlend(); }
            } else if (icons[assetIndex] != null) {
                float value = showing ? 0.12f + 0.88f * reveal : 0.025f;
                texture(icons[assetIndex], CX, CY + rise, width * size, height * size, faceDown[assetIndex] ? 180f : 0f,
                        value, value, value, emergence);
                restoreBlend();
            } else {
                // An untextured signal diamond is an honest fallback for an unavailable icon.
                quad(CX, CY + rise, 110 * size, 110 * size, (float) Math.PI / 4, showing ? accent : 0x08100b, emergence);
                quad(CX, CY + rise, 100 * size, 100 * size, (float) Math.PI / 4, 0x0b1911, emergence);
            }
        }
        if (drawHero && showing) rarityStars(CX, 594, rank, 18, reveal);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // Quiet top/bottom fade keeps native stage labels readable over the bright painting.
        gradient(0, HEIGHT, WIDTH, -100, 0x07100e, 0.55f, 0f);
        gradient(0, 0, WIDTH, 80, 0x07100e, 0.65f, 0f);
    }

    // Logical coordinates shared by rendering, native labels, previews and hit testing.
    public static final float CARD_W = 394f, CARD_H = 100f;
    public static final float FEATURE_X = 221f, FEATURE_Y = 394f;
    public static float cardX(int slot) { return 621f + (slot % 2) * 418f; }
    public static float cardY(int slot) { return 586f - (slot / 2) * 112f; }
    public static float cardArtX(int slot) { return cardX(slot) - CARD_W / 2 + 62f; }
    public static float cardTextX(int slot) { return cardX(slot) - CARD_W / 2 + 126f; }
    public static float cardTextWidth() { return CARD_W - 148f; }

    /** Never selects an unrevealed reward. Finished pages default to their highest rarity. */
    public static int featuredIndex(Moci_GachaBatchSequence batch, int selected) {
        if (selected >= batch.pageStart() && selected < batch.pageEnd() && batch.visible(selected)) return selected;
        int best = -1;
        for (int i = batch.pageStart(); i < batch.pageEnd(); i++) if (batch.visible(i)) {
            if (!batch.pageComplete() || best < 0 || batch.rankAt(i) > batch.rankAt(best)) best = i;
        }
        return best;
    }

    private void drawCards(Moci_GachaBatchSequence batch) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        float land = smooth(batch.landing());
        quad(CX, 360, WIDTH, HEIGHT, 0, 0x0a0f14, .62f);
        int featured = featuredIndex(batch, selectedIndex);
        // One quiet showcase, with thin brass rules rather than ten luminous frames.
        quad(FEATURE_X, 362, 354, 556, 0, 0x141d24, .94f * land);
        quad(FEATURE_X, 640, 354, 1, 0, 0xcaaf73, .8f * land);
        quad(FEATURE_X, 84, 354, 1, 0, 0x303e47, land);
        quad(FEATURE_X, FEATURE_Y, 306, 198, 0, 0x0c141a, .95f * land);
        quad(FEATURE_X, 294, 306, 1, 0, 0x303e47, land);
        if (featured >= 0) {
            if (fixedUiSizes[featured] > 0) {
                float f = fixedUiSizes[featured];
                drawAsset(featured, FEATURE_X, FEATURE_Y, f / stageScaleX, f / stageScaleY, 1, land);
            } else drawAsset(featured, FEATURE_X, FEATURE_Y, 280, 176, 1, land);
            rarityStars(FEATURE_X, 264, batch.rankAt(featured), 15, land, PANEL_COLORS[batch.rankAt(featured)]);
        } else seal(FEATURE_X, FEATURE_Y, 1.4f, land);
        for (int i = batch.pageStart(); i < batch.pageEnd(); i++) {
            int slot = i - batch.pageStart();
            float x = CX + (cardX(slot) - CX) * land, y = CY + (cardY(slot) - CY) * land;
            float scale = .8f + .2f * land;
            boolean visible = batch.visible(i), selected = i == featured;
            int accent = visible ? PANEL_COLORS[batch.rankAt(i)] : 0x51606a;
            quad(x, y, CARD_W * scale, CARD_H * scale, 0, selected ? 0x26313a : 0x141d24, .95f * land);
            quad(x, y - CARD_H / 2 * scale, CARD_W * scale, 1, 0, selected ? accent : 0x303e47, .8f * land);
            if (selected) quad(x - CARD_W / 2 * scale + 1, y, 2, CARD_H * scale, 0, accent, land);
            float artX = x + (cardArtX(slot) - cardX(slot)) * scale;
            quad(artX, y, 92 * scale, 84 * scale, 0, 0x0c141a, .9f * land);
            if (visible) {
                float progress = smooth(batch.cardProgress(i));
                drawAsset(i, artX, y, 84 * scale, 78 * scale, progress, land);
                if (progress < 1) quad(x, y, CARD_W * scale, CARD_H * scale, 0, accent, (1 - progress) * .18f);
                rarityStars(x + CARD_W / 2 * scale - 53 * scale, y + 30 * scale, batch.rankAt(i), 8 * scale, land, PANEL_COLORS[batch.rankAt(i)]);
            } else seal(artX, y, .65f * scale, land);
        }
    }

    /** Dry inventory seal: no ring, spindle or glow on the unrevealed result. */
    private void seal(float x, float y, float scale, float opacity) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(1f);
        setColor(0x88949b, opacity * .65f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x - 18 * scale, y - 23 * scale);
        GL11.glVertex2f(x + 9 * scale, y - 23 * scale);
        GL11.glVertex2f(x + 18 * scale, y - 14 * scale);
        GL11.glVertex2f(x + 18 * scale, y + 23 * scale);
        GL11.glVertex2f(x - 18 * scale, y + 23 * scale);
        GL11.glEnd();
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(x - 10 * scale, y - 10 * scale);
        GL11.glVertex2f(x - 10 * scale, y + 10 * scale);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + 10 * scale, y + 10 * scale);
        GL11.glVertex2f(x + 10 * scale, y - 10 * scale);
        GL11.glEnd();
    }

    /** Large filled five-point stars; 1/2/3 for R/SR/SSR. */
    private void rarityStars(float x, float y, int rank, float radius, float opacity) {
        rarityStars(x,y,rank,radius,opacity,RARITY_COLORS[rank]);
    }
    private void rarityStars(float x, float y, int rank, float radius, float opacity, int color) {
        restoreBlend();
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        for (int i = 0; i <= rank; i++) glow(x + (i - rank * .5f) * radius * 2.7f, y, radius * 1.6f, color, opacity * .22f);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (int i = 0; i <= rank; i++) {
            float cx = x + (i - rank * .5f) * radius * 2.7f;
            GL11.glBegin(GL11.GL_TRIANGLE_FAN); setColor(color, opacity); GL11.glVertex2f(cx, y);
            for (int v = 0; v <= 10; v++) {
                float a = (float) Math.PI * .5f + v * (float) Math.PI / 5, r = v % 2 == 0 ? radius : radius * .44f;
                GL11.glVertex2f(cx + cos(a) * r, y + sin(a) * r);
            }
            GL11.glEnd();
        }
    }

    private void centralPortal(float zoom, float brightness, float opacity) {
        if (background == null) return;
        GL11.glEnable(GL11.GL_TEXTURE_2D); background.bindTexture();
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        portalVertex(CX, CY, zoom, brightness, opacity);
        for (int i = 0; i <= 48; i++) {
            float a = i * (float) (Math.PI * 2 / 48);
            portalVertex(CX + cos(a) * 190, CY + sin(a) * 135, zoom, brightness, opacity);
        }
        GL11.glEnd();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= 48; i++) {
            float a = i * (float) (Math.PI * 2 / 48), ca = cos(a), sa = sin(a);
            portalVertex(CX + ca * 190, CY + sa * 135, zoom, brightness, opacity);
            portalVertex(CX + ca * 410, CY + sa * 295, zoom, brightness, 0);
        }
        GL11.glEnd(); GL11.glDisable(GL11.GL_TEXTURE_2D);
    }
    private void portalVertex(float x, float y, float zoom, float brightness, float opacity) {
        GL11.glColor4f(brightness, brightness, brightness, opacity * alpha);
        GL11.glTexCoord2f(((x - 640) / (1280 * zoom) + .5f) * background.getTextureWidth(), ((y - 360) / (720 * zoom) + .5f) * background.getTextureHeight());
        GL11.glVertex2f(x, y);
    }

    /** Source pixels are UI pixels: compensate for the logical stage transform before capping. */
    private float nativeLimit(int index, float width, float height) {
        if (nativeWidths[index] <= 0 || nativeHeights[index] <= 0) return 1f;
        return Math.min(1f, Math.min(nativeWidths[index] / (width * stageScaleX),
                nativeHeights[index] / (height * stageScaleY)));
    }

    /** 让某个奖品（军官头像）以固定 UI 像素边长显示；只影响特写和重点展示，卡片小图仍按格子缩放。 */
    public void setFixedUiSize(int index, float uiPixels) {
        if (index >= 0 && index < fixedUiSizes.length) fixedUiSizes[index] = Math.max(0f, uiPixels);
    }

    private void drawAsset(int index, float x, float y, float maxW, float maxH, float progress, float opacity) {
        float ratio = iconRatios[index], h = Math.min(maxH, maxW / Math.max(.01f, ratio)), w = h * ratio;
        float nativeLimit = nativeLimit(index, w, h);
        w *= nativeLimit; h *= nativeLimit;
        float value = .025f + .975f * progress;
        if (models[index] != null) {
            GL11.glPushMatrix();
            try {
                GL11.glTranslatef(x, y, 0);
                if (faceDown[index]) GL11.glRotatef(180, 0, 0, 1);
                models[index].renderCentered(0, 0, w, h, opacity * alpha, new Color(value, value, value));
            } finally { GL11.glPopMatrix(); }
            restoreBlend();
        } else if (icons[index] != null) {
            texture(icons[index], x, y, w, h, faceDown[index] ? 180 : 0, value, value, value, opacity);
            restoreBlend();
        } else {
            ring(x, y, 22, .2f, 0, 0x9db3a1, opacity * .5f);
        }
    }

    private void expandingRing(float age, int c) {
        float radius = 200 + age * 580, opacity = (1f - age / 1.2f) * 0.65f;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= 128; i++) {
            float a = i * (float) (Math.PI * 2 / 128);
            setColor(c, 0); GL11.glVertex2f(CX + cos(a) * (radius - 14), CY + sin(a) * (radius - 14));
            setColor(c, opacity); GL11.glVertex2f(CX + cos(a) * radius, CY + sin(a) * radius);
        }
        GL11.glEnd();
    }

    private void stars(float time, int rank, int c, float progress) {
        int count = 10 + rank * 8;
        for (int i = 0; i < count; i++) {
            float a = angles[i] + time * (i % 2 == 0 ? 0.035f : -0.045f);
            float r = 210 + phases[i] * 100;
            float x = CX + cos(a) * r, y = CY + sin(a) * r * 0.82f;
            float pulse = (0.35f + 0.65f * (float) Math.pow((sin(time * 2 + i * 2.4f) + 1) * 0.5f, 3)) * progress;
            glow(x, y, 16, c, pulse * 0.25f);
            spindle(x, y, 12 + rank * 4, 2, 0, c, pulse * 0.75f);
            spindle(x, y, 12 + rank * 4, 2, (float) Math.PI / 2, c, pulse * 0.75f);
        }
    }

    private void ring(float x, float y, float radius, float gap, float rotation, int c, float opacity) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_LINES);
        setColor(c, opacity);
        for (int i = 0; i < 180; i++) {
            float a = i * (float) (Math.PI * 2 / 180) + rotation;
            float b = a + gap * 0.035f;
            GL11.glVertex2f(x + cos(a) * radius, y + sin(a) * radius);
            GL11.glVertex2f(x + cos(b) * radius, y + sin(b) * radius);
        }
        GL11.glEnd();
    }

    private void texture(SpriteAPI sprite, float x, float y, float w, float h, float angle,
                         float r, float g, float b, float opacity) {
        if (sprite == null) return;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        sprite.bindTexture();
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glColor4f(r, g, b, opacity * alpha);
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0); GL11.glRotatef(angle, 0, 0, 1);
        float tw = sprite.getTextureWidth(), th = sprite.getTextureHeight();
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-w / 2, -h / 2);
        GL11.glTexCoord2f(tw, 0); GL11.glVertex2f(w / 2, -h / 2);
        GL11.glTexCoord2f(tw, th); GL11.glVertex2f(w / 2, h / 2);
        GL11.glTexCoord2f(0, th); GL11.glVertex2f(-w / 2, h / 2);
        GL11.glEnd(); GL11.glPopMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    private void glow(float x, float y, float radius, int c, float opacity) {
        if (opacity * alpha <= .002f) return;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        setColor(c, opacity); GL11.glVertex2f(x, y);
        int n = segments(radius);
        for (int i = 0; i <= n; i++) {
            float a = i * (float) (Math.PI * 2 / n);
            setColor(c, 0); GL11.glVertex2f(x + cos(a) * radius, y + sin(a) * radius);
        }
        GL11.glEnd();
    }

    /** Lens-shaped light, pinched to a point at each end, fading smoothly at the edge. */
    private void spindle(float x, float y, float length, float width, float angle, int c, float opacity) {
        if (opacity * alpha <= .002f) return;
        float ca = cos(angle), sa = sin(angle);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        setColor(c, opacity); GL11.glVertex2f(x, y);
        int n = Math.max(12, segments(length * .5f));
        for (int i = 0; i <= n; i++) {
            float a = i * (float) (Math.PI * 2 / n), sy = sin(a);
            float lx = cos(a) * length * 0.5f, ly = sy * Math.abs(sy) * width * 0.5f;
            setColor(c, 0); GL11.glVertex2f(x + lx * ca - ly * sa, y + lx * sa + ly * ca);
        }
        GL11.glEnd();
    }

    private void bokeh(float x, float y, float radius, int c, float opacity) {
        if (opacity * alpha <= .002f) return;
        int n = segments(radius);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        setColor(c, opacity); GL11.glVertex2f(x, y);
        for (int i = 0; i <= n; i++) {
            float a = i * (float) (Math.PI * 2 / n);
            setColor(c, opacity * 0.7f);
            GL11.glVertex2f(x + cos(a) * radius * 0.65f, y + sin(a) * radius * 0.65f);
        }
        GL11.glEnd();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= n; i++) {
            float a = i * (float) (Math.PI * 2 / n), dx = cos(a) * radius, dy = sin(a) * radius;
            setColor(c, opacity * 0.7f); GL11.glVertex2f(x + dx * 0.65f, y + dy * 0.65f);
            setColor(c, 0); GL11.glVertex2f(x + dx, y + dy);
        }
        GL11.glEnd();
    }

    private void quad(float x, float y, float w, float h, float angle, int c, float opacity) {
        float cos = cos(angle), sin = sin(angle), hw = w / 2, hh = h / 2;
        setColor(c, opacity);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x - hw * cos + hh * sin, y - hw * sin - hh * cos);
        GL11.glVertex2f(x + hw * cos + hh * sin, y + hw * sin - hh * cos);
        GL11.glVertex2f(x + hw * cos - hh * sin, y + hw * sin + hh * cos);
        GL11.glVertex2f(x - hw * cos - hh * sin, y - hw * sin + hh * cos);
        GL11.glEnd();
    }

    private void gradient(float x, float y, float width, float height, int c, float from, float to) {
        GL11.glBegin(GL11.GL_QUADS);
        setColor(c, from); GL11.glVertex2f(x, y); GL11.glVertex2f(x + width, y);
        setColor(c, to); GL11.glVertex2f(x + width, y + height); GL11.glVertex2f(x, y + height);
        GL11.glEnd();
    }
    private void setColor(int c, float opacity) {
        GL11.glColor4f(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, opacity * alpha);
    }
    public static Color rarityColor(int rank) { return new Color(PANEL_COLORS[Math.max(0, Math.min(2, rank))]); }
    private static float sin(float a) { return (float) Math.sin(a); }
    private static float cos(float a) { return (float) Math.cos(a); }
    private static float fract(float a) { return a - (float) Math.floor(a); }
    private static float smooth(float a) { return a * a * (3 - 2 * a); }
    private static float backOut(float a) { float b = a - 1; return 1 + 2.4f * b * b * b + 1.4f * b * b; }
}
