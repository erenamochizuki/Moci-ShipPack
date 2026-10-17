package data.shipsystems.scripts;

import java.awt.Color;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 横向推进系统 (Lateral Thrust System)
 * 
 * 参考OrionDeviceStats实现的横向推进系统
 * 通过EveryFrameCombatPlugin持续应用横向推力
 * 
 * @author Moci
 */
public class Moci_LateralThrustStats extends BaseShipSystemScript {

	public static class LateralThrustParams {
		public float thrustAccel = 4000f;
		public float thrustDuration = 0.5f;
		public float thrustRateMult = 2f;
		public float maxSpeedBoost = 800f;
		public float brakingDecelMult = 2f;
		public float brakingThreshold = 2f;
		public float minVelocityForDirection = 10f;
	}
	
	protected LateralThrustParams p = new LateralThrustParams();
	protected boolean thrustPluginCreated = false;
	
	public Moci_LateralThrustStats() {
		p = new LateralThrustParams();
	}
	
	@Override
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		ShipAPI ship = null;
		if (stats.getEntity() instanceof ShipAPI) {
			ship = (ShipAPI) stats.getEntity();
		} else {
			return;
		}
		
		// 系统激活时创建推进插件（只创建一次）
		if (effectLevel >= 1 && !thrustPluginCreated) {
			float thrustAngle = determineThrustDirection(ship);
			Global.getCombatEngine().addPlugin(createThrustPlugin(ship, thrustAngle));
			thrustPluginCreated = true;
		}
		
		// 系统结束时重置状态
		if (state == State.COOLDOWN && thrustPluginCreated) {
			thrustPluginCreated = false;
		}
	}
	
	/**
	 * 确定横向推进方向
	 * 
	 * 逻辑：速度在船头左侧时，向右推进（让飞船向右横移）
	 *       速度在船头右侧时，向左推进（让飞船向左横移）
	 */
	protected float determineThrustDirection(ShipAPI ship) {
		Vector2f velocity = ship.getVelocity();
		float shipFacing = ship.getFacing();
		
		if (velocity.length() < p.minVelocityForDirection) {
			return shipFacing + 90f;
		}
		
		float velocityAngle = (float) Math.toDegrees(Math.atan2(velocity.y, velocity.x));
		float turnDir = Misc.getClosestTurnDirection(velocityAngle, shipFacing);
		
		// 反向推进：速度在左侧时向右推，速度在右侧时向左推
		if (turnDir < 0) {
			// 速度在船头左侧 → 向右推进
			return shipFacing + 90f;
		} else {
			// 速度在船头右侧 → 向左推进
			return shipFacing - 90f;
		}
	}
	
	/**
	 * 创建推进插件 - 核心推进逻辑
	 */
	protected EveryFrameCombatPlugin createThrustPlugin(final ShipAPI ship, final float thrustAngle) {
		return new BaseEveryFrameCombatPlugin() {
			float elapsed = 0f;
			float thrustTime = 0f;
			float brakingTime = 0f;
			boolean thrusting = true;
			boolean braking = false;
			boolean done = false;
			
			@Override
			public void advance(float amount, List<InputEventAPI> events) {
				if (Global.getCombatEngine().isPaused()) return;
				
				elapsed += amount;
				
				String modId = "moci_lateral_thrust";
				ship.getMutableStats().getMaxSpeed().unmodifyFlat(modId);
				float maxSpeedWithoutBonus = ship.getMutableStats().getMaxSpeed().getModifiedValue();

				// === 推进阶段 ===
				if (thrusting) {
					thrustTime += amount * p.thrustRateMult;
					
					// 计算推力衰减
					float mag = (1f - thrustTime) * (1f - thrustTime);
					if (mag > 0) mag = (float) Math.sqrt(mag);
					
					// 计算并应用加速度
					Vector2f acc = Misc.getUnitVectorAtDegreeAngle(thrustAngle);
					acc.scale(p.thrustAccel * mag * amount);
					Vector2f.add(ship.getVelocity(), acc, ship.getVelocity());
					
					// 临时提升最大速度
					ship.getMutableStats().getMaxSpeed().modifyFlat(modId, p.maxSpeedBoost);
					
					// 增加减速能力
					float currentSpeed = ship.getVelocity().length();
					if (currentSpeed > maxSpeedWithoutBonus) {
						float excessSpeed = currentSpeed - maxSpeedWithoutBonus;
						ship.getMutableStats().getDeceleration().modifyFlat(modId, excessSpeed);
					}
					
					// 强制减速指令
					ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
					ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
					ship.giveCommand(ShipCommand.DECELERATE, null, 0);
					
					// 引擎光芒
					ship.getEngineController().extendFlame(this, 1f, 1f, 1f);
					
					if (thrustTime >= 1f) {
						thrusting = false;
						braking = true;
						ship.getMutableStats().getMaxSpeed().unmodify(modId);
					}
				}
				
				// === 制动阶段 ===
				if (braking) {
					brakingTime += amount;
					
					float currentSpeed = ship.getVelocity().length();
					if (currentSpeed > maxSpeedWithoutBonus) {
						float excessSpeed = currentSpeed - maxSpeedWithoutBonus;
						ship.getMutableStats().getDeceleration().modifyFlat(modId, 
							p.brakingDecelMult * excessSpeed);
					}
					
					ship.blockCommandForOneFrame(ShipCommand.ACCELERATE);
					ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
					ship.giveCommand(ShipCommand.DECELERATE, null, 0);
					
					ship.getEngineController().extendFlame(this, 0.5f, 0.5f, 0.5f);
					
					if (brakingTime >= p.brakingThreshold || ship.getVelocity().length() <= maxSpeedWithoutBonus) {
						done = true;
					}
				}
				
				// === 清理 ===
				if (done) {
					Global.getCombatEngine().removePlugin(this);
					ship.getMutableStats().getMaxSpeed().unmodify(modId);
					ship.getMutableStats().getDeceleration().unmodify(modId);
				}
			}
		};
	}
	
	@Override
	public void unapply(MutableShipStatsAPI stats, String id) {
		// 清理
	}
	
	@Override
	public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
		if (ship.getEngineController().isFlamedOut() || ship.getEngineController().isFlamingOut()) {
			return false;
		}
		return super.isUsable(system, ship);
	}
}
