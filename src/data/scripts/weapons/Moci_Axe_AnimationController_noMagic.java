package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

/**
 * 斧头武器动画控制器
 * 基于Moci_System1Effect.java实现可靠的残影效果
 */
public class Moci_Axe_AnimationController_noMagic extends Moci_BladeEffectPlugin {

    // 残影效果参数（基于Moci_System1Effect.java的成功实现）
    private final float AFTERIMAGE_INTERVAL = 0.06f; // 残影间隔
    private final float AFTERIMAGE_FADE_IN = 0f;
    private final float AFTERIMAGE_FADE_OUT = 0.4f;
    private final float AFTERIMAGE_ALPHA_START = 0.7f;
    private final float AFTERIMAGE_ALPHA_END = 0f;
    private float afterimageAccumulator = 0f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 首先调用父类方法处理基础功能
        super.advance(amount, engine, weapon);
        
        if (engine == null || engine.isPaused()) return;

        // 添加武器残像效果（基于Moci_System1Effect.java的成功实现）
        handleWeaponAfterimage(amount, weapon);
    }

    /**
     * 处理武器残像效果
     * 完全基于Moci_System1Effect.java中的成功实现
     */
    private void handleWeaponAfterimage(float amount, WeaponAPI weapon) {
        ShipAPI ship = weapon.getShip();
        
        // 在武器攻击时生成残像效果
        if (weapon.isFiring() && weapon.getChargeLevel() > 0f && !ship.getFluxTracker().isOverloaded()) {
            afterimageAccumulator += amount;
            
            if (afterimageAccumulator >= AFTERIMAGE_INTERVAL) {
                createWeaponAfterimage(weapon);
                afterimageAccumulator = 0f;
            }
        }
    }
    
    /**
     * 创建武器残影
     */
    private void createWeaponAfterimage(WeaponAPI weapon) {
        if (weapon.getSprite() == null) return;
        
        ShipAPI ship = weapon.getShip();
        
        // 获取舰船偏移量
        SpriteAPI shipSprite = ship.getSpriteAPI();
        float offsetX = shipSprite.getWidth()/2 - shipSprite.getCenterX();
        float offsetY = shipSprite.getHeight()/2 - shipSprite.getCenterY();

        // 计算旋转后的偏移量
        float trueOffsetX = (float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetX -
                (float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetY;
        float trueOffsetY = (float)Math.sin(Math.toRadians(ship.getFacing()-90f))*offsetX +
                (float)Math.cos(Math.toRadians(ship.getFacing()-90f))*offsetY;
        
        // 检查武器是否被设置为完全透明
        boolean isHidden = false;
        if (weapon.getSprite() != null && weapon.getSprite().getColor().getAlpha() == 0) {
            isHidden = true;
        }
        if (weapon.getBarrelSpriteAPI() != null && weapon.getBarrelSpriteAPI().getColor().getAlpha() == 0) {
            isHidden = true;
        }
        if (weapon.getUnderSpriteAPI() != null && weapon.getUnderSpriteAPI().getColor().getAlpha() == 0) {
            isHidden = true;
        }
        if (weapon.getGlowSpriteAPI() != null && weapon.getGlowSpriteAPI().getColor().getAlpha() == 0) {
            isHidden = true;
        }

        if (isHidden) return;

        String spritePath;
        if (weapon.getAnimation() != null) {
            // 对于动画武器，获取当前帧的贴图路径
            int frame = weapon.getAnimation().getFrame();
            String basePath = weapon.getSlot().isTurret() ?
                    weapon.getSpec().getTurretSpriteName() :
                    weapon.getSpec().getHardpointSpriteName();
            // 移除.png后缀
            if (basePath.endsWith(".png")) {
                basePath = basePath.substring(0, basePath.length() - 4);
            }
            // 添加帧号和后缀
            spritePath = String.format("%s_%02d.png", basePath, frame);
        } else {
            // 无动画的武器使用默认贴图
            spritePath = weapon.getSlot().isTurret() ?
                    weapon.getSpec().getTurretSpriteName() :
                    weapon.getSpec().getHardpointSpriteName();
        }

        try {
            // 获取新的Sprite实例
            SpriteAPI weaponSprite = Global.getSettings().getSprite(spritePath);
            if (weaponSprite != null) {
                weaponSprite.setSize(weapon.getSprite().getWidth(), weapon.getSprite().getHeight());

                // 斧头武器使用橙红色残像
                Color axeAfterimageColor = new Color(255, 150, 50, 
                    (int)(AFTERIMAGE_ALPHA_START * 255));

                // 创建残影
                MagicRender.battlespace(
                        weaponSprite,
                        new Vector2f(weapon.getLocation().x + trueOffsetX, weapon.getLocation().y + trueOffsetY),
                        new Vector2f(0, 0),
                        new Vector2f(weapon.getSprite().getWidth(), weapon.getSprite().getHeight()),
                        new Vector2f(0, 0),
                        weapon.getCurrAngle()-90f,
                        0f,
                        axeAfterimageColor,
                        true,
                        0f,
                        0f,
                        0f,
                        0f,
                        0f,
                        AFTERIMAGE_FADE_IN,
                        AFTERIMAGE_FADE_OUT,
                        AFTERIMAGE_ALPHA_END,
                        CombatEngineLayers.BELOW_SHIPS_LAYER
                );
            }
        } catch (Exception e) {
            // 静默处理sprite加载失败，不影响游戏进行
        }
    }


} 