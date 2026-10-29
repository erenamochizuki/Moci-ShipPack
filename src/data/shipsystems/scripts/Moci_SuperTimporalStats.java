package data.shipsystems.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import data.scripts.combat.Moci_TimeFlow;
import java.awt.Color;
import org.apache.log4j.Logger;

public class Moci_SuperTimporalStats extends BaseShipSystemScript {

	// ===== 时间泄漏排查开关（常驻，不绑 devMode）=====
	public static final boolean TRACE = true;
	private static final String LOG_PREFIX = "[MOCI-TIME] ";
	private static final Logger LOG = Global.getLogger(Moci_SuperTimporalStats.class);

	// ===== 推进系统参数 =====
	private static final float SPEED_BOOST = 600f; // 最大速度加成基数
	private static final float TURN_SPEED = 600f; // 最大转向速度加成基数
	private static final float ACCEL_BOOST = 1200f; // 加速度加成基数
	// ===== 武器系统参数 =====
	private static final float WEAPON_BOOST = 2f; // 武器冷却基数
	private static final float WEAPON_REDUCE = 0.5f; // 武器耗能降低

	// ===== 时间操控参数 =====
	private static final float MAX_TIME_MULT = 3.33f; // 最大时间加速倍率
	private static final float MIN_TIME_MULT = 0.5f; // 最小时间减速倍率
	private static final float REPAIR_RATE_MULT = 10f; // 维修速度倍率
	private static final Color JITTER_COLOR = new Color(90, 165, 255, 55); // 外层抖动光效
	private static final Color JITTER_UNDER_COLOR = new Color(90, 165, 255, 155); // 内层抖动光效

	// ===== 系统状态跟踪 =====
	/** 时间申请的名字：迁移到 Moci_TimeFlow 之后只作 Handle 名，不再作属性表里的键。 */
	private static final String TIME_HANDLE_ID = "Moci_SuperTimporalStats";
	private boolean isPlayerShip = false;
	private boolean soundPlayed = false; // 音效播放状态跟踪
	/** 本场生效中的时间申请；null = 当前没有申请。释放点统一走 releaseTimeFlow。 */
	private Moci_TimeFlow.Handle timeHandle;
	/**
	 * 当前申请是否为「子弹时间」形态（玩家舰 = 船 ×倍率 且 世界 ×1/倍率；非玩家舰 = 只有船侧）。
	 * 形态由 isPlayerShip 决定，而 isPlayerShip 会在换乘时变化，所以形态变了必须重开申请，
	 * 不能只 set()：异形句柄的 set() 只会改已存在的两半，不会补上/摘掉世界那一半。
	 */
	private boolean timeHandleIsBullet;

