package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;

/**
 * 弹夹武器 - 为最大尺寸的武器提供弹药补给
 * 
 * 功能：
 * 1. 禁止自身开火
 * 2. 根据自身tags检测舰船上尺寸最大的对应类型武器
 * 3. 在目标武器弹药不满时，转移自身弹药给目标武器
 * 4. 支持多个弹夹武器协同工作（轮流转移弹药）
 * 5. 性能优化：缓存目标武器引用
 * 
 * 支持的Tags：
 * - MOCI_AMMO: 标记为弹夹武器（必须，防止循环补给）
 * - MOCI_AMMO_ENERGY: 为能量武器补给弹药
 * - MOCI_AMMO_MISSILE: 为导弹武器补给弹药
 * - MOCI_AMMO_BALLISTIC: 为实弹武器补给弹药
 * 
 * 可以同时使用多个类型tags来支持多种武器类型
 */
public class Moci_MagazineWeapon implements EveryFrameWeaponEffectPlugin {
    
    // Tag常量
    private static final String TAG_AMMO = "MOCI_AMMO";
    private static final String TAG_AMMO_ENERGY = "MOCI_AMMO_ENERGY";
    private static final String TAG_AMMO_MISSILE = "MOCI_AMMO_MISSILE";
    private static final String TAG_AMMO_BALLISTIC = "MOCI_AMMO_BALLISTIC";
    
    // 初始化标记
    private boolean initialized = false;
    
    // 目标导弹武器（缓存）
    private WeaponAPI targetMissileWeapon = null;
    
    // 目标武器的最大弹药数（缓存）
    private int targetMaxAmmo = 0;
    
    // 是否已经找到目标武器
    private boolean targetFound = false;
    
    // 轮换计数器（用于多个弹夹武器轮流工作）
    private int rotationCounter = 0;
    
    // 轮换间隔（每N帧工作一次）
    private static final int ROTATION_INTERVAL = 3;
    
    // 支持的武器类型（根据tags决定）
    private boolean supportsEnergy = false;
    private boolean supportsMissile = false;
    private boolean supportsBallistic = false;
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || weapon == null) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive()) {
            return;
        }
        
        // 初始化
        if (!initialized) {
            initialized = true;
            
            // 读取武器tags，确定支持的武器类型
            if (weapon.getSpec() != null && weapon.getSpec().getTags() != null) {
                for (String tag : weapon.getSpec().getTags()) {
                    if (TAG_AMMO_ENERGY.equals(tag)) {
                        supportsEnergy = true;
                    } else if (TAG_AMMO_MISSILE.equals(tag)) {
                        supportsMissile = true;
                    } else if (TAG_AMMO_BALLISTIC.equals(tag)) {
                        supportsBallistic = true;
                    }
                }
            }
            
            // 如果没有任何tag，默认支持导弹和能量武器（向后兼容）
            if (!supportsEnergy && !supportsMissile && !supportsBallistic) {
                supportsEnergy = true;
                supportsMissile = true;
            }
            
            // 将弹夹武器从武器组中移除（使用安全方法，通过临时全局插件）
            // 注意：新方法通过全局插件绕过第0组限制，可以安全移除任何武器
            data.scripts.util.Moci_WeaponGroupSafetyUtil.safelyRemoveWeaponFromGroups(
                engine, ship, weapon, false
            );
        }
        
        // 禁止自身开火
        weapon.setForceNoFireOneFrame(true);
        
        // 轮换计数器递增
        rotationCounter++;
        
        // 只在特定帧执行弹药转移（避免多个弹夹武器冲突）
        if (rotationCounter % ROTATION_INTERVAL != 0) {
            return;
        }
        
        // 检查弹夹武器是否有弹药
        if (weapon.getAmmo() <= 0) {
            return;
        }
        
        // 查找或更新目标武器
        if (!targetFound) {
            findTargetMissileWeapon(ship, weapon);
        }
        
        // 如果没有找到目标武器，直接返回
        if (targetMissileWeapon == null) {
            return;
        }
        
        // 检查目标武器是否还存在且有效
        if (!isWeaponValid(ship, targetMissileWeapon)) {
            // 目标武器失效，重新查找
            targetFound = false;
            targetMissileWeapon = null;
            return;
        }
        
        // 检查目标武器弹药是否已满
        int currentAmmo = targetMissileWeapon.getAmmo();
        if (currentAmmo >= targetMaxAmmo) {
            // 弹药已满，重新查找其他目标（可能有多个同尺寸武器）
            targetFound = false;
            targetMissileWeapon = null;
            return;
        }
        
        // 转移弹药：扣除弹夹武器1发，给目标武器加1发
        weapon.setAmmo(weapon.getAmmo() - 1);
        targetMissileWeapon.setAmmo(currentAmmo + 1);
    }
    
    /**
     * 查找舰船上尺寸最大的目标武器
     */
    private void findTargetMissileWeapon(ShipAPI ship, WeaponAPI magazineWeapon) {
        WeaponAPI largestMissile = null;
        int largestSize = -1;
        
        for (WeaponAPI w : ship.getAllWeapons()) {
            // 跳过不使用弹药的武器
            if (!w.usesAmmo()) {
                continue;
            }
            
            // 跳过带有MOCI_AMMO标签的武器（防止弹夹武器互相补给）
            if (hasAmmoTag(w)) {
                continue;
            }
            
            // 跳过不支持的武器类型
            boolean isSupported = false;
            if (supportsEnergy && w.getType() == WeaponType.ENERGY) {
                isSupported = true;
            }
            if (supportsMissile && w.getType() == WeaponType.MISSILE) {
                isSupported = true;
            }
            if (supportsBallistic && w.getType() == WeaponType.BALLISTIC) {
                isSupported = true;
            }
            
            if (!isSupported) {
                continue;
            }
            
            // 跳过装饰武器和隐藏武器
            if (w.getSlot().isDecorative() || w.getSlot().isHidden()) {
                continue;
            }
            
            // 获取武器尺寸
            int size = getWeaponSizeValue(w.getSize());
            
            // 找到更大的武器
            if (size > largestSize) {
                largestSize = size;
                largestMissile = w;
            } else if (size == largestSize && largestMissile != null) {
                // 尺寸相同时，选择弹药更少的那个
                if (w.getAmmo() < largestMissile.getAmmo()) {
                    largestMissile = w;
                }
            }
        }
        
        // 缓存找到的目标武器
        if (largestMissile != null) {
            targetMissileWeapon = largestMissile;
            targetMaxAmmo = largestMissile.getMaxAmmo();
            targetFound = true;
        }
    }
    
    /**
     * 检查武器是否有MOCI_AMMO标签
     */
    private boolean hasAmmoTag(WeaponAPI weapon) {
        if (weapon.getSpec() != null && weapon.getSpec().getTags() != null) {
            for (String tag : weapon.getSpec().getTags()) {
                if (TAG_AMMO.equals(tag)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 将武器尺寸转换为数值（用于比较）
     */
    private int getWeaponSizeValue(WeaponSize size) {
        if (size == WeaponSize.SMALL) {
            return 1;
        } else if (size == WeaponSize.MEDIUM) {
            return 2;
        } else if (size == WeaponSize.LARGE) {
            return 3;
        }
        return 0;
    }
    
    /**
     * 检查武器是否有效
     */
    private boolean isWeaponValid(ShipAPI ship, WeaponAPI weapon) {
        if (weapon == null) {
            return false;
        }
        
        // 检查武器是否还在船上
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w == weapon) {
                return true;
            }
        }
        
        return false;
    }
}
