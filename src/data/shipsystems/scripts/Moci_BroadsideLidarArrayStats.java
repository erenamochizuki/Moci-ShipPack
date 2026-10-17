package data.shipsystems.scripts;

import  java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.lazylib.MathUtils;

/**
 * 侧弦雷达阵列系统
 * 基于原版雷达阵列系统，但专为侧舷火力设计
 */
public class Moci_BroadsideLidarArrayStats extends BaseShipSystemScript {

    // 定义基本参数
    public static String LIDAR_WINDUP = "lidar_windup";
    
    public static Color WEAPON_GLOW = new Color(255,50,50,155);
    
    public static float RANGE_BONUS = 100f; // 武器射程提升
    public static float PASSIVE_RANGE_BONUS = 35f; // 被动射程提升
    public static float ROF_BONUS = 2f; // 射速提升倍率
    public static float RECOIL_BONUS = 75f; // 后坐力减少百分比
    public static float PROJECTILE_SPEED_BONUS = 50f; // 弹速提升百分比
    
    /**
     * 雷达天线数据类
     */
    public static class LidarDishData {
        public float turnDir;
        public float turnRate;
        public float angle;
        public float phase;
        public float count;
        public WeaponAPI w;
    }
    
    // 类成员变量
    protected List<LidarDishData> dishData = new ArrayList<LidarDishData>();
    protected boolean needsUnapply = false;
    protected boolean playedWindup = false;
    protected boolean inited = false;
    private boolean leftSideStronger = false; // 左侧火力是否更强
    private boolean rightSideStronger = false; // 右侧火力是否更强
    
