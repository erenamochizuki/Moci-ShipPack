package data.scripts.visual;

import java.awt.Color;
import java.util.EnumSet;
import java.util.List;

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

/**
 * Psycommu 用的绿色 Halo 渲染插件。
 * 通过舰船实像素轮廓和 SDF 外扩，在舰船下层绘制绿色外缘 Halo。
 */
public class Moci_PsycommuGlowVisual extends BaseCombatLayeredRenderingPlugin {

	public static final String DATA_KEY = "Moci_PsycommuGlowVisual";
	public static final String KEY_OUTLINE_SIZE = "MOCI_PGV_OUTLINE_SIZE";
	public static final String KEY_OUTLINE_INTENSITY = "MOCI_PGV_OUTLINE_INTENSITY";
	public static final String KEY_OUTLINE_COLOR = "MOCI_PGV_OUTLINE_COLOR";
	public static final String KEY_OUTLINE_ALPHA = "MOCI_PGV_OUTLINE_ALPHA";
	public static final String KEY_OUTLINE_FLASH = "MOCI_PGV_OUTLINE_FLASH";
	public static final String KEY_OUTLINE_SHIFT = "MOCI_PGV_OUTLINE_SHIFT";
	public static final String KEY_OUTLINE_COLOR_OVERRIDE = "MOCI_PGV_OUTLINE_COLOR_OVERRIDE";

	public static final Color BASE_OUTLINE_COLOR = new Color(70, 255, 96, 255);
	public static final Color SHIFT_OUTLINE_COLOR = new Color(128, 255, 168, 255);

	// 这里的 base 值是给 StatBonus 参与 computeEffective 用的，不是直接喂 shader 的最终值。
	public static final float BASE_OUTLINE_SIZE = 1f;
	public static final float BASE_OUTLINE_INTENSITY = 0.25f;
	public static final float BASE_OUTLINE_ALPHA = 0.25f;
	public static final float BASE_OUTLINE_FLASH = 0.5f;
	public static final float BASE_OUTLINE_SHIFT = 0.5f;

	private static final float STENCIL_ALPHA_THRESHOLD = Moci_BoxBasedUtil.SOLID_ALPHA_THRESHOLD;
	private static final byte SDF_STROKE = 32;
	private static final float SDF_ALPHA_THRESHOLD = Moci_BoxBasedUtil.SOLID_ALPHA_THRESHOLD;
	private static final float FIXED_OUTLINE_PIXELS = 3f;
	private static final float FIXED_OUTLINE_SIZE = FIXED_OUTLINE_PIXELS / SDF_STROKE;
	private static final float MIN_PART_RADIUS = 6f;
	private static final float MODULE_INTENSITY_MULT = 0.92f;
	private static final float MODULE_ALPHA_MULT = 0.90f;
	private static final float WEAPON_INTENSITY_MULT = 0.86f;
	private static final float WEAPON_ALPHA_MULT = 0.82f;
	private static final CombatEngineLayers HALO_LAYER = CombatEngineLayers.BELOW_SHIPS_LAYER;

	private final ShipAPI ship;

	private boolean valid = true;
	private float aliveLevel = 1f;
	private float dynamicRenderRadius = -1f;
	private float patternTime = 0f;

	private int vaoId = -1;
	private int vboId = -1;
	private BaseShaderData program;
	private final Matrix4f matrix = new Matrix4f();
	private SpriteAPI haloPattern;
	private SpriteAPI haloFog;

	public Moci_PsycommuGlowVisual(ShipAPI ship) {
		this.ship = ship;
	}

	public static Moci_PsycommuGlowVisual getInstance(CombatEngineAPI engine, ShipAPI ship, boolean shouldCreate) {
		if (!ship.getCustomData().containsKey(DATA_KEY) && shouldCreate) {
			Moci_PsycommuGlowVisual plugin = new Moci_PsycommuGlowVisual(ship);
			engine.addLayeredRenderingPlugin(plugin);
			ship.setCustomData(DATA_KEY, plugin);
			return plugin;
		}
		return (Moci_PsycommuGlowVisual) ship.getCustomData().get(DATA_KEY);
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
		return EnumSet.of(HALO_LAYER);
	}

