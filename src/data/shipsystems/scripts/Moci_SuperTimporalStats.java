package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import java.awt.Color;
import org.apache.log4j.Logger;

/**
 * 时空加速场：船侧给本舰加速、玩家驾驶时再给世界一条 1/timeMult 的配平。
 *
 * <p>时间修饰符由本类自己写、自己摘（原生写法，和原版 TemporalShellStats 一个路子）：
 * 船侧键 = 系统框架传进来的 {@code id}，世界侧键 = {@code getIdWithShip(ship)}（含舰 ID）。
 * 写入点只有 {@link #applyTimeManipulation}，摘除点有四个，全部在本类内部、全部可 grep：
 * {@link #applyTimeManipulation} 的 else 分支（本舰已不是玩家座舰）、
 * {@link #handleSystemDeactivation}（OUT）、{@link #unapply}、以及 {@link #apply} 里 validateShip 失败的分支。
 * 每一处摘除都各自独立兜底：任何一项抛错都不能连累其余项，尤其是「世界侧必须被摘掉」这一条。
 *
 * <p>历史 bug（已修）：原版只用 {@code if (isPlayerShip)} 写世界侧、没有 else，
 * 玩家换舰之后那条 {@code 1/timeMult} 就永远留在引擎上（世界一直停在加速状态）；
 * OUT 分支也只摘了 maxSpeed，世界侧同样漏掉。
 */
public class Moci_SuperTimporalStats extends BaseShipSystemScript {

	/** 常驻排查日志开关（禁止挂到 devMode 上）。 */
	public static final boolean TRACE = true;

	private static final String LOG_PREFIX = "[MOCI-TIME] ";
	private static final Logger LOG = Global.getLogger(Moci_SuperTimporalStats.class);

	private static final float SPEED_BOOST = 600f;
	private static final float TURN_SPEED = 600f;
	private static final float ACCEL_BOOST = 1200f;

	private static final float WEAPON_BOOST = 2f;
	private static final float WEAPON_REDUCE = 0.5f;

	private static final float MAX_TIME_MULT = 3.33f;
	private static final float MIN_TIME_MULT = 0.5f;
	private static final float REPAIR_RATE_MULT = 10f;

	private static final Color JITTER_COLOR = new Color(90, 165, 255, 55);
	private static final Color JITTER_UNDER_COLOR = new Color(90, 165, 255, 155);

	// ===== 系统状态跟踪 =====
	private String uniqueId; // 实例唯一标识
	private boolean isPlayerShip = false;
	private boolean soundPlayed = false; // 音效播放状态跟踪

	/** 世界侧那条 1/timeMult 当前是否由本实例持有 —— 只用来决定要不要打日志（unmodify 本身随便调）。 */
	private boolean worldTimeApplied = false;

	@Override
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		// 获取舰船实体与标识
		ShipAPI ship = validateShip(stats);
		if (ship == null) {
			// 拿不到舰（属性挂在非舰船实体上、或引擎已经结束）时，也要把可能残留的世界侧修饰摘掉，
			// 否则这条修饰符会随着本类失去上下文而永远留在引擎上。
			releaseWorldTime("属性实体不是舰船或引擎已结束");
			return;
		}

