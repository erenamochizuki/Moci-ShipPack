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

/**
 * 发光扫描视觉效果
 * 
 * 功能说明：
 * 这个类创建了一个扫描光效果，类似于科幻电影中的扫描线从舰船上扫过。
 * 
 * 技术实现：
 * 1. 从舰船精灵生成SDF（Signed Distance Field）纹理
 * 2. 使用自定义着色器渲染扫描光效果
 * 3. 扫描线可以从0到1移动，创建扫描动画
 * 
 * 使用方法：
 * 1. 创建实例：new Moci_GlowScanVisual(ship, scanColor)
 * 2. 添加到引擎：engine.addLayeredRenderingPlugin(visual)
 * 3. 更新扫描进度：visual.setScanLevel(0.0f ~ 1.0f)
 * 4. 结束时：visual.setValid(false)
 * 
 * @author Polaris Prime (原作者)
 * @author Moci (移植并添加中文注释)
 */
public class Moci_GlowScanVisual extends BaseCombatLayeredRenderingPlugin {

	/** 数据键 */
	public static final String DATA_KEY = "Moci_GlowScanVisual";
	
	/** 零向量 */
	public static final Vector2f ZERO = new Vector2f();

	/** SDF描边宽度 */
	public static final byte SDF_STROKE = 96;

	// ==================== 实例变量 ====================
	/** 目标舰船 */
	private final ShipAPI ship;
	
	/** SDF纹理ID */
	private final int sdf;
	
	/** SDF纹理宽度 */
	private final float sdfWidth;
	
	/** SDF纹理高度 */
	private final float sdfHeight;
	
	/** 扫描光颜色 */
	private final Color scanColor;

	/** 是否有效 */
	private boolean valid = true;
	
	/** 存活等级（用于淡出） */
	private float aliveLevel = 1f;
	
	/** 扫描进度（0-1） */
	private float scanLevel = 0f;
	
	/** 扫描线宽度 */
	private float scanWidth = 0.15f;

	// ==================== OpenGL资源 ====================
	/** VAO ID */
	private int vaoId = -99;
	
	/** VBO ID */
	private int vboId = -99;
	
	/** 着色器程序 */
	private BaseShaderData program = null;
	
	/** 变换矩阵 */
	private Matrix4f matrix = new Matrix4f();

	/**
	 * 构造函数
	 * 
	 * @param ship 目标舰船
	 * @param scanColor 扫描光颜色
	 */
	public Moci_GlowScanVisual(ShipAPI ship, Color scanColor) {
		this.ship = ship;
		this.scanColor = scanColor;

		// 从舰船精灵生成SDF纹理
		SpriteAPI sprite = ship.getSpriteAPI();
		int[] sdfs = Moci_BoxBasedUtil.genSDF(sprite, SDF_STROKE);
		this.sdf = sdfs[0];
		this.sdfWidth = sdfs[1];
		this.sdfHeight = sdfs[2];
	}

	/**
	 * 设置是否有效
	 * 设置为false后，效果会淡出并被移除
	 */
	public void setValid(boolean valid) {
		this.valid = valid;
	}

	/**
	 * 获取是否有效
	 */
	public boolean isValid() {
		return valid;
	}

	/**
	 * 设置扫描进度
	 * 
	 * @param scanLevel 扫描进度（0-1）
	 */
	public void setScanLevel(float scanLevel) {
		this.scanLevel = scanLevel;
	}

	/**
	 * 获取扫描进度
	 */
	public float getScanLevel() {
		return scanLevel;
	}

	/**
	 * 设置扫描线宽度
	 * 
	 * @param scanWidth 扫描线宽度（0-1）
	 */
	public void setScanWidth(float scanWidth) {
		this.scanWidth = scanWidth;
	}

	/**
	 * 获取扫描线宽度
	 */
	public float getScanWidth() {
		return scanWidth;
	}

	/**
	 * 获取渲染半径
	 */
	@Override
	public float getRenderRadius() {
		return ship.getCollisionRadius() + SDF_STROKE;
	}

	/**
	 * 获取激活的渲染层
	 */
	@Override
	public EnumSet<CombatEngineLayers> getActiveLayers() {
		return EnumSet.of(CombatEngineLayers.ABOVE_PARTICLES);
	}

	/**
	 * 每帧更新
	 */
	@Override
	public void advance(float amount) {

		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine.isPaused()) return;

