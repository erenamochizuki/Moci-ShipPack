package data.shipsystems.scripts.ai;

import com.fs.starfarer.api.combat.FluxTrackerAPI;
import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import data.shipsystems.scripts.Moci_RecallDeviceStats;

/**
 * 紧急召回装置 - AI控制脚本
 * 功能：自动判断何时激活召回系统
 * 
 * 激活条件：
 * 1. 母舰幅能低于50%（确保有足够幅能容量）
 * 2. 有需要救援的战机存在
 */
public class Moci_RecallAI implements ShipSystemAIScript {
    
    private ShipAPI ship;
    private CombatEngineAPI engine;
    private ShipwideAIFlags flags;
    private ShipSystemAPI system;
    private IntervalUtil tracker;
    
    public Moci_RecallAI() {
        // 每0.1-0.5秒检查一次
        this.tracker = new IntervalUtil(0.1f, 0.5f);
    }
    
    @Override
    public void init(final ShipAPI ship, final ShipSystemAPI system, final ShipwideAIFlags flags, final CombatEngineAPI engine) {
        this.ship = ship;
        this.flags = flags;
        this.engine = engine;
        this.system = ship.getSystem();
    }
    
    @Override
    public void advance(final float amount, final Vector2f missileDangerDir, final Vector2f collisionDangerDir, final ShipAPI target) {
        this.tracker.advance(amount);
        float decision = 0f;
        
        if (engine == null) {
            return;
        }
        if (engine.isPaused()) {
            return;
        }
        
        if (tracker.intervalElapsed()) {
            final FluxTrackerAPI fluxTracker = ship.getFluxTracker();
            
            // 判断是否应该激活召回系统
            // 条件1：幅能低于50%（确保有足够容量）
            // 条件2：有需要救援的战机
            if (fluxTracker.getFluxLevel() <= 0.50f && Moci_RecallDeviceStats.getFighters(ship).size() > 0) {
                decision += 9999f;  // 极高优先级，立即激活
            }
            
            // 如果决策值足够高且系统未激活，则使用系统
            if (decision >= 2f && !system.isActive()) {
                ship.useSystem();
            }
        }
    }
}
