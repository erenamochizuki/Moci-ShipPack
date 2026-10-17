package data.scripts.weapons;

import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;

public class Moci_mjolnir_OnHitEffect implements OnHitEffectPlugin {
    
    private static final float FLUX_DAMAGE = 200f;
    
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target,
                     Vector2f point, boolean shieldHit, ApplyDamageResultAPI damageResult, 
                     CombatEngineAPI engine) {
        
        if (shieldHit && target instanceof ShipAPI) {
            // 应用软通量伤害
            engine.applyDamage(
                target,
                point,
                FLUX_DAMAGE,
                DamageType.ENERGY,  // 伤害类型设置为软幅能
                0f,               // 无EMP
                false,            // 不绕过护盾
                true,            // 造成软幅能
                projectile.getSource()
            );
        }
    }
}