package data.shipsystems.scripts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import org.lwjgl.util.vector.Vector2f;

public class Moci_PointDefenseSystem extends BaseShipSystemScript {
    
    // 系统音效标识
    public static final String LIDAR_WINDUP = "lidar_windup";
    // 武器特效颜色
    public static final Color WEAPON_GLOW = new Color(255, 200, 0, 200);
    // 系统参数配置
    public static final float RANGE_BONUS = 75f;           // 激活时射程加成百分比
    public static final float PASSIVE_RANGE_BONUS = 25f;    // 被动模式加成
    public static final float ROF_BONUS = 1f;               // 射速加成倍数（1+2=3倍）
    public static final float RECOIL_BONUS = 50f;           // 后坐力减少百分比
    public static final float PROJECTILE_SPEED_BONUS = 50f; // 弹速加成百分比
    private static final float ACTIVE_TURN_RATE_BONUS = 50f;    // 武器转速提升百分比
    private static final float FLUX_REDUCTION = 35f;             // 幅能消耗降低比例
    private static final float ARMOR_DAMAGE_REDUCTION = 0f;    // 装甲伤害减免比例
    private static final float REPAIR_RATE_MULT = 1f;           // 维修速度倍率
    
    private boolean needsUnapply = false;    // 标记是否需要取消增益
    private boolean playedWindup = false;    // 标记是否已播放启动音效
    private boolean isCleanedUp = false;     // 标记是否已清理

    private final List<LidarDishData> lidarData = new ArrayList<>();
    
    public static class LidarDishData {
        public WeaponAPI lidarWeapon;       // 装饰武器
        public WeaponAPI nearestBallistic;  // 最近的实弹武器
        public float lastAngle;            // 上次记录的角度
    }
    /**
     * 主逻辑：应用/取消系统效果
     */
    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = (ShipAPI) stats.getEntity();
        
        // 舰船无效或已死亡时清理状态
        if (ship == null || ship.isHulk() || !ship.isAlive()) {
            cleanupSystem(ship, id, stats);
            return;
        }
        
        // 初始化装饰武器
        initLidarWeapons(ship);
        isCleanedUp = false; // 重置清理标记
        
        // 判断系统是否激活
        boolean active = state == State.IN || state == State.ACTIVE || state == State.OUT;
        
        // 更新装饰武器状态
        if (active) {
            updateLidarWeapons(effectLevel);
        }
        
        // 状态切换处理
        if (active) {
            modify(id, stats, effectLevel); // 应用增益
            needsUnapply = true;
        } else {
            if (needsUnapply) {
                unapply(stats, id); // 取消增益
                resetWeaponGlow(ship); // 重置武器发光
                needsUnapply = false;
            }
        }
        
        if (!active) return;
        
        // 激活时武器开火控制
        for (var weapon : ship.getAllWeapons()) {
            weapon.setForceNoFireOneFrame(!isPointDefenseWeapon(weapon));
        }
        
        // 设置武器发光效果
        for (var weapon : ship.getAllWeapons()) {
            if (!weapon.isDecorative() && isPointDefenseWeapon(weapon)) {
                weapon.setGlowAmount(effectLevel, WEAPON_GLOW);
            }
        }
        
