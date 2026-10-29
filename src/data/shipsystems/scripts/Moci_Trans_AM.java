package data.shipsystems.scripts;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

import data.scripts.combat.Moci_TimeFlow;

/**
 * TRANS-AM：系统激活期间本舰 ×(1..3) 时间，玩家驾驶时再把世界按 1/f 压回去。
 *
 * <p>时间倍率一律经由 {@link Moci_TimeFlow} 申请，本类不再自己持有时间修饰符 id
 * （见架构文档附录 E：「谁申请、谁释放」必须有单一归属，否则任何一条退出路径走岔，
 * 世界倍率就永久留场）。</p>
 */
public class Moci_Trans_AM extends BaseShipSystemScript {

	public static final float MAX_TIME_MULT = 3f;
	public static final Color JITTER_COLOR = new Color(255, 65, 65, 75);
	public static final Color JITTER_UNDER_COLOR = new Color(255, 90, 90, 155);

	/** 句柄挂在舰船 customData 上：系统脚本实例的生命周期由引擎决定，不能假定它跟着某一艘船走。 */
	private static final String TIME_FLOW_KEY = "moci_trans_am_timeflow";

	/** 申请名只用于日志与去重，不再是属性键。 */
	private static final String TIME_FLOW_ID = "Moci_Trans_AM";

	@Override
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		ShipAPI ship;
		if (stats.getEntity() instanceof ShipAPI) ship = (ShipAPI) stats.getEntity();
		else return;

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
		applyTimeFlow(ship, shipTimeMult);

		ship.getEngineController().fadeToOtherColor(this, JITTER_COLOR, new Color(0, 0, 0, 0), effectLevel, 0.5f);
		ship.getEngineController().extendFlame(this, -0.25f, -0.25f, -0.25f);
	}

	@Override
	public void unapply(MutableShipStatsAPI stats, String id) {
		if (stats.getEntity() instanceof ShipAPI) releaseTimeFlow((ShipAPI) stats.getEntity(), "unapply");
	}

	/**
	 * 本舰时间倍率走时间流权威。
	 *
	 * <p>「玩家驾驶」决定的是<b>要不要压世界</b>，这是结构性的：换乘会让它翻转，所以形态变化时必须
	 * 重新申请一条新租约，而不是对旧句柄 {@code set()} 了事 —— 旧句柄的世界半在有/无之间切换时，
	 * 只改数值是变不出来的。</p>
	 */
	private static void applyTimeFlow(ShipAPI ship, float shipTimeMult) {
		CombatEngineAPI engine = Global.getCombatEngine();
		boolean bullet = engine != null && engine.getPlayerShip() == ship;
		Flow flow = flowOf(ship);
		if (flow != null && flow.handle.active() && flow.bullet == bullet) {
			flow.handle.set(shipTimeMult);
			return;
		}
		releaseTimeFlow(ship, "时间形态变化，重新申请");
		Moci_TimeFlow.Handle handle = bullet
				? Moci_TimeFlow.openBulletTime(ship, TIME_FLOW_ID, shipTimeMult)
				: Moci_TimeFlow.openShip(ship, TIME_FLOW_ID, shipTimeMult);
		ship.setCustomData(TIME_FLOW_KEY, new Flow(handle, bullet));
	}

	private static void releaseTimeFlow(ShipAPI ship, String why) {
		Flow flow = flowOf(ship);
		if (flow == null) return;
		ship.setCustomData(TIME_FLOW_KEY, null);
		flow.handle.release();
	}

	private static Flow flowOf(ShipAPI ship) {
		Object raw = ship.getCustomData().get(TIME_FLOW_KEY);
		return raw instanceof Flow flow ? flow : null;
	}

	/** 一条租约 + 它当前的形态（是否成对压世界）。 */
	private static final class Flow {
		private final Moci_TimeFlow.Handle handle;
		private final boolean bullet;

		private Flow(Moci_TimeFlow.Handle handle, boolean bullet) {
			this.handle = handle;
			this.bullet = bullet;
		}
	}

	@Override
	public StatusData getStatusData(int index, State state, float effectLevel) {
		if (index == 0) {
			return new StatusData("TRANS ACT MAX", false);
		}
		return null;
	}
}