		// 更新位置
		entity.getLocation().set(ship.getLocation());

		// 如果舰船死亡，逐渐淡出
		if (!ship.isAlive()) {
			aliveLevel -= amount * 3f;
			if (aliveLevel <= 0f) {
				aliveLevel = 0f;
				valid = false;
			}

			return;
		}
	}

	/**
	 * 初始化
	 */
	@Override
	public void init(CombatEntityAPI entity) {
		super.init(entity);

		// 创建着色器程序
		int programId = ShaderUtil.createShaderVFFormPath(this.getClass().getName(),
			"data/shaders/Moci_glow_scan.vert",
			"data/shaders/Moci_glow_scan.frag");
		program = new BaseShaderData(programId) {};
		program.location = new int[] {
			program.getUniformIndex("modelMatrix"),
			program.getUniformIndex("size"),
			program.getUniformIndex("offset"),
			program.getUniformIndex("uv"),
			program.getUniformIndex("tex"),
			program.getUniformIndex("scanColor"),
			program.getUniformIndex("scanLine"),
			program.getUniformIndex("scanWidth")
		};
		program.uboLocation = new int[] {
			program.getUBOIndex("BUtilGlobalData", ShaderCore.getMatrixUBOBinding())
		};

		// 创建VAO和VBO
		int[] idV = Moci_BoxBasedUtil.createUniversalRectVAO();
		vaoId = idV[0];
		vboId = idV[1];
	}

	/**
	 * 清理资源
	 */
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

	/**
	 * 是否过期
	 */
	@Override
	public boolean isExpired() {
		return !valid;
	}

	/**
	 * 渲染
	 */
	@Override
	public void render(CombatEngineLayers layer, ViewportAPI viewport) {

		float alphaMult = viewport.getAlphaMult() * ship.getAlphaMult();
		if (alphaMult <= 0f) return;

		if (layer == CombatEngineLayers.ABOVE_PARTICLES) {
			renderGlow(ship, sdf, sdfWidth, sdfHeight, scanLevel, scanWidth, 0, 0, scanColor, alphaMult);
		}
	}

	/**
	 * 渲染发光效果
	 * 
	 * @param anchor 锚点舰船
	 * @param textureId SDF纹理ID
	 * @param textureWidth SDF纹理宽度
	 * @param textureHeight SDF纹理高度
	 * @param scanLine 扫描线位置（0-1）
	 * @param scanWidth 扫描线宽度
	 * @param offsetX X偏移
	 * @param offsetY Y偏移
	 * @param color 扫描光颜色
	 * @param alphaMult 透明度倍数
	 */
	public void renderGlow(ShipAPI anchor, int textureId, float textureWidth, float textureHeight, 
	                       float scanLine, float scanWidth, float offsetX, float offsetY, 
	                       Color color, float alphaMult) {

		if (program != null && vaoId > 0 && vboId > 0) {

			float angle = anchor.getFacing();
			Vector2f location = anchor.getLocation();

			// 设置混合模式（加法混合）
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

			// 绑定VAO
			GL30.glBindVertexArray(vaoId);
			program.active();

			// 设置模型矩阵
			TransformUtil.createModelMatrixVanilla(location, MathUtils.clampAngle(angle), matrix);
			GL20.glUniformMatrix4(program.location[0], false, CommonUtil.createFloatBuffer(matrix));

			// 设置大小
			GL20.glUniform2f(program.location[1], textureHeight, textureWidth);

			// 设置偏移
			GL20.glUniform2f(program.location[2], offsetY, offsetX);

			// 设置UV
			GL20.glUniform2f(program.location[3], 1, 1);

			// 绑定纹理
			GL13.glActiveTexture(GL13.GL_TEXTURE0);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
			GL20.glUniform1i(program.location[4], 0);

			// 设置扫描光颜色
			GL20.glUniform4f(program.location[5], 
				color.getRed() / 255f, 
				color.getGreen() / 255f, 
				color.getBlue() / 255f, 
				color.getAlpha() / 255f * alphaMult);

			// 设置扫描线参数
			GL20.glUniform1f(program.location[6], scanLine);
			GL20.glUniform1f(program.location[7], scanWidth);

			// 绘制
			GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

			// 清理
			program.close();
			GL30.glBindVertexArray(0);
		}
	}
}
