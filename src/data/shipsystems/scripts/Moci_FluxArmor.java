package data.shipsystems.scripts;

import java.awt.Color;
import java.util.EnumSet;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import org.apache.log4j.Logger;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

public class Moci_FluxArmor extends BaseShipSystemScript {

	private static final Logger LOG = Global.getLogger(Moci_FluxArmor.class);
	private static final String LOG_PREFIX = "[MOCI-TIME] ";

	// 系统参数配置
	public static final float INCOMING_DAMAGE_MULT = 0.3f;  // 70%减伤
	public static final float TIME_MULT = 3f;               // 时间流速倍率
	public static final float SPEED_BONUS = 150f;           // 速度加成
	public static final float ACCEL_BONUS = 300f;           // 加速度加成
	public static final float TURN_PENALTY = -75f;          // 转向惩罚
	public static final float DECEL_PENALTY = -50f;          // 减速惩罚

	// 视觉效果参数
	public static final Color JITTER_COLOR = new Color(255, 100, 100, 75);    // 浅红色UnderJitter
	public static final Color JITTER_UNDER_COLOR = new Color(255, 255, 255, 125); // 浅白色CoverJitter
	public static final Color ENGINE_COLOR = new Color(255, 100, 100, 255);  // 引擎颜色
	// 新增残影效果参数
	public static final float AFTERIMAGE_INTERVAL = 0.25f; // 残影产生间隔(秒)
	public static final float AFTERIMAGE_FADE_IN = 0f;   // 残影淡入时间
	public static final float AFTERIMAGE_FADE_OUT = 0.3f; // 残影淡出时间
	public static final float AFTERIMAGE_ALPHA_START = 0.8f; // 初始透明度
	public static final float AFTERIMAGE_ALPHA_END = 0.2f;   // 结束透明度

	private Color originalShipColor; // 存储舰船原始颜色
	private static final String AFTERIMAGE_TRACKER_ID = "moci_fluxarmor_afterimage";
	private float afterimageAccumulator = 0f;

	// 系统状态跟踪
	private boolean playedSound = false;

	/**
	 * 时缓修饰符 id 前缀。带舰船 id 而不是直接用系统 id：同一台系统可能同时挂在两艘舰上
	 * （换乘、多实例），共用 id 时后一辆车的 unmodify 会把前一辆车的时缓一起摘掉。
	 */
	private static final String TIME_ID_PREFIX = "moci.fluxarmor.time.";

	/** 当前时缓挂在哪艘船上；死船自检要用（系统脚本没有 advance，只能在每帧的 apply 里看）。 */
	private ShipAPI timeShip;
	/** 与 {@link #timeShip} 对应的修饰符 id；摘除时用它，不重算，避免 id 参数变化导致摘不掉。 */
	private String timeId;
	/** 本次挂载是否已经为「船没了」打过日志；只为避免每帧刷屏，重新挂载时复位。 */
	private boolean releaseLogged;

	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		ShipAPI ship = null;
		if (stats.getEntity() instanceof ShipAPI) {
			ship = (ShipAPI) stats.getEntity();
			// 存储原始颜色
			if (originalShipColor == null) {
				originalShipColor = ship.getSpriteAPI().getColor();
			}
		} else {
			return;
		}

		// 这是本类唯一的每帧入口：引擎的 F.advance 只要 eff>0 就调 apply，死船的 apply 照样会跑，
		// 但引擎既不会 deactivate 系统、也不会回头调 unapply ——「船没了就摘」只能在这里自己发现。
		if (dead(ship)) {
			releaseForDead(ship);
			return;
		}

		// 系统激活时立即重装所有武器弹药
		if (state == State.IN && effectLevel > 0.1f) {
			for (WeaponAPI weapon : ship.getAllWeapons()) {
				if (weapon.usesAmmo()) {
					weapon.resetAmmo();
				}
				weapon.setRemainingCooldownTo(0f);
			}

			// 播放系统激活音效
			if (!playedSound) {
				Global.getSoundPlayer().playSound("lidar_windup", 1f, 1f, ship.getLocation(), ship.getVelocity());
				playedSound = true;
			}
		}

