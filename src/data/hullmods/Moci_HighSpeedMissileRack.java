package data.hullmods;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.magiclib.util.MagicIncompatibleHullmods;
import data.scripts.util.Moci_TextLoader;

public class Moci_HighSpeedMissileRack extends BaseHullMod {
    private static final String TEXT_ID = "Moci_highspeed_missilerack";
    public static final String HULLMOD_ID = "Moci_vector_industrial_design";

	// 定义不同尺寸导弹武器产生的幅能百分比常量
	private static final float FLUX_PERCENT_SMALL = 0.025f;  // 小型武器幅能百分比
	private static final float FLUX_PERCENT_MEDIUM = 0.05f; // 中型武器幅能百分比
	private static final float FLUX_PERCENT_LARGE = 0.10f;  // 大型武器幅能百分比
	private static final float AMMO_REDUCTION = 0.5f;       // 弹药减少比例

	/**
	 * 在舰船创建前应用效果 - 减少导弹备弹量，检查不兼容性
	 * @param hullSize 舰船尺寸
	 * @param stats 可变的舰船状态API
	 * @param id 效果ID
	 */
	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		if (stats == null || stats.getVariant() == null) {
			return;
		}
		// 检查并移除不兼容的船插
		if (stats.getVariant().hasHullMod("missile_autoloader")) {
			MagicIncompatibleHullmods.removeHullmodWithWarning(stats.getVariant(), "missile_autoloader", spec.getId());
		}
		
