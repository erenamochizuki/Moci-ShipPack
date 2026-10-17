package data.scripts.weapons;

import com.fs.starfarer.api.combat.*;
import data.scripts.weapons.render.Moci_EngineRender;
import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.graphics.SpriteAPI;

import java.awt.*;

public class Moci_MechSystemFollowerAnimationWeapon implements EveryFrameWeaponEffectPlugin {
    int current = 0;
    float timer = 0;

    // 引擎控制参数
    private Color engineColor = new Color(43, 149, 255, 255);

    public static float ENGINE_LENGTH_MULT = 8f;  // 尾焰长度乘数
    public static float ENGINE_WIDTH_MULT = 4f;    // 尾焰宽度乘数
    public static float GLOW_SIZE_MULT = 2.0f;  // 光亮尺寸乘数

    // Moci_EngineRender render; //渲染器应当主动同步位置到武器开火点，然后在系统激活的时候进行渲染，武器只负责创建引擎渲染器
    boolean init = false;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        if (weapon.getAnimation() == null) {
            return;
        }

        // 检查武器槽位ID是否以_B结尾
        boolean isBSlot = weapon.getSlot().getId().endsWith("_B");

        if (!init && !isBSlot) {  // 只有非_B槽位的武器才初始化引擎渲染器
            init = true;
            ShipAPI ship = weapon.getShip();

            if (ship.getEngineController() != null && ship.getEngineController().getShipEngines() != null) {
                // 不再寻找最近的引擎，而是直接使用武器自身作为引擎位置
                ShipEngineControllerAPI.ShipEngineAPI dummyEngine = null;

                // 创建一个虚拟引擎槽
                for (ShipEngineControllerAPI.ShipEngineAPI engineSlot : ship.getEngineController().getShipEngines()) {
                    if (engineSlot != null) {
                        dummyEngine = engineSlot;
                        break; // 随便取一个引擎作为模板
                    }
                }

                SpriteAPI glowSprite = Global.getSettings().getSprite("fx", "engineglow32b");
                SpriteAPI flameSprite = Global.getSettings().getSprite("fx", "MBSglowOutline");
                SpriteAPI cricleSprite = Global.getSettings().getSprite("fx", "MBSEngineglowCricleNothing");

                // 确保找到有效引擎
                if (dummyEngine != null) {
                    Moci_EngineRender render = new Moci_EngineRender(
                            weapon.getShip(),
                            dummyEngine,
                            weapon,
                            glowSprite,
                            flameSprite,
                            cricleSprite,
                            CombatEngineLayers.ABOVE_SHIPS_LAYER,
                            CombatEngineLayers.ABOVE_SHIPS_LAYER,
                            false
                    );
                    // 设置光亮参数
                    render.setGlowSizeMult(GLOW_SIZE_MULT);
                    // 设置引擎颜色
                    // 获取原始引擎颜色，如果有的话
                    Color engineColorToUse = engineColor; // 默认使用预设颜色
                    if (dummyEngine.getEngineColor() != null) {
                        // 使用原始引擎颜色，但保留我们自定义的透明度
                        engineColorToUse = new Color(
                                dummyEngine.getEngineColor().getRed(),
                                dummyEngine.getEngineColor().getGreen(),
                                dummyEngine.getEngineColor().getBlue(),
                                engineColor.getAlpha()
                        );
                    }
                    render.setColorOverride(engineColorToUse);
                    // 设置尾焰尺寸参数
                    render.setLengthMult(ENGINE_LENGTH_MULT);
                    render.setWidthMult(ENGINE_WIDTH_MULT);

                    engine.addLayeredRenderingPlugin(render);
                }
            }
        }
        weapon.getAnimation().pause();
        boolean shouldActive = (weapon.getShip().getSystem() != null && weapon.getShip().getSystem().isActive()) || activeOverride;
        int facing = shouldActive ? 1 : -1;
        if (facing < 0) {
            if (current > 0) {
                timer += amount * weapon.getAnimation().getFrameRate();
                while (timer >= 1) {
                    timer -= 1;
                    current -= 1;
                    if (current == 0) {
                        timer = 0;
                        break;
                    }
                }
            }
        } else {
            int total = weapon.getAnimation().getNumFrames() - 1;
            if (current < total) {
                timer += amount * weapon.getAnimation().getFrameRate();
                while (timer >= 1) {
                    timer -= 1;
                    current += 1;
                    if (current == total) {
                        timer = 0;
                        break;
                    }
                }
            }
        }
        weapon.getAnimation().setFrame(current);
    }

    private boolean activeOverride = false;

    public void setExtraActive(boolean active) {
        this.activeOverride = active;
    }

    public boolean getActiveOverride() {
        return activeOverride;
    }
}
