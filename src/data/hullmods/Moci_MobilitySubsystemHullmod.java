package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import org.magiclib.subsystems.MagicSubsystemsManager;
import data.subsystems.Moci_MobilitySubsystem;

/**
 * 机动子系统船插
 * 
 * 功能：
 * - 为舰船添加机动子系统
 * - 提供临时的机动性增强
 * - 包括加速、转向和最大速度提升
 * - 有冷却时间和幅能消耗
 * 
 * 注意：所有参数配置都在 Moci_MobilitySubsystem 类中，这里只负责安装
 */
public class Moci_MobilitySubsystemHullmod extends BaseHullMod {

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        try {
            // 创建机动子系统实例（参数由子系统根据船体尺寸自动配置）
            Moci_MobilitySubsystem mobilitySubsystem = new Moci_MobilitySubsystem(ship);

            // 注册到MagicLib子系统管理器
            MagicSubsystemsManager.addSubsystemToShip(ship, mobilitySubsystem);

        } catch (Exception e) {
            // 记录错误日志，便于调试
            Global.getLogger(this.getClass()).error("添加机动子系统失败: " + e.getMessage(), e);
        }
    }

}