        // 播放系统启动音效
        if (((state == State.IN && effectLevel > 0) || state == State.ACTIVE) && !playedWindup) {
            Global.getSoundPlayer().playSound(LIDAR_WINDUP, 1f, 1f, ship.getLocation(), ship.getVelocity());
            playedWindup = true;
        }
    }
    
    /**
     * 清理系统状态（舰船死亡时调用）
     */
    private void cleanupSystem(ShipAPI ship, String id, MutableShipStatsAPI stats) {
        if (isCleanedUp) return; // 避免重复清理
        
        if (needsUnapply) {
            unapply(stats, id);
            needsUnapply = false;
        }
        
        // 重置所有武器的发光效果
        if (ship != null) {
            resetWeaponGlow(ship);
        }
        
        // 清空装饰武器数据
        lidarData.clear();
        
        // 重置标记
        playedWindup = false;
        isCleanedUp = true;
    }
    
    /**
     * 重置武器发光效果
     */
    private void resetWeaponGlow(ShipAPI ship) {
        for (var weapon : ship.getAllWeapons()) {
            if (weapon.getSlot().isSystemSlot()) continue;
            if (!weapon.isDecorative() && weapon.getSlot().isHardpoint() && !weapon.isBeam() &&
                    (weapon.getType() == WeaponType.BALLISTIC || weapon.getType() == WeaponType.ENERGY)) {
                weapon.setGlowAmount(0, null);
            }
        }
    }
    /**
     * 点防御武器识别
     */
    private boolean isPointDefenseWeapon(WeaponAPI weapon) {
        if (weapon == null) return false;

        var slot = weapon.getSlot();
        if (weapon.isDecorative() ||
                weapon.hasAIHint(WeaponAPI.AIHints.SYSTEM) ||
                slot == null ||
                slot.isDecorative() ||
                slot.isSystemSlot()) {
            return false;
        }

        return weapon.hasAIHint(WeaponAPI.AIHints.PD) ||
                weapon.hasAIHint(WeaponAPI.AIHints.PD_ALSO) ||
                weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY);
    }

    /**
     * 初始化装饰武器数据
     */
    private void initLidarWeapons(ShipAPI ship) {
        if (!lidarData.isEmpty()) return;

        // 获取所有装饰武器
        var lidarWeapons = new ArrayList<WeaponAPI>();
        for (var weapon : ship.getAllWeapons()) {
            if (weapon.isDecorative() && weapon.getSpec().hasTag(Tags.LIDAR)) {
                weapon.setSuspendAutomaticTurning(true);
                lidarWeapons.add(weapon);
            }
        }

        // 获取所有大型实弹武器
        var ballisticWeapons = new ArrayList<WeaponAPI>();
        for (var weapon : ship.getAllWeapons()) {
            if (weapon.getType() == WeaponType.BALLISTIC && weapon.getSlot().isTurret()) {
                ballisticWeapons.add(weapon);
            }
        }

        // 为每个装饰武器找到最近的实弹武器
        for (var lidar : lidarWeapons) {
            var data = new LidarDishData();
            data.lidarWeapon = lidar;
            data.nearestBallistic = findNearestBallistic(lidar, ballisticWeapons);
            lidarData.add(data);
        }
    }

    /**
     * 查找最近的实弹武器
     */
    private WeaponAPI findNearestBallistic(WeaponAPI lidar, List<WeaponAPI> ballistics) {
        if (ballistics.isEmpty()) return null;

        WeaponAPI nearest = null;
        float minDistance = Float.MAX_VALUE;

        for (var ballistic : ballistics) {
            float distance = Vector2f.sub(lidar.getLocation(), ballistic.getLocation(), null).length();
            if (distance < minDistance) {
                minDistance = distance;
                nearest = ballistic;
            }
        }
        return nearest;
    }

    /**
     * 更新装饰武器状态
     */
    private void updateLidarWeapons(float effectLevel) {
        if (effectLevel <= 0) return;

        for (var data : lidarData) {
            if (data.nearestBallistic != null) {
                // 强制装饰武器开火
                data.lidarWeapon.setForceFireOneFrame(true);

                // 同步实弹武器角度
                float targetAngle = data.nearestBallistic.getCurrAngle();
                data.lidarWeapon.setCurrAngle(targetAngle);
                data.lastAngle = targetAngle;
            }
        }
    }

    /**
     * 获取最高射程
     */
    private float getMaxWeaponRange(ShipAPI ship) {
        float maxRange = 0f;
        for (var weapon : ship.getAllWeapons()) {
            if (isPointDefenseWeapon(weapon) &&
                    weapon.getType() == WeaponType.BALLISTIC) {
                maxRange = Math.max(maxRange, weapon.getRange());
            }
        }
        return maxRange;
    }

    /**
     * 应用属性增益
     */
    private void modify(String id, MutableShipStatsAPI stats, float effectLevel) {
        var ship = (ShipAPI) stats.getEntity();
        if (ship == null) return;
        
        // 获取最高射程
        float maxRange = getMaxWeaponRange(ship);
        // 同步装饰武器射程（通过PD标签）
        stats.getBeamPDWeaponRangeBonus().modifyFlat("Moci_PointDefense_Range", maxRange - 1000f);

        float mult = 1f + ROF_BONUS * effectLevel;
        
        // 射程加成
        stats.getBallisticWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getEnergyWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        // 射速加成
        stats.getBallisticRoFMult().modifyMult(id, mult);
        stats.getEnergyRoFMult().modifyMult(id, mult);
        // 转速加成
        stats.getWeaponTurnRateBonus().modifyPercent(id, ACTIVE_TURN_RATE_BONUS);
        // 后坐力减免
        stats.getMaxRecoilMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
        stats.getRecoilPerShotMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
        stats.getRecoilDecayMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
        // 弹速加成
        stats.getBallisticProjectileSpeedMult().modifyPercent(id, PROJECTILE_SPEED_BONUS);
        stats.getEnergyProjectileSpeedMult().modifyPercent(id, PROJECTILE_SPEED_BONUS);
        // 幅能效率优化
        stats.getEnergyWeaponFluxCostMod().modifyPercent(id, -FLUX_REDUCTION);
        stats.getBallisticWeaponFluxCostMod().modifyPercent(id, -FLUX_REDUCTION);
        // 维修系统超频
        stats.getCombatEngineRepairTimeMult().modifyMult(id, 1f / REPAIR_RATE_MULT);
        stats.getCombatWeaponRepairTimeMult().modifyMult(id, 1f / REPAIR_RATE_MULT);
        // 装甲强化
        stats.getArmorDamageTakenMult().modifyMult(id, 1f - ARMOR_DAMAGE_REDUCTION);
    }
    
    /**
     * 取消属性增益
     */
    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        // 恢复为被动射程加成
        stats.getBallisticWeaponRangeBonus().modifyPercent(id, PASSIVE_RANGE_BONUS);
        stats.getEnergyWeaponRangeBonus().modifyPercent(id, PASSIVE_RANGE_BONUS);
        stats.getWeaponTurnRateBonus().modifyPercent(id, PASSIVE_RANGE_BONUS);
        // 移除射速加成
        stats.getBallisticRoFMult().unmodifyMult(id);
        stats.getEnergyRoFMult().unmodifyMult(id);
        // 恢复后坐力参数
        stats.getMaxRecoilMult().unmodifyMult(id);
        stats.getRecoilPerShotMult().unmodifyMult(id);
        stats.getRecoilDecayMult().unmodifyMult(id);
        // 移除弹速加成
        stats.getBallisticProjectileSpeedMult().unmodifyPercent(id);
        stats.getEnergyProjectileSpeedMult().unmodifyPercent(id);
        // 移除幅能效率优化
        stats.getEnergyWeaponFluxCostMod().unmodify(id);
        stats.getBallisticWeaponFluxCostMod().unmodify(id);
        // 移除维修系统超频
        stats.getCombatEngineRepairTimeMult().unmodify(id);
        stats.getCombatWeaponRepairTimeMult().unmodify(id);
        // 移除装甲强化
        stats.getArmorDamageTakenMult().unmodify(id);
        
        // 同步装饰武器射程（通过PD标签）
        stats.getBeamPDWeaponRangeBonus().unmodify("Moci_PointDefense_Range");

        // 恢复装饰武器朝向
        for (var data : lidarData) {
            if (data.lidarWeapon != null && data.lidarWeapon.getShip() != null) {
                float facing = data.lidarWeapon.getArcFacing() + data.lidarWeapon.getShip().getFacing();
                data.lidarWeapon.setFacing(facing);
            }
        }
        
        // 重置音效标记
        playedWindup = false;
    }

    /**
     * 状态栏显示数据
     */
    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (state == State.IDLE || state == State.COOLDOWN) {
            if (index == 3) {
                return new StatusData("点防御射程/转速 +" + (int) PASSIVE_RANGE_BONUS + "%", false);
            }
        }
        if (effectLevel <= 0f) return null;
        // 根据索引返回不同的状态描述

        float bonusPercent = (int) ((ROF_BONUS) * 100f);

        if (index == 3) {
            return new StatusData("点防御射程 +" + (int) RANGE_BONUS + "%", false);
        }
        if (index == 2) {
            return new StatusData("点防御射速 +" + (int) bonusPercent + "%", false);
        }
        if (index == 1) {
            return new StatusData("武器幅能消耗 -" + (int) FLUX_REDUCTION + "%", false);
        }
        if (index == 0) {
            return new StatusData("后坐力减轻/快速修复", false);
        }
        return null;
    }
    
    /**
     * 系统名称显示
     */
    @Override
    public String getDisplayNameOverride(State state, float effectLevel) {
        if (state == State.IDLE || state == State.COOLDOWN) {
            return "点防御阵列(被动)"; // 被动模式显示名称
        }
        return null; // 激活时使用默认名称
    }
}
