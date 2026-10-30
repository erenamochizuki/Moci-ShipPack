package data.shipsystems.scripts;

import java.awt.Color;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

public class Moci_Trans_AM extends BaseShipSystemScript {

	public static final float MAX_TIME_MULT = 3f;
	public static final Color JITTER_COLOR = new Color(255, 65, 65, 75);
	public static final Color JITTER_UNDER_COLOR = new Color(255, 90, 90, 155);

	private static final Logger LOG = Global.getLogger(Moci_Trans_AM.class);
	private static final String LOG_PREFIX = "[MOCI-TIME] ";

	/** 当前时缓挂在哪艘船上；死船自检要用（系统脚本没有 advance，只能在每帧的 apply 里看）。 */
	private ShipAPI timeShip;
	/** 与 {@link #timeShip} 对应的修饰符 id（系统 id + 舰船 id），摘除时用它而不是重算参数。 */
	private String timeId;
	/** 本次挂载是否已经为「船没了」打过日志；只为避免每帧刷屏，重新挂载时复位。 */
	private boolean releaseLogged;

	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		ShipAPI ship = null;
		if (stats.getEntity() instanceof ShipAPI) {
			ship = (ShipAPI) stats.getEntity();
			id = id + "_" + ship.getId();
		} else {
			return;
		}

		// 这是本类唯一的每帧入口：引擎的 F.advance 只要 eff>0 就调 apply，死船的 apply 照样会跑，
		// 但引擎既不会 deactivate 系统、也不会回头调 unapply ——「船没了就摘」只能在这里自己发现。
		if (dead(ship)) {
			releaseForDead(ship);
			return;
		}

		float jitterLevel = effectLevel;
		float jitterRangeBonus = 0;
		float maxRangeBonus = 10f;
		if (state == State.IN) {
			jitterLevel = effectLevel / (1f / ship.getSystem().getChargeUpDur());
			if (jitterLevel > 1) {
				jitterLevel = 1f;
			}
			jitterRangeBonus = jitterLevel * maxRangeBonus;
		} else if (state == State.ACTIVE) {
			jitterLevel = 1f;
			jitterRangeBonus = maxRangeBonus;
		} else if (state == State.OUT) {
			jitterRangeBonus = jitterLevel * maxRangeBonus;
		}
		jitterLevel = (float) Math.sqrt(jitterLevel);
		effectLevel *= effectLevel;

		ship.setJitter(this, JITTER_COLOR, jitterLevel, 3, 0, 0 + jitterRangeBonus);
		ship.setJitterUnder(this, JITTER_UNDER_COLOR, jitterLevel, 25, 0f, 5f + jitterRangeBonus);


		float shipTimeMult = 1f + (MAX_TIME_MULT - 1f) * effectLevel;
		applyTime(ship, stats, id, shipTimeMult);

		ship.getEngineController().fadeToOtherColor(this, JITTER_COLOR, new Color(0,0,0,0), effectLevel, 0.5f);
		ship.getEngineController().extendFlame(this, -0.25f, -0.25f, -0.25f);
	}


	public void unapply(MutableShipStatsAPI stats, String id) {
		// 不依赖 apply 是否跑过：id 由参数 + 舰船 id 现场推出来，脚本实例被换掉也摘得掉。
		// 这里是无条件摘干净，不判死活：正是「不管怎么退出都要摘」的那一侧。
		ShipAPI entity = stats.getEntity() instanceof ShipAPI ? (ShipAPI) stats.getEntity() : null;
		if (entity != null) {
			id = id + "_" + entity.getId();
		}
		String staleId = timeId != null ? timeId : (entity != null ? id : null);
		releaseTime();
		unmodifyTime(entity, staleId);
	}


	/**
	 * 写本舰时间倍率（以及玩家舰的世界 1/f）。
	 *
	 * <p>船属性表与引擎是两个载体、两个作用域，分开写才能分开摘；世界那一半只在玩家舰上存在，
	 * 非玩家舰必须显式 {@code unmodify}，否则换乘后旧 id 会永久留场。</p>
	 */
	private void applyTime(ShipAPI ship, MutableShipStatsAPI stats, String id, float timeMult) {
		if (timeId != null && !timeId.equals(id)) {
			// 同一脚本实例被复用到另一艘船上：先摘掉旧的，绝不留下跨船残留
			unmodifyTime(timeShip, timeId);
		}
		timeShip = ship;
		timeId = id;
		releaseLogged = false;
		stats.getTimeMult().modifyMult(id, timeMult);
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine != null) {
			if (ship == engine.getPlayerShip() && ship.isAlive() && !ship.wasRemoved()) {
				engine.getTimeMult().modifyMult(id, 1f / timeMult);
			} else {
				engine.getTimeMult().unmodify(id);
			}
		}
	}

	/**
	 * 这艘船的时缓还该不该存在：判「船没了」。
	 *
	 * <p>为什么判这四条：被打爆（{@code isHulk}）与自然消失（{@code wasRemoved}）都要摘；
	 * 换场后 {@code Global.getCombatEngine()} 会换人或为 null，旧战场的属性表已经没意义；
	 * {@code isEntityInPlay} 覆盖「还活着但已被移出场」这种中间态。</p>
	 */
	private static boolean dead(ShipAPI ship) {
		if (ship == null) return true;
		if (!ship.isAlive() || ship.isHulk() || ship.wasRemoved()) return true;
		CombatEngineAPI engine = Global.getCombatEngine();
		return engine == null || !engine.isEntityInPlay(ship);
	}

	/** 船没了：把本条时缓摘干净并留一条日志。摘除本身幂等，重复调用无害。 */
	private void releaseForDead(ShipAPI ship) {
		boolean had = timeId != null;
		releaseTime();
		if (had && !releaseLogged) {
			releaseLogged = true;
			LOG.warn(LOG_PREFIX + "TRANS-AM 宿主舰已死亡或离场，摘掉时缓（ship="
					+ (ship == null ? "-" : ship.getId()) + "）");
		}
	}

	/** 摘掉本地记录的时缓；幂等、绝不抛。 */
	private void releaseTime() {
		ShipAPI ship = timeShip;
		String currentId = timeId;
		timeShip = null;
		timeId = null;
		if (currentId != null) unmodifyTime(ship, currentId);
	}

	/** 真正的摘除动作，不依赖字段，所以「脚本实例被换掉」时 unapply 也能直接摘掉旧 id。 */
	private static void unmodifyTime(ShipAPI ship, String id) {
		if (id == null) return;
		if (ship != null) {
			try {
				ship.getMutableStats().getTimeMult().unmodify(id);
			} catch (RuntimeException | LinkageError ignored) {
				// 舰船已被回收：这条属性表不存在了，没有可摘的东西
			}
		}
		try {
			CombatEngineAPI engine = Global.getCombatEngine();
			if (engine != null) engine.getTimeMult().unmodify(id);
		} catch (RuntimeException | LinkageError ignored) {
		}
	}


	public StatusData getStatusData(int index, State state, float effectLevel) {
		float shipTimeMult = 1f + (MAX_TIME_MULT - 1f) * effectLevel;
		if (index == 0) {
			return new StatusData("TRANS ACT MAX", false);
		}
		return null;
	}
}
