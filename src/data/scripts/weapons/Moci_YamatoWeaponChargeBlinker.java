package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于主武器充能等级的光照和颜色控制效果
 * 
 * 功能：
 * - 光照强度和大小随充能等级变化
 * - 舰船亮度随充能等级降低（充能1时亮度降至0.5）
 * - 其他武器亮度随充能等级降低（充能1时亮度降至0.4）
 * - 支持"WAVEGUN"前缀的主武器槽位
 * - 不修改武器自身的显示状态
 */
public class Moci_YamatoWeaponChargeBlinker implements EveryFrameWeaponEffectPlugin {

    private boolean once = true;
    private boolean withLight = true;
    private boolean withShipDarkening = true; // 是否启用舰船变暗效果
    private StandardLight light;
    private Color originalColor;
    private List<WeaponAPI> cachedMainWeapons = new ArrayList<WeaponAPI>();
    private List<WeaponAPI> cachedOtherWeapons = new ArrayList<WeaponAPI>(); // 缓存其他武器

    // 主武器槽位前缀配置
    private static final String[] MAIN_WEAPON_PREFIXES = { "WAVEGUN" };
    
    // 光照配置常量
    private static final float LIGHT_BASE_INTENSITY = 0f;      // 基础光照强度（充能0时）
    private static final float LIGHT_MAX_INTENSITY = 5.0f;       // 最大光照强度（充能1时）
    private static final float LIGHT_BASE_SIZE = 0f;           // 基础光照大小（充能0时）
    private static final float LIGHT_MAX_SIZE = 400f;            // 最大光照大小（充能1时）
    
    // 舰船和武器亮度配置常量
    private static final float SHIP_MIN_BRIGHTNESS = 0.4f;       // 舰船最低亮度（充能1时）
    private static final float WEAPON_MIN_BRIGHTNESS = 0.4f;     // 其他武器最低亮度（充能1时）

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础空指针检查
        if (weapon == null)
            return;

        if (once) {
            initialize(weapon);
            once = false;
        }

        // 检查是否在装配界面（通过Global.getCombatEngine()检查）
        CombatEngineAPI combatEngine = Global.getCombatEngine();
        ShipAPI ship = weapon.getShip();
        boolean inCombat = combatEngine != null && ship != null && ship.isAlive();

        if (!inCombat) {
            // 装配界面或舰船死亡时恢复舰船和其他武器亮度，关闭光照
            updateLight(weapon, 0f);
            updateShipBrightness(ship, 0f);
            updateOtherWeaponsBrightness(weapon, 0f);
            return;
        }

        // 获取主武器充能等级
        float chargeLevel = getMainWeaponChargeLevel();

