package data.scripts.weapons;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
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
 * 
 * 参考：armaa_valkazardEffect的架构
 * 
 * @author Moci
 */
public class Moci_MechBodyController_RX93_backup implements EveryFrameWeaponEffectPlugin {
    private static final Logger log = Global.getLogger(Moci_MechBodyController_RX93.class);
    
    // ========== 腿部动画相关变量 ==========
    private static final Map<String, int[]> SEQUENCE = new HashMap<>();
    private boolean initialized = false;
    
    private static int maxFrames = 1;
    private static int defaultFrame = 4;
    static {
        SEQUENCE.put("LEFT", new int[]{0, maxFrames});
        SEQUENCE.put("RIGHT", new int[]{0, maxFrames});
        SEQUENCE.put("FRONT", new int[]{0, defaultFrame});
        SEQUENCE.put("BACK", new int[]{defaultFrame, maxFrames});
    }
    
    String currentSequence = null;
    int current = 4;
    float timer = 0;
    
    // ========== 机体联动相关变量 ==========
    private ShipAPI ship;
    
    // 机体部件引用
    private WeaponAPI armL, armR;
    private WeaponAPI torso;
    private WeaponAPI shoulderL, shoulderR;
    private WeaponAPI bladeL, bladeR; // 左右手光剑
    
    // 原始位置记录
    private float originalArmLPos = 0f;
    private float originalArmRPos = 0f;
    private float originalShoulderLPos = 0f;
    private float originalShoulderRPos = 0f;
    
    // 角度偏移常量
    private final float LPAULDRONOFFSET = -5f;
    private final float RPAULDRONOFFSET = 5f;
    
