package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

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
 * 基于武器开火点的引擎渲染控制器
 * 
 * 开火点分组规则：
 * - 开火点 1-2: 朝前，后退时渲染
 * - 开火点 3-4: 朝左，右移时渲染
 * - 开火点 5-6: 朝右，左移时渲染
 * - 开火点 7-8: 朝后，前进时渲染
 */
public class Moci_MAEngineController implements EveryFrameWeaponEffectPlugin {

    private boolean initialized = false;

    // 引擎渲染器列表，按开火点分组
    private List<Moci_EngineRender> frontEngines = new ArrayList<>(); // 1-2: 朝前
    private List<Moci_EngineRender> leftEngines = new ArrayList<>(); // 3-4: 朝左
    private List<Moci_EngineRender> rightEngines = new ArrayList<>(); // 5-6: 朝右
    private List<Moci_EngineRender> backEngines = new ArrayList<>(); // 7-8: 朝后

    // ========== 前向引擎参数（开火点1-2）==========
    private static final float FRONT_ENGINE_LENGTH_MULT = 3.0f; // 尾焰长度倍数
    private static final float FRONT_ENGINE_WIDTH_MULT = 8.0f; // 尾焰宽度倍数
    private static final float FRONT_GLOW_SIZE_MULT = 3.0f; // 光晕大小倍数
    private static final float FRONT_BASE_LENGTH_MULT = 1.0f; // 基础长度倍数（相对模板引擎）
    private static final float FRONT_BASE_WIDTH_MULT = 1.0f; // 基础宽度倍数（相对模板引擎）
    private static final CombatEngineLayers FRONT_FLAME_LAYER = CombatEngineLayers.UNDER_SHIPS_LAYER; // 尾焰渲染层
    private static final CombatEngineLayers FRONT_GLOW_LAYER = CombatEngineLayers.UNDER_SHIPS_LAYER; // 光晕渲染层

    // ========== 左侧引擎参数（开火点3-4）==========
    private static final float LEFT_ENGINE_LENGTH_MULT = 1.5f;
    private static final float LEFT_ENGINE_WIDTH_MULT = 4.0f;
    private static final float LEFT_GLOW_SIZE_MULT = 2f;
    private static final float LEFT_BASE_LENGTH_MULT = 1.0f;
    private static final float LEFT_BASE_WIDTH_MULT = 2.0f;
    private static final CombatEngineLayers LEFT_FLAME_LAYER = CombatEngineLayers.UNDER_SHIPS_LAYER;
    private static final CombatEngineLayers LEFT_GLOW_LAYER = CombatEngineLayers.UNDER_SHIPS_LAYER;

    // ========== 右侧引擎参数（开火点5-6）==========
    private static final float RIGHT_ENGINE_LENGTH_MULT = 1.5f;
    private static final float RIGHT_ENGINE_WIDTH_MULT = 4.0f;
    private static final float RIGHT_GLOW_SIZE_MULT = 2f;
    private static final float RIGHT_BASE_LENGTH_MULT = 1.0f;
    private static final float RIGHT_BASE_WIDTH_MULT = 2.0f;
    private static final CombatEngineLayers RIGHT_FLAME_LAYER = CombatEngineLayers.UNDER_SHIPS_LAYER;
    private static final CombatEngineLayers RIGHT_GLOW_LAYER = CombatEngineLayers.UNDER_SHIPS_LAYER;

    // ========== 后向引擎参数（开火点7-8）==========
    private static final float BACK_ENGINE_LENGTH_MULT = 2f;
    private static final float BACK_ENGINE_WIDTH_MULT = 8f;
    private static final float BACK_GLOW_SIZE_MULT = 2f;
    private static final float BACK_BASE_LENGTH_MULT = 1f;
    private static final float BACK_BASE_WIDTH_MULT = 1.0f;
    private static final CombatEngineLayers BACK_FLAME_LAYER = CombatEngineLayers.UNDER_SHIPS_LAYER;
    private static final CombatEngineLayers BACK_GLOW_LAYER = CombatEngineLayers.UNDER_SHIPS_LAYER;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 初始化引擎渲染器
        if (!initialized) {
            initializeEngines(engine, weapon, ship);
            initialized = true;
        }

