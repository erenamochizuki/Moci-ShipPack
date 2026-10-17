package data.scripts.weapons;

import java.awt.Color;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicAnim;
import org.magiclib.util.MagicRender;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

/**
 * 简化版魔法动画武器控制器
 * 只包含武器动画和开火点粒子特效功能
 * 
 * 动画规则：
 * - 根据武器充能等级和槽位ID控制偏移量
 * - 充能为0时：武器在默认位置
 * - 充能达到阈值时：武器移动指定像素
 * - MAIN_L槽位：向右移动（X轴正方向）
 * - MAIN_R槽位：向左移动（X轴负方向）
 * - 其他槽位：向上移动（Y轴负方向）
 * - 使用MagicAnim实现平滑过渡效果
 * 
 * 开火点特效：
 * - 在武器的精确开火点生成粒子特效
 * - 包含闪光粒子、镜头光晕和散热烟雾
 * - 支持多炮管武器的轮换效果
 * - 通过布尔值控制各种效果的开关
 */
public class Moci_SimpleAnimationGun implements EveryFrameWeaponEffectPlugin {

    // ========== 动画参数配置 ==========
    private static final float OFFSET_MAX = 15f; // 最大偏移量（像素）
    private static final float CHARGE_THRESHOLD = 0f; // 充能阈值，超过此值开始移动
    private static final float ANIMATION_SPEED = 2.5f; // 动画速度倍数

    // 槽位ID常量
    private static final String SLOT_MAIN_L = "MAIN_L"; // 左主武器槽位
    private static final String SLOT_MAIN_R = "MAIN_R"; // 右主武器槽位

    // ========== 开火点特效参数 ==========
    private static final boolean ENABLE_MUZZLE_FLASH = true; // 开火点闪光开关
    private static final boolean ENABLE_LENS_FLARE = true; // 镜头光晕开关
    private static final boolean ENABLE_HEAT_SMOKE = true; // 散热烟雾开关

    // 闪光效果参数
    private static final float FLASH_INTERVAL = 0.1f; // 闪光间隔（秒）
    private static final Color FLASH_COLOR = new Color(255, 200, 100, 200); // 闪光颜色（橙黄色）

    // 镜头光晕参数
    private static final String LENS_FLARE_SPRITE_ID = "tahlan_lens_flare2"; // 镜头光晕贴图ID
    private static final Color FLARE_COLOR_PRIMARY = new Color(255, 30, 0, 80); // 主光晕颜色（红色）
    private static final Color FLARE_COLOR_SECONDARY = new Color(255, 200, 200, 100); // 次光晕颜色（白色）
    private static final Vector2f FLARE_SIZE_PRIMARY = new Vector2f(200f, 10f); // 主光晕尺寸
    private static final Vector2f FLARE_SIZE_SECONDARY = new Vector2f(150f, 2f); // 次光晕尺寸

    // 烟雾效果参数
    private static final float SMOKE_INTERVAL = 0.15f; // 烟雾生成间隔（秒）
    private static final float SMOKE_SPEED = 25f; // 烟雾粒子速度
    private static final float SMOKE_SIZE = 15f; // 烟雾粒子基础大小
    private static final float SMOKE_SIZE_MULT = 2.5f; // 烟雾粒子大小倍数
    private static final float SMOKE_DURATION = 3f; // 烟雾持续时间
    private static final Color SMOKE_COLOR = new Color(120, 120, 120, 100); // 烟雾颜色（灰色，半透明）

    // ========== 状态变量 ==========
    // 动画状态
    private float currentOffset = 0f; // 当前偏移量
    private boolean initialized = false;
    private SpriteAPI weaponSprite = null; // 武器精灵
    private float originalCenterX = 0f; // 原始中心点X坐标
    private float originalCenterY = 0f; // 原始中心点Y坐标
    private String slotId = null; // 武器槽位ID
    private boolean isXAxisAnimation = false; // 是否为X轴动画
    private float offsetDirection = 1f; // 偏移方向

