package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;

public class Moci_Hyper_Tpc_OnHitEffect implements BeamEffectPlugin {

	// 硬辐能系统配置
	private static final float FLUX_MULT = 1f / 15f; //   占总幅能比
	private static final float MISSING_TARGET_FLUX_PENALTY = 0.5f; //未命中时产生的幅能值 功能移除
	private float accumulatedFlux = 0f;        // 已累计的硬辐能
	private float previousChargeLevel = 0f;    // 记录上一次充能等级
	private float dynamicTotalFlux; //实际产生幅能值

	// EMP系统配置
	private final IntervalUtil empInterval = new IntervalUtil(0f, 0.2f); // EMP触发间隔控制器
	private boolean wasZero = true;

	// 电弧配置
	private static final float DAMAGE_MULTIPLIER = 0.15f; // 穿盾电弧伤害比例
	private static final float EMP_MULTIPLIER = 0.15f;    // 穿盾电弧EMP伤害比例复制

	// 电弧数量配置
	private static final float FLUX_PER_ARC = 300f;   // 每300幅能增加1个电弧
	private static final float MIN_FLUX = 1400f;      // 最低触发幅能
	private static final float MAX_FLUX = 2600f;      // 最大触发上限
	private static final int BASE_ARCS = 1;           // 基础电弧数量
	private static final int MAX_ARCS = 5;            // 最大电弧数量
	// 光束宽度配置
	private static final float MIN_BEAM_WIDTH = 25f;   // 最窄宽度
	private static final float MAX_BEAM_WIDTH = 70f;   // 最宽宽度
	private static final float WIDTH_RANGE = MAX_BEAM_WIDTH - MIN_BEAM_WIDTH;

	// 电弧颜色配置
	private static final Color FRINGE_COLOR = new Color(115, 100, 255, 255);   // 电弧边缘颜色
	private static final Color CORE_COLOR = new Color(255, 255, 255, 255); // 电弧核心颜色

	// 开火烟雾配置：移植原版 DEMScript 的锥形喷射爆炸（shapedExplosion），括号内为对应的原版参数名
	private static final boolean WITH_SHAPED_EXPLOSION = true;               // 是否启用开火烟雾（原版参数 withShapedExplosion）
	// 分两层：外缘色层（fringeColor）粒子大，内核色层（coreColor）粒子小；两层合计 50 个，与原配置的粒子总数相同
	private static final int SHAPED_EXPLOSION_FRINGE_NUM_PARTICLES = 30;     // 外缘色层的粒子数量（原版参数 shapedExplosionNumParticles）
	private static final int SHAPED_EXPLOSION_CORE_NUM_PARTICLES = 20;       // 内核色层的粒子数量（原版参数 shapedExplosionNumParticles）
	private static final float SHAPED_EXPLOSION_MIN_PARTICLE_DUR = 0.25f;     // 粒子最短存活时间，单位为秒（原版参数 shapedExplosionMinParticleDur）
	private static final float SHAPED_EXPLOSION_MAX_PARTICLE_DUR = 0.85f;     // 粒子最长存活时间，单位为秒（原版参数 shapedExplosionMaxParticleDur）
	private static final float SHAPED_EXPLOSION_FRINGE_MIN_PARTICLE_SIZE = 30f; // 外缘色层粒子的最小起始尺寸（原版参数 shapedExplosionMinParticleSize）
	private static final float SHAPED_EXPLOSION_FRINGE_MAX_PARTICLE_SIZE = 50f; // 外缘色层粒子的最大起始尺寸（原版参数 shapedExplosionMaxParticleSize）
	private static final float SHAPED_EXPLOSION_CORE_MIN_PARTICLE_SIZE = 20f;   // 内核色层粒子的最小起始尺寸（原版参数 shapedExplosionMinParticleSize）
	private static final float SHAPED_EXPLOSION_CORE_MAX_PARTICLE_SIZE = 30f;   // 内核色层粒子的最大起始尺寸（原版参数 shapedExplosionMaxParticleSize）
	private static final float SHAPED_EXPLOSION_ARC = 45f;                   // 喷射扇形的总角度，单位为度，中轴左右各分一半（原版参数 shapedExplosionArc）
	private static final float SHAPED_EXPLOSION_MIN_PARTICLE_VEL = 75f;      // 粒子最小初速度（原版参数 shapedExplosionMinParticleVel）
	private static final float SHAPED_EXPLOSION_MAX_PARTICLE_VEL = 255f;     // 粒子最大初速度（原版参数 shapedExplosionMaxParticleVel）
	private static final float SHAPED_EXPLOSION_END_SIZE_MIN = 2f;           // 粒子结束尺寸的最小倍率，乘在起始尺寸上（原版参数 shapedExplosionEndSizeMin）
	private static final float SHAPED_EXPLOSION_END_SIZE_MAX = 1f;           // 粒子结束尺寸的最大倍率，乘在起始尺寸上（原版参数 shapedExplosionEndSizeMax）
	private static final float SHAPED_EXPLOSION_SCATTER = 100f;              // 出生点沿喷射方向的最大偏移距离（原版参数 shapedExplosionScatter）
	private static final int SHAPED_EXPLOSION_ALPHA = 95;                    // 两层共用的透明度，取自原版参数 shapedExplosionColor 的 alpha；RGB 运行时分别取光束的 fringeColor 与 coreColor
	private static final boolean SHAPED_EXPLOSION_INHERIT_SHIP_VEL = true;   // 是否叠加舰船速度：原版不叠加，这里叠加是为了避免移动开火时烟雾被甩在炮口后面

