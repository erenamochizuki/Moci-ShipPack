package data.scripts.weapons.render;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import org.apache.log4j.Logger;

/**
 * 黑洞吸积云渲染器（纯视觉，不参与任何玩法判定）：
 * - 在奇点周围画吸积云、旋臂、核心辉光、横向光晕、电丝和光子环，全部由着色器程序化生成，不用贴图
 * - 奇点的位置、视界半径、蒸发状态都从 {@link Moci_SingularityRenderer} 读取，投射物必须先在那边登记
 * - 先把盘光画入战场，奇点透镜随后一次采样已包含盘面和舰船的画面。
 * - 横向光晕随黑洞长大（质量增加）变长、变亮。
 * - 黑洞消散时不是原地淡出，而是「爆发」：核心闪光，吸积物质被抛向外侧，
 *   一圈参差的冲击光环和几道放射状碎屑向外扩散，最后一起消失。
 *   玩法上的扩散冲击（伤害、扭曲波纹）仍由 Moci_GravityCannonChargeGlow 负责，这里只是视觉。
 */
public final class Moci_BlackHoleAccretionRenderer extends BaseCombatLayeredRenderingPlugin {

    public static final String RENDER_KEY = "Moci_BlackHoleAccretionRenderer";
    private static final Logger LOG = Global.getLogger(Moci_BlackHoleAccretionRenderer.class);

    private static final String VERT_PATH = "data/shaders/Moci_BlackHoleAccretion.vert";
    private static final String FRAG_PATH = "data/shaders/Moci_BlackHoleAccretion.frag";
    private static final String SHADER_KEY = "Moci_BlackHoleAccretion";

    private static final CombatEngineLayers DISK_LAYER = CombatEngineLayers.ABOVE_PARTICLES;

    // ========================================
    // 可调参数（半径均为世界单位）
    // ========================================

    /** 横向光晕半长 = 吸积云外缘半径 × 此倍数（不小于 1） */
    public static float FLARE_LENGTH_MULT = 1.4f;
    /** 黑洞长满时横向光晕额外加长的倍数：半长倍数 = FLARE_LENGTH_MULT + 此值 × 成长度 */
    public static float FLARE_GROWTH_LENGTH_MULT = 1.6f;
    /** 淡入时长（秒） */
    public static float FADE_IN_TIME = 0.8f;
    /** 奇点蒸发或被清理后的爆发时长（秒） */
    public static float FADE_OUT_TIME = 1.4f;
    /** 爆发时绘制范围的扩大比例（冲击光环和被抛出的物质需要更大的四边形） */
    public static float BURST_QUAD_EXPAND = 1.3f;
    /** 爆发开始时的核心闪光：尺寸 = 吸积云半径 × 此倍数 */
    public static float BURST_FLASH_SIZE_MULT = 1.8f;
    /** 爆发开始时的核心闪光持续时间（秒） */
    public static float BURST_FLASH_DURATION = 0.35f;
    /** 爆发后的余辉：尺寸 = 吸积云半径 × 此倍数 */
    public static float BURST_AFTERGLOW_SIZE_MULT = 3.2f;
    /** 爆发后的余辉持续时间（秒） */
    public static float BURST_AFTERGLOW_DURATION = 1.1f;
    /** 整体亮度倍率 */
    public static float BRIGHTNESS = 1.0f;
    /** 旋转方向：正数逆时针，负数顺时针 */
    public static float SPIN_DIRECTION = 1f;
    /** 核心、光子环、光晕的颜色 */
    public static Color COLOR_CORE = new Color(255, 248, 236);
    /** 吸积云内侧的颜色 */
    public static Color COLOR_MID = new Color(226, 186, 160);
    /** 吸积云外缘的颜色 */
    public static Color COLOR_EDGE = new Color(128, 88, 70);

    private static final class Hole {
        final DamagingProjectileAPI projectile;
        final Vector2f center = new Vector2f();
        final float seed;
        float radius = 1f;
        float phase = 0f;
        float fadeIn = 0f;
        float death = 0f;
        boolean dying = false;
        /** 爆发开始时冻结的视界半径：之后透镜那边的半径会蒸发缩小，爆发的尺寸不跟着缩 */
        float burstRadius = -1f;

        Hole(DamagingProjectileAPI projectile) {
            this.projectile = projectile;
            this.center.set(projectile.getLocation());
            this.seed = (float) Math.random() * 50f;
        }
    }

    private final CombatEngineAPI engine;
    private final List<Hole> holes = new ArrayList<Hole>();

