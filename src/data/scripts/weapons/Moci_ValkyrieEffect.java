package data.scripts.weapons;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.Global;
import java.awt.Color;
import java.util.List;
import org.lazywizard.lazylib.MathUtils;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 女武神分离式武器平台特效
 * 功能：控制子模块的锁定与分离机制
 * 
 * 主要特性：
 * 1. 平时锁定子模块，使其固定在母舰上作为装饰
 * 2. 战斗中随机分离子模块，使其成为独立战斗单位
 * 3. 控制特定武器的角度
 * 4. 强制召回子模块上的战机
 */
public class Moci_ValkyrieEffect implements EveryFrameWeaponEffectPlugin {
	
	// 分离触发的时间间隔（0.08秒到5秒之间随机）
	private IntervalUtil interval = new IntervalUtil(0.08f, 5f);
	
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
		
		// 获取母舰的所有子模块
		List<ShipAPI> children = ship.getChildModulesCopy();
		interval.advance(amount);
		
		if (children != null) {
			for (ShipAPI m : children) {
				// 跳过没有停靠槽位的模块
				if (m.getStationSlot() == null) {
					continue;
				}
				
				// 如果模块控制未锁定，则锁定它
				if (m.controlsLocked() == false) {
					m.setControlsLocked(true);  // 锁定控制
					m.setShipAI(null);           // 移除AI，防止自主行动
				} else {
					// 控制特定武器的角度
					for (WeaponAPI wep : m.getAllWeapons()) {
						if (wep.getSlot().getId().equals("TRUE_GUN2")) {
							// 将武器角度设置为模块朝向+90度（垂直方向）
							wep.setCurrAngle(m.getFacing() + 90f);
						}
					}
				}
				
				// 分离触发条件：附近有敌人 && 时间间隔已过 && 30%随机概率
				if (ship.areAnyEnemiesInRange() && interval.intervalElapsed() && Math.random() > 0.70f) {
					// 播放分离音效（带随机音调变化）
					float variance = MathUtils.getRandomNumberInRange(-0.2f, 0.2f);
					Global.getSoundPlayer().playSound("disabled_small_crit", 1f + variance, 1f, m.getLocation(), m.getVelocity());
					
					// 生成白色烟雾粒子特效
					engine.addNebulaSmokeParticle(
						m.getLocation(), 
						m.getVelocity(), 
						40f * (0.75f + (float) Math.random() * 0.5f),  // 大小：30-50
						3f + 1f * (float) Math.random() * 2f,          // 持续时间：3-5秒
						0f,
						0f,
						1f,
						Color.white
					);
					
					// 执行分离操作
					m.resetDefaultAI();              // 重置AI，使其可以自主战斗
					if (m.isRetreating()) {
						m.setRetreating(false, false);  // 取消撤退状态
					}
					m.setControlsLocked(false);      // 解除控制锁定
					m.setStationSlot(null);          // 清除停靠槽位，成为独立单位
					
					new Color(155, 155, 155, 200);
				}
				
				// 确保模块有克隆的停靠槽位规格
				m.ensureClonedStationSlotSpec();
				
				// 强制召回子模块上的所有战机
				if (m.getAllWings() == null || m.getAllWings().size() == 0) {
					continue;
				}
				for (ShipAPI fighter : m.getAllWings().get(0).getWingMembers()) {
					m.getAllWings().get(0).orderReturn(fighter);
				}
			}
		}
	}
}
