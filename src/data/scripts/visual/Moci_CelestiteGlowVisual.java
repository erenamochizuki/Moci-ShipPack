package data.scripts.visual;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_BoxBasedUtil;
import org.boxutil.base.BaseShaderData;
import org.boxutil.manager.ShaderCore;
import org.boxutil.util.CommonUtil;
import org.boxutil.util.ShaderUtil;
import org.boxutil.util.TransformUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.EnumSet;

/**
 * 基于SDF轮廓的舰船辉光渲染插件。
 * 通过 ship.getMutableStats().getDynamic() 暴露动态参数，方便系统脚本联动。
 */
public class Moci_CelestiteGlowVisual extends BaseCombatLayeredRenderingPlugin {

	public static final String DATA_KEY = "Moci_CelestiteGlowVisual";
	public static final String KEY_OUTLINE_SIZE = "MOCI_CGV_OUTLINE_SIZE";
	public static final String KEY_OUTLINE_INTENSITY = "MOCI_CGV_OUTLINE_INTENSITY";
	public static final String KEY_OUTLINE_COLOR = "MOCI_CGV_OUTLINE_COLOR";
	public static final String KEY_OUTLINE_ALPHA = "MOCI_CGV_OUTLINE_ALPHA";
	public static final String KEY_OUTLINE_FLASH = "MOCI_CGV_OUTLINE_FLASH";
	public static final String KEY_OUTLINE_SHIFT = "MOCI_CGV_OUTLINE_SHIFT";

	public static final Color BASE_OUTLINE_COLOR = new Color(60, 125, 255, 255);
	public static final Color SHIFT_OUTLINE_COLOR = new Color(143, 227, 246, 255);

	public static final float BASE_OUTLINE_SIZE = 0.003f;
	public static final float BASE_OUTLINE_INTENSITY = 0.25f;
	public static final float BASE_OUTLINE_ALPHA = 0.25f;
	public static final float BASE_OUTLINE_FLASH = 0.5f;
	public static final float BASE_OUTLINE_SHIFT = 0.5f;
	public static final byte SDF_STROKE = 32;
	public static final float SDF_ALPHA_THRESHOLD = Moci_BoxBasedUtil.SOLID_ALPHA_THRESHOLD;
	private static final float MODULE_INTENSITY_MULT = 0.95f;
	private static final float MODULE_ALPHA_MULT = 0.9f;
	private static final float WEAPON_INTENSITY_MULT = 0.85f;
	private static final float WEAPON_ALPHA_MULT = 0.8f;
	private static final float MIN_PART_RADIUS = 6f;

	private final ShipAPI ship;
	private final int sdfTextureId;
	private final float sdfWidth;
	private final float sdfHeight;

	private boolean valid = true;
	private float aliveLevel = 1f;

	private int vaoId = -1;
	private int vboId = -1;
	private BaseShaderData program;
	private final Matrix4f matrix = new Matrix4f();
	private float dynamicRenderRadius = -1f;

	public Moci_CelestiteGlowVisual(ShipAPI ship) {
		this.ship = ship;

		SpriteAPI sprite = ship.getSpriteAPI();
		int[] sdfData = Moci_BoxBasedUtil.genSDF(sprite, SDF_STROKE, SDF_ALPHA_THRESHOLD);
		this.sdfTextureId = sdfData[0];
		this.sdfWidth = sdfData[1];
		this.sdfHeight = sdfData[2];
	}

	public static Moci_CelestiteGlowVisual getInstance(CombatEngineAPI engine, ShipAPI ship, boolean shouldCreate) {
		if (!ship.getCustomData().containsKey(DATA_KEY) && shouldCreate) {
			Moci_CelestiteGlowVisual plugin = new Moci_CelestiteGlowVisual(ship);
			engine.addLayeredRenderingPlugin(plugin);
			ship.setCustomData(DATA_KEY, plugin);
			return plugin;
		}
		return (Moci_CelestiteGlowVisual) ship.getCustomData().get(DATA_KEY);
	}

	public void setValid(boolean valid) {
		this.valid = valid;
	}