    // 特效状态
    private float flashTimer = 0f; // 闪光计时器
    private float smokeTimer = 0f; // 烟雾计时器
    private boolean hasFiredThisCharge = false; // 本次充能是否已开火
    private int currentBarrel = 0; // 当前炮管索引

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础检查：确保游戏和武器状态正常
        if (engine == null || engine.isPaused()) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        // 初始化：获取武器精灵、槽位信息和原始位置
        if (!initialized) {
            weaponSprite = weapon.getSprite();
            if (weaponSprite != null && weapon.getSlot() != null) {
                // 记录原始中心点位置
                originalCenterX = weaponSprite.getCenterX();
                originalCenterY = weaponSprite.getCenterY();

                // 获取槽位ID并确定动画方向
                slotId = weapon.getSlot().getId();
                determineAnimationDirection();

                initialized = true;
            } else {
                return; // 如果无法获取精灵或槽位信息，跳过处理
            }
        }

        // ========== 开火点特效处理 ==========
        handleMuzzleEffects(amount, engine, weapon);

        // ========== 武器动画处理 ==========
        handleWeaponAnimation(amount, weapon);
    }

    /**
     * 根据槽位ID确定动画方向
     */
    private void determineAnimationDirection() {
        if (slotId == null) {
            // 默认为Y轴向上动画
            isXAxisAnimation = false;
            offsetDirection = -1f; // 向上为负方向
            return;
        }

        if (SLOT_MAIN_L.equals(slotId)) {
            // MAIN_L槽位：X轴向右移动
            isXAxisAnimation = true;
            offsetDirection = 1f; // 向右为正方向
        } else if (SLOT_MAIN_R.equals(slotId)) {
            // MAIN_R槽位：X轴向左移动
            isXAxisAnimation = true;
            offsetDirection = -1f; // 向左为负方向
        } else {
            // 其他槽位：Y轴向上移动
            isXAxisAnimation = false;
            offsetDirection = -1f; // 向上为负方向
        }
    }

    /**
     * 处理武器动画效果
     */
    private void handleWeaponAnimation(float amount, WeaponAPI weapon) {
        // 获取当前武器充能等级（0.0 - 1.0）
        float chargeLevel = weapon.getChargeLevel();

        // 计算目标偏移量
        float targetOffset = calculateTargetOffset(chargeLevel);

        // 使用平滑过渡
        float offsetDifference = targetOffset - currentOffset;

        // 如果差值很小，直接设置目标值
        if (Math.abs(offsetDifference) < 0.1f) {
            currentOffset = targetOffset;
        } else {
            // 平滑过渡到目标偏移量
            float smoothStep = offsetDifference * ANIMATION_SPEED * amount;
            currentOffset += smoothStep;
        }

        // 应用偏移到武器精灵
        applyOffsetToWeapon(weapon);
    }

    /**
     * 根据充能等级计算目标偏移量
     */
    private float calculateTargetOffset(float chargeLevel) {
        if (chargeLevel >= CHARGE_THRESHOLD) {
            // 充能达到阈值时，使用平滑插值计算偏移量
            float normalizedCharge = MagicAnim.normalizeRange(chargeLevel, CHARGE_THRESHOLD, 1.0f);
            float smoothedCharge = MagicAnim.smooth(normalizedCharge);
            return smoothedCharge * OFFSET_MAX * offsetDirection;
        } else {
            // 充能未达到阈值时，保持在原位置
            return 0f;
        }
    }

    /**
     * 将偏移应用到武器精灵
     */
    private void applyOffsetToWeapon(WeaponAPI weapon) {
        if (weaponSprite == null)
            return;

        if (isXAxisAnimation) {
            // X轴动画：修改中心点X坐标
            float newCenterX = originalCenterX + currentOffset;
            weaponSprite.setCenter(newCenterX, originalCenterY);
        } else {
            // Y轴动画：修改中心点Y坐标
            float newCenterY = originalCenterY + currentOffset;
            weaponSprite.setCenter(originalCenterX, newCenterY);
        }
    }

    /**
     * 处理开火点特效
     */
    private void handleMuzzleEffects(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 屏幕外剔除优化
        if (!engine.getViewport().isNearViewport(weapon.getLocation(), 400f)) {
            return;
        }

        // 获取武器状态
        float chargeLevel = weapon.getChargeLevel();
        boolean isFiring = weapon.isFiring();

        // 处理开火点特效（在开火时渲染）
        if (isFiring && chargeLevel > 0f) {
            // 获取开火点位置
            Vector2f muzzleLocation = calculateMuzzleLocation(weapon);

            // 处理闪光效果
            if (ENABLE_MUZZLE_FLASH) {
                handleMuzzleFlash(amount, engine, weapon, muzzleLocation);
            }

            // 处理镜头光晕效果
            if (ENABLE_LENS_FLARE) {
                handleLensFlare(engine, weapon, muzzleLocation);
            }

            // 处理散热烟雾效果
            if (ENABLE_HEAT_SMOKE) {
                handleHeatSmoke(amount, engine, weapon, muzzleLocation);
            }
        } else {
            // 重置计时器
            flashTimer = 0f;
            smokeTimer = 0f;
        }

        // 处理炮管轮换
        handleBarrelRotation(weapon, chargeLevel, isFiring);
    }

    /**
     * 处理开火点闪光效果
     */
    private void handleMuzzleFlash(float amount, CombatEngineAPI engine, WeaponAPI weapon, Vector2f muzzleLocation) {
        flashTimer += amount;

        if (flashTimer >= FLASH_INTERVAL) {
            // 生成闪光粒子
            float size = MathUtils.getRandomNumberInRange(15f, 25f);
            float duration = MathUtils.getRandomNumberInRange(0.2f, 0.25f);

            engine.addSmoothParticle(muzzleLocation, weapon.getShip().getVelocity(), size, 0.8f, duration, FLASH_COLOR);

            flashTimer = 0f;
        }
    }

    /**
     * 处理镜头光晕效果
     */
    private void handleLensFlare(CombatEngineAPI engine, WeaponAPI weapon, Vector2f muzzleLocation) {
        try {
            // 获取镜头光晕贴图
            SpriteAPI flare1 = Global.getSettings().getSprite("fx", LENS_FLARE_SPRITE_ID);
            SpriteAPI flare2 = Global.getSettings().getSprite("fx", LENS_FLARE_SPRITE_ID);

            // 主光晕（红色）
            Vector2f flarePos1 = MathUtils.getRandomPointInCircle(muzzleLocation, MathUtils.getRandomNumberInRange(0f, 2f));
            MagicRender.singleframe(
                flare1,
                flarePos1,
                FLARE_SIZE_PRIMARY,
                0f,
                FLARE_COLOR_PRIMARY,
                true
            );

            // 次光晕（白色）
            Vector2f flarePos2 = MathUtils.getRandomPointInCircle(muzzleLocation, MathUtils.getRandomNumberInRange(0f, 2f));
            MagicRender.singleframe(
                flare2,
                flarePos2,
                FLARE_SIZE_SECONDARY,
                0f,
                FLARE_COLOR_SECONDARY,
                true
            );
        } catch (Exception e) {
            // 如果贴图不存在，静默忽略镜头光晕效果
        }
    }

    /**
     * 处理散热烟雾效果
     */
    private void handleHeatSmoke(float amount, CombatEngineAPI engine, WeaponAPI weapon, Vector2f muzzleLocation) {
        smokeTimer += amount;

        if (smokeTimer >= SMOKE_INTERVAL) {
            float weaponAngle = weapon.getCurrAngle();

            // 在开火点周围生成烟雾
            for (int i = 0; i < 2; i++) {
                // 在开火点附近随机偏移
                Vector2f smokeLoc = new Vector2f(muzzleLocation);
                smokeLoc.x += MathUtils.getRandomNumberInRange(-5f, 5f);
                smokeLoc.y += MathUtils.getRandomNumberInRange(-5f, 5f);

                // 计算烟雾速度（向武器后方扩散）
                float smokeAngle = weaponAngle + 180f + MathUtils.getRandomNumberInRange(-30f, 30f);
                Vector2f smokeVel = VectorUtils.getDirectionalVector(smokeLoc,
                        MathUtils.getPointOnCircumference(smokeLoc, 10f, smokeAngle));
                smokeVel.scale(SMOKE_SPEED);

                // 叠加舰船速度
                Vector2f.add(weapon.getShip().getVelocity(), smokeVel, smokeVel);

                // 生成烟雾粒子
                engine.addNebulaSmokeParticle(
                    smokeLoc,
                    smokeVel,
                    SMOKE_SIZE,
                    SMOKE_SIZE_MULT,
                    0.1f,
                    0.1f,
                    SMOKE_DURATION,
                    SMOKE_COLOR
                );
            }

            smokeTimer = 0f;
        }
    }

    /**
     * 处理炮管轮换逻辑
     */
    private void handleBarrelRotation(WeaponAPI weapon, float chargeLevel, boolean isFiring) {
        // 如果达到满充能且未记录开火，标记为已开火
        if (chargeLevel >= 1f && !hasFiredThisCharge) {
            hasFiredThisCharge = true;
        }

        // 如果已开火且现在停止充能或停止开火，进行炮管轮换
        if (hasFiredThisCharge && (chargeLevel <= 0f || !isFiring)) {
            hasFiredThisCharge = false;
            currentBarrel++;

            // 获取炮管数量并循环
            int barrelCount = getBarrelCount(weapon);
            if (currentBarrel >= barrelCount) {
                currentBarrel = 0;
            }
        }
    }

    /**
     * 获取武器的炮管数量
     */
    private int getBarrelCount(WeaponAPI weapon) {
        if (weapon.getSlot().isHardpoint()) {
            return weapon.getSpec().getHardpointAngleOffsets().size();
        } else if (weapon.getSlot().isHidden()) {
            return weapon.getSpec().getHiddenAngleOffsets().size();
        } else {
            return weapon.getSpec().getTurretAngleOffsets().size();
        }
    }

    /**
     * 计算开火点的世界坐标位置
     */
    private Vector2f calculateMuzzleLocation(WeaponAPI weapon) {
        // 初始坐标
        Vector2f trueCenterLocation = new Vector2f(0f, 0f);

        // 获取炮管的开火偏移
        if (weapon.getSlot().isHardpoint()) {
            trueCenterLocation.x += weapon.getSpec().getHardpointFireOffsets().get(currentBarrel).x;
            trueCenterLocation.y += weapon.getSpec().getHardpointFireOffsets().get(currentBarrel).y;
        } else if (weapon.getSlot().isTurret()) {
            trueCenterLocation.x += weapon.getSpec().getTurretFireOffsets().get(currentBarrel).x;
            trueCenterLocation.y += weapon.getSpec().getTurretFireOffsets().get(currentBarrel).y;
        } else {
            trueCenterLocation.x += weapon.getSpec().getHiddenFireOffsets().get(currentBarrel).x;
            trueCenterLocation.y += weapon.getSpec().getHiddenFireOffsets().get(currentBarrel).y;
        }

        // 转换到世界坐标系
        trueCenterLocation = VectorUtils.rotate(trueCenterLocation, weapon.getCurrAngle(), new Vector2f(0f, 0f));
        trueCenterLocation.x += weapon.getLocation().x;
        trueCenterLocation.y += weapon.getLocation().y;

        return trueCenterLocation;
    }
}