        // 根据舰船移动状态控制引擎渲染
        updateEngineRendering(ship);
    }

    /**
     * 初始化引擎渲染器
     */
    private void initializeEngines(CombatEngineAPI engine, WeaponAPI weapon, ShipAPI ship) {
        // 获取武器的所有开火点
        List<Vector2f> firePoints = weapon.getSpec().getTurretFireOffsets();
        if (firePoints == null || firePoints.isEmpty()) {
            firePoints = weapon.getSpec().getHardpointFireOffsets();
        }

        if (firePoints == null || firePoints.isEmpty()) {
            return;
        }

        // 获取一个引擎模板
        ShipEngineControllerAPI.ShipEngineAPI templateEngine = getTemplateEngine(ship);
        if (templateEngine == null) {
            return;
        }

        // 加载引擎精灵
        SpriteAPI glowSprite = Global.getSettings().getSprite("fx", "engineglow32b");
        SpriteAPI flameSprite = Global.getSettings().getSprite("fx", "MBSglowOutline");
        SpriteAPI circleSprite = Global.getSettings().getSprite("fx", "MBSEngineglowCricleNothing");

        // 根据开火点索引创建引擎渲染器
        for (int i = 0; i < firePoints.size(); i++) {
            Moci_EngineRender render = null;

            // 根据开火点索引分配到不同的引擎组，并使用对应的参数
            if (i == 0 || i == 1) {
                // 开火点 1-2: 朝前
                render = createEngineRender(
                        ship, templateEngine, weapon,
                        glowSprite, flameSprite, circleSprite,
                        i,
                        FRONT_ENGINE_LENGTH_MULT, FRONT_ENGINE_WIDTH_MULT,
                        FRONT_GLOW_SIZE_MULT, FRONT_BASE_LENGTH_MULT, FRONT_BASE_WIDTH_MULT,
                        FRONT_FLAME_LAYER, FRONT_GLOW_LAYER);
                if (render != null)
                    frontEngines.add(render);

            } else if (i == 2 || i == 3) {
                // 开火点 3-4: 朝左
                render = createEngineRender(
                        ship, templateEngine, weapon,
                        glowSprite, flameSprite, circleSprite,
                        i,
                        LEFT_ENGINE_LENGTH_MULT, LEFT_ENGINE_WIDTH_MULT,
                        LEFT_GLOW_SIZE_MULT, LEFT_BASE_LENGTH_MULT, LEFT_BASE_WIDTH_MULT,
                        LEFT_FLAME_LAYER, LEFT_GLOW_LAYER);
                if (render != null)
                    leftEngines.add(render);

            } else if (i == 4 || i == 5) {
                // 开火点 5-6: 朝右
                render = createEngineRender(
                        ship, templateEngine, weapon,
                        glowSprite, flameSprite, circleSprite,
                        i,
                        RIGHT_ENGINE_LENGTH_MULT, RIGHT_ENGINE_WIDTH_MULT,
                        RIGHT_GLOW_SIZE_MULT, RIGHT_BASE_LENGTH_MULT, RIGHT_BASE_WIDTH_MULT,
                        RIGHT_FLAME_LAYER, RIGHT_GLOW_LAYER);
                if (render != null)
                    rightEngines.add(render);

            } else if (i == 6 || i == 7) {
                // 开火点 7-8: 朝后
                render = createEngineRender(
                        ship, templateEngine, weapon,
                        glowSprite, flameSprite, circleSprite,
                        i,
                        BACK_ENGINE_LENGTH_MULT, BACK_ENGINE_WIDTH_MULT,
                        BACK_GLOW_SIZE_MULT, BACK_BASE_LENGTH_MULT, BACK_BASE_WIDTH_MULT,
                        BACK_FLAME_LAYER, BACK_GLOW_LAYER);
                if (render != null)
                    backEngines.add(render);
            }

            // 添加到渲染层
            if (render != null) {
                engine.addLayeredRenderingPlugin(render);
            }
        }
    }

    /**
     * 获取引擎模板
     */
    private ShipEngineControllerAPI.ShipEngineAPI getTemplateEngine(ShipAPI ship) {
        if (ship.getEngineController() == null ||
                ship.getEngineController().getShipEngines() == null) {
            return null;
        }

        for (ShipEngineControllerAPI.ShipEngineAPI engineSlot : ship.getEngineController().getShipEngines()) {
            if (engineSlot != null) {
                return engineSlot;
            }
        }
        return null;
    }

    /**
     * 创建引擎渲染器
     */
    private Moci_EngineRender createEngineRender(
            ShipAPI ship,
            ShipEngineControllerAPI.ShipEngineAPI templateEngine,
            WeaponAPI weapon,
            SpriteAPI glowSprite,
            SpriteAPI flameSprite,
            SpriteAPI circleSprite,
            int firePointIndex,
            float lengthMult,
            float widthMult,
            float glowSizeMult,
            float baseLengthMult,
            float baseWidthMult,
            CombatEngineLayers flameLayer,
            CombatEngineLayers glowLayer) {

        Moci_EngineRender render = new Moci_EngineRender(
                ship,
                templateEngine,
                weapon,
                glowSprite,
                flameSprite,
                circleSprite,
                flameLayer,
                glowLayer,
                true);

        // 设置引擎基础尺寸（相对模板引擎的倍率）
        float baseLength = templateEngine.getEngineSlot().getLength() * baseLengthMult;
        float baseWidth = templateEngine.getEngineSlot().getWidth() * baseWidthMult;
        render.setBaseLength(baseLength);
        render.setBaseWidth(baseWidth);

        // 设置引擎渲染参数
        render.setGlowSizeMult(glowSizeMult);
        render.setLengthMult(lengthMult);
        render.setWidthMult(widthMult);

        // 设置引擎颜色（直接使用样板引擎的颜色）
        if (templateEngine.getEngineColor() != null) {
            render.setColorOverride(templateEngine.getEngineColor());
        }

        // 设置使用特定的开火点
        render.setFirePointIndex(firePointIndex);

        // 获取开火点配置的角度
        // 从武器配置文件中读取开火点的角度（turretAngleOffsets 或 hardpointAngleOffsets）
        List<Float> angleOffsets = weapon.getSpec().getTurretAngleOffsets();
        if (angleOffsets == null || angleOffsets.isEmpty()) {
            angleOffsets = weapon.getSpec().getHardpointAngleOffsets();
        }

        // 获取该开火点的角度
        float firePointAngle = 0f;
        if (angleOffsets != null && firePointIndex < angleOffsets.size()) {
            firePointAngle = angleOffsets.get(firePointIndex);
        }

        // 直接使用开火点配置的角度作为引擎喷射方向
        // 例如：开火点角度 = 180°，引擎就向后（180°）喷射
        // 开火点角度 = 0°，引擎就向前（0°）喷射
        // 开火点角度 = 90°，引擎就向左（90°）喷射
        // 开火点角度 = -90°，引擎就向右（-90°）喷射
        render.setFixedFacing(firePointAngle);

        return render;
    }

    /**
     * 根据舰船移动状态更新引擎渲染
     */
    private void updateEngineRendering(ShipAPI ship) {
        ShipEngineControllerAPI controller = ship.getEngineController();
        if (controller == null) {
            return;
        }

        // 检测舰船移动状态
        boolean isAccelerating = controller.isAccelerating();
        boolean isDecelerating = controller.isDecelerating();
        boolean isBackwards = controller.isAcceleratingBackwards();
        boolean isStrafingLeft = controller.isStrafingLeft();
        boolean isStrafingRight = controller.isStrafingRight();

        // 朝前的引擎（1-2）：后退时渲染
        boolean shouldRenderFront = isDecelerating || isBackwards;
        setEnginesActive(frontEngines, shouldRenderFront);

        // 朝左的引擎（3-4）：右移时渲染
        boolean shouldRenderLeft = isStrafingRight;
        setEnginesActive(leftEngines, shouldRenderLeft);

        // 朝右的引擎（5-6）：左移时渲染
        boolean shouldRenderRight = isStrafingLeft;
        setEnginesActive(rightEngines, shouldRenderRight);

        // 朝后的引擎（7-8）：前进时渲染
        boolean shouldRenderBack = isAccelerating;
        setEnginesActive(backEngines, shouldRenderBack);
    }

    /**
     * 设置引擎组的激活状态
     */
    private void setEnginesActive(List<Moci_EngineRender> engines, boolean active) {
        for (Moci_EngineRender render : engines) {
            if (render != null) {
                render.setActive(active);
            }
        }
    }
}
