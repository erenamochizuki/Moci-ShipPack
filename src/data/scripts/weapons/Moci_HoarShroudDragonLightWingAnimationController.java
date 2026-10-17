package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 霜岚光翼/肩灯的“纯帧动画控制器”。
 *
 * 这个类现在只负责：
 * 1. 控制装饰武器的贴图帧动画
 * 2. 按 ship.getSystem().getEffectLevel() 调整动画透明度
 *
 * 文件关系：
 * - 本文件：只做“帧动画”
 * - Moci_HoarShroudDragonLightWingEffectController：只做“光翼特效注册”
 * - Moci_HoarShroudDragonLightWingCombinedController：.wpn 实际挂载的组合入口
 * - Moci_HoarShroudDragonLightWingRenderer：真正负责 FBO 双层拖尾渲染
 *
 * 使用方式：
 * - 如果你只想复用“装饰武器翻帧动画”，可以直接把 .wpn 的 everyFrameEffect 指向本类。
 * - 如果你想同时要“动画 + 光翼拖尾”，推荐把 .wpn 指向 CombinedController，
 *   让它内部同时调用本类和 EffectController。
 *
 * 注意：
 * - 本类不再负责注册任何渲染插件。
 */
public class Moci_HoarShroudDragonLightWingAnimationController implements EveryFrameWeaponEffectPlugin {
    /**
     * 当 .wpn 没有设置有效 frameRate 时使用的默认帧率。
     *
     * 调大：
     * - 翻帧更快，视觉更活跃
     * 调小：
     * - 翻帧更慢，展开更沉稳
     */
    private static final float DEFAULT_FRAME_RATE = 30f;

    /**
     * 当前动画帧。
     *
     * -1 表示尚未初始化，会在真正开始播放时回到第 0 帧。
     */
    private int currentFrame = -1;
    /**
     * 手动推进动画的累计计时器。
     */
    private float timer = 0f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (weapon == null) {
            return;
        }

        if (engine == null || engine.isPaused() || weapon.getAnimation() == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            applyAnimationAlpha(weapon, 0f);
            setBlankFrame(weapon);
            return;
        }

        int totalFrames = weapon.getAnimation().getNumFrames();
        if (totalFrames <= 0) {
            return;
        }

        int blankFrame = totalFrames - 1;
        int lastActiveFrame = Math.max(0, blankFrame - 1);
        weapon.getAnimation().pause();

        float effectLevel = getSystemEffectLevel(ship);
        applyAnimationAlpha(weapon, effectLevel);

        if (effectLevel <= 0f) {
            setBlankFrame(weapon);
            return;
        }

        if (currentFrame < 0 || currentFrame > lastActiveFrame) {
            currentFrame = 0;
        }

        float frameRate = weapon.getAnimation().getFrameRate();
        if (frameRate <= 0f) {
            frameRate = DEFAULT_FRAME_RATE;
        }

        timer += amount * frameRate;
        while (timer >= 1f) {
            timer -= 1f;
            currentFrame++;
            if (currentFrame > lastActiveFrame) {
                currentFrame = 0;
            }
        }

        setSafeFrame(weapon, currentFrame);
    }

    private float getSystemEffectLevel(ShipAPI ship) {
        if (ship == null || ship.getSystem() == null) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, ship.getSystem().getEffectLevel()));
    }

    private void applyAnimationAlpha(WeaponAPI weapon, float effectLevel) {
        if (weapon != null && weapon.getAnimation() != null) {
            weapon.getAnimation().setAlphaMult(Math.max(0f, Math.min(1f, effectLevel)));
        }
    }

    private void setBlankFrame(WeaponAPI weapon) {
        int blankFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        currentFrame = clampFrame(weapon, blankFrame);
        timer = 0f;
        setSafeFrame(weapon, currentFrame);
        weapon.getAnimation().pause();
    }

    private int clampFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return Math.max(0, frame);
        }
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        return Math.max(0, Math.min(frame, maxFrame));
    }

    private void setSafeFrame(WeaponAPI weapon, int frame) {
        if (weapon == null || weapon.getAnimation() == null) {
            return;
        }
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        int safeFrame = Math.max(0, Math.min(frame, maxFrame));
        weapon.getAnimation().setFrame(safeFrame);
    }
}
