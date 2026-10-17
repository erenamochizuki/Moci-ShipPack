package data.scripts.world.submarkets;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import data.hullmods.Moci_CombatSquadConfigLoader;
import org.apache.log4j.Logger;

/**
 * 机动战士市场
 * 专门销售机动战士（MS）及其配套装备
 */
public class Moci_MobileSuitMarket extends BaseSubmarketPlugin {
    
    private static final Logger log = Global.getLogger(Moci_MobileSuitMarket.class);
    
    // 调试日志开关
    private static final boolean DEBUG_LOGGING = false;
    
    // 刷新间隔（天）
    private static final float REFRESH_INTERVAL = 30f;
    
    // 关税率
    private static final float TARIFF_RATE = 1.25f;
    
    // 机动战士标签
    private static final String MS_TAG = "moci_ms";
    
    public Moci_MobileSuitMarket() {
        if (DEBUG_LOGGING) {
            log.info("机动战士市场已创建");
        }
    }
    
    @Override
    public void updateCargoPrePlayerInteraction() {
        // 检查是否需要刷新
        if (sinceLastCargoUpdate < REFRESH_INTERVAL) {
            return;
        }
        
        if (DEBUG_LOGGING) {
            log.info("=== 机动战士市场开始刷新 ===");
            log.info("距离上次刷新: " + sinceLastCargoUpdate + " 天");
        }
        
        sinceLastCargoUpdate = 0f;
        
        CargoAPI cargo = getCargo();
        
        // 清空旧货物
        clearCargo(cargo);
        
        // 添加机动战士
        addMobileSuits(cargo);
        
        // 添加武器
        addWeapons(cargo);
        
        // 添加战机
        addFighters(cargo);
        
        // 排序
        cargo.sort();
        
        if (DEBUG_LOGGING) {
            log.info("=== 机动战士市场刷新完成 ===");
        }
    }
    
