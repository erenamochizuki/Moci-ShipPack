package data.scripts.weapons.misc;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

public class Moci_GugacannonFireSoundEffect implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    private static final String MOD_ID = "moci_shippack";
    private static final String SETTING_KEY = "Moci_gugacannonFireSound";
    private static final String DEFAULT_FIRE_SOUND = "gigacannon_fire";
    // LunaSettings 里 Radio 选项的取值（已写进玩家存档配置，不能随音效 ID 改名）
    private static final String GUGUGAGA_SETTING_VALUE = "Moci_gugugaga";
    // sounds.json 中注册的音效 ID
    private static final String GUGUGAGA_FIRE_SOUND = "moci_wpn_gugugaga";

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // OnFireEffectPlugin is triggered through the everyFrameEffect hook.
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (weapon == null || weapon.getShip() == null) {
            return;
        }

        Global.getSoundPlayer().playSound(
                getSelectedFireSound(),
                1f,
                1f,
                projectile != null ? projectile.getLocation() : weapon.getLocation(),
                weapon.getShip().getVelocity()
        );
    }

    private String getSelectedFireSound() {
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                String configuredSound = lunalib.lunaSettings.LunaSettings.getString(MOD_ID, SETTING_KEY);
                if (GUGUGAGA_SETTING_VALUE.equals(configuredSound)) {
                    return GUGUGAGA_FIRE_SOUND;
                }
            }
        } catch (Exception ignored) {
            // Fall back to the vanilla selection if LunaLib is unavailable or errors out.
        }
        return DEFAULT_FIRE_SOUND;
    }
}
