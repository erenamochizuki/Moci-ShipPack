package data.scripts.weapons;

import org.lazywizard.lazylib.MathUtils;
import org.magiclib.util.MagicAnim;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * RX-93 v2机体联动控制器（挂在LEGS装饰武器上）
 * 
 * 功能：
 * 1. 腿部动画控制（根据引擎状态）- 保留原有功能
 * 2. 机体联动控制（躯干、手臂、肩甲）- 新增功能
 * 3. 检测左右手光剑的状态并响应
 * 4. 盾牌激活时禁用左手Y轴移动
 * 5. 任何动画激活时隐藏船体贴图
 * 
 * 槽位对应（基于Moci_RX_93_v2.ship）：
 * - LEGS: 本控制器挂载位置
 * - ARM_L: 左手臂装饰（Moci_MechProjDecoWeaponEffect_shield_arm）
 * - ARM_R: 右手臂装饰（Moci_MechProjDecoWeaponEffect_noHide）
 * - ARM_L_SOURCE: 左手光剑武器（Moci_BladeEffectPlugin）
 * - ARM_R_SOURCE: 右手光剑武器（Moci_BladeEffectPlugin）
 * - SHOULDER_L: 左肩甲（Moci_MechShoulderDecoWeaponEffect）
 * - SHOULDER_R: 右肩甲（Moci_MechShoulderDecoWeaponEffect）
 * - BODY: 躯干装饰（无代码）
 * - SHIELD: 盾牌武器
 * 
 * @author Moci
 */
public class Moci_MechBodyController_RX93 implements EveryFrameWeaponEffectPlugin {
    
    // ========== 腿部动画相关变量 ==========
    private static final String FRONT_SEQUENCE = "FRONT";
    private static final String BACK_SEQUENCE = "BACK";
    private static final int DEFAULT_FRAME_TARGET = 4;

    private boolean mechControlInitialized = false;
    private boolean legAnimationInitialized = false;

    private int maxFrame = 0;
    private int defaultFrame = 0;

    private String currentSequence = null;
    private int currentFrame = 0;
    private float timer = 0f;
    
    // ========== 机体联动相关变量 ==========
    private ShipAPI ship;
    
    // 机体部件引用
    private WeaponAPI armL, armR;
    private WeaponAPI torso;
    private WeaponAPI shoulderL, shoulderR;
    private WeaponAPI bladeL, bladeR; // 左右手光剑
    private ShipAPI shieldModule = null; // 盾牌子模块（不是武器）
    
    // 原始位置记录
    private float originalArmLPos = 0f;
    private float originalArmRPos = 0f;
    private float originalShoulderLPos = 0f;
    private float originalShoulderRPos = 0f;
    private float originalShieldModuleY = 0f; // 盾牌子模块原始Y位置
    private float originalBladeLY = 0f; // 左手光剑原始Y位置
    private float originalBladeRY = 0f; // 右手光剑原始Y位置
    
    // 根据舰船级别计算的偏移系数
    private float offsetMultiplier = 1.0f;
    
    // 角度偏移常量
    private final float LPAULDRONOFFSET = -5f;
    private final float RPAULDRONOFFSET = 5f;
    
    // Y轴偏移基础值（默认值，用于驱逐级及以上）
    private static final float BASE_ARM_OFFSET = 16f;      // 手臂基础偏移
    private static final float BASE_SHOULDER_OFFSET = 8f;  // 肩甲基础偏移
    
