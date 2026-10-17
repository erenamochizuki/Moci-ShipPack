package data.scripts;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import data.scripts.util.Moci_ProjectileConvergenceUtil;

import java.util.List;

/**
 * 弹丸收束系统战斗插件
 * 
 * 功能：在每帧更新所有注册的收束弹丸
 * 
 * 注册方式：在 settings.json 的 plugins 部分添加：
 * "mociProjectileConvergenceCombatPlugin": "data.scripts.Moci_ProjectileConvergenceCombatPlugin"
 */
public class Moci_ProjectileConvergenceCombatPlugin extends BaseEveryFrameCombatPlugin {
    
    private CombatEngineAPI engine;
    
    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }
    
    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        
        // 更新所有收束弹丸
        Moci_ProjectileConvergenceUtil.updateProjectiles(amount, engine);
    }
}
