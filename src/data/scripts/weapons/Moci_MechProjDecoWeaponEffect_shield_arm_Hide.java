package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileRenderDataAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Zaku版护盾臂装饰武器效果
 * 继承护盾臂基础功能，增加SOURCE武器隐藏功能和开火点同步
 */
public class Moci_MechProjDecoWeaponEffect_shield_arm_Hide extends Moci_MechProjDecoWeaponEffect_shield_arm {
    
    private boolean firePointSynced = false; // 开火点同步标记
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 调用父类的基础功能
        super.advance(amount, engine, weapon);
        
        // 如果找到了SOURCE武器，则进行开火点同步和隐藏
        if (source != null) {
            // 只在初始化时同步一次开火点
            if (!firePointSynced) {
                syncFirePointToDecoWeapon(weapon);
                firePointSynced = true;
            }
            
            // 每帧隐藏SOURCE武器
            hideSourceWeapon();
        }
    }
    
    /**
     * 同步开火点到装饰武器位置
     */
    private void syncFirePointToDecoWeapon(WeaponAPI weapon) {
        if (source == null) return;
        
        // 计算装饰武器和真武器之间的坐标差
        Vector2f diff = new Vector2f();
        Vector2f.sub(weapon.getSlot().getLocation(), source.getSlot().getLocation(), diff);
        
        // 取装饰武器的炮塔模式第一个开火点坐标
        Vector2f firePoint = weapon.getSpec().getTurretFireOffsets().get(0);
        
        // 调用静态方法同步开火点
        Moci_MechProjDecoWeaponEffect.syncFirePoint(source, diff, firePoint);
    }
    
    /**
     * 隐藏SOURCE武器
     * 参考骤雨mod的方法：每帧都设置透明颜色，包括所有贴图组件
     */
    private void hideSourceWeapon() {
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
        
        // 隐藏导弹渲染数据
        if (source.getMissileRenderData() != null) {
            for (MissileRenderDataAPI render : source.getMissileRenderData()) {
                render.getSprite().setWidth(0f);
                render.getSprite().setHeight(0f);
            }
        }
    }
} 