		// 时间流速控制
		// 自己写、自己摘：id 带舰船 id，世界那一半只在「本舰是玩家舰且还活着」时存在。
		// 换乘 / 战死时这里会把世界那一半摘掉，这是原实现里唯一会「越挂越多」的地方。
		float timeMult = 1f + (TIME_MULT - 1f) * effectLevel;
		applyTime(ship, stats, timeMult);

		// 伤害减免效果
		stats.getHullDamageTakenMult().modifyMult(id, 1f - (1f - INCOMING_DAMAGE_MULT) * effectLevel);
		stats.getArmorDamageTakenMult().modifyMult(id, 1f - (1f - INCOMING_DAMAGE_MULT) * effectLevel);
		stats.getEmpDamageTakenMult().modifyMult(id, 1f - (1f - INCOMING_DAMAGE_MULT) * effectLevel);

		// 机动性调整
		stats.getMaxSpeed().modifyFlat(id, SPEED_BONUS * effectLevel);
		stats.getAcceleration().modifyFlat(id, ACCEL_BONUS * effectLevel);
		stats.getTurnAcceleration().modifyPercent(id, TURN_PENALTY * effectLevel);
		stats.getDeceleration().modifyPercent(id, DECEL_PENALTY * effectLevel);
		stats.getMaxTurnRate().modifyPercent(id, TURN_PENALTY * effectLevel);

