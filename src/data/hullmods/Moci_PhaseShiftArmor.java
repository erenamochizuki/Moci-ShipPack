package data.hullmods;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

public class Moci_PhaseShiftArmor extends BaseLogisticsHullMod {
    private static final String TEXT_ID = "Moci_PhaseShiftArmor";

	// 效果常量
	public static final float SENSOR_PROFILE_MULT = 0.5f; // 传感器信号乘数
	public static final int HARD_FLUX_DISSIPATION_PERCENT = 40; // 硬通量耗散率

	// 免疫开关
	public static final boolean ENABLE_COLLISION_IMMUNITY = true; // 碰撞伤害免疫开关
	public static final boolean ENABLE_EXPLOSION_IMMUNITY = true; // 殉爆伤害免疫开关

	// 船插ID，用于监听器检查
	private static final String HULLMOD_ID = "Moci_PhaseShiftArmor";

	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		stats.getSensorProfile().modifyMult(id, SENSOR_PROFILE_MULT);
		stats.getHardFluxDissipationFraction().modifyFlat(id, (float)HARD_FLUX_DISSIPATION_PERCENT * 0.01f);
		stats.getDynamic().getMod("individual_ship_recovery_mod").modifyFlat(id, 1000.0F); //打捞恢复概率
	}

	@Override
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		if (!ship.hasListenerOfClass(CollisionDamageListener.class)) {
			ship.addListener(new CollisionDamageListener());
		}
	}

	@Override
	public void advanceInCombat(ShipAPI ship, float amount) {
		if (ship == null) return;
		
		hideWeaponCovers(ship);
		hideMissileSprites(ship);
		// 同步武器贴图与装甲损伤
		ship.syncWeaponDecalsWithArmorDamage();
	}

	/**
	 * 碰撞和殉爆伤害监听器
	 */
	public static class CollisionDamageListener implements DamageTakenModifier {
		@Override
		public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point,
				boolean shieldHit) {

			// 处理殉爆伤害
			if (ENABLE_EXPLOSION_IMMUNITY && param instanceof DamagingProjectileAPI) {
				DamagingProjectileAPI projectile = (DamagingProjectileAPI) param;
				ShipAPI source = projectile.getSource();
				if (source != null) {
					float explosionRadius = DamagingExplosionSpec.getShipExplosionRadius(source);
					if ((projectile.getCollisionRadius() - explosionRadius) == 0) {
						// 殉爆伤害减免
						damage.getModifier().modifyMult(this.getClass().getName() + "_explosion", 0f);
						return Moci_TextLoader.getText(TEXT_ID, "combat.explosion_immunity");
					}
				}
			}

			// 处理碰撞伤害 - 动能伤害且param为null才是碰撞伤害
			if (ENABLE_COLLISION_IMMUNITY && param == null && damage.getType() == DamageType.KINETIC) {
				// 碰撞伤害减免
				damage.getModifier().modifyMult(this.getClass().getName() + "_collision", 0f);
				return Moci_TextLoader.getText(TEXT_ID, "combat.collision_immunity");
			}

			return null;
		}
	}

	private void hideWeaponCovers(ShipAPI ship) {
		if (ship.getLargeHardpointCover() != null) {
			ship.getLargeHardpointCover().setSize(0.0f, 0.0f);
		}
		if (ship.getMediumHardpointCover() != null) {
			ship.getMediumHardpointCover().setSize(0.0f, 0.0f);
		}
		if (ship.getSmallHardpointCover() != null) {
			ship.getSmallHardpointCover().setSize(0.0f, 0.0f);
		}
		if (ship.getLargeTurretCover() != null) {
			ship.getLargeTurretCover().setSize(0.0f, 0.0f);
		}
		if (ship.getMediumTurretCover() != null) {
			ship.getMediumTurretCover().setSize(0.0f, 0.0f);
		}
		if (ship.getSmallTurretCover() != null) {
			ship.getSmallTurretCover().setSize(0.0f, 0.0f);
		}
		
		for (WeaponAPI weapon : ship.getAllWeapons()) {
			if (weapon != null && (weapon.getSlot().isTurret() || weapon.getSlot().isHardpoint())) {
				weapon.ensureClonedSpec();
			}
		}
	}

	private void hideMissileSprites(ShipAPI ship) {
		for (WeaponAPI weapon : ship.getAllWeapons()) {
			if (weapon != null && weapon.getMissileRenderData() != null) {
				for (MissileRenderDataAPI render : weapon.getMissileRenderData()) {
					if (render != null && render.getSprite() != null) {
						render.getSprite().setWidth(0);
						render.getSprite().setHeight(0);
					}
				}
			}
		}
	}

	/**
	 * 是否应该添加描述到工具提示
	 */
	@Override
	public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
		return false;
	}

	/**
	 * 添加描述部分
	 */
	@Override
	public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width,
			boolean isForModSpec) {
		float pad = 3f;
		Color h = Misc.getHighlightColor();
		Color good = Misc.getPositiveHighlightColor();

		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.intro"), pad);

		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.sensor_profile",
						Moci_TextLoader.mapOf("sensor_profile", String.valueOf(Math.round((1f - SENSOR_PROFILE_MULT) * 100f)))),
				pad, h,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.sensor_profile_highlights",
						Moci_TextLoader.mapOf("sensor_profile", String.valueOf(Math.round((1f - SENSOR_PROFILE_MULT) * 100f)))).toArray(new String[0]));
		tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.hard_flux",
						Moci_TextLoader.mapOf("hard_flux", String.valueOf(HARD_FLUX_DISSIPATION_PERCENT))),
				pad, h,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.hard_flux_highlights",
						Moci_TextLoader.mapOf("hard_flux", String.valueOf(HARD_FLUX_DISSIPATION_PERCENT))).toArray(new String[0]));
		
		if (ENABLE_COLLISION_IMMUNITY && ENABLE_EXPLOSION_IMMUNITY) {
			tooltip.addPara(
					Moci_TextLoader.getText(TEXT_ID, "description.immunity_both"),
					pad, good,
					Moci_TextLoader.getHighlights(TEXT_ID, "description.immunity_both_highlights").toArray(new String[0]));
		} else if (ENABLE_COLLISION_IMMUNITY) {
			tooltip.addPara(
					Moci_TextLoader.getText(TEXT_ID, "description.immunity_collision"),
					pad, good,
					Moci_TextLoader.getHighlights(TEXT_ID, "description.immunity_collision_highlights").toArray(new String[0]));
		} else if (ENABLE_EXPLOSION_IMMUNITY) {
			tooltip.addPara(
					Moci_TextLoader.getText(TEXT_ID, "description.immunity_explosion"),
					pad, good,
					Moci_TextLoader.getHighlights(TEXT_ID, "description.immunity_explosion_highlights").toArray(new String[0]));
		}

	}

	/**
	 * 获取S模式描述参数
	 * 
	 * @param index    参数索引
	 * @param hullSize 船体尺寸
	 * @return 格式化后的参数值
	 */
	@Override
	public String getSModDescriptionParam(int index, HullSize hullSize) {
		return null;
	}

	/**
	 * 获取普通模式描述参数
	 * 
	 * @param index    参数索引
	 * @param hullSize 船体尺寸
	 * @return 格式化后的参数值
	 */
	@Override
	public String getDescriptionParam(int index, HullSize hullSize) {
		if (index == 0) {
			// 传感器信号降低百分比
			return Math.round((1f - SENSOR_PROFILE_MULT) * 100f) + "%";
		}
		return null;
	}
}
