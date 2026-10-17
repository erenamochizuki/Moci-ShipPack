package data.hullmods;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.hullmods.BaseLogisticsHullMod;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 重力波接收器
 * 功能：
 * 1. 降低舰船50%的CR下降速率
 * 2. 当在重力波天线范围内时：
 *    - 每10秒补充所有武器弹药
 *    - CR/峰值时间不下降
 */
public class Moci_GravityReceiver extends BaseLogisticsHullMod {
    private static final String TEXT_ID = "Moci_GravityReceiver";
    
    // 基础效果常量
    public static final float CR_DECAY_REDUCTION = 0.5f; // 50%减少CR下降速率
    
    // 距离常量 - 根据舰船级别
    public static final float FIGHTER_RANGE = 4000f;
    public static final float FRIGATE_RANGE = 3000f;
    public static final float DESTROYER_RANGE = 2200f;
    public static final float CRUISER_RANGE = 1800f;
    public static final float CAPITAL_RANGE = 1500f;
    
    // 弹药补充间隔
    public static final float AMMO_RELOAD_INTERVAL = 10f; // 回弹时间
    
    // 数据键
    private static final String AMMO_TIMER_KEY = "moci_gravity_receiver_ammo_timer";
    private static final String IN_RANGE_KEY = "moci_gravity_receiver_in_range";
    private static final String STATUS_KEY = "moci_gravity_receiver_status";
    
    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 基础效果：减少50%的CR下降速率
        stats.getCRLossPerSecondPercent().modifyMult(id, 1f - CR_DECAY_REDUCTION);
    }
    
    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        // 检查是否已经安装了重力波天线
        if (ship != null && ship.getVariant().hasHullMod("Moci_GravityAntenna")) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_reason");
        }
        return null;
    }
    
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        // 不能与重力波天线同时安装
        return ship == null || !ship.getVariant().hasHullMod("Moci_GravityAntenna");
    }
    
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 注册全局重力波管理器（单例）
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null) {
            // 使用自定义数据检查是否已经有重力波管理器
            String managerKey = "moci_gravity_wave_manager";
            Object existingManager = engine.getCustomData().get(managerKey);
            
            if (existingManager == null) {
                // 创建并添加管理器
                GravityWaveManager manager = new GravityWaveManager();
                engine.addPlugin(manager);
                engine.getCustomData().put(managerKey, manager);
            }
        }
        
        // 初始化舰船计时器
        if (ship.getCustomData().get(AMMO_TIMER_KEY) == null) {
            ship.setCustomData(AMMO_TIMER_KEY, new IntervalUtil(AMMO_RELOAD_INTERVAL, AMMO_RELOAD_INTERVAL));
        }
    }
    
    /**
     * 检查舰船的制造商是否是尼尔加重工 - 用于描述显示
     */
    private boolean isNelgarManufacturer(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) return false;
        
        try {
            String manufacturer = ship.getHullSpec().getManufacturer();
            if (manufacturer != null) {
                return manufacturer.equals("尼尔加重工") || manufacturer.equals("尼尔加重工(特装)");
            }
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error(
                "检查舰船制造商时发生错误: " + e.getMessage()
            );
        }
        
        return false;
    }
    
    /**
     * 通过stats检查舰船的制造商是否是尼尔加重工 - 用于applyEffectsBeforeShipCreation
     */
    private boolean isNelgarManufacturerByStats(MutableShipStatsAPI stats) {
        if (stats == null || stats.getVariant() == null) return false;
        
        try {
            String hullId = stats.getVariant().getHullSpec().getHullId();
            String manufacturer = stats.getVariant().getHullSpec().getManufacturer();
            if (manufacturer != null) {
                return manufacturer.equals("尼尔加重工") || manufacturer.equals("尼尔加重工(特装)");
            }
        } catch (Exception e) {
            Global.getLogger(this.getClass()).error(
                "检查舰船制造商时发生错误: " + e.getMessage()
            );
        }
        
        return false;
    }
    
    /**
     * 根据舰船级别获取所需距离 - 用于描述显示
     */
    private float getRequiredRange(HullSize hullSize, ShipAPI ship) {
        float baseRange;
        switch (hullSize) {
            case FIGHTER:
                baseRange = FIGHTER_RANGE;
                break;
            case FRIGATE:
                baseRange = FRIGATE_RANGE;
                break;
            case DESTROYER:
                baseRange = DESTROYER_RANGE;
                break;
            case CRUISER:
                baseRange = CRUISER_RANGE;
                break;
            case CAPITAL_SHIP:
                baseRange = CAPITAL_RANGE;
                break;
            default:
                baseRange = FRIGATE_RANGE;
                break;
        }
        
        // 检查制造商和是否内置，如果不是尼尔加重工且未内置，范围减半
        boolean sMod = ship != null && ship.getVariant().getSModdedBuiltIns().contains("Moci_GravityReceiver");
        if (ship != null && !isNelgarManufacturer(ship) && !sMod) {
            baseRange *= 0.4f;
        }
        
        return baseRange;
    }
    
    @Override
    public String getDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
        if (index == 0) return Moci_TextLoader.getText(TEXT_ID, "description.param_cr_decay_reduction"); // CR下降减少
        if (index == 1) return Moci_TextLoader.getText(TEXT_ID, "description.param_reload_interval"); // 弹药补充间隔
        if (index == 2) {
            // 作用距离 - 需要传递ship参数来检查制造商
            if (ship != null) {
                return (int)getRequiredRange(hullSize, ship) + "";
            } else {
                // 如果ship为null，返回基础范围
                switch (hullSize) {
                    case FIGHTER: return (int)FIGHTER_RANGE + "";
                    case FRIGATE: return (int)FRIGATE_RANGE + "";
                    case DESTROYER: return (int)DESTROYER_RANGE + "";
                    case CRUISER: return (int)CRUISER_RANGE + "";
                    case CAPITAL_SHIP: return (int)CAPITAL_RANGE + "";
                    default: return (int)FRIGATE_RANGE + "";
                }
            }
        }
        if (index == 3) {
            // 弹药补充比例
            boolean sMod = ship != null && ship.getVariant().getSModdedBuiltIns().contains("Moci_GravityReceiver");
            if (ship != null && !isNelgarManufacturer(ship) && !sMod) {
                return Moci_TextLoader.getText(TEXT_ID, "description.param_reload_reduced"); // 非尼尔加制造商且未内置
            } else {
                return Moci_TextLoader.getText(TEXT_ID, "description.param_reload_default"); // 尼尔加制造商或内置
            }
        }
        return null;
    }
    

    @Override
    public String getSModDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
        if (index == 0) return Moci_TextLoader.getText(TEXT_ID, "description.smod_param_nelgar");
        if (index == 1) return Moci_TextLoader.getText(TEXT_ID, "description.smod_param_other");
        return null;
    }
    
    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }
    
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width,
            boolean isForModSpec) {
        float pad = 3f;
        Color h = Misc.getHighlightColor();
        Color good = Misc.getPositiveHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();
        
        // 获取作用范围
        float range;
        String reloadPercent;
        boolean sMod = ship != null && ship.getVariant().getSModdedBuiltIns().contains("Moci_GravityReceiver");
        if (ship != null) {
            range = getRequiredRange(hullSize, ship);
            boolean isNelgar = isNelgarManufacturer(ship);
            reloadPercent = (isNelgar || sMod) ? "50%" : "20%";
        } else {
            // 如果ship为null，显示基础范围
            switch (hullSize) {
                case FIGHTER: range = FIGHTER_RANGE; break;
                case FRIGATE: range = FRIGATE_RANGE; break;
                case DESTROYER: range = DESTROYER_RANGE; break;
                case CRUISER: range = CRUISER_RANGE; break;
                case CAPITAL_SHIP: range = CAPITAL_RANGE; break;
                default: range = FRIGATE_RANGE; break;
            }
            reloadPercent = "50%";
        }
        
        tooltip.addPara(
                Moci_TextLoader.getTextWithReplacements(TEXT_ID, "description.main",
                        Moci_TextLoader.mapOf("range", String.valueOf((int) range),
                                "interval", String.valueOf((int) AMMO_RELOAD_INTERVAL),
                                "reload_percent", reloadPercent)),
                pad,
                new Color[]{h, good, good, good, h, good},
                Moci_TextLoader.getHighlightsWithReplacements(TEXT_ID, "description.main_highlights",
                        Moci_TextLoader.mapOf("range", String.valueOf((int) range),
                                "interval", String.valueOf((int) AMMO_RELOAD_INTERVAL),
                                "reload_percent", reloadPercent)).toArray(new String[0]));
        
        if (!sMod) {
            tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.manufacturer_penalty"),
                    pad,
                    new Color[]{h, bad},
                    Moci_TextLoader.getHighlights(TEXT_ID, "description.manufacturer_penalty_highlights").toArray(new String[0]));
        } else {
            // 内置效果说明
            boolean isNelgar = ship != null && isNelgarManufacturer(ship);
            if (isNelgar) {
                tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.smod_nelgar"),
                        pad,
                        new Color[]{good},
                        Moci_TextLoader.getHighlights(TEXT_ID, "description.smod_nelgar_highlights").toArray(new String[0]));
            } else {
                tooltip.addPara(Moci_TextLoader.getText(TEXT_ID, "description.smod_other"),
                        pad,
                        new Color[]{good},
                        Moci_TextLoader.getHighlights(TEXT_ID, "description.smod_other_highlights").toArray(new String[0]));
            }
        }
    }
    
    /**
     * 全局重力波管理器 - 避免重复执行
     * 作为战斗插件运行，确保整个战斗只有一个实例在处理重力波逻辑
     */
    public static class GravityWaveManager extends BaseEveryFrameCombatPlugin {
        
        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) return;
            
            // 遍历所有舰船，寻找安装了重力波接收器的舰船
            for (ShipAPI ship : engine.getShips()) {
                if (ship == null || !ship.isAlive()) continue;
                if (!ship.getVariant().hasHullMod("Moci_GravityReceiver")) continue;
                
                // 检查是否在重力波天线范围内
                boolean inRange = isInGravityAntennaRange(ship, engine);
                ship.setCustomData(IN_RANGE_KEY, inRange);
                
                if (inRange) {
                    // 在范围内时的特殊效果
                    handleInRangeEffects(ship, amount);
                } else {
                    // 不在范围内时，移除零幅能加速效果
                    ship.getMutableStats().getZeroFluxMinimumFluxLevel().unmodify("Moci_GravityReceiver_sMod");
                }
                
                // 显示状态（仅玩家舰船）
                displayStatus(ship, engine, inRange);
            }
        }
        
        /**
         * 显示状态信息
         */
        private void displayStatus(ShipAPI ship, CombatEngineAPI engine, boolean inRange) {
            // 只为玩家舰船显示
            if (ship != engine.getPlayerShip()) return;
            
            // 获取弹药补充计时器
            IntervalUtil ammoTimer = (IntervalUtil) ship.getCustomData().get(AMMO_TIMER_KEY);
            if (ammoTimer == null) return;
            
            // 计算倒计时
            float elapsed = ammoTimer.getElapsed();
            float interval = ammoTimer.getIntervalDuration();
            float remaining = interval - elapsed;
            
            // 构建状态信息
            String statusTitle = Moci_TextLoader.getText(TEXT_ID, "status.title");
            String statusInfo;
            
            if (inRange) {
                if (remaining > 0.1f) {
                    statusInfo = Moci_TextLoader.getTextWithReplacements(TEXT_ID, "status.in_range_countdown",
                            Moci_TextLoader.mapOf("seconds", String.format("%.1f", remaining)));
                } else {
                    statusInfo = Moci_TextLoader.getText(TEXT_ID, "status.in_range_ready");
                }
            } else {
                statusInfo = Moci_TextLoader.getText(TEXT_ID, "status.out_of_range");
            }
            
            // 显示状态
            engine.maintainStatusForPlayerShip(
                STATUS_KEY,
                "graphics/icons/hullsys/recall_device.png",
                statusTitle,
                statusInfo,
                !inRange // 如果不在范围内，显示为负面状态（红色）
            );
        }
        
        /**
         * 检查舰船是否在重力波天线范围内
         */
        private boolean isInGravityAntennaRange(ShipAPI ship, CombatEngineAPI engine) {
            HullSize hullSize = ship.getHullSize();
            float requiredRange = getRequiredRange(hullSize, ship);
            
            // 遍历所有舰船，寻找装有重力波天线的友方舰船
            for (ShipAPI otherShip : engine.getShips()) {
                if (otherShip == ship) continue; // 跳过自己
                if (otherShip.getOwner() != ship.getOwner()) continue; // 只检查友方
                if (!otherShip.isAlive()) continue; // 跳过已毁舰船
                
                // 检查是否装有重力波天线
                if (otherShip.getVariant().hasHullMod("Moci_GravityAntenna")) {
                    float distance = Misc.getDistance(ship.getLocation(), otherShip.getLocation());
                    
                    if (distance <= requiredRange) {
                        return true;
                    }
                }
            }
            
            return false;
        }
        
        /**
         * 根据舰船级别获取所需距离
         */
        private float getRequiredRange(HullSize hullSize, ShipAPI ship) {
            float baseRange;
            switch (hullSize) {
                case FIGHTER:
                    baseRange = FIGHTER_RANGE;
                    break;
                case FRIGATE:
                    baseRange = FRIGATE_RANGE;
                    break;
                case DESTROYER:
                    baseRange = DESTROYER_RANGE;
                    break;
                case CRUISER:
                    baseRange = CRUISER_RANGE;
                    break;
                case CAPITAL_SHIP:
                    baseRange = CAPITAL_RANGE;
                    break;
                default:
                    baseRange = FRIGATE_RANGE;
                    break;
            }
            
            // 检查制造商和是否内置，如果不是尼尔加重工且未内置，范围减半
            boolean sMod = ship.getVariant().getSModdedBuiltIns().contains("Moci_GravityReceiver");
            if (!isNelgarManufacturer(ship) && !sMod) {
                baseRange *= 0.4f;
            }
            
            return baseRange;
        }
        
        /**
         * 检查舰船的制造商是否是尼尔加重工
         */
        private boolean isNelgarManufacturer(ShipAPI ship) {
            if (ship == null || ship.getHullSpec() == null) return false;
            
            try {
                String manufacturer = ship.getHullSpec().getManufacturer();
                if (manufacturer != null) {
                    return manufacturer.equals("尼尔加重工") || manufacturer.equals("尼尔加重工(特装)");
                }
            } catch (Exception e) {
                Global.getLogger(this.getClass()).error(
                    "检查舰船制造商时发生错误: " + e.getMessage()
                );
            }
            
            return false;
        }
        
        /**
         * 处理在范围内时的特殊效果
         */
        private void handleInRangeEffects(ShipAPI ship, float amount) {
            // 获取弹药补充计时器
            IntervalUtil ammoTimer = (IntervalUtil) ship.getCustomData().get(AMMO_TIMER_KEY);
            if (ammoTimer == null) {
                ammoTimer = new IntervalUtil(AMMO_RELOAD_INTERVAL, AMMO_RELOAD_INTERVAL);
                ship.setCustomData(AMMO_TIMER_KEY, ammoTimer);
            }
            
            // 推进计时器
            ammoTimer.advance(amount);
            
            // 弹药补充
            if (ammoTimer.intervalElapsed()) {
                reloadAllWeapons(ship);
            }
            
            // CR/峰值时间不下降 - 通过重置CR损失来实现
            preventCRDecay(ship, amount);
            
            // 内置效果：尼尔加的船在范围内获得零幅能加速
            boolean sMod = ship.getVariant().getSModdedBuiltIns().contains("Moci_GravityReceiver");
            if (sMod && isNelgarManufacturer(ship)) {
                ship.getMutableStats().getZeroFluxMinimumFluxLevel().modifyFlat("Moci_GravityReceiver_sMod", 1f);
            }
        }
        
        /**
         * 补充所有武器弹药
         */
        private void reloadAllWeapons(ShipAPI ship) {
            // 计算补充比例
            float reloadPercent = 0.5f; // 基础50%
            boolean sMod = ship.getVariant().getSModdedBuiltIns().contains("Moci_GravityReceiver");
            if (!isNelgarManufacturer(ship) && !sMod) {
                reloadPercent *= 0.4f; // 非尼尔加制造商且未内置再减半，变成20%
            }
            
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (weapon.usesAmmo() && weapon.getAmmo() < weapon.getMaxAmmo()) {
                    int maxAmmo = weapon.getMaxAmmo();
                    int currentAmmo = weapon.getAmmo();
                    int reloadAmount = (int) (maxAmmo * reloadPercent);
                    
                    // 保底至少补充1发弹药
                    if (reloadAmount < 1) {
                        reloadAmount = 1;
                    }
                    
                    int newAmmo = Math.min(maxAmmo, currentAmmo + reloadAmount);
                    weapon.setAmmo(newAmmo);
                }
            }
            
            // 播放补充音效（如果是玩家舰船）
            if (ship == Global.getCombatEngine().getPlayerShip()) {
                Global.getSoundPlayer().playSound("missile_weapon_reloaded", 1f, 1f, 
                    ship.getLocation(), ship.getVelocity());
            }
        }
        
        /**
         * 防止CR下降
         */
        private void preventCRDecay(ShipAPI ship, float amount) {
            // 通过修改时间部署来实现CR不下降
            // 让时间部署不增加，从而避免CR损失
            float timeDeployed = ship.getTimeDeployedForCRReduction();
            ship.setTimeDeployed(timeDeployed - amount);
            
            // 确保时间不会变成负数
            if (ship.getTimeDeployedForCRReduction() < 0) {
                ship.setTimeDeployed(0);
            }
        }
    }
}