    // 互斥控制标记（区分左右手）
    private static final String BLADE_SWING_ACTIVE_LEFT_KEY = "MOCI_BLADE_SWING_ACTIVE_LEFT";
    private static final String BLADE_SWING_ACTIVE_RIGHT_KEY = "MOCI_BLADE_SWING_ACTIVE_RIGHT";
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon.getAnimation() == null) return;
        
        ship = weapon.getShip();
        if (!ship.isAlive() || ship.isHulk()) {
            weapon.getAnimation().pause();
            return;
        }
        
        // ========== 初始化 ==========
        if (!initialized) {
            initializeLegAnimation(weapon);
            initializeMechControl();
        }
        
        // ========== 腿部动画控制 ==========
        updateLegAnimation(amount, weapon);
        
        // ========== 机体联动控制 ==========
        updateMechControl(amount, engine);
    }
    
    /**
     * 初始化腿部动画
     */
    private void initializeLegAnimation(WeaponAPI weapon) {
        if (weapon.getAnimation() != null) {
            maxFrames = weapon.getAnimation().getNumFrames() - 1;
            defaultFrame = Math.min(4, maxFrames);
        }
        SEQUENCE.put("FRONT", new int[]{0, defaultFrame});
        SEQUENCE.put("BACK", new int[]{defaultFrame, maxFrames});
        initialized = true;
    }
    
    /**
     * 初始化机体联动控制
     */
    private void initializeMechControl() {
        log.info("[RX93 Controller] 初始化机体联动控制");
        
        // 查找各个肢体武器
        for (WeaponAPI w : ship.getAllWeapons()) {
            String slotId = w.getSlot().getId();
            
            switch (slotId) {
                case "BODY":
                    torso = w;
                    log.info("[RX93 Controller] 找到躯干: " + slotId);
                    break;
                case "ARM_L":
                    armL = w;
                    if (armL.getSprite() != null) {
                        originalArmLPos = armL.getSprite().getCenterY();
                        log.info("[RX93 Controller] 找到左手臂: " + slotId + ", 原始Y位置: " + originalArmLPos);
                    }
                    break;
                case "ARM_R":
                    armR = w;
                    if (armR.getSprite() != null) {
                        originalArmRPos = armR.getSprite().getCenterY();
                        log.info("[RX93 Controller] 找到右手臂: " + slotId + ", 原始Y位置: " + originalArmRPos);
                    }
                    break;
                case "SHOULDER_L":
                    shoulderL = w;
                    if (shoulderL.getSprite() != null) {
                        originalShoulderLPos = shoulderL.getSprite().getCenterY();
                        log.info("[RX93 Controller] 找到左肩甲: " + slotId + ", 原始Y位置: " + originalShoulderLPos);
                    }
                    break;
                case "SHOULDER_R":
                    shoulderR = w;
                    if (shoulderR.getSprite() != null) {
                        originalShoulderRPos = shoulderR.getSprite().getCenterY();
                        log.info("[RX93 Controller] 找到右肩甲: " + slotId + ", 原始Y位置: " + originalShoulderRPos);
                    }
                    break;
                case "ARM_L_SOURCE":
                    bladeL = w;
                    log.info("[RX93 Controller] 找到左手光剑: " + slotId);
                    break;
                case "ARM_R_SOURCE":
                    bladeR = w;
                    log.info("[RX93 Controller] 找到右手光剑: " + slotId);
                    break;
            }
        }
    }
    
    /**
     * 更新腿部动画
     */
    private void updateLegAnimation(float amount, WeaponAPI weapon) {
        if (currentSequence == null) {
            weapon.getAnimation().pause();
        }
        
        if (currentSequence != null) {
            int[] sq = SEQUENCE.get(currentSequence);
            
            boolean isAccelerating = ship.getEngineController().isAccelerating();
            boolean isDecelerating = ship.getEngineController().isDecelerating();
            boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();
            
            timer += amount * weapon.getAnimation().getFrameRate();
            while (timer >= 1) {
                timer -= 1;
                if ("FRONT".equals(currentSequence)) {
                    if (isAccelerating) {
                        current = Math.max(current - 1, sq[0]);
                    } else {
                        if (current < defaultFrame) {
                            current = Math.min(current + 1, defaultFrame);
                        }
                    }
                } else if ("BACK".equals(currentSequence)) {
                    if (isDecelerating || isBackwards) {
                        current = Math.min(current + 1, sq[1]);
                    } else {
                        if (current > defaultFrame) {
                            current = Math.max(current - 1, defaultFrame);
                        }
                    }
                }
            }
            
            boolean shouldReset = false;
            if ("FRONT".equals(currentSequence)) {
                shouldReset = (current == defaultFrame && !isAccelerating);
            } else if ("BACK".equals(currentSequence)) {
                shouldReset = (current == defaultFrame && !isDecelerating && !isBackwards);
            }
            
            if (shouldReset) {
                currentSequence = null;
                timer = 0;
            }
        } else {
            boolean isAccelerating = ship.getEngineController().isAccelerating();
            boolean isDecelerating = ship.getEngineController().isDecelerating();
            boolean isBackwards = ship.getEngineController().isAcceleratingBackwards();
            
            if (isAccelerating) {
                currentSequence = "FRONT";
            }
            if (isDecelerating || isBackwards) {
                currentSequence = "BACK";
            }
            
            if (currentSequence != null) {
                timer = 0;
                weapon.getAnimation().play();
            }
        }
        
        if (currentSequence == null) {
            current = defaultFrame;
        }
        
        weapon.getAnimation().setFrame(current);
    }
    
    /**
     * 更新机体联动控制
     */
    private void updateMechControl(float amount, CombatEngineAPI engine) {
        if (ship == null) return;
        
        float global = ship.getFacing();
        
        // ========== 检测左右手光剑状态 ==========
        boolean leftBladeActive = isBladeActive(bladeL);
        boolean rightBladeActive = isBladeActive(bladeR);
        
        // 设置全局标记（供其他脚本检查）
        setBladeSwingActive(leftBladeActive, rightBladeActive);
        
        // ========== 左手控制（每帧都执行） ==========
        if (armL != null && bladeL != null) {
            if (leftBladeActive) {
                // 光剑激活：更新手臂控制
                updateArmControl(
                    armL, bladeL, shoulderL,
                    originalArmLPos, originalShoulderLPos,
                    amount, global, false // false = 左手
                );
            } else {
                // 光剑未激活：保持原始位置
                if (armL.getSprite() != null) {
                    armL.getSprite().setCenterY(originalArmLPos);
                }
                if (shoulderL != null && shoulderL.getSprite() != null) {
                    shoulderL.getSprite().setCenterY(originalShoulderLPos);
                }
            }
        }
        
        // ========== 右手控制（每帧都执行） ==========
        if (armR != null && bladeR != null) {
            if (rightBladeActive) {
                // 光剑激活：更新手臂控制
                updateArmControl(
                    armR, bladeR, shoulderR,
                    originalArmRPos, originalShoulderRPos,
                    amount, global, true // true = 右手
                );
            } else {
                // 光剑未激活：保持原始位置
                if (armR.getSprite() != null) {
                    armR.getSprite().setCenterY(originalArmRPos);
                }
                if (shoulderR != null && shoulderR.getSprite() != null) {
                    shoulderR.getSprite().setCenterY(originalShoulderRPos);
                }
            }
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
        boolean anyBladeActive = leftBladeActive || rightBladeActive;
        if (anyBladeActive) {
            WeaponAPI activeBlade = rightBladeActive ? bladeR : bladeL;
            updateAIBehavior(activeBlade);
        }
    }
    
    /**
     * 检查光剑是否激活
     */
    private boolean isBladeActive(WeaponAPI blade) {
        if (blade == null || blade.isDisabled()) return false;
        // 只有在充能时才激活（不包括冷却）
        boolean active = blade.getChargeLevel() > 0f;
        if (active) {
            log.debug("[RX93 Controller] 光剑激活检查: " + blade.getSlot().getId() + 
                ", 充能等级=" + blade.getChargeLevel());
        }
        return active;
    }
    
    /**
     * 更新手臂控制
     */
    private void updateArmControl(
        WeaponAPI arm, WeaponAPI blade, WeaponAPI shoulder,
        float originalArmPos, float originalShoulderPos,
        float amount, float global, boolean isRightHand
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
        // 光剑武器的角度由Moci_Blade_BeamEffect控制
        // 手臂装饰武器跟随光剑武器
        arm.setCurrAngle(blade.getCurrAngle());
        
        // 手臂Y轴位置调整（每帧都执行）
        if (arm.getSprite() != null) {
            float yOffset = 16 * sinceB;
            float newY = originalArmPos - yOffset;
            arm.getSprite().setCenterY(newY);
        }
        
        // 肩甲联动
        if (shoulder != null && torso != null) {
            float shoulderOffset = isRightHand ? RPAULDRONOFFSET : LPAULDRONOFFSET;
            
            shoulder.setCurrAngle(
                torso.getCurrAngle() + shoulderOffset * sineA +
                MathUtils.getShortestRotation(torso.getCurrAngle(), arm.getCurrAngle()) * 0.70f
            );
            
            // 调整肩甲Y轴位置（每帧都执行）
            if (shoulder.getSprite() != null) {
                float yOffset = 8 * sinceB;
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
}
