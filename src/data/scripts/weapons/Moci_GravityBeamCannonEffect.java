package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.RippleDistortion;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicLensFlare;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * 重力波光束炮武器特效系统
 * 
 * 关联武器ID: "Moci_GravityWaveCannon"
 * 
 * 功能特性：
 * 1. 充能阶段：从武器开火点到所有LIGHT_前缀武器槽创建重力场电弧
 * 2. 重力光晕：三层渐变光晕效果（外圈深紫色、中间蓝紫色、内核白色）
 * 3. 光束布雷：光束击中目标时围绕目标生成重力异常地雷
 * 4. 空间扭曲：沿光束路径创建波形扭曲效果
 * 5. 重力场：在光束源点生成负粒子效果
 * 6. 路径电弧：沿光束路径的连贯电弧效果（可通过ENABLE_PATH_ARCS控制）
 * 7. 路径地雷：沿光束路径的连贯地雷链（可通过ENABLE_PATH_MINES控制）
 * 
 * 控制开关：
 * - ENABLE_PATH_MINES: 控制是否生成路径地雷链
 * - ENABLE_PATH_ARCS: 控制是否生成路径连贯电弧
 */
public class Moci_GravityBeamCannonEffect implements EveryFrameWeaponEffectPlugin, BeamEffectPlugin {

    // === 功能控制开关 ===
    private static final boolean ENABLE_PATH_MINES = false; // 控制是否生成路径地雷
    private static final boolean ENABLE_PATH_ARCS = true; // 控制是否生成路径电弧

    // 充能阶段参数
    private static final float CHARGE_ARC_INTERVAL = 0.25f; // 充能电弧间隔（增加到0.25秒）
    private static final float CHARGE_DURATION = 1.2f; // 充能持续时间

    // 重力波颜色定义 - 深紫色到蓝紫色渐变
    private static final Color GRAVITY_DARK = new Color(75, 0, 130, 100); // 深紫色
    private static final Color GRAVITY_LIGHT = new Color(138, 43, 226, 80); // 蓝紫色
    private static final Color GRAVITY_CORE_DARK = new Color(100, 20, 150, 140); // 深紫核心
    private static final Color GRAVITY_CORE_LIGHT = new Color(180, 100, 255, 120); // 亮紫核心

    // 重力光晕颜色定义
    private static final Color FLARE_OUTER = new Color(75, 0, 130, 80); // 外圈深紫色
    private static final Color FLARE_MIDDLE = new Color(138, 43, 226, 100); // 中间蓝紫色
    private static final Color FLARE_INNER = new Color(255, 255, 255, 200); // 内核白色

    // 光晕尺寸参数
    private static final float FLARE_BASE_SIZE = 20f; // 基础光晕尺寸
    private static final float FLARE_MAX_SIZE = 100f; // 最大光晕尺寸

    // 光束布雷系统参数（参考RiftCascadeEffect）
    private static final int MAX_GRAVITY_MINES = 12; // 最大重力地雷数量
    private static final float UNUSED_RANGE_PER_SPAWN = 75f; // 每个地雷对应的未使用射程
    private static final float MINE_SPACING = 125f; // 地雷间距
    private static final float MINE_SPAWN_INTERVAL = 0.08f; // 地雷生成间隔
    private static final String GRAVITY_ANOMALY_ID = "Moci_GravityWaveCannon_minelayer"; // 重力异常投射物ID

    // 扭曲效果参数（路径扭曲已删除，保留其他扭曲效果使用的参数）

    // 负粒子效果参数
    private static final Color NEGATIVE_GRAVITY_COLOR = new Color(75, 0, 130, 100); // 重力负粒子颜色

    // 充能系统状态
    private float effectLevel = 0f; // 充能进度 (0-1)
    private final IntervalUtil chargeArcTimer = new IntervalUtil(CHARGE_ARC_INTERVAL, CHARGE_ARC_INTERVAL);
    private final IntervalUtil flareTimer = new IntervalUtil(0.1f, 0.1f); // 光晕生成间隔
    private boolean wasCharging = false; // 用于检测充能状态变化

    // 新增fx贴图特效状态
    private float fx1Size = 0f; // Moci_Nadesico_fx_1 当前尺寸
    private float fx3Size = 0f; // Moci_Nadesico_fx_3 当前尺寸
    private float fx2Rotation = 0f; // Moci_Nadesico_fx_2 旋转角度
    private float fx4Rotation = 0f; // Moci_Nadesico_fx_4 旋转角度
    private float fx2Alpha = 0f; // Moci_Nadesico_fx_2 透明度
    private float fx4Alpha = 0f; // Moci_Nadesico_fx_4 透明度
    private final IntervalUtil fxBlinkTimer = new IntervalUtil(0.15f, 0.25f); // fx闪烁间隔

    // 光束效果计时器
    private final IntervalUtil negativeParticleTimer = new IntervalUtil(0.1f, 0.15f);
    private final IntervalUtil firePointRippleTimer = new IntervalUtil(0.5f, 0.5f); // 开火点涟漪间隔
    private final IntervalUtil beamArcTimer = new IntervalUtil(0.2f, 0.3f); // 光束路径电弧间隔

    // 光束布雷状态变量（参考RiftCascadeEffect）
    private Vector2f arcFrom = null; // 电弧起点位置
    private Vector2f prevMineLoc = null; // 上一个地雷位置
    private boolean doneSpawningMines = false; // 是否完成地雷生成
    private float spawned = 0; // 已生成地雷数量
    private int numToSpawn = 0; // 需要生成的地雷总数
    private float untilNextSpawn = 0; // 距离下次生成的时间
    private float spawnDir = 0; // 地雷生成方向

