package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.input.Keyboard;

import java.awt.Color;

/**
 * 大和号主炮弹药切换系统
 * 支持在普通弹和高爆弹之间切换
 * 集成了不同弹药类型的枪口特效
 */
public class Moci_YamatoMainGunAmmoSwitch implements EveryFrameWeaponEffectPlugin, OnFireEffectPlugin {
    private boolean useHE = false;
    private static final String MOD_ID = "moci_shippack";
    private static final String AMMO_SWITCH_KEY_SETTING = "Moci_YamatoMainGunAmmoSwitchKey";
    private static final int DEFAULT_AMMO_SWITCH_KEY = Keyboard.KEY_B;
    private static final String STATUS_KEY = "MOCI_YAMATO_MAINGUN_AMMO_SWITCH";
    private static final String PLAYER_SHARED_AMMO_KEY = STATUS_KEY + "_PLAYER_USE_HE";
    private static final String PLAYER_SHARED_KEY_PRESSED_KEY = STATUS_KEY + "_PLAYER_KEY_PRESSED";
    private boolean keyPressed = false;
    private int currentBarrel = 0; // 当前炮管索引
    private boolean hasFiredThisFrame = false; // 本帧是否已开火

    // ========== 特效配置区域 ==========
    // 在这里可以自由调整所有特效参数

    // === 普通弹特效配置（脉冲镭射弹）===

    // --- 普通弹闪光配置 ---
    private static final String NORMAL_FLASH_TYPE = "BRIGHT"; // 粒子类型：SMOKE(烟雾)/SMOOTH(柔和)/BRIGHT(明亮)
    private static final Color NORMAL_FLASH_COLOR = new Color(150, 200, 255);
    private static final float NORMAL_FLASH_SIZE_MIN = 100f; // 粒子最小大小（像素）
    private static final float NORMAL_FLASH_SIZE_MAX = 100f; // 粒子最大大小（像素），与MIN相同=固定大小
    private static final float NORMAL_FLASH_VELOCITY_MIN = 0f; // 粒子最小速度（像素/秒），0=静止
    private static final float NORMAL_FLASH_VELOCITY_MAX = 0f; // 粒子最大速度（像素/秒）
    private static final float NORMAL_FLASH_DURATION_MIN = 0.1f; // 粒子最短持续时间（秒）
    private static final float NORMAL_FLASH_DURATION_MAX = 0.1f; // 粒子最长持续时间（秒）
    private static final float NORMAL_FLASH_OFFSET_MIN = 10f; // 粒子最小偏移距离（沿速度方向，像素）
    private static final float NORMAL_FLASH_OFFSET_MAX = 10f; // 粒子最大偏移距离（像素）
    private static final float NORMAL_FLASH_ARC = 0f; // 粒子扩散角度（度），0=直线，180=半圆，360=全圆
    private static final int NORMAL_FLASH_COUNT = 1; // 粒子数量（个）

    // --- 普通弹烟雾配置 ---
    private static final String NORMAL_SMOKE_TYPE = "NEBULA"; // 粒子类型：星云烟雾
    private static final Color NORMAL_SMOKE_COLOR = new Color(164, 225, 236, 100);
    private static final float NORMAL_SMOKE_SIZE_MIN = 10f; // 粒子最小大小
    private static final float NORMAL_SMOKE_SIZE_MAX = 10f; // 粒子最大大小，随机在15-30之间
    private static final float NORMAL_SMOKE_VELOCITY_MIN = 240f; // 粒子最小速度
    private static final float NORMAL_SMOKE_VELOCITY_MAX = 260f; // 粒子最大速度
    private static final float NORMAL_SMOKE_DURATION_MIN = 0.5f; // 粒子最短持续时间
    private static final float NORMAL_SMOKE_DURATION_MAX = 1f; // 粒子最长持续时间
    private static final float NORMAL_SMOKE_OFFSET_MIN = 0f; // 粒子最小偏移，从炮口开始
    private static final float NORMAL_SMOKE_OFFSET_MAX = 0f; // 粒子最大偏移（NEBULA类型不需要偏移）
    private static final float NORMAL_SMOKE_ARC = 0f; // 粒子扩散角度（NEBULA类型沿武器方向）
    private static final int NORMAL_SMOKE_COUNT = 12; // 粒子数量
    private static final float NORMAL_SMOKE_SIZE_MULT = 1.5f; // 星云烟雾大小倍率
    private static final float NORMAL_SMOKE_FADE_IN = 0f; // 星云烟雾淡入时间
    private static final float NORMAL_SMOKE_FADE_OUT = 0.3f; // 星云烟雾淡出时间