	// 开火引爆光效配置：移植原版 DEM 鱼雷（dragon.proj）引爆时的几层光效，只有视觉效果，不造成伤害
	// 尺寸类数值统一取原版的 60%，其余数值与原版相同或按原版效果换算；括号内为原版参数名和原版数值
	// 原版在离目标约 700 处引爆，这里在炮口生成，同船多门炮齐射时会叠在一起，太亮可以先调小尺寸或关掉某一层
	// 大闪光：两张不扩散、随时间淡出的光斑贴图，大的用光束外缘色，小的用光束内核色
	private static final boolean WITH_DETONATION_FLASH = true;              // 是否启用大闪光（原版在 useDetailedExplosion 为 true 时生成）
	private static final float DETONATION_FLASH_FRINGE_SIZE = 210f;         // 外圈闪光尺寸（原版 detailedExplosionFlashRadius 350）
	private static final float DETONATION_FLASH_CORE_SIZE = 52.5f;          // 内圈闪光尺寸（原版为 detailedExplosionFlashRadius × 0.25 = 87.5）
	private static final float DETONATION_FLASH_DUR = 1.25f;                // 闪光持续时间，单位为秒（原版 detailedExplosionFlashDuration 1.25）
	private static final float DETONATION_FLASH_FRINGE_BRIGHTNESS = 1f;     // 外圈亮度，范围 0~1（原版 1）
	private static final float DETONATION_FLASH_CORE_BRIGHTNESS = 0.6f;     // 内圈亮度，范围 0~1：原版亮度为 1，但内核色是压暗的灰色（detailedExplosionFlashColorCore [155,155,155]），这里用亮度把光束内核色压到差不多的程度
	// 火花：一次性向四周迸出的小光点，颜色用光束外缘色
	private static final boolean WITH_DETONATION_SPARKS = true;             // 是否启用火花
	private static final int DETONATION_SPARK_COUNT = 100;                  // 火花数量（原版 particleCount 100）
	private static final float DETONATION_SPARK_MIN_SIZE = 2.4f;            // 火花最小尺寸（原版 particleSizeMin 4）
	private static final float DETONATION_SPARK_MAX_SIZE = 4.8f;            // 火花最大尺寸（原版为 particleSizeMin 4 + particleSizeRange 4 = 8，引擎取整后实际为 4~7）
	private static final float DETONATION_SPARK_DUR = 1f;                   // 火花存活时间，单位为秒（原版 particleDuration 1）
	private static final float DETONATION_SPARK_SPAWN_RADIUS = 33.75f;      // 火花出生点离炮口的最大距离（原版 particleSpawnRadius 未配置，取 explosionSpec 的 radius 75 × 0.75 = 56.25）
	private static final float DETONATION_SPARK_SPEED_MULT = 3f;            // 火花速度倍率：每个轴的速度 = 出生点偏移 × 该倍率 × 0~1 的随机数，离炮口越远飞得越快（原版写死为 3）
	// 爆炸火团：引擎标准的爆炸动画，由一批随机爆炸贴图组成，颜色用光束外缘色，引擎会把透明度固定为 50
	private static final boolean WITH_DETONATION_FIREBALL = true;           // 是否启用爆炸火团（原版在 useDetailedExplosion 为 true 时生成）
	private static final float DETONATION_FIREBALL_SIZE = 90f;              // 火团尺寸，越大贴图越多（原版 detailedExplosionRadius 150）
	private static final float DETONATION_FIREBALL_DUR = 1f;                // 火团最长持续时间，单位为秒（原版 particleDuration 1）
	// 小光晕：中心的一小团白光，外圈用光束内核色，内圈固定为白色（原版外圈用顶层 explosionColor 白色，引擎再叠一层白色内圈）
	private static final boolean WITH_DETONATION_GLOW = true;               // 是否启用小光晕
	private static final float DETONATION_GLOW_OUTER_SIZE = 60f;            // 外圈尺寸（原版为顶层 explosionRadius 50 × 2 = 100）
	private static final float DETONATION_GLOW_INNER_SIZE = 15f;            // 内圈尺寸（原版为顶层 explosionRadius 50 × 0.5 = 25）
	private static final float DETONATION_GLOW_OUTER_DUR = 0.5f;            // 外圈持续时间，单位为秒（原版由引擎按尺寸自动计算，尺寸 100 对应 0.5）
	private static final float DETONATION_GLOW_INNER_DUR = 0.25f;           // 内圈持续时间，单位为秒（原版由引擎按尺寸自动计算，尺寸 25 对应 0.25）
	private static final float DETONATION_GLOW_BRIGHTNESS = 1f;             // 两圈共用的亮度，范围 0~1（原版取导弹的伤害倍率，通常为 1）
	private static final boolean DETONATION_INHERIT_SHIP_VEL = true;        // 闪光、火花、火团和小光晕是否叠加舰船速度：原版这几层都停在原地，这里叠加是为了避免移动开火时被甩在炮口后面
	private boolean wasFullBrightness = false;                               // 上一帧光束是否满亮度，用于检测开火上升沿

