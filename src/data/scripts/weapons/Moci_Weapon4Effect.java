package data.scripts.weapons;

import java.awt.Color;
import java.util.List;

import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.AIUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import com.fs.starfarer.api.util.Misc;

public class Moci_Weapon4Effect implements EveryFrameWeaponEffectPlugin {
    // 双模式武器状态枚举
    private enum WeaponState {
        IDLE,               // 空闲状态
        PILE_CHARGE_UP,     // 打桩机充能阶段 (新增)
        PILE_ATTACK,        // 打桩机攻击
        TRANSFORMING_TO_MISSILE, // 切换到导弹模式
        MISSILE_AIMING,     // 导弹瞄准状态 (新增)
        MISSILE_ATTACK,     // 导弹攻击
        TRANSFORMING_TO_PILE     // 切换回打桩机模式
    }

    // 动画帧常量
    private static final int IDLE_FRAME = 0;           // 静止帧
    private static final int PILE_START_FRAME = 1;     // 打桩起始帧
    private static final int PILE_END_FRAME = 15;      // 打桩结束帧
    private static final int TRANSFORM_TO_MISSILE_START = 16; // 切换到导弹开始帧
    private static final int TRANSFORM_TO_MISSILE_END = 25;   // 切换到导弹结束帧
    private static final int WEAPON_DETACH_FRAME = 20;        // 武器从背后取下的帧
    private static final int MISSILE_START_FRAME = 26;        // 导弹起始帧
    private static final int MISSILE_END_FRAME = 32;          // 导弹结束帧
    private static final int TRANSFORM_TO_PILE_START = 33;    // 切换回打桩开始帧
    private static final int TRANSFORM_TO_PILE_END = 41;      // 切换回打桩结束帧
    private static final int WEAPON_ATTACH_FRAME = 38;        // 武器挂回背后的帧

    // 副武器帧常量
    private static final int BACK_WEAPON_ATTACHED = 0;  // 武器挂在背后
    private static final int BACK_WEAPON_DETACHED = 1;  // 武器被取下

    // 打桩机配置参数
    private static final float AUTO_AIM_SEARCH_CONE_ANGLE = 10f; // 索敌锥角 (度)
    private static final float AUTO_AIM_SEARCH_RANGE = 500f;     // 索敌范围
    private static final float AUTO_AIM_ROTATION_DEGREES_PER_SECOND = 30f; // 每秒转向度数
    private static final float ENGINE_GLOW_MULT = 2f;   // 引擎发光倍数
    private static final int PILE_DAMAGE = 2000;        // 打桩伤害
    private static final int PILE_EMP_DAMAGE = 500;    // 打桩EMP伤害
    private static final float PILE_CHARGE_UP_DURATION = 2f; // 打桩充能时间 (新增)

    // 导弹配置参数
    private static final int DEFAULT_MISSILE_BURST_COUNT = 1;   // 默认导弹发射数量
    private static final float MISSILE_BURST_DELAY = 0f; // 导弹发射间隔（设为0）
    private static final String DEFAULT_MISSILE_PROJECTILE_ID = "reaper"; // 默认导弹ID
    private static final String DEFAULT_MISSILE_SOUND = "reaper_fire"; // 默认导弹发射音效
    private static final float MISSILE_AIM_MAX_DURATION = 3.5f; // 导弹瞄准最大时间
    private static final float TARGET_SIZE_SCALE_FACTOR = 0.5f; // 目标碰撞尺寸的缩放因子
    private static final float MISSILE_AIM_ROTATION_DEGREES_PER_SECOND = 10.0f; // 导弹瞄准转向速度
    private static final float EXPLODE_DELAY = 2.0f; // 打桩爆炸延迟 (新增)

    // 动画和状态控制
    private float animTimer = 0f;          // 动画计时器
    private float stateTimer = 0f;         // 状态计时器 (新增)
    private float missileTimer = 0f;       // 导弹发射计时器
    private int currentAnimFrame = 0;      // 当前动画帧
    private int missileCount = 0;          // 已发射导弹数量
    private boolean initialized = false;   // 初始化标志
    private WeaponState currentState = WeaponState.IDLE; // 当前武器状态
    
    // 打桩相关变量
    private CollisionClass originalCollision = null; // 原始碰撞类型
    private ShipAPI targetShip = null;     // 目标舰船
    private ShipAPI autoAimTarget = null;  // 自动瞄准目标

    // 武器引用
    private WeaponAPI backWeapon = null;   // 背后武器引用
    private WeaponAPI backpackSourceWeapon = null; // BACKPACK_SOURCE槽位武器引用
    