	@Override
	public void init(CombatEntityAPI entity) {
		super.init(entity);

		int programId = ShaderUtil.createShaderVFFormPath(
				this.getClass().getName(),
				"data/shaders/Moci_psycommu_halo.vert",
				"data/shaders/Moci_psycommu_halo.frag"
		);

		program = new BaseShaderData(programId) {};
		program.location = new int[] {
				program.getUniformIndex("modelMatrix"),
				program.getUniformIndex("size"),
				program.getUniformIndex("offset"),
				program.getUniformIndex("uv"),
				program.getUniformIndex("maskTex"),
				program.getUniformIndex("patternTex"),
				program.getUniformIndex("fogTex"),
				program.getUniformIndex("outlineColor"),
				program.getUniformIndex("outlineAlpha"),
				program.getUniformIndex("outlineIntensity"),
				program.getUniformIndex("outlineSize"),
				program.getUniformIndex("time"),
				program.getUniformIndex("patternUvRect"),
				program.getUniformIndex("fogUvRect")
		};
		program.uboLocation = new int[] {
				program.getUBOIndex("BUtilGlobalData", ShaderCore.getMatrixUBOBinding())
		};

		int[] ids = Moci_BoxBasedUtil.createUniversalRectVAO();
		vaoId = ids[0];
		vboId = ids[1];

		haloPattern = Global.getSettings().getSprite("fx", "Moci_GNShield");
		haloFog = Global.getSettings().getSprite("fx", "Moci_GNShield_fog");
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

		// 不做抖动偏移，只把系统提供的 flash/shift 当成纹理流动速度调制。
		patternTime += amount * (0.35f + getOutlineShiftMult() * 0.12f + getOutlineFlashMult() * 0.08f);
		updateDynamicRenderRadius();
	}

	@Override
	public void render(CombatEngineLayers layer, ViewportAPI viewport) {
		if (layer != HALO_LAYER || ship == null || ship.getSpriteAPI() == null) {
			return;
		}
		if (program == null || vaoId <= 0 || vboId <= 0 || haloPattern == null || haloFog == null) {
			return;
		}

		float combinedAlpha = viewport.getAlphaMult()
				* ship.getAlphaMult()
				* ship.getExtraAlphaMult()
				* ship.getExtraAlphaMult2()
				* aliveLevel;
		if (combinedAlpha <= 0f) {
			return;
		}

		float sizeLevel = getOutlineSize();
		float intensity = getOutlineIntensity();
		float outlineAlpha = getOutlineAlphaMult();
		if (sizeLevel <= 0f || intensity <= 0f || outlineAlpha <= 0f) {
			return;
		}

		renderStencilMaskedHalo(combinedAlpha, sizeLevel, intensity, outlineAlpha);
	}

	private void renderStencilMaskedHalo(float combinedAlpha, float sizeLevel, float intensity, float outlineAlpha) {
		GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_STENCIL_TEST);
		GL11.glDisable(GL11.GL_DEPTH_TEST);

