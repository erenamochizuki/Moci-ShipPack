package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * Moci_Weapon4的触发器
 * 移除发射的弹丸，触发主武器效果
 */
public class Moci_Weapon4OnFireEffect implements OnFireEffectPlugin {
    
    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 移除发射的弹丸
        if (projectile != null) {
            engine.removeEntity(projectile);
        }
        
        // 直接获取并触发武器效果实例
        Moci_Weapon4Effect effectPlugin = Moci_Weapon4Effect.getWeapon4EffectInstance(weapon.getShip());
        if (effectPlugin != null) {
            effectPlugin.triggerPileAttack();
        }
    }
} 