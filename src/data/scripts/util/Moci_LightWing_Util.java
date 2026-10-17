package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;
import org.dark.shaders.util.ShaderLib;
import org.lwjgl.opengl.*;

import java.awt.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 光翼拖尾渲染工具。
 * 用分层插件管理拖尾实体，先画到离屏 FBO，再贴回世界；拖尾分下层外轮廓和上层发光核心两部分。
 */
public class Moci_LightWing_Util {
    private static final Logger log = Logger.getLogger(Moci_LightWing_Util.class);

    public static class engineRenderEntity {
        ShipAPI ship;
        WeaponAPI weapon;
        WeaponSlotAPI slot;
        float lenth = 0f;
        float width = 0f;
        float facing = 0f; // 历史遗留字段，暂时保留
        Color startColor = new Color(255, 255, 255);
        Color endColor = new Color(255, 255, 255);
        float ran = (float) Math.random(); // 每条拖尾自己的 UV 偏移
        float texturerun = 0f; // 当前纹理滚动量
    }

    public static class Vbc_engineRender_plugin extends BaseCombatLayeredRenderingPlugin {
        private final ShipAPI ship;
        private final EnumSet<CombatEngineLayers> activeLayers = EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_LAYER);
        private boolean should_exexpired = false;
        public SpriteAPI texture1 = Global.getSettings().getSprite("graphics/fx/Moci_trail_flame.png"); // 上层亮拖尾纹理
        public SpriteAPI texture2 = Global.getSettings().getSprite("graphics/fx/beam_rough2_core.png"); // 下层外轮廓纹理
        private CombatEngineAPI engine = null;
        boolean init = false; // 历史遗留字段，当前未使用
        List<engineRenderEntity> list = new ArrayList<>();
        float widthstart = 0.3f; // 根部宽度倍率
        float widthend = 0.8f; // 末端宽度倍率
        float rootTransitionFraction = 0.18f; // 根部过渡阶段占总长度比例
        float rootStartAlphaMult = 0.14f; // 根部起始透明度倍率
        float rootOverlapAlphaMult = 0.35f; // 根部额外重叠层透明度倍率
        float rootOverlapWidthMult = 1.2f; // 根部额外重叠层宽度倍率
        int baseSegmentCount = 100; // 默认尺寸下的基准段数
        int minSegmentCount = 48;
        int maxSegmentCount = 240;
        float segmentReferenceLength = 240f;
        float segmentReferenceWidth = 58f;
        float textureScrollBaseSpeed = 0.1f;
        float textureScrollEffectLevelBonus = 0.2f;
        float textureScrollSpeedMult = 1f;
        float systemLengthMult = 1f;
        float systemWidthMult = 1f;
        float systemScrollSpeedMult = 1f;
        BufferWithData bufferWithData = null;

        public Vbc_engineRender_plugin(ShipAPI ship) {
            this.ship = ship;
            this.layer = CombatEngineLayers.ABOVE_SHIPS_LAYER;
            engine = Global.getCombatEngine();
        }

        @Override
        public EnumSet<CombatEngineLayers> getActiveLayers() {
            return activeLayers;
        }

        public boolean shouldPauseLogic() {
            if (engine == null) return true;
            if (engine.isPaused()) return true;
            if (!engine.isEntityInPlay(ship)) return true;
            return false;
        }

        public boolean shouldSkipRender() {
            if (engine == null) return true;
            if (ship == null) return true;
            if (!ship.isAlive() || ship.isHulk()) return true;
            if (!engine.isEntityInPlay(ship)) return true;
            return false;
        }

        @Override
        public float getRenderRadius() {
            return Float.MAX_VALUE;
        }

        @Override
        public boolean isExpired() {
            return should_exexpired;
        }

