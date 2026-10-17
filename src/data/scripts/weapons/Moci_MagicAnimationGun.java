package data.scripts.weapons;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import org.lazywizard.lazylib.FastTrig;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicAnim;
import org.magiclib.util.MagicRender;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

/**
 * 魔法动画武器控制器
 * 使用MagicLib的MagicAnim功能实现武器的平滑移动动画
 * 
 * 动画规则：
 * - 根据武器充能等级和槽位ID控制偏移量
 * - 充能为0时：武器在默认位置
 * - 充能达到阈值时：武器移动20像素
 * - MAIN_L槽位：向右移动（X轴正方向）
 * - MAIN_R槽位：向左移动（X轴负方向）
 * - 其他槽位：向上移动（Y轴负方向）
 * - 使用MagicAnim实现平滑过渡效果
 * - 直接修改武器精灵的中心点位置实现动画
 * 
 * 伪弹夹机制：
 * - 弹药为0时禁止开火
 * - 开火时每0.25秒消耗1发弹药
 * 
 * 散热效果：
 * - 持续开火时生成烟雾粒子模拟散热
 * - 通过ENABLE_HEAT_SMOKE布尔值控制开关
 * 
 * 光束振荡效果：
 * - 对光束武器添加双频振荡效果
 * - 通过ENABLE_BEAM_OSCILLATION布尔值控制开关
 * - 使用两个不同频率的正弦波控制光束宽度变化
 * 
 * 光束起点效果：
 * - 在光束起点生成粒子和镜头光晕特效
 * - 支持多炮管武器的轮换效果
 * - 包含散热烟雾、起点粒子和镜头光晕
 * - 通过ENABLE_BEAM_MUZZLE_EFFECTS布尔值控制开关
 */
public class Moci_MagicAnimationGun implements EveryFrameWeaponEffectPlugin {

    // 动画参数配置
    private static final float OFFSET_MAX = 15f; // 最大偏移量（像素）
    private static final float CHARGE_THRESHOLD = 0f; // 充能阈值，超过此值开始移动
    private static final float ANIMATION_SPEED = 3f; // 动画速度倍数

    // 槽位ID常量
    private static final String SLOT_MAIN_L = "MAIN_L"; // 左主武器槽位
    private static final String SLOT_MAIN_R = "MAIN_R"; // 右主武器槽位

    // 伪弹夹机制参数
    private static final float AMMO_CONSUME_INTERVAL = 0.25f; // 弹药消耗间隔（秒）
    private float ammoTimer = 0f; // 弹药计时器
    private boolean wasFiring = false; // 上一帧是否在开火

    // ========== 烟雾散热效果参数 ==========
    private static final boolean ENABLE_HEAT_SMOKE = true; // 散热烟雾开关
    private static final float SMOKE_INTERVAL = 0.15f; // 烟雾生成间隔（秒）
    private static final float SMOKE_SPEED = 25f; // 烟雾粒子速度
    private static final float SMOKE_SIZE = 15f; // 烟雾粒子基础大小
    private static final float SMOKE_SIZE_MULT = 2.5f; // 烟雾粒子大小倍数
    private static final float SMOKE_DURATION = 3f; // 烟雾持续时间
    private static final Color SMOKE_COLOR = new Color(120, 120, 120, 100); // 烟雾颜色（灰色，半透明）
    private float smokeTimer = 0f; // 烟雾计时器

    // 动画状态变量
    private float currentOffset = 0f; // 当前偏移量
    private boolean initialized = false;
    private SpriteAPI weaponSprite = null; // 武器精灵
    private float originalCenterX = 0f; // 原始中心点X坐标
    private float originalCenterY = 0f; // 原始中心点Y坐标

    private String slotId = null; // 武器槽位ID
    private boolean isXAxisAnimation = false; // 是否为X轴动画（true=X轴，false=Y轴）
    private float offsetDirection = 1f; // 偏移方向（1=正方向，-1=负方向）
    private boolean shouldAnimate = false; // 是否应该执行动画（只有符合命名规则的槽位才执行）

