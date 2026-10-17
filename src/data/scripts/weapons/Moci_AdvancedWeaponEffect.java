package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicLensFlare;

import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 高级武器特效系统
 * 
 * 关联武器ID: "Moci_advanced_weapon"
 * 
 * 功能特性：
 * 1. 充能阶段：从武器开火点到所有LIGHT_前缀武器槽创建蓝紫色电弧
 * 2. 充能光晕：三层渐变光晕效果（外圈紫色、中间蓝色、内核白色）
 * 3. 开火特效：在开火点周围生成强化电弧特效
 * 4. 裂隙地雷：开火后在投射物路径上生成智能地雷
 */
public class Moci_AdvancedWeaponEffect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

    // 充能阶段参数
    private static final float CHARGE_ARC_INTERVAL = 0.08f; // 充能电弧间隔
    private static final float CHARGE_DURATION = 1.0f; // 充能持续时间

    // 开火特效参数
    private static final int FIRE_ARC_COUNT = 12; // 开火时电弧数量
    private static final float FIRE_EFFECT_RANGE = 60f; // 开火特效范围

    // 电弧视觉参数
    private static final float ARC_THICKNESS_MIN = 2f;
    private static final float ARC_THICKNESS_MAX = 8f;

    // 颜色定义 - 蓝色到紫色渐变
    private static final Color BLUE_COLOR = new Color(100, 150, 255, 180);
    private static final Color PURPLE_COLOR = new Color(200, 100, 255, 180);
    private static final Color BLUE_CORE = new Color(150, 200, 255, 220);
    private static final Color PURPLE_CORE = new Color(255, 150, 255, 220);

    // 光晕颜色定义 - 三层渐变光晕
    private static final Color FLARE_OUTER = new Color(200, 100, 255, 120); // 外圈紫色
    private static final Color FLARE_MIDDLE = new Color(100, 150, 255, 160); // 中间蓝色
    private static final Color FLARE_INNER = new Color(255, 255, 255, 255); // 内核白色

    // 光晕尺寸参数
    private static final float FLARE_BASE_SIZE = 30f; // 基础光晕尺寸
    private static final float FLARE_MAX_SIZE = 80f; // 最大光晕尺寸

    // 裂隙地雷系统参数
    private static final float RIFT_TARGET_RANGE = 100f; // 地雷目标搜索范围
    private static final float RIFT_SPAWN_RANGE = 50f; // 地雷生成偏移范围
    private static final String RIFT_MINE_ID = "Moci_rift_mine"; // 地雷投射物ID
    private static final float RIFT_SPAWN_INTERVAL = 0.8f; // 地雷生成间隔

    private float effectLevel = 0f; // 充能进度 (0-1)
    private final IntervalUtil chargeArcTimer = new IntervalUtil(CHARGE_ARC_INTERVAL, CHARGE_ARC_INTERVAL);
    private final IntervalUtil riftMineTimer = new IntervalUtil(RIFT_SPAWN_INTERVAL, RIFT_SPAWN_INTERVAL);
    private boolean wasCharging = false; // 用于检测充能状态变化
    private boolean hasFired = false; // 用于检测开火状态

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused() || weapon == null || weapon.getShip() == null)
            return;

        ShipAPI ship = weapon.getShip();
        boolean isCharging = weapon.isFiring() && weapon.getCooldownRemaining() == 0;

        // 处理充能阶段
        if (isCharging) {
            // 更新充能进度
            effectLevel = Math.min(1f, effectLevel + amount / CHARGE_DURATION);

            // 创建充能光晕效果
            createChargingFlare(engine, weapon, effectLevel);

            chargeArcTimer.advance(amount);
            if (chargeArcTimer.intervalElapsed()) {
                createChargingArcs(engine, weapon, ship);
            }

            wasCharging = true;
            hasFired = false; // 重置开火状态
        } else {
            // 重置充能状态
            if (wasCharging) {
                effectLevel = 0f;
                wasCharging = false;
                hasFired = true; // 标记已开火
            }
            chargeArcTimer.setElapsed(0f);
        }

        // 处理开火后的裂隙地雷生成
        if (hasFired && weapon.getCooldownRemaining() > 0) {
            handleRiftMineGeneration(amount, engine, weapon);
        }
    }

    /**
     * 创建充能阶段的多层光晕效果
     * 在武器开火点渲染三层渐变光晕：外圈紫色、中间蓝色、内核白色
     */
    private void createChargingFlare(CombatEngineAPI engine, WeaponAPI weapon, float chargeLevel) {
        Vector2f firePoint = weapon.getFirePoint(0);
        if (firePoint == null) {
            firePoint = weapon.getLocation();
        }

        // 计算光晕尺寸（基于充能等级）
        float flareSize = FLARE_BASE_SIZE + (FLARE_MAX_SIZE - FLARE_BASE_SIZE) * chargeLevel;

        // 计算光晕亮度（基于充能等级，但内核始终保持满透明度）
        float outerAlpha = Math.min(255f, 120f + 100f * chargeLevel);
        float middleAlpha = Math.min(255f, 160f + 80f * chargeLevel);

        // 动态调整颜色透明度
        Color outerColor = new Color(
                FLARE_OUTER.getRed(),
                FLARE_OUTER.getGreen(),
                FLARE_OUTER.getBlue(),
                (int) outerAlpha);
        Color middleColor = new Color(
                FLARE_MIDDLE.getRed(),
                FLARE_MIDDLE.getGreen(),
                FLARE_MIDDLE.getBlue(),
                (int) middleAlpha);
        // 内核颜色保持纯白色满透明度，只改变大小
        Color innerColor = FLARE_INNER;

        // 创建外层光晕（紫色）
        MagicLensFlare.createSharpFlare(
                engine, weapon.getShip(), firePoint,
                8f, // 光晕尺寸基数
                flareSize * 1.2f, // 外层最大
                weapon.getCurrAngle() + 90f, // 垂直于武器朝向
                outerColor, outerColor);

        // 创建中层光晕（蓝色）
        MagicLensFlare.createSharpFlare(
                engine, weapon.getShip(), firePoint,
                6f, // 光晕尺寸基数
                flareSize * 0.8f, // 中层尺寸
                weapon.getCurrAngle() + 90f,
                middleColor, middleColor);

        // 创建内核光晕（白色）
        MagicLensFlare.createSharpFlare(
                engine, weapon.getShip(), firePoint,
                4f, // 光晕尺寸基数
                flareSize * 0.4f, // 内核最小
                weapon.getCurrAngle() + 90f,
                innerColor, innerColor);
    }

    /**
     * 创建充能阶段的电弧效果
     * 从武器开火点连接到所有LIGHT_前缀的武器槽
     */
    private void createChargingArcs(CombatEngineAPI engine, WeaponAPI sourceWeapon, ShipAPI ship) {
        Vector2f firePoint = sourceWeapon.getFirePoint(0);
        if (firePoint == null) {
            firePoint = sourceWeapon.getLocation();
        }

        // 查找所有LIGHT_前缀的武器槽
        List<WeaponAPI> lightWeapons = findLightWeapons(ship);

        if (lightWeapons.isEmpty()) {
            // 如果没有LIGHT_武器，在武器周围创建环形电弧
            createFallbackChargingEffect(engine, sourceWeapon, firePoint);
            return;
        }

        // 为每个LIGHT_武器创建电弧
        for (WeaponAPI lightWeapon : lightWeapons) {
            Vector2f targetPoint = lightWeapon.getLocation();

            // 计算颜色渐变 (基于充能进度和随机因子)
            float colorRatio = (effectLevel + MathUtils.getRandomNumberInRange(-0.2f, 0.2f));
            colorRatio = Math.max(0f, Math.min(1f, colorRatio));

            Color arcColor = interpolateColor(BLUE_COLOR, PURPLE_COLOR, colorRatio);
            Color coreColor = interpolateColor(BLUE_CORE, PURPLE_CORE, colorRatio);

            // 计算电弧粗细 (基于充能进度)
            float thickness = ARC_THICKNESS_MIN + (ARC_THICKNESS_MAX - ARC_THICKNESS_MIN) * effectLevel;

            // 创建主电弧
            engine.spawnEmpArcVisual(
                    firePoint, sourceWeapon.getShip(),
                    targetPoint, ship,
                    thickness,
                    arcColor,
                    coreColor);

            // 添加次级电弧增强效果
            if (effectLevel > 0.5f) {
                Vector2f midPoint = getMidPointWithOffset(firePoint, targetPoint,
                        MathUtils.getRandomNumberInRange(-30f, 30f));

                engine.spawnEmpArcVisual(
                        firePoint, sourceWeapon.getShip(),
                        midPoint, null,
                        thickness * 0.6f,
                        new Color(arcColor.getRed(), arcColor.getGreen(), arcColor.getBlue(), 120),
                        new Color(coreColor.getRed(), coreColor.getGreen(), coreColor.getBlue(), 150));
            }
        }
    }

    /**
     * 查找舰船上所有前缀为LIGHT_的武器
     */
    private List<WeaponAPI> findLightWeapons(ShipAPI ship) {
        List<WeaponAPI> lightWeapons = new ArrayList<WeaponAPI>();

        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot() != null && weapon.getSlot().getId() != null) {
                String slotId = weapon.getSlot().getId();
                if (slotId.startsWith("LIGHT_")) {
                    lightWeapons.add(weapon);
                }
            }
        }

        return lightWeapons;
    }

    /**
     * 备用充能效果 - 当没有LIGHT_武器时使用
     */
    private void createFallbackChargingEffect(CombatEngineAPI engine, WeaponAPI weapon, Vector2f firePoint) {
        int arcCount = (int) (4 + effectLevel * 6); // 4-10个电弧

        for (int i = 0; i < arcCount; i++) {
            float angle = (360f / arcCount) * i + MathUtils.getRandomNumberInRange(-20f, 20f);
            float distance = 40f + effectLevel * 20f;

            Vector2f arcEnd = MathUtils.getPointOnCircumference(firePoint, distance, angle);

            float colorRatio = MathUtils.getRandomNumberInRange(0f, 1f);
            Color arcColor = interpolateColor(BLUE_COLOR, PURPLE_COLOR, colorRatio);
            Color coreColor = interpolateColor(BLUE_CORE, PURPLE_CORE, colorRatio);

            engine.spawnEmpArcVisual(
                    firePoint, weapon.getShip(),
                    arcEnd, weapon.getShip(),
                    ARC_THICKNESS_MIN + effectLevel * 3f,
                    arcColor,
                    coreColor);
        }
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        Vector2f firePoint = new Vector2f(projectile.getLocation());

        // 在开火点周围生成强化电弧特效
        for (int i = 0; i < FIRE_ARC_COUNT; i++) {
            Vector2f arcStart = MathUtils.getPointOnCircumference(
                    firePoint,
                    MathUtils.getRandomNumberInRange(10f, FIRE_EFFECT_RANGE * 0.7f),
                    MathUtils.getRandomNumberInRange(0f, 360f));

            Vector2f arcEnd = MathUtils.getPointOnCircumference(
                    firePoint,
                    MathUtils.getRandomNumberInRange(15f, FIRE_EFFECT_RANGE),
                    MathUtils.getRandomNumberInRange(0f, 360f));

            // 随机选择蓝紫色调
            float colorRatio = MathUtils.getRandomNumberInRange(0f, 1f);
            Color arcColor = interpolateColor(BLUE_COLOR, PURPLE_COLOR, colorRatio);
            Color coreColor = interpolateColor(BLUE_CORE, PURPLE_CORE, colorRatio);

            engine.spawnEmpArcVisual(
                    arcStart, weapon.getShip(),
                    arcEnd, weapon.getShip(),
                    MathUtils.getRandomNumberInRange(ARC_THICKNESS_MIN, ARC_THICKNESS_MAX),
                    arcColor,
                    coreColor);
        }

        // 播放音效 (需要在sounds.json中注册)
        Global.getSoundPlayer().playSound("plasma_cannon_fire", 1.0f, 0.8f,
                firePoint, new Vector2f(0, 0));

        // 初始化裂隙地雷生成系统
        riftMineTimer.setElapsed(0f);
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

    // ========== 裂隙地雷生成系统 ==========

    /**
     * 处理开火后的裂隙地雷生成
     * 在投射物飞行路径上定期生成智能地雷
     */
    private void handleRiftMineGeneration(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        riftMineTimer.advance(amount);

        if (riftMineTimer.intervalElapsed()) {
            // 寻找该武器发射的投射物
            List<DamagingProjectileAPI> projectiles = findWeaponProjectiles(weapon, engine);

            for (DamagingProjectileAPI projectile : projectiles) {
                // 为每个投射物生成一个地雷
                generateRiftMine(projectile, weapon, engine);
            }
        }
    }

    /**
     * 查找属于指定武器的投射物
     */
    private List<DamagingProjectileAPI> findWeaponProjectiles(WeaponAPI weapon, CombatEngineAPI engine) {
        List<DamagingProjectileAPI> weaponProjectiles = new ArrayList<DamagingProjectileAPI>();

        for (DamagingProjectileAPI proj : engine.getProjectiles()) {
            if (proj.getWeapon() == weapon && !proj.isExpired() && engine.isEntityInPlay(proj)) {
                weaponProjectiles.add(proj);
            }
        }

        return weaponProjectiles;
    }

    /**
     * 为指定投射物生成裂隙地雷
     */
    private void generateRiftMine(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        Vector2f projLocation = new Vector2f(projectile.getLocation());

        // 寻找投射物附近的敌方目标
        CombatEntityAPI target = findRiftTarget(projLocation, weapon, engine);

        Vector2f mineLocation;
        if (target != null) {
            // 有目标时，在投射物和目标之间生成地雷
            mineLocation = calculateTargetedMinePosition(projLocation, target.getLocation());
        } else {
            // 无目标时，在投射物周围随机生成地雷
            mineLocation = calculateRandomMinePosition(projLocation);
        }

        // 生成地雷
        spawnRiftMine(weapon.getShip(), mineLocation, engine);
    }

    /**
     * 寻找投射物附近的敌方目标
     */
    private CombatEntityAPI findRiftTarget(Vector2f projLocation, WeaponAPI weapon, CombatEngineAPI engine) {
        Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
                projLocation, RIFT_TARGET_RANGE * 2f, RIFT_TARGET_RANGE * 2f);

        int owner = weapon.getShip().getOwner();
        WeightedRandomPicker<CombatEntityAPI> picker = new WeightedRandomPicker<CombatEntityAPI>();

        while (iter.hasNext()) {
            Object o = iter.next();

            // 只考虑导弹和舰船
            if (!(o instanceof MissileAPI) && !(o instanceof ShipAPI))
                continue;

            CombatEntityAPI entity = (CombatEntityAPI) o;

            // 跳过友方单位
            if (entity.getOwner() == owner)
                continue;

            // 如果是舰船，只瞄准战机和无人机
            if (entity instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) entity;
                if (!ship.isFighter() && !ship.isDrone())
                    continue;
            }

            // 距离检查
            float distance = Misc.getDistance(projLocation, entity.getLocation());
            if (distance > RIFT_TARGET_RANGE)
                continue;

            picker.add(entity);
        }

        return picker.pick();
    }

    /**
     * 计算瞄准目标的地雷位置
     */
    private Vector2f calculateTargetedMinePosition(Vector2f projLocation, Vector2f targetLocation) {
        Vector2f direction = Misc.getUnitVectorAtDegreeAngle(
                Misc.getAngleInDegrees(projLocation, targetLocation));

        float distance = Math.min(Misc.getDistance(projLocation, targetLocation), RIFT_SPAWN_RANGE);
        direction.scale(distance);

        return Vector2f.add(projLocation, direction, null);
    }

    /**
     * 计算随机地雷位置
     */
    private Vector2f calculateRandomMinePosition(Vector2f projLocation) {
        return Misc.getPointWithinRadius(projLocation, RIFT_SPAWN_RANGE);
    }

    /**
     * 生成裂隙地雷
     */
    private void spawnRiftMine(ShipAPI source, Vector2f location, CombatEngineAPI engine) {
        // 生成地雷投射物
        MissileAPI mine = (MissileAPI) engine.spawnProjectile(
                source, null, RIFT_MINE_ID, location,
                (float) Math.random() * 360f, null);

        if (mine == null)
            return; // 如果地雷ID不存在，直接返回

        // 应用伤害修正
        if (source != null) {
            engine.applyDamageModifiersToSpawnedProjectileWithNullWeapon(
                    source, WeaponType.ENERGY, false, mine.getDamage());
        }

        // 设置地雷行为
        mine.getVelocity().scale(0); // 静止不动
        mine.fadeOutThenIn(0.1f); // 淡入效果
        mine.setFlightTime(mine.getMaxFlightTime()); // 立即激活
        mine.addDamagedAlready(source); // 友军免疫
        mine.setNoMineFFConcerns(true);

        // 播放地雷生成音效
        Global.getSoundPlayer().playSound("ui_discovered_entity", 0.6f, 1.3f,
                location, new Vector2f(0, 0));
    }
}