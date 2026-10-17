package data.hullmods;

import java.awt.Color;
import java.util.Random;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.Moci_TextLoader;

/**
 * 原生种兵器
 * 在战斗中自动回复结构和装甲
 */
public class Moci_EinstWeapon extends BaseHullMod {
    private static final String TEXT_ID = "Moci_EinstWeapon";

    // 修复速度参数
    private static final float HULL_REPAIR_RATE = 1f; // 统一的修复率，暂时注释掉Map版本
    private static final float ARMOR_REPAIR_RATE = 1f; // 统一的修复率，暂时注释掉Map版本

    private static final String REPAIR_STATUS_KEY = "moci_einst_repair_status";
    private static final Color PARTICLE_COLOR = new Color(255, 255, 255, 200); // 白色粒子
    private static final Random rand = new Random();
    
    // 特效参数
    private static final float PARTICLE_RADIUS = 50f; // 粒子生成的最大半径
    private static final int MAX_PARTICLES = 15; // 每帧最大粒子数

    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getDynamic().getMod("individual_ship_recovery_mod").modifyFlat(id, 1000.0F);
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (Global.getCombatEngine().isPaused() || ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 计算当前帧的修复量
        float hullRepairAmount = ship.getMaxHitpoints() * (HULL_REPAIR_RATE / 100f) * amount;
        float armorRepairPerCell = ship.getArmorGrid().getMaxArmorInCell() * (ARMOR_REPAIR_RATE / 100f) * amount;
        
        // 初始化修复统计（用于视觉效果）
        float repairLevel = 0f; 
        
        // 修复结构
        boolean hullRepaired = false;
        if (ship.getHitpoints() < ship.getMaxHitpoints()) {
            ship.setHitpoints(Math.min(ship.getHitpoints() + hullRepairAmount, ship.getMaxHitpoints()));
            repairLevel += 1.0f;
            hullRepaired = true;
        }
        
        // 修复装甲
        boolean armorRepaired = false;
        ArmorGridAPI armorGrid = ship.getArmorGrid();
        float[][] grid = armorGrid.getGrid();
        float maxArmorInCell = armorGrid.getMaxArmorInCell();
        
        for (int x = 0; x < grid.length; x++) {
            for (int y = 0; y < grid[0].length; y++) {
                if (grid[x][y] < maxArmorInCell) {
                    float toRepair = Math.min(armorRepairPerCell, maxArmorInCell - grid[x][y]);
                    if (toRepair > 0) {
                        armorGrid.setArmorValue(x, y, grid[x][y] + toRepair);
                        armorRepaired = true;
                        repairLevel += 0.05f; // 每个格子增加小量修复等级
                    }
                }
            }
        }
        
        // 如果装甲或结构被修复，同步修复状态并添加视觉效果
        if (armorRepaired || hullRepaired) {
            ship.syncWithArmorGridState();
            
            // 限制最大修复等级
            repairLevel = Math.min(repairLevel, 4.0f);
            
            // 根据修复等级决定粒子数量
            int particleCount = Math.min((int)(repairLevel * 5), MAX_PARTICLES);
            
            // 在舰船中心区域生成随机粒子效果
            if (particleCount > 0) {
                for (int i = 0; i < particleCount; i++) {
                    // 计算随机位置（以舰船为中心，PARTICLE_RADIUS为半径的圆形区域内）
                    float angle = rand.nextFloat() * 360f;
                    float distance = rand.nextFloat() * PARTICLE_RADIUS;
                    
                    Vector2f offset = Misc.getUnitVectorAtDegreeAngle(angle);
                    offset.scale(distance);
                    
                    // 粒子生成位置
                    Vector2f particleLoc = new Vector2f(ship.getLocation());
                    Vector2f.add(particleLoc, offset, particleLoc);
                    
                    // 粒子参数
                    float particleSize = 2f + repairLevel * (0.5f + rand.nextFloat() * 0.5f);
                    float particleBrightness = 0.5f + repairLevel / 8f + (rand.nextFloat() * 0.2f);
                    float particleDuration = 0.2f + repairLevel / 10f + (rand.nextFloat() * 0.3f);
                    
                    // 随机速度和方向
                    Vector2f particleVel = Misc.getUnitVectorAtDegreeAngle(rand.nextFloat() * 360f);
                    particleVel.scale(rand.nextFloat() * 30f + 10f * repairLevel);
                    
                    // 添加粒子
                    Global.getCombatEngine().addSmoothParticle(
                            particleLoc,
                            particleVel,
                            particleSize,
                            particleBrightness,
                            particleDuration,
                            PARTICLE_COLOR);
                }
            }

            // 如果是玩家舰船，显示修复状态
            if (ship == Global.getCombatEngine().getPlayerShip()) {
                Global.getCombatEngine().maintainStatusForPlayerShip(
                        REPAIR_STATUS_KEY,
                        "graphics/icons/hullsys/repair_drone.png", 
                        Moci_TextLoader.getText(TEXT_ID, "status.title"),
                        Moci_TextLoader.getText(TEXT_ID, "status.repairing"),
                        false);
            }
        }
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        if (index == 0) {
            return String.format("%.1f%%", HULL_REPAIR_RATE);
        } else if (index == 1) {
            return String.format("%.1f%%", ARMOR_REPAIR_RATE);
        }
        return null;
    }
    
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null;
    }
} 