	@Override
	public float getRenderRadius() {
		if (dynamicRenderRadius > 0f) {
			return dynamicRenderRadius;
		}
		return ship.getCollisionRadius() + SDF_STROKE + 128f;
	}

	@Override
	public EnumSet<CombatEngineLayers> getActiveLayers() {
		return EnumSet.of(CombatEngineLayers.BELOW_SHIPS_LAYER);
	}

	@Override
	public void init(CombatEntityAPI entity) {
		super.init(entity);

		int programId = ShaderUtil.createShaderVFFormPath(
				this.getClass().getName(),
				"data/shaders/Moci_celestite_underglow.vert",
				"data/shaders/Moci_celestite_underglow.frag"
		);

		program = new BaseShaderData(programId) {};
		program.location = new int[] {
				program.getUniformIndex("modelMatrix"),
				program.getUniformIndex("size"),
				program.getUniformIndex("offset"),
				program.getUniformIndex("uv"),
				program.getUniformIndex("tex"),
				program.getUniformIndex("outlineColor"),
				program.getUniformIndex("outlineIntensity"),
				program.getUniformIndex("outlineSize")
		};
		program.uboLocation = new int[] {
				program.getUBOIndex("BUtilGlobalData", ShaderCore.getMatrixUBOBinding())
		};

		int[] ids = Moci_BoxBasedUtil.createUniversalRectVAO();
		vaoId = ids[0];
		vboId = ids[1];
	}

	@Override
	public void advance(float amount) {
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null || engine.isPaused()) {
			return;
		}

		entity.getLocation().set(ship.getLocation());

		if (!ship.isAlive()) {
			aliveLevel -= amount * 3f;
			if (aliveLevel <= 0f) {
				aliveLevel = 0f;
				valid = false;
			}
		} else if (aliveLevel < 1f) {
			aliveLevel += amount * 2f;
			if (aliveLevel > 1f) {
				aliveLevel = 1f;
			}
		}

