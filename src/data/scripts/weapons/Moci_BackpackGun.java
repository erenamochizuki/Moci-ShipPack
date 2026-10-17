package data.scripts.weapons;

import java.awt.Color;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 背包武器帧动画控制器
 * 根据武器充能等级逐帧切换动画帧，并实现伪弹夹机制和散热烟雾效果
 * 
 * 动画规则：
 * - 默认状态：最后一帧（自动获取）
 * - 充能到阈值时：第0帧
 * - 充能降低时逐帧过渡回默认帧
 * 
 * 伪弹夹机制：
 * - 弹药为0时禁止开火
 * - 开火时每0.25秒消耗1发弹药
 * 
 * 散热效果：
 * - 持续开火时生成烟雾粒子模拟散热
 * - 需要武器具有"Moci_SMOKE"标签才会生效
 */
public class Moci_BackpackGun implements EveryFrameWeaponEffectPlugin {
    
    // 动画帧配置
    private int defaultFrame = 0;                       // 默认帧（最后一帧，初始化时获取）
    private static final int MAX_CHARGE_FRAME = 0;      // 最大充能时第0帧
    private static final float CHARGE_THRESHOLD = 0.5f; // 充能阈值，超过此值切换到第0帧
    
    // 帧切换参数
    private int currentFrame = 0;                       // 当前帧（整数）
    private float frameTimer = 0f;                      // 帧切换计时器
    
    // 伪弹夹机制参数
    private static final float AMMO_CONSUME_INTERVAL = 0.25f; // 弹药消耗间隔（秒）
    private float ammoTimer = 0f;                       // 弹药计时器
    private boolean wasFiring = false;                  // 上一帧是否在开火
    
    // 烟雾散热效果参数
    private static final float SMOKE_INTERVAL = 0.15f;  // 烟雾生成间隔（秒）
    private static final float SMOKE_SPEED = 25f;       // 烟雾粒子速度
    private static final float SMOKE_SIZE = 15f;        // 烟雾粒子基础大小
    private static final float SMOKE_SIZE_MULT = 2.5f;  // 烟雾粒子大小倍数
    private static final float SMOKE_DURATION = 3f;     // 烟雾持续时间
    private static final Color SMOKE_COLOR = new Color(120, 120, 120, 100); // 烟雾颜色（灰色，半透明）
    private float smokeTimer = 0f;                      // 烟雾计时器
    
    // 状态变量
    private boolean initialized = false;
    