    // program 由 Moci_ShaderManager 按 key 缓存并跨战斗复用，本类不负责删除。
    private int program = 0;
    private int uTime = -1;
    private int uPhase = -1;
    private int uShape = -1;
    private int uAlpha = -1;
    private int uSpin = -1;
    private int uColCore = -1;
    private int uColMid = -1;
    private int uColEdge = -1;
    private int uGrowth = -1;

    private float time = 0f;

    public static Moci_BlackHoleAccretionRenderer getOrCreate() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return null;
        Object existing = engine.getCustomData().get(RENDER_KEY);
        if (existing instanceof Moci_BlackHoleAccretionRenderer) {
            return (Moci_BlackHoleAccretionRenderer) existing;
        }
        Moci_BlackHoleAccretionRenderer renderer = new Moci_BlackHoleAccretionRenderer();
        engine.addLayeredRenderingPlugin(renderer);
        engine.getCustomData().put(RENDER_KEY, renderer);
        return renderer;
    }

    private Moci_BlackHoleAccretionRenderer() {
        this.engine = Global.getCombatEngine();
        this.layer = DISK_LAYER;
        initShader();
    }

    /**
     * 开始为一枚奇点投射物绘制吸积云。投射物需已在 {@link Moci_SingularityRenderer} 登记。
     */
    public void track(DamagingProjectileAPI projectile) {
        if (projectile == null || isTracked(projectile)) return;
        holes.add(new Hole(projectile));
        Moci_SingularityRenderer lens = Moci_SingularityRenderer.getOrCreate();
        if (program != 0 && lens != null) lens.enableAccretionLens(projectile);
    }

    public boolean isTracked(DamagingProjectileAPI projectile) {
        for (Hole h : holes) {
            if (h.projectile == projectile) return true;
        }
        return false;
    }

    @Override
    public void advance(float amount) {
        if (engine == null || engine.isPaused()) return;

        time += amount;
        if (holes.isEmpty()) return;

        Moci_SingularityRenderer singularity = Moci_SingularityRenderer.getOrCreate();
        for (int i = holes.size() - 1; i >= 0; i--) {
            Hole h = holes.get(i);
            boolean tracked = singularity != null && singularity.isTracked(h.projectile);
            if (tracked) {
                h.radius = singularity.getEventHorizonRadiusWorld(h.projectile);
                if (singularity.isEvaporating(h.projectile)) h.dying = true;
            } else {
                // 奇点已被透镜渲染器清理：沿用最后一次的位置和半径淡出
                h.dying = true;
            }

            // Integrate speed, not time * current speed: growth must never rewind the arms.
            h.phase += amount * Moci_BlackHoleDiskProfile.angularSpeed(Moci_BlackHoleDiskProfile.growth(h.radius));
            if (h.dying) {
                if (h.burstRadius < 0f) startBurst(h);
                h.death += amount / Math.max(0.05f, FADE_OUT_TIME);
                if (h.death >= 1f) holes.remove(i);
            } else {
                h.fadeIn = Math.min(1f, h.fadeIn + amount / Math.max(0.05f, FADE_IN_TIME));
            }
        }
    }

    /** 爆发开始：冻结尺寸，并在中心放一团闪光和余辉粒子。 */
    private void startBurst(Hole h) {
        h.burstRadius = Math.max(1f, h.radius);
        if (engine == null) return;
        float cloud = Moci_BlackHoleDiskProfile.cloudRadius(h.burstRadius, Moci_BlackHoleDiskProfile.growth(h.burstRadius));
        Vector2f still = new Vector2f();
        engine.addHitParticle(new Vector2f(h.center), still, cloud * BURST_FLASH_SIZE_MULT, 1f,
                BURST_FLASH_DURATION, COLOR_CORE);
        engine.addSmoothParticle(new Vector2f(h.center), still, cloud * BURST_AFTERGLOW_SIZE_MULT, 0.55f,
                BURST_AFTERGLOW_DURATION, COLOR_MID);
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (layer != DISK_LAYER || holes.isEmpty() || program == 0) return;

        // 与透镜渲染器保持一致：指挥地图和部署界面下透镜不画黑色视界，这里也不画，否则核心会是一团没有遮挡的白光。
        if (engine != null && engine.getCombatUI() != null
                && (engine.getCombatUI().isShowingCommandUI() || engine.getCombatUI().isShowingDeploymentDialog())) {
            return;
        }

        float viewAlpha = viewport.getAlphaMult();
        if (viewAlpha <= 0f) return;
        Moci_SingularityRenderer singularity = Moci_SingularityRenderer.getOrCreate();
        // Same visible horizon cap as the screen-space lens when the camera zooms very close.
        float maxVisibleRs = 0.45f * Math.min(viewport.getVisibleWidth(), viewport.getVisibleHeight());

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

            GL20.glUseProgram(program);
            GL20.glUniform1f(uTime, time);
            GL20.glUniform1f(uSpin, SPIN_DIRECTION >= 0f ? 1f : -1f);
            setColor(uColCore, COLOR_CORE);
            setColor(uColMid, COLOR_MID);
            setColor(uColEdge, COLOR_EDGE);

            for (Hole h : holes) {
                if (singularity != null && singularity.isTracked(h.projectile)) {
                    // Read the live/frozen position in render, just as the lens does, not a tick earlier.
                    Vector2f c = singularity.getCenterWorld(h.projectile);
                    if (c != null) h.center.set(c);
                    h.radius = singularity.getEventHorizonRadiusWorld(h.projectile);
                }
                float radius = h.burstRadius > 0f ? h.burstRadius : h.radius;
                float growth = Moci_BlackHoleDiskProfile.growth(radius);
                float alpha = getAlpha(h) * viewAlpha * BRIGHTNESS * Moci_BlackHoleDiskProfile.brightness(growth);
                if (alpha <= 0.002f) continue;

                float rs = Math.max(1f, Math.min(radius, maxVisibleRs));
                float cloudRadius = Moci_BlackHoleDiskProfile.cloudRadius(rs, growth);
                float half = cloudRadius * Math.max(1f, FLARE_LENGTH_MULT + FLARE_GROWTH_LENGTH_MULT * growth);
                if (h.dying) {
                    float e = 1f - (1f - h.death) * (1f - h.death) * (1f - h.death);
                    half *= 1f + BURST_QUAD_EXPAND * e;
                }
                if (!viewport.isNearViewport(h.center, half)) continue;

                // 着色器里的半径都以四边形半边长为 1
                GL20.glUniform4f(uShape, rs / half, cloudRadius / half, h.death, h.seed);
                GL20.glUniform1f(uGrowth, growth);
                GL20.glUniform1f(uAlpha, alpha);
                GL20.glUniform1f(uPhase, h.phase);
                drawQuad(h.center.x, h.center.y, half);
            }
        } finally {
            GL20.glUseProgram(0);
            GL11.glPopAttrib();
        }
    }

    /** 只管淡入；消散阶段的明暗由着色器按 death 处理（爆发先亮后灭）。 */
    private static float getAlpha(Hole h) {
        if (h.death >= 1f) return 0f;
        return h.fadeIn * h.fadeIn * (3f - 2f * h.fadeIn);
    }

    private static void setColor(int location, Color color) {
        GL20.glUniform3f(location, color.getRed() / 255f, color.getGreen() / 255f, color.getBlue() / 255f);
    }

    /** 纹理坐标即四边形局部坐标（-1..1），着色器用它算到中心的距离。 */
    private static void drawQuad(float cx, float cy, float half) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(-1f, -1f); GL11.glVertex2f(cx - half, cy - half);
        GL11.glTexCoord2f(-1f, 1f);  GL11.glVertex2f(cx - half, cy + half);
        GL11.glTexCoord2f(1f, 1f);   GL11.glVertex2f(cx + half, cy + half);
        GL11.glTexCoord2f(1f, -1f);  GL11.glVertex2f(cx + half, cy - half);
        GL11.glEnd();
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(DISK_LAYER);
    }

    @Override
    public boolean isExpired() {
        // 常驻到战斗结束，由引擎清理
        return false;
    }

    @Override
    public float getRenderRadius() {
        return 1000000f;
    }

    @Override
    public void cleanup() {
        holes.clear();
        // program 留在 Moci_ShaderManager 的缓存里，这里只丢弃引用
        program = 0;
    }

    private void initShader() {
        try {
            program = Moci_ShaderManager.getInstance().createShader(VERT_PATH, FRAG_PATH, SHADER_KEY);
            if (program == 0) {
                LOG.warn("[MOCI_BLACKHOLE] shader unavailable, accretion visuals disabled");
                return;
            }
            uTime = GL20.glGetUniformLocation(program, "u_time");
            uPhase = GL20.glGetUniformLocation(program, "u_phase");
            uShape = GL20.glGetUniformLocation(program, "u_shape");
            uAlpha = GL20.glGetUniformLocation(program, "u_alpha");
            uSpin = GL20.glGetUniformLocation(program, "u_spin");
            uColCore = GL20.glGetUniformLocation(program, "u_colCore");
            uColMid = GL20.glGetUniformLocation(program, "u_colMid");
            uColEdge = GL20.glGetUniformLocation(program, "u_colEdge");
            uGrowth = GL20.glGetUniformLocation(program, "u_growth");
        } catch (Exception e) {
            program = 0;
            LOG.error("[MOCI_BLACKHOLE] shader init failed", e);
        }
    }
}
