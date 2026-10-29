package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

import data.shipsystems.scripts.Moci_DeepDiveMissileManager;

/**
 * TDD-1 内置左右导弹的发射器动画。
 *
 * 导弹进入深潜、退相位和状态恢复统一由
 * Moci_DeepDiveMissileManager 处理；本脚本不再逐帧强制开火，
 * 因而不会绕过或干扰游戏默认的武器组 AI。
 */
public class Moci_TDD_1_missileAnimEffect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    private int current = 0;
    private final int[] chargeUpSequence = new int[]{0, 8};
    private final int[] chargeDownSequence = new int[]{9, 12};
    private float timer = 0f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null || weapon.getShip() == null
                || !weapon.getShip().isAlive() || weapon.getAnimation() == null) {
            return;
        }

        weapon.getAnimation().pause();
        boolean charging = weapon.getChargeLevel() > 0f
                && (weapon.getCooldownRemaining() <= 0f || weapon.isInBurst());

        if (charging) {
            current = (int) (weapon.getChargeLevel() * chargeUpSequence[1]);
            timer = 0f;
        } else if (current > 0) {
            timer += amount * weapon.getAnimation().getFrameRate();
            while (timer >= 1f) {
                timer -= 1f;
                if (current == chargeDownSequence[1] || current == 0) {
                    current = 0;
                    break;
                }
                if (current >= chargeUpSequence[1]) {
                    current++;
                } else {
                    current--;
                }
            }
        }

        int maxFrame = Math.max(0, weapon.getAnimation().getNumFrames() - 1);
        weapon.getAnimation().setFrame(Math.max(0, Math.min(current, maxFrame)));
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (!(projectile instanceof MissileAPI) || weapon == null || engine == null
                || weapon.getShip() == null) {
            return;
        }
        Moci_DeepDiveMissileManager.register(engine, (MissileAPI) projectile, true);
    }
}