    // 互斥控制标记（区分左右手）
    private static final String BLADE_SWING_ACTIVE_LEFT_KEY = "MOCI_BLADE_SWING_ACTIVE_LEFT";
    private static final String BLADE_SWING_ACTIVE_RIGHT_KEY = "MOCI_BLADE_SWING_ACTIVE_RIGHT";
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null || weapon.getAnimation() == null) return;
        
        ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        syncLegAnimationState(weapon);
        // 腿部帧由脚本手动控制，不能和内置动画自动播放并用。
        weapon.getAnimation().pause();

        if (!ship.isAlive() || ship.isHulk()) {
            resetLegAnimation(weapon);
            return;
        }
        
        // ========== 初始化 ==========
        if (!mechControlInitialized) {
            initializeMechControl();
            mechControlInitialized = true;
        }
        
        // ========== 腿部动画控制 ==========
        updateLegAnimation(amount, weapon);
        
        // ========== 机体联动控制 ==========
        updateMechControl(amount, engine);
    }
    
    /**
     * 同步腿部动画的帧边界
     */
    private void syncLegAnimationState(WeaponAPI weapon) {
        int currentMaxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        if (!legAnimationInitialized || currentMaxFrame != maxFrame) {
            maxFrame = currentMaxFrame;
            defaultFrame = Math.min(DEFAULT_FRAME_TARGET, maxFrame);
            currentFrame = clampLegFrame(legAnimationInitialized ? currentFrame : defaultFrame);
            legAnimationInitialized = true;
        }
    }
    
    /**
     * 初始化机体联动控制
     */
    private void initializeMechControl() {
        // 查找各个肢体武器
        for (WeaponAPI w : ship.getAllWeapons()) {
            String slotId = w.getSlot().getId();
            
            switch (slotId) {
                case "BODY":
                    torso = w;
                    break;
                case "ARM_L":
                    armL = w;
                    if (armL.getSprite() != null) {
                        originalArmLPos = armL.getSprite().getCenterY();
                    }
                    break;
                case "ARM_R":
                    armR = w;
                    if (armR.getSprite() != null) {
                        originalArmRPos = armR.getSprite().getCenterY();
                    }
                    break;
                case "SHOULDER_L":
                    shoulderL = w;
                    if (shoulderL.getSprite() != null) {
                        originalShoulderLPos = shoulderL.getSprite().getCenterY();
                    }
                    break;
                case "SHOULDER_R":
                    shoulderR = w;
                    if (shoulderR.getSprite() != null) {
                        originalShoulderRPos = shoulderR.getSprite().getCenterY();
                    }
                    break;
                case "ARM_L_SOURCE":
                    bladeL = w;
                    if (bladeL.getSprite() != null) {
                        originalBladeLY = bladeL.getSprite().getCenterY();
                    }
                    break;
                case "ARM_R_SOURCE":
                    bladeR = w;
                    if (bladeR.getSprite() != null) {
                        originalBladeRY = bladeR.getSprite().getCenterY();
                    }
                    break;
            }
        }
        
        // 查找SHIELD_L子模块（正确的盾牌检测方法）
        for (ShipAPI module : ship.getChildModulesCopy()) {
            if (module.getStationSlot() != null && 
                "SHIELD_L".equals(module.getStationSlot().getId())) {
                shieldModule = module;
                if (shieldModule.getSpriteAPI() != null) {
                    originalShieldModuleY = shieldModule.getSpriteAPI().getCenterY();
                }
                break;
            }
        }
        
        // 根据舰船级别计算偏移系数
        offsetMultiplier = calculateOffsetMultiplier(ship);
    }
    
    /**
     * 根据舰船级别计算偏移系数
     * 护卫级和战机级使用0.5倍偏移，其他级别使用1.0倍偏移
     */
    private float calculateOffsetMultiplier(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return 1.0f;
        }
        
        ShipAPI.HullSize hullSize = ship.getHullSize();
        
        switch (hullSize) {
            case FIGHTER:      // 战机级
            case FRIGATE:      // 护卫级
                return 0.35f;   // 偏移常数
            
            case DESTROYER:    // 驱逐级
            case CRUISER:      // 巡洋级
            case CAPITAL_SHIP: // 主力舰级
            default:
                return 1.0f;   // 使用默认偏移量
        }
    }
    
    /**
     * 更新腿部动画
     */
    private void updateLegAnimation(float amount, WeaponAPI weapon) {
        if (currentSequence != null) {
            boolean isAccelerating = ship.getEngineController().isAccelerating();
            boolean isDecelerating = ship.getEngineController().isDecelerating();
            boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();
            
            timer += amount * Math.max(0f, weapon.getAnimation().getFrameRate());
            while (timer >= 1f) {
                timer -= 1f;
                if (FRONT_SEQUENCE.equals(currentSequence)) {
                    if (isAccelerating) {
                        currentFrame--;
                    } else {
                        if (currentFrame < defaultFrame) {
                            currentFrame++;
                        }
                    }
                } else if (BACK_SEQUENCE.equals(currentSequence)) {
                    if (isDecelerating || isBackwards) {
                        currentFrame++;
                    } else {
                        if (currentFrame > defaultFrame) {
                            currentFrame--;
                        }
                    }
                }
                currentFrame = clampLegFrame(currentFrame);
            }
            
            boolean shouldReset = false;
            if (FRONT_SEQUENCE.equals(currentSequence)) {
                shouldReset = (currentFrame == defaultFrame && !isAccelerating);
            } else if (BACK_SEQUENCE.equals(currentSequence)) {
                shouldReset = (currentFrame == defaultFrame && !isDecelerating && !isBackwards);
            }
            
            if (shouldReset) {
                currentSequence = null;
                timer = 0f;
            }
        } else {
            boolean isAccelerating = ship.getEngineController().isAccelerating();
            boolean isDecelerating = ship.getEngineController().isDecelerating();
            boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();
            
            if (isAccelerating) {
                currentSequence = FRONT_SEQUENCE;
            }
            if (isDecelerating || isBackwards) {
                currentSequence = BACK_SEQUENCE;
            }
            
            if (currentSequence != null) {
                timer = 0f;
            }
        }
        
        if (currentSequence == null) {
            currentFrame = defaultFrame;
        }
        
        setSafeLegFrame(weapon, currentFrame);
    }

    private void resetLegAnimation(WeaponAPI weapon) {
        currentSequence = null;
        timer = 0f;
        currentFrame = defaultFrame;
        setSafeLegFrame(weapon, currentFrame);
    }

    private int clampLegFrame(int frame) {
        return Math.max(0, Math.min(frame, maxFrame));
    }

    private void setSafeLegFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        weapon.getAnimation().setFrame(clampLegFrame(frame));
    }
    
    /**
     * 更新机体联动控制
     */
    private void updateMechControl(float amount, CombatEngineAPI engine) {
        if (ship == null) return;
        
        float global = ship.getFacing();
        
        // ========== 检测盾牌状态 ==========
        boolean shieldActive = isShieldActive();
        
        // ========== 检测左右手光剑状态 ==========
        boolean leftBladeActive = isBladeActive(bladeL);
        boolean rightBladeActive = isBladeActive(bladeR);
        
        // 设置全局标记（供其他脚本检查）
        setBladeSwingActive(leftBladeActive, rightBladeActive);
        
        // ========== 检查是否有任何动画激活 ==========
        boolean anyAnimationActive = leftBladeActive || rightBladeActive;
        
        // ========== 左手光剑控制 ==========
        if (leftBladeActive && armL != null && bladeL != null) {
            updateArmControl(
                armL, bladeL, shoulderL,
                originalArmLPos, originalShoulderLPos,
                amount, global, false, shieldActive // false = 左手
            );
        } else if (armL != null) {
            restoreArmPosition(armL, originalArmLPos);
            if (shoulderL != null) {
                restoreArmPosition(shoulderL, originalShoulderLPos);
            }
            // 恢复SOURCE武器和盾牌位置
            restoreSourceAndShieldPosition(true);
        }
        
        // ========== 右手光剑控制 ==========
        if (rightBladeActive && armR != null && bladeR != null) {
            updateArmControl(
                armR, bladeR, shoulderR,
                originalArmRPos, originalShoulderRPos,
                amount, global, true, false // true = 右手, 右手不受盾牌影响
            );
        } else if (armR != null) {
            restoreArmPosition(armR, originalArmRPos);
            if (shoulderR != null) {
                restoreArmPosition(shoulderR, originalShoulderRPos);
            }
            // 恢复SOURCE武器位置
            restoreSourceAndShieldPosition(false);
        }
        
        // ========== 躯干联动（优先级：右手 > 左手） ==========
        if (torso != null) {
            WeaponAPI activeBlade = rightBladeActive ? bladeR : (leftBladeActive ? bladeL : null);
            boolean isRightHand = rightBladeActive;
            
            if (activeBlade != null) {
                updateTorsoControl(torso, activeBlade, global, isRightHand, 0f);
            }
        }
        
        // ========== AI行为控制（任一光剑激活时） ==========
        if (anyAnimationActive) {
            WeaponAPI activeBlade = rightBladeActive ? bladeR : bladeL;
            updateAIBehavior(activeBlade);
        }
    }
    
    /**
     * 检查光剑是否激活（只有光剑武器才触发动画）
     */
    private boolean isBladeActive(WeaponAPI blade) {
        if (blade == null || blade.isDisabled()) return false;
        
        // 检查武器ID是否以_Sabre结尾（光剑标识）
        String weaponId = blade.getSpec().getWeaponId();
        if (weaponId == null || !weaponId.endsWith("_Sabre")) {
            return false;
        }
        
        return blade.getChargeLevel() > 0f;
    }
    
    /**
     * 检查盾牌是否激活（检查子模块是否存活）
     */
    private boolean isShieldActive() {
        // 盾牌是子模块，检查模块是否存活
        return (shieldModule != null && shieldModule.isAlive());
    }
    
    /**
     * 更新手臂控制
     */
    private void updateArmControl(
        WeaponAPI arm, WeaponAPI blade, WeaponAPI shoulder,
        float originalArmPos, float originalShoulderPos,
        float amount, float global, boolean isRightHand, boolean shieldActive
    ) {
        float sineA = 0f, sinceB = 0f;
        
        // 计算充能等级
        if (blade.getChargeLevel() < 1) {
            sineA = MagicAnim.smoothNormalizeRange(blade.getChargeLevel(), 0.3f, 0.9f);
            sinceB = MagicAnim.smoothNormalizeRange(blade.getChargeLevel(), 0.3f, 1f);
        } else {
            sineA = 1;
            sinceB = 1;
        }
        
        // ========== 手臂跟随光剑武器角度 ==========
        arm.setCurrAngle(blade.getCurrAngle());
        
        // ========== 手臂Y轴位置调整 ==========
        if (arm.getSprite() != null) {
            // 根据舰船级别计算实际偏移量
            float yOffset = BASE_ARM_OFFSET * offsetMultiplier * sinceB;
            float newY = originalArmPos - yOffset;
            arm.getSprite().setCenterY(newY);
            
            // ========== 让SOURCE武器和盾牌子模块跟随手臂Y轴移动 ==========
            if (!isRightHand && shieldActive) {
                // 左手且盾牌存活：移动盾牌子模块
                moveShieldModule(yOffset);
            }
            // SOURCE武器（光剑）跟随手臂Y轴移动
            moveSourceWeapon(blade, yOffset);
        }
        
        // ========== 肩甲联动 ==========
        if (shoulder != null && torso != null) {
            float shoulderOffset = isRightHand ? RPAULDRONOFFSET : LPAULDRONOFFSET;
            
            shoulder.setCurrAngle(
                torso.getCurrAngle() + shoulderOffset * sineA +
                MathUtils.getShortestRotation(torso.getCurrAngle(), arm.getCurrAngle()) * 0.70f
            );
            
            // 肩甲Y轴位置调整（根据舰船级别计算实际偏移量）
            if (shoulder.getSprite() != null) {
                float yOffset = BASE_SHOULDER_OFFSET * offsetMultiplier * sinceB;
                float newY = originalShoulderPos - yOffset;
                shoulder.getSprite().setCenterY(newY);
            }
        }
    }
    
    /**
     * 更新躯干控制
     */
    private void updateTorsoControl(WeaponAPI torso, WeaponAPI activeBlade, float global, boolean isRightHand, float swingLevel) {
        if (torso.getChargeLevel() == 0 &&
            (ship.getSelectedGroupAPI() == null ||
             !ship.getSelectedGroupAPI().getWeaponsCopy().contains(torso))) {
            
            float sineA = 0f;
            if (activeBlade.getChargeLevel() < 1) {
                sineA = MagicAnim.smoothNormalizeRange(activeBlade.getChargeLevel(), 0.3f, 0.9f);
            } else {
                sineA = 1;
            }
            
            // 躯干跟随光剑武器的角度（使用sineA作为插值因子）
            float aim = MathUtils.getShortestRotation(global, activeBlade.getCurrAngle());
            torso.setCurrAngle(global + aim * 0.3f * sineA);
        }
    }
    
    /**
     * 更新AI行为控制
     */
    private void updateAIBehavior(WeaponAPI activeBlade) {
        if (!ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.BACKING_OFF) &&
            !ship.getAIFlags().hasFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE)) {
            
            if (ship.getShipTarget() != null &&
                MathUtils.getDistanceSquared(ship.getLocation(), ship.getShipTarget().getLocation()) < 500f * 500f &&
                !activeBlade.isDisabled()) {
                
                if (ship.getAI() != null) {
                    ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
                    Global.getCombatEngine().headInDirectionWithoutTurning(
                        ship,
                        activeBlade.getCurrAngle(),
                        ship.getMaxSpeed()
                    );
                }
            }
        }
    }
    
    /**
     * 设置光剑挥动状态（用于互斥控制）
     */
    private void setBladeSwingActive(boolean leftActive, boolean rightActive) {
        if (ship == null) return;
        ship.setCustomData(BLADE_SWING_ACTIVE_LEFT_KEY, leftActive);
        ship.setCustomData(BLADE_SWING_ACTIVE_RIGHT_KEY, rightActive);
    }
    
    /**
     * 恢复手臂位置
     */
    private void restoreArmPosition(WeaponAPI weapon, float originalPos) {
        if (weapon == null || weapon.getSprite() == null) return;
        weapon.getSprite().setCenterY(originalPos);
    }
    
    /**
     * 移动盾牌子模块的Y轴位置
     */
    private void moveShieldModule(float yOffset) {
        if (shieldModule == null || !shieldModule.isAlive()) return;
        
        // 子模块的位置需要通过设置相对于父舰船的偏移来调整
        // 注意：这里使用精灵的Y轴偏移
        if (shieldModule.getSpriteAPI() != null) {
            float newY = originalShieldModuleY - yOffset;
            shieldModule.getSpriteAPI().setCenterY(newY);
        }
    }
    
    /**
     * 移动SOURCE武器的Y轴位置
     */
    private void moveSourceWeapon(WeaponAPI weapon, float yOffset) {
        if (weapon == null || weapon.getSprite() == null) return;
        
        float originalY = (weapon == bladeL) ? originalBladeLY : originalBladeRY;
        float newY = originalY - yOffset;
        weapon.getSprite().setCenterY(newY);
    }
    
    /**
     * 恢复SOURCE武器和盾牌位置
     */
    private void restoreSourceAndShieldPosition(boolean isLeftHand) {
        if (isLeftHand) {
            // 恢复左手SOURCE武器
            if (bladeL != null && bladeL.getSprite() != null) {
                bladeL.getSprite().setCenterY(originalBladeLY);
            }
            // 恢复盾牌子模块
            if (shieldModule != null && shieldModule.isAlive() && shieldModule.getSpriteAPI() != null) {
                shieldModule.getSpriteAPI().setCenterY(originalShieldModuleY);
            }
        } else {
            // 恢复右手SOURCE武器
            if (bladeR != null && bladeR.getSprite() != null) {
                bladeR.getSprite().setCenterY(originalBladeRY);
            }
        }
    }
}
