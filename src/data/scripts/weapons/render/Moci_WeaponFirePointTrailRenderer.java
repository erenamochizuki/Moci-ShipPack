package data.scripts.weapons.render;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.dark.shaders.util.ShaderLib;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.nio.ByteBuffer;

/**
 * 以武器开火点为锚点的拖尾渲染器。
 * 支持离屏FBO合成，复用单个缓冲，并在生命周期结束时主动释放显存资源。
 */
public class Moci_WeaponFirePointTrailRenderer extends BaseCombatLayeredRenderingPlugin {

    private static final float MIN_INTENSITY_TO_RENDER = 0.01f;
    private static final int MIN_BUFFER_SIZE = 64;
    private static final int BUFFER_PADDING = 48;

    private final WeaponAPI weapon;
    private final SpriteAPI texturePrimary;
    private final SpriteAPI textureDetail;
    private final int firePointIndex;

    private CombatEngineAPI engine;
    private boolean shouldExpire = false;

    private float targetIntensity = 1f;
    private float intensity = 1f;
    private float intensityChangeSpeed = 6f;

    private float textureRun = 0f;
    private float scrollSpeed = 0.55f;
    private final float uvRandomShift = (float) Math.random();

    private int segment = 84;
    private float widthStart = 0.30f;
    private float widthEnd = 0.82f;
    private float trailLength = 220f;
    private float trailWidth = 36f;

    private Color primaryStartColor = new Color(230, 245, 255, 190);
    private Color primaryEndColor = new Color(120, 190, 255, 120);
    private Color detailStartColor = new Color(255, 255, 255, 150);
    private Color detailEndColor = new Color(255, 240, 165, 90);

    private BufferWithData bufferWithData;

    public Moci_WeaponFirePointTrailRenderer(WeaponAPI weapon, int firePointIndex, SpriteAPI texturePrimary, SpriteAPI textureDetail) {
        this.weapon = weapon;
        this.firePointIndex = firePointIndex;
        this.texturePrimary = texturePrimary;
        this.textureDetail = textureDetail != null ? textureDetail : texturePrimary;
        this.layer = CombatEngineLayers.ABOVE_SHIPS_LAYER;
    }

    public void setTargetIntensity(float targetIntensity) {
        this.targetIntensity = clamp(targetIntensity, 0f, 1f);
    }

    public void setIntensityChangeSpeed(float intensityChangeSpeed) {
        this.intensityChangeSpeed = Math.max(0.01f, intensityChangeSpeed);
    }

    public void setScrollSpeed(float scrollSpeed) {
        this.scrollSpeed = scrollSpeed;
    }

    public void setTrailShape(float trailLength, float trailWidth, int segment) {
        this.trailLength = Math.max(1f, trailLength);
        this.trailWidth = Math.max(1f, trailWidth);
        this.segment = Math.max(4, segment);
        rebuildBuffer();
    }

    public void forceExpire() {
        shouldExpire = true;
    }

    @Override
    public void init(CombatEntityAPI entity) {
        super.init(entity);
        this.engine = Global.getCombatEngine();
        rebuildBuffer();
    }

    @Override
    public float getRenderRadius() {
        return trailLength + trailWidth + 250f;
    }

    @Override
    public boolean isExpired() {
        return shouldExpire;
    }

    @Override
    public void advance(float amount) {
        if (engine == null) {
            engine = Global.getCombatEngine();
        }

        if (shouldAutoExpire()) {
            shouldExpire = true;
            cleanupResources();
            return;
        }
        if (engine == null || engine.isPaused()) {
            return;
        }

        Vector2f anchor = getAnchorLocation();
        if (anchor != null && entity != null) {
            entity.getLocation().set(anchor);
        }

        textureRun -= amount * scrollSpeed;
        if (textureRun <= -1000f) {
            textureRun += 1000f;
        }

        if (intensity < targetIntensity) {
            intensity = Math.min(targetIntensity, intensity + amount * intensityChangeSpeed);
        } else if (intensity > targetIntensity) {
            intensity = Math.max(targetIntensity, intensity - amount * intensityChangeSpeed);
        }
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (layer != this.layer) {
            return;
        }
        if (shouldExpire) {
            cleanupResources();
            return;
        }
        if (engine == null || engine.isPaused() || intensity <= MIN_INTENSITY_TO_RENDER) {
            return;
        }
        if (!ensureBufferReady()) {
            shouldExpire = true;
            cleanupResources();
            return;
        }

        Vector2f anchor = getAnchorLocation();
        if (anchor == null) {
            return;
        }
        float facing = getFirstFirePointFacing();

        composeTrailToBuffer();
        drawBufferToWorld(anchor, facing);
    }