    // ========== 光束振荡效果参数 ==========
    private static final boolean ENABLE_BEAM_OSCILLATION = true; // 光束振荡开关
    private static final float OSCILLATION_TIME_PRIM = 0.1f; // 主振荡周期
    private static final float OSCILLATION_TIME_SEC = 0.25f; // 次振荡周期
    private static final float OSCILLATION_WIDTH_MIN = 0.75f; // 振荡最小宽度倍数
    private static final float OSCILLATION_WIDTH_MAX = 1.25f; // 振荡最大宽度倍数

    // 光束振荡状态变量
    private float oscillationCounter = 0f; // 振荡时间计数器
    private boolean oscillationRunOnce = true; // 振荡初始化标志
    private Map<Integer, BeamAPI> beamMap = new HashMap<Integer, BeamAPI>(); // 光束映射
    private Map<Integer, Float> oscillationWidthMap = new HashMap<Integer, Float>(); // 光束原始宽度映射

    // ========== 光束起点效果参数 ==========
    private static final boolean ENABLE_BEAM_MUZZLE_EFFECTS = true; // 光束起点效果开关
    private static final float BEAM_FLASH_INTERVAL = 0.1f; // 光束闪光间隔（秒）
    private static final String LENS_FLARE_SPRITE_ID = "Moci_bullet_aura"; // 镜头光晕贴图ID
    private static final Color FLARE_COLOR_PRIMARY = new Color(215, 157, 255, 80); // 主光晕颜色
    private static final Color FLARE_COLOR_SECONDARY = new Color(255, 200, 200, 100); // 次光晕颜色（白色）
    private static final Vector2f FLARE_SIZE_PRIMARY = new Vector2f(200f, 10f); // 主光晕尺寸
    private static final Vector2f FLARE_SIZE_SECONDARY = new Vector2f(150f, 2f); // 次光晕尺寸

    // 光束起点效果状态变量
    private float beamFlashTimer = 0f; // 光束闪光计时器
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

        // ========== 伪弹夹机制处理 ==========
        handlePseudoAmmoSystem(amount, weapon);

        // ========== 光束振荡效果处理 ==========
        handleBeamOscillation(amount, engine, weapon);

        // ========== 光束起点效果处理 ==========
        handleBeamMuzzleEffects(amount, engine, weapon);