		// 视觉效果
		if (effectLevel > 0f) {
			// 设置舰船颜色为红色调
			ship.getSpriteAPI().setColor(new Color(
					Math.min(255, (int)(originalShipColor.getRed() * 0.7f + 76)),
					Math.min(255, (int)(originalShipColor.getGreen() * 0.4f)),
					Math.min(255, (int)(originalShipColor.getBlue() * 0.4f)),
					originalShipColor.getAlpha()
			));

			// 抖动效果
			ship.setJitterUnder(ship, JITTER_UNDER_COLOR, effectLevel, 15, 0f, 10f);
			ship.setJitter(ship, JITTER_COLOR, effectLevel, 5, 0f, 5f);

			// 残影效果
			afterimageAccumulator += effectLevel * Global.getCombatEngine().getElapsedInLastFrame();
			if (afterimageAccumulator > AFTERIMAGE_INTERVAL) {
				createAdvancedAfterimages(ship);
				afterimageAccumulator = 0f;
			}
		}
	}

	/**
	 * 写本舰时间倍率（以及玩家舰的世界 1/f）。
	 *
	 * <p>为什么不把这两条合成一条：它们的载体不同（船属性表 / 引擎），作用域也不同，
	 * 分开写才能分开摘；世界那一半只在玩家舰上存在，非玩家舰必须显式 {@code unmodify}，
	 * 否则换乘时旧 id 会永久留场。</p>
	 */
	private void applyTime(ShipAPI ship, MutableShipStatsAPI stats, float timeMult) {
		String currentId = TIME_ID_PREFIX + ship.getId();
		if (timeId != null && !timeId.equals(currentId)) {
			// 同一实例被复用到另一艘船上：先把旧的那两条摘掉，绝不留下跨船的残留修饰符
			unmodifyTime(timeShip, timeId);
		}
		timeShip = ship;
		timeId = currentId;
		releaseLogged = false;
		stats.getTimeMult().modifyMult(currentId, timeMult);
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine != null) {
			if (ship == engine.getPlayerShip() && ship.isAlive() && !ship.wasRemoved()) {
				engine.getTimeMult().modifyMult(currentId, 1f / timeMult);
			} else {
				engine.getTimeMult().unmodify(currentId);
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
			LOG.warn(LOG_PREFIX + "FLUX 宿主舰已死亡或离场，摘掉时缓（ship="
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

	/** 真正的摘除动作。不依赖字段，所以「脚本实例被换掉」时 unapply 也能直接摘掉旧 id。 */
	private static void unmodifyTime(ShipAPI ship, String id) {
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

	public void unapply(MutableShipStatsAPI stats, String id) {
		// 移除所有状态修饰器
		// 时缓按「本船 id」摘，即使本实例没走过 apply（脚本实例被换掉）也摘得掉
		ShipAPI entity = stats.getEntity() instanceof ShipAPI ? (ShipAPI) stats.getEntity() : null;
		String staleId = timeId != null ? timeId : entity != null ? TIME_ID_PREFIX + entity.getId() : null;
		releaseTime();
		if (staleId != null) unmodifyTime(entity, staleId);

		stats.getMaxSpeed().unmodify(id);
		stats.getAcceleration().unmodify(id);
		stats.getTurnAcceleration().unmodify(id);
		stats.getDeceleration().unmodify(id);
		stats.getMaxTurnRate().unmodify(id);

		stats.getHullDamageTakenMult().unmodify(id);
		stats.getArmorDamageTakenMult().unmodify(id);
		stats.getEmpDamageTakenMult().unmodify(id);
		// 恢复舰船原始颜色
		ShipAPI ship = (ShipAPI)stats.getEntity();
		if (ship != null && originalShipColor != null) {
			ship.getSpriteAPI().setColor(originalShipColor);
		}

		// 重置音效标记
		playedSound = false;
	}

	public StatusData getStatusData(int index, State state, float effectLevel) {
		if (index == 0) {
			return new StatusData("决战形态", false);
		}
		if (index == 1) {
			float percent = (1f - INCOMING_DAMAGE_MULT) * 100;
			return new StatusData((int) percent + "% 伤害减免", false);
		}
		if (index == 2) {
			return new StatusData("时间流速 x" + String.format("%.1f", 1f + (TIME_MULT - 1f) * effectLevel), false);
		}
		if (index == 3) {
			return new StatusData("+" + (int)(SPEED_BONUS * effectLevel) + " 最大速度", false);
		}
		return null;
	}
	// 残影效果
	private void createAdvancedAfterimages(ShipAPI ship) {
		// 获取偏移量
		SpriteAPI sprite = ship.getSpriteAPI();
		float offsetX = sprite.getWidth()/2 - sprite.getCenterX();
		float offsetY = sprite.getHeight()/2 - sprite.getCenterY();

		// 计算旋转后的偏移量
		float trueOffsetX = (float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetX -
				(float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetY;
		float trueOffsetY = (float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetX +
				(float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetY;

		// 创建舰船主体残影
		MagicRender.battlespace(
				ship.getSpriteAPI(),
				new Vector2f(ship.getLocation().x + trueOffsetX, ship.getLocation().y + trueOffsetY),
				new Vector2f(0, 0),
				new Vector2f(sprite.getWidth(), sprite.getHeight()),
				new Vector2f(0, 0),
				ship.getFacing()-90f,
				0f,
				new Color(255, 100, 100, (int)(AFTERIMAGE_ALPHA_START * 255)), // 使用初始透明度
				true,
				0f,
				0f,
				0f,
				0f,
				0f,
				AFTERIMAGE_FADE_IN,
				AFTERIMAGE_FADE_OUT,
				AFTERIMAGE_ALPHA_END,
				CombatEngineLayers.BELOW_SHIPS_LAYER
		);

		// 为所有武器创建残影
		for (WeaponAPI weapon : ship.getAllWeapons()) {
			if (weapon.getSprite() == null) continue;

			MagicRender.battlespace(
					weapon.getSprite(),
					new Vector2f(weapon.getLocation().x + trueOffsetX, weapon.getLocation().y + trueOffsetY),
					new Vector2f(0, 0),
					new Vector2f(weapon.getSprite().getWidth(), weapon.getSprite().getHeight()),
					new Vector2f(0, 0),
					weapon.getCurrAngle()-90f,
					0f,
					new Color(255, 150, 150, (int)(AFTERIMAGE_ALPHA_START * 255)), // 使用初始透明度
					true,
					0f,
					0f,
					0f,
					0f,
					0f,
					AFTERIMAGE_FADE_IN,
					AFTERIMAGE_FADE_OUT,
					AFTERIMAGE_ALPHA_END,
					CombatEngineLayers.BELOW_SHIPS_LAYER
			);
		}
	}
}