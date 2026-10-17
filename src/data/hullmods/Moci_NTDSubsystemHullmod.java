package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import org.magiclib.subsystems.MagicSubsystemsManager;
import data.subsystems.Moci_NTDSubsystem;

/**
 * NTD模式子系统船插
 * 
 * 功能：
 * - 为舰船添加NTD（觉醒）模式子系统
 * - 提供觉醒变形动画控制
 * - 提供临时的性能增强
 * - 武器发光效果
 * - 有充能限制和幅能消耗
 * 
 * 注意：所有参数配置都在 Moci_NTDSubsystem 类中，这里只负责安装
 */
public class Moci_NTDSubsystemHullmod extends BaseHullMod {
    private static final boolean START_WITH_NO_CHARGES = false;

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        try {
            // 创建NTD子系统实例（参数由子系统根据船体尺寸自动配置）
            Moci_NTDSubsystem ntdSubsystem = new Moci_NTDSubsystem(ship);
            ntdSubsystem.setStartWithNoCharges(START_WITH_NO_CHARGES);

            // 注册到MagicLib子系统管理器
            MagicSubsystemsManager.addSubsystemToShip(ship, ntdSubsystem);

        } catch (Exception e) {
            // 记录错误日志，便于调试
            Global.getLogger(this.getClass()).error("添加NTD子系统失败: " + e.getMessage(), e);
        }
    }
}