        // ========== 武器动画处理 ==========
        handleWeaponAnimation(amount, weapon);
    }

    /**
     * 根据槽位ID确定动画方向
     * 只有符合命名规则的槽位才执行动画
     */
    private void determineAnimationDirection() {
        if (slotId == null) {
            // 槽位ID为空，不执行动画
            shouldAnimate = false;
            return;
        }

        if (SLOT_MAIN_L.equals(slotId)) {
            // MAIN_L槽位：X轴向右移动
            isXAxisAnimation = true;
            offsetDirection = 1f; // 向右为正方向
            shouldAnimate = true;
        } else if (SLOT_MAIN_R.equals(slotId)) {
            // MAIN_R槽位：X轴向左移动
            isXAxisAnimation = true;
            offsetDirection = -1f; // 向左为负方向
            shouldAnimate = true;
        } else {
            // 其他槽位：不执行动画
            shouldAnimate = false;
        }
    }

    /**
     * 处理武器动画效果
     * 
     * @param amount 时间增量
     * @param weapon 武器实例
     */
    private void handleWeaponAnimation(float amount, WeaponAPI weapon) {
        // 如果不应该执行动画，直接返回
        if (!shouldAnimate) {
            return;
        }

        // 获取当前武器充能等级（0.0 - 1.0）
        float chargeLevel = weapon.getChargeLevel();

        // 计算目标偏移量
        float targetOffset = calculateTargetOffset(chargeLevel);

        // 使用MagicAnim实现平滑过渡
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
     * 
     * @param chargeLevel 武器充能等级 (0.0 - 1.0)
     * @return 目标偏移量
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
     * 根据动画类型修改武器精灵的中心点位置实现动画效果
     * 
     * @param weapon 武器实例
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
     * 处理伪弹夹机制
     * 
     * @param amount 时间增量
     * @param weapon 武器实例
     */
    private void handlePseudoAmmoSystem(float amount, WeaponAPI weapon) {
        // 检查武器是否使用弹药系统
        if (!weapon.usesAmmo()) {
            return; // 武器不使用弹药，跳过处理
        }

        // 获取当前弹药数量和充能等级
        int currentAmmo = weapon.getAmmo();
        boolean isFiring = weapon.isFiring();
        float chargeLevel = weapon.getChargeLevel();

        // 弹药为0时禁止开火
        if (currentAmmo <= 0) {
            weapon.setForceNoFireOneFrame(true);
            // 重置计时器
            ammoTimer = 0f;
            wasFiring = false;
            return;
        }

        // 处理开火状态变化
        if (isFiring && !wasFiring) {
            // 刚开始开火，重置计时器
            ammoTimer = 0f;
        } else if (!isFiring && wasFiring) {
            // 停止开火，重置计时器
            ammoTimer = 0f;
        }

        // 如果正在开火且充能等级足够高，累计时间
        if (isFiring && chargeLevel > 0.99f) {
            ammoTimer += amount;

            // 当累计时间达到消耗间隔时
            if (ammoTimer >= AMMO_CONSUME_INTERVAL) {
                // 扣除一发弹药
                if (currentAmmo > 0) {
                    weapon.setAmmo(currentAmmo - 1);
                }

                // 重置计时器，准备下一次消耗
                ammoTimer = 0f;
            }
        }

        // 更新开火状态记录
        wasFiring = isFiring;
    }

    /**
     * 检查武器是否具有光束振荡能力
     */
    private boolean canOscillateBeam(WeaponAPI weapon) {
        if (weapon == null || weapon.getSpec() == null)
            return false;
        return ENABLE_BEAM_OSCILLATION; // 使用布尔值控制
    }

    /**
     * 处理光束振荡效果
     * 使用双频正弦波控制光束宽度的动态变化
     * 
     * @param amount 时间增量
     * @param engine 战斗引擎
     * @param weapon 武器实例
     */
    private void handleBeamOscillation(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 检查光束振荡功能是否启用
        if (!canOscillateBeam(weapon)) {
            return;
        }

        // 重置映射和变量如果光束不存在（充能未满）
        if (weapon.getChargeLevel() < 1f) {
            oscillationCounter = 0f;
            beamMap.clear();
            oscillationWidthMap.clear();
            oscillationRunOnce = true;
            return;
        }

        // 如果武器完全充能且需要初始化，收集光束信息
        if (weapon.getChargeLevel() >= 1f && oscillationRunOnce) {
            int counterForBeams = 0;
            for (BeamAPI beam : engine.getBeams()) {
                if (beam.getWeapon() == weapon) {
                    if (!beamMap.containsValue(beam)) {
                        beamMap.put(counterForBeams, beam);
                        counterForBeams++;
                    }
                }
            }

            if (!beamMap.isEmpty()) {
                oscillationRunOnce = false;
            }
        }

        // 推进时间计数器
        oscillationCounter += amount;

        // 获取光束并应用振荡效果
        for (Integer i : beamMap.keySet()) {
            BeamAPI beam = beamMap.get(i);

            // 初始化宽度映射（如果需要）
            if (oscillationWidthMap.get(i) == null) {
                oscillationWidthMap.put(i, beam.getWidth());
            }

            // 计算振荡相位：使用两个不同频率的正弦波
            float radCountPrim = oscillationCounter * 2f * (float) Math.PI / OSCILLATION_TIME_PRIM;
            float radCountSec = oscillationCounter * 2f * (float) Math.PI / OSCILLATION_TIME_SEC;

            // 主振荡：快速变化，振幅较大
            float oscillationPhasePrim = ((float) FastTrig.sin(radCountPrim) * 0.4f) + 0.6f;
            // 次振荡：缓慢变化，振幅较小
            float oscillationPhaseSec = ((float) FastTrig.sin(radCountSec) * 0.15f) + 0.85f;

            // 计算视觉倍数：两个振荡相乘产生复合效果
            float visMult = oscillationPhasePrim * oscillationPhaseSec;

            // 将振荡结果限制在设定的最小最大范围内
            visMult = Math.max(OSCILLATION_WIDTH_MIN, Math.min(OSCILLATION_WIDTH_MAX, visMult));

            // 应用振荡效果到光束宽度
            beam.setWidth(oscillationWidthMap.get(i) * visMult);
        }
    }

    /**
     * 检查武器是否具有光束起点效果能力
     */
    private boolean canGenerateBeamMuzzleEffects(WeaponAPI weapon) {
        if (weapon == null || weapon.getSpec() == null)
            return false;
        return ENABLE_BEAM_MUZZLE_EFFECTS; // 使用布尔值控制
    }

    /**
     * 处理光束起点效果
     * 在光束起点生成粒子、镜头光晕和烟雾特效
     * 
     * @param amount 时间增量
     * @param engine 战斗引擎
     * @param weapon 武器实例
     */
    private void handleBeamMuzzleEffects(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 检查光束起点效果功能是否启用
        if (!canGenerateBeamMuzzleEffects(weapon)) {
            return;
        }

        // 屏幕外剔除优化
        if (!engine.getViewport().isNearViewport(weapon.getLocation(), 400f)) {
            return;
        }

        // 获取武器状态
        float chargeLevel = weapon.getChargeLevel();
        boolean isFiring = weapon.isFiring();

        // 处理光束起点效果（只在完全充能且开火时渲染）
        if (isFiring && chargeLevel >= 0.9f) {
            // 获取光束起点位置
            Vector2f muzzleLocation = calculateMuzzleLocation(weapon);

            // 处理光束闪光效果
            handleBeamFlashEffects(amount, engine, weapon, muzzleLocation);

            // 处理镜头光晕效果
            handleLensFlareEffects(engine, weapon, muzzleLocation);

            // 处理散热烟雾效果
            handleMuzzleSmokeEffects(amount, engine, weapon, muzzleLocation);
        } else {
            // 重置计时器
            beamFlashTimer = 0f;
            smokeTimer = 0f;
        }

        // 处理开火状态变化和炮管轮换
        handleBarrelRotation(weapon, chargeLevel, isFiring);
    }

    /**
     * 确定武器的序列状态
     */
    private String determineWeaponSequenceState(WeaponAPI weapon, float chargeLevel, boolean isFiring) {
        if (chargeLevel > 0 && (!weapon.isBeam() || isFiring)) {
            if (chargeLevel >= 1f) {
                return "FIRING";
            } else if (!hasFiredThisCharge) {
                return "CHARGEUP";
            } else {
                return "CHARGEDOWN";
            }
        } else if (weapon.getCooldownRemaining() > 0) {
            return "COOLDOWN";
        }
        return "READY";
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
     * 处理光束闪光效果
     * 在所有开火点生成粒子特效
     */
    private void handleBeamFlashEffects(float amount, CombatEngineAPI engine, WeaponAPI weapon,
            Vector2f muzzleLocation) {
        beamFlashTimer += amount;

        if (beamFlashTimer >= BEAM_FLASH_INTERVAL) {
            // 获取光束信息（如果是光束武器）
            BeamAPI currentBeam = null;
            for (BeamAPI beam : engine.getBeams()) {
                if (beam.getWeapon() == weapon) {
                    currentBeam = beam;
                    break;
                }
            }

            // 获取所有开火点数量
            int firePointCount = getBarrelCount(weapon);

            // 在所有开火点生成特效
            for (int i = 0; i < firePointCount; i++) {
                Vector2f firePointLocation = calculateFirePointLocation(weapon, i);

                if (currentBeam != null) {
                    // 使用光束的颜色和宽度
                    float beamWidth = currentBeam.getWidth();
                    Color coreColor = currentBeam.getCoreColor();
                    Color fringeColor = currentBeam.getFringeColor();

                    // 生成核心粒子
                    float size = beamWidth * MathUtils.getRandomNumberInRange(2f, 2.2f);
                    float duration = MathUtils.getRandomNumberInRange(0.2f, 0.25f);

                    engine.addSmoothParticle(firePointLocation, weapon.getShip().getVelocity(), beamWidth, 0.8f,
                            duration, coreColor);
                    engine.addSmoothParticle(firePointLocation, weapon.getShip().getVelocity(), size, 0.8f, duration,
                            fringeColor.brighter());
                } else {
                    // 非光束武器的默认粒子效果
                    float size = MathUtils.getRandomNumberInRange(15f, 25f);
                    float duration = MathUtils.getRandomNumberInRange(0.2f, 0.25f);
                    Color defaultColor = new Color(255, 200, 100, 200);

                    engine.addSmoothParticle(firePointLocation, weapon.getShip().getVelocity(), size, 0.8f, duration,
                            defaultColor);
                }
            }

            beamFlashTimer = 0f;
        }
    }

    /**
     * 处理镜头光晕效果
     * 使用MagicRender在所有开火点生成镜头光晕
     */
    private void handleLensFlareEffects(CombatEngineAPI engine, WeaponAPI weapon, Vector2f muzzleLocation) {
        try {
            // 获取所有开火点数量
            int firePointCount = getBarrelCount(weapon);

            // 在所有开火点生成光晕
            for (int i = 0; i < firePointCount; i++) {
                Vector2f firePointLocation = calculateFirePointLocation(weapon, i);

                // 获取镜头光晕贴图
                SpriteAPI flare1 = Global.getSettings().getSprite("fx", LENS_FLARE_SPRITE_ID);
                SpriteAPI flare2 = Global.getSettings().getSprite("fx", LENS_FLARE_SPRITE_ID);

                // 主光晕（红色）
                Vector2f flarePos1 = MathUtils.getRandomPointInCircle(firePointLocation,
                        MathUtils.getRandomNumberInRange(0f, 2f));
                MagicRender.singleframe(
                        flare1,
                        flarePos1,
                        FLARE_SIZE_PRIMARY,
                        0f,
                        FLARE_COLOR_PRIMARY,
                        true);

                // 次光晕（白色）
                Vector2f flarePos2 = MathUtils.getRandomPointInCircle(firePointLocation,
                        MathUtils.getRandomNumberInRange(0f, 2f));
                MagicRender.singleframe(
                        flare2,
                        flarePos2,
                        FLARE_SIZE_SECONDARY,
                        0f,
                        FLARE_COLOR_SECONDARY,
                        true);
            }
        } catch (Exception e) {
            // 如果贴图不存在，静默忽略镜头光晕效果
        }
    }

    /**
     * 处理开火点烟雾效果
     * 在所有开火点生成散热烟雾
     */
    private void handleMuzzleSmokeEffects(float amount, CombatEngineAPI engine, WeaponAPI weapon,
            Vector2f muzzleLocation) {
        if (!ENABLE_HEAT_SMOKE) {
            return;
        }

        smokeTimer += amount;

        if (smokeTimer >= SMOKE_INTERVAL) {
            float weaponAngle = weapon.getCurrAngle();

            // 获取所有开火点数量
            int firePointCount = getBarrelCount(weapon);

            // 在所有开火点周围生成烟雾
            for (int firePoint = 0; firePoint < firePointCount; firePoint++) {
                Vector2f firePointLocation = calculateFirePointLocation(weapon, firePoint);

                // 在每个开火点周围生成烟雾粒子
                for (int i = 0; i < 2; i++) {
                    // 在开火点附近随机偏移
                    Vector2f smokeLoc = new Vector2f(firePointLocation);
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
                            SMOKE_COLOR);
                }
            }

            smokeTimer = 0f;
        }
    }

    /**
     * 计算开火点的世界坐标位置
     * 完全按照EradicatorSmokeScript的方式实现
     */
    private Vector2f calculateMuzzleLocation(WeaponAPI weapon) {
        // 初始坐标转换：EradicatorSmokeScript中的spawnPoint坐标转换
        Vector2f trueCenterLocation = new Vector2f(0f, 0f);

        // 确定真实的炮管索引（考虑状态偏移）
        int trueCurrentBarrel = currentBarrel;
        boolean shouldOffsetBarrelExtra = false; // 简化版本，不考虑CHARGEDOWN/COOLDOWN状态

        if (currentBarrel > 0 && shouldOffsetBarrelExtra) {
            trueCurrentBarrel -= 1;
        }

        // 获取炮管的开火偏移（完全按照EradicatorSmokeScript的方式）
        if (weapon.getSlot().isHardpoint()) {
            if (currentBarrel <= 0 && shouldOffsetBarrelExtra) {
                trueCurrentBarrel = weapon.getSpec().getHardpointAngleOffsets().size() - 1;
            }
            trueCenterLocation.x += weapon.getSpec().getHardpointFireOffsets().get(currentBarrel).x;
            trueCenterLocation.y += weapon.getSpec().getHardpointFireOffsets().get(currentBarrel).y;
        } else if (weapon.getSlot().isTurret()) {
            if (currentBarrel <= 0 && shouldOffsetBarrelExtra) {
                trueCurrentBarrel = weapon.getSpec().getTurretAngleOffsets().size() - 1;
            }
            trueCenterLocation.x += weapon.getSpec().getTurretFireOffsets().get(currentBarrel).x;
            trueCenterLocation.y += weapon.getSpec().getTurretFireOffsets().get(currentBarrel).y;
        } else {
            if (currentBarrel <= 0 && shouldOffsetBarrelExtra) {
                trueCurrentBarrel = weapon.getSpec().getHiddenAngleOffsets().size() - 1;
            }
            trueCenterLocation.x += weapon.getSpec().getHiddenFireOffsets().get(currentBarrel).x;
            trueCenterLocation.y += weapon.getSpec().getHiddenFireOffsets().get(currentBarrel).y;
        }

        // 转换到世界坐标系（完全按照EradicatorSmokeScript的方式）
        trueCenterLocation = VectorUtils.rotate(trueCenterLocation, weapon.getCurrAngle(), new Vector2f(0f, 0f));
        trueCenterLocation.x += weapon.getLocation().x;
        trueCenterLocation.y += weapon.getLocation().y;

        return trueCenterLocation;
    }

    /**
     * 获取指定炮管的角度偏移
     */
    private float getBarrelAngleOffset(WeaponAPI weapon, int barrelIndex) {
        if (weapon.getSlot().isHardpoint()) {
            return weapon.getSpec().getHardpointAngleOffsets().get(barrelIndex);
        } else if (weapon.getSlot().isHidden()) {
            return weapon.getSpec().getHiddenAngleOffsets().get(barrelIndex);
        } else {
            return weapon.getSpec().getTurretAngleOffsets().get(barrelIndex);
        }
    }

    /**
     * 计算指定索引的开火点世界坐标位置
     * 
     * @param weapon 武器实例
     * @param firePointIndex 开火点索引
     * @return 开火点的世界坐标位置
     */
    private Vector2f calculateFirePointLocation(WeaponAPI weapon, int firePointIndex) {
        Vector2f firePointLocation = new Vector2f(0f, 0f);

        // 获取指定索引的开火偏移
        if (weapon.getSlot().isHardpoint()) {
            firePointLocation.x += weapon.getSpec().getHardpointFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getHardpointFireOffsets().get(firePointIndex).y;
        } else if (weapon.getSlot().isTurret()) {
            firePointLocation.x += weapon.getSpec().getTurretFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getTurretFireOffsets().get(firePointIndex).y;
        } else {
            firePointLocation.x += weapon.getSpec().getHiddenFireOffsets().get(firePointIndex).x;
            firePointLocation.y += weapon.getSpec().getHiddenFireOffsets().get(firePointIndex).y;
        }

        // 转换到世界坐标系
        firePointLocation = VectorUtils.rotate(firePointLocation, weapon.getCurrAngle(), new Vector2f(0f, 0f));
        firePointLocation.x += weapon.getLocation().x;
        firePointLocation.y += weapon.getLocation().y;

        return firePointLocation;
    }
}