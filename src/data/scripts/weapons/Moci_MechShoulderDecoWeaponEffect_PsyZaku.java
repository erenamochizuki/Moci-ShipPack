package data.scripts.weapons;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * PsyZaku版机甲肩膀装饰武器效果
 * 继承基础类，移除开火动画功能，添加装甲死亡时清空弹药功能
 * 支持护盾模块关联，根据护盾状态切换武器帧
 * 不隐藏导弹贴图，保留导弹视觉效果
 */
public class Moci_MechShoulderDecoWeaponEffect_PsyZaku extends Moci_MechShoulderDecoWeaponEffect {
    
    // 槽位到护盾模块的映射关系
    private static final Map<String, String> SLOT_TO_SHIELD_MAP = new HashMap<String, String>();
    static {
        SLOT_TO_SHIELD_MAP.put("SHOULDER_R", "SHIELD_RS");
        SLOT_TO_SHIELD_MAP.put("SHOULDER_L", "SHIELD_LS");
    }
    
    private ShipAPI shield = null; // 关联的护盾模块
    private String currentSlotId = null; // 当前武器槽位ID
    private boolean shieldWasAlive = true; // 记录护盾模块上一帧的存活状态
    private boolean ammoCleared = false; // 标记是否已清空弹药

    @Override
    protected void onInitialized(WeaponAPI weapon) {
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
            
            // 初始化时记录护盾模块的存活状态
            if (shield != null) {
                shieldWasAlive = shield.isAlive();
            }
        }
    }
    
    /**
     * 重写setupSourceWeapon方法，使用不隐藏导弹的syncFirePoint版本
     */
    @Override
    protected void setupSourceWeapon(WeaponAPI weapon) {
        // 计算装饰武器和关联武器的武器槽坐标差值
        Vector2f diff = new Vector2f();
        Vector2f.sub(weapon.getSlot().getLocation(), source.getSlot().getLocation(), diff);
        
        // 取装饰武器的炮塔模式第一个开火点坐标
        Vector2f firePoint = weapon.getSpec().getTurretFireOffsets().get(0);
        
        // 使用不隐藏导弹的版本
        syncFirePointWithoutHidingMissiles(source, diff, firePoint);
        
        // 隐藏SOURCE武器
        hideSourceWeapon();
        source.setSuspendAutomaticTurning(true);
    }

    @Override
    protected void onAdvance(WeaponAPI weapon, float amount, CombatEngineAPI engine) {
        // 控制护盾动画 - 基于护盾存活状态
        if (weapon.getAnimation() != null && shield != null) {
            boolean isShieldAlive = shield.isAlive();
            
            if (isShieldAlive) {
                weapon.getAnimation().setFrame(1); // 护盾存活为第1帧
            } else {
                weapon.getAnimation().setFrame(0); // 护盾死亡为第0帧
            }
            
            // 检测护盾模块从存活变为死亡
            if (shieldWasAlive && !isShieldAlive && !ammoCleared) {
                clearSourceAmmo();
                ammoCleared = true;
            }
            
            // 更新护盾存活状态
            shieldWasAlive = isShieldAlive;
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
     * 清空SOURCE武器的弹药
     * 当护盾模块被摧毁时调用
     */
    private void clearSourceAmmo() {
        if (source == null) return;
        
        // 检查武器是否使用弹药
        if (source.usesAmmo()) {
            // 将当前弹药设置为0
            source.setAmmo(0);
        }
    }
    
    /**
     * 重写父类的开火动画处理方法，移除开火动画功能
     */
    @Override
    protected void handleSourceWeapon(WeaponAPI weapon, float amount) {
        // 只保留角度同步，移除开火动画逻辑
        if (source != null) {
            source.setCurrAngle(weapon.getCurrAngle());
            hideSourceWeapon();
        }
    }
    
    /**
     * 批量修改开火点（不隐藏导弹贴图版本）
     *
     * @param sync     关联武器的开火点V2的list
     * @param diff     武器槽之间的坐标差值
     * @param override 装饰武器的开火点
     */
    private static void syncFirePointWithoutHidingMissiles(WeaponAPI sync, Vector2f diff, Vector2f override) {
        if (override == null) {
            override = new Vector2f();
        }

        Vector2f.add(override, diff, diff);
        sync.ensureClonedSpec();
        int t = sync.getSpec().getTurretFireOffsets().size();
        int h = sync.getSpec().getHardpointFireOffsets().size();
        int hd = sync.getSpec().getHiddenFireOffsets().size();

        sync.getSpec().getTurretFireOffsets().clear();
        sync.getSpec().getHardpointFireOffsets().clear();
        sync.getSpec().getHiddenFireOffsets().clear();
        
        for (int i = 0; i < t; i++) {
            sync.getSpec().getTurretFireOffsets().add(new Vector2f(diff));
        }
        for (int i = 0; i < h; i++) {
            sync.getSpec().getHardpointFireOffsets().add(new Vector2f(diff));
        }
        for (int i = 0; i < hd; i++) {
            sync.getSpec().getHiddenFireOffsets().add(new Vector2f(diff));
        }

        // 注意：这里不隐藏导弹贴图，保留导弹的视觉效果
        // 原版代码会将导弹贴图宽度设为0，这里移除了该逻辑
    }
}