	@Override
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		// 获取舰船实体与标识
		ShipAPI ship = validateShip(stats);
		if (ship == null) {
			// 实体不可识别（脚本被复用、stats 已换实体）时也不能把时间申请留在场上
			releaseTimeFlow("entity is not a ship");
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
			applyTimeManipulation(ship, effectLevel);
			applyVisualEffects(ship, state, effectLevel);
		}
	}

	@Override
	public void unapply(MutableShipStatsAPI stats, String id) {
		// 移除所有效果修饰器。
		// 逐项释放：原实现是一条直链，任一项抛错（例如 :64 的 Global.getCombatEngine() 为 null）
		// 都会跳过后续所有 unmodify，把时间修饰永久留在船和引擎上。
		// 时间倍率已不在这里摘：它由 Moci_TimeFlow 的申请独占，releaseTimeFlow 一次成对收回
		// （船侧 + 世界侧同属一个句柄），不可能出现「只摘了一半」的中间态。
		releaseQuietly(() -> stats.getMaxSpeed().unmodify(id), "max speed");
		releaseQuietly(() -> stats.getTurnAcceleration().unmodify(id), "turn accel");
		releaseQuietly(() -> stats.getAcceleration().unmodify(id), "accel");
		releaseQuietly(() -> stats.getCombatEngineRepairTimeMult().unmodify(id), "engine repair mult");
		releaseQuietly(() -> stats.getCombatWeaponRepairTimeMult().unmodify(id), "weapon repair mult");

		releaseTimeFlow("unapply");

		// 重置音效播放状态，准备下次激活
		soundPlayed = false;
		isPlayerShip = false;
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
		ShipAPI ship = shipOf(stats);
		if (ship == null)
			return null;
		CombatEngineAPI engine = Global.getCombatEngine();
		isPlayerShip = engine != null && ship == engine.getPlayerShip();
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
	 * 应用时间操控效果。
	 *
	 * <p>数值与原实现一字不差：船侧 {@code timeMult = 1 + (MAX_TIME_MULT - 1) * effectLevel²}，
	 * 玩家舰另加世界侧 {@code 1/timeMult}。区别只在于这两半现在由 {@link Moci_TimeFlow}
	 * 成对持有：玩家舰用 {@code openBulletTime}（船 ×倍率、世界 ×1/倍率，原子出现、原子消失），
	 * 非玩家舰用 {@code openShip}（只压船侧，绝不触碰世界）。换乘导致的「玩家 ↔ 非玩家」
	 * 形态切换会被识别并重开申请，所以不再需要手写的 worldTimeApplied 记账 ——
	 * 也就不存在「记账与引擎实际状态不一致 → 永久留场」的窗口。</p>
	 */
	private void applyTimeManipulation(ShipAPI ship, float effectLevel) {
		// 计算时间倍率（二次曲线平滑）—— 与原实现完全相同的公式
		float timeMult = 1f + (MAX_TIME_MULT - 1f) * effectLevel * effectLevel;

		boolean bullet = isPlayerShip;
		if (timeHandle == null || !timeHandle.active() || timeHandleIsBullet != bullet) {
			// 缺失/已失效/形态变更：先收旧申请再开新申请（release 幂等，重复调用无副作用）
			releaseTimeFlow("重建时间申请 形态=" + (bullet ? "bullet" : "ship"));
			timeHandle = bullet
					? Moci_TimeFlow.openBulletTime(ship, TIME_HANDLE_ID, timeMult)
					: Moci_TimeFlow.openShip(ship, TIME_HANDLE_ID, timeMult);
			timeHandleIsBullet = bullet;
			if (TRACE && timeHandle != null && timeHandle.active()) {
				LOG.info(LOG_PREFIX + "时空加速场申请时间 形态="
						+ (bullet ? "bullet(船×" + timeMult + " 世界×" + (1f / timeMult) + ")" : "ship(船×" + timeMult + ")"));
			}
		} else {
			// 每帧把最新 effectLevel 写进同一个申请：倍率随升/降级连续变化，不必重建句柄
			timeHandle.set(timeMult);
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
	 * 系统关闭时的过渡处理（OUT 过渡期 = 系统正在离开 ACTIVE）
	 */
	private void handleSystemDeactivation(MutableShipStatsAPI stats, String id) {
		// 时间申请必须在这里就收回，不能留给 unapply：unapply 只要被框架跳过一次，
		// 申请就会永久留场（原实现正是这样把 1/timeMult 留在引擎上）。
		// 句柄是成对的，所以这一次 release 同时收走船侧加速与世界侧减速，
		// 不会出现「世界已恢复正常、本舰仍按 3.33 倍主观时间跑」的错位。
		releaseTimeFlow("system leaving ACTIVE");
		releaseQuietly(() -> stats.getMaxSpeed().unmodify(id), "max speed (OUT)"); // 平滑减速至正常速度
	}

	/**
	 * 播放系统激活音效
	 */
	private void playActivationSound(ShipAPI ship) {
		// 播放鲸鸣音效
		Global.getSoundPlayer().playSound("Moci_Whales_call", 1f, 0.5f,
				ship.getLocation(), ship.getVelocity());
	}

	/**
	 * 收回本类唯一的时间申请。
	 *
	 * <p>句柄自带「船侧 + 世界侧」两半，所以这一次调用等价于原来的
	 * {@code stats.getTimeMult().unmodify(...)} + {@code engine.getTimeMult().unmodify(...)} 两条直写，
	 * 但不可能只成功一半。原来分别是 releaseWorldTime / releaseEngineTime 的职责（各自还要记账
	 * worldTimeApplied、还要补摘复合 id），现在两个方法已删除：同一件事不再有两套机制。</p>
	 */
	private void releaseTimeFlow(String why) {
		Moci_TimeFlow.Handle handle = timeHandle;
		if (handle == null) {
			return;
		}
		timeHandle = null;
		boolean wasActive = handle.active();
		try {
			handle.release(); // 幂等；句柄已失效时是空操作
		} catch (RuntimeException | LinkageError failure) {
			LOG.warn(LOG_PREFIX + "收回时间申请失败 id=" + TIME_HANDLE_ID + " -> " + failure);
		}
		if (TRACE && wasActive) {
			LOG.info(LOG_PREFIX + "时空加速场收回时间申请 id=" + TIME_HANDLE_ID + " 原因=" + why);
		}
	}

	// ===== 幂等释放工具（本类私有，不依赖任何全局注册表） =====

	private static ShipAPI shipOf(MutableShipStatsAPI stats) {
		if (stats == null)
			return null;
		Object entity = stats.getEntity();
		return entity instanceof ShipAPI ? (ShipAPI) entity : null;
	}

	/** 单步释放：一步失败只记一条日志，不影响后续步骤 */
	private void releaseQuietly(Runnable step, String what) {
		try {
			step.run();
		} catch (RuntimeException | LinkageError failure) {
			LOG.warn(LOG_PREFIX + "释放失败 " + what + " -> " + failure);
		}
	}
}
