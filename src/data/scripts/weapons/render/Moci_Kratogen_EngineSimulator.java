package data.scripts.weapons.render;

import java.awt.Color;
import java.util.EnumSet;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

import data.hullmods.Moci_MobileSuitsIDcard;

/**
魔法猫猫的妙妙代码
 */
public class Moci_Kratogen_EngineSimulator extends BaseCombatLayeredRenderingPlugin {

	public static final String DATA_KEY = "Moci_Kratogen_EngineSimulator";
	public static final int ENGINE_PARTS = 6;
	public static final float SLIGHT_SPACE = 0f;//0.01f;
	public static final float DEFAULT_ENGINE_LEVEL = 0.6f;
	public static final float DEFAULT_MAX_SPREAD = 90f;
	public static final float DEFAULT_ROTATION_FACTOR = 0.15f;
	public static final float DEFAULT_ROTATION_THRESHOLD = 25f;
	public static final Vector2f ZERO = new Vector2f();

	private CombatEngineAPI engine;
	private boolean forceExpired = false;

	private final ShipAPI ship;
	private final ShipEngineAPI bindingEngine;
	private Vector2f locationOverride;

	private CombatEngineLayers glowLayer;
	private EnumSet<CombatEngineLayers> layers;

	private final SpriteAPI glowSprite;
	private final SpriteAPI glowOutline;
	private final SpriteAPI glowCircle;
	private Color colorOverride = null;

	private float engineLevel;
	private float spreadLevel;
	private float glowLevel;

	private float baseLength;
	private float baseWidth;
	private float glowSizeMult;
	private float lengthMult;
	private float widthMult;
	private float facing;

	private float rotationThreshold;
	private float textureElapsed;
	private boolean active = true;

	public Moci_Kratogen_EngineSimulator(ShipAPI ship, ShipEngineAPI bindingEngine, Vector2f locationOverride, SpriteAPI glowSprite, SpriteAPI glowOutline, SpriteAPI glowCircle) {
		this.ship = ship;
		this.bindingEngine = bindingEngine;
		this.locationOverride = locationOverride;
		this.facing = bindingEngine.getEngineSlot().computeMidArcAngle(ship.getFacing());

		this.baseLength = bindingEngine.getEngineSlot().getLength();
		this.baseWidth = bindingEngine.getEngineSlot().getWidth();
		this.glowSizeMult = 1f;
		this.lengthMult = 1.5f;
		this.widthMult = 1.5f;

		this.engineLevel = DEFAULT_ENGINE_LEVEL;
		this.spreadLevel = 0f;
		this.glowLevel = 0f;
		this.rotationThreshold = DEFAULT_ROTATION_THRESHOLD;

		this.glowSprite = glowSprite;
		this.glowOutline = glowOutline;
		this.glowCircle = glowCircle;
		this.textureElapsed = 0f;

		this.layer = CombatEngineLayers.ABOVE_SHIPS_LAYER;
		this.glowLayer = CombatEngineLayers.ABOVE_SHIPS_LAYER;
		this.layers = EnumSet.of(layer, glowLayer);
	}

	public ShipEngineAPI getBindingEngine() {
		return bindingEngine;
	}

	public void forceExpired() {
		forceExpired = true;
	}

	public float getEngineLevel() {
		return engineLevel;
	}

	public void overrideEngineLevel(float engineLevel) {
		this.engineLevel = engineLevel;
	}

	public float getSpreadLevel() {
		return spreadLevel;
	}

	public void overrideSpreadLevel(float spreadLevel) {
		this.spreadLevel = spreadLevel;
	}

	public float getBaseLength() {
		return baseLength;
	}

	public void setBaseLength(float baseLength) {
		this.baseLength = baseLength;
	}

	public float getBaseWidth() {
		return baseWidth;
	}

	public void setBaseWidth(float baseWidth) {
		this.baseWidth = baseWidth;
	}