    // 路径地雷生成状态变量（独立于命中目标的地雷）
    private final IntervalUtil pathMineTimer = new IntervalUtil(1.5f, 2.0f); // 路径地雷生成间隔（较长，因为一次生成完整链）
    private boolean pathMinesGenerated = false; // 是否已生成路径地雷链

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused() || weapon == null || weapon.getShip() == null)
            return;

        ShipAPI ship = weapon.getShip();
        boolean isCharging = weapon.isFiring() && weapon.getCooldownRemaining() == 0;
        
        // 检查武器是否在充能或发射阶段（effectLevel > 0 且还未完全发射）
        boolean isWeaponActive = effectLevel > 0 && isCharging;

        // 禁用战术系统（在充能和发射阶段）
        if (isWeaponActive) {
            // 禁用战术系统
            ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
            
            // 为玩家舰船添加状态栏提示
            if (ship == engine.getPlayerShip()) {
                String statusText;
                if (effectLevel < 1f) {
                    statusText = "重力波炮 启动中 - 禁用战术系统";
                } else {
                    statusText = "重力波炮 发射中 - 禁用战术系统";
                }
                engine.maintainStatusForPlayerShip(
                    "Moci_GravityBeamCannon_SystemBlock",
                    "graphics/icons/hullsys/fortress_shield.png",
                    "重力波炮",
                    statusText,
                    true // 负面效果，显示为红色
                );
            }
        }
        
        // 处理充能阶段
        if (isCharging) {
            // 更新充能进度
            effectLevel = Math.min(1f, effectLevel + amount / CHARGE_DURATION);

            // 更新fx贴图特效状态（已移动到相位穿越系统）
            // updateFxEffects(amount, effectLevel);

            // 控制光晕生成频率，避免每帧叠加
            flareTimer.advance(amount);
            if (flareTimer.intervalElapsed()) {
                createGravityChargingFlare(engine, weapon, effectLevel);
            }

            chargeArcTimer.advance(amount);
            if (chargeArcTimer.intervalElapsed()) {
                createGravityChargingArcs(engine, weapon, ship);
            }

            // 渲染fx贴图特效（已移动到相位穿越系统）
            // renderFxEffects(engine, weapon);

            wasCharging = true;
        } else {
            // 重置充能状态
            if (wasCharging) {
                effectLevel = 0f;
                wasCharging = false;
                // 重置fx特效状态（已移动到相位穿越系统）
                // resetFxEffects();
            }
            chargeArcTimer.setElapsed(0f);
            flareTimer.setElapsed(0f);
        }
    }

    /**
     * 光束效果处理 - 参考RiftCascadeEffect和HWIIonLanceEffect
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused() || beam == null)
            return;

        // 如果光束刚开始激活，重置路径地雷状态
        if (beam.getBrightness() >= 1f && !pathMinesGenerated) {
            pathMinesGenerated = false; // 重置路径地雷生成状态
        }

        // 生成负粒子效果
        negativeParticleTimer.advance(amount);
        if (negativeParticleTimer.intervalElapsed()) {
            spawnGravityNegativeParticles(engine, beam);
        }

        // 沿光束路径创建扭曲效果已删除

        // 沿光束路径创建连贯电弧效果（可控制开关）
        if (ENABLE_PATH_ARCS) {
            beamArcTimer.advance(amount);
            if (beamArcTimer.intervalElapsed() && beam.getBrightness() >= 1f) {
                createBeamPathContinuousArcs(beam, engine);
            }
        }

        // 沿光束路径生成连贯地雷链（独立于命中目标的地雷，可控制开关）
        if (ENABLE_PATH_MINES) {
            pathMineTimer.advance(amount);
            if (pathMineTimer.intervalElapsed() && beam.getBrightness() >= 1f && !pathMinesGenerated) {
                createContinuousPathMineChain(beam, engine);
                pathMinesGenerated = true; // 标记已生成，避免重复生成
            }
        }

        // 光束未完全激活时不生成地雷，并重置路径地雷状态
        if (beam.getBrightness() < 1f) {
            if (ENABLE_PATH_MINES) {
                pathMinesGenerated = false; // 重置路径地雷生成状态，允许下次重新生成
            }
            return;
        }

        // 已完成地雷生成时直接返回
        if (doneSpawningMines)
            return;

        // === 计算地雷生成数量（参考RiftCascadeEffect） ===
        if (numToSpawn <= 0 && beam.getDamageTarget() != null) {
            float range = beam.getWeapon().getRange(); // 武器射程
            float length = beam.getLengthPrevFrame(); // 光束长度

            // 根据未使用的射程计算地雷数量
            numToSpawn = (int) ((range - length) / UNUSED_RANGE_PER_SPAWN) + 1;
            if (numToSpawn > MAX_GRAVITY_MINES) {
                numToSpawn = MAX_GRAVITY_MINES; // 限制最大数量
            }
            untilNextSpawn = 0f;
        }

        // === 地雷生成时机控制 ===
        untilNextSpawn -= amount;
        if (untilNextSpawn > 0)
            return; // 还未到生成时间

        ShipAPI ship = beam.getSource();
        boolean spawnedMine = false;

        // === 地雷生成逻辑（参考RiftCascadeEffect） ===
        if (beam.getLength() > beam.getWeapon().getRange() - 10f) {
            // 情况1：光束达到最大射程时，在光束末端生成地雷
            float angle = Misc.getAngleInDegrees(beam.getFrom(), beam.getRayEndPrevFrame());
            Vector2f loc = Misc.getUnitVectorAtDegreeAngle(angle);
            loc.scale(beam.getLength());
            Vector2f.add(loc, beam.getFrom(), loc);

            spawnGravityMine(ship, loc);
            spawnedMine = true;
        } else if (beam.getDamageTarget() != null) {
            // 情况2：光束击中目标时，围绕目标生成地雷
            Vector2f arcTo = getNextGravityMineLocation(engine, beam, MINE_SPACING);
            float dist = Misc.getDistance(arcFrom, arcTo);

            // 在地雷之间生成重力电弧连接
            if (dist < MINE_SPACING * 2f) {
                engine.spawnEmpArcVisual(
                        arcFrom, null, arcTo, null, beam.getWidth(),
                        GRAVITY_DARK, GRAVITY_CORE_LIGHT);
            }

            spawnGravityMine(ship, arcTo);
            spawnedMine = true;
            arcFrom = arcTo; // 更新电弧起点
        }

        // === 更新生成状态 ===
        untilNextSpawn = MINE_SPAWN_INTERVAL;
        if (spawnedMine) {
            spawned++;
            if (spawned >= numToSpawn) {
                doneSpawningMines = true; // 完成所有地雷生成
            }
        }
    }

    /**
     * 生成重力负粒子效果 - 沿光束路径分布
     */
    private void spawnGravityNegativeParticles(CombatEngineAPI engine, BeamAPI beam) {
        float length = beam.getLengthPrevFrame();
        if (length <= 10f)
            return; // 光束太短时不生成

        ShipAPI ship = beam.getSource();
        Vector2f from = beam.getFrom();
        Vector2f to = beam.getRayEndPrevFrame();
        Color color = NEGATIVE_GRAVITY_COLOR; // 深紫色负粒子
        float sizeMult = 0.8f; // 尺寸倍数

        // 计算沿光束路径生成负粒子的数量
        int particleCount = Math.max(3, (int) (length / 150f)); // 每150单位至少1个粒子
        particleCount = Math.min(particleCount, 8); // 最多8个粒子

        for (int i = 0; i < particleCount; i++) {
            float rampUp = 0.3f + 0.2f * (float) Math.random(); // 渐入时间
            float dur = 1.2f + 0.8f * (float) Math.random(); // 持续时间
            float size = 60f + 40f * (float) Math.random(); // 粒子大小
            size *= sizeMult;

            // 沿光束路径随机分布
            float ratio = (float) Math.random(); // 0-1之间的随机位置
            Vector2f beamPos = new Vector2f(
                    from.x + (to.x - from.x) * ratio,
                    from.y + (to.y - from.y) * ratio);

            // 在光束路径点周围添加随机偏移
            Vector2f loc = Misc.getPointWithinRadius(beamPos, size * 0.6f);

            // 随机化负粒子颜色（保持透明度不变）
            Color randomColor = getRandomGravityColor();
            engine.addNegativeParticle(loc, ship.getVelocity(), size, rampUp / dur, dur, randomColor);
        }

        // 光束亮度=1时，在开火点生成涟漪扭曲（控制频率）
        if (beam.getBrightness() >= 1.0f) {
            firePointRippleTimer.advance(Global.getCombatEngine().getElapsedInLastFrame());
            if (firePointRippleTimer.intervalElapsed()) {
                createFirePointRipple(from, engine);
            }
        }
    }

    /**
     * 在开火点创建涟漪扭曲效果
     */
    private void createFirePointRipple(Vector2f firePoint, CombatEngineAPI engine) {
        RippleDistortion ripple = new RippleDistortion();
        ripple.setLocation(firePoint);
        ripple.setIntensity(40f);
        ripple.setLifetime(1.0f);
        ripple.setFrameRate(60f / 1.0f);
        ripple.setCurrentFrame(0f);
        ripple.setSize(80f);
        ripple.fadeInSize(0.2f);
        ripple.fadeOutIntensity(1.0f);
        ripple.flip(Math.random() > 0.5f);
        DistortionShader.addDistortion(ripple);
    }

    /**
     * 计算下一个重力地雷的生成位置 - 使用RiftCascadeEffect的精确位置计算
     */
    private Vector2f getNextGravityMineLocation(CombatEngineAPI engine, BeamAPI beam, float perSpawn) {
        CombatEntityAPI target = beam.getDamageTarget();
        float radiusOverride = -1f;
        
        // 处理空间站模块的特殊情况
        if (target instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) target;
            if (ship.getParentStation() != null && ship.getStationSlot() != null) {
                radiusOverride = Misc.getDistance(beam.getRayEndPrevFrame(), ship.getParentStation().getLocation()) + 0f;
                target = ship.getParentStation();
            }
        }

        // === 初始化电弧起点和生成方向 ===
        if (arcFrom == null) {
            arcFrom = new Vector2f(beam.getRayEndPrevFrame());

            float beamAngle = Misc.getAngleInDegrees(beam.getFrom(), beam.getRayEndPrevFrame());
            float beamSourceToTarget = Misc.getAngleInDegrees(beam.getFrom(), target.getLocation());

            // 计算地雷生成方向（顺时针或逆时针）
            spawnDir = Misc.getClosestTurnDirection(beamAngle, beamSourceToTarget);
            if (spawnDir == 0)
                spawnDir = 1;

            // 检查是否需要立即计算下一个位置
            boolean computeNextLoc = false;
            if (prevMineLoc != null) {
                float dist = Misc.getDistance(arcFrom, prevMineLoc);
                if (dist < perSpawn) {
                    perSpawn -= dist;
                    computeNextLoc = true;
                }
            }
            if (!computeNextLoc) {
                return arcFrom;
            }
        }

        // === 计算目标参数 ===
        Vector2f targetLoc = target.getLocation();
        float targetRadius = target.getCollisionRadius();
        if (radiusOverride >= 0) {
            targetRadius = radiusOverride;
        }

        // 检查是否击中护盾
        boolean hitShield = target.getShield() != null && target.getShield().isWithinArc(beam.getRayEndPrevFrame());
        if (hitShield) perSpawn *= 0.67f; // 击中护盾时减少间距

        // === 计算圆形轨迹上的下一个位置 ===
        float prevAngle = Misc.getAngleInDegrees(targetLoc, arcFrom);
        float anglePerSegment = 360f * perSpawn / (3.14f * 2f * targetRadius);
        if (anglePerSegment > 90f) anglePerSegment = 90f;
        float angle = prevAngle + anglePerSegment * spawnDir;

        Vector2f arcTo = Misc.getUnitVectorAtDegreeAngle(angle);
        arcTo.scale(targetRadius);
        Vector2f.add(targetLoc, arcTo, arcTo);

        // === 使用RiftCascadeEffect的精确半径计算 ===
        float actualRadius = Global.getSettings().getTargetingRadius(arcTo, target, hitShield);
        if (radiusOverride >= 0) {
            actualRadius = radiusOverride;
        }
        if (!hitShield) {
            actualRadius += -30f + 50f * (float) Math.random();
        } else {
            actualRadius += -30f + 50f * (float) Math.random();
        }

        arcTo = Misc.getUnitVectorAtDegreeAngle(angle);
        arcTo.scale(actualRadius);
        Vector2f.add(targetLoc, arcTo, arcTo);

        // === 船体边界贴合优化（RiftCascadeEffect的精确定位） ===
        if (target instanceof ShipAPI && !hitShield) {
            ShipAPI ship = (ShipAPI) target;
            BoundsAPI bounds = ship.getExactBounds();
            if (bounds != null) {
                Vector2f best = null;
                float bestDist = Float.MAX_VALUE;
                for (SegmentAPI segment : bounds.getSegments()) {
                    float test = Misc.getDistance(segment.getP1(), arcTo);
                    if (test < bestDist) {
                        bestDist = test;
                        best = segment.getP1();
                    }
                }
                if (best != null) {
                    Object o = Global.getSettings().getWeaponSpec(GRAVITY_ANOMALY_ID).getProjectileSpec();
                    if (o instanceof MissileSpecAPI) {
                        MissileSpecAPI spec = (MissileSpecAPI) o;
                        float explosionRadius = (float) spec.getBehaviorJSON().optJSONObject("explosionSpec").optDouble("coreRadius");
                        float sizeMult = getGravityMineSizeMult();
                        explosionRadius *= sizeMult;
                        
                        Vector2f dir = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(best, arcTo));
                        dir.scale(explosionRadius * 0.9f);
                        Vector2f.add(best, dir, dir);
                        arcTo = dir;
                    }
                }
            }
        }

        return arcTo;
    }

    /**
     * 生成重力地雷 - 参考RiftCascadeEffect
     */
    private void spawnGravityMine(ShipAPI source, Vector2f mineLoc) {
        CombatEngineAPI engine = Global.getCombatEngine();

        // 生成重力异常投射物
        MissileAPI mine = (MissileAPI) engine.spawnProjectile(source, null,
                GRAVITY_ANOMALY_ID,
                mineLoc,
                (float) Math.random() * 360f, null);

        if (mine == null)
            return; // 如果地雷ID不存在，直接返回

        // 计算地雷尺寸倍数（递减效果）
        float sizeMult = getGravityMineSizeMult();
        mine.setCustomData("gravity_sizeMult", sizeMult);

        // 应用伤害修正
        if (source != null) {
            engine.applyDamageModifiersToSpawnedProjectileWithNullWeapon(
                    source, WeaponType.ENERGY, false, mine.getDamage());
        }

        // 应用尺寸倍数到伤害
        mine.getDamage().getModifier().modifyMult("gravity_sizeMult", sizeMult);

        // === 地雷行为设置 ===
        float fadeInTime = 0.08f;
        mine.getVelocity().scale(0); // 静止不动
        mine.fadeOutThenIn(fadeInTime); // 淡入效果

        // 立即激活地雷
        mine.setFlightTime(mine.getMaxFlightTime());
        mine.addDamagedAlready(source); // 友军免疫
        mine.setNoMineFFConcerns(true);

        // 创建地雷生成时的重力扭曲效果
        createMineSpawnDistortion(mineLoc, sizeMult);

        // 播放重力异常生成音效
        Global.getSoundPlayer().playSound("shield_burnout", 0.5f, 1.4f,
                mineLoc, new Vector2f(0, 0));

        prevMineLoc = mineLoc; // 记录位置用于下次计算
    }

    /**
     * 计算重力地雷尺寸倍数 - 参考RiftCascadeEffect
     */
    private float getGravityMineSizeMult() {
        float sizeMult = 1f - spawned / (float) Math.max(1, numToSpawn - 1);
        sizeMult = 0.8f + (1f - sizeMult) * 0.4f; // 0.8-1.2倍范围
        return sizeMult;
    }

    /**
     * 创建地雷生成时的扭曲效果
     */
    private void createMineSpawnDistortion(Vector2f location, float sizeMult) {
        RippleDistortion ripple = new RippleDistortion();
        ripple.setLocation(location);
        ripple.setIntensity(35f * sizeMult);
        ripple.setLifetime(1.2f);
        ripple.setFrameRate(60f / 1.2f);
        ripple.setCurrentFrame(0f);
        ripple.setSize(80f * sizeMult);
        ripple.fadeInSize(0.3f);
        ripple.fadeOutIntensity(1.2f);
        ripple.flip(Math.random() > 0.5f);
        DistortionShader.addDistortion(ripple);
    }

    /**
     * 创建充能阶段的重力光晕效果
     * 分阶段显示不同层次的光晕，创造由小变大的效果
     */
    private void createGravityChargingFlare(CombatEngineAPI engine, WeaponAPI weapon, float chargeLevel) {
        Vector2f firePoint = weapon.getFirePoint(0);
        if (firePoint == null) {
            firePoint = weapon.getLocation();
        }

        // 基础尺寸随充能等级增长
        float baseSize = FLARE_BASE_SIZE + (FLARE_MAX_SIZE - FLARE_BASE_SIZE) * chargeLevel;

        // === 阶段1：外圈深紫色光晕（充能10%以上时显示）===
        if (chargeLevel > 0.1f) {
            // 大幅降低透明度，避免叠加过亮
            float outerAlpha = Math.min(80f, 30f + 50f * chargeLevel);
            Color outerColor = new Color(
                    FLARE_OUTER.getRed(),
                    FLARE_OUTER.getGreen(),
                    FLARE_OUTER.getBlue(),
                    (int) outerAlpha);

            // 外圈尺寸随充能等级增长
            float outerSize = baseSize * (0.4f + 0.8f * chargeLevel);

            MagicLensFlare.createSharpFlare(
                    engine, weapon.getShip(), firePoint,
                    4f, outerSize, weapon.getCurrAngle() + 90f,
                    outerColor, outerColor);
        }

        // === 阶段2：中间蓝紫色光晕（充能50%以上时显示）===
        if (chargeLevel > 0.5f) {
            float middleProgress = (chargeLevel - 0.5f) / 0.5f; // 0-1范围
            // 进一步降低透明度
            float middleAlpha = Math.min(60f, 25f + 35f * middleProgress);
            Color middleColor = new Color(
                    FLARE_MIDDLE.getRed(),
                    FLARE_MIDDLE.getGreen(),
                    FLARE_MIDDLE.getBlue(),
                    (int) middleAlpha);

            // 中间层尺寸比外圈小很多
            float middleSize = baseSize * (0.2f + 0.3f * middleProgress);

            MagicLensFlare.createSharpFlare(
                    engine, weapon.getShip(), firePoint,
                    3f, middleSize, weapon.getCurrAngle() + 90f,
                    middleColor, middleColor);
        }

        // === 阶段3：内核白色光晕（充能85%以上时显示）===
        if (chargeLevel > 0.85f) {
            float innerProgress = (chargeLevel - 0.85f) / 0.15f; // 0-1范围
            // 内核透明度较低，作为亮点而不是主体
            Color innerColor = new Color(255, 255, 255, (int) (100f * innerProgress));

            // 内核非常小，只作为能量核心的指示
            float innerSize = baseSize * (0.03f + 0.07f * innerProgress);

            MagicLensFlare.createSharpFlare(
                    engine, weapon.getShip(), firePoint,
                    1f, innerSize, weapon.getCurrAngle() + 90f,
                    innerColor, innerColor);
        }

        // 充能达到80%时创建额外的重力波扭曲
        if (chargeLevel > 0.8f) {
            createChargingDistortion(firePoint, chargeLevel);
        }
    }

    /**
     * 创建充能阶段的重力扭曲效果
     * 修复：使用RippleDistortion替代WaveDistortion，确保有完整的生命周期管理
     */
    private void createChargingDistortion(Vector2f firePoint, float chargeLevel) {
        // 使用RippleDistortion替代WaveDistortion，因为它有完整的生命周期管理
        RippleDistortion ripple = new RippleDistortion();
        ripple.setLocation(firePoint);
        ripple.setSize(FLARE_MAX_SIZE * chargeLevel);
        ripple.setIntensity(25f * chargeLevel); // 稍微降低强度
        ripple.flip(true);

        // 完整的生命周期管理
        ripple.setLifetime(0.6f); // 0.6秒生命周期
        ripple.setFrameRate(60f / 0.6f); // 动画帧率
        ripple.setCurrentFrame(0f); // 起始帧
        ripple.fadeInSize(0.1f); // 快速淡入
        ripple.fadeOutIntensity(0.6f); // 淡出动画

        DistortionShader.addDistortion(ripple);
    }

    /**
     * 获取随机的重力负粒子颜色（保持透明度不变）
     */
    private Color getRandomGravityColor() {
        // 基础透明度保持不变
        int alpha = NEGATIVE_GRAVITY_COLOR.getAlpha();

        // 在重力波色系中随机选择颜色
        float colorVariation = (float) Math.random();

        if (colorVariation < 0.33f) {
            // 深紫色系
            return new Color(75, 0, 130, alpha);
        } else if (colorVariation < 0.66f) {
            // 蓝紫色系
            return new Color(100, 20, 150, alpha);
        } else {
            // 暗蓝色系
            return new Color(50, 25, 120, alpha);
        }
    }

    /**
     * 为电弧生成随机的紫蓝色调
     * 返回数组：[0] = 电弧颜色, [1] = 核心颜色
     */
    private Color[] getRandomArcColors() {
        float colorVariation = (float) Math.random();

        Color arcColor;
        Color coreColor;

        if (colorVariation < 0.2f) {
            // 深紫色调
            arcColor = new Color(75, 0, 130, 200);
            coreColor = new Color(100, 20, 150, 240);
        } else if (colorVariation < 0.4f) {
            // 蓝紫色调
            arcColor = new Color(138, 43, 226, 180);
            coreColor = new Color(180, 100, 255, 220);
        } else if (colorVariation < 0.6f) {
            // 紫蓝色调
            arcColor = new Color(106, 90, 205, 190);
            coreColor = new Color(147, 112, 219, 230);
        } else if (colorVariation < 0.8f) {
            // 蓝色调
            arcColor = new Color(65, 105, 225, 185);
            coreColor = new Color(100, 149, 237, 225);
        } else {
            // 深蓝色调
            arcColor = new Color(25, 25, 112, 195);
            coreColor = new Color(72, 61, 139, 235);
        }

        return new Color[] { arcColor, coreColor };
    }

    /**
     * 创建充能阶段的重力电弧效果
     */
    private void createGravityChargingArcs(CombatEngineAPI engine, WeaponAPI sourceWeapon, ShipAPI ship) {
        Vector2f firePoint = sourceWeapon.getFirePoint(0);
        if (firePoint == null) {
            firePoint = sourceWeapon.getLocation();
        }

        // 查找所有LIGHT_前缀的武器槽位置
        List<Vector2f> lightSlotLocations = findLightWeaponSlots(ship);

        if (lightSlotLocations.isEmpty()) {
            // 如果没有LIGHT_武器槽，在武器周围创建环形重力电弧
            createFallbackGravityEffect(engine, sourceWeapon, firePoint);
            return;
        }

        // 为每个LIGHT_武器槽创建重力电弧
        for (Vector2f targetPoint : lightSlotLocations) {

            // 为每根电弧生成随机的紫蓝色调
            Color[] arcColors = getRandomArcColors();
            Color arcColor = arcColors[0];
            Color coreColor = arcColors[1];

            // 计算电弧粗细
            float thickness = 3f + 9f * effectLevel;

            // 创建主重力电弧
            engine.spawnEmpArcVisual(
                    firePoint, sourceWeapon.getShip(),
                    targetPoint, ship,
                    thickness,
                    arcColor,
                    coreColor);

            // 添加次级重力电弧增强效果
            if (effectLevel > 0.6f) {
                Vector2f midPoint = getMidPointWithOffset(firePoint, targetPoint,
                        MathUtils.getRandomNumberInRange(-40f, 40f));

                engine.spawnEmpArcVisual(
                        firePoint, sourceWeapon.getShip(),
                        midPoint, null,
                        thickness * 0.7f,
                        new Color(arcColor.getRed(), arcColor.getGreen(), arcColor.getBlue(), 140),
                        new Color(coreColor.getRed(), coreColor.getGreen(), coreColor.getBlue(), 170));
            }
        }
    }

    /**
     * 查找舰船上所有前缀为LIGHT_的武器槽位置
     * 参考Moci_Lidararray的做法，遍历所有武器槽而不是已安装的武器
     */
    private List<Vector2f> findLightWeaponSlots(ShipAPI ship) {
        List<Vector2f> lightSlotLocations = new ArrayList<Vector2f>();

        // 使用getAllWeaponSlotsCopy()遍历所有武器槽（包括空的）
        for (com.fs.starfarer.api.loading.WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
            String slotId = slot.getId();

            if (slotId.startsWith("LIGHT_")) {
                // 将槽位的相对坐标转换为世界坐标
                Vector2f slotLocation = convertSlotLocationToWorld(ship, slot.getLocation());
                lightSlotLocations.add(slotLocation);
            }
        }
        return lightSlotLocations;
    }

    /**
     * 将武器槽的相对坐标转换为世界坐标
     * 参考Moci_Lidararray的OffSetToLocation方法
     */
    private Vector2f convertSlotLocationToWorld(ShipAPI ship, Vector2f slotOffset) {
        Vector2f loc = new Vector2f(slotOffset);
        VectorUtils.rotate(loc, ship.getFacing(), loc);
        Vector2f.add(loc, ship.getLocation(), loc);
        return loc;
    }

    /**
     * 备用重力充能效果
     */
    private void createFallbackGravityEffect(CombatEngineAPI engine, WeaponAPI weapon, Vector2f firePoint) {
        int arcCount = (int) (6 + effectLevel * 8); // 6-14个电弧

        for (int i = 0; i < arcCount; i++) {
            float angle = (360f / arcCount) * i + MathUtils.getRandomNumberInRange(-25f, 25f);
            float distance = 50f + effectLevel * 30f;

            Vector2f arcEnd = MathUtils.getPointOnCircumference(firePoint, distance, angle);

            // 为每根备用电弧生成随机的紫蓝色调
            Color[] arcColors = getRandomArcColors();
            Color arcColor = arcColors[0];
            Color coreColor = arcColors[1];

            engine.spawnEmpArcVisual(
                    firePoint, weapon.getShip(),
                    arcEnd, weapon.getShip(),
                    3f + effectLevel * 4f,
                    arcColor,
                    coreColor);
        }
    }

    /**
     * 颜色插值函数
     */
    private Color interpolateColor(Color color1, Color color2, float ratio) {
        ratio = Math.max(0f, Math.min(1f, ratio));

        int r = (int) (color1.getRed() + ratio * (color2.getRed() - color1.getRed()));
        int g = (int) (color1.getGreen() + ratio * (color2.getGreen() - color1.getGreen()));
        int b = (int) (color1.getBlue() + ratio * (color2.getBlue() - color1.getBlue()));
        int a = (int) (color1.getAlpha() + ratio * (color2.getAlpha() - color1.getAlpha()));

        return new Color(r, g, b, a);
    }

    /**
     * 获取两点间的中点，并添加垂直偏移
     */
    private Vector2f getMidPointWithOffset(Vector2f start, Vector2f end, float offset) {
        Vector2f midPoint = new Vector2f(
                (start.x + end.x) / 2f,
                (start.y + end.y) / 2f);

        // 计算垂直于连线的偏移向量
        Vector2f direction = Vector2f.sub(end, start, null);
        Vector2f perpendicular = new Vector2f(-direction.y, direction.x);
        perpendicular.normalise();
        perpendicular.scale(offset);

        return Vector2f.add(midPoint, perpendicular, null);
    }

    /**
     * 沿光束路径创建连贯电弧效果 - 参考HWIIonLanceEffect
     * 创建一条完整的沿光束路径的连贯电弧，而不是分散的随机电弧
     */
    private void createBeamPathContinuousArcs(BeamAPI beam, CombatEngineAPI engine) {
        Vector2f from = beam.getFrom();
        Vector2f to = beam.getRayEndPrevFrame();
        float length = beam.getLengthPrevFrame();

        if (length < 100f)
            return; // 光束太短时不生成电弧

        // 创建2-3条完整的连贯电弧，沿光束路径从起点到终点
        int continuousArcCount = MathUtils.getRandomNumberInRange(2, 3);

        for (int i = 0; i < continuousArcCount; i++) {
            // 为每条连贯电弧生成随机颜色
            Color[] arcColors = getRandomArcColors();
            Color arcColor = arcColors[0];
            Color coreColor = arcColors[1];

            // 计算电弧粗细（基于光束亮度和电弧索引）
            float thickness = (3f + 5f * beam.getBrightness()) * (1f - i * 0.2f);

            // 创建沿光束路径的连贯电弧
            // 在光束路径附近添加轻微的随机偏移，但保持连贯性
            Vector2f arcStart = new Vector2f(from);
            Vector2f arcEnd = new Vector2f(to);

            // 为电弧起点和终点添加轻微的随机偏移
            float offsetRadius = 15f + i * 10f; // 每条电弧的偏移半径不同
            Vector2f startOffset = MathUtils.getRandomPointInCircle(new Vector2f(0, 0), offsetRadius);
            Vector2f endOffset = MathUtils.getRandomPointInCircle(new Vector2f(0, 0), offsetRadius);

            Vector2f.add(arcStart, startOffset, arcStart);
            Vector2f.add(arcEnd, endOffset, arcEnd);

            // 创建完整的连贯电弧
            engine.spawnEmpArcVisual(
                    arcStart, beam.getSource(), // 绑定到光束源舰船
                    arcEnd, null, // 终点不绑定，让电弧自由延伸
                    thickness,
                    arcColor,
                    coreColor);
        }

        // 额外创建一些短距离的分支电弧，增强视觉效果
        createBeamPathBranchArcs(beam, engine, from, to, length);
    }

    /**
     * 创建光束路径上的分支电弧，增强连贯电弧的视觉效果
     */
    private void createBeamPathBranchArcs(BeamAPI beam, CombatEngineAPI engine, Vector2f from, Vector2f to,
            float length) {
        // 在光束路径上创建3-5个分支点
        int branchPointCount = MathUtils.getRandomNumberInRange(3, 5);

        for (int i = 0; i < branchPointCount; i++) {
            // 沿光束路径分布分支点
            float ratio = (float) (i + 1) / (float) (branchPointCount + 1);
            Vector2f branchCenter = new Vector2f(
                    from.x + (to.x - from.x) * ratio,
                    from.y + (to.y - from.y) * ratio);

            // 在每个分支点创建1-2根短距离分支电弧
            int branchCount = MathUtils.getRandomNumberInRange(1, 2);

            for (int j = 0; j < branchCount; j++) {
                // 生成分支电弧的终点
                float branchLength = 25f + 35f * (float) Math.random();
                float branchAngle = (float) Math.random() * 360f;
                Vector2f branchEnd = MathUtils.getPointOnCircumference(branchCenter, branchLength, branchAngle);

                // 为分支电弧生成颜色（稍微暗一些）
                Color[] branchColors = getRandomArcColors();
                Color branchArcColor = new Color(
                        branchColors[0].getRed(),
                        branchColors[0].getGreen(),
                        branchColors[0].getBlue(),
                        (int) (branchColors[0].getAlpha() * 0.7f));
                Color branchCoreColor = new Color(
                        branchColors[1].getRed(),
                        branchColors[1].getGreen(),
                        branchColors[1].getBlue(),
                        (int) (branchColors[1].getAlpha() * 0.8f));

                // 分支电弧较细
                float branchThickness = 1.5f + 2f * beam.getBrightness();

                // 创建分支电弧
                engine.spawnEmpArcVisual(
                        branchCenter, null,
                        branchEnd, null,
                        branchThickness,
                        branchArcColor,
                        branchCoreColor);
            }
        }
    }

    /**
     * 在光束路径上生成连贯的地雷链（类似电弧的连贯性）
     * 一次性从起点到终点生成完整的地雷链，而不是逐个生成
     */
    private void createContinuousPathMineChain(BeamAPI beam, CombatEngineAPI engine) {
        Vector2f from = beam.getFrom();
        Vector2f to = beam.getRayEndPrevFrame();
        float length = beam.getLengthPrevFrame();

        if (length < 200f)
            return; // 光束太短时不生成路径地雷链

        // 计算地雷链参数
        float mineSpacing = 125f; // 地雷间距
        int mineCount = (int) (length / mineSpacing); // 根据光束长度计算地雷数量
        mineCount = Math.max(2, Math.min(mineCount, 8)); // 限制在2-8个地雷之间

        // 沿光束路径从起点到终点生成连贯的地雷链
        for (int i = 1; i <= mineCount; i++) { // 从1开始，避免在起点生成地雷
            // 计算每个地雷在光束路径上的位置比例
            float ratio = (float) i / (float) (mineCount + 1); // 均匀分布，避开起点和终点

            // 计算地雷的基础位置（在光束路径上）
            Vector2f mineLocation = new Vector2f(
                    from.x + (to.x - from.x) * ratio,
                    from.y + (to.y - from.y) * ratio);

            // 添加轻微的随机偏移，让地雷不完全在光束路径上，但保持连贯性
            float offsetRadius = 25f + 15f * (float) Math.random(); // 随机偏移半径
            Vector2f randomOffset = MathUtils.getRandomPointInCircle(new Vector2f(0, 0), offsetRadius);
            Vector2f.add(mineLocation, randomOffset, mineLocation);

            // 生成路径地雷
            spawnPathMine(beam.getSource(), mineLocation, engine, i, mineCount);
        }

        // 在地雷之间创建连接电弧，增强连贯性视觉效果
        createPathMineConnectingArcs(beam, engine, from, to, mineCount);
    }

    /**
     * 在路径地雷之间创建连接电弧，增强连贯性视觉效果
     */
    private void createPathMineConnectingArcs(BeamAPI beam, CombatEngineAPI engine, Vector2f from, Vector2f to,
            int mineCount) {
        // 创建1-2条连接整个地雷链的电弧
        int connectingArcCount = MathUtils.getRandomNumberInRange(1, 2);

        for (int i = 0; i < connectingArcCount; i++) {
            // 为连接电弧生成颜色
            Color[] arcColors = getRandomArcColors();
            Color arcColor = new Color(
                    arcColors[0].getRed(),
                    arcColors[0].getGreen(),
                    arcColors[0].getBlue(),
                    (int) (arcColors[0].getAlpha() * 0.6f)); // 稍微透明一些
            Color coreColor = new Color(
                    arcColors[1].getRed(),
                    arcColors[1].getGreen(),
                    arcColors[1].getBlue(),
                    (int) (arcColors[1].getAlpha() * 0.7f));

            // 计算连接电弧的起点和终点（稍微偏移光束路径）
            float offsetRadius = 20f + i * 15f;
            Vector2f arcStart = new Vector2f(from);
            Vector2f arcEnd = new Vector2f(to);

            // 添加偏移让电弧不完全重合
            Vector2f startOffset = MathUtils.getRandomPointInCircle(new Vector2f(0, 0), offsetRadius);
            Vector2f endOffset = MathUtils.getRandomPointInCircle(new Vector2f(0, 0), offsetRadius);
            Vector2f.add(arcStart, startOffset, arcStart);
            Vector2f.add(arcEnd, endOffset, arcEnd);

            // 创建连接整个地雷链的电弧
            engine.spawnEmpArcVisual(
                    arcStart, beam.getSource(),
                    arcEnd, null,
                    2f + 3f * beam.getBrightness(), // 较细的连接电弧
                    arcColor,
                    coreColor);
        }
    }

    /**
     * 生成路径地雷（与命中目标地雷分开的独立系统）
     * 重载方法，支持地雷链中的位置信息
     */
    private void spawnPathMine(ShipAPI source, Vector2f mineLoc, CombatEngineAPI engine, int mineIndex,
            int totalMines) {
        // 生成重力异常投射物
        MissileAPI mine = (MissileAPI) engine.spawnProjectile(source, null,
                GRAVITY_ANOMALY_ID,
                mineLoc,
                (float) Math.random() * 360f, null);

        if (mine == null)
            return; // 如果地雷ID不存在，直接返回

        // 路径地雷使用渐变尺寸，从起点到终点逐渐变小，营造透视效果
        float sizeMult = 1.0f - (float) mineIndex / (float) (totalMines + 1) * 0.3f; // 1.0到0.7的渐变
        mine.setCustomData("gravity_sizeMult", sizeMult);

        // 应用伤害修正
        if (source != null) {
            engine.applyDamageModifiersToSpawnedProjectileWithNullWeapon(
                    source, WeaponType.ENERGY, false, mine.getDamage());
        }

        // 应用尺寸倍数到伤害
        mine.getDamage().getModifier().modifyMult("gravity_sizeMult", sizeMult);

        // === 路径地雷行为设置 ===
        float fadeInTime = 0.1f;
        mine.getVelocity().scale(0); // 静止不动
        mine.fadeOutThenIn(fadeInTime); // 淡入效果

        // 立即激活地雷
        mine.setFlightTime(mine.getMaxFlightTime());
        mine.addDamagedAlready(source); // 友军免疫
        mine.setNoMineFFConcerns(true);

        // 创建路径地雷生成时的重力扭曲效果
        createMineSpawnDistortion(mineLoc, sizeMult);

        // 播放重力异常生成音效（音量稍小）
        Global.getSoundPlayer().playSound("shield_burnout", 0.3f, 1.2f,
                mineLoc, new Vector2f(0, 0));
    }

    /**
     * 更新fx贴图特效状态
     */
    private void updateFxEffects(float amount, float chargeLevel) {
        // 更新fx1和fx3的尺寸（从充能开始就增长，0.5时达到1f）
        if (chargeLevel <= 0.5f) {
            fx1Size = chargeLevel / 0.5f; // 0-1范围
            fx3Size = chargeLevel / 0.5f; // 0-1范围
        } else {
            fx1Size = 1f; // 保持最大尺寸
            fx3Size = 1f; // 保持最大尺寸
        }

        // 当充能等级达到0.5时，开始渲染fx2和fx4
        if (chargeLevel >= 0.5f) {
            // 更新旋转角度
            fx2Rotation += amount * 120f; // 每秒旋转120度
            fx4Rotation += amount * 90f; // 每秒旋转90度（不同速度）

            // 保持角度在0-360范围内
            if (fx2Rotation >= 360f)
                fx2Rotation -= 360f;
            if (fx4Rotation >= 360f)
                fx4Rotation -= 360f;

            // 更新闪烁效果
            fxBlinkTimer.advance(amount);
            if (fxBlinkTimer.intervalElapsed()) {
                // 随机闪烁透明度
                fx2Alpha = 180f + 75f * (float) Math.random(); // 180-255范围
                fx4Alpha = 180f + 75f * (float) Math.random(); // 180-255范围
            }

            // 渐入效果（充能0.5-1.0时从0渐入到最大透明度）
            float fadeInProgress = (chargeLevel - 0.5f) / 0.5f; // 0-1范围
            fx2Alpha *= fadeInProgress;
            fx4Alpha *= fadeInProgress;
        } else {
            // 充能等级小于0.5时，fx2和fx4不显示
            fx2Alpha = 0f;
            fx4Alpha = 0f;
        }
    }

    /**
     * 渲染fx贴图特效
     */
    private void renderFxEffects(CombatEngineAPI engine, WeaponAPI weapon) {
        Vector2f firePoint = weapon.getFirePoint(0);
        if (firePoint == null) {
            firePoint = weapon.getLocation();
        }

        // 根据实际贴图尺寸设置缩放参数 - 保持各自的视觉特征和层次
        // fx_1: 大型背景效果，作为最大的基础层
        float fx1Scale = 1.5f;
        // fx_2: 中型旋转效果，中等尺寸
        float fx2Scale = 1.5f;
        // fx_3: 小型细节效果，保持小巧精致
        float fx3Scale = 1.5f;
        // fx_4: 大型旋转效果，比fx_1稍小但仍然显眼
        float fx4Scale = 1.5f;

        // 渲染fx1（从充能开始就显示，透明度从150到255）
        if (fx1Size > 0f) {
            float fx1Alpha = 150f + 105f * effectLevel; // 150-255范围
            Color fx1Color = new Color(255, 255, 255, (int) fx1Alpha);

            // fx_1:
            float fx1Width = 294 * fx1Scale * fx1Size;
            float fx1Height = 294 * fx1Scale * fx1Size;

            org.magiclib.util.MagicRender.singleframe(
                    Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_1"),
                    firePoint,
                    new Vector2f(fx1Width, fx1Height),
                    0f, // 角度
                    fx1Color,
                    true // additive混合
            );
        }

        // 渲染fx3（从充能开始就显示，透明度从150到255）
        if (fx3Size > 0f) {
            float fx3Alpha = 150f + 105f * effectLevel; // 150-255范围
            Color fx3Color = new Color(255, 255, 255, (int) fx3Alpha);

            // fx_3: 42x37，小贴图需要放大显示
            float fx3Width = 44 * fx3Scale * fx3Size;
            float fx3Height = 44 * fx3Scale * fx3Size;

            org.magiclib.util.MagicRender.singleframe(
                    Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_3"),
                    firePoint,
                    new Vector2f(fx3Width, fx3Height),
                    0f, // 角度
                    fx3Color,
                    true // additive混合
            );
        }

        // 渲染fx2（充能0.5时开始显示，带旋转和闪烁）
        if (fx2Alpha > 0f) {
            Color fx2Color = new Color(255, 255, 255, (int) fx2Alpha);

            // fx_2: 155x179，保持实际宽高比
            float fx2Width = 190 * fx2Scale;
            float fx2Height = 190 * fx2Scale;

            org.magiclib.util.MagicRender.singleframe(
                    Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_2"),
                    firePoint,
                    new Vector2f(fx2Width, fx2Height),
                    fx2Rotation, // 旋转角度
                    fx2Color,
                    true // additive混合
            );
        }

        // 渲染fx4（充能0.5时开始显示，带旋转和闪烁）
        if (fx4Alpha > 0f) {
            Color fx4Color = new Color(255, 255, 255, (int) fx4Alpha);

            // fx_4: 211x208，保持实际宽高比
            float fx4Width = 212 * fx4Scale;
            float fx4Height = 212 * fx4Scale;

            org.magiclib.util.MagicRender.singleframe(
                    Global.getSettings().getSprite("fx", "Moci_Nadesico_fx_4"),
                    firePoint,
                    new Vector2f(fx4Width, fx4Height),
                    fx4Rotation, // 旋转角度
                    fx4Color,
                    true // additive混合
            );
        }
    }

    /**
     * 重置fx特效状态
     */
    private void resetFxEffects() {
        fx1Size = 0f;
        fx3Size = 0f;
        fx2Rotation = 0f;
        fx4Rotation = 0f;
        fx2Alpha = 0f;
        fx4Alpha = 0f;
        fxBlinkTimer.setElapsed(0f);
    }
}