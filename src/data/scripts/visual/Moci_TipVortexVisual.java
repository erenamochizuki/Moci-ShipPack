package data.scripts.visual;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import data.scripts.util.Moci_BoxBasedUtil;
import org.boxutil.base.BaseShaderData;
import org.boxutil.manager.ShaderCore;
import org.boxutil.util.CommonUtil;
import org.boxutil.util.ShaderUtil;
import org.boxutil.util.TransformUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.EnumSet;

public class Moci_TipVortexVisual extends BaseCombatLayeredRenderingPlugin {

    public static final Vector2f ZERO = new Vector2f();

    private final ShipAPI anchor;
    private final SpriteAPI centerTexture;
    private final SpriteAPI underTexture;
    private final SpriteAPI tipTexture;

    private Color centerColor = new Color(80, 156, 255);
    private Color underColor = new Color(62, 115, 255);
    private Color tipColor = new Color(111, 188, 255);
    private Color topColor = new Color(100, 165, 255, 255);

    private final float basicWidth;
    private final float basicLength;
    private float widthMult = 1f;
    private float lengthMult = 1f;
    private float offset = 0f;

    private boolean valid = true;
    private float aliveLevel = 1f;
    private float effectLevel = 0f;
    private float sizeMult = 1f;

    public Moci_TipVortexVisual(ShipAPI anchor, SpriteAPI centerTexture, SpriteAPI underTexture, SpriteAPI tipTexture) {
        this.anchor = anchor;
        this.centerTexture = centerTexture;
        this.underTexture = underTexture;
        this.tipTexture = tipTexture;
        this.basicWidth = anchor.getCollisionRadius() + 10f;
        this.basicLength = anchor.getCollisionRadius() * 0.5f + 2f;
    }

    public Color getCenterColor() {
        return centerColor;
    }

    public void setCenterColor(Color centerColor) {
        this.centerColor = centerColor;
    }

    public Color getUnderColor() {
        return underColor;
    }

    public void setUnderColor(Color underColor) {
        this.underColor = underColor;
    }

    public Color getTipColor() {
        return tipColor;
    }

    public void setTipColor(Color tipColor) {
        this.tipColor = tipColor;
    }

    public Color getTopColor() {
        return topColor;
    }

    public void setTopColor(Color topColor) {
        this.topColor = topColor;
    }

    public float getWidthMult() {
        return widthMult;
    }

    public void setWidthMult(float widthMult) {
        this.widthMult = widthMult;
    }

    public float getLengthMult() {
        return lengthMult;
    }

    public void setLengthMult(float lengthMult) {
        this.lengthMult = lengthMult;
    }

    public float getOffset() {
        return offset;
    }

    public void setOffset(float offset) {
        this.offset = offset;
    }

    public float getBasicWidth() {
        return basicWidth;
    }

    public float getBasicLength() {
        return basicLength;
    }

