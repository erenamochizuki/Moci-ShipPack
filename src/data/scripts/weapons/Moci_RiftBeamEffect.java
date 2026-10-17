package data.scripts.weapons;

import java.util.Iterator;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * Moci裂隙光束武器特效系统
 * 
 * 关联武器ID: "Moci_rift_beam"
 * 
 * 工作原理：
 * 1. 监控光束武器的发射状态
 * 2. 定期在光束路径上生成"裂隙地雷"
 * 3. 地雷会优先瞄准光束附近的敌方目标（战机/无人机）
 * 4. 如果没有目标，则在光束路径上随机位置生成地雷
 * 
 * 这种设计让光束武器具有了区域控制能力，特别适合对付小型快速目标
 * 
 * 使用方法：
 * 在武器配置文件(.wpn)中添加：
 * "everyFrameEffect":"data.scripts.weapons.Moci_RiftBeamEffect"
 */
public class Moci_RiftBeamEffect implements EveryFrameWeaponEffectPlugin {

	// === 核心参数配置 ===
	public static float TARGET_RANGE = 120f;  // 目标搜索范围：地雷会在光束120单位范围内寻找目标
	public static float RIFT_RANGE = 60f;     // 裂隙生成范围：地雷最远可以偏离光束60单位
	
	// 地雷投射物ID - 需要在游戏数据中定义对应的投射物
	private static final String MINE_PROJECTILE_ID = "Moci_rift_mine";
	
	// === 时间控制系统 ===
	// 地雷生成间隔：0.6-1.0秒随机间隔，比原版更频繁
	protected IntervalUtil interval = new IntervalUtil(0.6f, 1.0f);
	
	/**
	 * 构造函数：初始化随机时间偏移
	 * 防止多个相同武器同时生成地雷，分散生成时机
	 */
	public Moci_RiftBeamEffect() {
		// 设置随机的初始延迟，让每个武器实例的生成时机错开
		interval.setElapsed((float) Math.random() * interval.getIntervalDuration());
	}

	/**
	 * 主要逻辑处理方法 - 每帧调用
	 * 
	 * 处理流程：
	 * 1. 检查光束状态（是否存在、是否完全激活）
	 * 2. 根据时间间隔决定是否生成新的裂隙地雷
	 * 3. 寻找最佳目标位置（优先敌方小型目标）
	 * 4. 在计算出的位置生成地雷
	 * 
	 * @param amount 帧时间间隔
	 * @param engine 战斗引擎接口
	 * @param weapon 武器实例
	 */
	public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
		// === 光束状态检查 ===
		List<BeamAPI> beams = weapon.getBeams();
		if (beams.isEmpty()) return; // 武器没有激活光束时直接返回
		
		BeamAPI beam = beams.get(0); // 获取第一条光束（大多数光束武器只有一条）
		if (beam.getBrightness() < 1f) return; // 光束未完全激活时不生成地雷

		// === 时间间隔控制 ===
		// 使用2.5倍速度推进计时器，让地雷生成更频繁
		interval.advance(amount * 2.5f);
		
		// 只有在时间间隔到达时才生成地雷
		if (interval.intervalElapsed()) {
			// 光束长度检查：太短的光束（<15单位）不生成地雷
			if (beam.getLengthPrevFrame() < 15) return;
			
			// === 目标位置计算 ===
			Vector2f loc; // 最终的地雷生成位置
			
			// 1. 首先尝试寻找光束附近的敌方目标
			CombatEntityAPI target = findTarget(beam, beam.getWeapon(), engine);
			
			if (target == null) {
				// 2. 没有找到目标时，在光束路径上随机选择位置
				loc = pickNoTargetDest(beam, beam.getWeapon(), engine);
			} else {
				// 3. 找到目标时，直接瞄准目标位置
				loc = target.getLocation();
			}
			
			// === 地雷位置精确计算 ===
			// 找到光束线段上最接近目标位置的点作为起始点
			Vector2f from = Misc.closestPointOnSegmentToPoint(beam.getFrom(), beam.getRayEndPrevFrame(), loc);
			
			// 计算从光束到目标的方向向量
			Vector2f to = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(from, loc));
			
