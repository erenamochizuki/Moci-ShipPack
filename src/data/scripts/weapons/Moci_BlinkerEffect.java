package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import java.awt.Color;

public class Moci_BlinkerEffect implements EveryFrameWeaponEffectPlugin {

    // ===== 配置参数 ===== //
    private static final float MIN_ALPHA = 0f;   // 最小透明度（0-1）
    private static final float PEAK_DURATION = 15f; // 达到最大透明度需要的持续时间（秒）
    private static final float FADE_RATE = 0.1f;    // 超过峰值后的衰减速率（每秒降低比例）

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 引擎暂停或无效武器时直接返回
        if (engine == null || engine.isPaused() || weapon == null) return;

        // 获取绑定舰船
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            resetWeaponAppearance(weapon);
            return;
        }

        // 从舰船数据获取BUFF持续时间
        Float duration = getBuffDuration(ship);
        if (duration == null) {
            resetWeaponAppearance(weapon);
            return;
        }

        // 计算动态透明度
        float alpha = calculateDynamicAlpha(duration, amount);
        applyVisualEffect(weapon, alpha);
    }

    /**
     * 从舰船自定义数据获取BUFF持续时间
     */
    private Float getBuffDuration(ShipAPI ship) {
        Object data = ship.getCustomData().get("LIDAR_BUFF_DURATION");
        return (data instanceof Float) ? (Float) data : null;
    }

    /**
     * 计算动态透明度值
     * @param duration 当前BUFF持续时间（秒）
     * @param amount 时间增量（秒）
     * @return 计算后的透明度（0.0-1.0）
     */
    private float calculateDynamicAlpha(float duration, float amount) {
        // 第一阶段：持续时间在0-15秒之间
        if (duration <= PEAK_DURATION) {
            return Math.max(MIN_ALPHA, duration / PEAK_DURATION);
        }

        // 第二阶段：超过15秒后的衰减
        float overtime = duration - PEAK_DURATION;
        return Math.max(MIN_ALPHA, 1f - (overtime * FADE_RATE));
    }

    /**
     * 应用视觉效果
     */
    private void applyVisualEffect(WeaponAPI weapon, float alpha) {
        // 限制透明度范围
        alpha = Math.max(MIN_ALPHA, Math.min(1f, alpha));

        // 转换为整型透明度值（0-255）
        int alphaChannel = (int) (alpha * 255);

        // 创建颜色实例并应用
        Color color = new Color(255, 255, 255, alphaChannel);
        weapon.getSprite().setColor(color);

        // 强制更新武器动画帧（保持激活状态）
        if (weapon.getAnimation() != null) {
            weapon.getAnimation().setFrame(1);
        }
    }

    /**
     * 重置武器外观到默认状态
     */
    private void resetWeaponAppearance(WeaponAPI weapon) {
        // 设置最低透明度
        Color color = new Color(255, 255, 255, (int)(MIN_ALPHA * 255));
        weapon.getSprite().setColor(color);

        // 重置动画帧
        if (weapon.getAnimation() != null) {
            weapon.getAnimation().setFrame(0);
        }
    }
}