    private boolean shouldAutoExpire() {
        if (shouldExpire) return true;
        if (weapon == null) return true;
        if (weapon.getShip() == null) return true;
        if (!weapon.getShip().isAlive() || weapon.getShip().isHulk()) return true;
        if (engine != null && !engine.isEntityInPlay(weapon.getShip())) return true;
        return false;
    }

    private Vector2f getAnchorLocation() {
        if (weapon == null) return null;
        int index = Math.max(0, firePointIndex);
        return weapon.getFirePoint(index);
    }

    private float getFirstFirePointFacing() {
        Vector2f weaponLoc = weapon.getLocation();
        Vector2f firePoint = weapon.getFirePoint(Math.max(0, firePointIndex));
        if (weaponLoc == null || firePoint == null) {
            return weapon.getCurrAngle();
        }

        float dx = firePoint.x - weaponLoc.x;
        float dy = firePoint.y - weaponLoc.y;
        if (dx * dx + dy * dy < 0.0001f) {
            return weapon.getCurrAngle();
        }

        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    private void composeTrailToBuffer() {
        if (bufferWithData == null) {
            return;
        }
        int width = bufferWithData.getWidth();
        int height = bufferWithData.getHeight();
        int textureBufferId = bufferWithData.getTextureBufferId();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL20.glUseProgram(0);
        bindFramebuffer(textureBufferId);

        GL11.glViewport(0, 0, width, height);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, width, 0, height, -2000, 2000);

        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glColorMask(true, true, true, true);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

        drawTrailPass(texturePrimary, width, height, 0f, 1.0f, primaryStartColor, primaryEndColor, 0.60f);
        drawTrailPass(textureDetail, width, height, 0.37f, 0.85f, detailStartColor, detailEndColor, 0.80f);

        GL11.glDisable(GL11.GL_BLEND);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();

        unbindFramebuffer();
        GL11.glPopAttrib();
    }