        @Override
        public void advance(float amount) {
            if (ship == null || !ship.isAlive() || ship.isHulk()) {
                expireAndCleanup();
                return;
            }
            if (engine == null) {
                engine = Global.getCombatEngine();
            }
            if (engine != null && !engine.isEntityInPlay(ship)) {
                expireAndCleanup();
                return;
            }
            if (shouldPauseLogic()) return;

            if (!list.isEmpty()) {
                for (engineRenderEntity entity : list) {
                    float effectlevel = getEffectLevel(entity);
                    float currentSystemScrollMult = getInterpolatedSystemMult(effectlevel, systemScrollSpeedMult);
                    entity.texturerun -= amount
                            * (textureScrollBaseSpeed + textureScrollEffectLevelBonus * effectlevel) // 基础速度 + 系统加成
                            * textureScrollSpeedMult
                            * currentSystemScrollMult;
                    if (entity.texturerun <= -1000f) {
                        entity.texturerun += 1000f;
                    }
                }
                List<engineRenderEntity> list2 = new ArrayList<>(list);
                for (engineRenderEntity entity : list2) {
                    if (isInvalidEntity(entity)) {
                        list.remove(entity);
                    }
                }
            }
        }

        public void autoCreateTrailEntity(ShipAPI ship, Color startColor, Color endColor) {
            Global.getLogger(this.getClass()).info("autoCreateTrailEntity by ship");
            if (ship == null) {
                return;
            }
            for (WeaponAPI w : ship.getAllWeapons()) {
                if (w == null || w.getSpec() == null || w.getSpec().getWeaponId().contentEquals("")) continue;
                engineRenderEntity entity = new engineRenderEntity();
                entity.ship = ship;
                entity.weapon = w;
                entity.facing = w.getArcFacing();
                entity.startColor = startColor;
                entity.endColor = endColor;
                entity.lenth = 200f;
                entity.width = 50f;
                list.add(entity);
            }
        }

        public void createTrailEntity(ShipAPI ship, WeaponAPI weapon, Color startColor, Color endColor, float lenth, float width) {
            Global.getLogger(this.getClass()).info("autoCreateTrailEntity by weapon");
            if (ship == null || weapon == null) {
                return;
            }
            engineRenderEntity entity = new engineRenderEntity();
            entity.ship = ship;
            entity.weapon = weapon;
            entity.facing = weapon.getArcFacing();
            entity.startColor = startColor;
            entity.endColor = endColor;
            entity.lenth = lenth;
            entity.width = width;
            list.add(entity);
        }

        public void setTrailGradientColors(Color startColor, Color endColor) {
            if (startColor == null && endColor == null) {
                return;
            }
            for (engineRenderEntity entity : list) {
                if (entity == null) {
                    continue;
                }
                if (startColor != null) {
                    entity.startColor = startColor;
                }
                if (endColor != null) {
                    entity.endColor = endColor;
                }
            }
        }

        public void setTrailDimensions(float lenth, float width) {
            float safeLength = Math.max(0f, lenth);
            float safeWidth = Math.max(0f, width);
            for (engineRenderEntity entity : list) {
                if (entity == null) {
                    continue;
                }
                entity.lenth = safeLength;
                entity.width = safeWidth;
            }
        }

        public void setSegmentReferenceDimensions(float length, float width) {
            segmentReferenceLength = Math.max(1f, length);
            segmentReferenceWidth = Math.max(1f, width);
        }

        public void setBaseSegmentCount(int segmentCount) {
            baseSegmentCount = Math.max(1, segmentCount);
        }

        public void createTrailEntity(ShipAPI ship, WeaponSlotAPI slot, Color startColor, Color endColor, float lenth, float width) {
            Global.getLogger(this.getClass()).info("autoCreateTrailEntity by slot");
            if (ship == null || slot == null) {
                return;
            }
            engineRenderEntity entity = new engineRenderEntity();
            entity.ship = ship;
            entity.slot = slot;
            entity.facing = slot.getAngle();
            entity.startColor = startColor;
            entity.endColor = endColor;
            entity.lenth = lenth;
            entity.width = width;
            list.add(entity);
        }