		// 用舰船实像素写模板，只允许原轮廓外侧的 halo 通过。
		GL11.glColorMask(false, false, false, false);
		GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 255);
		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
		GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
		GL11.glEnable(GL11.GL_ALPHA_TEST);
		GL11.glAlphaFunc(GL11.GL_GREATER, STENCIL_ALPHA_THRESHOLD);
		GL11.glColor4f(1f, 1f, 1f, 1f);
		renderSilhouetteMask();

		GL11.glStencilFunc(GL11.GL_NOTEQUAL, 1, 255);
		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
		GL11.glColorMask(true, true, true, true);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glEnable(GL11.GL_BLEND);
		// 用偏发光的混合，但实际透明度交给 shader 柔化。
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

		renderShipHalo(sizeLevel, intensity, combinedAlpha * outlineAlpha);
		renderModuleHalos(sizeLevel, intensity, combinedAlpha * outlineAlpha);
		renderWeaponHalos(sizeLevel, intensity, combinedAlpha * outlineAlpha);

		GL11.glDisable(GL11.GL_STENCIL_TEST);
		GL11.glColor4f(1f, 1f, 1f, 1f);
		GL11.glPopAttrib();
	}

	private void renderSilhouetteMask() {
		renderSilhouetteSprite(ship.getSpriteAPI(), ship.getLocation());

		List<ShipAPI> modules = ship.getChildModulesCopy();
		if (modules != null) {
			for (ShipAPI module : modules) {
				if (module == null) {
					continue;
				}
				renderSilhouetteSprite(module.getSpriteAPI(), module.getLocation());
			}
		}

		for (WeaponAPI weapon : ship.getAllWeapons()) {
			if (!shouldRenderWeapon(weapon)) {
				continue;
			}
			renderWeaponSilhouetteSprites(weapon);
		}
	}

	private void renderWeaponSilhouetteSprites(WeaponAPI weapon) {
		if (weapon == null || weapon.getLocation() == null) {
			return;
		}
		renderSilhouetteSprite(weapon.getSprite(), weapon.getLocation());
		renderSilhouetteSprite(weapon.getBarrelSpriteAPI(), weapon.getLocation());
	}

	private void renderSilhouetteSprite(SpriteAPI sprite, Vector2f location) {
		if (!isRenderableSprite(sprite) || location == null) {
			return;
		}
		sprite.renderAtCenter(location.x, location.y);
	}

	private void renderShipHalo(float sizeLevel, float intensity, float alpha) {
		SpriteAPI sprite = ship.getSpriteAPI();
		if (!isRenderableSprite(sprite)) {
			return;
		}

		renderHaloFromSprite(
				ship.getLocation(),
				ship.getFacing(),
				sprite,
				alpha,
				intensity,
				getFixedOutlineSize(sizeLevel)
		);
	}

	private void renderModuleHalos(float sizeLevel, float intensity, float alpha) {
		List<ShipAPI> modules = ship.getChildModulesCopy();
		if (modules == null) {
			return;
		}

		for (ShipAPI module : modules) {
			if (module == null || !isRenderableSprite(module.getSpriteAPI()) || module.getLocation() == null) {
				continue;
			}

			renderHaloFromSprite(
					module.getLocation(),
					module.getFacing(),
					module.getSpriteAPI(),
					alpha * MODULE_ALPHA_MULT,
					intensity * MODULE_INTENSITY_MULT,
					getFixedOutlineSize(sizeLevel)
			);
		}
	}

	private void renderWeaponHalos(float sizeLevel, float intensity, float alpha) {
		for (WeaponAPI weapon : ship.getAllWeapons()) {
			if (!shouldRenderWeapon(weapon) || weapon.getLocation() == null) {
				continue;
			}

			float weaponAlpha = alpha * WEAPON_ALPHA_MULT;
			float weaponIntensity = intensity * WEAPON_INTENSITY_MULT;
			float outlineSize = getFixedOutlineSize(sizeLevel);

			renderHaloFromSprite(
					weapon.getLocation(),
					weapon.getCurrAngle(),
					weapon.getSprite(),
					weaponAlpha,
					weaponIntensity,
					outlineSize
			);
			renderHaloFromSprite(
					weapon.getLocation(),
					weapon.getCurrAngle(),
					weapon.getBarrelSpriteAPI(),
					weaponAlpha,
					weaponIntensity,
					outlineSize
			);
		}
	}

	private void renderHaloFromSprite(
			Vector2f location,
			float facing,
			SpriteAPI sprite,
			float alpha,
			float intensity,
			float outlineSize
	) {
		if (!isRenderableSprite(sprite) || location == null || alpha <= 0f || outlineSize <= 0f) {
			return;
		}

		int[] sdfData = Moci_BoxBasedUtil.genSDF(sprite, SDF_STROKE, SDF_ALPHA_THRESHOLD);
		renderGlowAt(
				location,
				facing,
				sprite,
				sdfData[0],
				sdfData[1],
				sdfData[2],
				alpha,
				intensity,
				outlineSize
		);
	}

	private void renderGlowAt(
			Vector2f location,
			float facing,
			SpriteAPI sourceSprite,
			int maskTextureId,
			float textureWidth,
			float textureHeight,
			float alpha,
			float intensity,
			float outlineSize
	) {
		GL30.glBindVertexArray(vaoId);
		program.active();

		TransformUtil.createModelMatrixVanilla(location, MathUtils.clampAngle(facing), matrix);
		GL20.glUniformMatrix4(program.location[0], false, CommonUtil.createFloatBuffer(matrix));
		GL20.glUniform2f(
				program.location[1],
				getSdfRenderHeight(textureHeight),
				getSdfRenderWidth(textureWidth)
		);
		Vector2f spriteCenterOffset = getSpriteCenterOffset(sourceSprite);
		GL20.glUniform2f(program.location[2], spriteCenterOffset.x, spriteCenterOffset.y);
		GL20.glUniform2f(program.location[3], 1f, 1f);

		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTextureId);
		GL20.glUniform1i(program.location[4], 0);

		GL13.glActiveTexture(GL13.GL_TEXTURE1);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, haloPattern.getTextureId());
		GL20.glUniform1i(program.location[5], 1);

		GL13.glActiveTexture(GL13.GL_TEXTURE2);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, haloFog.getTextureId());
		GL20.glUniform1i(program.location[6], 2);

		Color color = getOutlineColor();
		GL20.glUniform4f(
				program.location[7],
				color.getRed() / 255f,
				color.getGreen() / 255f,
				color.getBlue() / 255f,
				color.getAlpha() / 255f
		);
		GL20.glUniform1f(program.location[8], Math.max(0f, Math.min(1f, alpha)));
		GL20.glUniform1f(program.location[9], Math.max(0.01f, intensity));
		GL20.glUniform1f(program.location[10], Math.max(0.01f, Math.min(0.95f, outlineSize)));
		GL20.glUniform1f(program.location[11], patternTime);
		GL20.glUniform4f(
				program.location[12],
				haloPattern.getTexX(),
				haloPattern.getTexY(),
				haloPattern.getTexWidth(),
				haloPattern.getTexHeight()
		);
		GL20.glUniform4f(
				program.location[13],
				haloFog.getTexX(),
				haloFog.getTexY(),
				haloFog.getTexWidth(),
				haloFog.getTexHeight()
		);

		GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

		program.close();
		GL30.glBindVertexArray(0);
		GL13.glActiveTexture(GL13.GL_TEXTURE0);
	}

	private Vector2f getSpriteCenterOffset(SpriteAPI sprite) {
		if (sprite == null) {
			return new Vector2f();
		}

		float offsetX = sprite.getWidth() * 0.5f - sprite.getCenterX();
		float offsetY = sprite.getHeight() * 0.5f - sprite.getCenterY();
		// 当前 halo quad 预先做了 90 度轴交换，因此这里要把 sprite 的中心偏移映射回 shader 局部坐标。
		return new Vector2f(offsetY, -offsetX);
	}

	private boolean shouldRenderWeapon(WeaponAPI weapon) {
		if (weapon == null || weapon.getSlot() == null) {
			return false;
		}
		if (weapon.isDisabled()) {
			return false;
		}
		if (weapon.getSlot().isHidden()) {
			return false;
		}
		if (!weapon.getSlot().isBuiltIn() && !weapon.getSlot().isDecorative()) {
			return false;
		}
		return isRenderableSprite(weapon.getSprite()) || isRenderableSprite(weapon.getBarrelSpriteAPI());
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

		float extentLeft = sprite.getCenterX();
		float extentRight = sprite.getWidth() - sprite.getCenterX();
		float extentDown = sprite.getCenterY();
		float extentUp = sprite.getHeight() - sprite.getCenterY();
		float maxExtentX = Math.max(extentLeft, extentRight);
		float maxExtentY = Math.max(extentDown, extentUp);
		float radius = (float) Math.sqrt(maxExtentX * maxExtentX + maxExtentY * maxExtentY);
		return Math.max(MIN_PART_RADIUS, radius);
	}

	private float getFixedOutlineSize(float sizeLevel) {
		if (sizeLevel <= 0f) {
			return 0f;
		}
		return FIXED_OUTLINE_SIZE;
	}

	private float getSdfRenderWidth(float spriteWidth) {
		return spriteWidth + SDF_STROKE * 2f;
	}

	private float getSdfRenderHeight(float spriteHeight) {
		return spriteHeight + SDF_STROKE * 2f;
	}

	private void updateDynamicRenderRadius() {
		float maxRadius = ship.getCollisionRadius();
		Vector2f center = ship.getLocation();

		List<ShipAPI> modules = ship.getChildModulesCopy();
		if (modules != null) {
			for (ShipAPI module : modules) {
				if (module == null || module.getLocation() == null || !isRenderableSprite(module.getSpriteAPI())) {
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
			if (!shouldRenderWeapon(weapon) || weapon.getLocation() == null) {
				continue;
			}
			float dx = weapon.getLocation().x - center.x;
			float dy = weapon.getLocation().y - center.y;
			float distance = (float) Math.sqrt(dx * dx + dy * dy);
			float weaponReach = distance + getWeaponSpriteRadius(weapon) + SDF_STROKE;
			if (weaponReach > maxRadius) {
				maxRadius = weaponReach;
			}
		}

		dynamicRenderRadius = maxRadius + SDF_STROKE + FIXED_OUTLINE_PIXELS + 64f;
	}

	private float getWeaponSpriteRadius(WeaponAPI weapon) {
		float maxRadius = 0f;
		if (weapon == null) {
			return maxRadius;
		}
		if (isRenderableSprite(weapon.getSprite())) {
			maxRadius = Math.max(maxRadius, getSpriteRadius(weapon.getSprite()));
		}
		if (isRenderableSprite(weapon.getBarrelSpriteAPI())) {
			maxRadius = Math.max(maxRadius, getSpriteRadius(weapon.getBarrelSpriteAPI()));
		}
		return Math.max(MIN_PART_RADIUS, maxRadius);
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
		return getOutlineSizeStat().computeEffective(BASE_OUTLINE_SIZE);
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

	public void setOutlineColorOverride(Color color) {
		if (color == null) {
			ship.getCustomData().remove(KEY_OUTLINE_COLOR_OVERRIDE);
			return;
		}
		ship.setCustomData(KEY_OUTLINE_COLOR_OVERRIDE, color);
	}

	public Color getOutlineColor() {
		Object override = ship.getCustomData().get(KEY_OUTLINE_COLOR_OVERRIDE);
		if (override instanceof Color) {
			return (Color) override;
		}

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