    // --- 普通弹能量粒子配置（类似火花的能量效果）---
    private static final String NORMAL_SPARKS_TYPE = "BRIGHT"; // 粒子类型：明亮能量粒子
    private static final Color NORMAL_SPARKS_COLOR = new Color(255, 185, 215, 215);
    private static final float NORMAL_SPARKS_SIZE_MIN = 20f; // 粒子最小大小，小颗粒
    private static final float NORMAL_SPARKS_SIZE_MAX = 30f; // 粒子最大大小
    private static final float NORMAL_SPARKS_VELOCITY_MIN = 50f; // 粒子最小速度，快速飞散
    private static final float NORMAL_SPARKS_VELOCITY_MAX = 200f; // 粒子最大速度
    private static final float NORMAL_SPARKS_DURATION_MIN = 0.2f; // 粒子最短持续时间
    private static final float NORMAL_SPARKS_DURATION_MAX = 0.5f; // 粒子最长持续时间
    private static final float NORMAL_SPARKS_OFFSET_MIN = 0f; // 粒子最小偏移，稍微远离炮口
    private static final float NORMAL_SPARKS_OFFSET_MAX = 100f; // 粒子最大偏移
    private static final float NORMAL_SPARKS_ARC = 30f; // 粒子扩散角度，中等扩散
    private static final int NORMAL_SPARKS_COUNT = 50; // 粒子数量，比高爆弹少

    // === 高爆弹特效配置（三式融合弹）===

    // --- 高爆弹闪光配置 ---
    private static final String HE_FLASH_TYPE = "BRIGHT"; // 粒子类型：明亮发光
    private static final Color HE_FLASH_COLOR = new Color(255, 150, 50);
    private static final float HE_FLASH_SIZE_MIN = 120f; // 粒子最小大小，比普通弹大20%
    private static final float HE_FLASH_SIZE_MAX = 120f; // 粒子最大大小
    private static final float HE_FLASH_VELOCITY_MIN = 0f; // 粒子最小速度，静止
    private static final float HE_FLASH_VELOCITY_MAX = 0f; // 粒子最大速度
    private static final float HE_FLASH_DURATION_MIN = 0.15f; // 粒子最短持续时间，比普通弹长50%
    private static final float HE_FLASH_DURATION_MAX = 0.15f; // 粒子最长持续时间
    private static final float HE_FLASH_OFFSET_MIN = 10f; // 粒子最小偏移
    private static final float HE_FLASH_OFFSET_MAX = 10f; // 粒子最大偏移
    private static final float HE_FLASH_ARC = 0f; // 粒子扩散角度，无扩散
    private static final int HE_FLASH_COUNT = 1; // 粒子数量

    // --- 高爆弹烟雾配置 ---
    private static final String HE_SMOKE_TYPE = "NEBULA"; // 粒子类型：星云烟雾（更真实的烟雾效果）
    private static final Color HE_SMOKE_COLOR = new Color(250, 242, 237, 180);
    private static final float HE_SMOKE_SIZE_MIN = 10f; // 粒子最小大小，比普通弹大67%
    private static final float HE_SMOKE_SIZE_MAX = 25f; // 粒子最大大小
    private static final float HE_SMOKE_VELOCITY_MIN = 100f; // 粒子最小速度
    private static final float HE_SMOKE_VELOCITY_MAX = 250f; // 粒子最大速度
    private static final float HE_SMOKE_DURATION_MIN = 0.5f; // 粒子最短持续时间
    private static final float HE_SMOKE_DURATION_MAX = 0.75f; // 粒子最长持续时间
    private static final float HE_SMOKE_OFFSET_MIN = 0f; // 粒子最小偏移
    private static final float HE_SMOKE_OFFSET_MAX = 0f; // 粒子最大偏移（NEBULA类型不需要偏移）
    private static final float HE_SMOKE_ARC = 30f; // 粒子扩散角度（NEBULA类型沿武器方向）
    private static final int HE_SMOKE_COUNT = 40; // 粒子数量，比普通弹多50%
    private static final float HE_SMOKE_SIZE_MULT = 2f; // 星云烟雾大小倍率，更大更浓
    private static final float HE_SMOKE_FADE_IN = 0f; // 星云烟雾淡入时间
    private static final float HE_SMOKE_FADE_OUT = 0.4f; // 星云烟雾淡出时间