        /**
         * 配置根部起始阶段：按圆弧外形从窄到宽扩张，并在根部附加一个低 alpha 的重叠过渡层。
         */
        public void setRootTransitionProfile(
                float transitionFraction,
                float rootWidthMult,
                float rootAlphaMult,
                float overlapAlphaMult,
                float overlapWidthMult) {
            rootTransitionFraction = clampUnitFloat(transitionFraction);
            widthstart = clampUnitFloat(rootWidthMult);
            rootStartAlphaMult = clampUnitFloat(rootAlphaMult);
            rootOverlapAlphaMult = clampUnitFloat(overlapAlphaMult);
            this.rootOverlapWidthMult = Math.max(1f, overlapWidthMult);
        }

        public void setTextureScrollSpeedMult(float mult) {
            textureScrollSpeedMult = Math.max(0f, mult);
        }

        public void setSystemLengthMult(float mult) {
            systemLengthMult = Math.max(0f, mult);
        }

        public void setSystemWidthMult(float mult) {
            systemWidthMult = Math.max(0f, mult);
        }

        public void setSystemScrollSpeedMult(float mult) {
            systemScrollSpeedMult = Math.max(0f, mult);
        }

        public void setRenderLayer(CombatEngineLayers renderLayer) {
            if (renderLayer == null) {
                return;
            }
            this.layer = renderLayer;
            activeLayers.clear();
            activeLayers.add(renderLayer);
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (!activeLayers.contains(layer) || should_exexpired || list.isEmpty()) {
                return;
            }
            if (ship == null || !ship.isAlive() || ship.isHulk()) {
                expireAndCleanup();
                return;
            }
            if (shouldSkipRender()) {
                return;
            }
            if (!ensureBufferReady()) {
                expireAndCleanup();
                return;
            }
            renderUnderTrail();
            renderUpTrail();
        }