			// 限制地雷生成距离，不能超过RIFT_RANGE范围
			to.scale(Math.min(Misc.getDistance(from, loc), RIFT_RANGE));
			
			// 计算最终的地雷生成位置
			Vector2f.add(from, to, to);
			
			// === 生成裂隙地雷 ===
			spawnMine(beam.getSource(), to);
			
			// === 播放音效 ===
			// 播放裂隙生成音效（需要在sounds.json中注册）
			Global.getSoundPlayer().playSound("ui_discovered_entity", 0.8f, 1.2f, 
				to, new Vector2f(0, 0));
		}
	}

	/**
	 * 生成裂隙地雷的核心方法
	 * 
	 * 这个方法创建一个特殊的"地雷"投射物，具有以下特性：
	 * 1. 静止不动（速度为0）
	 * 2. 随机朝向（360度随机角度）
	 * 3. 立即激活（跳过飞行时间）
	 * 4. 友军免疫（不会伤害发射者）
	 * 
	 * @param source 发射源舰船
	 * @param mineLoc 地雷生成位置
	 */
	public void spawnMine(ShipAPI source, Vector2f mineLoc) {
		CombatEngineAPI engine = Global.getCombatEngine();
		
		// === 创建地雷投射物 ===
		// 生成Moci专用的裂隙地雷投射物
		// 参数说明：
		// - source: 发射源舰船
		// - null: 没有关联的武器（因为是特效生成的）
		// - MINE_PROJECTILE_ID: Moci裂隙地雷的投射物ID
		// - mineLoc: 生成位置
		// - (float) Math.random() * 360f: 随机朝向（0-360度）
		// - null: 没有初始速度
		MissileAPI mine = (MissileAPI) engine.spawnProjectile(source, null, 
															  MINE_PROJECTILE_ID, 
															  mineLoc, 
															  (float) Math.random() * 360f, null);
		
		// === 应用伤害修正 ===
		// 如果有发射源，应用舰船的伤害修正（如技能加成、船插效果等）
		if (source != null) {
			// 将地雷视为能量武器类型，应用相应的伤害修正
			Global.getCombatEngine().applyDamageModifiersToSpawnedProjectileWithNullWeapon(
											source, WeaponType.ENERGY, false, mine.getDamage());
		}
		
		// === 地雷行为设置 ===
		
		// 1. 视觉效果设置
		float fadeInTime = 0.08f; // 0.08秒的淡入时间，比原版稍慢
		mine.getVelocity().scale(0); // 将速度设为0，让地雷静止不动
		mine.fadeOutThenIn(fadeInTime); // 先淡出再淡入，创造"凭空出现"的效果
		
		// 2. 生命周期设置
		float liveTime = 0f; // 地雷的"飞行时间"
		
		// 设置地雷立即进入激活状态（跳过飞行阶段）
		// 通过设置飞行时间接近最大值来实现
		mine.setFlightTime(mine.getMaxFlightTime() - liveTime);
		
		// 3. 友军保护设置
		mine.addDamagedAlready(source); // 将发射源添加到"已伤害"列表，防止地雷伤害自己
		mine.setNoMineFFConcerns(true); // 设置为友军免疫地雷，不会被友军触发
	}

	/**
	 * 无目标时的地雷位置选择算法
	 * 
	 * 当光束附近没有敌方目标时，使用此方法在光束路径上随机选择一个位置生成地雷
	 * 
	 * 算法逻辑：
	 * 1. 在光束长度的30%-100%范围内随机选择一个点（比原版更靠前）
	 * 2. 在该点周围RIFT_RANGE范围内进一步随机化位置
	 * 3. 这样既保证地雷在光束路径附近，又增加了随机性
	 * 
	 * @param beam 光束实例
	 * @param weapon 武器实例
	 * @param engine 战斗引擎（未使用，但保持接口一致性）
	 * @return 计算出的地雷生成位置
	 */
	public Vector2f pickNoTargetDest(BeamAPI beam, WeaponAPI weapon, CombatEngineAPI engine) {
		// === 获取光束基本信息 ===
		Vector2f from = beam.getFrom();           // 光束起点
		Vector2f to = beam.getRayEndPrevFrame();  // 光束终点（上一帧）
		float length = beam.getLengthPrevFrame(); // 光束长度（上一帧）
		
		// === 计算光束路径上的随机点 ===
		// 生成0.3-1.0之间的随机系数，让地雷更多出现在光束前段
		float f = 0.3f + (float) Math.random() * 0.7f;
		
		// 计算光束方向的单位向量
		Vector2f loc = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(from, to));
		
		// 按随机系数缩放，得到光束路径上的一个点
		loc.scale(length * f);
		
		// 将相对位置转换为绝对位置
		Vector2f.add(from, loc, loc);
		
		// === 添加随机偏移 ===
		// 在计算出的点周围RIFT_RANGE范围内随机选择最终位置
		// 这增加了地雷分布的随机性，避免过于规律的排列
		return Misc.getPointWithinRadius(loc, RIFT_RANGE);
	}

	/**
	 * 智能目标搜索算法
	 * 
	 * 在光束路径附近寻找合适的敌方目标，优先瞄准小型快速单位
	 * 
	 * 目标筛选条件：
	 * 1. 必须是导弹或舰船
	 * 2. 必须是敌方单位
	 * 3. 如果是舰船，必须是战机或无人机
	 * 4. 必须在光束TARGET_RANGE范围内
	 * 
	 * @param beam 光束实例
	 * @param weapon 武器实例
	 * @param engine 战斗引擎
	 * @return 找到的目标实体，如果没有合适目标则返回null
	 */
	public CombatEntityAPI findTarget(BeamAPI beam, WeaponAPI weapon, CombatEngineAPI engine) {
		// === 搜索区域设置 ===
		Vector2f to = beam.getRayEndPrevFrame(); // 以光束终点为搜索中心
		
		// 获取搜索区域内的所有对象迭代器
		// 搜索范围：RIFT_RANGE * 2.5 的正方形区域（比原版更大）
		Iterator<Object> iter = Global.getCombatEngine().getAllObjectGrid().getCheckIterator(to,
																			RIFT_RANGE * 2.5f, RIFT_RANGE * 2.5f);
		
		// === 目标筛选准备 ===
		int owner = weapon.getShip().getOwner(); // 获取武器所属方
		WeightedRandomPicker<CombatEntityAPI> picker = new WeightedRandomPicker<CombatEntityAPI>(); // 随机选择器
		
		// === 遍历搜索区域内的所有对象 ===
		while (iter.hasNext()) {
			Object o = iter.next();
			
			// 1. 类型过滤：只考虑导弹和舰船
			if (!(o instanceof MissileAPI) &&
					!(o instanceof ShipAPI)) continue;
			
			CombatEntityAPI other = (CombatEntityAPI) o;
			
			// 2. 阵营过滤：跳过友方单位
			if (other.getOwner() == owner) continue;
			
			// 3. 舰船类型过滤：只瞄准战机和无人机
			if (other instanceof ShipAPI) {
				ShipAPI ship = (ShipAPI) other;
				// 跳过大型舰船，只瞄准小型快速单位
				if (!ship.isFighter() && !ship.isDrone()) continue;
			}
			
			// === 距离检查 ===
			// 计算目标的有效半径（考虑碰撞体积）
			float radius = Misc.getTargetingRadius(to, other, false);
			
			// 找到光束线段上最接近目标的点
			Vector2f p = Misc.closestPointOnSegmentToPoint(beam.getFrom(), beam.getRayEndPrevFrame(), other.getLocation());
			
			// 计算实际距离（减去目标半径）
			float dist = Misc.getDistance(p, other.getLocation()) - radius;
			
			// 4. 距离过滤：超出TARGET_RANGE的目标不考虑
			if (dist > TARGET_RANGE) continue;
			
			// === 将合格目标添加到选择器 ===
			// 所有合格目标权重相等，随机选择一个
			picker.add(other);
		}
		
		// 从所有合格目标中随机选择一个返回
		// 如果没有合格目标，返回null
		return picker.pick();
	}
}