    private void drawTrailPass(SpriteAPI texture, int width, int height, float uvOffset, float alphaMult, Color startColor, Color endColor, float alphaFadeStart) {
        if (texture == null) {
            return;
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        texture.bindTexture();

        float centerX = width * 0.5f;
        float centerY = height * 0.5f;
        float segmentLength = trailLength / segment;
        float halfBaseWidth = trailWidth * 0.5f;

        for (int i = 0; i < segment; i++) {
            float t = (float) i / (float) segment;
            float widthMult;
            if (i <= segment * 0.2f) {
                widthMult = widthStart + ((1f - widthStart) * smoothstep(0f, segment * 0.2f, i));
            } else {
                widthMult = 1f - ((1f - widthEnd) * smoothstep(segment * 0.2f, segment, i));
            }

            float x0 = centerX + i * segmentLength;
            float x1 = x0 + segmentLength;
            float halfWidth = halfBaseWidth * widthMult;

            float uvLeft = t + textureRun + uvOffset + uvRandomShift;
            float uvRight = ((float) (i + 1) / (float) segment) + textureRun + uvOffset + uvRandomShift;

            Color color = interpolateColor(startColor, endColor, t);
            float alpha = color.getAlpha()
                    * intensity
                    * alphaMult
                    * (1f - smoothstep(segment * alphaFadeStart, segment, i));

            GL11.glColor4ub(
                    (byte) color.getRed(),
                    (byte) color.getGreen(),
                    (byte) color.getBlue(),
                    (byte) clampToByte(alpha)
            );

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(uvLeft, 0f);
            GL11.glVertex3f(x0, centerY - halfWidth, 0f);

            GL11.glTexCoord2f(uvRight, 0f);
            GL11.glVertex3f(x1, centerY - halfWidth, 0f);

            GL11.glTexCoord2f(uvRight, 1f);
            GL11.glVertex3f(x1, centerY + halfWidth, 0f);

            GL11.glTexCoord2f(uvLeft, 1f);
            GL11.glVertex3f(x0, centerY + halfWidth, 0f);
            GL11.glEnd();
        }

        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    private void drawBufferToWorld(Vector2f location, float facing) {
        int width = bufferWithData.getWidth();
        int height = bufferWithData.getHeight();
        int textureId = bufferWithData.getTextureId();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();

        GL11.glTranslatef(location.x, location.y, 0f);
        GL11.glRotatef(facing, 0f, 0f, 1f);

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glColor4f(1f, 1f, 1f, intensity);

        float halfWidth = width * 0.5f;
        float halfHeight = height * 0.5f;

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 1f);
        GL11.glVertex2f(-halfWidth, halfHeight);
        GL11.glTexCoord2f(1f, 1f);
        GL11.glVertex2f(halfWidth, halfHeight);
        GL11.glTexCoord2f(1f, 0f);
        GL11.glVertex2f(halfWidth, -halfHeight);
        GL11.glTexCoord2f(0f, 0f);
        GL11.glVertex2f(-halfWidth, -halfHeight);
        GL11.glEnd();

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();

        GL11.glPopAttrib();
    }

    private boolean ensureBufferReady() {
        int expectedWidth = Math.max(MIN_BUFFER_SIZE, (int) Math.ceil(trailLength) + BUFFER_PADDING * 2);
        int expectedHeight = Math.max(MIN_BUFFER_SIZE, (int) Math.ceil(trailWidth * 2f) + BUFFER_PADDING * 2);

        if (bufferWithData != null && bufferWithData.getWidth() == expectedWidth && bufferWithData.getHeight() == expectedHeight) {
            return true;
        }

        cleanupResources();
        bufferWithData = BufferWithData.createBuffer(expectedWidth, expectedHeight);
        return bufferWithData != null;
    }

    private void rebuildBuffer() {
        if (bufferWithData != null) {
            cleanupResources();
        }
        if (engine != null) {
            ensureBufferReady();
        }
    }

    private void cleanupResources() {
        if (bufferWithData != null) {
            int textureId = bufferWithData.getTextureId();
            if (textureId != 0) {
                GL11.glDeleteTextures(textureId);
            }

            int textureBufferId = bufferWithData.getTextureBufferId();
            if (textureBufferId != 0) {
                if (ShaderLib.useBufferCore()) {
                    GL30.glDeleteFramebuffers(textureBufferId);
                } else if (ShaderLib.useBufferARB()) {
                    ARBFramebufferObject.glDeleteFramebuffers(textureBufferId);
                } else {
                    EXTFramebufferObject.glDeleteFramebuffersEXT(textureBufferId);
                }
            }
        }
        bufferWithData = null;
    }

    private void bindFramebuffer(int textureBufferId) {
        if (ShaderLib.useBufferCore()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, textureBufferId);
        } else if (ShaderLib.useBufferARB()) {
            ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, textureBufferId);
        } else {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, textureBufferId);
        }
    }

    private void unbindFramebuffer() {
        if (ShaderLib.useBufferCore()) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        } else if (ShaderLib.useBufferARB()) {
            ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, 0);
        } else {
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0);
        }
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        if (edge0 == edge1) return 0f;
        float t = (x - edge0) / (edge1 - edge0);
        t = clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static int clampToByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Color interpolateColor(Color c1, Color c2, float t) {
        t = clamp(t, 0f, 1f);
        int r = (int) (c1.getRed() + (c2.getRed() - c1.getRed()) * t);
        int g = (int) (c1.getGreen() + (c2.getGreen() - c1.getGreen()) * t);
        int b = (int) (c1.getBlue() + (c2.getBlue() - c1.getBlue()) * t);
        int a = (int) (c1.getAlpha() + (c2.getAlpha() - c1.getAlpha()) * t);
        return new Color(r, g, b, a);
    }

    private static class BufferWithData {
        private final int width;
        private final int height;
        private final int textureId;
        private final int textureBufferId;

        private BufferWithData(int width, int height, int textureId, int textureBufferId) {
            this.width = width;
            this.height = height;
            this.textureId = textureId;
            this.textureBufferId = textureBufferId;
        }

        public static BufferWithData createBuffer(int width, int height) {
            int textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA8,
                    width,
                    height,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null
            );

            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);

            int framebufferId;
            if (ShaderLib.useBufferCore()) {
                framebufferId = ShaderLib.makeFramebuffer(GL30.GL_COLOR_ATTACHMENT0, textureId, width, height, 0);
            } else if (ShaderLib.useBufferARB()) {
                framebufferId = ShaderLib.makeFramebuffer(ARBFramebufferObject.GL_COLOR_ATTACHMENT0, textureId, width, height, 0);
            } else {
                framebufferId = ShaderLib.makeFramebuffer(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, textureId, width, height, 0);
            }

            if (framebufferId == 0) {
                if (textureId != 0) {
                    GL11.glDeleteTextures(textureId);
                }
                return null;
            }
            return new BufferWithData(width, height, textureId, framebufferId);
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getTextureId() {
            return textureId;
        }

        public int getTextureBufferId() {
            return textureBufferId;
        }
    }
}
