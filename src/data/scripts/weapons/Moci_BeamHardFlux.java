package data.scripts.weapons;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;

/**
 * 硬幅能光束武器效果
 * 功能：让光束武器造成的伤害全部转化为硬幅能
 */
public class Moci_BeamHardFlux implements BeamEffectPlugin {
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        // 引擎暂停时不处理
        if (engine.isPaused()) return;
        
        // 直接设置光束伤害为硬幅能
        if (beam.getDamage() != null) {
            beam.getDamage().setForceHardFlux(true);
        }
    }
} 