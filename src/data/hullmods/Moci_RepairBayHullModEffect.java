package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import data.scripts.util.Moci_TextLoader;

/**
 * Moci_RepairBayHullModEffect - 修理舱船体模组效果
 * 
 * 继承BaseHullMod，为舰船提供修理舱功能的船体模组
 * 
 * 核心功能：
 * - 在舰船创建后自动初始化修理舱系统
 * - 为装备该模组的舰船添加Moci_RepairBayScript监听器
 * - 使舰船具备为其他舰船提供修理服务的能力
 * 
 * 安装要求：
 * - 舰船必须拥有非内置的飞行甲板
 * - 舰体级别必须大于等于驱逐舰（不能是护卫舰）
 * - 需要配合带有"Moci_RepairBay"标签的武器使用
 */
public class Moci_RepairBayHullModEffect extends BaseHullMod {
    private static final String TEXT_ID = "Moci_RepairBayHullModEffect";

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        ensureRepairBayScript(ship);
    }

    /**
     * 新的通用整备航母判定：
     * 任意驱逐舰及以上、拥有飞行甲板、且不是机动兵器本体的舰船，
     * 都可以在战斗中被动态初始化为整备航母。
     */
    public static boolean canActAsRepairCarrier(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null || ship.getVariant() == null) {
            return false;
        }

        ShipAPI.HullSize hullSize = ship.getHullSpec().getHullSize();
        if (hullSize == ShipAPI.HullSize.FRIGATE || hullSize == ShipAPI.HullSize.FIGHTER) {
            return false;
        }

        if (ship.getHullSpec().getFighterBays() <= 0 && ship.getNumFighterBays() <= 0) {
            return false;
        }

        return !ship.getVariant().hasHullMod("Moci_MobileSuits");
    }

    /**
     * 确保舰船拥有 RepairBayScript。
     * 这样 LandingSequence 在搜索普通航母时，不再依赖对方预装旧的 LandingBay 船插。
     */
    public static Moci_RepairBayScript ensureRepairBayScript(ShipAPI ship) {
        if (!canActAsRepairCarrier(ship)) {
            return null;
        }

        Moci_RepairBayScript existing = Moci_RepairBayScript.getInstance(ship);
        if (existing != null) {
            return existing;
        }

        if (!ship.hasListenerOfClass(Moci_RepairBayScript.class)) {
            ship.addListener(new Moci_RepairBayScript(ship));
        }

        return Moci_RepairBayScript.getInstance(ship);
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return false;
        }
        
        // 检查舰体级别：必须是驱逐舰及以上
        ShipAPI.HullSize hullSize = ship.getHullSpec().getHullSize();
        if (hullSize == ShipAPI.HullSize.FRIGATE || hullSize == ShipAPI.HullSize.FIGHTER) {
            return false;
        }
        
        // 检查是否有飞行甲板
        if (ship.getHullSpec().getFighterBays() <= 0) {
            return false;
        }
        
        // 关键逻辑：比较飞行甲板数量和内置战机数量
        int totalFighterBays = ship.getHullSpec().getFighterBays();  // 总飞行甲板数
        int builtInWings = ship.getHullSpec().getBuiltInWings().size();  // 内置战机中队数
        
        // 如果飞行甲板数量大于内置战机数量，说明有非内置的飞行甲板
        return totalFighterBays > builtInWings;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_ship_null");
        }
        
        if (ship.getHullSpec() == null) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_hullspec_null");
        }
        
        // 检查舰体级别
        ShipAPI.HullSize hullSize = ship.getHullSpec().getHullSize();
        if (hullSize == ShipAPI.HullSize.FRIGATE || hullSize == ShipAPI.HullSize.FIGHTER) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_size");
        }
        
        // 检查飞行甲板
        if (ship.getHullSpec().getFighterBays() <= 0) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_no_bays");
        }
        
        // 检查非内置甲板
        int totalFighterBays = ship.getHullSpec().getFighterBays();
        int builtInWings = ship.getHullSpec().getBuiltInWings().size();
        if (totalFighterBays <= builtInWings) {
            return Moci_TextLoader.getText(TEXT_ID, "description.unapplicable_builtin_only");
        }
        
        return null;
    }

    @Override
    public boolean showInRefitScreenModPickerFor(ShipAPI ship) {
        return isApplicableToShip(ship);
    }

    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) return Moci_TextLoader.getText(TEXT_ID, "description.param_landing_sequence");
        if (index == 1) return Moci_TextLoader.getText(TEXT_ID, "description.param_ms");
        if (index == 2) return Moci_TextLoader.getText(TEXT_ID, "description.param_maintenance");
        return null;
    }
}
