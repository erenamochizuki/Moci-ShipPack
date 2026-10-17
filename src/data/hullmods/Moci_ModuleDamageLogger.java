package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;

/**
 * 模块伤害日志记录器
 * 用于调试：记录模块受到的所有伤害及其来源
 * 
 * 使用方法：
 * 1. 在需要监控的模块上添加这个船插
 * 2. 运行游戏并查看 starsector.log
 * 3. 搜索 "ModuleDamageLogger" 查看所有伤害记录
 */
public class Moci_ModuleDamageLogger extends BaseHullMod {
    
    private static final String LOGGER_ID = "ModuleDamageLogger";
    
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 添加伤害监听器
        ship.addListener(new ModuleDamageListener(ship));
        
        // 记录船插已应用
        Global.getLogger(this.getClass()).info(
            LOGGER_ID + ": 已应用到舰船 " + getShipName(ship) + 
            " (槽位: " + (ship.getStationSlot() != null ? ship.getStationSlot().getId() : "无") + ")" +
            " 初始生命值: " + ship.getHitpoints() + "/" + ship.getMaxHitpoints()
        );
    }
    
    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        return null;
    }
    
    /**
     * 获取舰船名称（用于日志）
     */
    private String getShipName(ShipAPI ship) {
        if (ship.getStationSlot() != null) {
            return ship.getStationSlot().getId();
        }
        if (ship.getVariant() != null) {
            return ship.getVariant().getHullVariantId();
        }
        return "未知舰船";
    }
    
    /**
     * 伤害监听器内部类
     */
    private static class ModuleDamageListener implements DamageListener {
        private final ShipAPI ship;
        private float lastHitpoints;
        private boolean isDead = false;
        private int damageCount = 0;
        
        public ModuleDamageListener(ShipAPI ship) {
            this.ship = ship;
            this.lastHitpoints = ship.getHitpoints();
        }
        
        @Override
        public void reportDamageApplied(Object source, 
                                       CombatEntityAPI target, 
                                       ApplyDamageResultAPI result) {
            
            // 如果已经死亡，不再记录
            if (isDead) {
                return;
            }
            
            damageCount++;
            float currentHp = ship.getHitpoints();
            float hpLost = lastHitpoints - currentHp;
            
            // 记录伤害信息
            String sourceName = "未知";
            String sourceDetails = "";
            
            if (source instanceof ShipAPI) {
                ShipAPI sourceShip = (ShipAPI) source;
                sourceName = "舰船: " + sourceShip.getHullSpec().getHullName();
                if (sourceShip.getStationSlot() != null) {
                    sourceName += " (槽位: " + sourceShip.getStationSlot().getId() + ")";
                }
            } else if (source != null) {
                sourceName = "类型: " + source.getClass().getName();
                sourceDetails = " | toString: " + source.toString();
            } else {
                sourceName = "NULL";
            }
            
            Global.getLogger(this.getClass()).info(
                LOGGER_ID + ": [" + damageCount + "] 舰船 " + getShipName(ship) + 
                " 受到伤害 " + String.format("%.1f", result.getDamageToHull()) +
                " (类型: " + result.getType() + ")" +
                " 来源: " + sourceName + sourceDetails +
                " | 生命值: " + String.format("%.1f", lastHitpoints) + " -> " + String.format("%.1f", currentHp) +
                " (损失: " + String.format("%.1f", hpLost) + ")"
            );
            
            lastHitpoints = currentHp;
            
            // 检查是否死亡
            if (!ship.isAlive() && !isDead) {
                isDead = true;
                Global.getLogger(this.getClass()).error(
                    LOGGER_ID + ": =========================================="
                );
                Global.getLogger(this.getClass()).error(
                    LOGGER_ID + ": 舰船 " + getShipName(ship) + " 已死亡！"
                );
                Global.getLogger(this.getClass()).error(
                    LOGGER_ID + ": 最后伤害来源: " + sourceName
                );
                Global.getLogger(this.getClass()).error(
                    LOGGER_ID + ": 总共受到 " + damageCount + " 次伤害"
                );
                Global.getLogger(this.getClass()).error(
                    LOGGER_ID + ": =========================================="
                );
            }
        }
        
        /**
         * 获取舰船名称
         */
        private String getShipName(ShipAPI ship) {
            if (ship.getStationSlot() != null) {
                return ship.getStationSlot().getId();
            }
            if (ship.getVariant() != null) {
                return ship.getVariant().getHullVariantId();
            }
            return "未知舰船";
        }
    }
}
