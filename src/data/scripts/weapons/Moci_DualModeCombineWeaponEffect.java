package data.scripts.weapons;

import java.awt.Color;

import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 双模式武器效果插件 - 步枪和火箭筒形态
 * 实现步枪弹药耗尽后切换为火箭筒，发射完火箭弹后恢复步枪形态并重新装填
 */
public class Moci_DualModeCombineWeaponEffect implements EveryFrameWeaponEffectPlugin {

    // 动画帧索引常量
    private static final int RIFLE_FRAME = 0;          // 步枪形态帧
    private static final int ROCKET_FRAME = 6;         // 火箭筒形态帧
    private static final int TRANSFORM_START = 1;      // 变形动画开始帧
    private static final int TRANSFORM_END = 5;        // 变形动画结束帧

    // 武器状态常量
    private enum WeaponState {
        RIFLE,              // 步枪形态
        TRANSFORMING_TO_ROCKET,  // 切换到火箭筒中
        ROCKET,             // 火箭筒形态
        TRANSFORMING_TO_RIFLE,   // 切换到步枪中
        RELOADING           // 步枪装填中
    }

    // 火箭筒配置
    private int rocketBurstSize = 3;        // 火箭弹发射数量
    private float rocketBurstDelay = 0.4f;  // 火箭弹发射间隔

    // 步枪配置
    private int rifleMaxAmmo = 20;          // 步枪最大弹药
    private float rifleReloadTime = 3.0f;   // 步枪重新装填时间

    // 动画配置
    private float transformFrameRate = 7f; // 变形动画帧率

    // 状态变量
    private WeaponState currentState = WeaponState.RIFLE;
    private int currentRifleAmmo;
    private int remainingRockets;
    private float transformTimer = 0f;
    private float rocketBurstTimer = 0f;
    private float reloadTimer = 0f;
    private int currentAnimFrame = RIFLE_FRAME;
    private boolean initialized = false;
    private String rocketProjectileId = "RX_93_v2_arm_right_gun_bazooka";  // 默认火箭弹武器ID
    private String rocketSoundId = "hammer_fire"; // 默认火箭发射音效

