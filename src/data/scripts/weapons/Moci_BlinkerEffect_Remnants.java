package data.scripts.weapons;

import java.util.Random;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

public class Moci_BlinkerEffect_Remnants implements EveryFrameWeaponEffectPlugin {

	// ===== 核心参数 ===== //
	private float elapsed = 0f;                // 累积时间计数器
	private boolean isActive = true;           // 动画激活状态
	private int currentFrame = 0;               // 当前动画帧索引

	// 帧率映射参数
	private static final float MAX_FPS = 30f;   // 最快速度（当frameRate=0时）
	private static final float MIN_FPS = 1f;    // 最慢速度（当frameRate=1时）

	@Override
	public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
		if (engine.isPaused() || weapon.getAnimation() == null) return;

		// 获取动画参数
		int totalFrames = weapon.getAnimation().getNumFrames();
		if (totalFrames < 1) return;

		ShipAPI ship = weapon.getShip();

		// ===== 状态检测 ===== //
		checkShipStates(ship);

		// ===== 帧更新逻辑 ===== //
		if (shouldAnimate(ship)) {
			updateAnimation(amount, weapon, totalFrames);
		} else {
			resetAnimation(weapon);
		}
	}

	/**
	 * 检测舰船特殊状态
	 */
	private void checkShipStates(ShipAPI ship) {
		// 残骸状态强制关闭
		if (ship.isHulk()) {
			isActive = false;
			return;
		}

		// 排散状态关闭动画
		if (ship.getFluxTracker().isVenting()) {
			isActive = false;
			return;
		}

		// 过载状态随机激活（25%概率）
		if (ship.getFluxTracker().isOverloaded()) {
			isActive = (new Random().nextInt(4) == 3);
			return;
		}

		isActive = true; // 默认激活状态
	}

	/**
	 * 判断是否需要播放动画
	 */
	private boolean shouldAnimate(ShipAPI ship) {
		return isActive && ship.isAlive() && !ship.isHulk();
	}

	/**
	 * 获取并处理帧率参数
	 */
	private float getAdjustedFrameRate(WeaponAPI weapon) {
		// 直接从动画数据获取帧率参数
		float frameRate = weapon.getAnimation().getFrameRate();
		// 钳制参数到合法范围[0,1]
		return Math.max(0f, Math.min(1f, frameRate));
	}

	/**
	 * 将frameRate转换为实际帧间隔
	 */
	private float calculateFrameInterval(float frameRate) {
		// 线性映射公式：fps = MAX_FPS - (MAX_FPS - MIN_FPS) * frameRate
		float actualFPS = MAX_FPS - (MAX_FPS - MIN_FPS) * frameRate;
		return 1f / actualFPS;
	}

	/**
	 * 更新动画帧
	 */
	private void updateAnimation(float deltaTime, WeaponAPI weapon, int totalFrames) {
		// 获取处理后的帧率参数
		float frameRate = getAdjustedFrameRate(weapon);
		float frameInterval = calculateFrameInterval(frameRate);

		elapsed += deltaTime;

		// 累积时间超过间隔时推进动画
		while (elapsed >= frameInterval) {
			currentFrame = (currentFrame + 1) % totalFrames;
			elapsed -= frameInterval;
		}

		weapon.getAnimation().setFrame(currentFrame);
	}

	/**
	 * 重置动画状态
	 */
	private void resetAnimation(WeaponAPI weapon) {
		elapsed = 0f;
		currentFrame = 0;
		weapon.getAnimation().setFrame(0);
	}
}