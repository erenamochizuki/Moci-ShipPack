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
 * 斧头武器动画控制器：跟随实际挥砍进度伸缩普通贴图与发光层
 * 扩展基础效果，添加Y轴伸出/收回动画
 * 基于Moci_System1Effect.java实现可靠的残影效果
 */
public class Moci_Axe_AnimationController extends Moci_BladeEffectPlugin {
    // 伸缩与 BeamEffect 的真实挥砍共用进度，不再使用独立的0.4秒阶段计时。
    public static final float MAX_EXTEND = 20f; // 收回位置到完全伸出位置的总行程，往返各10战场单位。
    private final java.util.Map<SpriteAPI, Float> originalCenters = new java.util.IdentityHashMap<>();
    private float extension; // 0=完全收回（原始中心），1=完全伸出（中心Y减10）。
    private int axeSwingPhase; // 0=空闲，1=左侧收回，2=正挥伸出，3=右侧收回，4=回挥伸出，5=结束保持。
    private static final float IDLE_RETRACT_TIME = 0.2f; // 一轮结束后的收回时间，秒。

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

        // 处理武器伸缩动画
        if (weapon.getChargeLevel() <= 0f) {
            extension = Math.max(0f, extension - Math.max(0f, amount) / IDLE_RETRACT_TIME);
            axeSwingPhase = 0;
            applyExtension(weapon);
        }

        // 添加武器残像效果（基于Moci_System1Effect.java的成功实现）
        handleWeaponAfterimage(amount, weapon);
    }

    /**
     * 处理武器残像效果
     * 完全基于Moci_System1Effect.java中的成功实现
     */
    protected void handleWeaponAfterimage(float amount, WeaponAPI weapon) {
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

                // 斧头武器使用橙红色残像，根据阶段调整强度
                float alphaMultiplier = 1.0f;
                if (axeSwingPhase == 2 || axeSwingPhase == 4) {
                    // 在劈砍阶段增强残像效果
                    alphaMultiplier = 1.3f;
                }

                Color axeAfterimageColor = new Color(255, 150, 50,
                    (int)(AFTERIMAGE_ALPHA_START * 255 * alphaMultiplier));

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

    /**
     * 由战斧 BeamEffect 在更新角度时调用：左侧收回→正挥伸出→右侧收回→回挥伸出。
     * progress 与角度使用相同的 smoothStep 进度，避免出现刀已挥完而伸缩还没开始。
     */
    public static void updateExtension(WeaponAPI weapon, int phase, float progress) {
        if (weapon.getEffectPlugin() instanceof Moci_Axe_AnimationController) {
            Moci_Axe_AnimationController effect = (Moci_Axe_AnimationController) weapon.getEffectPlugin();
            effect.axeSwingPhase = phase;
            effect.extension = Math.max(0f, Math.min(1f, progress));
            effect.applyExtension(weapon);
        }
    }

    /** 左侧蓄势时平滑收回；已经收回则立即就绪，不额外等待固定时间。 */
    public static boolean retractBeforeSwing(WeaponAPI weapon, float amount) {
        if (!(weapon.getEffectPlugin() instanceof Moci_Axe_AnimationController)) return true;
        Moci_Axe_AnimationController effect = (Moci_Axe_AnimationController) weapon.getEffectPlugin();
        updateExtension(weapon, 1, effect.extension - Math.max(0f, amount) / IDLE_RETRACT_TIME);
        return effect.extension <= 0f;
    }

    private void applyExtension(WeaponAPI weapon) {
        // 普通本体、发光层、独立炮管和底层都要移动；只有发光贴图的武器也能看到伸缩。
        applySpriteExtension(weapon.getSprite());
        applySpriteExtension(weapon.getGlowSpriteAPI());
        applySpriteExtension(weapon.getBarrelSpriteAPI());
        applySpriteExtension(weapon.getUnderSpriteAPI());
    }

    private void applySpriteExtension(SpriteAPI sprite) {
        if (sprite == null) return;
        Float original = originalCenters.get(sprite);
        if (original == null) {
            original = sprite.getCenterY();
            originalCenters.put(sprite, original);
        }
        sprite.setCenterY(original - extension * MAX_EXTEND);
    }
}
