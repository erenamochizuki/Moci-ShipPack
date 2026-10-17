package data.scripts.visual;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import data.scripts.util.Moci_BoxBasedUtil;
import org.boxutil.base.BaseShaderData;
import org.boxutil.util.CommonUtil;
import org.boxutil.util.ShaderUtil;
import org.boxutil.util.TransformUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.EnumSet;

/**
 * 披风视觉效果渲染器
 * 
 * 功能特性：
 * - 可调节的前端和末端宽度
 * - 可配置的褶皱数量
 * - 基于惯性的物理模拟
 * - 平滑的卷动动画
 */
public class Moci_CapeVisual extends BaseCombatLayeredRenderingPlugin {

    // 锚点实体（披风附着的对象）
    private final CombatEntityAPI anchor;
    
    // 披风纹理
    private final SpriteAPI texture;
    
    // 披风颜色
    private Color tintColor = new Color(255, 255, 255, 255);
    
    // === 尺寸参数 ===
    private float length = 200f;           // 披风长度
    private float frontWidth = 100f;       // 前端宽度
    private float endWidth = 150f;         // 末端宽度
    
    // === 物理参数 ===
    private float foldCount = 5f;          // 褶皱数量
    private float foldSpeed = 2f;          // 卷动速度
    private float inertia = 0.5f;          // 惯性（0-1，越大延迟越明显）
    
    // === 位置参数 ===
    private Vector2f offset = new Vector2f(0f, 0f);  // 相对锚点的偏移
    private float angleOffset = 180f;      // 角度偏移（默认向后）
    
    // === 状态参数 ===
    private boolean valid = true;
    private float alphaMult = 1f;
    
    // === 渲染资源 ===
    private int vaoId = 0;
    private int vboId = 0;
    private BaseShaderData program = null;
    private Matrix4f matrix = new Matrix4f();

    /**
     * 构造函数
     * 
     * @param anchor 锚点实体
     * @param texture 披风纹理
     */
    public Moci_CapeVisual(CombatEntityAPI anchor, SpriteAPI texture) {
        this.anchor = anchor;
        this.texture = texture;
    }

    // === Getter和Setter方法 ===
    
    public Color getTintColor() {
        return tintColor;
    }

    public void setTintColor(Color tintColor) {
        this.tintColor = tintColor;
    }

    public float getLength() {
        return length;
    }

    public void setLength(float length) {
        this.length = length;
    }

    public float getFrontWidth() {
        return frontWidth;
    }

    public void setFrontWidth(float frontWidth) {
        this.frontWidth = frontWidth;
    }

    public float getEndWidth() {
        return endWidth;
    }

    public void setEndWidth(float endWidth) {
        this.endWidth = endWidth;
    }

    public float getFoldCount() {
        return foldCount;
    }

    public void setFoldCount(float foldCount) {
        this.foldCount = foldCount;
    }

    public float getFoldSpeed() {
        return foldSpeed;
    }

    public void setFoldSpeed(float foldSpeed) {
        this.foldSpeed = foldSpeed;
    }

    public float getInertia() {
        return inertia;
    }

    /**
     * 设置惯性值
     * @param inertia 惯性（0-1，越大延迟越明显，模拟更重的布料）
     */
    public void setInertia(float inertia) {
        this.inertia = Math.max(0f, Math.min(1f, inertia));
    }

    public Vector2f getOffset() {
        return offset;
    }

    public void setOffset(Vector2f offset) {
        this.offset = offset;
    }

    public void setOffset(float x, float y) {
        this.offset = new Vector2f(x, y);
    }

    public float getAngleOffset() {
        return angleOffset;
    }

    public void setAngleOffset(float angleOffset) {
        this.angleOffset = angleOffset;
    }

    public float getAlphaMult() {
        return alphaMult;
    }

