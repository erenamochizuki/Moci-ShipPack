package data.hullmods;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicIncompatibleHullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import data.scripts.Moci_CollisionStateManager;
import data.scripts.weapons.render.Moci_Kratogen_EngineSimulator;
import data.scripts.util.Moci_TextLoader;

import java.util.Arrays;

public class Moci_MobileSuitsIDcard extends BaseLogisticsHullMod {
	public static float PROFILE_MULT = 0.1f; // 传感器信号乘数
	public static float HEALTH_BONUS = 200f; // 引擎健康加成
	public static float REPAIR_BONUS = 50f; // 维修效率加成
	public static float OVERLOAD_BONUS = 50f; // 过载时间增加
	
	// 碰撞状态管理
	private static final String COLLISION_MODIFIER_ID = "Moci_MobileSuitsIDcard";
	private static final int COLLISION_PRIORITY = 100; // 机动兵器ID卡的碰撞优先级（低于精神感应系统的200）
	private Moci_CollisionStateManager collisionManager = Moci_CollisionStateManager.getInstance();

	// 护卫舰特殊规则相关常量
	public static float FRIGATE_CR_DECAY_MULT = 1.5f; // 战备衰减速率乘数
	public static float FRIGATE_PD_DAMAGE_MULT = 1.5f; // 点防伤害乘数
	public static float FRIGATE_NO_OFFICER_CR_PENALTY = 0.15f; // 无军官CR惩罚

	// 对导弹和战机的增伤
	public static float ANTI_MISSILE_FIGHTER_DAMAGE_MULT = 1.5f; // 对导弹/战机50%增伤

	// 免疫开关
	public static boolean ENABLE_COLLISION_IMMUNITY = true; // 碰撞伤害免疫开关
	public static boolean ENABLE_EXPLOSION_IMMUNITY = true; // 殉爆伤害免疫开关
	public static boolean ENABLE_COLLISION_DEBUG_LOG = false; // 记录异常碰撞诊断日志

	// AI行为控制开关
	public static boolean ENABLE_AGGRESSIVE_AI = true; // 是否启用激进AI行为（不后退）
	public static boolean ENABLE_MOBILE_SUIT_AI_CONFIG = true; // 是否启用默认AI参数覆写
	// 是否让AI更积极地通过横移维持火力，而不是优先拉开距离。
	public static boolean MOBILE_SUIT_ALWAYS_STRAFE_OFFENSIVELY = false;
	// 是否允许AI在非散幅状态下主动后撤；设为true时会更像默认AI。
	public static boolean MOBILE_SUIT_BACKING_OFF_WHILE_NOT_VENTING_ALLOWED = false;
	// 是否优先把未受损装甲面朝向敌人，适合装甲分布差异较大的船体。
	public static boolean MOBILE_SUIT_TURN_TO_FACE_WITH_UNDAMAGED_ARMOR = false;
	// 冲锋类系统启动时是否更倾向忽略沿途威胁，直接执行突进。
	public static boolean MOBILE_SUIT_BURN_DRIVE_IGNORE_ENEMIES = true;

	// 禁用护卫舰特殊规则的船插列表（可扩展）
	private static final Set<String> FRIGATE_RULE_DISABLE_HULLMODS = new HashSet<>();
	static {
		FRIGATE_RULE_DISABLE_HULLMODS.add("Moci_EinstWeapon");
		FRIGATE_RULE_DISABLE_HULLMODS.add("Moci_SuperAlloyArmor");
		// 可以在这里添加更多船插ID
	}

	// 只应用碰撞规则但不应用数值加成的船插列表
	private static final Set<String> COLLISION_ONLY_HULLMODS = new HashSet<>();
	static {
		// COLLISION_ONLY_HULLMODS.add("Moci_PsycommuSystem");
		// 可以在这里添加更多船插ID
	}

	// 完全禁用本船插功能的船插列表
	private static final Set<String> DISABLE_ALL_HULLMODS = new HashSet<>();
	static {
		DISABLE_ALL_HULLMODS.add("strikeCraft");
		DISABLE_ALL_HULLMODS.add("armaa_strikeCraftFrig");
	}

	// 数据键定义
	private static final String ENGINE_COLOR_KEY = "moci_ms_idcard_engine_color";
	public static final String ENGINE_RENDER_SUPPRESSED_KEY = "moci_ms_idcard_engine_render_suppressed";
	private static final String VARIABLE_FRAME_HULLMOD_ID = "Moci_VariableFrame";
	private static final String FRIGATE_RULES_KEY = "moci_frigate_rules_active";
	private static final String COLLISION_ONLY_KEY = "moci_collision_only_active";
	private static final String COLLISION_APPLIED_KEY = "moci_collision_applied";
	public static float ENGINE_LENGTH_MULT = 3f; // 尾焰长度乘数
	public static float ENGINE_WIDTH_MULT = 2.5f; // 尾焰宽度乘数
	public static float GLOW_SIZE_MULT = 1.2f; // 光亮尺寸乘数
	private static final Set<String> UNAPPLICABLE = new HashSet<>();
	static {
		UNAPPLICABLE.add("converted_hangar");
		UNAPPLICABLE.add("pointdefenseai");
		UNAPPLICABLE.add("surveying_equipment");
	}

