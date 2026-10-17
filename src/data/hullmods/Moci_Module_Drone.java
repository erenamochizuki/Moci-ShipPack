package data.hullmods;

import java.util.HashSet;
import java.util.Set;

import com.fs.starfarer.api.impl.campaign.ids.Stats;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import data.scripts.util.Moci_TextLoader;


public class Moci_Module_Drone extends BaseLogisticsHullMod {
	private static final String TEXT_ID = "Moci_Module_Drone";
	
	// 禁用船插列表
	private static final Set<String> DISABLE_ALL_HULLMODS = new HashSet<>();
	static {
		DISABLE_ALL_HULLMODS.add("strikeCraft");
		DISABLE_ALL_HULLMODS.add("armaa_strikeCraftFrig");
	}

	// 数据键定义
	private static final String COLLISION_APPLIED_KEY = "moci_simple_collision_applied";

    public static float PROFILE_MULT = 0.1f; // 传感器信号乘数
    public static final float DAMAGE_MULT = 0f; // 无殉爆伤害

    // 数据键定义
    private static final String FRIGATE_RULES_KEY = "moci_module_frigate_rules_active";

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 模块脱离几率增加100%
        stats.getDynamic().getStat(Stats.MODULE_DETACH_CHANCE_MULT).modifyFlat(id, 100f);
        // 殉爆伤害减免
        stats.getDynamic().getStat(Stats.EXPLOSION_DAMAGE_MULT).modifyMult(id, DAMAGE_MULT);
        // EMP伤害免疫
        stats.getEmpDamageTakenMult().modifyMult(id, 0f);
        // 传感器信号降低
        stats.getSensorProfile().modifyMult(id, PROFILE_MULT);
    }
	/**
	 * 检查是否应该禁用所有功能
	 */
	private boolean shouldDisableAllEffects(MutableShipStatsAPI stats) {
		for (String hullMod : DISABLE_ALL_HULLMODS) {
			if (stats.getVariant().hasHullMod(hullMod)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		// 检查是否应该禁用所有功能
		if (shouldDisableAllEffects(ship.getMutableStats())) {
			return;
		}

		// 添加伤害监听器
		if (!ship.hasListenerOfClass(SimpleDamageListener.class)) {
			ship.addListener(new SimpleDamageListener());
		}
	}

	@Override
	public void advanceInCombat(ShipAPI ship, float amount) {
		// 检查是否应该禁用所有功能
		if (shouldDisableAllEffects(ship.getMutableStats())) {
			return;
		}

		// 一次性设置碰撞类型为战机（在战斗开始后）
		if (ship.getCustomData().get(COLLISION_APPLIED_KEY) == null) {
			// 检查是否安装了模块碰撞同步船插
			if (ship.getVariant().hasHullMod("Moci_ModuleCollisionSync")) {
				// 已安装模块碰撞同步，让其处理碰撞类型设置
				ship.setCustomData(COLLISION_APPLIED_KEY, true);
			} else {
				// 未安装模块碰撞同步，直接设置主舰船碰撞类型
				if (ship.getCollisionClass() != CollisionClass.NONE) {
					ship.setCollisionClass(CollisionClass.FIGHTER);
				}

				// 手动处理模块碰撞类型
				if (ship.getChildModulesCopy() != null) {
					for (ShipAPI module : ship.getChildModulesCopy()) {
						if (module != null && module.getStationSlot() != null) {
							if (module.getCollisionClass() != CollisionClass.NONE) {
								module.setCollisionClass(CollisionClass.FIGHTER);
							}
						}
					}
				}

				ship.setCustomData(COLLISION_APPLIED_KEY, true);
			}
		}
	}

	/**
	 * 简化版伤害监听器
	 * 只处理碰撞伤害和殉爆伤害减免
	 */
	public static class SimpleDamageListener implements DamageTakenModifier {
		@Override
		public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point,
				boolean shieldHit) {
			// 如果目标不是舰船，直接返回
			if (!(target instanceof ShipAPI)) {
				return null;
			}

			ShipAPI targetShip = (ShipAPI) target;

			// 检查是否应该禁用所有功能
			boolean shouldDisable = false;
			for (String hullMod : DISABLE_ALL_HULLMODS) {
				if (targetShip.getVariant().hasHullMod(hullMod)) {
					shouldDisable = true;
					break;
				}
			}
			if (shouldDisable) {
				return null;
			}

			// 处理殉爆伤害
			if (param instanceof DamagingProjectileAPI) {
				DamagingProjectileAPI projectile = (DamagingProjectileAPI) param;
				ShipAPI source = projectile.getSource();
				if (source != null) {
					float explosionRadius = DamagingExplosionSpec.getShipExplosionRadius(source);
					if ((projectile.getCollisionRadius() - explosionRadius) == 0) {
						// 殉爆伤害减免
						damage.getModifier().modifyMult(this.getClass().getName(), 0f);
						return Moci_TextLoader.getText(TEXT_ID, "combat.explosion_immunity");
					}
				}
			}

			// 处理碰撞伤害 - 动能伤害且param为null才是碰撞伤害
			if (param == null && damage.getType() == DamageType.KINETIC) {
				// 碰撞伤害减免
				damage.getModifier().modifyMult(this.getClass().getName(), 0f);
				return Moci_TextLoader.getText(TEXT_ID, "combat.collision_immunity");
			}

			return null;
		}
	}

	@Override
	public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
		return false;
	}
}