    // 烟雾效果标签
    private static final String SMOKE_TAG = "Moci_SMOKE";
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础检查：确保游戏和武器状态正常
        if (engine == null || engine.isPaused() || weapon.getAnimation() == null) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            // 舰船死亡时暂停动画
            weapon.getAnimation().pause();
            return;
        }
        
        // 初始化：获取武器的总帧数并设置默认帧为最后一帧
        if (!initialized) {
            int maxFrames = weapon.getAnimation().getNumFrames() - 1;
            defaultFrame = maxFrames; // 默认帧为最后一帧
            currentFrame = defaultFrame; // 初始帧也设为默认帧
            initialized = true;
        }
        
        // ========== 伪弹夹机制处理 ==========
        handlePseudoAmmoSystem(amount, weapon);
        
        // ========== 散热烟雾效果处理 ==========
        handleHeatSmokeEffect(amount, engine, weapon);
        
        // 暂停动画自动播放，由脚本手动控制
        weapon.getAnimation().pause();
        
        // 获取当前武器充能等级（0.0 - 1.0）
        float chargeLevel = weapon.getChargeLevel();
        
        // 计算目标帧
        int targetFrame = calculateTargetFrame(chargeLevel);
        
        // 逐帧过渡到目标帧
        transitionToFrame(targetFrame, amount, weapon);
        
        // 设置当前动画帧
        weapon.getAnimation().setFrame(currentFrame);
    }
    
    /**
     * 根据充能等级计算目标帧
     * @param chargeLevel 武器充能等级 (0.0 - 1.0)
     * @return 目标帧数（整数）
     */
    private int calculateTargetFrame(float chargeLevel) {
        if (chargeLevel >= CHARGE_THRESHOLD) {
            // 充能达到阈值时显示第0帧
            return MAX_CHARGE_FRAME;
        } else {
            // 线性插值计算帧数
            // chargeLevel 0.0 -> defaultFrame (最后一帧)
            // chargeLevel 0.5 -> MAX_CHARGE_FRAME (0)
            float normalizedCharge = chargeLevel / CHARGE_THRESHOLD;
            int frame = Math.round(defaultFrame - normalizedCharge * (defaultFrame - MAX_CHARGE_FRAME));
            // 确保帧数在有效范围内
            return Math.max(MAX_CHARGE_FRAME, Math.min(frame, defaultFrame));
        }
    }
    
    /**
     * 逐帧过渡到目标帧
     * 参考 Moci_MechLegAnimationController 的实现
     * @param targetFrame 目标帧数
     * @param amount 时间增量
     * @param weapon 武器实例
     */
    private void transitionToFrame(int targetFrame, float amount, WeaponAPI weapon) {
        // 如果已经在目标帧，直接返回
        if (currentFrame == targetFrame) {
            frameTimer = 0f;
            return;
        }
        
        // 累积帧切换计时器，使用武器自身的帧率
        frameTimer += amount * weapon.getAnimation().getFrameRate();
        
        // 每当计时器达到1，切换一帧
        while (frameTimer >= 1f) {
            frameTimer -= 1f;
            
            // 逐帧向目标帧移动
            if (currentFrame < targetFrame) {
                currentFrame++;
            } else if (currentFrame > targetFrame) {
                currentFrame--;
            }
            
            // 确保不超过目标帧
            if (currentFrame == targetFrame) {
                break;
            }
        }
        
        // 确保帧数在有效范围内
        currentFrame = Math.max(MAX_CHARGE_FRAME, Math.min(currentFrame, defaultFrame));
    }
    
    /**
     * 处理伪弹夹机制
     * @param amount 时间增量
     * @param weapon 武器实例
     */
    private void handlePseudoAmmoSystem(float amount, WeaponAPI weapon) {
        // 检查武器是否使用弹药系统
        if (!weapon.usesAmmo()) {
            return; // 武器不使用弹药，跳过处理
        }
        
        // 获取当前弹药数量和充能等级
        int currentAmmo = weapon.getAmmo();
        boolean isFiring = weapon.isFiring();
        float chargeLevel = weapon.getChargeLevel();
        
        // 弹药为0时禁止开火
        if (currentAmmo <= 0) {
            weapon.setForceNoFireOneFrame(true);
            // 重置计时器
            ammoTimer = 0f;
            wasFiring = false;
            return;
        }
        
        // 处理开火状态变化
        if (isFiring && !wasFiring) {
            // 刚开始开火，重置计时器
            ammoTimer = 0f;
        } else if (!isFiring && wasFiring) {
            // 停止开火，重置计时器
            ammoTimer = 0f;
        }
        
        // 如果正在开火且充能等级足够高，累计时间
        if (isFiring && chargeLevel > 0.99f) {
            ammoTimer += amount;
            
            // 当累计时间达到消耗间隔时
            if (ammoTimer >= AMMO_CONSUME_INTERVAL) {
                // 扣除一发弹药
                if (currentAmmo > 0) {
                    weapon.setAmmo(currentAmmo - 1);
                }
                
                // 重置计时器，准备下一次消耗
                ammoTimer = 0f;
            }
        }
        
        // 更新开火状态记录
        wasFiring = isFiring;
    }
    
    /**
     * 检查武器是否具有生成烟雾的能力
     */
    private boolean canGenerateSmoke(WeaponAPI weapon) {
        if (weapon == null || weapon.getSpec() == null) return false;
        return weapon.getSpec().hasTag(SMOKE_TAG);
    }
    
    /**
     * 处理散热烟雾效果
     * 持续开火时在所有开火点生成烟雾粒子模拟散热
     * 参考 Moci_MagicAnimationGun 的实现
     * @param amount 时间增量
     * @param engine 战斗引擎
     * @param weapon 武器实例
     */
    private void handleHeatSmokeEffect(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 检查武器是否具有烟雾效果标签
        if (!canGenerateSmoke(weapon)) {
            return;
        }
        
        // 只有在开火时才生成烟雾
        if (!weapon.isFiring()) {
            smokeTimer = 0f; // 重置计时器
            return;
        }
        
        // 累计烟雾生成时间
        smokeTimer += amount;
        
        // 当达到烟雾生成间隔时
        if (smokeTimer >= SMOKE_INTERVAL) {
            float weaponAngle = weapon.getCurrAngle();
            
            // 获取所有开火点数量
            int firePointCount = getBarrelCount(weapon);
            
            // 在所有开火点周围生成烟雾
            for (int firePoint = 0; firePoint < firePointCount; firePoint++) {
                Vector2f firePointLocation = calculateFirePointLocation(weapon, firePoint);
                
                // 在每个开火点周围生成烟雾粒子
                for (int i = 0; i < 2; i++) {
                    // 在开火点附近随机偏移
                    Vector2f smokeLoc = new Vector2f(firePointLocation);
                    smokeLoc.x += MathUtils.getRandomNumberInRange(-5f, 5f);
                    smokeLoc.y += MathUtils.getRandomNumberInRange(-5f, 5f);
                    
                    // 计算烟雾速度（向武器后方扩散）
                    float smokeAngle = weaponAngle + 180f + MathUtils.getRandomNumberInRange(-30f, 30f);
                    Vector2f smokeVel = VectorUtils.getDirectionalVector(smokeLoc, 
                        MathUtils.getPointOnCircumference(smokeLoc, 10f, smokeAngle));
                    smokeVel.scale(SMOKE_SPEED);
                    
                    // 叠加舰船速度，让烟雾跟随舰船运动
                    Vector2f.add(weapon.getShip().getVelocity(), smokeVel, smokeVel);
                    
                    // 生成烟雾粒子
                    engine.addNebulaSmokeParticle(
                        smokeLoc,                    // 烟雾位置
                        smokeVel,                    // 烟雾速度
                        SMOKE_SIZE,                  // 烟雾基础大小
                        SMOKE_SIZE_MULT,             // 烟雾大小倍数
                        0.1f,                        // 烟雾透明度变化开始
                        0.1f,                        // 烟雾透明度变化结束
                        SMOKE_DURATION,              // 烟雾持续时间
                        SMOKE_COLOR                  // 烟雾颜色
                    );
                }
            }
            
            // 重置计时器
            smokeTimer = 0f;
        }
    }
    
    /**
     * 获取武器的炮管数量
     */
    private int getBarrelCount(WeaponAPI weapon) {
        if (weapon.getSlot().isHardpoint()) {
            return weapon.getSpec().getHardpointAngleOffsets().size();
        } else if (weapon.getSlot().isHidden()) {
            return weapon.getSpec().getHiddenAngleOffsets().size();
        } else {
            return weapon.getSpec().getTurretAngleOffsets().size();
        }
    }
    
    /**
     * 计算指定索引的开火点世界坐标位置
     * @param weapon 武器实例
     * @param firePointIndex 开火点索引
     * @return 开火点的世界坐标位置
     */
    private Vector2f calculateFirePointLocation(WeaponAPI weapon, int firePointIndex) {
        Vector2f firePointLocation = new Vector2f(0f, 0f);
        
        // 获取指定索引的开火偏移
        if (weapon.getSlot().isHardpoint()) {
            firePointLocation.x += weapon.getSpec().getHardpointFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getHardpointFireOffsets().get(firePointIndex).y;
        } else if (weapon.getSlot().isTurret()) {
            firePointLocation.x += weapon.getSpec().getTurretFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getTurretFireOffsets().get(firePointIndex).y;
        } else {
            firePointLocation.x += weapon.getSpec().getHiddenFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getHiddenFireOffsets().get(firePointIndex).y;
        }
        
        // 转换到世界坐标系
        firePointLocation = VectorUtils.rotate(firePointLocation, weapon.getCurrAngle(), new Vector2f(0f, 0f));
        firePointLocation.x += weapon.getLocation().x;
        firePointLocation.y += weapon.getLocation().y;
        
        return firePointLocation;
    }
}