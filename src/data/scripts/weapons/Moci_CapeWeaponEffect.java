package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import data.scripts.visual.Moci_CapeVisual;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;

/**
 * 披风武器特效
 * 
 * 使用方法：
 * 1. 创建一个隐藏武器（DECORATIVE类型）
 * 2. 在weapon_data.csv中设置everyFrameEffect为此类（或其子类）
 * 3. 武器位置即为披风的起始点
 * 4. 如需自定义参数，创建子类并覆盖配置字段
 */
public class Moci_CapeWeaponEffect implements EveryFrameWeaponEffectPlugin {
    
    private static final String CAPE_KEY_PREFIX = "moci_cape_";
    
    // 可在子类中覆盖的配置参数
    protected float length = 200f;
    protected float frontWidth = 80f;
    protected float endWidth = 150f;
    protected float foldCount = 6f;
    protected float foldSpeed = 2.5f;
    protected float inertia = 0.5f;
    protected float angleOffset = 180f;
    protected String texturePath = "graphics/fx/smoke_particle2.png";
    protected Color tintColor = new Color(200, 220, 255, 200);
    
    private boolean initialized = false;
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || weapon == null) {
            return;
        }
        
        if (engine.isPaused()) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }
        
        // 检查BoxUtil是否可用
        if (!data.scripts.Moci_bounty_plugin.isBoxUtilAvailable()) {
            return;
        }
        
        // 生成唯一key
        String capeKey = CAPE_KEY_PREFIX + weapon.getSlot().getId();
        
        // 首次创建披风
        if (!initialized) {
            try {
                // 加载纹理
                SpriteAPI texture = Global.getSettings().getSprite(texturePath);
                
                // 创建披风实例（锚点为舰船，而非武器）
                Moci_CapeVisual cape = new Moci_CapeVisual(ship, texture);
                
                // 应用配置参数
                cape.setLength(length);
                cape.setFrontWidth(frontWidth);
                cape.setEndWidth(endWidth);
                cape.setFoldCount(foldCount);
                cape.setFoldSpeed(foldSpeed);
                cape.setInertia(inertia);
                cape.setTintColor(tintColor);
                
                // 计算武器相对舰船的偏移
                Vector2f weaponLoc = weapon.getLocation();
                Vector2f shipLoc = ship.getLocation();
                float offsetX = weaponLoc.x - shipLoc.x;
                float offsetY = weaponLoc.y - shipLoc.y;
                
                // 设置偏移和角度
                cape.setOffset(offsetX, offsetY);
                
                // 角度偏移 = 武器角度 - 舰船朝向 + 配置的角度偏移
                float weaponAngleOffset = weapon.getSlot().getAngle() + angleOffset;
                cape.setAngleOffset(weaponAngleOffset);
                
                // 添加到引擎
                engine.addLayeredRenderingPlugin(cape);
                
                // 保存到舰船自定义数据
                ship.setCustomData(capeKey, cape);
                
                initialized = true;
                
            } catch (Exception e) {
                Global.getLogger(this.getClass()).error("Failed to create cape for weapon " + weapon.getSlot().getId(), e);
                initialized = true; // 标记为已初始化，避免重复尝试
            }
        }
        
        // 更新披风状态
        Moci_CapeVisual cape = (Moci_CapeVisual) ship.getCustomData().get(capeKey);
        if (cape != null) {
            // 根据舰船速度动态调整飘动效果
            float speed = ship.getVelocity().length();
            float speedFactor = Math.min(speed / 200f, 2f);
            cape.setFoldSpeed(foldSpeed * (1f + speedFactor * 0.5f));
            
            // 根据舰船状态调整透明度
            float alpha = 1f;
            if (ship.isPhased()) {
                alpha = 0.3f;
            }
            if (ship.getFluxTracker().isOverloadedOrVenting()) {
                alpha = 0.5f;
            }
            cape.setAlphaMult(alpha);
            
            // 舰船死亡时移除披风
            if (!ship.isAlive() || ship.isHulk()) {
                cape.setValid(false);
            }
        }
    }
}