		// 根据系统状态应用不同效果
		if (state == State.OUT) {
			handleSystemDeactivation(stats, id);
		} else {
			// 系统激活时播放音效（仅播放一次）
			if (!soundPlayed && (state == State.IN || state == State.ACTIVE)) {
				playActivationSound(ship);
				soundPlayed = true;
			}

			applyMovementBoost(stats, id, effectLevel);
			applyTimeManipulation(ship, id, effectLevel);
			applyVisualEffects(ship, state, effectLevel);
		}
	}

	@Override
	public void unapply(MutableShipStatsAPI stats, String id) {
		// 移除所有效果修饰器：六项逐条独立兜底。这里不能写成一条直线，
		// 因为 reservoir 里任何一项抛错都会把后面（含时间倍率）全部跳过，而时间倍率才是会拖死战斗的那条。
		releaseQuietly(() -> stats.getMaxSpeed().unmodify(id), "unapply maxSpeed");
		releaseQuietly(() -> stats.getTurnAcceleration().unmodify(id), "unapply turnAcceleration");
		releaseQuietly(() -> stats.getAcceleration().unmodify(id), "unapply acceleration");
		releaseQuietly(() -> stats.getTimeMult().unmodify(id), "unapply shipTimeMult");
		releaseQuietly(() -> stats.getCombatEngineRepairTimeMult().unmodify(id), "unapply engineRepairTimeMult");
		releaseQuietly(() -> stats.getCombatWeaponRepairTimeMult().unmodify(id), "unapply weaponRepairTimeMult");

		// 世界侧两种形态都摘：实例字段（写过就一定在）与按舰 ID 重算的键（实例字段丢失时的兜底）。
		// unmodify 幂等，重复调用没有副作用。
		releaseWorldTime("unapply");
		releaseEngineTime(uniqueId, "unapply(uniqueId)");
		releaseEngineTime(getIdWithShip(stats), "unapply(getIdWithShip)");

		// 重置音效播放状态，准备下次激活
		soundPlayed = false;
	}

	@Override
	public StatusData getStatusData(int index, State state, float effectLevel) {
		// 状态栏显示逻辑
		if (index == 0) {
			return new StatusData("时空加速场激活", false);
		}
		if (index == 1) {
			float timeMult = 1f + (MAX_TIME_MULT - 1f) * effectLevel;
			return new StatusData("主观时间流速 x" + String.format("%.1f", timeMult), false);
		}
		return null;
	}

	// ===== 核心功能模块 =====

	/**
	 * 舰船实体验证与初始化
	 */
	private ShipAPI validateShip(MutableShipStatsAPI stats) {
		if (stats == null || !(stats.getEntity() instanceof ShipAPI))
			return null;
		ShipAPI ship = (ShipAPI) stats.getEntity();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null)
			return null;
		isPlayerShip = (ship == engine.getPlayerShip());
		uniqueId = getIdWithShip(ship); // 生成唯一ID
		return ship;
	}

	/**
	 * 应用推进系统增益
	 */
	private void applyMovementBoost(MutableShipStatsAPI stats, String id, float effectLevel) {
		stats.getMaxSpeed().modifyFlat(id, SPEED_BOOST * effectLevel);
		stats.getTurnAcceleration().modifyFlat(id, TURN_SPEED * effectLevel);
		stats.getAcceleration().modifyFlat(id, ACCEL_BOOST * effectLevel);
		// 维修系统超频
		stats.getCombatEngineRepairTimeMult().modifyMult(id, 1f / REPAIR_RATE_MULT);
		stats.getCombatWeaponRepairTimeMult().modifyMult(id, 1f / REPAIR_RATE_MULT);
		// 武器快速冷却
		stats.getEnergyAmmoRegenMult().modifyMult(id, WEAPON_BOOST);
		stats.getBallisticAmmoRegenMult().modifyMult(id, WEAPON_BOOST);
		stats.getMissileAmmoRegenMult().modifyMult(id, WEAPON_BOOST);
		// 武器耗能降低
		stats.getEnergyWeaponFluxCostMod().modifyMult(id, WEAPON_REDUCE);
		stats.getBallisticWeaponFluxCostMod().modifyMult(id, WEAPON_REDUCE);
		stats.getMissileWeaponFluxCostMod().modifyMult(id, WEAPON_REDUCE);
	}

	/**
	 * 应用时间操控效果 —— 本类的唯一时间写入点。
	 *
	 * <p>船侧恒写；世界侧只在「这艘舰此刻就是玩家座舰」时写。
	 * 关键就是那个 else：玩家换舰/换乘之后 isPlayerShip 变 false，必须当场把上一次写下的
	 * {@code 1/timeMult} 摘掉，否则引擎上会留一条没人再碰的修饰符，世界永久加速。
	 * 摘除每帧都尝试是安全的 —— {@code unmodify} 幂等，且只在真的持有过的时候才打一行日志。
	 */
	private void applyTimeManipulation(ShipAPI ship, String id, float effectLevel) {
		// 计算时间倍率（二次曲线平滑）
		float timeMult = 1f + (MAX_TIME_MULT - 1f) * effectLevel * effectLevel;
		ship.getMutableStats().getTimeMult().modifyMult(id, timeMult);

		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) {
			return;
		}

		// 玩家舰船需要补偿全局时间流速
		if (isPlayerShip) {
			try {
				engine.getTimeMult().modifyMult(uniqueId, 1f / timeMult);
				worldTimeApplied = true;
			} catch (RuntimeException | LinkageError failure) {
				LOG.warn(LOG_PREFIX + "写入世界时间修饰失败 id=" + uniqueId + " 异常=" + failure);
			}
		} else {
			// 本舰不再是玩家座舰：世界侧那条补偿必须当场摘掉，不能等 unapply（换舰时它可能永远不来）
			releaseWorldTime("本舰已不再是被玩家驾驶的座舰");
		}
	}

	/**
	 * 应用视觉特效
	 */
	private void applyVisualEffects(ShipAPI ship, State state, float effectLevel) {
		// 抖动效果参数计算
		float jitterLevel = (state == State.IN) ? Math.min(1f, effectLevel / ship.getSystem().getChargeUpDur())
				: effectLevel;
		jitterLevel = (float) Math.sqrt(jitterLevel); // 平方根平滑

		// 设置双层抖动效果
		ship.setJitter(this, JITTER_COLOR, jitterLevel, 3, 0, 5f);
		ship.setJitterUnder(this, JITTER_UNDER_COLOR, jitterLevel, 25, 0f, 10f);

		// 引擎火焰特效
		ship.getEngineController().fadeToOtherColor(this, JITTER_COLOR, new Color(0, 0, 0, 0), effectLevel, 0.5f);
		ship.getEngineController().extendFlame(this, -0.25f, -0.25f, -0.25f);
	}

	/**
	 * 系统关闭时的过渡处理（State.OUT）：世界侧时间补偿与船侧时间倍率都在这里释放。
	 *
	 * <p>原版这里只摘了 maxSpeed，等于把 {@code 1/timeMult} 与船侧 timeMult 的摘除全押在后面的
	 * {@link #unapply} 上；一旦玩家在 OUT 期间换舰/舰体被击毁导致 unapply 没走到，世界就卡住。
	 */
	private void handleSystemDeactivation(MutableShipStatsAPI stats, String id) {
		releaseWorldTime("系统离开 ACTIVE");
		releaseQuietly(() -> stats.getTimeMult().unmodify(id), "OUT shipTimeMult");
		releaseQuietly(() -> stats.getMaxSpeed().unmodify(id), "OUT maxSpeed"); // 平滑减速至正常速度
	}

	/**
	 * 播放系统激活音效
	 */
	private void playActivationSound(ShipAPI ship) {
		// 播放鲸鸣音效
		Global.getSoundPlayer().playSound("Moci_Whales_call", 1f, 0.5f,
				ship.getLocation(), ship.getVelocity());
	}

	// ===== 时间修饰的兜底工具 =====

	/** 世界侧释放：只关心「本实例确实写过」的那条，日志只在这里打一次。 */
	private void releaseWorldTime(String why) {
		if (!worldTimeApplied) {
			return;
		}
		worldTimeApplied = false;
		releaseEngineTime(uniqueId, why);
	}

	/** 摘引擎上的时间修饰；引擎不在、键为空都直接跳过。unmodify 幂等，可重复调用。 */
	private void releaseEngineTime(String timeId, String why) {
		if (timeId == null) {
			return;
		}
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) {
			return;
		}
		try {
			engine.getTimeMult().unmodify(timeId);
			if (TRACE) {
				LOG.info(LOG_PREFIX + "摘除世界时间修饰 id=" + timeId + " 原因=" + why);
			}
		} catch (RuntimeException | LinkageError failure) {
			LOG.warn(LOG_PREFIX + "摘除世界时间修饰失败 id=" + timeId + " 原因=" + why + " 异常=" + failure);
		}
	}

	/** 单项解除的兜底：一项失败只记一行，继续摘下一项。 */
	private void releaseQuietly(Runnable step, String what) {
		try {
			step.run();
		} catch (RuntimeException | LinkageError failure) {
			LOG.warn(LOG_PREFIX + "解除修饰失败 步骤=" + what + " 异常=" + failure);
		}
	}

	/**
	 * 生成带舰船ID的唯一标识
	 */
	private String getIdWithShip(ShipAPI ship) {
		if (ship == null)
			return null;
		return this.getClass().getSimpleName() + "_" + ship.getId();
	}

	private String getIdWithShip(MutableShipStatsAPI stats) {
		if (stats == null || !(stats.getEntity() instanceof ShipAPI))
			return null;
		return getIdWithShip((ShipAPI) stats.getEntity());
	}
}