    @Override
    public float getRenderRadius() {
        return basicWidth * sizeMult * 5f;
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.BELOW_SHIPS_LAYER, CombatEngineLayers.ABOVE_PARTICLES_LOWER);
    }

    public float getEffectLevel() {
        return effectLevel;
    }

    public void setEffectLevel(float effectLevel) {
        this.effectLevel = effectLevel;
    }

    public float getSizeMult() {
        return sizeMult;
    }

    public void setSizeMult(float sizeMult) {
        this.sizeMult = sizeMult;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public boolean isValid() {
        return valid;
    }

    @Override
    public void advance(float amount) {

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine.isPaused()) return;
        if (!engine.isEntityInPlay(anchor)) {
            valid = false;
            return;
        }

        entity.getLocation().set(anchor.getLocation());

        if (!anchor.isAlive()) {
            aliveLevel -= amount * 3f;
            if (aliveLevel <= 0f) {
                aliveLevel = 0f;
                valid = false;
            }

            return;
        }
    }

    @Override
    public void init(CombatEntityAPI entity) {
        super.init(entity);
        advance(0f);

        // 仅在BoxUtil可用时初始化shader和VAO
        if (!data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
            vaoId = 0;
            vboId = 0;
            program = null;
            return;
        }

        try {
            int programId = ShaderUtil.createShaderVFFormPath(this.getClass().getName(),
                    "data/shaders/Moci_tip_vortex.vert",
                    "data/shaders/Moci_tip_vortex.frag");
            program = new BaseShaderData(programId) {};
            program.location = new int[]{
                    program.getUniformIndex("modelMatrix"),
                    program.getUniformIndex("size"),
                    program.getUniformIndex("time"),
                    program.getUniformIndex("uv"),
                    program.getUniformIndex("tex"),
                    program.getUniformIndex("colorStart"),
                    program.getUniformIndex("colorEnd")
            };
            program.uboLocation = new int[]{
                    program.getUBOIndex("BUtilGlobalData", ShaderCore.getMatrixUBOBinding())
            };

            // 创建VAO
            int[] idV = Moci_BoxBasedUtil.createUniversalRectVAO();
            vaoId = idV[0];
            vboId = idV[1];
        } catch (Exception e) {
            // BoxUtil初始化失败，设置为无效状态
            vaoId = 0;
            vboId = 0;
            program = null;
        }
    }

    @Override
    public void cleanup() {
        if (vaoId > 0) {
            GL30.glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
        if (vboId > 0) {
            GL15.glDeleteBuffers(vaoId);
            vboId = 0;
        }
        if (program != null) {
            program.delete();
            program = null;
        }
    }

    @Override
    public boolean isExpired() {
        return !valid;
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        // 如果BoxUtil不可用，跳过渲染
        if (!data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
            return;
        }

        float alphaMult = viewport.getAlphaMult() * anchor.getAlphaMult() * aliveLevel * effectLevel;
        if (alphaMult <= 0f) return;

        alphaMult *= alphaMult;

        float width = basicWidth * widthMult * sizeMult;
        float length = basicLength * lengthMult * sizeMult;

        CombatEngineAPI engine = Global.getCombatEngine();
        if (layer == CombatEngineLayers.BELOW_SHIPS_LAYER) {
            renderVortex(engine, anchor, underTexture, topColor, underColor, 2f * length, 4f * width, offset + length * 0.8f, alphaMult * 0.75f);
        } else if (layer == CombatEngineLayers.ABOVE_PARTICLES_LOWER) {
            renderVortex(engine, anchor, centerTexture, topColor, centerColor, 3f * length, 2.25f * width, offset + length * 0.25f, alphaMult);
            renderVortex(engine, anchor, tipTexture, topColor, tipColor, 5f * length, 2f * width, offset - length * 0.8f, alphaMult);
        }
    }

    private int vaoId = -99;
    private int vboId = -99;
    private BaseShaderData program = null;
    private Matrix4f matrix = new Matrix4f();

    public void renderVortex(CombatEngineAPI engine, ShipAPI anchor, SpriteAPI texture, Color startColor, Color endColor, float length, float width, float offset, float alphaMult) {

        if (program != null && vaoId > 0 && vboId > 0) {

            float angle = anchor.getFacing();
            Vector2f location = MathUtils.getPoint(anchor.getLocation(), offset, angle);

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

            GL30.glBindVertexArray(vaoId);
            program.active();

            TransformUtil.createModelMatrixVanilla(location, MathUtils.clampAngle(angle), matrix);
            GL20.glUniformMatrix4(program.location[0], false, CommonUtil.createFloatBuffer(matrix));

            GL20.glUniform2f(program.location[1], length, width);

            GL20.glUniform1f(program.location[2], engine.getTotalElapsedTime(false));

            // 注意：shader中的uv uniform实际上没有被使用，这里传递1.0作为占位符
            GL20.glUniform2f(program.location[3], 1.0f, 1.0f);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
            GL20.glUniform1i(program.location[4], 0);

            GL20.glUniform4f(program.location[5], startColor.getRed() / 255f, startColor.getGreen() / 255f, startColor.getBlue() / 255f, alphaMult * startColor.getAlpha() / 255f);
            GL20.glUniform4f(program.location[6], endColor.getRed() / 255f, endColor.getGreen() / 255f, endColor.getBlue() / 255f, alphaMult * endColor.getAlpha() / 255f);

            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

            program.close();
            GL30.glBindVertexArray(0);
        }
    }
}
