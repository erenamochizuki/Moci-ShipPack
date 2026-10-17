package data.scripts.weapons;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.FaderUtil;

/**
 * 抛弃式火箭筒效果插件
 * 当武器弹药耗尽时，会抛弃火箭筒并根据配置生成贴图或无人机
 */
public class Moci_DiscardableLauncher_Effect implements EveryFrameWeaponEffectPlugin {
    
    // 配置参数
    private static final int MODE_SPRITE = 0;      // 生成贴图模式
    private static final int MODE_DRONE = 1;       // 生成无人机模式
    private static final float SPRITE_DURATION = 5f;  // 贴图持续时间（秒）
    private static final float SPEED_BONUS = 0.1f;    // 速度加成（10%）
    private static final String DRONE_HULL_ID = "moci_discarded_launcher_drone"; // 无人机船体ID
    
    // 实例变量
    private boolean initialized = false;
    private boolean wasEmpty = false;           // 上一帧是否为空弹药
    private boolean hasDiscarded = false;       // 是否已经抛弃
    private int effectMode = MODE_SPRITE;       // 效果模式：0=贴图，1=无人机
    private String droneHullId = DRONE_HULL_ID; // 无人机船体ID
    
    // 静态变量用于跟踪贴图效果
    private static final Map<String, DiscardedSprite> activeSprites = new HashMap<>();
    
    // 贴图效果类
    private static class DiscardedSprite {
        SpriteAPI sprite;
        Vector2f location;
        float facing;
        FaderUtil fader;
        float timeLeft;
        
