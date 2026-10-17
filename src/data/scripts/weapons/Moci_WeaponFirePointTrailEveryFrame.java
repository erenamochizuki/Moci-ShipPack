package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import data.scripts.weapons.render.Moci_WeaponFirePointTrailRenderer;

/**
 * 武器EveryFrame脚本：
 * - 初始化一次拖尾渲染器
 * - 锚点使用武器第一个开火点
 * - 朝向使用“武器中心 -> 第一个开火点”的方向
 */
public class Moci_WeaponFirePointTrailEveryFrame implements EveryFrameWeaponEffectPlugin {

    private static final float IDLE_INTENSITY = 0.20f;

    private boolean initialized = false;
    private Moci_WeaponFirePointTrailRenderer renderer;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || weapon == null || weapon.getShip() == null) {
            return;
        }

        if (!initialized) {
            initialized = true;

            SpriteAPI trailPrimary = Global.getSettings().getSprite("graphics/fx/trail_flame.png");
            SpriteAPI trailDetail = Global.getSettings().getSprite("graphics/fx/beam_rough2_core.png");

            renderer = new Moci_WeaponFirePointTrailRenderer(weapon, 0, trailPrimary, trailDetail);
            renderer.setTrailShape(220f, 36f, 84);
            renderer.setScrollSpeed(0.55f);
            renderer.setIntensityChangeSpeed(6f);

            engine.addLayeredRenderingPlugin(renderer);
        }

        if (renderer == null) {
            return;
        }

        if (!weapon.getShip().isAlive() || weapon.getShip().isHulk()) {
            renderer.forceExpire();
            return;
        }

        float targetIntensity = IDLE_INTENSITY;
        if (weapon.isDisabled() || weapon.isPermanentlyDisabled()) {
            targetIntensity = 0f;
        } else {
            if (weapon.getChargeLevel() > targetIntensity) {
                targetIntensity = weapon.getChargeLevel();
            }
            if (weapon.isFiring()) {
                targetIntensity = 1f;
            }
        }

        renderer.setTargetIntensity(targetIntensity);
    }
}