        // 设置光照、舰船亮度和其他武器亮度（不修改武器自身）
        updateLight(weapon, chargeLevel);
        updateShipBrightness(ship, chargeLevel);
        updateOtherWeaponsBrightness(weapon, chargeLevel);
    }

    private void initialize(WeaponAPI weapon) {
        if (weapon == null || weapon.getSpec() == null)
            return;

        originalColor = weapon.getSpec().getGlowColor();
        cacheMainWeapons(weapon.getShip());

        if (withLight && weapon.getShip() != null) {
            try {
                // 初始化时光照位置使用WAVEGUN的位置
                Vector2f initialLocation = getMainWeaponLocation(weapon);
                light = new StandardLight(initialLocation, new Vector2f(0, 0), new Vector2f(0, 0),
                        weapon.getShip());
                light.makePermanent();
                if (originalColor != null)
                    light.setColor(originalColor);
                light.setIntensity(LIGHT_BASE_INTENSITY);
                light.setSize(LIGHT_BASE_SIZE);
                LightShader.addLight(light);
            } catch (Exception e) {
                withLight = false;
                light = null;
            }
        }
    }

    private void cacheMainWeapons(ShipAPI ship) {
        if (ship == null)
            return;

        cachedMainWeapons.clear();
        cachedOtherWeapons.clear();

        for (WeaponAPI weapon : ship.getAllWeapons()) {
            String slotId = weapon.getSlot().getId();
            if (slotId != null && isMainWeaponSlot(slotId)) {
                cachedMainWeapons.add(weapon);
            } else {
                // 缓存所有非主武器
                cachedOtherWeapons.add(weapon);
            }
        }
    }

    private boolean isMainWeaponSlot(String slotId) {
        for (String prefix : MAIN_WEAPON_PREFIXES) {
            if (slotId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private float getMainWeaponChargeLevel() {
        if (cachedMainWeapons.isEmpty())
            return 0f;

        float totalCharge = 0f;
        int validCount = 0;

        for (WeaponAPI weapon : cachedMainWeapons) {
            if (weapon != null && weapon.getShip() != null && weapon.getShip().isAlive()) {
                totalCharge += weapon.getChargeLevel();
                validCount++;
            }
        }

        return validCount > 0 ? totalCharge / validCount : 0f;
    }

    private void updateLight(WeaponAPI weapon, float chargeLevel) {
        if (!withLight || light == null)
            return;

        try {
            // 根据充能等级计算光照强度和大小
            float intensity = LIGHT_BASE_INTENSITY + (LIGHT_MAX_INTENSITY - LIGHT_BASE_INTENSITY) * chargeLevel;
            float size = LIGHT_BASE_SIZE + (LIGHT_MAX_SIZE - LIGHT_BASE_SIZE) * chargeLevel;

            if (originalColor != null)
                light.setColor(originalColor);
            light.setIntensity(intensity);
            light.setSize(size);
            
            // 光照位置跟随WAVEGUN武器
            Vector2f lightLocation = getMainWeaponLocation(weapon);
            light.setLocation(lightLocation);
        } catch (Exception e) {
            // 忽略光照错误
        }
    }

    /**
     * 获取主武器（WAVEGUN）的位置
     * 如果有多个WAVEGUN，返回它们的平均位置
     * 如果没有WAVEGUN，返回当前武器的位置作为后备
     */
    private Vector2f getMainWeaponLocation(WeaponAPI currentWeapon) {
        if (cachedMainWeapons.isEmpty()) {
            return currentWeapon.getLocation();
        }

        float totalX = 0f;
        float totalY = 0f;
        int validCount = 0;

        for (WeaponAPI mainWeapon : cachedMainWeapons) {
            if (mainWeapon != null && mainWeapon.getShip() != null && mainWeapon.getShip().isAlive()) {
                Vector2f loc = mainWeapon.getLocation();
                totalX += loc.x;
                totalY += loc.y;
                validCount++;
            }
        }

        if (validCount > 0) {
            return new Vector2f(totalX / validCount, totalY / validCount);
        } else {
            return currentWeapon.getLocation();
        }
    }

    private void updateShipBrightness(ShipAPI ship, float chargeLevel) {
        if (!withShipDarkening || ship == null || ship.getSpriteAPI() == null)
            return;

        try {
            // 根据充能等级计算舰船亮度
            // 充能等级0：正常亮度（1.0）
            // 充能等级1：最暗亮度（SHIP_MIN_BRIGHTNESS）
            float shipBrightness = 1f - chargeLevel * (1f - SHIP_MIN_BRIGHTNESS);

            // 计算RGB颜色值（保持默认白色255,255,255的比例）
            int colorValue = (int) (255 * shipBrightness);
            Color shipColor = new Color(colorValue, colorValue, colorValue, 255);

            // 直接设置舰船精灵颜色
            ship.getSpriteAPI().setColor(shipColor);

        } catch (Exception e) {
            // 忽略舰船亮度设置错误
        }
    }

    private void updateOtherWeaponsBrightness(WeaponAPI currentWeapon, float chargeLevel) {
        if (!withShipDarkening || cachedOtherWeapons.isEmpty())
            return;

        try {
            // 根据充能等级计算武器亮度
            // 充能等级0：正常亮度（1.0）
            // 充能等级1：最暗亮度（WEAPON_MIN_BRIGHTNESS）
            float weaponBrightness = 1f - chargeLevel * (1f - WEAPON_MIN_BRIGHTNESS);

            // 计算RGB颜色值
            int colorValue = (int) (255 * weaponBrightness);
            Color weaponColor = new Color(colorValue, colorValue, colorValue, 255);

            // 设置所有其他武器的颜色（排除当前武器和HiddenWeapon管理的武器）
            for (WeaponAPI weapon : cachedOtherWeapons) {
                if (weapon != null && weapon != currentWeapon && weapon.getSprite() != null) {
                    // 跳过被HiddenWeapon管理的SOURCE和FOLLOW武器
                    String slotId = weapon.getSlot().getId();
                    if (slotId != null && (slotId.endsWith("_SOURCE") || slotId.endsWith("_FOLLOW"))) {
                        continue;
                    }
                    weapon.getSprite().setColor(weaponColor);
                }
            }

        } catch (Exception e) {
            // 忽略武器亮度设置错误
        }
    }
}