		updateDynamicRenderRadius();
	}

	@Override
	public void render(CombatEngineLayers layer, ViewportAPI viewport) {
		if (layer != CombatEngineLayers.BELOW_SHIPS_LAYER) {
			return;
		}

		float alphaMult = viewport.getAlphaMult() * ship.getAlphaMult() * aliveLevel;
		if (alphaMult <= 0f) {
			return;
		}

		float frameTime = Global.getCombatEngine().getElapsedInLastFrame();
		float offsetFlash = getOutlineFlashMult() + ship.getHullSize().ordinal() * 0.1f;
		float randomShiftX = MathUtils.getRandomNumberInRange(-offsetFlash, offsetFlash);
		float randomShiftY = MathUtils.getRandomNumberInRange(-offsetFlash, offsetFlash);

		float shiftMult = getOutlineShiftMult();
		float offsetX = randomShiftX - ship.getVelocity().x * shiftMult * frameTime;
		float offsetY = randomShiftY - ship.getVelocity().y * shiftMult * frameTime;

		float shipPartRadius = Math.max(ship.getCollisionRadius(), getSpriteRadius(ship.getSpriteAPI()));
		renderGlowAt(
				ship.getLocation(),
				ship.getFacing(),
				sdfTextureId,
				sdfWidth,
				sdfHeight,
				offsetX,
				offsetY,
				alphaMult,
				getOutlineIntensity(),
				getOutlineSizeForRadius(shipPartRadius),
				getOutlineAlphaMult()
		);

		renderModuleOutlines(offsetX, offsetY, alphaMult);
		renderWeaponOutlines(offsetX, offsetY, alphaMult);
	}

	private void renderGlowAt(
			Vector2f location,
			float facing,
			int textureId,
			float textureWidth,
			float textureHeight,
			float offsetX,
			float offsetY,
			float alphaMult,
			float outlineIntensity,
			float outlineSize,
			float outlineAlpha
	) {
		if (location == null || program == null || vaoId <= 0 || vboId <= 0) {
			return;
		}

		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

		GL30.glBindVertexArray(vaoId);
		program.active();

		TransformUtil.createModelMatrixVanilla(location, MathUtils.clampAngle(facing), matrix);
		GL20.glUniformMatrix4(program.location[0], false, CommonUtil.createFloatBuffer(matrix));
		GL20.glUniform2f(program.location[1], textureHeight, textureWidth);
		GL20.glUniform2f(program.location[2], offsetY, offsetX);
		GL20.glUniform2f(program.location[3], 1f, 1f);

		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
		GL20.glUniform1i(program.location[4], 0);

		Color color = getOutlineColor();
		float finalAlpha = Math.max(0f, Math.min(1f, alphaMult * outlineAlpha));
		GL20.glUniform4f(
				program.location[5],
				color.getRed() / 255f,
				color.getGreen() / 255f,
				color.getBlue() / 255f,
				color.getAlpha() / 255f * finalAlpha
		);

		float safeIntensity = Math.max(0.01f, outlineIntensity);
		float safeOutlineSize = Math.max(0.001f, Math.min(0.95f, outlineSize));
		GL20.glUniform1f(program.location[6], safeIntensity);
		GL20.glUniform1f(program.location[7], safeOutlineSize);

		GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

		program.close();
		GL30.glBindVertexArray(0);
	}

	private void renderModuleOutlines(float offsetX, float offsetY, float alphaMult) {
		if (!ship.isShipWithModules()) {
			return;
		}

		for (ShipAPI module : ship.getChildModulesCopy()) {
			if (module == null || !module.isAlive()) {
				continue;
			}

			SpriteAPI sprite = module.getSpriteAPI();
			if (!isRenderableSprite(sprite)) {
				continue;
			}

			int[] sdfData = Moci_BoxBasedUtil.genSDF(sprite, SDF_STROKE, SDF_ALPHA_THRESHOLD);
			float partRadius = Math.max(module.getCollisionRadius(), getSpriteRadius(sprite));

			renderGlowAt(
					module.getLocation(),
					module.getFacing(),
					sdfData[0],
					sdfData[1],
					sdfData[2],
					offsetX,
					offsetY,
					alphaMult,
					getOutlineIntensity() * MODULE_INTENSITY_MULT,
					getOutlineSizeForRadius(partRadius),
					getOutlineAlphaMult() * MODULE_ALPHA_MULT
			);
		}
	}

	private void renderWeaponOutlines(float offsetX, float offsetY, float alphaMult) {
		for (WeaponAPI weapon : ship.getAllWeapons()) {
			if (!shouldRenderWeapon(weapon)) {
				continue;
			}

			SpriteAPI sprite = weapon.getSprite();
			if (!isRenderableSprite(sprite) || weapon.getLocation() == null) {
				continue;
			}

			int[] sdfData = Moci_BoxBasedUtil.genSDF(sprite, SDF_STROKE, SDF_ALPHA_THRESHOLD);
			float partRadius = getSpriteRadius(sprite);

			renderGlowAt(
					weapon.getLocation(),
					weapon.getCurrAngle(),
					sdfData[0],
					sdfData[1],
					sdfData[2],
					offsetX,
					offsetY,
					alphaMult,
					getOutlineIntensity() * WEAPON_INTENSITY_MULT,
					getOutlineSizeForRadius(partRadius),
					getOutlineAlphaMult() * WEAPON_ALPHA_MULT
			);
		}
	}

	private boolean shouldRenderWeapon(WeaponAPI weapon) {
		if (weapon == null || weapon.getSprite() == null || weapon.getSlot() == null) {
			return false;
		}
		if (weapon.isDisabled()) {
			return false;
		}
		return !weapon.getSlot().isHidden() && (weapon.getSlot().isBuiltIn() || weapon.getSlot().isDecorative());
	}

	private boolean isRenderableSprite(SpriteAPI sprite) {
		if (sprite == null) {
			return false;
		}
		if (sprite.getWidth() <= 1f || sprite.getHeight() <= 1f) {
			return false;
		}
		return sprite.getTextureId() > 0;
	}

	private float getSpriteRadius(SpriteAPI sprite) {
		if (sprite == null) {
			return MIN_PART_RADIUS;
		}
		return Math.max(MIN_PART_RADIUS, Math.max(sprite.getWidth(), sprite.getHeight()) * 0.5f);
	}

	private float getOutlineSizeForRadius(float radius) {
		float safeRadius = Math.max(1f, radius);
		return getOutlineSizeStat().computeEffective(safeRadius * BASE_OUTLINE_SIZE);
	}

	private void updateDynamicRenderRadius() {
		float maxRadius = ship.getCollisionRadius() + SDF_STROKE + 32f;
		Vector2f center = ship.getLocation();

		if (ship.isShipWithModules()) {
			for (ShipAPI module : ship.getChildModulesCopy()) {
				if (module == null || module.getLocation() == null || module.getSpriteAPI() == null) {
					continue;
				}
				float dx = module.getLocation().x - center.x;
				float dy = module.getLocation().y - center.y;
				float distance = (float) Math.sqrt(dx * dx + dy * dy);
				float moduleReach = distance + getSpriteRadius(module.getSpriteAPI()) + SDF_STROKE;
				if (moduleReach > maxRadius) {
					maxRadius = moduleReach;
				}
			}
		}

		for (WeaponAPI weapon : ship.getAllWeapons()) {
			if (!shouldRenderWeapon(weapon) || weapon.getLocation() == null || weapon.getSprite() == null) {
				continue;
			}
			float dx = weapon.getLocation().x - center.x;
			float dy = weapon.getLocation().y - center.y;
			float distance = (float) Math.sqrt(dx * dx + dy * dy);
			float weaponReach = distance + getSpriteRadius(weapon.getSprite()) + SDF_STROKE;
			if (weaponReach > maxRadius) {
				maxRadius = weaponReach;
			}
		}

		dynamicRenderRadius = maxRadius;
	}

	@Override
	public boolean isExpired() {
		return !valid;
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

	public StatBonus getOutlineSizeStat() {
		return ship.getMutableStats().getDynamic().getMod(KEY_OUTLINE_SIZE);
	}

	public float getOutlineSize() {
		return getOutlineSizeForRadius(ship.getCollisionRadius());
	}

	public StatBonus getOutlineIntensityStat() {
		return ship.getMutableStats().getDynamic().getMod(KEY_OUTLINE_INTENSITY);
	}

	public float getOutlineIntensity() {
		return getOutlineIntensityStat().computeEffective(BASE_OUTLINE_INTENSITY);
	}

	public StatBonus getOutlineAlphaMultStat() {
		return ship.getMutableStats().getDynamic().getMod(KEY_OUTLINE_ALPHA);
	}

	public float getOutlineAlphaMult() {
		return getOutlineAlphaMultStat().computeEffective(BASE_OUTLINE_ALPHA);
	}

	public StatBonus getOutlineFlashMultStat() {
		return ship.getMutableStats().getDynamic().getMod(KEY_OUTLINE_FLASH);
	}

	public float getOutlineFlashMult() {
		return getOutlineFlashMultStat().computeEffective(BASE_OUTLINE_FLASH);
	}

	public StatBonus getOutlineShiftMultStat() {
		return ship.getMutableStats().getDynamic().getMod(KEY_OUTLINE_SHIFT);
	}

	public float getOutlineShiftMult() {
		return getOutlineShiftMultStat().computeEffective(BASE_OUTLINE_SHIFT);
	}

	public StatBonus getColorShiftMultStat() {
		return ship.getMutableStats().getDynamic().getMod(KEY_OUTLINE_COLOR);
	}

	public Color getOutlineColor() {
		float shift = getColorShiftMultStat().computeEffective(0f);
		if (shift <= 0f) {
			return BASE_OUTLINE_COLOR;
		}
		if (shift >= 1f) {
			return SHIFT_OUTLINE_COLOR;
		}
		return Misc.interpolateColor(BASE_OUTLINE_COLOR, SHIFT_OUTLINE_COLOR, shift);
	}
}