    /**
     * 清空货物
     */
    private void clearCargo(CargoAPI cargo) {
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            float qty = stack.getSize();
            cargo.removeItems(stack.getType(), stack.getData(), qty);
        }
        cargo.getMothballedShips().clear();
        cargo.removeEmptyStacks();
    }
    
    /**
     * 添加机动战士
     */
    private void addMobileSuits(CargoAPI cargo) {
        if (DEBUG_LOGGING) {
            log.info("--- 开始添加机动战士 ---");
        }
        
        WeightedRandomPicker<String> shipPicker = new WeightedRandomPicker<>(itemGenRandom);
        
        int totalShips = 0;
        int passedShips = 0;
        int failedTag = 0;
        int failedSize = 0;
        int failedManufacturer = 0;
        int failedNoSell = 0;
        
        // 收集所有机动战士船体
        for (ShipHullSpecAPI spec : Global.getSettings().getAllShipHullSpecs()) {
            totalShips++;
            
            if (DEBUG_LOGGING) {
                // 详细检查每个条件
                boolean hasTag = spec.hasTag(MS_TAG);
                boolean isFrigate = spec.getHullSize() == ShipAPI.HullSize.FRIGATE;
                String manufacturer = spec.getManufacturer();
                boolean hasManufacturer = manufacturer != null && !manufacturer.isEmpty();
                boolean isAllowedManufacturer = hasManufacturer && Moci_CombatSquadConfigLoader.isManufacturerAllowed(manufacturer);
                boolean hasNoSellTag = spec.hasTag("no_dealer") || spec.hasTag("no_sell");
                
                // 记录失败原因
                if (!hasTag) {
                    failedTag++;
                } else if (!isFrigate) {
                    failedSize++;
                } else if (!isAllowedManufacturer) {
                    failedManufacturer++;
                    if (hasManufacturer) {
                        log.info("  制造商未通过: " + spec.getHullId() + " - " + manufacturer);
                    }
                } else if (hasNoSellTag) {
                    failedNoSell++;
                    log.info("  禁售标签: " + spec.getHullId() + " - " + manufacturer);
                }
            }
            
            if (isMobileSuit(spec)) {
                float weight = calculateShipWeight(spec);
                shipPicker.add(spec.getHullId(), weight);
                passedShips++;
                if (DEBUG_LOGGING) {
                    log.info("  ✓ 添加机体: " + spec.getHullId() + " (" + spec.getHullName() + ") - " + spec.getManufacturer() + " [权重: " + weight + "]");
                }
            }
        }
        
        if (DEBUG_LOGGING) {
            log.info("船体扫描统计:");
            log.info("  总船体数: " + totalShips);
            log.info("  通过筛选: " + passedShips);
            log.info("  失败原因:");
            log.info("    - 缺少标签: " + failedTag);
            log.info("    - 非护卫舰: " + failedSize);
            log.info("    - 制造商未通过: " + failedManufacturer);
            log.info("    - 禁售标签: " + failedNoSell);
        }
        
        // 随机添加12-20艘机动战士
        int shipCount = 12 + itemGenRandom.nextInt(9);
        
        if (DEBUG_LOGGING) {
            log.info("目标添加数量: " + shipCount);
            log.info("可选机体数量: " + shipPicker.getItems().size());
        }
        
        int addedCount = 0;
        for (int i = 0; i < shipCount && !shipPicker.isEmpty(); i++) {
            String hullId = shipPicker.pick();
            FleetMemberAPI member = Global.getFactory().createFleetMember(
                FleetMemberType.SHIP, 
                hullId + "_Hull"
            );
            
            member.getRepairTracker().setMothballed(true);
            member.getRepairTracker().setCR(0.7f);
            
            cargo.getMothballedShips().addFleetMember(member);
            addedCount++;
        }
        
        if (DEBUG_LOGGING) {
            log.info("实际添加数量: " + addedCount);
            log.info("--- 机动战士添加完成 ---");
        }
    }
    
    /**
     * 判断是否为机动战士
     * 规则：
     * 1. 必须有 moci_ms 标签
     * 2. 必须是护卫舰
     * 3. 制造商必须在 data/config/MociCombatSquad/Moci_CombatSquad_Manufacturers.csv 中
     * 4. 不能有 no_dealer 或 no_sell 标签
     */
    private boolean isMobileSuit(ShipHullSpecAPI spec) {
        // 1. 检查是否有机动战士标签
        if (!spec.hasTag(MS_TAG)) {
            return false;
        }
        
        // 2. 检查是否为护卫舰
        if (spec.getHullSize() != ShipAPI.HullSize.FRIGATE) {
            return false;
        }
        
        // 3. 检查制造商是否在允许列表中
        String manufacturer = spec.getManufacturer();
        if (manufacturer == null || manufacturer.isEmpty()) {
            return false;
        }
        
        if (!Moci_CombatSquadConfigLoader.isManufacturerAllowed(manufacturer)) {
            return false;
        }
        
        // 4. 排除有禁售标签的机体
        if (spec.hasTag("no_dealer") || spec.hasTag("no_sell")) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 调试方法：列出所有允许的制造商
     */
    private void logAllowedManufacturers() {
        log.info("允许的制造商列表:");
        for (String manufacturer : Moci_CombatSquadConfigLoader.getAllowedManufacturers()) {
            log.info("  - " + manufacturer);
        }
    }
    
    /**
     * 计算舰船权重
     */
    private float calculateShipWeight(ShipHullSpecAPI spec) {
        // 基础权重：FP值越低，权重越高
        float weight = 100f / Math.max(1f, spec.getFleetPoints());
        return weight;
    }
    
    /**
     * 添加武器
     */
    private void addWeapons(CargoAPI cargo) {
        WeightedRandomPicker<String> weaponPicker = new WeightedRandomPicker<>(itemGenRandom);
        
        // 收集所有Moci武器
        for (WeaponSpecAPI spec : Global.getSettings().getAllWeaponSpecs()) {
            if (isMociWeapon(spec)) {
                float weight = 10f - spec.getTier();
                weight *= spec.getRarity();
                weaponPicker.add(spec.getWeaponId(), weight);
                // weaponPicker.add(spec.getWeaponId(), weight); // 添加两次增加被选中概率
            }
        }
        
        // 添加20-30种武器
        int weaponTypeCount = 20 + itemGenRandom.nextInt(11);
        for (int i = 0; i < weaponTypeCount && !weaponPicker.isEmpty(); i++) {
            String weaponId = weaponPicker.pickAndRemove();
            WeaponSpecAPI spec = Global.getSettings().getWeaponSpec(weaponId);
            
            int quantity = 2;
            if (spec.getSize() == WeaponAPI.WeaponSize.SMALL) {
                quantity = 8;
            } else if (spec.getSize() == WeaponAPI.WeaponSize.MEDIUM) {
                quantity = 4;
            }
            
            cargo.addWeapons(weaponId, quantity);
        }
    }
    
    /**
     * 判断是否为Moci武器
     */
    private boolean isMociWeapon(WeaponSpecAPI spec) {
        // 检查禁售标签
        if (spec.hasTag("no_drop") || 
            spec.hasTag("no_sell") || 
            spec.hasTag("no_drop_salvage") || 
            spec.hasTag("restricted")) {
            return false;
        }
        
        // 检查是否为系统武器
        if (spec.getAIHints().contains(WeaponAPI.AIHints.SYSTEM)) {
            return false;
        }
        
        // 筛选 Moci_ 开头的武器
        String weaponId = spec.getWeaponId();
        if (weaponId != null && weaponId.startsWith("Moci_")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 添加战机
     */
    private void addFighters(CargoAPI cargo) {
        WeightedRandomPicker<String> fighterPicker = new WeightedRandomPicker<>(itemGenRandom);
        
        // 收集所有Moci战机
        for (FighterWingSpecAPI spec : Global.getSettings().getAllFighterWingSpecs()) {
            if (isMociFighter(spec)) {
                fighterPicker.add(spec.getId());
            }
        }
        
        // 添加8-12种战机
        int fighterCount = 8 + itemGenRandom.nextInt(5);
        for (int i = 0; i < fighterCount && !fighterPicker.isEmpty(); i++) {
            String fighterId = fighterPicker.pick();
            cargo.addItems(CargoAPI.CargoItemType.FIGHTER_CHIP, fighterId, 1);
        }
    }
    
    /**
     * 判断是否为Moci战机
     */
    private boolean isMociFighter(FighterWingSpecAPI spec) {
        // 检查禁售标签
        if (spec.hasTag("no_drop") || 
            spec.hasTag("no_sell") || 
            spec.hasTag("no_drop_salvage") || 
            spec.hasTag("restricted")) {
            return false;
        }
        
        // 筛选 Moci_ 开头的战机
        String fighterId = spec.getId();
        if (fighterId != null && fighterId.startsWith("Moci_")) {
            return true;
        }
        
        return false;
    }
    
    // ========================================
    // 市场属性设置
    // ========================================
    
    @Override
    public boolean isIllegalOnSubmarket(CargoStackAPI stack, TransferAction action) {
        // 允许玩家购买和出售
        return false;
    }
    
    @Override
    public boolean isIllegalOnSubmarket(String commodityId, TransferAction action) {
        // 允许玩家购买和出售
        return false;
    }
    
    @Override
    public boolean isIllegalOnSubmarket(FleetMemberAPI member, TransferAction action) {
        // 允许玩家购买和出售
        return false;
    }
    
    @Override
    public String getIllegalTransferText(FleetMemberAPI member, TransferAction action) {
        return null;
    }
    
    @Override
    public String getIllegalTransferText(CargoStackAPI stack, TransferAction action) {
        return null;
    }
    
    @Override
    public float getTariff() {
        return TARIFF_RATE;
    }
    
    @Override
    public boolean isBlackMarket() {
        return false;
    }
    
    @Override
    public boolean isParticipatesInEconomy() {
        return false;
    }
}