    // 动态导弹参数
    private String currentMissileId = DEFAULT_MISSILE_PROJECTILE_ID; // 当前导弹ID
    private String currentMissileSound = DEFAULT_MISSILE_SOUND; // 当前导弹音效
    private int currentMissileBurstCount = DEFAULT_MISSILE_BURST_COUNT; // 当前导弹发射数量

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null || !weapon.getShip().isAlive()) {
            return;
        }

        // 初始化
        if (!initialized) {
            init(engine, weapon);
        }

        // 更新背部武器的显示状态
        updateBackWeaponDisplay();
        
        // 持续隐藏BACKPACK_SOURCE武器
        updateBackpackSourceWeapon();

        // 根据当前状态处理武器逻辑
        switch (currentState) {
            case IDLE:
                // 空闲状态不需要特殊处理
                break;
            case PILE_CHARGE_UP:
                handlePileChargeUp(amount, engine, weapon);
                break;
            case PILE_ATTACK:
                handlePileAttack(amount, engine, weapon);
                break;
            case TRANSFORMING_TO_MISSILE:
                handleTransformToMissile(amount, engine, weapon);
                break;
            case MISSILE_AIMING:
                handleMissileAiming(amount, engine, weapon);
                break;
            case MISSILE_ATTACK:
                handleMissileAttack(amount, engine, weapon);
                break;
            case TRANSFORMING_TO_PILE:
                handleTransformToPile(amount, engine, weapon);
                break;
        }

        // 更新动画帧
        if (weapon.getAnimation() != null) {
            weapon.getAnimation().setFrame(currentAnimFrame);
        }
    }

    /**
     * 初始化武器
     */
    private void init(CombatEngineAPI engine, WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();

        // 查找BACKPACK槽位武器
        for (WeaponAPI w : ship.getAllWeapons()) {
            if ("BACKPACK".equals(w.getSlot().getId())) {
                backWeapon = w;
            } else if ("BACKPACK_SOURCE".equals(w.getSlot().getId())) {
                backpackSourceWeapon = w;
                // 隐藏该武器并禁用
                hideAndDisableWeapon(engine, w);
                // 从该武器获取导弹配置
                configureMissileFromWeapon(w);
            }
        }
        
        initialized = true;
    }
    
    /**
     * 隐藏并禁用武器
     */
    private void hideAndDisableWeapon(CombatEngineAPI engine, WeaponAPI weapon) {
        if (weapon == null) return;
        
        // 设置武器为透明状态
        if (weapon.getSprite() != null) {
            weapon.getSprite().setAlphaMult(0f);
        }
        
        // 隐藏导弹武器的导弹渲染 - 参考Moci_MechProjDecoWeaponEffect的实现
        if (weapon.getType() == WeaponAPI.WeaponType.MISSILE) {
            weapon.getSprite().setWidth(0f);
            weapon.getSprite().setHeight(0f);
            
            // 隐藏导弹渲染数据
            if (weapon.getMissileRenderData() != null) {
                for (MissileRenderDataAPI render : weapon.getMissileRenderData()) {
                    render.getSprite().setWidth(0);
                    render.getSprite().setHeight(0);
                }
            }
        }
        
        // 从武器组中移除该武器（使用安全方法，通过临时全局插件）
        ShipAPI ship = weapon.getShip();
        if (ship != null && engine != null) {
            data.scripts.util.Moci_WeaponGroupSafetyUtil.safelyRemoveWeaponFromGroups(
                engine, ship, weapon, true
            );
        }
    }
    
    /**
     * 从武器配置导弹参数
     */
    private void configureMissileFromWeapon(WeaponAPI weapon) {
        if (weapon == null) return;
        
        try {
            // 获取武器的导弹ID - 对于导弹武器，直接使用武器ID作为导弹ID
            String weaponId = weapon.getId();
            if (weaponId != null && !weaponId.isEmpty()) {
                // 如果是导弹武器，使用武器ID作为导弹ID
                if (weapon.getType() == WeaponAPI.WeaponType.MISSILE) {
                    currentMissileId = weaponId;
                    // 使用默认音效，避免音效不存在的问题
                    currentMissileSound = DEFAULT_MISSILE_SOUND;
                    
                    // 获取武器的Burst size作为导弹发射数量
                    int burstSize = weapon.getSpec().getBurstSize();
                    if (burstSize > 0) {
                        currentMissileBurstCount = burstSize;
                    } else {
                        currentMissileBurstCount = DEFAULT_MISSILE_BURST_COUNT;
                    }
                    
                    // 调试输出
                    System.out.println("Moci_Weapon4Effect: 武器 " + weaponId + " Burst Size: " + burstSize + 
                                     ", 设置导弹发射数量: " + currentMissileBurstCount);
                }
            }
        } catch (Exception e) {
            // 如果获取失败，保持使用默认值
            currentMissileId = DEFAULT_MISSILE_PROJECTILE_ID;
            currentMissileSound = DEFAULT_MISSILE_SOUND;
            currentMissileBurstCount = DEFAULT_MISSILE_BURST_COUNT;
        }
    }

    /**
     * 更新背部武器的显示状态
     */
    private void updateBackWeaponDisplay() {
        if (backWeapon != null && backWeapon.getAnimation() != null) {
            int frame = BACK_WEAPON_ATTACHED;
            
            // 在转换到导弹模式后，从WEAPON_DETACH_FRAME开始显示武器被取下
            if (currentAnimFrame >= WEAPON_DETACH_FRAME && currentAnimFrame < WEAPON_ATTACH_FRAME) {
                frame = BACK_WEAPON_DETACHED;
            }
            
            backWeapon.getAnimation().setFrame(frame);
        }
    }
    
    /**
     * 更新BACKPACK_SOURCE武器状态
     */
    private void updateBackpackSourceWeapon() {
        if (backpackSourceWeapon != null) {
            // 持续禁用武器开火
            backpackSourceWeapon.setForceNoFireOneFrame(true);
            
            // 确保武器保持透明状态
            if (backpackSourceWeapon.getSprite() != null) {
                backpackSourceWeapon.getSprite().setAlphaMult(0f);
                // 对于导弹武器，同时隐藏尺寸和导弹渲染
                if (backpackSourceWeapon.getType() == WeaponAPI.WeaponType.MISSILE) {
                    backpackSourceWeapon.getSprite().setWidth(0f);
                    backpackSourceWeapon.getSprite().setHeight(0f);
                    
                    // 持续隐藏导弹渲染数据
                    if (backpackSourceWeapon.getMissileRenderData() != null) {
                        for (MissileRenderDataAPI render : backpackSourceWeapon.getMissileRenderData()) {
                            render.getSprite().setWidth(0);
                            render.getSprite().setHeight(0);
                        }
                    }
                }
            }
        }
    }

    /**
     * 处理打桩充能阶段
     */
    private void handlePileChargeUp(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        
        // 保持在第0帧，表示准备状态
        currentAnimFrame = IDLE_FRAME;
        
        // 变更碰撞类型以便穿透敌舰
        if (originalCollision == null) {
            originalCollision = ship.getCollisionClass();
            ship.setCollisionClass(CollisionClass.FIGHTER);
        }
        
        // 阻止减速和后退
        ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
        ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
        ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
        
        // 增加速度和加速度实现冲刺准备
        ship.getMutableStats().getMaxSpeed().modifyFlat("Moci_Weapon4", 350);
        ship.getMutableStats().getAcceleration().modifyFlat("Moci_Weapon4", 10000);
        ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        ship.getEngineController().extendFlame(this, ENGINE_GLOW_MULT, ENGINE_GLOW_MULT, ENGINE_GLOW_MULT);
        
        // 自动瞄准处理
        handleAutoAim(amount, ship);
        
        // 检查是否碰撞到目标
        ShipAPI hitTarget = checkPileCollision(weapon);
        if (hitTarget != null) {
            // 命中目标，立即执行打桩攻击效果
            targetShip = hitTarget;
            executePileEffect(engine, weapon, targetShip);
            
            // 立即清理冲刺状态
            cleanupPileState(ship);
            
            // 转入打桩攻击阶段播放动画
            transitionToState(WeaponState.PILE_ATTACK);
            return;
        }
        
        // 计时器超出充能时间，转入攻击状态
        stateTimer += amount;
        if (stateTimer >= PILE_CHARGE_UP_DURATION) {
            // 充能完毕，转入攻击状态
            transitionToState(WeaponState.PILE_ATTACK);
        }
    }

    /**
     * 处理打桩攻击状态
     */
    private void handlePileAttack(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        
        // 自动瞄准处理（保留方向控制但不加速）
        handleAutoAim(amount, ship);
        
        // 还没有找到目标时，继续检测碰撞
        if (targetShip == null) {
            // 保持碰撞类型为战机以便穿透敌舰
            if (originalCollision == null) {
                originalCollision = ship.getCollisionClass();
                ship.setCollisionClass(CollisionClass.FIGHTER);
            }
            
            // 保持速度和加速度增强
            ship.getMutableStats().getMaxSpeed().modifyFlat("Moci_Weapon4", 350);
            ship.getMutableStats().getAcceleration().modifyFlat("Moci_Weapon4", 10000);
            ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
            ship.getEngineController().extendFlame(this, ENGINE_GLOW_MULT, ENGINE_GLOW_MULT, ENGINE_GLOW_MULT);
            
            // 阻止减速和后退
            ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
            ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
            ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
            ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);
            ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
            ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
            
            // 检测碰撞
            ShipAPI hitTarget = checkPileCollision(weapon);
            if (hitTarget != null) {
                // 命中目标，立即执行打桩攻击效果
                targetShip = hitTarget;
                executePileEffect(engine, weapon, targetShip);
                
                // 立即清理冲刺状态
                cleanupPileState(ship);
            }
        }
        
        // 更新动画
        animTimer += amount * weapon.getAnimation().getFrameRate();
        if (animTimer >= 1f) {
            animTimer -= 1f;
            currentAnimFrame++;
            
            // 动画播放到结束帧时
            if (currentAnimFrame > PILE_END_FRAME) {
                // 确保已经清理了冲刺状态
                cleanupPileState(ship);
                
                // 根据是否有目标决定下一步
                if (targetShip != null) {
                    transitionToState(WeaponState.TRANSFORMING_TO_MISSILE);
                } else {
                    transitionToState(WeaponState.IDLE);
                }
            }
        }
    }

    /**
     * 处理转换到导弹模式状态
     */
    private void handleTransformToMissile(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 更新动画
        animTimer += amount * weapon.getAnimation().getFrameRate() * 0.5f; // 减慢变形动画速度
        if (animTimer >= 1f) {
            animTimer -= 1f;
            currentAnimFrame++;
            
            // 变形完成后切换到导弹瞄准状态
            if (currentAnimFrame >= TRANSFORM_TO_MISSILE_END) {
                transitionToState(WeaponState.MISSILE_AIMING);
            }
        }
    }

    /**
     * 处理导弹瞄准状态 (新增方法)
     */
    private void handleMissileAiming(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        
        // 停留在导弹起始帧
        currentAnimFrame = MISSILE_START_FRAME;
        
        // 寻找最近的敌舰作为导弹目标
        if (autoAimTarget == null || !autoAimTarget.isAlive() || autoAimTarget.isPhased()) {
            autoAimTarget = findNearestEnemyInRange(ship);
        }
        
        // 如果有目标，尝试转向瞄准
        if (autoAimTarget != null) {
            float angleToTarget = VectorUtils.getAngle(ship.getLocation(), autoAimTarget.getLocation());
            float shipFacing = ship.getFacing();
            float signedAngleDiff = Misc.getAngleDiff(shipFacing, angleToTarget);
            float maxRotationThisFrame = MISSILE_AIM_ROTATION_DEGREES_PER_SECOND * amount;
            
            // 计算目标在当前距离下的角度大小
            float distance = MathUtils.getDistance(ship.getLocation(), autoAimTarget.getLocation());
            float effectiveAngleThreshold = 1.0f; // 最小默认值，防止距离过远时阈值为0
            
            if (distance > 0) {
                // 通过三角函数计算目标的碰撞半径在当前距离下对应的角度
                float targetAngularSize = (float) Math.toDegrees(Math.atan(autoAimTarget.getCollisionRadius() / distance));
                
                // 应用缩放因子得到最终的角度阈值
                effectiveAngleThreshold = targetAngularSize * TARGET_SIZE_SCALE_FACTOR;
                
                // 确保阈值至少有1度，防止距离过远时发射判定太严格
                effectiveAngleThreshold = Math.max(1.0f, effectiveAngleThreshold);
            }
            
            // 在计算出的角度阈值内发射导弹
            if (Math.abs(signedAngleDiff) <= effectiveAngleThreshold) {
                transitionToState(WeaponState.MISSILE_ATTACK);
                return;
            }
            
            // 否则继续旋转瞄准
            if (Math.abs(signedAngleDiff) > 0.1f) {
                if (Math.abs(signedAngleDiff) <= maxRotationThisFrame) {
                    ship.setFacing(angleToTarget);
                } else {
                    if (signedAngleDiff > 0) {
                        ship.setFacing(shipFacing + maxRotationThisFrame);
                    } else {
                        ship.setFacing(shipFacing - maxRotationThisFrame);
                    }
                }
            }
        }
        
        // 超时则放弃瞄准，直接进入导弹攻击状态或挂回状态
        stateTimer += amount;
        if (stateTimer >= MISSILE_AIM_MAX_DURATION) {
            if (autoAimTarget != null) {
                // 超时但有目标，直接发射
                transitionToState(WeaponState.MISSILE_ATTACK);
            } else {
                // 超时且无目标，直接挂回
                transitionToState(WeaponState.TRANSFORMING_TO_PILE);
            }
        }
    }

    /**
     * 处理导弹攻击状态
     */
    private void handleMissileAttack(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 导弹发射逻辑
        if (missileCount < currentMissileBurstCount) {
            missileTimer -= amount;
            if (missileTimer <= 0f) {
                // 发射导弹
                launchMissile(engine, weapon);
                missileCount++;
                missileTimer = MISSILE_BURST_DELAY;
                
                // 更新动画帧以显示发射效果
                currentAnimFrame = MISSILE_START_FRAME;
                animTimer = 0f;
            } else {
                // 更新导弹发射动画
                animTimer += amount * weapon.getAnimation().getFrameRate();
                if (animTimer >= 1f) {
                    animTimer -= 1f;
                    currentAnimFrame++;
                    if (currentAnimFrame > MISSILE_END_FRAME) {
                        currentAnimFrame = MISSILE_START_FRAME;
                    }
                }
            }
        } else {
            // 所有导弹发射完毕，等待动画完成后再切换到挂回状态
            animTimer += amount * weapon.getAnimation().getFrameRate();
            if (animTimer >= 1f) {
                animTimer -= 1f;
                currentAnimFrame++;
                
                // 如果动画播放到最后一帧，切换到挂回状态
                if (currentAnimFrame > MISSILE_END_FRAME) {
                    transitionToState(WeaponState.TRANSFORMING_TO_PILE);
                }
            }
        }
    }

    /**
     * 处理转换回打桩模式状态
     */
    private void handleTransformToPile(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 更新动画
        animTimer += amount * weapon.getAnimation().getFrameRate() * 0.5f; // 减慢变形动画速度
        if (animTimer >= 1f) {
            animTimer -= 1f;
            currentAnimFrame++;
            
            // 变形完成后回到空闲状态
            if (currentAnimFrame >= TRANSFORM_TO_PILE_END) {
                // 给ARM_L_SOURCE武器重新装满弹药
                reloadArmWeapon(weapon.getShip());
                
                transitionToState(WeaponState.IDLE);
            }
        }
    }

    /**
     * 重新装填左臂武器的弹药
     */
    private void reloadArmWeapon(ShipAPI ship) {
        if (ship == null) return;
        
        // 查找ARM_L_SOURCE武器
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w != null && "ARM_L_SOURCE".equals(w.getSlot().getId())) {
                // 检查武器是否使用弹药系统
                if (w.usesAmmo() && w.getMaxAmmo() > 0) {
                    // 恢复武器弹药到最大值
                    w.setAmmo(w.getMaxAmmo());
                }
                break;
            }
        }
    }

    /**
     * 处理自动瞄准逻辑
     */
    private void handleAutoAim(float amount, ShipAPI ship) {
        float bestWeight = -Float.MAX_VALUE;
        ShipAPI currentBestTarget = null;
        
        // 搜索潜在目标
        for (ShipAPI potentialTarget : AIUtils.getNearbyEnemies(ship, AUTO_AIM_SEARCH_RANGE)) {
            if (potentialTarget.isFighter() || !potentialTarget.isAlive() || potentialTarget.isPhased() || potentialTarget == ship) {
                continue;
            }
            
            float angleToPTarget = VectorUtils.getAngle(ship.getLocation(), potentialTarget.getLocation());
            float angleDiff = Math.abs(Misc.getAngleDiff(ship.getFacing(), angleToPTarget));
            
            if (angleDiff <= AUTO_AIM_SEARCH_CONE_ANGLE / 2f) { // 在锥形范围内
                float weight = calculateTargetWeight(potentialTarget, ship);
                if (weight > bestWeight) {
                    bestWeight = weight;
                    currentBestTarget = potentialTarget;
                }
            }
        }
        
        autoAimTarget = currentBestTarget;
        
        // 自动转向逻辑
        if (autoAimTarget != null) {
            float angleToAimTarget = VectorUtils.getAngle(ship.getLocation(), autoAimTarget.getLocation());
            float shipFacing = ship.getFacing();
            float signedAngleDiff = Misc.getAngleDiff(shipFacing, angleToAimTarget); // 带符号的角度差
            float maxRotationThisFrame = AUTO_AIM_ROTATION_DEGREES_PER_SECOND * amount;
            
            if (Math.abs(signedAngleDiff) > 0.1f) { // 避免抖动
                if (Math.abs(signedAngleDiff) <= maxRotationThisFrame) {
                    ship.setFacing(angleToAimTarget); // 如果足够近，直接对准
                } else {
                    if (signedAngleDiff > 0) { // 目标在右边
                        ship.setFacing(shipFacing + maxRotationThisFrame);
                    } else { // 目标在左边
                        ship.setFacing(shipFacing - maxRotationThisFrame);
                    }
                }
            }
        }
    }

    /**
     * 计算目标权重，用于自动瞄准
     */
    private float calculateTargetWeight(ShipAPI potentialTarget, ShipAPI selfShip) {
        float distance = MathUtils.getDistance(selfShip, potentialTarget);
        float angleToTarget = VectorUtils.getAngle(selfShip.getLocation(), potentialTarget.getLocation());
        float shipFacing = selfShip.getFacing();
        float angleDifference = Math.abs(Misc.getAngleDiff(shipFacing, angleToTarget));
        
        // 计算角度分数和距离分数
        float angleScore = Math.max(0, (AUTO_AIM_SEARCH_CONE_ANGLE / 2f) - angleDifference);
        float distanceScore = Math.max(0, AUTO_AIM_SEARCH_RANGE - distance);
        
        // 权重计算
        float angleFactor = 2.0f;
        float distanceFactor = 1.0f;
        
        if (distance > 400f) {
            angleFactor = 1.0f;
            distanceFactor = 2.0f;
        }
        
        return 1.0f + (angleScore * angleFactor) + (distanceScore * distanceFactor);
    }

    /**
     * 查找范围内最近的敌舰
     */
    private ShipAPI findNearestEnemyInRange(ShipAPI ship) {
        ShipAPI nearest = null;
        float minDistance = Float.MAX_VALUE;
        
        for (ShipAPI enemy : AIUtils.getNearbyEnemies(ship, AUTO_AIM_SEARCH_RANGE)) {
            if (enemy.isFighter() || !enemy.isAlive() || enemy.isPhased()) {
                continue;
            }
            
            float distance = MathUtils.getDistance(ship, enemy);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = enemy;
            }
        }
        
        return nearest;
    }

    /**
     * 检查打桩机碰撞
     */
    private ShipAPI checkPileCollision(WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        
        // 优先检查自动瞄准的目标
        if (autoAimTarget != null && autoAimTarget.isAlive() && !autoAimTarget.isPhased()) {
            if (CollisionUtils.isPointWithinBounds(weapon.getFirePoint(0), autoAimTarget)) {
                return autoAimTarget;
            }
        }
        
        // 检查其他可能的碰撞目标
        List<ShipAPI> nearbyEnemies = AIUtils.getNearbyEnemies(ship, ship.getCollisionRadius() + 100f);
        for (ShipAPI enemy : nearbyEnemies) {
            if (enemy.isFighter() || !enemy.isAlive() || enemy.isPhased() || enemy == ship || enemy == autoAimTarget) {
                continue;
            }
            
            if (CollisionUtils.isPointWithinBounds(weapon.getFirePoint(0), enemy)) {
                return enemy;
            }
        }
        
        return null;
    }

    /**
     * 执行打桩效果
     */
    private void executePileEffect(CombatEngineAPI engine, WeaponAPI weapon, ShipAPI target) {
        Vector2f point = weapon.getFirePoint(0);
        float angle = 180 + weapon.getCurrAngle();
        ShipAPI ship = weapon.getShip();
        
        // 应用伤害
        engine.applyDamage(target, point, PILE_DAMAGE, DamageType.KINETIC, PILE_EMP_DAMAGE, true, false, ship);
        
        // 调整舰船速度，使其相对于目标的速度降为0，避免撞击
        adjustVelocityToAvoidCollision(ship, target);
        
        // 播放音效
        Global.getSoundPlayer().playSound("Moci_Weapon2Sound", 1f, 1f, point, new Vector2f());
        
        // 添加视觉效果
        addPileVisualEffects(engine, ship, point, angle);
        
        // 添加锚点和延迟爆炸效果 (新增)
        Global.getCombatEngine().addLayeredRenderingPlugin(
            new PileAnchorEffect(target, point, weapon.getCurrAngle(), ship, weapon));
    }
    
    /**
     * 调整舰船速度，避免与目标碰撞
     */
    private void adjustVelocityToAvoidCollision(ShipAPI ship, ShipAPI target) {
        // 获取两艘舰船的速度
        Vector2f shipVel = ship.getVelocity();
        Vector2f targetVel = target.getVelocity();
        
        // 计算相对速度
        Vector2f relativeVel = new Vector2f(shipVel.x - targetVel.x, shipVel.y - targetVel.y);
        
        // 获取从舰船指向目标的方向向量
        Vector2f dirToTarget = Vector2f.sub(target.getLocation(), ship.getLocation(), null);
        float distToTarget = dirToTarget.length();
        
        // 将方向向量标准化
        if (distToTarget > 0) {
            dirToTarget.scale(1f / distToTarget);
            
            // 计算相对速度在船到目标方向上的投影（点积）
            float velAlongDirToTarget = relativeVel.x * dirToTarget.x + relativeVel.y * dirToTarget.y;
            
            // 如果相对速度沿着从船到目标的方向（即船正在靠近目标）
            if (velAlongDirToTarget > 0) {
                // 计算需要抵消的速度分量
                float cancelX = velAlongDirToTarget * dirToTarget.x;
                float cancelY = velAlongDirToTarget * dirToTarget.y;
                
                // 更新船速，抵消相对速度分量
                ship.getVelocity().x = targetVel.x + (relativeVel.x - cancelX);
                ship.getVelocity().y = targetVel.y + (relativeVel.y - cancelY);
                
                // 添加一个小的远离目标的速度分量，防止立即再次碰撞
                ship.getVelocity().x -= dirToTarget.x * 20f;
                ship.getVelocity().y -= dirToTarget.y * 20f;
            }
        }
    }

    /**
     * 添加打桩视觉效果
     */
    private void addPileVisualEffects(CombatEngineAPI engine, ShipAPI ship, Vector2f point, float angle) {
        // 创建烟雾效果
        for (int i = 0; i < 20; i++) {
            Vector2f vel = MathUtils.getRandomPointOnCircumference(null, 
                    MathUtils.getRandomNumberInRange(20f, 100f));
            VectorUtils.rotate(vel, angle + MathUtils.getRandomNumberInRange(-20f, 20f));
            Vector2f.add(vel, ship.getVelocity(), vel);
            
            float grey = MathUtils.getRandomNumberInRange(0.5f, 0.75f);
            Color smokeColor = new Color(grey, grey, grey, MathUtils.getRandomNumberInRange(0.25f, 0.75f));
            
            Vector2f particlePos = MathUtils.getRandomPointInCircle(point, 5f);
            
            engine.addSmokeParticle(
                particlePos,
                vel,
                MathUtils.getRandomNumberInRange(5, 20),
                MathUtils.getRandomNumberInRange(0.25f, 0.75f),
                MathUtils.getRandomNumberInRange(0.25f, 1f),
                smokeColor);
        }
        
        // 爆炸粒子效果
        Color hitColor1 = new Color(255, 125, 50);
        Color hitColor2 = new Color(255, 20, 10);
        Color hitColor3 = new Color(255, 200, 50);
        Color hitColor4 = new Color(255, 234, 212);
        
        for (int i = 0; i < 10; i++) {
            Vector2f vel = MathUtils.getRandomPointOnCircumference(null, 
                    MathUtils.getRandomNumberInRange(50f, 250f));
            VectorUtils.rotate(vel, angle + MathUtils.getRandomNumberInRange(-20f, 20f));
            Vector2f.add(vel, ship.getVelocity(), vel);
            
            engine.addHitParticle(
                point,
                vel,
                MathUtils.getRandomNumberInRange(2, 4),
                1,
                MathUtils.getRandomNumberInRange(0.05f, 0.25f),
                hitColor1);
        }
        
        // 核心爆炸效果
        Vector2f vel = MathUtils.getRandomPointOnCircumference(null, 
                MathUtils.getRandomNumberInRange(20f, 100f));
        VectorUtils.rotate(vel, angle + MathUtils.getRandomNumberInRange(-20f, 20f));
        Vector2f.add(vel, ship.getVelocity(), vel);
        
        engine.addSmoothParticle(point, vel, 100, 0.5f, 0.5f, hitColor2);
        engine.addSmoothParticle(point, vel, 80, 0.75f, 0.15f, hitColor3);
        engine.addSmoothParticle(point, vel, 50, 1, 0.05f, hitColor4);
    }

    /**
     * 清理打桩状态
     */
    private void cleanupPileState(ShipAPI ship) {
        ship.getMutableStats().getMaxSpeed().unmodify("Moci_Weapon4");
        ship.getMutableStats().getAcceleration().unmodify("Moci_Weapon4");
        
        normalizeShipSpeed(ship);
        
        if (originalCollision != null) {
            ship.setCollisionClass(originalCollision);
            originalCollision = null;
        }
    }

    /**
     * 规范化舰船速度
     */
    private void normalizeShipSpeed(ShipAPI ship) {
        float defaultMaxSpeed = ship.getMaxSpeed();
        if (ship.getVelocity().length() > defaultMaxSpeed) {
            Vector2f velocity = ship.getVelocity();
            velocity.normalise();
            velocity.scale(defaultMaxSpeed);
            ship.getVelocity().set(velocity);
        }
    }

    /**
     * 发射导弹
     */
    private void launchMissile(CombatEngineAPI engine, WeaponAPI weapon) {
        float facing = weapon.getCurrAngle();
        float distance = 20f; // 发射点距离武器位置的偏移
        ShipAPI ship = weapon.getShip();
        
        // 创建发射位置
        Vector2f launchPos = MathUtils.getPoint(weapon.getFirePoint(0), distance, facing);
        
        // 发射导弹
        engine.spawnProjectile(
                ship,
                weapon,
                currentMissileId,
                launchPos,
                facing,
                ship.getVelocity());
        
        // 添加视觉效果
        Vector2f smokeVel = MathUtils.getRandomPointOnCircumference(null, 30f);
        VectorUtils.rotate(smokeVel, facing + 180f);
        Vector2f.add(smokeVel, ship.getVelocity(), smokeVel);
        
        engine.addSmokeParticle(launchPos, smokeVel, 20f, 0.8f, 1.5f, new Color(100, 100, 100, 200));
        engine.addSmoothParticle(launchPos, ship.getVelocity(), 100f, 0.3f, 0.2f, new Color(255, 150, 50, 255));
        
        // 播放发射音效
        Global.getSoundPlayer().playSound(currentMissileSound, 1f, 0.8f, launchPos, ship.getVelocity());
    }

    /**
     * 转换到新状态
     */
    private void transitionToState(WeaponState newState) {
        switch (newState) {
            case IDLE:
                currentAnimFrame = IDLE_FRAME;
                break;
            case PILE_CHARGE_UP:
                currentAnimFrame = IDLE_FRAME;
                break;
            case PILE_ATTACK:
                currentAnimFrame = PILE_START_FRAME;
                break;
            case TRANSFORMING_TO_MISSILE:
                currentAnimFrame = TRANSFORM_TO_MISSILE_START;
                break;
            case MISSILE_AIMING:
                currentAnimFrame = MISSILE_START_FRAME;
                break;
            case MISSILE_ATTACK:
                currentAnimFrame = MISSILE_START_FRAME;
                missileCount = 0;
                missileTimer = 0f;
                break;
            case TRANSFORMING_TO_PILE:
                currentAnimFrame = TRANSFORM_TO_PILE_START;
                break;
        }
        
        animTimer = 0f;
        stateTimer = 0f;
        currentState = newState;
    }
    
    /**
     * 触发打桩攻击
     */
    public void triggerPileAttack() {
        if (currentState == WeaponState.IDLE) {
            transitionToState(WeaponState.PILE_CHARGE_UP);
        }
    }
    
    /**
     * 获取舰船上的Moci_Weapon4Effect实例
     * @param ship 目标舰船
     * @return Moci_Weapon4Effect实例，如果不存在则返回null
     */
    public static Moci_Weapon4Effect getWeapon4EffectInstance(ShipAPI ship) {
        if (ship == null) return null;
        
        // 遍历所有武器查找使用Moci_Weapon4Effect的武器
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getEffectPlugin() instanceof Moci_Weapon4Effect) {
                return (Moci_Weapon4Effect) weapon.getEffectPlugin();
            }
        }
        return null;
    }

    /**
     * 打桩锚点效果类 (新增)
     * 实现打桩后在目标上留下的视觉效果和延迟爆炸
     */
    private static class PileAnchorEffect extends BaseCombatLayeredRenderingPlugin {
        private final ShipAPI ship;
        private final Vector2f offset;
        private final SpriteAPI sprite;
        private final float facing;
        private Vector2f location;
        private final ShipAPI source;
        private final WeaponAPI weapon;
        private boolean hasExploded = false;
        private float explosionTimer = 0f;
        private float lastBeepTime = 0f;
        private static final float BEEP_INTERVAL = 1f;
        
        // 缓存颜色对象
        private Color flashColor = new Color(1f, 1f, 1f, 1f);
        private final Color explosionColor1 = new Color(255, 79, 15, 255);
        private final Color explosionColor2 = new Color(255, 227, 185, 100);
        private final Vector2f zeroVector = new Vector2f(0f, 0f);

        /**
         * 构造函数
         */
        private PileAnchorEffect(
            ShipAPI ship, Vector2f location, float facing, ShipAPI source, WeaponAPI weapon) {
            this.ship = ship;
            this.source = source;
            this.weapon = weapon;
            // 将世界坐标转换为相对于目标舰船的偏移量
            this.offset = locationToOffSet(ship, location);
            // 加载打桩精灵
            this.sprite = Global.getSettings().getSprite("graphics/missiles/Moci_pile.png");
            this.facing = normalizeAngle(facing - ship.getFacing());
            this.location = new Vector2f(location);
            this.layer = CombatEngineLayers.ABOVE_SHIPS_LAYER;
        }

        @Override
        public float getRenderRadius() {
            return 30;
        }

        float timer = 0;

        /**
         * 渲染方法，绘制打桩锚点
         */
        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (layer == this.layer) {
                sprite.setAngle(facing + ship.getFacing() - 90);
                // 在爆炸前闪烁效果
                if (timer < EXPLODE_DELAY) {
                    float flashIntensity = (float) Math.sin(timer * 10f) * 0.5f + 0.5f;
                    flashColor = new Color(1f, 1f - flashIntensity, 1f - flashIntensity, 1f);
                    sprite.setColor(flashColor);
                } else {
                    sprite.setColor(Color.WHITE);
                }
                sprite.renderAtCenter(location.x, location.y);
            }
        }

        /**
         * 每帧更新方法，处理锚点效果逻辑
         */
        @Override
        public void advance(float amount) {
            if (isExpired) {
                return;
            }
            // 更新锚点位置，跟随目标舰船移动
            this.location.set(offSetToLocation(ship, offset));
            this.entity.getLocation().set(location);
            timer += amount;

            // 爆炸前播放警告音效
            if (timer < EXPLODE_DELAY) {
                if (timer - lastBeepTime >= BEEP_INTERVAL) {
                    Global.getSoundPlayer().playSound("Moci_Beep", 1f, 0.7f, location, zeroVector);
                    lastBeepTime = timer;
                }
            }

            // 延迟爆炸效果
            if (timer >= EXPLODE_DELAY && !hasExploded) {
                float f = normalizeAngle(facing + ship.getFacing());
                // 创建爆炸效果
                DamagingExplosionSpec spec =
                    new DamagingExplosionSpec(
                        0.1f,
                        150,
                        75,
                        2000,
                        1000,
                        CollisionClass.MISSILE_FF,
                        CollisionClass.MISSILE_FF,
                        3,
                        10,
                        0.12f,
                        25,
                        explosionColor1,
                        explosionColor2);
                Global.getCombatEngine().spawnDamagingExplosion(spec, source, location);
                
                hasExploded = true;
                explosionTimer = 0.5f; // 爆炸后显示0.5秒再消失
            }
            
            // 爆炸后的显示时间结束，标记为过期
            if (hasExploded) {
                explosionTimer -= amount;
                if (explosionTimer <= 0) {
                    isExpired = true;
                }
            }
        }

        private boolean isExpired = false;

        @Override
        public boolean isExpired() {
            return isExpired;
        }
    }
    
    /**
     * 将相对偏移量转换为世界坐标
     */
    private static Vector2f offSetToLocation(CombatEntityAPI entity, Vector2f offset) {
        if (entity == null || offset == null) return new Vector2f();
        Vector2f loc = new Vector2f(offset);
        VectorUtils.rotate(loc, entity.getFacing(), loc);
        Vector2f.add(loc, entity.getLocation(), loc);
        return loc;
    }

    /**
     * 将世界坐标转换为相对偏移量
     */
    private static Vector2f locationToOffSet(CombatEntityAPI entity, Vector2f loc) {
        if (entity == null || loc == null) return new Vector2f();
        Vector2f offset = new Vector2f(loc);
        Vector2f.sub(offset, entity.getLocation(), offset);
        VectorUtils.rotate(offset, -entity.getFacing(), offset);
        return offset;
    }

    /**
     * 规范化角度到-180到180度范围
     */
    private static float normalizeAngle(float ang) {
        while ((ang > 180f || ang < -180f)) {
            if (ang > 180f) {
                ang -= 360f;
            }
            if (ang < -180f) {
                ang += 360f;
            }
        }
        return ang;
    }
} 