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
 * 基于主武器充能等级的武器指示灯效果
 * 
 * 功能：
 * - 武器透明度 = 主武器充能等级 (0-1)
 * - 光照强度和大小随充能等级变化
 * - 舰船亮度随充能等级降低（充能1时亮度降至0.5）
 * - 支持"MAIN_"前缀的主武器槽位
 */
public class Moci_MainWeaponChargeBlinker implements EveryFrameWeaponEffectPlugin {

    private boolean once = true;
    private boolean withLight = true;
    private boolean withShipDarkening = true; // 是否启用舰船变暗效果
    private StandardLight light;
    private Color originalColor;
    private List<WeaponAPI> cachedMainWeapons = new ArrayList<WeaponAPI>();
    private List<WeaponAPI> cachedOtherWeapons = new ArrayList<WeaponAPI>(); // 缓存其他武器

    private static final String[] MAIN_WEAPON_PREFIXES = { "MAIN_" };

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
            // 装配界面或舰船死亡时设为透明，恢复舰船和其他武器亮度
            setWeaponAlpha(weapon, 0f);
            updateLight(weapon, 0f);
            updateShipBrightness(ship, 0f);
            updateOtherWeaponsBrightness(weapon, 0f);
            return;
        }

        // 获取主武器充能等级
        float chargeLevel = getMainWeaponChargeLevel();

        // 设置武器透明度、光照、舰船亮度和其他武器亮度
        setWeaponAlpha(weapon, chargeLevel);
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
                light = new StandardLight(weapon.getLocation(), new Vector2f(0, 0), new Vector2f(0, 0),
                        weapon.getShip());
                light.makePermanent();
                if (originalColor != null)
                    light.setColor(originalColor);
                light.setIntensity(0.8f);
                light.setSize(200f);
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

    private void setWeaponAlpha(WeaponAPI weapon, float alpha) {
        if (weapon == null || weapon.getSprite() == null)
            return;

        alpha = Math.max(0f, Math.min(1f, alpha));
        Color currentColor = weapon.getSprite().getColor();
        if (currentColor == null)
            return;

        weapon.getSprite().setColor(new Color(
                currentColor.getRed(),
                currentColor.getGreen(),
                currentColor.getBlue(),
                (int) (alpha * 255)));
    }

    private void updateLight(WeaponAPI weapon, float alpha) {
        if (!withLight || light == null)
            return;

        try {
            float intensity = 0.8f + 0.8f * alpha; // 0.8-1.6
            float size = 200f + 200f * alpha; // 200-400

            if (originalColor != null)
                light.setColor(originalColor);
            light.setIntensity(intensity);
            light.setSize(size);
            light.setLocation(weapon.getLocation());
        } catch (Exception e) {
            // 忽略光照错误
        }
    }

    private void updateShipBrightness(ShipAPI ship, float chargeLevel) {
        if (!withShipDarkening || ship == null || ship.getSpriteAPI() == null)
            return;

        try {
            // 根据充能等级计算舰船亮度
            // 充能等级0：正常亮度（1.0）
            // 充能等级1：最暗亮度（0.5）
            float shipBrightness = 1f - chargeLevel * 0.5f; // 1.0-0.5范围

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
            // 充能等级1：最暗亮度（0.5）
            float weaponBrightness = 1f - chargeLevel * 0.6f; // 1.0-0.4范围

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