	private static final Map<HullSize, Float> SPEED_BONUSES = new HashMap<HullSize, Float>();
	static {
		SPEED_BONUSES.put(HullSize.FRIGATE, 50f);
		SPEED_BONUSES.put(HullSize.DESTROYER, 30f);
		SPEED_BONUSES.put(HullSize.CRUISER, 20f);
		SPEED_BONUSES.put(HullSize.CAPITAL_SHIP, 10f);
	}

	/**
	 * 检查变体上是否安装了会禁用护卫舰特殊规则的“高级船插”。
	 *
	 * 这里复用 Moci_MobileSuitsIDcard 对轻型机动兵器的既有定义，
	 * 供其他需要与该规则保持一致的功能直接调用。
	 */
	public static boolean hasAdvancedFrigateHullmod(ShipVariantAPI variant) {
		if (variant == null) {
			return false;
		}
		for (String hullMod : FRIGATE_RULE_DISABLE_HULLMODS) {
			if (variant.hasHullMod(hullMod)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isEngineRenderSuppressed(ShipAPI ship) {
		return ship != null && Boolean.TRUE.equals(ship.getCustomData().get(ENGINE_RENDER_SUPPRESSED_KEY));
	}

	private HullSize getOriginalHullSize(MutableShipStatsAPI stats) {
		if (stats == null || stats.getVariant() == null) {
			return null;
		}
		return stats.getVariant().getHullSize();
	}

	/**
	 * 检查是否应该禁用所有功能
	 */
	private boolean shouldDisableAllEffects(MutableShipStatsAPI stats) {
		if (getOriginalHullSize(stats) == HullSize.FIGHTER) {
			return true;
		}
		if (stats == null || stats.getVariant() == null) {
			return false;
		}
		for (String hullMod : DISABLE_ALL_HULLMODS) {
			if (stats.getVariant().hasHullMod(hullMod)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 检查是否应该应用护卫舰特殊规则
	 */
	private boolean shouldApplyFrigateRules(MutableShipStatsAPI stats) {
		// 检查船体尺寸是否为护卫舰
		if (getOriginalHullSize(stats) != HullSize.FRIGATE) {
			return false;
		}
		if (stats == null || stats.getVariant() == null) {
			return false;
		}

		// 检查是否安装了禁用特殊规则的船插
		if (hasAdvancedFrigateHullmod(stats.getVariant())) {
			return false;
		}

		// 检查是否安装了只应用碰撞规则的船插
		for (String hullMod : COLLISION_ONLY_HULLMODS) {
			if (stats.getVariant().hasHullMod(hullMod)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * 检查是否应该只应用碰撞规则（不应用数值加成）
	 */
	private boolean shouldApplyCollisionOnly(MutableShipStatsAPI stats) {
		// 检查船体尺寸是否为护卫舰
		if (getOriginalHullSize(stats) != HullSize.FRIGATE) {
			return false;
		}
		if (stats == null || stats.getVariant() == null) {
			return false;
		}

		// 检查是否安装了只应用碰撞规则的船插
		for (String hullMod : COLLISION_ONLY_HULLMODS) {
			if (stats.getVariant().hasHullMod(hullMod)) {
				return true;
			}
		}

		return false;
	}

	// 安全协议超驰部署点惩罚
	public static float SAFETY_OVERRIDES_DP_PENALTY = 10f; // 部署点惩罚

	/**
	 * 在飞船创建前应用效果
	 * 
	 * @param hullSize 船体尺寸
	 * @param stats    可变的飞船状态
	 * @param id       插件ID
	 */
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// 检查是否应该禁用所有功能
		if (shouldDisableAllEffects(stats)) {
			return;
		}

		// 对战机/导弹的增伤属于进攻属性，不能在承伤监听器中修改被击中的机体。
		stats.getDamageToFighters().modifyMult(id, ANTI_MISSILE_FIGHTER_DAMAGE_MULT);
		stats.getDamageToMissiles().modifyMult(id, ANTI_MISSILE_FIGHTER_DAMAGE_MULT);

		stats.getEngineHealthBonus().modifyPercent(id, HEALTH_BONUS);

		stats.getSensorProfile().modifyMult(id, PROFILE_MULT);

		stats.getCombatEngineRepairTimeMult().modifyMult(id, 1f - REPAIR_BONUS * 0.01f);
		stats.getCombatWeaponRepairTimeMult().modifyMult(id, 1f - REPAIR_BONUS * 0.01f);

		stats.getOverloadTimeMod().modifyMult(id, 1f + OVERLOAD_BONUS * 0.01f);

		// 检查是否安装了安全协议超驰，如果安装了则增加部署点惩罚
		if (stats.getVariant().hasHullMod("safetyoverrides")) {
			stats.getDynamic().getMod(Stats.DEPLOYMENT_POINTS_MOD)
				.modifyFlat(id + "_safetyoverrides", SAFETY_OVERRIDES_DP_PENALTY);
		}

		// 检查并应用护卫舰特殊规则
		boolean applyFrigateRules = shouldApplyFrigateRules(stats);

		if (applyFrigateRules) {
			// 战备衰减速率提高50%
			stats.getCRLossPerSecondPercent().modifyMult(id + "_frigate", FRIGATE_CR_DECAY_MULT);

			// 检查军官状态，直接应用CR惩罚
			if (stats.getEntity() instanceof ShipAPI) {
				ShipAPI ship = (ShipAPI) stats.getEntity();
				if (ship.getCaptain() == null || ship.getCaptain().isDefault()) {
					stats.getMaxCombatReadiness().modifyFlat(id + "_no_officer", -FRIGATE_NO_OFFICER_CR_PENALTY);
				}
			} else if (stats.getFleetMember() != null) {
				// 战役模式下检查舰队成员的军官
				if (stats.getFleetMember().getCaptain() == null || stats.getFleetMember().getCaptain().isDefault()) {
					stats.getMaxCombatReadiness().modifyFlat(id + "_no_officer", -FRIGATE_NO_OFFICER_CR_PENALTY);
				}
			}
		}

		for (String hullmMod : UNAPPLICABLE) {
			if (stats.getVariant().hasHullMod(hullmMod)) {
				MagicIncompatibleHullmods.removeHullmodWithWarning(stats.getVariant(), hullmMod, spec.getId());
			}
		}
	}

	@Override
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		// 检查是否应该禁用所有功能
		if (shouldDisableAllEffects(ship.getMutableStats())) {
			return;
		}

		// 初始化碰撞状态管理器
		collisionManager.initializeDefaultCollision(ship);

		// 检查是否应该应用护卫舰特殊规则
		boolean shouldApplyRules = shouldApplyFrigateRules(ship.getMutableStats());
		// 检查是否应该只应用碰撞规则
		boolean shouldApplyCollisionOnly = shouldApplyCollisionOnly(ship.getMutableStats());

		if (shouldApplyRules) {
			// 标记需要应用护卫舰规则，模块碰撞类型处理移到advanceInCombat中
			ship.setCustomData(FRIGATE_RULES_KEY, true);
			// 这类机动兵器的常态碰撞应当是 FIGHTER。
			// 否则起飞系统的临时碰撞修改结束后，会被碰撞管理器恢复成 SHIP。
			collisionManager.setDefaultCollision(ship, CollisionClass.FIGHTER);
		} else if (shouldApplyCollisionOnly) {
			// 标记只需要应用碰撞规则
			ship.setCustomData(COLLISION_ONLY_KEY, true);
			collisionManager.setDefaultCollision(ship, CollisionClass.FIGHTER);
		}

		if (!ship.hasListenerOfClass(ExplosionDamageListener.class)) {
			ship.addListener(new ExplosionDamageListener());
		}
	}

	@Override
	public void advanceInCombat(ShipAPI ship, float amount) {
		if (ship == null) {
			return;
		}
		if (!ship.isAlive() || ship.isHulk() || ship.getHitpoints() <= 0f) {
			killChildModules(ship);
			return;
		}

		// 检查是否应该禁用所有功能
		if (shouldDisableAllEffects(ship.getMutableStats())) {
			return;
		}

		// 将旧的 LandingSequence 机制并入机动兵器本体能力。
		// 为了兼容现有 variant，如果仍然挂着独立的 LandingSequence 船插，
		// 就继续让旧船插负责，避免同一帧重复跑两次逻辑。
		if (ship.getVariant() == null || !ship.getVariant().hasHullMod("Moci_LandingSequence")) {
			Moci_LandingSequence.advanceLandingLogic(ship, amount);
		}

		if (ship.getEngineController() == null)
			return;

		// 参考 CombatSquad 的默认AI配置逻辑，补充机动兵器的基础AI行为参数。
		// 这里只迁移 ShipAIConfig 这一层的通用参数，不迁移僚机专属的护航/撤退保底逻辑，
		// 避免普通机动兵器也被当成编队僚机去分配任务。
		applyMobileSuitAIConfig(ship);

		// 额外保留原本的激进行为：状态良好的机动兵器会更倾向于压上，不主动后退。
		// 但只要已经进入整备需求态，就必须给返航/整备链让路。
		if (ENABLE_AGGRESSIVE_AI && !isRefitPending(ship) && ship.getHullLevel() > 0.3f
				&& !ship.getFluxTracker().isOverloadedOrVenting()) {
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine != null && !ship.isHulk() && engine.isEntityInPlay(ship)) {
				ShipwideAIFlags flags = ship.getAIFlags();
				if (flags != null) {
					flags.setFlag(AIFlags.DO_NOT_BACK_OFF, 0.5f);
				}
			}
		} else {
			ShipwideAIFlags flags = ship.getAIFlags();
			if (flags != null) {
				flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
			}
		}

		// 持续维护护卫舰碰撞类型（支持相位穿越后恢复）
		if (ship.getCustomData().get(FRIGATE_RULES_KEY) != null
				|| ship.getCustomData().get(COLLISION_ONLY_KEY) != null) {
			
			// 检查主舰船的碰撞类型，只有非NONE时才修改
			CollisionClass defaultCollision = collisionManager.getDefaultCollision(ship);
			if (defaultCollision != null && defaultCollision != CollisionClass.NONE) {
				// 检查当前碰撞类型是否正确
				if (ship.getCollisionClass() != CollisionClass.FIGHTER) {
					// 使用碰撞管理器设置碰撞类型为战机
					collisionManager.setCollisionModifier(ship, COLLISION_MODIFIER_ID, COLLISION_PRIORITY, CollisionClass.FIGHTER);
				}
			}
		}

		// 从母舰侧同步一次模块碰撞，避免模块同步器先于本体碰撞更新执行时留下 1 帧的 SHIP 窗口。
		synchronizeModuleCollision(ship);
		
		// 同步武器贴图与装甲损伤
		ship.syncWeaponDecalsWithArmorDamage();

		// 隐藏武器覆盖物贴图
		hideWeaponCovers(ship);

		// 检查是否处于零幅能加速状态且没有安装安全协议
		if (ship.getFluxTracker().isEngineBoostActive() && !ship.getVariant().hasHullMod("safetyoverrides")) {
			// 编队可将护卫舰临时标为 FIGHTER；加成始终按原始舰体尺寸计算。
			ship.getMutableStats().getMaxSpeed().modifyFlat("Moci_MobileSuitsIDcard",
					SPEED_BONUSES.getOrDefault(getOriginalHullSize(ship.getMutableStats()), 0f));
		} else {
			// 移除航速加成
			ship.getMutableStats().getMaxSpeed().unmodify("Moci_MobileSuitsIDcard");
		}

		// 为每个引擎创建对应的渲染器。可变式框架需要按形态切换推进器位置，
		// 因此由 Moci_VariableFrame 接管引擎渲染。
		if (!hasVariableFrameHullmod(ship)
				&& !isEngineRenderSuppressed(ship)
				&& ship.getCustomData().get("Moci_EngineRenderersCreated") == null) {
			for (ShipEngineAPI engine : ship.getEngineController().getShipEngines()) {
				SpriteAPI glowSprite = Global.getSettings().getSprite("fx", "MBSlowSprite");
				SpriteAPI flameSprite = Global.getSettings().getSprite("fx", "MBSglowOutline");
				SpriteAPI cricleSprite = Global.getSettings().getSprite("fx", "MBSEengineGlowSmoke");

				Moci_Kratogen_EngineSimulator simulator = new Moci_Kratogen_EngineSimulator(
						ship,
						engine,
						null,
						glowSprite,
						flameSprite,
						cricleSprite);

				// 获取或创建舰船专用的引擎颜色
				Color engineColor = (Color) ship.getCustomData().get(ENGINE_COLOR_KEY);
				if (engineColor == null) {
					engineColor = new Color(43, 149, 255, 210); // 默认引擎颜色
					ship.setCustomData(ENGINE_COLOR_KEY, engineColor);
				}

				// 获取原始引擎颜色，如果有的话
				Color engineColorToUse = engineColor; // 默认使用预设颜色
				if (engine.getEngineColor() != null) {
					// 使用原始引擎颜色，但保留我们自定义的透明度
					engineColorToUse = new Color(
							engine.getEngineColor().getRed(),
							engine.getEngineColor().getGreen(),
							engine.getEngineColor().getBlue(),
							engineColor.getAlpha());
				}

				// 设置引擎颜色
				simulator.setColorOverride(engineColorToUse);
				// 设置尾焰尺寸参数
				simulator.setLengthMult(ENGINE_LENGTH_MULT);
				simulator.setWidthMult(ENGINE_WIDTH_MULT);
				simulator.setGlowSizeMult(GLOW_SIZE_MULT);

				Global.getCombatEngine().addLayeredRenderingPlugin(simulator);
			}
			ship.setCustomData("Moci_EngineRenderersCreated", true);
		}
	}

	private boolean hasVariableFrameHullmod(ShipAPI ship) {
		return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(VARIABLE_FRAME_HULLMOD_ID);
	}

	/**
	 * 隐藏武器覆盖物贴图
	 * 
	 * @param ship 飞船实例
	 */
	private void hideWeaponCovers(ShipAPI ship) {
		try {
			// 隐藏所有尺寸的硬点覆盖物
			if (ship.getLargeHardpointCover() != null) {
				ship.getLargeHardpointCover().setSize(0.0f, 0.0f);
			}

			if (ship.getMediumHardpointCover() != null) {
				ship.getMediumHardpointCover().setSize(0.0f, 0.0f);
			}

			if (ship.getSmallHardpointCover() != null) {
				ship.getSmallHardpointCover().setSize(0.0f, 0.0f);
			}

			// 遍历所有武器，处理炮塔和硬点武器
			for (WeaponAPI weapon : ship.getAllWeapons()) {
				try {
					if (weapon == null || weapon.getSlot() == null) {
						continue;
					}
					
					if (weapon.getSlot().isTurret() || weapon.getSlot().isHardpoint()) {
						weapon.ensureClonedSpec();
					}
				} catch (Exception e) {
					// 捕获单个武器处理时的异常，避免影响其他武器
					// 静默处理，不影响游戏运行
				}
			}
		} catch (Exception e) {
			// 捕获整个方法的异常，避免崩溃
			Global.getLogger(Moci_MobileSuitsIDcard.class).error("隐藏武器覆盖物时发生错误: " + e.getMessage(), e);
		}
	}

	private void synchronizeModuleCollision(ShipAPI ship) {
		if (ship == null || ship.getChildModulesCopy() == null || ship.getChildModulesCopy().isEmpty()) {
			return;
		}

		CollisionClass parentCollision = ship.getCollisionClass();
		if (parentCollision == null) {
			return;
		}

		for (ShipAPI module : ship.getChildModulesCopy()) {
			if (module == null || !module.isAlive()) {
				continue;
			}
			if (!module.isStationModule()) {
				continue;
			}
			if (module.getVariant() == null || !module.getVariant().hasHullMod("Moci_module_collision_controller")) {
				continue;
			}
			if (module.getCollisionClass() != parentCollision) {
				module.setCollisionClass(parentCollision);
			}
		}
	}

	/**
	 * 将 CombatSquad 中验证过的默认AI参数覆写到机动兵器上。
	 *
	 * 这里作用的是“默认AI怎么思考”，适合做通用性格微调；
	 * 而护航任务、自动撤退之类的编队行为依赖额外上下文，不在这里处理。
	 */
	private void applyMobileSuitAIConfig(ShipAPI ship) {
		if (!ENABLE_MOBILE_SUIT_AI_CONFIG || ship == null || !ship.isAlive() || ship.isHulk()) {
			return;
		}
		// 没有默认ShipAI或配置对象时，说明当前舰船可能被玩家接管或使用了自定义AI，直接跳过。
		if (ship.getShipAI() == null || ship.getShipAI().getConfig() == null) {
			return;
		}

		ShipAIConfig aiConfig = ship.getShipAI().getConfig();
		if (isRefitPending(ship)) {
			aiConfig.alwaysStrafeOffensively = false;
			aiConfig.backingOffWhileNotVentingAllowed = true;
			aiConfig.turnToFaceWithUndamagedArmor = MOBILE_SUIT_TURN_TO_FACE_WITH_UNDAMAGED_ARMOR;
			aiConfig.burnDriveIgnoreEnemies = false;
			return;
		}
		aiConfig.alwaysStrafeOffensively = MOBILE_SUIT_ALWAYS_STRAFE_OFFENSIVELY;
		aiConfig.backingOffWhileNotVentingAllowed = MOBILE_SUIT_BACKING_OFF_WHILE_NOT_VENTING_ALLOWED;
		aiConfig.turnToFaceWithUndamagedArmor = MOBILE_SUIT_TURN_TO_FACE_WITH_UNDAMAGED_ARMOR;
		aiConfig.burnDriveIgnoreEnemies = MOBILE_SUIT_BURN_DRIVE_IGNORE_ENEMIES;
	}

	private boolean isRefitPending(ShipAPI ship) {
		if (ship == null || ship.getCustomData() == null) {
			return false;
		}
		if (ship.getCustomData().get("Moci_NeedsRefitReason") instanceof String) {
			return true;
		}
		if (ship.getCustomData().get("Moci_ReturnToCarrierModuleInstance") != null) {
			return true;
		}
		Object landingAIActive = ship.getCustomData().get("Moci_LandingAIActive");
		return landingAIActive instanceof Boolean && (Boolean) landingAIActive;
	}

	private void killChildModules(ShipAPI ship) {
		if (ship == null) {
			return;
		}
		for (ShipAPI module : ship.getChildModulesCopy()) {
			if (module == null || !module.isAlive()) {
				continue;
			}
			module.setHitpoints(0f);
		}
	}

	public static class ExplosionDamageListener implements DamageTakenModifier {
		@Override
		public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point,
				boolean shieldHit) {
			// 此监听器只处理机体承受的伤害；攻击增伤由舰船属性统一提供。
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

			// 检查是否是护卫舰且启用了特殊规则
			boolean hasFrigateRules = (targetShip.getCustomData().get(FRIGATE_RULES_KEY) != null);

			if (hasFrigateRules) {
				// 检查点防武器攻击
				if (param instanceof DamagingProjectileAPI) {
					DamagingProjectileAPI projectile = (DamagingProjectileAPI) param;
					WeaponAPI weapon = projectile.getWeapon();

					// 检查武器是否有PD提示或者发射源舰船的军官有点防专精
					boolean isPDAttack = false;

					// 检查武器是否有PD提示
					if (weapon != null && weapon.hasAIHint(WeaponAPI.AIHints.PD)) {
						isPDAttack = true;
					}

					// 检查发射源舰船的军官是否有点防专精
					if (projectile.getSource() != null && projectile.getSource().getCaptain() != null) {
						PersonAPI captain = projectile.getSource().getCaptain();
						if (captain.getStats().getSkillLevel("point_defense") > 0) {
							isPDAttack = true;
						}
					}

					// 如果是点防攻击，增加50%伤害
					if (isPDAttack) {
						damage.getModifier().modifyMult(this.getClass().getName() + "_pd", FRIGATE_PD_DAMAGE_MULT);
						return Moci_TextLoader.getText("Moci_MobileSuitsIDcard", "combat.frigate_pd_damage");
					}
				}

				// 检查是否有军官驾驶，如果没有则应用CR惩罚
				if (targetShip.getCaptain() == null || targetShip.getCaptain().isDefault()) {
					// 这里可以添加额外的无军官惩罚逻辑
					// 主要的CR惩罚已经在applyEffectsBeforeShipCreation中应用
				}
			}

			// 处理殉爆伤害
			if (ENABLE_EXPLOSION_IMMUNITY && param instanceof DamagingProjectileAPI) {
				DamagingProjectileAPI projectile = (DamagingProjectileAPI) param;

				// 检查是否是高爆伤害&伤害来源不是弹丸&源船已经死亡
				// if ((projectile.getDamageType() == DamageType.HIGH_EXPLOSIVE)
				// && (projectile.getProjectileSpecId() == null)
				// && !projectile.getSource().isAlive())
				// {
				ShipAPI source = projectile.getSource();
				if (source != null) {
					float explosionRadius = DamagingExplosionSpec.getShipExplosionRadius(source);
					if ((projectile.getCollisionRadius() - explosionRadius) == 0) {
						// 殉爆伤害减免
						damage.getModifier().modifyMult(this.getClass().getName() + "_explosion", 0f);
						return Moci_TextLoader.getText("Moci_MobileSuitsIDcard", "combat.explosion_immunity");
					}
				}
				// }
			}

			// 处理碰撞伤害 - 动能伤害且param为null才是碰撞伤害
			if (ENABLE_COLLISION_IMMUNITY && param == null && damage.getType() == DamageType.KINETIC) {
				// 碰撞伤害减免
				damage.getModifier().modifyMult(this.getClass().getName() + "_collision", 0f);
				return Moci_TextLoader.getText("Moci_MobileSuitsIDcard", "combat.collision_immunity");
			}

			return null;
		}

		private void logCollisionEvent(ShipAPI targetShip, DamageAPI damage, Vector2f point, boolean shieldHit) {
			if (!ENABLE_COLLISION_DEBUG_LOG || targetShip == null) {
				return;
			}

			Moci_CollisionStateManager manager = Moci_CollisionStateManager.getInstance();
			Moci_CollisionStateManager.CollisionModifier modifier = manager.getCurrentModifier(targetShip);
			Object launchTimer = targetShip.getCustomData().get(data.shipsystems.scripts.Moci_MSLaunchTravelDrive.LAUNCH_TIMER_KEY);
			Object landingActive = targetShip.getCustomData().get("Moci_LandingAIActive");

			String modifierInfo = modifier != null
					? modifier.modifierId + "/" + modifier.priority + "/" + modifier.collisionClass
					: "none";
			String commanderInfo = targetShip.getCustomData().get("Moci_WingmanCommander") != null ? "yes" : "no";
			Object launchMode = targetShip.getCustomData().get("Moci_CombatSquadLaunchMode");
			Object immediateLaunchUsed = targetShip.getCustomData().get("Moci_ImmediateLaunchUsed");

			Global.getLogger(Moci_MobileSuitsIDcard.class).info(
					"[MOCI_COLLISION_DEBUG] ship=" + targetShip.getName()
							+ ", id=" + targetShip.getId()
							+ ", hull=" + (targetShip.getHullSpec() != null ? targetShip.getHullSpec().getHullId() : "null")
							+ ", variant=" + (targetShip.getVariant() != null ? targetShip.getVariant().getHullVariantId() : "null")
							+ ", damage=" + damage.getDamage()
							+ ", point=" + point
							+ ", shieldHit=" + shieldHit
							+ ", collision=" + targetShip.getCollisionClass()
							+ ", defaultCollision=" + manager.getDefaultCollision(targetShip)
							+ ", currentModifier=" + modifierInfo
							+ ", hullSize=" + targetShip.getHullSize()
							+ ", isFighter=" + targetShip.isFighter()
							+ ", isLanding=" + targetShip.isLanding()
							+ ", isFinishedLanding=" + targetShip.isFinishedLanding()
							+ ", isPhased=" + targetShip.isPhased()
							+ ", travelDriveActive=" + (targetShip.getTravelDrive() != null && targetShip.getTravelDrive().isActive())
							+ ", launchTimer=" + launchTimer
							+ ", landingAIActive=" + landingActive
							+ ", wingmanCommander=" + commanderInfo
							+ ", launchMode=" + launchMode
							+ ", immediateLaunchUsed=" + immediateLaunchUsed
							+ ", velocity=" + targetShip.getVelocity());
		}
	}

	@Override
	public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
		return false;
	}

	@Override
	public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width,
			boolean isForModSpec) {
		float pad = 3f;
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color good = Misc.getPositiveHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();

		// 船插ID，用于从配置文件加载文本
		String hullmodId = "Moci_MobileSuitsIDcard";
		
		// 准备数值替换映射
		Map<String, String> replacements = new HashMap<>();
		replacements.put("damage", String.valueOf((int) ((ANTI_MISSILE_FIGHTER_DAMAGE_MULT - 1f) * 100f)));
		replacements.put("profile", String.valueOf(Math.round((1f - PROFILE_MULT) * 100f)));
		replacements.put("health", String.valueOf((int) HEALTH_BONUS));
		replacements.put("overload", String.valueOf((int) OVERLOAD_BONUS));
		replacements.put("repair", String.valueOf((int) REPAIR_BONUS));
		replacements.put("dp", String.valueOf((int) SAFETY_OVERRIDES_DP_PENALTY));
		replacements.put("cr_decay", String.valueOf((int) ((FRIGATE_CR_DECAY_MULT - 1f) * 100f)));
		replacements.put("pd_damage", String.valueOf((int) ((FRIGATE_PD_DAMAGE_MULT - 1f) * 100f)));
		replacements.put("no_officer_cr", String.valueOf((int) (FRIGATE_NO_OFFICER_CR_PENALTY * 100f)));

		// 主要描述
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.main", replacements),
			pad, h,
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.main_highlights", replacements).toArray(new String[0])
		);

		// 根据开关显示免疫效果
		if (ENABLE_COLLISION_IMMUNITY && ENABLE_EXPLOSION_IMMUNITY) {
			tooltip.addPara(
				Moci_TextLoader.getText(hullmodId, "description.immunity_both"),
				pad, good,
				Moci_TextLoader.getHighlights(hullmodId, "description.immunity_both_highlights").toArray(new String[0])
			);
		} else if (ENABLE_COLLISION_IMMUNITY) {
			tooltip.addPara(
				Moci_TextLoader.getText(hullmodId, "description.immunity_collision"),
				pad, good,
				Moci_TextLoader.getHighlights(hullmodId, "description.immunity_collision_highlights").toArray(new String[0])
			);
		} else if (ENABLE_EXPLOSION_IMMUNITY) {
			tooltip.addPara(
				Moci_TextLoader.getText(hullmodId, "description.immunity_explosion"),
				pad, good,
				Moci_TextLoader.getHighlights(hullmodId, "description.immunity_explosion_highlights").toArray(new String[0])
			);
		}

		// 对导弹和战机的增伤
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.anti_missile_fighter", replacements),
			pad, good,
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.anti_missile_fighter_highlights", replacements).toArray(new String[0])
		);

		// 检查是否安装了禁用船插，显示相应提示
		if (ship != null && shouldDisableAllEffects(ship.getMutableStats())) {
			tooltip.addPara(
				Moci_TextLoader.getText(hullmodId, "description.disabled_by_hullmod"),
				pad, bad,
				Moci_TextLoader.getHighlights(hullmodId, "description.disabled_by_hullmod_highlights").toArray(new String[0])
			);
			return; // 如果功能被禁用，不显示其他效果描述
		}

		// 信号半径
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.sensor_profile", replacements),
			pad, good,
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.sensor_profile_highlights", replacements).toArray(new String[0])
		);

		// 引擎和过载
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.engine_stats", replacements),
			pad, new Color[] { good, bad },
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.engine_stats_highlights", replacements).toArray(new String[0])
		);

