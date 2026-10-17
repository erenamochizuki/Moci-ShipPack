package data.shipsystems.scripts;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.HullDamageAboutToBeTakenListener;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.util.IntervalUtil;

public class Moci_Great_Determination extends BaseShipSystemScript {
    // 常量定义
    private static final float HULL_THRESHOLD = 0.2f; // AI使用系统的生命值阈值
    private static final Color JITTER_COLOR = new Color(0, 255, 100, 255); // 抖动效果颜色
    private static final float DEFAULT_DURATION = 5f; // 默认持续时间，仅在无法获取实际时间时使用
    
    // 成员变量
    private boolean wasUsed = false; // 是否已使用过系统
    private IntervalUtil repairInterval = new IntervalUtil(0.1f, 0.2f); // 修复间隔
    private Object JITTER_KEY = new Object();
    private boolean listenerAdded = false; // 标记是否已添加监听器
    
    // 被动触发系统的监听器
    public static class DeterminationListener implements HullDamageAboutToBeTakenListener {
        private final ShipAPI ship;
        
        public DeterminationListener(ShipAPI ship) {
            this.ship = ship;
        }
        
        @Override
        public boolean notifyAboutToTakeHullDamage(Object param, ShipAPI ship, Vector2f point, float damageAmount) {
            // 检查是否会致命
            if (damageAmount >= ship.getHitpoints()) {
                ShipSystemAPI system = getSystem(ship);
                // 检查系统是否可用、未激活、有弹药且已冷却
                if (isSystemUsable(system)) {
                    ship.setHitpoints(1f); // 保留1点结构
                    // 激活系统
                    ship.useSystem();
                    return true; // 阻止伤害
                }
            }
            return false;
        }
    }
    
    /**
     * 检查系统是否可以使用
     */
    private static boolean isSystemUsable(ShipSystemAPI system) {
        if (system == null) return false;
        return !system.isActive() && 
               system.getAmmo() > 0 && 
               system.getCooldownRemaining() <= 0 &&
               !system.isOutOfAmmo();
    }
    
    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = null;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        } else {
            return;
        }
        
        // 确保添加了被动触发监听器
        if (!listenerAdded) {
            addSystemListener(ship);
            listenerAdded = true;
        }
        
        // 系统激活效果
        if (state == State.IN) {
            wasUsed = true;
            
            // 无敌状态
            stats.getHullDamageTakenMult().modifyMult(id, 0f);
            stats.getArmorDamageTakenMult().modifyMult(id, 0f);
            stats.getEmpDamageTakenMult().modifyMult(id, 0f);

            float jitterLevel = effectLevel * 0.5f;
            float maxRangeBonus = 50f;
            ship.setJitter(JITTER_KEY, JITTER_COLOR, jitterLevel, 3, 0f, maxRangeBonus);
            
            // 播放系统激活音效和视觉效果(仅在刚激活时)
            if (effectLevel < 0.1f) {
                Global.getSoundPlayer().playSound("system_temporalshell", 1f, 1f, ship.getLocation(), ship.getVelocity());
                
                Global.getCombatEngine().addFloatingText(
                    ship.getLocation(), 
                    "大毅力激活!", 
                    20f, 
                    JITTER_COLOR, 
                    ship, 
                    0.5f, 
                    1.0f
                );
            }
            
            // 结构和装甲修复
            float amount = Global.getCombatEngine().getElapsedInLastFrame();
            repairInterval.advance(amount);
            if (repairInterval.intervalElapsed()) {
                // 计算修复量 - 使用默认的固定持续时间
                float repairPercentPerInterval = repairInterval.getIntervalDuration() / DEFAULT_DURATION;
                
                // 修复结构
                float maxHull = ship.getMaxHitpoints();
                float currentHull = ship.getHitpoints();
                float hullToRepair = maxHull * repairPercentPerInterval;
                ship.setHitpoints(Math.min(currentHull + hullToRepair, maxHull));
                
                // 修复装甲
                ArmorGridAPI armorGrid = ship.getArmorGrid();
                if (armorGrid != null) {
                    float maxArmorInCell = armorGrid.getMaxArmorInCell();
                    int width = armorGrid.getGrid().length;
                    int height = armorGrid.getGrid()[0].length;
                    
                    for (int x = 0; x < width; x++) {
                        for (int y = 0; y < height; y++) {
                            float currentArmor = armorGrid.getArmorValue(x, y);
                            float armorToRepair = maxArmorInCell * repairPercentPerInterval;
                            armorGrid.setArmorValue(x, y, Math.min(currentArmor + armorToRepair, maxArmorInCell));
                        }
                    }
                }
                
                // 添加修复特效
                Global.getCombatEngine().addFloatingText(
                    ship.getLocation(), 
                    "修复中...", 
                    15f, 
                    JITTER_COLOR, 
                    ship, 
                    0.5f, 
                    0.5f
                );
            }
        }
        // 系统结束时重置
        else if (state == State.OUT) {
            if (wasUsed) {
                stats.getHullDamageTakenMult().unmodify(id);
                stats.getArmorDamageTakenMult().unmodify(id);
                stats.getEmpDamageTakenMult().unmodify(id);
                wasUsed = false;
            }
        }
    }
    
    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        stats.getHullDamageTakenMult().unmodify(id);
        stats.getArmorDamageTakenMult().unmodify(id);
        stats.getEmpDamageTakenMult().unmodify(id);
        wasUsed = false;
    }
    
    @Override
    public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
        if (system.isOutOfAmmo()) return "无使用次数";
        if (system.getState() != ShipSystemAPI.SystemState.IDLE) return null;
        
        float hullLevel = ship.getHullLevel();
        if (hullLevel <= HULL_THRESHOLD) {
            return "准备激活";
        }
        return "待机中";
    }
    
    @Override
    public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
        return system.getAmmo() > 0 && !system.isOutOfAmmo(); 
    }
    
    /**
     * 获取舰船的系统，优先查找相位系统位置
     */
    public static ShipSystemAPI getSystem(ShipAPI ship) {
        ShipSystemAPI system = ship.getPhaseCloak();
        if (system != null && system.getId().equals("Moci_greatdetermination")) return system;
        return ship.getSystem();
    }
    
    /**
     * 为舰船添加被动触发监听器
     */
    public static void addSystemListener(ShipAPI ship) {
        if (!ship.hasListenerOfClass(DeterminationListener.class)) {
            ship.addListener(new DeterminationListener(ship));
        }
    }
} 