    // --- 高爆弹火花配置（独有效果）---
    private static final String HE_SPARKS_TYPE = "BRIGHT"; // 粒子类型：明亮火花
    private static final Color HE_SPARKS_COLOR = new Color(255, 200, 100); // 颜色：金黄色，火花效果
    private static final float HE_SPARKS_SIZE_MIN = 5f; // 粒子最小大小，小颗粒
    private static final float HE_SPARKS_SIZE_MAX = 15f; // 粒子最大大小
    private static final float HE_SPARKS_VELOCITY_MIN = 50f; // 粒子最小速度，快速飞溅
    private static final float HE_SPARKS_VELOCITY_MAX = 150f; // 粒子最大速度
    private static final float HE_SPARKS_DURATION_MIN = 0.3f; // 粒子最短持续时间，短暂闪烁
    private static final float HE_SPARKS_DURATION_MAX = 0.6f; // 粒子最长持续时间
    private static final float HE_SPARKS_OFFSET_MIN = 10f; // 粒子最小偏移，从炮口稍远处开始
    private static final float HE_SPARKS_OFFSET_MAX = 70f; // 粒子最大偏移
    private static final float HE_SPARKS_ARC = 30f; // 粒子扩散角度，中等扩散
    private static final int HE_SPARKS_COUNT = 35; // 粒子数量
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (!ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 重置开火标记
        hasFiredThisFrame = false;

        boolean isPlayerShip = engine.getPlayerShip() == ship;
        int ammoSwitchKey = getAmmoSwitchKey();

        // 玩家控制：按配置键切换弹药
        if (ship.getShipAI() == null) {
            if (isPlayerShip) {
                Object sharedAmmo = ship.getCustomData().get(PLAYER_SHARED_AMMO_KEY);
                if (sharedAmmo instanceof Boolean) {
                    useHE = (Boolean) sharedAmmo;
                } else {
                    ship.setCustomData(PLAYER_SHARED_AMMO_KEY, useHE);
                }

                Object sharedKeyPressed = ship.getCustomData().get(PLAYER_SHARED_KEY_PRESSED_KEY);
                keyPressed = sharedKeyPressed instanceof Boolean && (Boolean) sharedKeyPressed;

                if (Keyboard.isKeyDown(ammoSwitchKey)) {
                    if (!keyPressed) {
                        keyPressed = true;
                        useHE = !useHE;
                        ship.setCustomData(PLAYER_SHARED_AMMO_KEY, useHE);
                        ship.setCustomData(PLAYER_SHARED_KEY_PRESSED_KEY, true);
                    }
                } else {
                    keyPressed = false;
                    ship.setCustomData(PLAYER_SHARED_KEY_PRESSED_KEY, false);
                }
            }
        } else {
            // AI控制：根据目标状态自动选择弹药
            ShipAPI target = null;
            MissileAPI targetMissile = null;

            // 获取武器组的自动开火目标
            WeaponGroupAPI weaponGroup = ship.getWeaponGroupFor(weapon);
            if (weaponGroup != null) {
                AutofireAIPlugin autofireAI = weaponGroup.getAutofirePlugin(weapon);
                if (autofireAI != null) {
                    target = autofireAI.getTargetShip();
                    targetMissile = autofireAI.getTargetMissile();
                }
            }

            // 如果没有自动开火目标，使用舰船的主目标
            if (target == null) {
                target = ship.getShipTarget();
            }

            // 检查目标是否在武器射界内
            if (target != null) {
                float weaponAngle = Misc.normalizeAngle(weapon.getSlot().getAngle() + ship.getFacing());
                if (!Misc.isInArc(weaponAngle, weapon.getSlot().getArc(), weapon.getLocation(), target.getLocation())) {
                    target = null;
                }
            }

            // 根据目标类型和护盾状态选择弹药
            if (targetMissile != null) {
                // 如果目标是导弹，使用高爆弹
                useHE = true;
            } else if (target != null) {
                // 如果目标是战机或无人机，使用高爆弹
                if (target.isFighter() || target.isDrone()) {
                    useHE = true;
                } else if (target.getShield() == null || target.getShield().isOff()) {
                    // 目标没有护盾或护盾关闭 -> 使用高爆弹
                    useHE = true;
                } else {
                    // 检查武器是否在护盾覆盖范围外
                    useHE = !Misc.isInArc(target.getShield().getFacing(),
                            target.getShield().getActiveArc(),
                            target.getLocation(),
                            weapon.getLocation());
                }
            }
        }

        // 显示当前弹药类型（仅玩家舰船）
        if (isPlayerShip) {
            String ammoType = useHE ? "三式融合弹" : "脉冲镭射弹";
            engine.maintainStatusForPlayerShip(STATUS_KEY,
                    "graphics/icons/hullsys/ammo_feeder.png",
                    weapon.getDisplayName() + ": " + ammoType,
                    "按" + getKeyName(ammoSwitchKey) + "键切换弹药",
                    false);
        }
    }