		// 维修速度
		tooltip.addPara(
			Moci_TextLoader.getTextWithReplacements(hullmodId, "description.repair_speed", replacements),
			pad, good,
			Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.repair_speed_highlights", replacements).toArray(new String[0])
		);

		// 零幅能加速
		tooltip.addPara(
			Moci_TextLoader.getText(hullmodId, "description.zero_flux_boost"),
			pad, new Color[] { good, h },
			Moci_TextLoader.getHighlights(hullmodId, "description.zero_flux_boost_highlights").toArray(new String[0])
		);

		// 安全协议超驰惩罚说明
		if (ship != null && ship.getVariant().hasHullMod("safetyoverrides")) {
			tooltip.addPara(
				Moci_TextLoader.getTextWithReplacements(hullmodId, "description.safety_overrides_penalty", replacements),
				pad, new Color[] { bad, h, bad },
				Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.safety_overrides_penalty_highlights", replacements).toArray(new String[0])
			);
		}

		// 护卫舰特殊规则说明
		if (hullSize == HullSize.FRIGATE) {
			tooltip.addSectionHeading(
				Moci_TextLoader.getText(hullmodId, "description.frigate_rules_heading"),
				com.fs.starfarer.api.ui.Alignment.MID,
				pad
			);

			// 检查是否安装了禁用规则的船插
			boolean hasDisableHullmod = false;
			boolean hasCollisionOnlyHullmod = false;
			if (ship != null && ship.getVariant() != null) {
				for (String hullMod : FRIGATE_RULE_DISABLE_HULLMODS) {
					if (ship.getVariant().hasHullMod(hullMod)) {
						hasDisableHullmod = true;
						break;
					}
				}
				for (String hullMod : COLLISION_ONLY_HULLMODS) {
					if (ship.getVariant().hasHullMod(hullMod)) {
						hasCollisionOnlyHullmod = true;
						break;
					}
				}
			}

			if (hasDisableHullmod) {
				tooltip.addPara(
					Moci_TextLoader.getText(hullmodId, "description.frigate_disabled"),
					pad, new Color[] { h, h },
					Moci_TextLoader.getHighlights(hullmodId, "description.frigate_disabled_highlights").toArray(new String[0])
				);
			} else if (hasCollisionOnlyHullmod) {
				tooltip.addPara(
					Moci_TextLoader.getText(hullmodId, "description.frigate_collision_only"),
					pad, h,
					Moci_TextLoader.getHighlights(hullmodId, "description.frigate_collision_only_highlights").toArray(new String[0])
				);
				tooltip.addPara(
					Moci_TextLoader.getText(hullmodId, "description.frigate_collision_only_benefit1"),
					pad,
					new Color[] { Misc.getHighlightColor(), good },  // # 用黄色，其他用绿色
					Moci_TextLoader.getHighlights(hullmodId, "description.frigate_collision_only_benefit1_highlights").toArray(new String[0])
				);
				tooltip.addPara(
					Moci_TextLoader.getText(hullmodId, "description.frigate_collision_only_benefit2"),
					pad,
					new Color[] { Misc.getHighlightColor(), good },  // # 用黄色，其他用绿色
					Moci_TextLoader.getHighlights(hullmodId, "description.frigate_collision_only_benefit2_highlights").toArray(new String[0])
				);
			} else {
				tooltip.addPara(
					Moci_TextLoader.getText(hullmodId, "description.frigate_rules_intro"),
					pad, h,
					Moci_TextLoader.getHighlights(hullmodId, "description.frigate_rules_intro_highlights").toArray(new String[0])
				);

				tooltip.addPara(
					Moci_TextLoader.getText(hullmodId, "description.frigate_rule1"),
					pad,
					new Color[] { Misc.getHighlightColor(), good },  // # 用黄色，其他用绿色
					Moci_TextLoader.getHighlights(hullmodId, "description.frigate_rule1_highlights").toArray(new String[0])
				);
				tooltip.addPara(
					Moci_TextLoader.getTextWithReplacements(hullmodId, "description.frigate_rule2", replacements),
					pad,
					new Color[] { Misc.getHighlightColor(), bad },  // # 用黄色，数值用红色
					Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.frigate_rule2_highlights", replacements).toArray(new String[0])
				);
				tooltip.addPara(
					Moci_TextLoader.getTextWithReplacements(hullmodId, "description.frigate_rule3", replacements),
					pad,
					new Color[] { Misc.getHighlightColor(), bad },  // # 用黄色，数值用红色
					Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.frigate_rule3_highlights", replacements).toArray(new String[0])
				);
				tooltip.addPara(
					Moci_TextLoader.getTextWithReplacements(hullmodId, "description.frigate_rule4", replacements),
					pad,
					new Color[] { Misc.getHighlightColor(), bad },  // # 用黄色，数值用红色
					Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.frigate_rule4_highlights", replacements).toArray(new String[0])
				);
				tooltip.addPara(
					Moci_TextLoader.getTextWithReplacements(hullmodId, "description.frigate_rule5", replacements),
					pad,
					new Color[] { Misc.getHighlightColor(), bad },  // # 用黄色，数值用红色
					Moci_TextLoader.getHighlightsWithReplacements(hullmodId, "description.frigate_rule5_highlights", replacements).toArray(new String[0])
				);
			}
		}
	}

}