    public void setAlphaMult(float alphaMult) {
        this.alphaMult = alphaMult;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public boolean isValid() {
        return valid;
    }

    @Override
    public float getRenderRadius() {
        return Math.max(length, Math.max(frontWidth, endWidth)) * 2f;
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.BELOW_SHIPS_LAYER);
    }

    @Override
    public void advance(float amount) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine.isPaused()) return;
        
        if (!engine.isEntityInPlay(anchor)) {
            valid = false;
            return;
        }

        // 更新实体位置
        entity.getLocation().set(anchor.getLocation());
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
            int programId = ShaderUtil.createShaderVFFormPath(
                this.getClass().getName(),
                "data/shaders/Moci_cape.vert",
                "data/shaders/Moci_cape.frag"
            );
            
            program = new BaseShaderData(programId) {};
            program.location = new int[]{
                program.getUniformIndex("modelMatrix"),
                program.getUniformIndex("size"),
                program.getUniformIndex("time"),
                program.getUniformIndex("foldCount"),
                program.getUniformIndex("foldSpeed"),
                program.getUniformIndex("inertia"),
                program.getUniformIndex("tex"),
                program.getUniformIndex("colorTint"),
                program.getUniformIndex("widthRange")
            };
            program.uboLocation = new int[]{
                program.getUBOIndex("BUtilGlobalData", org.boxutil.manager.ShaderCore.getMatrixUBOBinding())
            };

            // 创建VAO
            int[] idV = Moci_BoxBasedUtil.createUniversalRectVAO();
            vaoId = idV[0];
            vboId = idV[1];
            
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error("Failed to initialize cape shader", e);
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
            GL15.glDeleteBuffers(vboId);
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

        if (program == null || vaoId <= 0 || vboId <= 0) {
            return;
        }

        float finalAlpha = viewport.getAlphaMult() * alphaMult;
        if (anchor instanceof ShipAPI) {
            finalAlpha *= ((ShipAPI) anchor).getAlphaMult();
        }
        
        if (finalAlpha <= 0f) return;

        CombatEngineAPI engine = Global.getCombatEngine();
        
        // 计算披风位置和角度
        float facing = anchor.getFacing() + angleOffset;
        Vector2f anchorLoc = anchor.getLocation();
        Vector2f capeLoc = MathUtils.getPointOnCircumference(anchorLoc, offset.length(), 
            anchor.getFacing() + (float) Math.toDegrees(Math.atan2(offset.y, offset.x)));

        // 启用混合
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // 绑定VAO和激活shader
        GL30.glBindVertexArray(vaoId);
        program.active();

        // 设置模型矩阵
        TransformUtil.createModelMatrixVanilla(capeLoc, MathUtils.clampAngle(facing), matrix);
        GL20.glUniformMatrix4(program.location[0], false, CommonUtil.createFloatBuffer(matrix));

        // 设置尺寸
        GL20.glUniform2f(program.location[1], length, Math.max(frontWidth, endWidth));

        // 设置时间
        GL20.glUniform1f(program.location[2], engine.getTotalElapsedTime(false));

        // 设置褶皱参数
        GL20.glUniform1f(program.location[3], foldCount);
        GL20.glUniform1f(program.location[4], foldSpeed);
        GL20.glUniform1f(program.location[5], inertia);

        // 绑定纹理
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
        GL20.glUniform1i(program.location[6], 0);

        // 设置颜色
        GL20.glUniform4f(program.location[7], 
            tintColor.getRed() / 255f, 
            tintColor.getGreen() / 255f, 
            tintColor.getBlue() / 255f, 
            tintColor.getAlpha() / 255f * finalAlpha);

        // 设置宽度范围（归一化）
        float maxWidth = Math.max(frontWidth, endWidth);
        GL20.glUniform2f(program.location[8], frontWidth / maxWidth, endWidth / maxWidth);

        // 绘制
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // 清理
        program.close();
        GL30.glBindVertexArray(0);
    }
}
