package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Moci_Weapon3Effect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {

    // 电弧生成间隔(秒)
    private static final float ARC_INTERVAL = 0.15f;
    // 每次生成的电弧数量
    private static final int ARC_COUNT = 8;
    // 电弧随机偏移范围
    private static final float ARC_RANDOM_OFFSET = 50f;
    // 开火特效范围
    private static final float FIRE_EFFECT_RANGE = 50f;

    private final IntervalUtil arcTimer = new IntervalUtil(ARC_INTERVAL, ARC_INTERVAL);
    // 存储弹体和它们的历史位置
    private final Map<DamagingProjectileAPI, Vector2f> projectileHistory = new HashMap<DamagingProjectileAPI, Vector2f>();
    // 充能阶段参数
    private static final float CHARGE_ARC_INTERVAL = 0.1f; // 充能电弧间隔
    private static final int CHARGE_ARC_COUNT = 4; // 充能阶段电弧生成次数
    private float effectLevel = 0f; // 充能进度
    private final IntervalUtil chargeArcTimer = new IntervalUtil(CHARGE_ARC_INTERVAL, CHARGE_ARC_INTERVAL);


    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine.isPaused() || weapon == null || weapon.getShip() == null) return;


        // 处理充能阶段的电弧
        if (weapon.isFiring() && weapon.getCooldownRemaining() == 0 && effectLevel < 1f) {
            effectLevel += amount * 2f; // 调整这个值可以控制效果速度
            chargeArcTimer.advance(amount);
            if (chargeArcTimer.intervalElapsed()) {
                // 获取武器朝向向量
                Vector2f weaponDir = VectorUtils.rotate(new Vector2f(1, 0), weapon.getCurrAngle());

                // 计算基础位置（使用x,y偏移坐标）
                float offsetX = -20 + 20f * (effectLevel * CHARGE_ARC_COUNT); // x轴偏移
                float offsetY = 7f; // y轴偏移
                Vector2f firePoint = new Vector2f(weapon.getLocation());
                Vector2f basePos = Vector2f.add(firePoint,
                        new Vector2f(weaponDir.x * offsetX + weaponDir.y * offsetY,
                                weaponDir.y * offsetX - weaponDir.x * offsetY), null);



                // 在左右两侧生成电弧
                    for (int i = 0; i < 2; i++) {
                        Vector2f leftPos = MathUtils.getPointOnCircumference(
                                basePos,
                                MathUtils.getRandomNumberInRange(5f, 10f),
                                weapon.getCurrAngle() - 90f);

                        Vector2f rightPos = MathUtils.getPointOnCircumference(
                                basePos,
                                MathUtils.getRandomNumberInRange(5f, 10f),
                                weapon.getCurrAngle() + 90f);

                        engine.spawnEmpArcVisual(
                                leftPos, weapon.getShip(), rightPos, weapon.getShip(),
                                1f,
                                new Color(255, 100, 100, 125),
                                new Color(255, 255, 255, 180)
                        );
                    }
                }
            }
        else {
            effectLevel = 0f;
            chargeArcTimer.setElapsed(0f);
        }
        // 更新电弧计时器
        arcTimer.advance(amount);

        // 获取所有属于该武器的弹体
        List<DamagingProjectileAPI> projectiles = new ArrayList<DamagingProjectileAPI>();
        for (DamagingProjectileAPI proj : CombatUtils.getProjectilesWithinRange(weapon.getLocation(), 1000f)) {
            if (weapon == proj.getWeapon() && engine.isEntityInPlay(proj) && !proj.isExpired()) {
                projectiles.add(proj);
            }
        }

        // 生成电弧效果
        if (arcTimer.intervalElapsed()) {
            for (DamagingProjectileAPI proj : projectiles) {
                Vector2f currentPos = new Vector2f(proj.getLocation());
                Vector2f lastPos = projectileHistory.get(proj);

                if (lastPos == null) {
                    projectileHistory.put(proj, currentPos);
                    continue;
                }

                for (int i = 0; i < ARC_COUNT; i++) {
                    // 使用线性插值计算路径上的点
                    float lerp = MathUtils.getRandomNumberInRange(0f, 1f);
                    Vector2f arcPos = new Vector2f(
                            lastPos.x + (currentPos.x - lastPos.x) * lerp,
                            lastPos.y + (currentPos.y - lastPos.y) * lerp
                    );

                    // 添加随机偏移，使用世界坐标而非相对坐标
                    Vector2f randomOffset = MathUtils.getRandomPointInCircle(
                            new Vector2f(0, 0),
                            MathUtils.getRandomNumberInRange(10f, ARC_RANDOM_OFFSET)
                    );
                    arcPos = Vector2f.add(arcPos, randomOffset, null);

                    // 生成电弧视觉效果
                    engine.spawnEmpArcVisual(
                            lastPos, null, // 不再绑定到舰船
                            arcPos, null,  // 不再绑定到舰船
                            MathUtils.getRandomNumberInRange(5f, 15f),
                            new Color(255, 100, 100, 100),
                            new Color(255, 255, 255, 150)
                    );
                }

                projectileHistory.put(proj, currentPos);
            }
        }

        // 清理已销毁的弹体记录
        List<DamagingProjectileAPI> toRemove = new ArrayList<DamagingProjectileAPI>();
        for (DamagingProjectileAPI proj : projectileHistory.keySet()) {
            if (!engine.isEntityInPlay(proj) || proj.isExpired()) {
                toRemove.add(proj);
            }
        }
        for (DamagingProjectileAPI proj : toRemove) {
            projectileHistory.remove(proj);
        }
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 初始化弹体历史位置
        projectileHistory.put(projectile, new Vector2f(projectile.getLocation()));

        // 在武器发射点周围生成电弧特效
        Vector2f firePoint = new Vector2f(projectile.getLocation());
        for (int i = 0; i < ARC_COUNT * 2; i++) {
            Vector2f arcStart = MathUtils.getPointOnCircumference(
                    firePoint,
                    MathUtils.getRandomNumberInRange(5f, FIRE_EFFECT_RANGE),
                    MathUtils.getRandomNumberInRange(0f, 360f));

            Vector2f arcEnd = MathUtils.getPointOnCircumference(
                    firePoint,
                    MathUtils.getRandomNumberInRange(5f, FIRE_EFFECT_RANGE),
                    MathUtils.getRandomNumberInRange(0f, 360f));

            engine.spawnEmpArcVisual(
                    arcStart, weapon.getShip(), arcEnd, weapon.getShip(),
                    MathUtils.getRandomNumberInRange(3f, 10f),
                    new Color(255, 52, 52, 150),
                    new Color(255, 255, 255, 200)
            );
        }
    }
}