package data.scripts.weapons;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.scripts.weapons.Moci_MechDecoWeaponMutexControl;

/**
 * 带护盾状态控制的机甲装饰武器效果
 * 基于Moci_MechProjDecoWeaponEffect，但有重要区别：
 * 1. 默认不隐藏SOURCE武器
 * 2. 检测护盾状态
 * 3. 护盾死亡时隐藏SOURCE武器并禁止开火
 * 4. 支持与RX93机体联动控制器的互斥控制
 */
public class Moci_MechProjDecoWeaponWithShieldControl_Effect implements EveryFrameWeaponEffectPlugin {
    
    // 槽位到护盾模块的映射关系
    private static final Map<String, String> SLOT_TO_SHIELD_MAP = new HashMap<>();
    static {
        SLOT_TO_SHIELD_MAP.put("ARM_R", "SHIELD_R");
        SLOT_TO_SHIELD_MAP.put("ARM_L", "SHIELD_L");
    }
    
    // 基础变量（从父类复制）
    boolean init = false;
    WeaponAPI source;
    WeaponAPI sourceMAIN;
    private static final float FOLLOW_RATIO = 0.33f;
    boolean wasFiring = false;
    protected boolean isAnimating = false;
    
    // 护盾控制变量
    private ShipAPI shield = null;
    private boolean weaponDisabledDueToShield = false; // 标记武器是否因护盾死亡而被禁用

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        
        // 初始化（从基础类复制）
        if (!init) {
            init = true;
            ShipAPI ship = weapon.getShip();
            String slotId = weapon.getSlot().getId();
            
            // 寻找关联武器
            String sourceId = slotId + "_SOURCE";
            String mainId = slotId + "_MAIN";

            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                }
                if (source == null && mainId.equals(w.getSlot().getId())) {
                    sourceMAIN = w;
                }
            }
            
            // 注意：不改变开火点，只做角度跟随
            
            // 初始化护盾检测
            initializeShieldDetection(weapon);
        }

        // ========== 互斥控制：检查光剑是否正在挥动 ==========
        if (Moci_MechDecoWeaponMutexControl.isBladeSwingActive(weapon)) {
            if (source != null) {
                Moci_MechDecoWeaponMutexControl.syncAngleOnly(weapon, source);
                // 注意：不隐藏SOURCE武器，因为这个脚本默认不隐藏
            }
            return; // 重要：直接返回，不执行后续控制逻辑
        }

        // 基础同步逻辑（从基础类复制）
        if (source != null) {
            weapon.setCurrAngle(source.getCurrAngle());
            
            // 开火动画同步
            if (weapon.getAnimation() != null) {
                boolean isFiring = source.isFiring() &&
                        (source.getCooldownRemaining() == 0 ||
                                (source.getSpec().getBurstSize() > 1 && source.isInBurst()));

                if (isFiring) {
                    if (!wasFiring) {
                        weapon.getAnimation().setFrame(0);
                    }
                    weapon.getAnimation().play();
                    wasFiring = true;
                    isAnimating = true;
                } else if (wasFiring) {
                    if (weapon.getAnimation().getFrame() >= weapon.getAnimation().getNumFrames() - 1) {
                        weapon.getAnimation().pause();
                        weapon.getAnimation().setFrame(0);
                        wasFiring = false;
                        isAnimating = false;
                    } else if (isAnimating) {
                        weapon.getAnimation().play();
                    }
                } else {
                    weapon.getAnimation().pause();
                    isAnimating = false;
                }
            }
            
            // 护盾状态控制SOURCE武器
            handleShieldControl();
        }

        // _MAIN槽位角度跟随（从基础类复制）
        if (sourceMAIN != null) {
            float shipFacing = weapon.getShip().getFacing();
            float sourceAngle = sourceMAIN.getCurrAngle() - shipFacing;

            while (sourceAngle > 180) sourceAngle -= 360;
            while (sourceAngle < -180) sourceAngle += 360;

            float targetAngle = shipFacing + sourceAngle * FOLLOW_RATIO;
            weapon.setCurrAngle(targetAngle);
        }
    }
    
    /**
     * 初始化护盾检测
     */
    private void initializeShieldDetection(WeaponAPI weapon) {
        String currentSlotId = weapon.getSlot().getId();
        String shieldId = SLOT_TO_SHIELD_MAP.get(currentSlotId);
        
        if (shieldId != null) {
            ShipAPI ship = weapon.getShip();
            if (ship != null && ship.getChildModulesCopy() != null) {
                for (ShipAPI module : ship.getChildModulesCopy()) {
                    if (module.getStationSlot() != null && module.getStationSlot().getId().equals(shieldId)) {
                        shield = module;
                        break;
                    }
                }
            }
        }
    }
    
    /**
     * 处理护盾状态控制
     */
    private void handleShieldControl() {
        if (source == null) return;
        
        boolean shieldAlive = (shield != null && shield.isAlive());
        
        if (shieldAlive) {
            // 护盾存活：SOURCE武器正常显示（不做任何处理）
        } else {
            // 护盾死亡：隐藏SOURCE武器并禁止开火
            hideSourceWeapon();
            source.setForceNoFireOneFrame(true);
            
            // 只在第一次护盾死亡时永久禁用武器
            if (!weaponDisabledDueToShield) {
                source.disable(true);
                weaponDisabledDueToShield = true;
            }
        }
    }
    
    /**
     * 隐藏SOURCE武器（使用alpha值控制）
     */
    private void hideSourceWeapon() {
        if (source == null) return;
        
        // 设置所有贴图组件的alpha为0（隐藏），保持原有RGB颜色不变
        if (source.getSprite() != null) {
            Color currentColor = source.getSprite().getColor();
            source.getSprite().setColor(new Color(currentColor.getRed(), currentColor.getGreen(), currentColor.getBlue(), 0));
        }
        if (source.getBarrelSpriteAPI() != null) {
            Color currentColor = source.getBarrelSpriteAPI().getColor();
            source.getBarrelSpriteAPI().setColor(new Color(currentColor.getRed(), currentColor.getGreen(), currentColor.getBlue(), 0));
        }
        if (source.getUnderSpriteAPI() != null) {
            Color currentColor = source.getUnderSpriteAPI().getColor();
            source.getUnderSpriteAPI().setColor(new Color(currentColor.getRed(), currentColor.getGreen(), currentColor.getBlue(), 0));
        }
        if (source.getGlowSpriteAPI() != null) {
            Color currentColor = source.getGlowSpriteAPI().getColor();
            source.getGlowSpriteAPI().setColor(new Color(currentColor.getRed(), currentColor.getGreen(), currentColor.getBlue(), 0));
        }
    }


    
    /**
     * 添加新的槽位映射关系
     */
    public static void addSlotMapping(String slotId, String shieldId) {
        SLOT_TO_SHIELD_MAP.put(slotId, shieldId);
    }
} 