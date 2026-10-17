package data.scripts.weapons;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

import data.scripts.weapons.render.Moci_EngineRender;

/**
 * 简化版引擎动画控制器
 * 仅保留引擎渲染效果，移除所有动画和武器跟随逻辑
 * 为每个开火点创建独立的渲染器，使引擎朝向跟随开火点方向
 */
public class Moci_MechLEngineAnimationController_Simple_small implements EveryFrameWeaponEffectPlugin {
    private boolean initialized = false;
    private java.util.List<Moci_EngineRender> renders = new java.util.ArrayList<>();

    // 引擎渲染参数
    private Color engineColor = new Color(43, 149, 255, 195); // 默认引擎颜色
    public static float ENGINE_LENGTH_MULT = 2f;  // 尾焰长度乘数
    public static float ENGINE_WIDTH_MULT = 3f;    // 尾焰宽度乘数
    public static float GLOW_SIZE_MULT = 1.0f;  // 光亮尺寸乘数

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }

        // 初始化引擎渲染
        if (!initialized) {
            initializeEngineRender(engine, weapon, ship);
            initialized = true;
        }

        // 每帧更新每个引擎的朝向，使其跟随对应开火点的方向
        java.util.List<Float> angleOffsets = weapon.getSpec().getTurretAngleOffsets();
        for (int i = 0; i < renders.size() && i < angleOffsets.size(); i++) {
            Moci_EngineRender render = renders.get(i);
            float firePointAngle = angleOffsets.get(i);
            // 计算开火点相对舰船的绝对角度
            float relativeAngle = weapon.getCurrAngle() - ship.getFacing() + firePointAngle;
            render.setFixedFacing(relativeAngle);
        }
    }

    /**
     * 初始化引擎渲染效果
     * 为每个开火点创建独立的渲染器
     */
    private void initializeEngineRender(CombatEngineAPI engine, WeaponAPI weapon, ShipAPI ship) {
        if (ship.getEngineController() == null || ship.getEngineController().getShipEngines() == null) {
            return;
        }

        // 获取一个引擎作为模板
        ShipEngineControllerAPI.ShipEngineAPI templateEngine = null;
        for (ShipEngineControllerAPI.ShipEngineAPI engineSlot : ship.getEngineController().getShipEngines()) {
            if (engineSlot != null) {
                templateEngine = engineSlot;
                break;
            }
        }

        if (templateEngine == null) {
            return;
        }

        // 加载引擎精灵图
        SpriteAPI glowSprite = Global.getSettings().getSprite("fx", "engineglow32b");
        SpriteAPI flameSprite = Global.getSettings().getSprite("fx", "MBSglowOutline");
        SpriteAPI circleSprite = Global.getSettings().getSprite("fx", "MBSEngineglowCricleNothing");

        // 获取开火点数量
        int firePointCount = weapon.getSpec().getTurretFireOffsets().size();

        // 为每个开火点创建独立的引擎渲染器
        for (int i = 0; i < firePointCount; i++) {
            Moci_EngineRender render = new Moci_EngineRender(
                    ship,
                    templateEngine,
                    weapon,
                    glowSprite,
                    flameSprite,
                    circleSprite,
                    CombatEngineLayers.UNDER_SHIPS_LAYER,
                    CombatEngineLayers.UNDER_SHIPS_LAYER,
                    true  // true = 始终激活，不受系统状态影响
            );

            // 设置只渲染当前开火点
            render.setFirePointIndex(i);

            // 设置光亮参数
            render.setGlowSizeMult(GLOW_SIZE_MULT);

            // 设置引擎颜色
            Color engineColorToUse = engineColor;
            if (templateEngine.getEngineColor() != null) {
                // 使用引擎原始颜色，但保留透明度
                engineColorToUse = new Color(
                        templateEngine.getEngineColor().getRed(),
                        templateEngine.getEngineColor().getGreen(),
                        templateEngine.getEngineColor().getBlue(),
                        engineColor.getAlpha()
                );
            }
            render.setColorOverride(engineColorToUse);

            // 设置尾焰尺寸参数
            render.setLengthMult(ENGINE_LENGTH_MULT);
            render.setWidthMult(ENGINE_WIDTH_MULT);

            // 添加到渲染层
            engine.addLayeredRenderingPlugin(render);

            // 保存到列表中，用于后续更新朝向
            renders.add(render);
        }
    }
}