		// 修改导弹备弹量为原来的50%
		stats.getMissileAmmoBonus().modifyMult(id, AMMO_REDUCTION);
	}

	/**
	 * 在舰船创建后应用效果 - 添加监听器脚本
	 * @param ship 舰船实例
	 * @param id 效果ID
	 */
	@Override
	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
		// 如果舰船还没有添加过此监听器，则添加一个新的
		if (!ship.hasListenerOfClass(HighSpeedMissileRackScript.class)) {
			ship.addListener(new HighSpeedMissileRackScript(ship, id));
		}
	}

	/**
	 * 高速导弹挂架脚本类，实现AdvanceableListener接口
	 * 用于在每帧更新时检测导弹发射并应用效果
	 */
	public static class HighSpeedMissileRackScript implements AdvanceableListener {
		protected ShipAPI ship;  // 关联的舰船实例
		protected String id;    // 效果ID

		// 使用Map存储每种武器尺寸的计时器和连续发射计数
		private final Map<WeaponAPI.WeaponSize, Float> timeSinceLastFireMap = new HashMap<>();
		private final Map<WeaponAPI.WeaponSize, Integer> consecutiveFiresMap = new HashMap<>();
		
		// 存储每个武器的上一帧弹药数量，用于检测发射
		private final Map<WeaponAPI, Integer> lastAmmoMap = new HashMap<>();

		// 根据武器尺寸定义窗口期和衰减速率
		private static final float WINDOW_SMALL = 0.5f;    // 小型武器窗口期(0.5秒)
		private static final float WINDOW_MEDIUM = 1f;     // 中型武器窗口期(1秒)
		private static final float WINDOW_LARGE = 1.25f;   // 大型武器窗口期(1.25秒)

		private static final float DECAY_SMALL = 0.5f;     // 小型武器衰减速率
		private static final float DECAY_MEDIUM = 0.9f;      // 中型武器衰减速率
		private static final float DECAY_LARGE = 1.10f;    // 大型武器衰减速率

		public HighSpeedMissileRackScript(ShipAPI ship, String id) {
			this.ship = ship;
			this.id = id;
			// 初始化所有尺寸武器的计时器和计数
			for (WeaponAPI.WeaponSize size : WeaponAPI.WeaponSize.values()) {
				timeSinceLastFireMap.put(size, 1f);  // 初始设为1秒，确保第一次发射是满效果
				consecutiveFiresMap.put(size, 0);    // 初始连续发射次数为0
			}
		}

		@Override
		public void advance(float amount) {
			// 如果舰船已死亡则不做任何处理
			if (!ship.isAlive() || ship.isHulk()) return;

			// 更新所有尺寸武器的计时器
			for (WeaponAPI.WeaponSize size : WeaponAPI.WeaponSize.values()) {
				float currentTime = timeSinceLastFireMap.get(size);
				timeSinceLastFireMap.put(size, currentTime + amount);
			}

			// 遍历舰船所有武器
			for (WeaponAPI weapon : ship.getAllWeapons()) {
				// 只处理导弹类型武器
				if (weapon.getType() != WeaponType.MISSILE) continue;
				// 跳过自动装填武器
				if (weapon.getAmmoPerSecond() > 0 || !(weapon.getAmmo() > 0)) continue;

				WeaponAPI.WeaponSize size = weapon.getSize();
				// 获取当前尺寸武器的窗口期和衰减速率
				float window = WINDOW_MEDIUM;
				float decay = DECAY_MEDIUM;

				switch(size) {
					case SMALL:
						window = WINDOW_SMALL;
						decay = DECAY_SMALL;
						break;
					case LARGE:
						window = WINDOW_LARGE;
						decay = DECAY_LARGE;
						break;
				}

				// 获取当前尺寸武器的计时器和计数
				float timeSinceLastFire = timeSinceLastFireMap.get(size);
				int consecutiveFires = consecutiveFiresMap.get(size);

				// 如果超过窗口期没有发射，减少连续发射计数
				if (timeSinceLastFire > window && consecutiveFires > 0) {
					consecutiveFiresMap.put(size, Math.max(0, (int)(consecutiveFires - decay)));
					timeSinceLastFireMap.put(size, 0f);
				}

				// 检测武器是否刚刚发射（通过弹药数量变化检测，更可靠）
				Integer lastAmmo = lastAmmoMap.get(weapon);
				int currentAmmo = weapon.getAmmo();
				
				if (lastAmmo != null && lastAmmo > currentAmmo && currentAmmo >= 0) {
					// 武器刚刚发射了导弹
					
					// 根据武器大小确定幅能百分比
					float fluxPercent = 0f;
					switch (weapon.getSize()) {
						case SMALL: fluxPercent = FLUX_PERCENT_SMALL; break;
						case MEDIUM: fluxPercent = FLUX_PERCENT_MEDIUM; break;
						case LARGE: fluxPercent = FLUX_PERCENT_LARGE; break;
					}

					// 增加硬幅能
					ship.getFluxTracker().increaseFlux(ship.getMaxFlux() * fluxPercent, true);
					// 获取武器的基础冷却时间
					float baseCooldown = weapon.getCooldown();

					// 计算冷却时间衰减效果
					float cooldownMultiplier = 0.1f; // 基础冷却倍率

					if (timeSinceLastFire <= window) {
						// 在窗口期内发射，增加连续发射计数
						int newConsecutiveFires = Math.min(consecutiveFires + 1, 4);
						consecutiveFiresMap.put(size, newConsecutiveFires);
						cooldownMultiplier = 0.1f * (1f + newConsecutiveFires);
					} else {
						// 超过窗口期发射，重置连续发射计数
						consecutiveFiresMap.put(size, 0);
					}

					// 应用冷却时间 (基础冷却时间 * 倍率)
					weapon.setRemainingCooldownTo(baseCooldown * cooldownMultiplier);
					// 重置当前尺寸武器的计时器
					timeSinceLastFireMap.put(size, 0f);
				}
				
				// 更新弹药记录
				lastAmmoMap.put(weapon, currentAmmo);
			}
		}
	}

	@Override
	public String getUnapplicableReason(ShipAPI ship) {
		if (hasIncompatibleHullMod(ship)) {
			return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_vector_design");
		}
		return null;
	}

	@Override
	public boolean isApplicableToShip(ShipAPI ship) {
		return getUnapplicableReason(ship) == null;
	}

	/**
	 * 获取描述参数
	 * @param index 参数索引
	 * @param hullSize 舰船尺寸
	 * @return 描述参数值
	 */
	@Override
	public String getDescriptionParam(int index, HullSize hullSize) {
		// 根据索引返回不同的描述参数
		if (index == 0) return "" + (int)(FLUX_PERCENT_SMALL * 100) + "%";
		if (index == 1) return "" + (int)(FLUX_PERCENT_MEDIUM * 100) + "%";
		if (index == 2) return "" + (int)(FLUX_PERCENT_LARGE * 100) + "%";
		if (index == 3) return Moci_TextLoader.getText(TEXT_ID, "description.param_ammo_reduction");
		return null;
	}

	/**
	 * 添加后描述部分 - 显示更详细的效果说明
	 * @param tooltip 工具提示制作器
	 * @param hullSize 舰船尺寸
	 * @param ship 舰船实例(可为null)
	 * @param width 工具提示宽度
	 * @param isForModSpec 是否用于mod规范
	 */
	@Override
	public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
		// 定义间距常量
		float pad = 3f;   // 小间距
		float opad = 10f; // 大间距

		// 获取高亮颜色
		Color h = Misc.getHighlightColor();
		// 获取负面效果颜色
		Color bad = Misc.getNegativeHighlightColor();

		// 添加详细描述内容
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.intro"), opad);
		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.effect_heading"), Alignment.MID, opad);
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.flux_intro"), pad);
		tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.flux_small",
				Moci_TextLoader.mapOf("small_flux", String.valueOf((int)(FLUX_PERCENT_SMALL * 100)))),
				pad, h,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.flux_small_highlights",
						Moci_TextLoader.mapOf("small_flux", String.valueOf((int)(FLUX_PERCENT_SMALL * 100)))).toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.flux_medium",
				Moci_TextLoader.mapOf("medium_flux", String.valueOf((int)(FLUX_PERCENT_MEDIUM * 100)))),
				pad, h,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.flux_medium_highlights",
						Moci_TextLoader.mapOf("medium_flux", String.valueOf((int)(FLUX_PERCENT_MEDIUM * 100)))).toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.flux_large",
				Moci_TextLoader.mapOf("large_flux", String.valueOf((int)(FLUX_PERCENT_LARGE * 100)))),
				pad, h,
				Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.flux_large_highlights",
						Moci_TextLoader.mapOf("large_flux", String.valueOf((int)(FLUX_PERCENT_LARGE * 100)))).toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.cooldown"), pad);
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.diminish"), pad, bad,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.diminish_highlights").toArray(new String[0]));
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.ammo_reduction"), pad, bad,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.ammo_reduction_highlights").toArray(new String[0]));

		tooltip.addSectionHeading(Moci_TextLoader.getText(TEXT_ID, "description.limit_heading"),
				new Color(255,150,150,255), //文字颜色
				new Color(200,0,0,150), //背景颜色
				Alignment.MID, // 文字位置
				opad); // 与其他元素之间的间距
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.limit_auto"), pad);
		tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.limit_incompatible"), pad, bad,
				Moci_TextLoader.getHighlights(TEXT_ID, "description.limit_incompatible_highlights").toArray(new String[0]));
	}

	private static boolean hasIncompatibleHullMod(ShipAPI ship) {
		return ship != null
				&& ship.getVariant() != null
				&& ship.getVariant().hasHullMod(HULLMOD_ID);
	}
}