	public float getGlowSizeMult() {
		return glowSizeMult;
	}

	public void setGlowSizeMult(float glowSizeMult) {
		this.glowSizeMult = glowSizeMult;
	}

	public float getLengthMult() {
		return lengthMult;
	}

	public void setLengthMult(float lengthMult) {
		this.lengthMult = lengthMult;
	}

	public float getWidthMult() {
		return widthMult;
	}

	public void setWidthMult(float widthMult) {
		this.widthMult = widthMult;
	}

	public float getFacing() {
		return facing;
	}

	public void setFacing(float facing) {
		this.facing = facing;
	}

	public Vector2f getLocation() {
		if (locationOverride != null) return locationOverride;
		return bindingEngine.getLocation();
	}

	public Vector2f getLocationOverride() {
		return locationOverride;
	}

	public void setLocationOverride(Vector2f locationOverride) {
		this.locationOverride.set(locationOverride);
	}

	public void renewLocationOverride(Vector2f location) {
		this.locationOverride = location;
	}

	public float getRotationThreshold() {
		return rotationThreshold;
	}

	public void setRotationThreshold(float rotationThreshold) {
		this.rotationThreshold = rotationThreshold;
	}

	public Color getColorOverride() {
		return colorOverride;
	}

	public void setColorOverride(Color colorOverride) {
		this.colorOverride = colorOverride;
	}

	public CombatEngineLayers getLayer() {
		return layer;
	}

	public void setLayer(CombatEngineLayers layer) {
		this.layer = layer;
		this.layers = EnumSet.of(layer, glowLayer);
	}

	public CombatEngineLayers getGlowLayer() {
		return glowLayer;
	}