    /**
     * 初始化雷达天线
     */
    public void init(ShipAPI ship) {
        if (inited) return;
        inited = true;
        
        needsUnapply = true;
        
        int turnDir = 1;
        float index = 0f;
        float count = 0f;
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.isDecorative() && w.getSpec().hasTag(Tags.LIDAR)) {
                count++;
            }
        }
        List<WeaponAPI> lidar = new ArrayList<WeaponAPI>();
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.isDecorative() && w.getSpec().hasTag(Tags.LIDAR)) {
                lidar.add(w);
            }
        }
        
        // 按X坐标排序雷达天线
        Collections.sort(lidar, new Comparator<WeaponAPI>() {
            public int compare(WeaponAPI o1, WeaponAPI o2) {
                return (int) Math.signum(o1.getSlot().getLocation().x - o2.getSlot().getLocation().x);
            }
        });
        
        // 初始化雷达天线数据
        for (WeaponAPI w : lidar) {
            if (w.isDecorative() && w.getSpec().hasTag(Tags.LIDAR)) {
                w.setSuspendAutomaticTurning(true);
                LidarDishData data = new LidarDishData();
                data.turnDir = Math.signum(turnDir);
                data.turnRate = 0.1f;
                data.w = w;
                data.angle = 0f;
                data.phase = index / count;
                data.count = count;
                dishData.add(data);
                turnDir = -turnDir;
                index++;
            }
        }
        
        // 分析舰船武器配置，确定哪一侧火力更强
        analyzeShipWeapons(ship);
    }
    
    /**
     * 旋转雷达天线
     */
    public void rotateLidarDishes(boolean active, float effectLevel) {
        float amount = Global.getCombatEngine().getElapsedInLastFrame();
        
        float turnRateMult = 1f;
        if (active) {
            turnRateMult = 20f;
        }
        
        for (LidarDishData data : dishData) {
            float arc = data.w.getArc();
            float useTurnDir = data.turnDir;
            if (active) {
                useTurnDir = Misc.getClosestTurnDirection(data.angle, 0f);
            }
            float delta = useTurnDir * amount * data.turnRate * turnRateMult * arc;
            if (active && effectLevel > 0f && Math.abs(data.angle) < Math.abs(delta * 1.5f)) {
                data.angle = 0f;
            } else {
                data.angle += delta;
                data.phase += 1f * amount;
                if (arc < 360f) {
                    if (data.angle > arc/2f && data.turnDir > 0f) {
                        data.angle = arc/2f;
                        data.turnDir = -1f;
                    }
                    if (data.angle < -arc/2f && data.turnDir < 0f) {
                        data.angle = -arc/2f;
                        data.turnDir = 1f;
                    }
                } else {
                    data.angle = data.angle % 360f;
                }
            }
            
            float facing = data.angle + data.w.getArcFacing() + data.w.getShip().getFacing();
            data.w.setFacing(facing);
            data.w.updateBeamFromPoints();
        }
    }
    
    /**
     * 分析舰船的武器配置，确定哪一侧的火力更强
     */
    private void analyzeShipWeapons(ShipAPI ship) {
        float leftFirepower = 0f;
        float rightFirepower = 0f;
        
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            // 跳过装饰性武器和导弹武器
            if (weapon.isDecorative() || weapon.getType() == WeaponType.MISSILE) continue;
            
            // 获取武器在舰船坐标系中的角度
            float weaponAngle = weapon.getSlot().getAngle();
            float relativeAngle = MathUtils.getShortestRotation(0, weaponAngle);
            
            // 根据武器角度和DPS判断左右侧火力
            float dps = 0f;
            if (weapon.getDerivedStats() != null) {
                dps = weapon.getDerivedStats().getDps();
            }
            float arc = weapon.getArc();
            
            // 计算武器是否主要朝向左侧(-90度附近)或右侧(90度附近)
            if (relativeAngle < -30f && relativeAngle > -150f) {
                leftFirepower += dps * (1f - Math.abs(relativeAngle + 90f) / 90f) * (arc / 360f);
            } else if (relativeAngle > 30f && relativeAngle < 150f) {
                rightFirepower += dps * (1f - Math.abs(relativeAngle - 90f) / 90f) * (arc / 360f);
            }
        }
        
        // 确定哪一侧的火力更强
        leftSideStronger = leftFirepower > rightFirepower;
        rightSideStronger = rightFirepower > leftFirepower;
    }
    
    /**
     * 应用系统效果
     */
    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = (ShipAPI)stats.getEntity();
        if (ship == null || ship.isHulk()) {
            if (needsUnapply) {
                unmodify(id, stats);
                for (WeaponAPI w : ship.getAllWeapons()) {
                    if (!w.isDecorative() && w.getSlot().isHardpoint() && !w.isBeam() &&
                            (w.getType() == WeaponType.BALLISTIC || w.getType() == WeaponType.ENERGY)) {
                        w.setGlowAmount(0, null);
                    }
                }
                needsUnapply = false;
            }
            return;
        }
        
        init(ship);
        
        boolean active = state == State.IN || state == State.ACTIVE || state == State.OUT;
        
        rotateLidarDishes(active, effectLevel);
        
        if (active) {
            modify(id, stats, effectLevel, ship);
            needsUnapply = true;
        } else {
            if (needsUnapply) {
                unmodify(id, stats);
                for (WeaponAPI w : ship.getAllWeapons()) {
                    if (w.getSlot().isSystemSlot()) continue;
                    if (!w.isDecorative() && w.getSlot().isHardpoint() && !w.isBeam() &&
                            (w.getType() == WeaponType.BALLISTIC || w.getType() == WeaponType.ENERGY)) {
                        w.setGlowAmount(0, null);
                    }
                }
                needsUnapply = false;
            }
        }
        
        if (!active) return;
        
        // 系统启动时禁止部分武器开火
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.getSlot().isSystemSlot()) continue;
            if (w.getType() == WeaponType.MISSILE) continue;
            if (state == State.IN) {
                if (!(w.isDecorative() && w.getSpec().hasTag(Tags.LIDAR))) {
                    w.setForceNoFireOneFrame(true);
                }
            } else {
                if (!(!w.isDecorative() && w.getSlot().isHardpoint() && !w.isBeam() &&
                        (w.getType() == WeaponType.BALLISTIC || w.getType() == WeaponType.ENERGY))) {
                    w.setForceNoFireOneFrame(true);
                }
            }
        }
        
        // 激活武器发光效果
        Color glowColor = WEAPON_GLOW;
        
        float lidarRange = 500;
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (!w.isDecorative() && w.getSlot().isHardpoint() && !w.isBeam() &&
                    (w.getType() == WeaponType.BALLISTIC || w.getType() == WeaponType.ENERGY)) {
                lidarRange = Math.max(lidarRange, w.getRange());
                w.setGlowAmount(effectLevel, glowColor);
            }
        }
        lidarRange += 100f;
        stats.getBeamWeaponRangeBonus().modifyFlat("lidararray", lidarRange);
        
        // 雷达天线激活声音效果
        float fireThreshold = 0.25f / 3.25f;
        fireThreshold += 0.02f;
        for (LidarDishData data : dishData) {
            boolean skip = data.phase % 1f > 1f / data.count;
            skip = false;
            if (skip) continue;
            if (data.w.isDecorative() && data.w.getSpec().hasTag(Tags.LIDAR)) {
                if (state == State.IN && Math.abs(data.angle) < 5f && effectLevel >= fireThreshold) {
                    data.w.setForceFireOneFrame(true);
                }
            }
        }
        
        if (((state == State.IN && effectLevel > 0.67f) || state == State.ACTIVE) && !playedWindup) {
            Global.getSoundPlayer().playSound(LIDAR_WINDUP, 1f, 1f, ship.getLocation(), ship.getVelocity());
            playedWindup = true;
        }
    }
    
    /**
     * 修改舰船属性以提供系统效果
     */
    protected void modify(String id, MutableShipStatsAPI stats, float effectLevel, ShipAPI ship) {
        float mult = 1f + ROF_BONUS * effectLevel;
        
        stats.getBallisticWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getEnergyWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getBallisticRoFMult().modifyMult(id, mult);
        stats.getEnergyRoFMult().modifyMult(id, mult);
        stats.getMaxRecoilMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
        stats.getRecoilPerShotMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
        stats.getRecoilDecayMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
        
        stats.getBallisticProjectileSpeedMult().modifyPercent(id, PROJECTILE_SPEED_BONUS);
        stats.getEnergyProjectileSpeedMult().modifyPercent(id, PROJECTILE_SPEED_BONUS);
    }
    
    /**
     * 移除系统效果
     */
    protected void unmodify(String id, MutableShipStatsAPI stats) {
        stats.getBallisticWeaponRangeBonus().modifyPercent(id, PASSIVE_RANGE_BONUS);
        stats.getEnergyWeaponRangeBonus().modifyPercent(id, PASSIVE_RANGE_BONUS);
        
        stats.getBallisticRoFMult().unmodifyMult(id);
        stats.getEnergyRoFMult().unmodifyMult(id);
        stats.getMaxRecoilMult().unmodifyMult(id);
        stats.getRecoilPerShotMult().unmodifyMult(id);
        stats.getRecoilDecayMult().unmodifyMult(id);
        
        stats.getBallisticProjectileSpeedMult().unmodifyPercent(id);
        stats.getEnergyProjectileSpeedMult().unmodifyPercent(id);
        
        playedWindup = false;
    }
    
    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        // 由于runScriptWhileIdle设置为true，这个方法实际上不会被调用
    }
    
    /**
     * 获取状态信息，用于UI显示
     */
    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (state == State.IDLE || state == State.COOLDOWN) {
            if (index == 3) {
                return new StatusData("武器射程 +" + (int) PASSIVE_RANGE_BONUS + "%", false);
            }    
        }
        if (effectLevel <= 0f) return null;
        
        float mult = 1f + ROF_BONUS;
        float bonusPercent = (int) ((mult - 1f) * 100f);
        
        if (index == 3) {
            return new StatusData("武器射程 +" + (int) RANGE_BONUS + "%", false);
        }
        if (index == 2) {
            return new StatusData("射速 +" + (int) bonusPercent + "%", false);
        }
        if (index == 1) {
            return new StatusData("后坐力 -" + (int) RECOIL_BONUS + "%", false);
        }
        if (index == 0 && PROJECTILE_SPEED_BONUS > 0) {
            return new StatusData("弹速 +" + (int) PROJECTILE_SPEED_BONUS + "%", false);
        }
        return null;
    }
    
    /**
     * 获取显示名称覆盖
     */
    @Override
    public String getDisplayNameOverride(State state, float effectLevel) {
        if (state == State.IDLE || state == State.COOLDOWN) {
            return "侧弦雷达阵列 - 被动";
        }
        return null;
    }
} 