    private static int getAmmoSwitchKey() {
        try {
            if (Global.getSettings().getModManager().isModEnabled("lunalib")) {
                return lunalib.lunaSettings.LunaSettings.getInt(MOD_ID, AMMO_SWITCH_KEY_SETTING);
            }
        } catch (Exception ignored) {
            // LunaLib不可用或配置缺失时使用默认B键。
        }
        return DEFAULT_AMMO_SWITCH_KEY;
    }

    private static String getKeyName(int keyCode) {
        String keyName = Keyboard.getKeyName(keyCode);
        return keyName == null || keyName.length() == 0 ? Keyboard.getKeyName(DEFAULT_AMMO_SWITCH_KEY) : keyName;
    }

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 标记本帧已开火
        hasFiredThisFrame = true;

        // ========== 生成枪口特效 ==========
        if (useHE) {
            // 高爆弹特效：橙红色闪光 + 浓密烟雾 + 火花

            // 播放高爆弹音效
            Global.getSoundPlayer().playSound("Moci_Yamato_maingun_02", 1f, 1f, 
                    weapon.getLocation(), weapon.getShip().getVelocity());

            // 闪光
            Moci_MuzzleFlashEffect.EffectConfig heFlash = new Moci_MuzzleFlashEffect.EffectConfig()
                    .type(HE_FLASH_TYPE)
                    .color(HE_FLASH_COLOR)
                    .size(HE_FLASH_SIZE_MIN, HE_FLASH_SIZE_MAX)
                    .velocity(HE_FLASH_VELOCITY_MIN, HE_FLASH_VELOCITY_MAX)
                    .duration(HE_FLASH_DURATION_MIN, HE_FLASH_DURATION_MAX)
                    .offset(HE_FLASH_OFFSET_MIN, HE_FLASH_OFFSET_MAX)
                    .arc(HE_FLASH_ARC)
                    .count(HE_FLASH_COUNT);
            Moci_MuzzleFlashEffect.spawnMuzzleFlashWithBarrel(engine, weapon, heFlash, currentBarrel);

            // 烟雾
            Moci_MuzzleFlashEffect.EffectConfig heSmoke = new Moci_MuzzleFlashEffect.EffectConfig()
                    .type(HE_SMOKE_TYPE)
                    .color(HE_SMOKE_COLOR)
                    .size(HE_SMOKE_SIZE_MIN, HE_SMOKE_SIZE_MAX)
                    .velocity(HE_SMOKE_VELOCITY_MIN, HE_SMOKE_VELOCITY_MAX)
                    .duration(HE_SMOKE_DURATION_MIN, HE_SMOKE_DURATION_MAX)
                    .offset(HE_SMOKE_OFFSET_MIN, HE_SMOKE_OFFSET_MAX)
                    .arc(HE_SMOKE_ARC)
                    .count(HE_SMOKE_COUNT)
                    .nebulaSizeMult(HE_SMOKE_SIZE_MULT)
                    .nebulaFade(HE_SMOKE_FADE_IN, HE_SMOKE_FADE_OUT);
            Moci_MuzzleFlashEffect.spawnMuzzleFlashWithBarrel(engine, weapon, heSmoke, currentBarrel);

            // 火花
            Moci_MuzzleFlashEffect.EffectConfig heSparks = new Moci_MuzzleFlashEffect.EffectConfig()
                    .type(HE_SPARKS_TYPE)
                    .color(HE_SPARKS_COLOR)
                    .size(HE_SPARKS_SIZE_MIN, HE_SPARKS_SIZE_MAX)
                    .velocity(HE_SPARKS_VELOCITY_MIN, HE_SPARKS_VELOCITY_MAX)
                    .duration(HE_SPARKS_DURATION_MIN, HE_SPARKS_DURATION_MAX)
                    .offset(HE_SPARKS_OFFSET_MIN, HE_SPARKS_OFFSET_MAX)
                    .arc(HE_SPARKS_ARC)
                    .count(HE_SPARKS_COUNT);
            Moci_MuzzleFlashEffect.spawnMuzzleFlashWithBarrel(engine, weapon, heSparks, currentBarrel);

            // 替换为高爆弹丸
            engine.spawnProjectile(projectile.getSource(),
                    weapon,
                    weapon.getId() + "_HE",
                    projectile.getLocation(),
                    projectile.getFacing(),
                    projectile.getSource().getVelocity());
            engine.removeEntity(projectile);
        } else {
            // 普通弹特效：蓝白色闪光 + 轻烟雾

            // 播放动能弹音效
            Global.getSoundPlayer().playSound("Moci_Yamato_maingun_01", 1f, 1f, 
                    weapon.getLocation(), weapon.getShip().getVelocity());

            // 闪光
            Moci_MuzzleFlashEffect.EffectConfig normalFlash = new Moci_MuzzleFlashEffect.EffectConfig()
                    .type(NORMAL_FLASH_TYPE)
                    .color(NORMAL_FLASH_COLOR)
                    .size(NORMAL_FLASH_SIZE_MIN, NORMAL_FLASH_SIZE_MAX)
                    .velocity(NORMAL_FLASH_VELOCITY_MIN, NORMAL_FLASH_VELOCITY_MAX)
                    .duration(NORMAL_FLASH_DURATION_MIN, NORMAL_FLASH_DURATION_MAX)
                    .offset(NORMAL_FLASH_OFFSET_MIN, NORMAL_FLASH_OFFSET_MAX)
                    .arc(NORMAL_FLASH_ARC)
                    .count(NORMAL_FLASH_COUNT);
            Moci_MuzzleFlashEffect.spawnMuzzleFlashWithBarrel(engine, weapon, normalFlash, currentBarrel);
            /*
             * // 烟雾
             * Moci_MuzzleFlashEffect.EffectConfig normalSmoke = new
             * Moci_MuzzleFlashEffect.EffectConfig()
             * .type(NORMAL_SMOKE_TYPE)
             * .color(NORMAL_SMOKE_COLOR)
             * .size(NORMAL_SMOKE_SIZE_MIN, NORMAL_SMOKE_SIZE_MAX)
             * .velocity(NORMAL_SMOKE_VELOCITY_MIN, NORMAL_SMOKE_VELOCITY_MAX)
             * .duration(NORMAL_SMOKE_DURATION_MIN, NORMAL_SMOKE_DURATION_MAX)
             * .offset(NORMAL_SMOKE_OFFSET_MIN, NORMAL_SMOKE_OFFSET_MAX)
             * .arc(NORMAL_SMOKE_ARC)
             * .count(NORMAL_SMOKE_COUNT)
             * .nebulaSizeMult(NORMAL_SMOKE_SIZE_MULT)
             * .nebulaFade(NORMAL_SMOKE_FADE_IN, NORMAL_SMOKE_FADE_OUT);
             * Moci_MuzzleFlashEffect.spawnMuzzleFlashWithBarrel(engine, weapon,
             * normalSmoke, currentBarrel);
             * 
             */

            // 能量粒子（类似火花的能量效果）
            Moci_MuzzleFlashEffect.EffectConfig normalSparks = new Moci_MuzzleFlashEffect.EffectConfig()
                    .type(NORMAL_SPARKS_TYPE)
                    .color(NORMAL_SPARKS_COLOR)
                    .size(NORMAL_SPARKS_SIZE_MIN, NORMAL_SPARKS_SIZE_MAX)
                    .velocity(NORMAL_SPARKS_VELOCITY_MIN, NORMAL_SPARKS_VELOCITY_MAX)
                    .duration(NORMAL_SPARKS_DURATION_MIN, NORMAL_SPARKS_DURATION_MAX)
                    .offset(NORMAL_SPARKS_OFFSET_MIN, NORMAL_SPARKS_OFFSET_MAX)
                    .arc(NORMAL_SPARKS_ARC)
                    .count(NORMAL_SPARKS_COUNT);
            Moci_MuzzleFlashEffect.spawnMuzzleFlashWithBarrel(engine, weapon, normalSparks, currentBarrel);
        }

        // 更新炮管索引（用于多管武器）
        updateBarrelIndex(weapon);
    }

    /**
     * 更新当前炮管索引
     */
    private void updateBarrelIndex(WeaponAPI weapon) {
        currentBarrel++;

        int barrelCount = weapon.getSpec().getTurretAngleOffsets().size();
        if (weapon.getSlot().isHardpoint()) {
            barrelCount = weapon.getSpec().getHardpointAngleOffsets().size();
        } else if (weapon.getSlot().isHidden()) {
            barrelCount = weapon.getSpec().getHiddenAngleOffsets().size();
        }

        if (currentBarrel >= barrelCount) {
            currentBarrel = 0;
        }
    }
}
