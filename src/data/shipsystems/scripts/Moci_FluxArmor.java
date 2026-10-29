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
import data.scripts.combat.Moci_TimeFlow;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

public class Moci_FluxArmor extends BaseShipSystemScript {

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

	// 时间流的申请名字（只作 Handle 名，不再当属性键用；属性键由 Moci_TimeFlow 统一持有）
	private static final String TIME_HANDLE_ID = "Moci_FluxArmor";
	private Moci_TimeFlow.Handle timeHandle;
	private boolean timeHandleIsBullet;

	// 系统状态跟踪
	private boolean playedSound = false;

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
		// 原实现是「自己挂 id、unapply 时自己摘」，于是系统被换乘、舰船离场或异常打断时，
		// 世界倍率 1/timeMult 会永久留在引擎上 = 进了时缓出不来。改由 Moci_TimeFlow 记账，
		// 申请失效（舰船离场）时由权威自动作废，本类只负责「还在驱动时每帧刷新」。
		float timeMult = 1f + (TIME_MULT - 1f) * effectLevel;
		applyTimeFlow(ship, timeMult);

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
	 * 申请/刷新本系统的时间流。
	 *
	 * <p>形态选择照原语义：玩家舰是「自己 ×timeMult + 世界 ×1/timeMult」的成对子弹时间
	 * （{@link Moci_TimeFlow#openBulletTime}），非玩家舰只有船侧（{@link Moci_TimeFlow#openShip}）。
	 * 世界半的**有无是结构性的**（换乘会让同一台系统在两种形态间翻转），所以形态变化必须重建句柄，
	 * 只 {@code set()} 无法凭空多出或少掉世界那一半。</p>
	 */
	private void applyTimeFlow(ShipAPI ship, float timeMult) {
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null || ship == null) {
			// 不在战斗里（比如装备界面预览）时没有时间流可申请，也就不该留下任何修饰符。
			releaseTimeFlow("没有战斗引擎");
			return;
		}
		boolean bullet = ship == engine.getPlayerShip();
		if (timeHandle != null && timeHandle.active() && timeHandleIsBullet == bullet) {
			timeHandle.set(timeMult);
			return;
		}
		releaseTimeFlow("时间申请形态变化");
		timeHandle = bullet
				? Moci_TimeFlow.openBulletTime(ship, TIME_HANDLE_ID, timeMult)
				: Moci_TimeFlow.openShip(ship, TIME_HANDLE_ID, timeMult);
		timeHandleIsBullet = bullet;
	}

	/** 收回时间申请；幂等，重复调用无副作用。 */
	private void releaseTimeFlow(String why) {
		Moci_TimeFlow.Handle handle = timeHandle;
		timeHandle = null;
		if (handle == null) {
			return;
		}
		if (Moci_TimeFlow.TRACE) {
			Global.getLogger(Moci_FluxArmor.class).info("[MOCI-TIME] Moci_FluxArmor 收回时间申请 原因=" + why);
		}
		handle.release();
	}

	public void unapply(MutableShipStatsAPI stats, String id) {
		// 移除所有状态修饰器；时间倍率只经句柄收回，不再直接触碰 time mult 属性表
		releaseTimeFlow("unapply");

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