    // 存储原始贴图尺寸
    private float originalSpriteWidth = -1f;
    private float originalSpriteHeight = -1f;
    private float originalBarrelWidth = -1f;
    private float originalBarrelHeight = -1f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null) return;

        // 初始化
        if (!initialized) {
            initialize(weapon);
            rifleMaxAmmo = weapon.getSpec().getMaxAmmo();
        }

        // 根据武器状态处理逻辑
        switch (currentState) {
            case RIFLE:
                handleRifleState(amount, engine, weapon);
                break;
            case TRANSFORMING_TO_ROCKET:
                handleTransformToRocketState(amount, engine, weapon);
                break;
            case ROCKET:
                handleRocketState(amount, engine, weapon);
                break;
            case TRANSFORMING_TO_RIFLE:
                handleTransformToRifleState(amount, engine, weapon);
                break;
            case RELOADING:
                handleReloadingState(amount, engine, weapon);
                break;
        }

        // 更新动画帧
        updateAnimationFrame(weapon);
    }

    private void initialize(WeaponAPI weapon) {
        // 使用武器标签系统来读取配置
        for (String tag : weapon.getSpec().getTags()) {
            if (tag.startsWith("dualmode_rocket_count_")) {
                rocketBurstSize = Integer.parseInt(tag.substring("dualmode_rocket_count_".length()));
            } else if (tag.startsWith("dualmode_rocket_delay_")) {
                rocketBurstDelay = Float.parseFloat(tag.substring("dualmode_rocket_delay_".length()));
            } else if (tag.startsWith("dualmode_rifle_reload_")) {
                rifleReloadTime = Float.parseFloat(tag.substring("dualmode_rifle_reload_".length()));
            } else if (tag.startsWith("dualmode_rocket_projectile_")) {
                rocketProjectileId = tag.substring("dualmode_rocket_projectile_".length());
            } else if (tag.startsWith("dualmode_rocket_sound_")) {
                rocketSoundId = tag.substring("dualmode_rocket_sound_".length());
            }
        }

        // 初始化状态变量
        currentRifleAmmo = rifleMaxAmmo;
        remainingRockets = rocketBurstSize;
        initialized = true;

        // 日志输出初始化信息（调试用）
        // Global.getLogger(DualModeCombineWeaponEffect.class).info("武器初始化: " + weapon.getSpec().getWeaponId()
        //    + ", 火箭数: " + rocketBurstSize + ", 步枪弹药: " + rifleMaxAmmo);
    }

    private void handleRifleState(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 每帧更新弹药状态
        currentRifleAmmo = weapon.getAmmo();
        
        // 如果弹药耗尽，切换到火箭筒形态
        if (currentRifleAmmo <= 0) {
            currentState = WeaponState.TRANSFORMING_TO_ROCKET;
            transformTimer = 0f;
            currentAnimFrame = TRANSFORM_START;
        }
    }

    private void handleTransformToRocketState(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 处理变形动画
        transformTimer += amount * transformFrameRate;
        int frameOffset = (int)transformTimer;

        // 计算当前应该显示的帧
        currentAnimFrame = TRANSFORM_START + frameOffset;

        // 如果完成了变形动画，切换到火箭筒状态
        if (currentAnimFrame >= TRANSFORM_END) {
            currentState = WeaponState.ROCKET;
            currentAnimFrame = ROCKET_FRAME;
            remainingRockets = rocketBurstSize;
            rocketBurstTimer = 0f;
        }
    }

    private void handleRocketState(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 处理火箭发射
        if (remainingRockets > 0) {
            rocketBurstTimer -= amount;

            if (rocketBurstTimer <= 0f) {
                // 发射火箭弹
                launchRocket(engine, weapon);
                remainingRockets--;
                rocketBurstTimer = rocketBurstDelay;
            }
        } else {
            // 火箭发射完毕，切换回步枪形态
            currentState = WeaponState.TRANSFORMING_TO_RIFLE;
            transformTimer = 0f;
            currentAnimFrame = TRANSFORM_END;
        }
    }

    private void handleTransformToRifleState(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 处理变形动画（反向）
        transformTimer += amount * transformFrameRate;
        int frameOffset = (int)transformTimer;

        // 计算当前应该显示的帧（从高到低）
        currentAnimFrame = TRANSFORM_END - frameOffset;

        // 如果完成了变形动画，切换到装填状态
        if (currentAnimFrame <= TRANSFORM_START) {
            currentState = WeaponState.RELOADING;
            currentAnimFrame = RIFLE_FRAME;
            reloadTimer = rifleReloadTime;
        }
    }

    private void handleReloadingState(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 处理步枪装填
        reloadTimer -= amount;

        if (reloadTimer <= 0f) {
            // 装填完成，恢复步枪状态和弹药
            currentState = WeaponState.RIFLE;
            currentRifleAmmo = rifleMaxAmmo;
            // 重置武器弹药
            weapon.setAmmo(rifleMaxAmmo);
        }
    }

    private void updateAnimationFrame(WeaponAPI weapon) {
        // 更新武器动画帧
        if (weapon.getAnimation() != null) {
            weapon.getAnimation().setFrame(currentAnimFrame);

            // 如果尚未保存原始尺寸，则保存
            if (originalSpriteWidth < 0 && weapon.getSprite() != null) {
                originalSpriteWidth = weapon.getSprite().getWidth();
                originalSpriteHeight = weapon.getSprite().getHeight();
            }

            if (originalBarrelWidth < 0 && weapon.getBarrelSpriteAPI() != null) {
                originalBarrelWidth = weapon.getBarrelSpriteAPI().getWidth();
                originalBarrelHeight = weapon.getBarrelSpriteAPI().getHeight();
            }

            // 根据帧状态控制武器贴图和枪管贴图的显示/隐藏
            if (currentAnimFrame == RIFLE_FRAME) {
                // 步枪形态：隐藏主武器贴图，显示枪管贴图
                if (weapon.getSprite() != null) {
                    weapon.getSprite().setSize(0f, 0f);  // 隐藏主武器贴图
                }
                if (weapon.getBarrelSpriteAPI() != null && originalBarrelWidth > 0) {
                    // 恢复枪管贴图的正常尺寸
                    weapon.getBarrelSpriteAPI().setSize(originalBarrelWidth, originalBarrelHeight);
                }
            } else {
                // 变形动画或火箭筒形态：显示主武器贴图，隐藏枪管贴图
                if (weapon.getSprite() != null && originalSpriteWidth > 0) {
                    // 恢复主武器贴图的正常尺寸
                    weapon.getSprite().setSize(originalSpriteWidth, originalSpriteHeight);
                }
                if (weapon.getBarrelSpriteAPI() != null) {
                    // 隐藏枪管贴图
                    weapon.getBarrelSpriteAPI().setSize(0f, 0f);
                }
            }
        }
    }

    private void launchRocket(CombatEngineAPI engine, WeaponAPI weapon) {
        // 发射火箭弹逻辑
        float facing = weapon.getCurrAngle();
        float distance = 20f; // 发射点距离武器位置的偏移

        // 创建发射位置
        Vector2f launchPos = MathUtils.getPoint(weapon.getFirePoint(0), distance, facing);

        // 发射火箭弹
        DamagingProjectileAPI rocket = (DamagingProjectileAPI) engine.spawnProjectile(
                weapon.getShip(),
                weapon,
                rocketProjectileId,
                launchPos,
                facing,
                weapon.getShip().getVelocity());

        // 添加视觉效果
        Vector2f vel = new Vector2f(rocket.getVelocity());
        vel.scale(0.2f);
        vel.setX(vel.getX() + weapon.getShip().getVelocity().getX());
        vel.setY(vel.getY() + weapon.getShip().getVelocity().getY());
        engine.addSmokeParticle(launchPos, vel, 20f, 160f, 1f, new Color(140, 140, 140, 180));

        // 播放发射音效
        Global.getSoundPlayer().playSound(rocketSoundId, 1f, 0.5f, launchPos, vel);
    }
} 