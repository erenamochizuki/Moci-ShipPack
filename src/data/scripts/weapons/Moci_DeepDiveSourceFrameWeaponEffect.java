package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import data.shipsystems.scripts.Moci_DeepDive;

public class Moci_DeepDiveSourceFrameWeaponEffect extends Moci_HiddenWeapon {
    private static final int TARGET_MAX_FRAME = 7;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || weapon == null) {
            return;
        }

        // 先执行父类逻辑，保留 HiddenWeapon 的完整行为：
        // 1) SOURCE/FOLLOW 查找
        // 2) 开火点同步
        // 3) SOURCE/FOLLOW 隐藏
        // 4) 角度跟随
        super.advance(amount, engine, weapon);

        if (weapon.getAnimation() == null) {
            return;
        }

        int usableMaxFrame = Math.max(0, Math.min(TARGET_MAX_FRAME, weapon.getAnimation().getNumFrames() - 1));

        weapon.getAnimation().pause();

        // 效果1：有source时默认帧0，无source时默认帧7
        if (source == null) {
            setFrameSafe(weapon, usableMaxFrame);
            return;
        }

        // 效果2：仅在有source时才工作
        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            setFrameSafe(weapon, 0);
            return;
        }

        boolean deepDiveActive = Moci_DeepDive.isDeepDiveActive(ship);
        if (deepDiveActive) {
            // 深潜期间按系统effectLevel映射动画：0->7，退出时effectLevel下降则自动7->0
            float effectLevel = getDeepDiveEffectLevel(Moci_DeepDive.getDeepDiveSystem(ship));
            int targetFrame = Math.round(usableMaxFrame * effectLevel);
            setFrameSafe(weapon, targetFrame);

            // 深潜期间每帧禁止source武器开火
            source.setForceNoFireOneFrame(true);
        } else {
            // 非深潜状态回到默认帧0，同时不再施加禁火（即解锁）
            setFrameSafe(weapon, 0);
        }
    }

    private float getDeepDiveEffectLevel(com.fs.starfarer.api.combat.ShipSystemAPI system) {
        if (system == null) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, system.getEffectLevel()));
    }

    private void setFrameSafe(WeaponAPI weapon, int frame) {
        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        int clamped = Math.max(0, Math.min(frame, maxFrame));
        weapon.getAnimation().setFrame(clamped);
    }
}