        private void renderUpTrail() {
            for (engineRenderEntity entity : list) {
                if (isInvalidEntity(entity)) {
                    continue;
                }
                int width = bufferWithData.getWidth();
                int height = bufferWithData.getHeight();
                int textureId = bufferWithData.getTextureId();
                int textureBufferId = bufferWithData.getTextureBufferId();

                // 先画到 FBO，再贴回世界坐标。
                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL20.glUseProgram(0);

                if (ShaderLib.useBufferCore()) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, textureBufferId);
                } else if (ShaderLib.useBufferARB()) {
                    ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, textureBufferId);
                } else {
                    EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, textureBufferId);
                }

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

                renderSingleTrail(entity, width, height);

                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_TEXTURE_2D);

                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();

                if (ShaderLib.useBufferCore()) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                } else if (ShaderLib.useBufferARB()) {
                    ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, 0);
                } else {
                    EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0);
                }
                GL11.glPopAttrib();

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPushMatrix();
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glPushMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();

                GL11.glTranslatef(getRenderX(entity), getRenderY(entity), 0f);
                GL11.glRotatef(getRenderFacing(entity), 0f, 0f, 1f);

                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glColor4f(1f, 1f, 1f, 1f);

                float halfHeight = height * 0.5f;
                float halfWidth = width * 0.5f;

                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2d(0, 1);
                GL11.glVertex2d(-halfWidth, halfHeight);
                GL11.glTexCoord2d(1, 1);
                GL11.glVertex2d(halfWidth, halfHeight);
                GL11.glTexCoord2d(1, 0);
                GL11.glVertex2d(halfWidth, -halfHeight);
                GL11.glTexCoord2d(0, 0);
                GL11.glVertex2d(-halfWidth, -halfHeight);
                GL11.glEnd();

                // 再画一遍同层，保留原始实现的额外增亮效果。
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2d(0, 1);
                GL11.glVertex2d(-halfWidth, halfHeight);
                GL11.glTexCoord2d(1, 1);
                GL11.glVertex2d(halfWidth, halfHeight);
                GL11.glTexCoord2d(1, 0);
                GL11.glVertex2d(halfWidth, -halfHeight);
                GL11.glTexCoord2d(0, 0);
                GL11.glVertex2d(-halfWidth, -halfHeight);
                GL11.glEnd();

                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();

                GL11.glPopAttrib();
            }
        }

        private void renderUnderTrail() {
            for (engineRenderEntity entity : list) {
                if (isInvalidEntity(entity)) {
                    continue;
                }
                int width = bufferWithData.getWidth();
                int height = bufferWithData.getHeight();
                int textureId = bufferWithData.getTextureId();
                int textureBufferId = bufferWithData.getTextureBufferId();

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
                GL20.glUseProgram(0);

                if (ShaderLib.useBufferCore()) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, textureBufferId);
                } else if (ShaderLib.useBufferARB()) {
                    ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, textureBufferId);
                } else {
                    EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, textureBufferId);
                }

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

                renderSingleUnderTrail(entity, width, height);

                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_TEXTURE_2D);

                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();

                if (ShaderLib.useBufferCore()) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                } else if (ShaderLib.useBufferARB()) {
                    ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, 0);
                } else {
                    EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0);
                }
                GL11.glPopAttrib();

                GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPushMatrix();
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glPushMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();

                GL11.glTranslatef(getRenderX(entity), getRenderY(entity), 0f);
                GL11.glRotatef(getRenderFacing(entity), 0f, 0f, 1f);

                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glColor4f(1f, 1f, 1f, 1f);

                float halfHeight = height * 0.5f;
                float halfWidth = width * 0.5f;

                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2d(0, 1);
                GL11.glVertex2d(-halfWidth, halfHeight);
                GL11.glTexCoord2d(1, 1);
                GL11.glVertex2d(halfWidth, halfHeight);
                GL11.glTexCoord2d(1, 0);
                GL11.glVertex2d(halfWidth, -halfHeight);
                GL11.glTexCoord2d(0, 0);
                GL11.glVertex2d(-halfWidth, -halfHeight);
                GL11.glEnd();

                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();

                GL11.glPopAttrib();
            }
        }

        private boolean ensureBufferReady() {
            if (bufferWithData != null) {
                return true;
            }
            bufferWithData = BufferWithData.createBuffer(texture1, texture2);
            if (bufferWithData == null) {
                log.info("createBuffer error");
                return false;
            }
            return true;
        }

        private void expireAndCleanup() {
            should_exexpired = true;
            clearBufferWithData(bufferWithData);
            bufferWithData = null;
            list.clear();
        }

        private boolean isInvalidEntity(engineRenderEntity entity) {
            return entity == null
                    || entity.ship == null
                    || (entity.weapon == null && entity.slot == null)
                    || !entity.ship.isAlive()
                    || entity.ship.isHulk();
        }

        private float getEffectLevel(engineRenderEntity entity) {
            if (entity == null || entity.ship == null || entity.ship.getSystem() == null) {
                return 0f;
            }
            return Math.max(0f, Math.min(1f, entity.ship.getSystem().getEffectLevel()));
        }

        private float getInterpolatedSystemMult(float effectLevel, float targetMult) {
            float clampedEffectLevel = Math.max(0f, Math.min(1f, effectLevel));
            return 1f + (targetMult - 1f) * clampedEffectLevel;
        }

        private int getDynamicSegmentCount(float currentLength, float currentWidth) {
            float safeReferenceLength = Math.max(1f, segmentReferenceLength);
            float safeReferenceWidth = Math.max(1f, segmentReferenceWidth);

            float lengthRatio = Math.max(0.1f, currentLength / safeReferenceLength);
            float widthRatio = Math.max(0.1f, currentWidth / safeReferenceWidth);

            float weightedRatio = lengthRatio * 0.45f + widthRatio * 0.85f; // 宽度权重更高，避免宽拖尾折线感
            float dominantRatio = Math.max(lengthRatio, widthRatio);
            float finalRatio = Math.max(0.5f, Math.max(weightedRatio, dominantRatio));

            int dynamicSegmentCount = Math.round(baseSegmentCount * finalRatio);
            return Math.max(minSegmentCount, Math.min(maxSegmentCount, dynamicSegmentCount));
        }

        private float getRenderX(engineRenderEntity entity) {
            if (entity != null && entity.weapon != null && entity.weapon.getLocation() != null) {
                return entity.weapon.getLocation().x;
            }
            if (entity != null && entity.slot != null && entity.ship != null) {
                return entity.slot.computePosition(entity.ship).x;
            }
            if (entity != null && entity.ship != null && entity.ship.getLocation() != null) {
                return entity.ship.getLocation().x;
            }
            return 0f;
        }

        private float getRenderY(engineRenderEntity entity) {
            if (entity != null && entity.weapon != null && entity.weapon.getLocation() != null) {
                return entity.weapon.getLocation().y;
            }
            if (entity != null && entity.slot != null && entity.ship != null) {
                return entity.slot.computePosition(entity.ship).y;
            }
            if (entity != null && entity.ship != null && entity.ship.getLocation() != null) {
                return entity.ship.getLocation().y;
            }
            return 0f;
        }

        private float getRenderFacing(engineRenderEntity entity) {
            if (entity != null && entity.weapon != null) {
                return entity.weapon.getCurrAngle();
            }
            if (entity != null && entity.slot != null && entity.ship != null) {
                return entity.ship.getFacing() + entity.slot.getAngle();
            }
            if (entity != null && entity.ship != null) {
                return entity.ship.getFacing();
            }
            return 0f;
        }

        private float getTrailProgress(int segmentIndex, int segmentCount) {
            if (segmentCount <= 1) {
                return 1f;
            }
            return clampUnitFloat((float) segmentIndex / (segmentCount - 1f));
        }

        private float getRootTransitionBoundary(int segmentCount) {
            float clampedFraction = Math.max(0.01f, rootTransitionFraction);
            return Math.max(1f, segmentCount * clampedFraction);
        }

        private float getArcBlend(float progress) {
            float clampedProgress = clampUnitFloat(progress);
            float inverse = 1f - clampedProgress;
            return (float) Math.sqrt(Math.max(0f, 1f - inverse * inverse));
        }

        private float getSegmentWidthMult(int segmentIndex, int segmentCount) {
            float rootBoundary = getRootTransitionBoundary(segmentCount);
            if (segmentIndex <= rootBoundary) {
                float rootProgress = smoothstep(0f, rootBoundary, segmentIndex);
                float arcBlend = getArcBlend(rootProgress);
                return widthstart + ((1f - widthstart) * arcBlend);
            }
            return 1f - ((1f - widthend) * smoothstep(rootBoundary, segmentCount, segmentIndex));
        }

        private float getRootAlphaMultiplier(int segmentIndex, int segmentCount) {
            float rootBoundary = getRootTransitionBoundary(segmentCount);
            if (segmentIndex >= rootBoundary) {
                return 1f;
            }
            float rootProgress = smoothstep(0f, rootBoundary, segmentIndex);
            return rootStartAlphaMult + ((1f - rootStartAlphaMult) * rootProgress);
        }

        private float getRootOverlapMultiplier(int segmentIndex, int segmentCount) {
            float rootBoundary = getRootTransitionBoundary(segmentCount);
            if (segmentIndex > rootBoundary) {
                return 0f;
            }
            return rootOverlapAlphaMult * (1f - smoothstep(0f, rootBoundary, segmentIndex));
        }

        private Color applyAlphaMultiplier(Color color, float alphaMult) {
            if (color == null) {
                return null;
            }
            return new Color(
                    color.getRed(),
                    color.getGreen(),
                    color.getBlue(),
                    clampToByte(color.getAlpha() * Math.max(0f, alphaMult)));
        }

        private void renderSingleTrail(engineRenderEntity entity, float obfcenterx, float obfcentery) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            GL11.glColorMask(true, true, true, true);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glColor4f(1f, 1f, 1f, 1f);

            GL11.glPushMatrix();

            float effectlevel = getEffectLevel(entity);
            float currentLength = entity.lenth * getInterpolatedSystemMult(effectlevel, systemLengthMult);
            float currentWidth = entity.width * getInterpolatedSystemMult(effectlevel, systemWidthMult);
            int segmentCount = getDynamicSegmentCount(currentLength, currentWidth);
            float segmentLength = currentLength / segmentCount;
            GL11.glTranslatef(obfcenterx / 2f, obfcentery / 2f, 0f);

            for (int i = 0; i < segmentCount; i++) {
                float segmentStart = i * segmentLength;
                float segmentEnd = segmentStart + segmentLength;
                float segmentProgress = getTrailProgress(i, segmentCount);
                float widthMult = getSegmentWidthMult(i, segmentCount);
                float rootAlphaMult = getRootAlphaMultiplier(i, segmentCount);
                float rootOverlapMult = getRootOverlapMultiplier(i, segmentCount);

                float uvLeft = (float) i / segmentCount + entity.texturerun + entity.ran;
                float uvRight = (float) (i + 1) / segmentCount + entity.texturerun + entity.ran;

                texture1.bindTexture();
                texture1.setNormalBlend();

                float halfWidth = currentWidth * 0.5f * widthMult;
                float alphaMult;
                Color nc;

                float textureoverride = 0.2f;
                uvLeft += textureoverride;
                uvRight += textureoverride;
                nc = Misc.interpolateColor(entity.startColor.brighter(), entity.endColor, segmentProgress);
                alphaMult = nc.getAlpha()
                        * (1f - smoothstep(-1, segmentCount * (0.5f + 0.5f * effectlevel), i))
                        * rootAlphaMult;
                if (rootOverlapMult > 0f) {
                    Color overlapColor = applyAlphaMultiplier(nc, rootOverlapMult);
                    GL11.glColor4ub(
                            (byte) overlapColor.getRed(),
                            (byte) overlapColor.getGreen(),
                            (byte) overlapColor.getBlue(),
                            (byte) overlapColor.getAlpha());
                    drawOverlapQuad(segmentStart, segmentEnd, halfWidth, uvLeft, uvRight, rootOverlapMult);
                }
                GL11.glColor4ub((byte) nc.getRed(), (byte) nc.getGreen(), (byte) nc.getBlue(), (byte) clampToByte(alphaMult));
                halfWidth *= 0.5f;
                drawSegmentQuad(segmentStart, segmentEnd, halfWidth, uvLeft, uvRight);

                halfWidth *= 4f;
                textureoverride = 0.8f;
                uvLeft -= textureoverride;
                uvRight -= textureoverride;
                nc = Misc.interpolateColor(entity.startColor.darker(), Color.BLACK, 0.5f + segmentProgress * 0.5f);
                GL11.glColor4ub((byte) nc.getRed(), (byte) nc.getGreen(), (byte) nc.getBlue(), (byte) clampToByte(alphaMult));
                drawSegmentQuad(segmentStart, segmentEnd, halfWidth, uvLeft, uvRight);

                textureoverride = 0.5f;
                uvLeft -= textureoverride;
                uvRight -= textureoverride;
                drawSegmentQuad(segmentStart, segmentEnd, halfWidth, uvLeft, uvRight);

                halfWidth /= 2f;
                textureoverride = 0.6f;
                uvLeft -= textureoverride;
                uvRight -= textureoverride;
                nc = Misc.interpolateColor(entity.startColor, Color.WHITE, segmentProgress * 0.5f);
                GL11.glColor4ub((byte) nc.getRed(), (byte) nc.getGreen(), (byte) nc.getBlue(), (byte) clampToByte(alphaMult));
                drawSegmentQuad(segmentStart, segmentEnd, halfWidth, uvLeft, uvRight);

                halfWidth /= 2f;
                nc = Misc.interpolateColor(entity.endColor, Color.WHITE, 0.7f - segmentProgress * 0.7f);
                GL11.glColor4ub((byte) nc.getRed(), (byte) nc.getGreen(), (byte) nc.getBlue(), (byte) clampToByte(alphaMult));
                drawSegmentQuad(segmentStart, segmentEnd, halfWidth, uvLeft, uvRight);

                textureoverride = 0.4f;
                uvLeft -= textureoverride;
                uvRight -= textureoverride;
                drawSegmentQuad(segmentStart, segmentEnd, halfWidth, uvLeft, uvRight);
            }

            GL11.glPopMatrix();
        }

        private void renderSingleUnderTrail(engineRenderEntity entity, float obfcenterx, float obfcentery) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            GL11.glColorMask(true, true, true, true);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glColor4f(1f, 1f, 1f, 1f);

            GL11.glPushMatrix();

            float effectlevel = getEffectLevel(entity);
            float currentLength = entity.lenth * getInterpolatedSystemMult(effectlevel, systemLengthMult);
            float currentWidth = entity.width * getInterpolatedSystemMult(effectlevel, systemWidthMult);
            int segmentCount = getDynamicSegmentCount(currentLength, currentWidth);
            float segmentLength = currentLength / segmentCount;
            GL11.glTranslatef(obfcenterx / 2f, obfcentery / 2f, 0f);

            for (int i = 0; i < segmentCount; i++) {
                float segmentStart = i * segmentLength;
                float segmentEnd = segmentStart + segmentLength;
                float segmentProgress = getTrailProgress(i, segmentCount);
                float widthMult = getSegmentWidthMult(i, segmentCount);
                float rootAlphaMult = getRootAlphaMultiplier(i, segmentCount);
                float rootOverlapMult = getRootOverlapMultiplier(i, segmentCount);

                float uvLeft = (float) i / segmentCount + entity.texturerun + entity.ran;
                float uvRight = (float) (i + 1) / segmentCount + entity.texturerun + entity.ran;
                float halfWidth = currentWidth * 0.5f * widthMult;

                Color nc = Misc.interpolateColor(entity.startColor, Color.BLACK, 0.8f + 0.1f * segmentProgress);
                float alphaMult = nc.getAlpha()
                        * (1f - smoothstep(0, segmentCount * (0.5f + 0.5f * effectlevel), i))
                        * rootAlphaMult;

                texture2.bindTexture();
                texture2.setNormalBlend();
                halfWidth *= 5f;

                if (rootOverlapMult > 0f) {
                    Color overlapColor = applyAlphaMultiplier(nc, rootOverlapMult);
                    GL11.glColor4ub(
                            (byte) overlapColor.getRed(),
                            (byte) overlapColor.getGreen(),
                            (byte) overlapColor.getBlue(),
                            (byte) overlapColor.getAlpha());
                    drawOverlapQuad(segmentStart, segmentEnd, halfWidth, uvLeft, uvRight, rootOverlapMult);
                }
                GL11.glColor4ub((byte) nc.getRed(), (byte) nc.getGreen(), (byte) nc.getBlue(), (byte) clampToByte(alphaMult));
                drawSegmentQuad(segmentStart, segmentEnd, halfWidth, uvLeft, uvRight);
            }

            GL11.glPopMatrix();
        }

        private void drawOverlapQuad(float startX, float endX, float halfWidth, float uvLeft, float uvRight, float overlapMult) {
            float overlapStrength = rootOverlapAlphaMult <= 0f
                    ? 0f
                    : clampUnitFloat(overlapMult / rootOverlapAlphaMult);
            float overlapBacktrack = (endX - startX) * (0.45f + 0.35f * overlapStrength);
            float overlapStart = startX - overlapBacktrack;
            float overlapHalfWidth = halfWidth * (1f + ((rootOverlapWidthMult - 1f) * overlapStrength));
            float uvScale = (uvRight - uvLeft) / Math.max(0.0001f, endX - startX);
            float overlapUvLeft = uvLeft - overlapBacktrack * uvScale;
            drawSegmentQuad(overlapStart, endX, overlapHalfWidth, overlapUvLeft, uvRight);
        }

        private void drawSegmentQuad(float startX, float endX, float halfWidth, float uvLeft, float uvRight) {
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(uvLeft, 0f);
            GL11.glVertex3f(startX, -halfWidth, 0f);

            GL11.glTexCoord2f(uvRight, 0f);
            GL11.glVertex3f(endX, -halfWidth, 0f);

            GL11.glTexCoord2f(uvRight, 1f);
            GL11.glVertex3f(endX, halfWidth, 0f);

            GL11.glTexCoord2f(uvLeft, 1f);
            GL11.glVertex3f(startX, halfWidth, 0f);
            GL11.glEnd();
        }

        public static class BufferWithData {
            public static final int STATIC_WIDTH_EDGE = 2000; // 横向安全边距
            public static final int STATIC_HEIGHT_EDGE = 2000; // 纵向安全边距

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

            public static BufferWithData createBuffer(SpriteAPI sprite1, SpriteAPI sprite2) {
                int spriteWidth = 1;
                int spriteHeight = 1;
                if (sprite1 != null) {
                    spriteWidth = Math.max(spriteWidth, (int) sprite1.getWidth());
                    spriteHeight = Math.max(spriteHeight, (int) sprite1.getHeight());
                }
                if (sprite2 != null) {
                    spriteWidth = Math.max(spriteWidth, (int) sprite2.getWidth());
                    spriteHeight = Math.max(spriteHeight, (int) sprite2.getHeight());
                }

                int width = spriteWidth + STATIC_WIDTH_EDGE;
                int height = spriteHeight + STATIC_HEIGHT_EDGE;

                int textureId = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

                if (ShaderLib.useBufferCore()) {
                    GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                } else if (ShaderLib.useBufferARB()) {
                    ARBFramebufferObject.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                } else {
                    EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D);
                }

                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);

                int textureBufferId;
                if (ShaderLib.useBufferCore()) {
                    textureBufferId = ShaderLib.makeFramebuffer(GL30.GL_COLOR_ATTACHMENT0, textureId, width, height, 0);
                } else if (ShaderLib.useBufferARB()) {
                    textureBufferId = ShaderLib.makeFramebuffer(ARBFramebufferObject.GL_COLOR_ATTACHMENT0, textureId, width, height, 0);
                } else {
                    textureBufferId = ShaderLib.makeFramebuffer(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, textureId, width, height, 0);
                }

                if (textureBufferId == 0) {
                    if (textureId != 0) GL11.glDeleteTextures(textureId);
                    return null;
                }
                return new BufferWithData(width, height, textureId, textureBufferId);
            }

            public int getWidth() { return width; }
            public int getHeight() { return height; }
            public int getTextureId() { return textureId; }
            public int getTextureBufferId() { return textureBufferId; }
        }

        private void clearTexture(BufferWithData textureSet) {
            if (textureSet == null) return;
            int textureId = textureSet.getTextureId();
            if (textureId != 0) GL11.glDeleteTextures(textureId);
        }

        private void clearBuffer(BufferWithData textureSet) {
            if (textureSet == null) return;
            int textureBufferId = textureSet.getTextureBufferId();
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

        private void clearBufferWithData(BufferWithData textureSet) {
            if (textureSet == null) {
                return;
            }
            int textureId = textureSet.getTextureId();
            if (textureId != 0) GL11.glDeleteTextures(textureId);
            int textureBufferId = textureSet.getTextureBufferId();
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

        private static float smoothstep(float edge0, float edge1, float x) {
            if (edge0 == edge1) {
                return 0f;
            }
            float t = (x - edge0) / (edge1 - edge0);
            t = Math.max(0f, Math.min(1f, t));
            return t * t * (3f - 2f * t);
        }

        private static float clampUnitFloat(float value) {
            if (Float.isNaN(value) || value <= 0f) {
                return 0f;
            }
            if (value >= 1f) {
                return 1f;
            }
            return value;
        }

        private static int clampToByte(float value) {
            return Math.max(0, Math.min(255, Math.round(value)));
        }
    }
}