	public void setGlowLayer(CombatEngineLayers glowLayer) {
		this.glowLayer = glowLayer;
		this.layers = EnumSet.of(layer, glowLayer);
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public boolean isActive() {
		return active;
	}

	@Override
	public void init(CombatEntityAPI entity) {
		super.init(entity);
		engine = Global.getCombatEngine();
	}

	@Override
	public boolean isExpired() {
		return !ship.isAlive() || !engine.isEntityInPlay(ship) || forceExpired;
	}

	public boolean shouldPause() {
		if (engine == null) return true;
		if (engine.isPaused()) return true;

		if (!ship.isAlive() || ship.isHulk()) return true;
		if (!engine.isEntityInPlay(ship)) return true;

		return false;
	}

	@Override
	public void advance(float amount) {

		if (shouldPause()) return;

		if (!active || Moci_MobileSuitsIDcard.isEngineRenderSuppressed(ship)) {
			engineLevel = Math.max(engineLevel - amount * 4f, 0f);
			spreadLevel = Math.max(spreadLevel - amount * DEFAULT_MAX_SPREAD * 4f, 0f);
			glowLevel = Math.max(glowLevel - amount * 4f, 0f);
			return;
		}

		Vector2f location = bindingEngine.getLocation();
		if (locationOverride != null) location = locationOverride;
		entity.getLocation().set(location);

		// 正确计算引擎朝向：基于引擎槽位的角度和舰船当前朝向
		this.facing = bindingEngine.getEngineSlot().computeMidArcAngle(ship.getFacing());

		textureElapsed -= amount;
		if (textureElapsed <= 0f) {
			textureElapsed += 1f;
		}

		ShipEngineControllerAPI controller = ship.getEngineController();
		if (bindingEngine.isDisabled()) {
			engineLevel = Math.max(engineLevel - amount, 0f);
			return;
		}

		boolean shouldIncreaseGlow = false;
		if (controller.isAccelerating()) {
			shouldIncreaseGlow = true;
			engineLevel = Math.min(engineLevel + amount * 0.8f, 0.9f);
		} else {
			engineLevel = Math.max(engineLevel - amount * 0.8f, DEFAULT_ENGINE_LEVEL);
		}

		if (controller.isDecelerating() || controller.isAcceleratingBackwards() || controller.isStrafingLeft() || controller.isStrafingRight()) {
			shouldIncreaseGlow = true;
			if (spreadLevel <= DEFAULT_MAX_SPREAD) {
				spreadLevel = Math.min(spreadLevel + amount * DEFAULT_MAX_SPREAD * 2f, DEFAULT_MAX_SPREAD);
			}
		} else {
			spreadLevel = Math.max(spreadLevel - amount * DEFAULT_MAX_SPREAD * 2f, 0f);
		}

		if (shouldIncreaseGlow) {
			glowLevel = Math.min(glowLevel + amount, 1f);
		} else {
			glowLevel = Math.max(glowLevel - amount * 2f, 0f);
		}
	}

	@Override
	public void render(CombatEngineLayers layerC, ViewportAPI view) {

		// if (shouldPause()) return;

		if (!active || Moci_MobileSuitsIDcard.isEngineRenderSuppressed(ship) || engineLevel <= 0f) return;

		float engineLength = baseLength * 2.5f * lengthMult;
		float engineWidth = baseWidth * widthMult;
		float engineWidthExtend = 1.3f + ship.getEngineController().getExtendWidthFraction().getCurr();
		float engineLengthExtend = 1f + ship.getEngineController().getExtendLengthFraction().getCurr() * 0.5f;
		engineLength *= engineLengthExtend;
		engineWidth *= engineWidthExtend;

		float engineSizeFactor = Math.max(0f, (engineLevel - 0.25f) / 0.75f);
		float renderWidthFactor = Math.max(0.1f, engineSizeFactor - 0.8f) / 0.2f;
		float renderLengthFactor = Math.max(0f, engineSizeFactor - 0.5f) / 0.5f;

		float totalRenderLength = engineLength * (0.2f + renderLengthFactor * 0.8f);
		float totalRenderWidth = engineWidth * (0.1f + renderWidthFactor * 0.9f);
		
		totalRenderWidth *= 1.2f;
		
		float maxAngleSpread = DEFAULT_MAX_SPREAD;
		renderWidthFactor *= (spreadLevel + maxAngleSpread) / maxAngleSpread;
		
		float partThreshold = engineWidth * (0.1f + renderWidthFactor * 0.9f);
		float partRenderLength = Math.min(partThreshold * 0.5f, totalRenderLength * 0.25f);
		float textureFactorForPart = partRenderLength / totalRenderLength;
		float textureFactorForTotal = engineLevel;

		float extraAlphaLevel = Math.min(engineLevel / 0.4f, 1f) * ship.getCombinedAlphaMult();
		float spreadRotateFactor = (1f - totalRenderLength / engineLength) * spreadLevel;
		float rotationAngleFactorBasedOnShip = -(ship.getAngularVelocity() * DEFAULT_ROTATION_FACTOR);
		if (rotationAngleFactorBasedOnShip < -rotationThreshold) rotationAngleFactorBasedOnShip = -rotationThreshold;
		else if (rotationAngleFactorBasedOnShip > rotationThreshold) rotationAngleFactorBasedOnShip = rotationThreshold;

		Vector2f location = bindingEngine.getLocation();
		if (locationOverride != null) location = locationOverride;

		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

		GL11.glPushMatrix();
		GL11.glTranslatef(location.getX(), location.getY(), 0f);
		GL11.glRotatef(facing, 0f, 0f, 1f);

		Color engineColor = bindingEngine.getEngineColor();
		if (colorOverride != null) engineColor = colorOverride;
		Color color = ship.getEngineController().getFlameColorShifter().getCurrForBase(engineColor);

		float textureForSubEngine = textureElapsed;
		int maxCount = ENGINE_PARTS;
		if (layerC == layer) for (int i = 0; i < maxCount; i++) {

			glowSprite.bindTexture();
			GL11.glPushMatrix();

			float rotationAngleBasedOnShip = (maxCount - i - 1f) / maxCount * rotationAngleFactorBasedOnShip;
			GL11.glRotatef(rotationAngleBasedOnShip, 0f, 0f, 1f);

			float facing = 1f;
			if (i % 2 == 0) {
				facing = -1f;
			}

			float rotationFactor = (i + 1f) / 2f;
			GL11.glRotatef((maxCount / 2f - rotationFactor - 1f) / (maxCount / 2f) * facing * 2f * spreadRotateFactor, 0f, 0f, 1f);
			GL11.glTranslatef((maxCount - i - 1f) * partRenderLength / (maxCount * 2f), 0f, 0f);
			GL11.glScalef(0.5f + 0.5f * (i + 1f) / maxCount, 1f * (maxCount - i) / maxCount, 1f);

			GL11.glBegin(GL11.GL_QUAD_STRIP);
			GL11.glColor4ub((byte) color.getRed(),
					(byte) color.getGreen(),
					(byte) color.getBlue(),
					(byte) ((float)color.getAlpha() * i * 5f / 255f * extraAlphaLevel));
			GL11.glTexCoord2f(textureForSubEngine, 0f + SLIGHT_SPACE);
			GL11.glVertex2f(0f, -totalRenderWidth / 2f);
			GL11.glTexCoord2f(textureForSubEngine, 1f - SLIGHT_SPACE);
			GL11.glVertex2f(0f, totalRenderWidth / 2f);

			GL11.glColor4ub((byte) color.getRed(),
					(byte) color.getGreen(),
					(byte) color.getBlue(),
					(byte) ((float)color.getAlpha() * 100f / 255f * extraAlphaLevel));
			GL11.glTexCoord2f(textureForSubEngine + textureFactorForPart, 0f + SLIGHT_SPACE);
			GL11.glVertex2f(partRenderLength, -totalRenderWidth / 2f);
			GL11.glTexCoord2f(textureForSubEngine + textureFactorForPart, 1f - SLIGHT_SPACE);
			GL11.glVertex2f(partRenderLength, totalRenderWidth / 2f);

			GL11.glColor4ub((byte) color.getRed(),
					(byte) color.getGreen(),
					(byte) color.getBlue(),
					(byte) 0);
			GL11.glTexCoord2f(textureForSubEngine + textureFactorForTotal, 0f + SLIGHT_SPACE);
			GL11.glVertex2f(totalRenderLength, -totalRenderWidth / 2f);
			GL11.glTexCoord2f(textureForSubEngine + textureFactorForTotal, 1f - SLIGHT_SPACE);
			GL11.glVertex2f(totalRenderLength, totalRenderWidth / 2f);
			GL11.glEnd();
			GL11.glPopMatrix();

			textureForSubEngine += 1f / maxCount;
		}

		float outlineAngle = 0f;
		if (layerC == layer) {
			glowOutline.bindTexture();
			GL11.glPushMatrix();

			GL11.glRotatef(outlineAngle, 0f, 0f, 1f);

			GL11.glColor4ub((byte)color.getRed(), (byte)color.getGreen(), (byte)color.getBlue(), (byte)(int)(engineLevel * 50f * color.getAlpha() / 255f * extraAlphaLevel));
			GL11.glBegin(GL11.GL_POLYGON_BIT);
			GL11.glTexCoord2f(0f + SLIGHT_SPACE, 0f + SLIGHT_SPACE);
			GL11.glVertex2f(0f, -totalRenderWidth / 2f);
			GL11.glTexCoord2f(0f + SLIGHT_SPACE, 1f - SLIGHT_SPACE);
			GL11.glVertex2f(0f, totalRenderWidth / 2f);
			GL11.glTexCoord2f(1f - SLIGHT_SPACE, 0f + SLIGHT_SPACE);
			GL11.glVertex2f(totalRenderLength, -totalRenderWidth / 2f);
			GL11.glTexCoord2f(1f - SLIGHT_SPACE, 1f - SLIGHT_SPACE);
			GL11.glVertex2f(totalRenderLength, totalRenderWidth / 2f);
			GL11.glEnd();
			GL11.glPopMatrix();
		}

		if (layerC == glowLayer) {
			float circleAlphaLevel = Math.min(0.75f + glowLevel, 1f);
			circleAlphaLevel = Math.min(circleAlphaLevel, extraAlphaLevel);

			float circleSizeFactor = circleAlphaLevel * 0.25f;
			if (circleAlphaLevel < 0.5f) {
				circleSizeFactor *= (0.15f + 0.85f * (circleAlphaLevel / 0.5f));
			}

			if (circleAlphaLevel > 0f) {

				float engineGlowExtend = 1f + ship.getEngineController().getExtendGlowFraction().getCurr();
				float engineGlow = 50f * engineGlowExtend;
				float underCircleSizeFactor = Math.min(circleSizeFactor, 15f) * 2f;

				glowCircle.bindTexture();
				GL11.glPushMatrix();
				GL11.glRotatef(outlineAngle, 0f, 0f, 1f);

				float size = engineGlow * (circleSizeFactor * 0.75f + underCircleSizeFactor) * glowSizeMult;
				Color colorForGlow = color;
				/*
			    GL11.glColor4ub((byte)colorForGlow.getRed(), (byte)colorForGlow.getGreen(), (byte)colorForGlow.getBlue(), (byte)(int)(circleAlphaLevel * colorForGlow.getAlpha()));
				GL11.glBegin(GL11.GL_POLYGON_BIT);
				GL11.glTexCoord2f(0f + SLIGHT_SPACE, 0f + SLIGHT_SPACE);
				GL11.glVertex2f(-size / 2f, -size / 2f);
				GL11.glTexCoord2f(0f + SLIGHT_SPACE, 1f - SLIGHT_SPACE);
				GL11.glVertex2f(-size / 2f, size / 2f);
				GL11.glTexCoord2f(1f - SLIGHT_SPACE, 0f + SLIGHT_SPACE);
				GL11.glVertex2f(size / 2f, -size / 2f);
				GL11.glTexCoord2f(1f - SLIGHT_SPACE, 1f - SLIGHT_SPACE);
				GL11.glVertex2f(size / 2f, size / 2f);
				GL11.glEnd();
				*/

				size = engineGlow * circleSizeFactor * 0.75f * glowSizeMult;
				// colorForGlow = Color.WHITE;
				GL11.glColor4ub((byte)colorForGlow.getRed(), (byte)colorForGlow.getGreen(), (byte)colorForGlow.getBlue(), (byte)(int)(circleAlphaLevel * colorForGlow.getAlpha()));
				GL11.glBegin(GL11.GL_POLYGON_BIT);
				GL11.glTexCoord2f(0f + SLIGHT_SPACE, 0f + SLIGHT_SPACE);
				GL11.glVertex2f(-size / 2f, -size / 2f);
				GL11.glTexCoord2f(0f + SLIGHT_SPACE, 1f - SLIGHT_SPACE);
				GL11.glVertex2f(-size / 2f, size / 2f);
				GL11.glTexCoord2f(1f - SLIGHT_SPACE, 0f + SLIGHT_SPACE);
				GL11.glVertex2f(size / 2f, -size / 2f);
				GL11.glTexCoord2f(1f - SLIGHT_SPACE, 1f - SLIGHT_SPACE);
				GL11.glVertex2f(size / 2f, size / 2f);
				GL11.glEnd();

				GL11.glPopMatrix();
			}
		}

		GL11.glPopMatrix();
	}

	@Override
	public float getRenderRadius() {
		return ship.getCollisionRadius();
	}

	@Override
	public EnumSet<CombatEngineLayers> getActiveLayers() {
		return layers;
	}
}
