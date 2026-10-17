package data.hullmods;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicIncompatibleHullmods;
import org.magiclib.util.MagicRender;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;

import data.scripts.util.Moci_TextLoader;

/**
 * 大和能量转换系统 I
 * - 光束武器造成100%的硬幅能伤害
 * - 降低光束武器20%的幅能消耗
 */
public class Moci_Ymato_ET_hullmods1 extends BaseHullMod {
	private static final String TEXT_ID = "Moci_Ymato_ET_hullmods1";

	// 硬幅能伤害比例（100%全部转换为硬通量）
	public static float HARD_FLUX_PERCENT = 100f;
	
	// 光束武器幅能消耗降低
	public static float BEAM_FLUX_COST_REDUCTION = 20f;
	
	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		ShipVariantAPI variant = stats.getVariant();
		
		// 检查并移除冲突的船插
		if (variant.hasHullMod(HullMods.ADVANCEDOPTICS)) {
			MagicIncompatibleHullmods.removeHullmodWithWarning(variant, HullMods.ADVANCEDOPTICS, spec.getId());
		}
		if (variant.hasHullMod("high_scatter_amp")) {
			MagicIncompatibleHullmods.removeHullmodWithWarning(variant, "high_scatter_amp", spec.getId());
		}
		
		// 降低光束武器幅能消耗
		stats.getBeamWeaponFluxCostMult().modifyMult(id, 1f - BEAM_FLUX_COST_REDUCTION * 0.01f);
	}

	@Override
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		// 添加伤害修改监听器
		ship.addListener(new YamatoETDamageDealtMod(ship));
	}
	
	// 残影效果参数
	private static final float AFTERIMAGE_INTERVAL = 0.02f;  // 残影间隔
	private static final float TOTAL_DURATION = 1.25f;       // 总持续时间
	private static final Color AFTERIMAGE_COLOR = new Color(255, 255, 255, 75);
	
	// CustomData 键
	private static final String KEY_ACCUMULATOR = "moci_yamato_et_accumulator";
	private static final String KEY_WAS_PHASE_ACTIVE = "moci_yamato_et_was_phase_active";
	private static final String KEY_ACTIVE_DURATION = "moci_yamato_et_active_duration";
	
	@Override
	public void advanceInCombat(ShipAPI ship, float amount) {
		if (ship == null) return;
		
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null || engine.isPaused()) return;
		
		// 从 ship.customData 获取状态数据
		Float accumulator = (Float) ship.getCustomData().get(KEY_ACCUMULATOR);
		Boolean wasPhaseActive = (Boolean) ship.getCustomData().get(KEY_WAS_PHASE_ACTIVE);
		Float activeDuration = (Float) ship.getCustomData().get(KEY_ACTIVE_DURATION);
		
		// 初始化
		if (accumulator == null) accumulator = 0f;
		if (wasPhaseActive == null) wasPhaseActive = false;
		if (activeDuration == null) activeDuration = 0f;
		
		// 检查phase cloak状态
		ShipSystemAPI phaseCloak = ship.getPhaseCloak();
		boolean isPhaseActive = phaseCloak != null && phaseCloak.isActive();
		
		// 检测phase cloak激活的瞬间
		if (isPhaseActive && !wasPhaseActive) {
			// 刚激活，重置计时器
			accumulator = 0f;
			activeDuration = 0f;
		}
		
		wasPhaseActive = isPhaseActive;
		
		// 在激活期间或激活后的1.25秒内生成残影
		if (isPhaseActive || activeDuration < TOTAL_DURATION) {
			activeDuration += amount;
			accumulator += amount;
			
			// 达到间隔时间，生成残影
			if (accumulator >= AFTERIMAGE_INTERVAL) {
				createAfterimage(ship);
				accumulator = 0f;
			}
			
			// 超过总持续时间，停止生成
			if (activeDuration >= TOTAL_DURATION) {
				activeDuration = TOTAL_DURATION;
			}
		}
		
		// 保存状态回 ship.customData
		ship.getCustomData().put(KEY_ACCUMULATOR, accumulator);
		ship.getCustomData().put(KEY_WAS_PHASE_ACTIVE, wasPhaseActive);
		ship.getCustomData().put(KEY_ACTIVE_DURATION, activeDuration);
	}
	
	/**
	 * 创建残影效果
	 */
	private void createAfterimage(ShipAPI ship) {
		// 获取船体贴图偏移量
		SpriteAPI sprite = ship.getSpriteAPI();
		float offsetX = sprite.getWidth()/2 - sprite.getCenterX();
		float offsetY = sprite.getHeight()/2 - sprite.getCenterY();
		
		// 计算旋转后的偏移量
		float trueOffsetX = (float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetX -
				(float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetY;
		float trueOffsetY = (float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetX +
				(float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetY;
		
		// 为船体创建残影
		MagicRender.battlespace(
			Global.getSettings().getSprite(ship.getHullSpec().getSpriteName()),
			new Vector2f(ship.getLocation().x + trueOffsetX, ship.getLocation().y + trueOffsetY),
			new Vector2f(0, 0),
			new Vector2f(sprite.getWidth(), sprite.getHeight()),
			new Vector2f(0, 0),
			ship.getFacing()-90f,
			0f,
			AFTERIMAGE_COLOR,
			true,
			0f,
			0f,
			0f,
			0f,
			0f,
			0f,
			0f,
			0.5f,
			CombatEngineLayers.BELOW_SHIPS_LAYER
		);
		
		// 为所有武器创建残影
		for (WeaponAPI weapon : ship.getAllWeapons()) {
			if (weapon.getSprite() == null) continue;
			
			// 检查武器是否被设置为完全透明（隐藏武器）
			boolean isHidden = false;
			if (weapon.getSprite() != null && weapon.getSprite().getColor().getAlpha() == 0) {
				isHidden = true;
			}
			if (weapon.getBarrelSpriteAPI() != null && weapon.getBarrelSpriteAPI().getColor().getAlpha() == 0) {
				isHidden = true;
			}
			if (weapon.getUnderSpriteAPI() != null && weapon.getUnderSpriteAPI().getColor().getAlpha() == 0) {
				isHidden = true;
			}
			if (weapon.getGlowSpriteAPI() != null && weapon.getGlowSpriteAPI().getColor().getAlpha() == 0) {
				isHidden = true;
			}
			
			if (isHidden) continue;
			
			// 获取武器贴图路径
			String spritePath;
			if (weapon.getAnimation() != null && weapon.getAnimation().getNumFrames() > 1) {
				// 对于动画武器，获取当前帧的贴图路径
				int frame = weapon.getAnimation().getFrame();
				String basePath = weapon.getSlot().isTurret() ?
						weapon.getSpec().getTurretSpriteName() :
						weapon.getSpec().getHardpointSpriteName();
				// 移除.png后缀（4个字符）
				if (basePath.endsWith(".png")) {
					basePath = basePath.substring(0, basePath.length() - 4);
				}
				// 添加帧号和后缀
				spritePath = String.format("%s_%02d.png", basePath, frame);
			} else {
				// 无动画的武器使用默认贴图
				spritePath = weapon.getSlot().isTurret() ?
						weapon.getSpec().getTurretSpriteName() :
						weapon.getSpec().getHardpointSpriteName();
			}
			
			// 获取新的Sprite实例
			SpriteAPI weaponSprite = Global.getSettings().getSprite(spritePath);
			weaponSprite.setSize(weapon.getSprite().getWidth(), weapon.getSprite().getHeight());
			
			// 创建武器残影
			MagicRender.battlespace(
				weaponSprite,
				new Vector2f(weapon.getLocation().x + trueOffsetX, weapon.getLocation().y + trueOffsetY),
				new Vector2f(0, 0),
				new Vector2f(weapon.getSprite().getWidth(), weapon.getSprite().getHeight()),
				new Vector2f(0, 0),
				weapon.getCurrAngle()-90f,
				0f,
				AFTERIMAGE_COLOR,
				true,
				0f,
				0f,
				0f,
				0f,
				0f,
				0f,
				0f,
				0.5f,
				CombatEngineLayers.BELOW_SHIPS_LAYER
			);
		}
	}
	
	/**
	 * 伤害修改器 - 使光束武器造成部分硬幅能伤害
	 */
	public static class YamatoETDamageDealtMod implements DamageDealtModifier {
		protected ShipAPI ship;
		
		public YamatoETDamageDealtMod(ShipAPI ship) {
			this.ship = ship;
		}
		
		public String modifyDamageDealt(Object param,
								   		CombatEntityAPI target, DamageAPI damage,
								   		Vector2f point, boolean shieldHit) {
			
			// 只对光束武器生效，强制造成硬通量伤害
			if (!(param instanceof DamagingProjectileAPI) && param instanceof BeamAPI) {
				damage.setForceHardFlux(true);
			}
			return null;
		}
	}

	@Override
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) return "" + (int)HARD_FLUX_PERCENT + "%";
		if (index == 1) return "" + (int)BEAM_FLUX_COST_REDUCTION + "%";
		if (index == 2) return Moci_TextLoader.getText(TEXT_ID, "description.param_advanced_optics");
		if (index == 3) return Moci_TextLoader.getText(TEXT_ID, "description.param_high_scatter_amp");
		return null;
	}
	
	@Override
	public boolean isApplicableToShip(ShipAPI ship) {
		return true;
	}
	
	@Override
	public String getUnapplicableReason(ShipAPI ship) {
		// 检查是否与冲突的船插同时存在
		if (ship != null && ship.getVariant() != null) {
			if (ship.getVariant().hasHullMod(HullMods.ADVANCEDOPTICS)) {
				return Moci_TextLoader.getText(TEXT_ID, "description.conflict_advanced_optics");
			}
			if (ship.getVariant().hasHullMod("high_scatter_amp")) {
				return Moci_TextLoader.getText(TEXT_ID, "description.conflict_high_scatter_amp");
			}
		}
		return null;
	}
	

}