        DiscardedSprite(SpriteAPI sprite, Vector2f location, float facing, float duration) {
            this.sprite = sprite;
            this.location = new Vector2f(location);
            this.facing = facing;
            this.fader = new FaderUtil(0f, 1f, duration);
            this.fader.fadeIn();
            this.timeLeft = duration;
        }
    }
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }
        
        // 初始化
        if (!initialized) {
            initialized = true;
            // 可以通过武器ID或其他方式来配置效果模式和无人机ID
            // 这里使用简单的规则：如果武器ID包含"drone"则使用无人机模式
            if (weapon.getId().toLowerCase().contains("drone")) {
                effectMode = MODE_DRONE;
            }
            // 可以通过武器spec的customData或者其他方式来配置droneHullId
        }
        
        // 检查武器是否有限弹药且有2帧动画
        if (weapon.getSpec().getMaxAmmo() <= 0 || weapon.getAnimation() == null || 
            weapon.getAnimation().getNumFrames() < 2) {
            return;
        }
        
        // 检查当前弹药状态
        boolean isEmpty = weapon.getAmmo() <= 0;
        
        // 弹药状态处理
        if (isEmpty) {
            // 弹药为空，设置动画帧为1
            weapon.getAnimation().setFrame(1);
            
            // 如果刚刚变空且还没有抛弃过，执行抛弃逻辑
            if (!wasEmpty && !hasDiscarded) {
                performDiscard(engine, weapon);
                hasDiscarded = true;
            }
            
            // 给予舰船速度和机动性加成
            applyShipBonus(ship);
            
        } else {
            // 有弹药，设置动画帧为0
            weapon.getAnimation().setFrame(0);
            
            // 移除舰船加成（如果之前有的话）
            removeShipBonus(ship);
        }
        
        // 更新状态
        wasEmpty = isEmpty;
        
        // 更新贴图效果
        updateSpriteEffects(amount);
    }
    
    /**
     * 执行抛弃逻辑
     */
    private void performDiscard(CombatEngineAPI engine, WeaponAPI weapon) {
        Vector2f weaponLocation = weapon.getLocation();
        float weaponFacing = weapon.getCurrAngle();
        
        if (effectMode == MODE_SPRITE) {
            // 生成贴图效果
            generateDiscardedSprite(weapon, weaponLocation, weaponFacing);
        } else {
            // 生成无人机
            generateDiscardedDrone(engine, weapon, weaponLocation, weaponFacing);
        }
    }
    
    /**
     * 生成抛弃的贴图效果
     */
    private void generateDiscardedSprite(WeaponAPI weapon, Vector2f location, float facing) {
        // 获取武器第0帧的贴图
        SpriteAPI weaponSprite = weapon.getSprite();
        if (weaponSprite == null) return;
        
        // 直接使用武器贴图作为抛弃贴图的基础
        // 注意：这里我们不能直接复制贴图，所以使用引用方式
        SpriteAPI discardedSprite = weaponSprite;
        
        // 设置贴图属性
        discardedSprite.setSize(weaponSprite.getWidth(), weaponSprite.getHeight());
        discardedSprite.setCenter(weaponSprite.getCenterX(), weaponSprite.getCenterY());
        
        // 添加到活动贴图列表
        String spriteId = weapon.getShip().getId() + "_" + weapon.getSlot().getId() + "_" + System.currentTimeMillis();
        activeSprites.put(spriteId, new DiscardedSprite(discardedSprite, location, facing, SPRITE_DURATION));
    }
    
    /**
     * 生成抛弃的无人机
     */
    private void generateDiscardedDrone(CombatEngineAPI engine, WeaponAPI weapon, Vector2f location, float facing) {
        try {
            // 获取无人机船体规格
            ShipHullSpecAPI spec = Global.getSettings().getHullSpec(droneHullId);
            if (spec == null) {
                // 如果指定的船体不存在，使用默认的
                Global.getLogger(this.getClass()).warn("Drone hull " + droneHullId + " not found, skipping drone generation");
                return;
            }
            
            // 创建无人机变体
            ShipVariantAPI variant = Global.getSettings().createEmptyVariant(droneHullId + "_Discarded", spec);
            
            // 生成无人机实体
            ShipAPI drone = engine.createFXDrone(variant);
            
            // 设置无人机属性
            drone.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
            drone.setOwner(weapon.getShip().getOwner());
            
            if (weapon.getShip().isAlly()) {
                drone.setAlly(true);
            }
            
            // 标记为无人机
            drone.setDrone(true);
            // drone.getAIFlags().setFlag(ShipwideAIFlags.AIFlags.DRONE_MOTHERSHIP, 100000f, weapon.getShip());
            
            // 设置碰撞类型
            drone.setCollisionClass(CollisionClass.FIGHTER);
            
            // 设置位置和朝向
            drone.getLocation().set(location);
            drone.setFacing(facing);
            
            // 添加到战斗引擎
            engine.addEntity(drone);
            
            // 设置AI
            drone.setHullSize(ShipAPI.HullSize.FIGHTER);
            
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error("Error generating discarded drone: " + e.getMessage());
        }
    }
    
    /**
     * 应用舰船加成
     */
    private void applyShipBonus(ShipAPI ship) {
        // 速度加成
        ship.getMutableStats().getMaxSpeed().modifyPercent("discarded_launcher", SPEED_BONUS * 100f);
        ship.getMutableStats().getAcceleration().modifyPercent("discarded_launcher", SPEED_BONUS * 100f);
        ship.getMutableStats().getDeceleration().modifyPercent("discarded_launcher", SPEED_BONUS * 100f);
        
        // 机动性加成
        ship.getMutableStats().getMaxTurnRate().modifyPercent("discarded_launcher", SPEED_BONUS * 100f);
        ship.getMutableStats().getTurnAcceleration().modifyPercent("discarded_launcher", SPEED_BONUS * 100f);
    }
    
    /**
     * 移除舰船加成
     */
    private void removeShipBonus(ShipAPI ship) {
        ship.getMutableStats().getMaxSpeed().unmodify("discarded_launcher");
        ship.getMutableStats().getAcceleration().unmodify("discarded_launcher");
        ship.getMutableStats().getDeceleration().unmodify("discarded_launcher");
        ship.getMutableStats().getMaxTurnRate().unmodify("discarded_launcher");
        ship.getMutableStats().getTurnAcceleration().unmodify("discarded_launcher");
    }
    
    /**
     * 更新贴图效果
     */
    private void updateSpriteEffects(float amount) {
        if (activeSprites.isEmpty()) return;
        
        // 更新所有活动贴图
        java.util.Iterator<Map.Entry<String, DiscardedSprite>> iterator = activeSprites.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, DiscardedSprite> entry = iterator.next();
            DiscardedSprite sprite = entry.getValue();
            
            // 更新时间
            sprite.timeLeft -= amount;
            sprite.fader.advance(amount);
            
            // 如果时间到了，移除贴图
            if (sprite.timeLeft <= 0) {
                iterator.remove();
                continue;
            }
            
            // 渲染贴图
            renderDiscardedSprite(sprite);
        }
    }
    
    /**
     * 渲染抛弃的贴图
     */
    private void renderDiscardedSprite(DiscardedSprite sprite) {
        if (sprite.sprite == null) return;
        
        // 计算透明度（随时间淡出）
        float alpha = sprite.fader.getBrightness();
        Color color = new Color(1f, 1f, 1f, alpha);
        sprite.sprite.setColor(color);
        
        // 渲染贴图
        sprite.sprite.setAngle(sprite.facing - 90f); // 调整角度以匹配武器朝向
        sprite.sprite.renderAtCenter(sprite.location.x, sprite.location.y);
    }
    
    /**
     * 设置效果模式
     * @param mode 0=贴图模式，1=无人机模式
     */
    public void setEffectMode(int mode) {
        this.effectMode = mode;
    }
    
    /**
     * 设置无人机船体ID
     * @param hullId 无人机船体ID
     */
    public void setDroneHullId(String hullId) {
        this.droneHullId = hullId;
    }
}
