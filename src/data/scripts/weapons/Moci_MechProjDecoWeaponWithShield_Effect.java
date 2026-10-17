package data.scripts.weapons;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;

/**
 * 带护盾状态（但不顶盾）检测的机甲装饰武器效果
 * 继承Moci_MechProjDecoWeaponEffect，增加护盾动画控制功能
 * 专门处理手臂槽位的护盾关联：
 * - ARM_R → SHIELD_R
 * - ARM_L → SHIELD_L
 * 同时支持_TYPE槽位的武器类型帧切换功能
 */
public class Moci_MechProjDecoWeaponWithShield_Effect extends Moci_MechProjDecoWeaponEffect {
    
    // 槽位到护盾模块的映射关系
    private static final Map<String, String> SLOT_TO_SHIELD_MAP = new HashMap<>();
    static {
        SLOT_TO_SHIELD_MAP.put("ARM_R", "SHIELD_R");
        SLOT_TO_SHIELD_MAP.put("ARM_L", "SHIELD_L");
        // 可以根据需要添加更多映射
    }
    
    private ShipAPI shield = null; // 关联的护盾模块
    private String currentSlotId = null; // 当前武器槽位ID
    private boolean shieldInitialized = false; // 护盾初始化标记
    private WeaponAPI typeWeapon; // _TYPE槽位武器
    private String slotId; // 武器槽位ID
    private ShipAPI ship; // 舰船引用
    private static final float FOLLOW_RATIO = 0.33f; // 跟随比例参数
    boolean wasFiring = false; // 开火状态标记
    private boolean isAnimating = false; // 动画播放状态标记

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        
        // 初始化逻辑
        if (!init) {
            init = true;
            ship = weapon.getShip();
            slotId = weapon.getSlot().getId();
            currentSlotId = slotId; // 兼容护盾检测
            
            // 查找关联武器槽位
            String sourceId = slotId + "_SOURCE";
            String mainId = slotId + "_MAIN";
            String typeId = slotId + "_TYPE";

            for (WeaponAPI w : ship.getAllWeapons()) {
                if (sourceId.equals(w.getSlot().getId())) {
                    source = w;
                }
                if (source == null && mainId.equals(w.getSlot().getId())) {
                    sourceMAIN = w;
                }
                if (typeId.equals(w.getSlot().getId())) {
                    typeWeapon = w;
                }
            }
            
            // 初始化SOURCE武器的开火点同步和透明化
            if (source != null) {
                //计算装饰武器和真武器之间的坐标差
                Vector2f diff = new Vector2f();
                Vector2f.sub(weapon.getSlot().getLocation(),source.getSlot().getLocation(), diff);
                //取装饰武器的炮塔模式第一个开火点坐标
                Vector2f firePoint = weapon.getSpec().getTurretFireOffsets().get(0);
                syncFirePoint(source, diff, firePoint);
            }
            
            // 初始化护盾检测
            initializeShieldDetection(weapon);
            shieldInitialized = true;
        }
        
        // _TYPE槽位武器类型帧切换逻辑
        handleTypeWeaponFrames();
        
        // 角度同步和透明化处理
        handleWeaponSync(weapon);
        
        // 处理护盾状态相关的自身动画控制
        handleShieldAnimation(weapon);
        
        // 处理开火动画（如果有的话）
        handleFiringAnimation(weapon);
        
