package data.hullmods;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.HullDamageAboutToBeTakenListener;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

/**
 * 热核聚变熔炉船插
 * 功能：
 * 1. 提高25%的幅能容量和幅能耗散
 * 2. 舰船死亡时爆炸半径和伤害为5倍
 * 3. 如果舰船碰撞类型是战机，在死亡前将碰撞类型改为SHIP
 */
public class Moci_NuclearFusionReactor extends BaseHullMod {
    
    // 幅能增强倍数
    public static final float FLUX_CAPACITY_MULT = 1.25f;
    public static final float FLUX_DISSIPATION_MULT = 1.25f;
    
    // 爆炸增强倍数
    public static final float EXPLOSION_RADIUS_MULT = 5.0f;
    public static final float EXPLOSION_DAMAGE_MULT = 5.0f;
    
    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 增加25%的幅能容量和耗散
        stats.getFluxCapacity().modifyMult(id, FLUX_CAPACITY_MULT);
        stats.getFluxDissipation().modifyMult(id, FLUX_DISSIPATION_MULT);
        
        // 增加5倍的爆炸伤害和半径
        stats.getDynamic().getStat(Stats.EXPLOSION_DAMAGE_MULT).modifyMult(id, EXPLOSION_DAMAGE_MULT);
        stats.getDynamic().getStat(Stats.EXPLOSION_RADIUS_MULT).modifyMult(id, EXPLOSION_RADIUS_MULT);
    }
    
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 添加死亡监听器
        ship.addListener(new NuclearFusionDeathListener(ship));
    }
    
    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        switch (index) {
            case 0:
                return "25%"; // 幅能容量和耗散增加值
            case 1:
                return "5"; // 爆炸伤害和半径倍数
            default:
                return null;
        }
    }
    
    /**
     * 死亡监听器，在舰船即将受到致命伤害时修改碰撞类型
     */
    public static class NuclearFusionDeathListener implements HullDamageAboutToBeTakenListener {
        private ShipAPI ship;
        private boolean collisionTypeModified = false;
        
        public NuclearFusionDeathListener(ShipAPI ship) {
            this.ship = ship;
        }
        
        @Override
        public boolean notifyAboutToTakeHullDamage(Object param, ShipAPI ship, Vector2f point, float damageAmount) {
            // 检查是否为致命伤害
            if (damageAmount >= ship.getHitpoints() && !collisionTypeModified) {
                // 如果舰船原本是战机碰撞类型，改为SHIP类型以确保完整的爆炸效果
                if (ship.getCollisionClass() == CollisionClass.FIGHTER) {
                    ship.setCollisionClass(CollisionClass.SHIP);
                    collisionTypeModified = true;
                }
            }
            // 返回false表示不阻止伤害，让舰船正常死亡
            return false;
        }
    }
} 