	@Override
	public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
		// 基础安全校验：暂停状态或无效光束直接返回
		if (engine.isPaused() || beam.getSource() == null) return;
		if (!(beam.getSource() instanceof ShipAPI)) return;

		final ShipAPI ship = (ShipAPI) beam.getSource();
		final WeaponAPI weapon = beam.getWeapon();

		handleFireEffects(engine, beam, ship, weapon);  // 开火瞬间的炮口光效与烟雾
		handleHardFluxGeneration(ship, weapon, engine); // 处理硬辐能生成
		handleEmpEffect(engine, beam);          // 处理EMP效果
		// 动态光束宽度控制
		updateBeamWidth(beam);
	}

	/**
	 * 开火瞬间的炮口光效与烟雾：先生成引爆光效（小光晕、火花、爆炸火团、大闪光），再生成锥形烟雾，顺序与原版 DEM 鱼雷相同。
	 * 引擎里光束亮度 = 充能时为充能进度、FIRING 时恒为 1、冷却时 1→0，一发只有一段满亮度区间。
	 * 在亮度到达 1 的那一帧（与 EMP 电弧同一个门槛）触发，所以每发正好触发一次。
	 * 用上升沿而不是一次性标记：即使引擎在连发之间复用同一个插件实例，也不会漏掉后续几发。
	 */
	private void handleFireEffects(CombatEngineAPI engine, BeamAPI beam, ShipAPI ship, WeaponAPI weapon) {
		// 检测满亮度上升沿：本帧满亮度且上一帧不满，说明这一帧刚刚开火
		final boolean fullBrightness = beam.getBrightness() >= 1f;
		final boolean justFired = fullBrightness && !wasFullBrightness;
		wasFullBrightness = fullBrightness;
		if (!justFired) return;

		// 引擎在调用 advance 之前已把 from 设为本帧炮口位置
		final Vector2f muzzle = beam.getFrom();
		// 视口剔除，余量与原版相同；屏幕外的这一发直接放弃，不会补发
		if (!engine.getViewport().isNearViewport(muzzle, 800f)) return;

		// 颜色取光束当前的外缘色与内核色；取不到时退回本类写死的电弧颜色
		final Color fringeColor = beam.getFringeColor() != null ? beam.getFringeColor() : FRINGE_COLOR;
		final Color coreColor = beam.getCoreColor() != null ? beam.getCoreColor() : CORE_COLOR;

		// 原版鱼雷先调用 explode() 生成引爆光效，再生成锥形烟雾
		spawnDetonation(engine, muzzle, ship, fringeColor, coreColor);

		if (!WITH_SHAPED_EXPLOSION) return;
		// 本武器 turretAngleOffsets / hardpointAngleOffsets 都是 0，光束方向即武器朝向
		final float angle = weapon != null ? weapon.getCurrAngle() : Misc.getAngleInDegrees(muzzle, beam.getTo());
		// 烟雾两层分别用外缘色与内核色，只替换透明度
		final Color fringeSmoke = Misc.setAlpha(fringeColor, SHAPED_EXPLOSION_ALPHA);
		final Color coreSmoke = Misc.setAlpha(coreColor, SHAPED_EXPLOSION_ALPHA);
		// 需要叠加舰船速度时取出当前速度，否则传 null 表示粒子只在世界坐标里飞
		final Vector2f shipVel = SHAPED_EXPLOSION_INHERIT_SHIP_VEL ? ship.getVelocity() : null;

		// 星云粒子是加色混合，内核色小粒子叠在外缘色大粒子上的地方会更亮、更接近白色
		spawnShapedExplosion(engine, muzzle, angle, shipVel, SHAPED_EXPLOSION_FRINGE_NUM_PARTICLES,
				SHAPED_EXPLOSION_FRINGE_MIN_PARTICLE_SIZE, SHAPED_EXPLOSION_FRINGE_MAX_PARTICLE_SIZE, fringeSmoke);
		spawnShapedExplosion(engine, muzzle, angle, shipVel, SHAPED_EXPLOSION_CORE_NUM_PARTICLES,
				SHAPED_EXPLOSION_CORE_MIN_PARTICLE_SIZE, SHAPED_EXPLOSION_CORE_MAX_PARTICLE_SIZE, coreSmoke);
	}

	/**
	 * 生成引爆光效，复刻原版导弹 explode() 与 DamagingExplosion 的视觉部分，按原版顺序依次为：小光晕、火花、爆炸火团、大闪光。
	 * 只调用纯视觉的粒子接口，不生成带伤害的爆炸。各层的尺寸、时长和亮度读取本类的引爆光效常量。
	 *
	 * @param engine      战斗引擎，用于投放粒子
	 * @param loc         引爆位置，即本帧的炮口位置
	 * @param ship        开火的舰船，需要叠加舰船速度时从这里取速度
	 * @param fringeColor 光束外缘色，用于大闪光外圈、火花和爆炸火团
	 * @param coreColor   光束内核色，用于大闪光内圈和小光晕外圈
	 */
	private static void spawnDetonation(CombatEngineAPI engine, Vector2f loc, ShipAPI ship, Color fringeColor, Color coreColor) {
		// 各层共用的基础速度：叠加时取开火这一刻的舰船速度，否则为 0（与原版一样停在原地）
		final Vector2f baseVel = DETONATION_INHERIT_SHIP_VEL ? new Vector2f(ship.getVelocity()) : new Vector2f();

		// 小光晕：两张光斑贴图，外圈用光束内核色，内圈用白色
		if (WITH_DETONATION_GLOW) {
			engine.addHitParticle(loc, baseVel, DETONATION_GLOW_OUTER_SIZE, DETONATION_GLOW_BRIGHTNESS, DETONATION_GLOW_OUTER_DUR, coreColor);
			engine.addHitParticle(loc, baseVel, DETONATION_GLOW_INNER_SIZE, DETONATION_GLOW_BRIGHTNESS, DETONATION_GLOW_INNER_DUR, Color.WHITE);
		}

		// 火花：在炮口周围的圆内随机出生，速度沿出生方向向外，两个轴各乘一次随机数（与原版相同）
		if (WITH_DETONATION_SPARKS) {
			for (int i = 0; i < DETONATION_SPARK_COUNT; i++) {
				final float theta = (float) (Math.random() * 2.0 * Math.PI);
				final float r = (float) Math.random() * DETONATION_SPARK_SPAWN_RADIUS;
				final float offsetX = (float) Math.cos(theta) * r;
				final float offsetY = (float) Math.sin(theta) * r;
				final Vector2f pLoc = new Vector2f(loc.x + offsetX, loc.y + offsetY);
				final Vector2f pVel = new Vector2f(
						offsetX * DETONATION_SPARK_SPEED_MULT * (float) Math.random() + baseVel.x,
						offsetY * DETONATION_SPARK_SPEED_MULT * (float) Math.random() + baseVel.y);
				final float pSize = DETONATION_SPARK_MIN_SIZE + (DETONATION_SPARK_MAX_SIZE - DETONATION_SPARK_MIN_SIZE) * (float) Math.random();
				// 引擎的这个接口不使用亮度参数，火花亮度只由颜色决定，这里固定传 1
				engine.addSmoothParticle(pLoc, pVel, pSize, 1f, DETONATION_SPARK_DUR, fringeColor);
			}
		}

		// 爆炸火团：引擎标准爆炸动画，纯视觉
		if (WITH_DETONATION_FIREBALL) {
			engine.spawnExplosion(loc, baseVel, fringeColor, DETONATION_FIREBALL_SIZE, DETONATION_FIREBALL_DUR);
		}

		// 大闪光：外圈用光束外缘色，内圈用光束内核色
		if (WITH_DETONATION_FLASH) {
			engine.addHitParticle(loc, baseVel, DETONATION_FLASH_FRINGE_SIZE, DETONATION_FLASH_FRINGE_BRIGHTNESS, DETONATION_FLASH_DUR, fringeColor);
			engine.addHitParticle(loc, baseVel, DETONATION_FLASH_CORE_SIZE, DETONATION_FLASH_CORE_BRIGHTNESS, DETONATION_FLASH_DUR, coreColor);
		}
	}

	/**
	 * 生成一层锥形烟雾，算法与原版 DEMScript.spawnShapedExplosion 相同。
	 * 粒子数量、尺寸范围和颜色由调用方按层传入，其余参数读取本类的烟雾常量。
	 *
	 * @param engine        战斗引擎，用于投放粒子
	 * @param muzzle        喷射起点，即本帧的炮口位置
	 * @param angle         喷射中轴角度，单位为度
	 * @param shipVel       需要叠加到粒子速度上的舰船速度，传 null 表示不叠加
	 * @param numParticles  这一层要生成的粒子数量
	 * @param minSize       粒子的最小起始尺寸
	 * @param maxSize       粒子的最大起始尺寸
	 * @param color         这一层粒子的颜色，alpha 已由调用方处理
	 */
	private static void spawnShapedExplosion(CombatEngineAPI engine, Vector2f muzzle, float angle, Vector2f shipVel,
			int numParticles, float minSize, float maxSize, Color color) {
		for (int i = 0; i < numParticles; i++) {
			// 偏角：大偏角做平方压缩，让粒子向中轴聚拢；越偏离中轴速度越低（0.5~1 倍）
			float angleOffset = (float) Math.random();
			if (angleOffset > 0.2f) angleOffset *= angleOffset;
			final float speedMult = 0.5f + 0.5f * (1f - angleOffset);
			// 随机决定偏向中轴的哪一侧，再把 0~1 的偏角映射到扇形半角
			angleOffset *= Math.signum((float) Math.random() - 0.5f);
			angleOffset *= SHAPED_EXPLOSION_ARC / 2f;

			// 出生点沿自身喷射方向偏移 r，random*random 让大部分粒子贴近炮口
			final Vector2f dir = Misc.getUnitVectorAtDegreeAngle(angle + angleOffset);
			final float r = (float) (Math.random() * Math.random()) * SHAPED_EXPLOSION_SCATTER;
			final Vector2f pLoc = new Vector2f(muzzle.x + dir.x * r, muzzle.y + dir.y * r);

			// 初速度在配置区间内随机，再乘上偏角衰减系数；叠加舰船速度可让烟雾跟着船走
			final float speed = (SHAPED_EXPLOSION_MIN_PARTICLE_VEL
					+ (SHAPED_EXPLOSION_MAX_PARTICLE_VEL - SHAPED_EXPLOSION_MIN_PARTICLE_VEL) * (float) Math.random()) * speedMult;
			final Vector2f pVel = new Vector2f(dir.x * speed, dir.y * speed);
			if (shipVel != null) Vector2f.add(pVel, shipVel, pVel);

			// 起始尺寸、存活时间和结束尺寸倍率都各自在配置区间内取随机值
			final float pSize = minSize + (maxSize - minSize) * (float) Math.random();
			final float pDur = SHAPED_EXPLOSION_MIN_PARTICLE_DUR
					+ (SHAPED_EXPLOSION_MAX_PARTICLE_DUR - SHAPED_EXPLOSION_MIN_PARTICLE_DUR) * (float) Math.random();
			final float endSize = SHAPED_EXPLOSION_END_SIZE_MIN
					+ (SHAPED_EXPLOSION_END_SIZE_MAX - SHAPED_EXPLOSION_END_SIZE_MIN) * (float) Math.random();

			// 与原版相同：前 10% 寿命用来渐亮，到 50% 寿命时最亮，之后逐渐变暗；endSize 是结束尺寸倍率
			engine.addNebulaParticle(pLoc, pVel, pSize, endSize, 0.1f, 0.5f, pDur, color);
		}
	}

	/**
	 * 动态更新光束宽度
	 */
	private void updateBeamWidth(BeamAPI beam) {
		// 计算在MIN_FLUX和MAX_FLUX之间的比例
		float fluxRatio = (dynamicTotalFlux - MIN_FLUX) / (MAX_FLUX - MIN_FLUX);

		// 限制比例在0-1之间
		fluxRatio = Math.max(0f, Math.min(1f, fluxRatio));

		// 计算动态宽度
		float currentWidth = MIN_BEAM_WIDTH + (WIDTH_RANGE * fluxRatio);

		// 应用宽度变化
		beam.setWidth(currentWidth);
	}

	/**
	 * 硬辐能生成逻辑：
	 * 根据武器充能等级动态生成硬辐能
	 */
	private void handleHardFluxGeneration(ShipAPI ship, WeaponAPI weapon, CombatEngineAPI engine) {
		final float currentCharge = weapon.getChargeLevel();
		// 目标是否有效
		if (ship == null || ship.getFluxTracker() == null) return;
		// 动态获取舰船最大幅能并计算
		final float maxFlux = ship.getFluxTracker().getMaxFlux();
		dynamicTotalFlux = maxFlux * FLUX_MULT;

		if (currentCharge > 0f) {
			float fluxDelta = dynamicTotalFlux * (currentCharge - previousChargeLevel);

			if (fluxDelta > 0f) {
				ship.getFluxTracker().increaseFlux(fluxDelta, true);
				accumulatedFlux += fluxDelta;

				// 溢出检查
				if (accumulatedFlux >= dynamicTotalFlux) {
					accumulatedFlux = dynamicTotalFlux;
				}
			}
		} else {
			accumulatedFlux = 0f; // 充能中断时重置
		}
		previousChargeLevel = currentCharge; // 记录当前充能状态
	}

	/**
	 * EMP效果
	 */
	private void handleEmpEffect(CombatEngineAPI engine, BeamAPI beam) {
		if (beam.getBrightness() < 1.0f) return;

		// 目标有效性检查
		CombatEntityAPI target = beam.getDamageTarget();
		if (target == null || !engine.isEntityInPlay(target)) return;
		if (target.getLocation() == null) return; // 防止目标坐标为空

		// 时间间隔控制
                         		float dur = beam.getDamage().getDpsDuration();
		if (!wasZero) dur = 0f;
		wasZero = (beam.getDamage().getDpsDuration() <= 0f);

		empInterval.advance(dur);
		if (empInterval.intervalElapsed()) {
			Vector2f point = beam.getRayEndPrevFrame();
			// 动态计算电弧数量
			float effectiveFlux = Math.max(dynamicTotalFlux - MIN_FLUX, 0f);
			int extraArcs = (int) (effectiveFlux / FLUX_PER_ARC);
			int totalArcs = BASE_ARCS + extraArcs;
			totalArcs = Math.min(totalArcs, MAX_ARCS);

			// 根据dynamicTotalFlux范围修正电弧数量
			if (dynamicTotalFlux <= MIN_FLUX) {
				totalArcs = BASE_ARCS;
			} else if (dynamicTotalFlux >= MAX_FLUX) {
				totalArcs = MAX_ARCS;
			}

			// 生成多个电弧
			for (int i = 0; i < totalArcs; i++) {
				engine.spawnEmpArcPierceShields(
						beam.getSource(),
						point,
						target,
						target,
						DamageType.ENERGY,
						dynamicTotalFlux * DAMAGE_MULTIPLIER,
						dynamicTotalFlux * EMP_MULTIPLIER,
						100000f,
						"tachyon_lance_emp_impact",
						beam.getWidth() + 10f,
						FRINGE_COLOR,
						CORE_COLOR
				);
			}
		}
	}
}
/**
 * 护盾穿透判断逻辑：
 * 根据目标硬辐能水平和护盾状态计算穿透概率
 */
	//private boolean shouldPierceShields(ShipAPI target, BeamAPI beam) {
	//	final boolean hitShield = target.getShield() != null
	//			&& target.getShield().isWithinArc(beam.getTo());

		// 基础穿透概率 = 目标硬辐能水平 - 0.1
	//	final float basePierceChance = target.getHardFluxLevel() - 0.1f;
		// 应用目标的护盾穿透抗性修正
	//	final float modifiedPierceChance = basePierceChance
	//			* target.getMutableStats().getDynamic().getValue(Stats.SHIELD_PIERCED_MULT);

	//	return !hitShield || (Math.random() < modifiedPierceChance);
	//