        // 处理_MAIN槽位的角度跟随
        handleMainWeaponSync(weapon);
    }
    
    /**
     * 处理_TYPE槽位武器的帧切换
     */
    private void handleTypeWeaponFrames() {
        if (source != null && typeWeapon != null && typeWeapon.getAnimation() != null) {
            int maxFrames = typeWeapon.getAnimation().getNumFrames();
            
            // 根据_SOURCE武器类型设置_TYPE武器的帧
            if (source.getType() == WeaponType.MISSILE || 
                source.getType() == WeaponType.COMPOSITE || 
                source.getType() == WeaponType.SYNERGY) {
                // 如果最大帧数大于1，设置到第1帧
                if (maxFrames > 1) {
                    typeWeapon.getAnimation().setFrame(1);
                } else {
                    typeWeapon.getAnimation().setFrame(0);
                }
            } else if ((source.getType() == WeaponType.ENERGY || 
                      source.getType() == WeaponType.HYBRID) && 
                      maxFrames > 2) {
                // 如果最大帧数大于2，设置到第2帧
                typeWeapon.getAnimation().setFrame(2);
            } else {
                // 其他情况设置到第0帧
                typeWeapon.getAnimation().setFrame(0);
            }
        }
    }
    
    /**
     * 处理武器同步（角度和透明化）
     */
    private void handleWeaponSync(WeaponAPI weapon) {
        if (source != null) {
            weapon.setCurrAngle(source.getCurrAngle());
            
            // 如果存在_TYPE槽位武器，同步其角度
            if (typeWeapon != null) {
                typeWeapon.setCurrAngle(weapon.getCurrAngle());
            }
            
            // 隐藏SOURCE武器
            hideSourceWeapon();
        }
    }
    
    /**
     * 处理开火动画
     */
    private void handleFiringAnimation(WeaponAPI weapon) {
        if (source != null && weapon.getAnimation() != null) {
            boolean isFiring = source.isFiring() &&
                    (source.getCooldownRemaining() == 0 ||
                            (source.getSpec().getBurstSize() > 1 && source.isInBurst()));

            if (isFiring) {
                // 开火时播放动画，但帧设置由护盾状态控制
                weapon.getAnimation().play();
                wasFiring = true;
                isAnimating = true;
            } else if (wasFiring) {
                // 检查是否到达最后一帧
                if (weapon.getAnimation().getFrame() >= weapon.getAnimation().getNumFrames() - 1) {
                    weapon.getAnimation().pause();
                    wasFiring = false;
                    isAnimating = false;
                } else if (isAnimating) {
                    // 继续播放直到完成
                    weapon.getAnimation().play();
                }
            } else {
                weapon.getAnimation().pause();
                isAnimating = false;
            }
        }
    }
    
    /**
     * 处理_MAIN槽位的角度跟随
     */
    private void handleMainWeaponSync(WeaponAPI weapon) {
        if (sourceMAIN != null) {
            // 计算武器相对于飞船的角度
            float shipFacing = weapon.getShip().getFacing();
            float sourceAngle = sourceMAIN.getCurrAngle() - shipFacing;

            // 规范化角度到[-180,180]范围
            while (sourceAngle > 180) sourceAngle -= 360;
            while (sourceAngle < -180) sourceAngle += 360;

            // 应用跟随比例并加回飞船朝向
            float targetAngle = shipFacing + sourceAngle * FOLLOW_RATIO;
            weapon.setCurrAngle(targetAngle);
        }
    }
    
    /**
     * 初始化护盾检测
     */
    private void initializeShieldDetection(WeaponAPI weapon) {
        // 获取当前武器的槽位ID
        currentSlotId = weapon.getSlot().getId();
        
        // 根据槽位ID获取对应的护盾模块ID
        String shieldId = SLOT_TO_SHIELD_MAP.get(currentSlotId);
        
        if (shieldId != null) {
            // 查找对应的护盾模块
            ShipAPI ship = weapon.getShip();
            
            for (ShipAPI module : ship.getChildModulesCopy()) {
                if (module.getStationSlot() != null && module.getStationSlot().getId().equals(shieldId)) {
                    shield = module;
                    break;
                }
            }
        }
    }
    
    /**
     * 处理护盾状态相关的动画控制
     * 只检测护盾存活与否，不受开火动画影响
     */
    private void handleShieldAnimation(WeaponAPI weapon) {
        // 只有在找到护盾模块且有动画的情况下才进行控制
        if (weapon.getAnimation() != null && shield != null) {
            // 检查护盾状态并设置对应的动画帧（不受开火动画影响）
            if (shield.isAlive()) {
                weapon.getAnimation().setFrame(1); // 护盾存活：第1帧
            } else {
                weapon.getAnimation().setFrame(0); // 护盾死亡：第0帧
            }
        }
    }
    
    /**
     * 添加新的槽位映射关系
     * @param slotId 武器槽位ID
     * @param shieldId 对应的护盾模块ID
     */
    public static void addSlotMapping(String slotId, String shieldId) {
        SLOT_TO_SHIELD_MAP.put(slotId, shieldId);
    }
    
    /**
     * 获取当前关联的护盾模块
     * @return 护盾模块，如果没有则返回null
     */
    public ShipAPI getShield() {
        return shield;
    }
    
    /**
     * 检查护盾是否存活
     * @return 护盾存活状态
     */
    public boolean isShieldAlive() {
        return shield != null && shield.isAlive();
    }

    /**
     * 隐藏SOURCE武器
     * 参考骤雨mod的方法：每帧都设置透明颜色，包括所有贴图组件
     */
    @Override
    protected void hideSourceWeapon() {
        if (source == null) return;
        
        // 透明颜色，参考骤雨mod的实现
        Color transparent = new Color(0f, 0f, 0f, 0f);
        
        // 设置所有贴图组件为透明，包括发光贴图
        if (source.getSprite() != null) {
            source.getSprite().setColor(transparent);
        }
        if (source.getBarrelSpriteAPI() != null) {
            source.getBarrelSpriteAPI().setColor(transparent);
        }
        if (source.getUnderSpriteAPI() != null) {
            source.getUnderSpriteAPI().setColor(transparent);
        }
        if (source.getGlowSpriteAPI() != null) {
            source.getGlowSpriteAPI().setColor(transparent);
        }
    }
} 