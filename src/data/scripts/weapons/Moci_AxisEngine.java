package data.scripts.weapons;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 阿克西斯引擎发光控制器（仅控制透明度与光源，不控制帧动画）
 *
 * 行为规则：
 * 1. 无移动命令时：先平滑回落到待机最低值 0.2，再在 0.2~0.3 之间随机跳变（故意不平滑）。
 * 2. 前进命令时：平滑提升到 1.0。
 * 3. 后退或减速命令时：平滑提升到 0.75。
 * 4. 同时出现前进与后退相关命令时，优先前进逻辑。
 */
public class Moci_AxisEngine implements EveryFrameWeaponEffectPlugin {

    // ===== 亮度目标参数 =====
    // 待机状态下的最小透明度（也是最低发光基准）。
    private static final float IDLE_MIN = 0.2f;
    // 待机状态下的最大透明度；与 IDLE_MIN 共同决定随机抖动范围。
    private static final float IDLE_MAX = 0.3f;
    // 前进时目标透明度（满亮）。
    private static final float FORWARD_TARGET = 1.0f;
    // 后退/减速时目标透明度（次高亮）。
    private static final float BACKWARD_TARGET = 0.75f;

    // ===== 过渡速度参数 =====
    // 平滑追踪速度（单位：每秒可变化的透明度幅度）。
    // 数值越大，响应越快；越小，过渡越柔和。
    private static final float RISE_SPEED = 1.0f;
    // 命令结束后回落到待机最低值时的平滑速度。
    private static final float FALL_SPEED = 1.0f;

    // ===== 光源映射参数 =====
    // 光强 = LIGHT_INTENSITY_BASE + alpha * LIGHT_INTENSITY_MULT
    private static final float LIGHT_INTENSITY_BASE = 0.3f;
    private static final float LIGHT_INTENSITY_MULT = 1.2f;
    // 光源半径（LARGE） = base + alpha * mult（维持当前表现）。
    private static final float LIGHT_SIZE_BASE_LARGE = 120f;
    private static final float LIGHT_SIZE_MULT_LARGE = 260f;
    // 光源半径（MEDIUM）参数。
    private static final float LIGHT_SIZE_BASE_MEDIUM = 60f;
    private static final float LIGHT_SIZE_MULT_MEDIUM = 130f;
    // 光源半径（SMALL）参数。
    private static final float LIGHT_SIZE_BASE_SMALL = 30f;
    private static final float LIGHT_SIZE_MULT_SMALL = 65f;

    // 武器tag：按尺寸档位选择光源尺寸；未命中时默认LARGE。
    private static final String TAG_ENGINE_SMALL = "MOCI_ENGINE_SMALL";
    private static final String TAG_ENGINE_MEDIUM = "MOCI_MEDUIM";
    private static final String TAG_ENGINE_MEDIUM_ALT = "MOCI_ENGINE_MEDIUM";
    private static final String TAG_ENGINE_LARGE = "MOCI_ENGINE_LARGE";

    // 待机随机刷新计时器：用较短间隔制造“跳变感”，避免平滑呼吸效果。
    private final IntervalUtil idleRandomTimer = new IntervalUtil(0.06f, 0.12f);

    // 是否完成一次性初始化（读取颜色、创建光源等）。
    private boolean initialized = false;
    // 当前显示透明度/亮度（最终写入精灵与光源）。
    private float currentAlpha = IDLE_MIN;
    // 命令驱动的平滑透明度（前进/后退/回落到待机最低值都由它统一管理）。
    private float smoothedAlpha = IDLE_MIN;
    // 待机随机跳变值（仅在完全待机时直接写入显示，不参与平滑）。
    private float idleJumpAlpha = IDLE_MIN;
    // ShaderLib 的动态光源列表：按炮塔开火点一一对应。
    private final List<StandardLight> lights = new ArrayList<StandardLight>();
    // 当前武器使用的光源尺寸参数（由tag决定，默认LARGE）。
    private float lightSizeBase = LIGHT_SIZE_BASE_LARGE;
    private float lightSizeMult = LIGHT_SIZE_MULT_LARGE;
    // 光源颜色来源于武器 spec 的 glowColor；为 null 时使用默认光源色。
    private Color glowColor;
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础安全检查：引擎暂停或对象无效时不更新。
        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        // 宿主舰船无效/死亡时清理光源，避免残留。
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            cleanupLight();
            return;
        }

        // 首帧初始化：缓存颜色并尝试创建永久光源。
        if (!initialized) {
            initialize(weapon);
            initialized = true;
        }

        ShipEngineControllerAPI controller = ship.getEngineController();
        boolean accelerating = controller != null && controller.isAccelerating();
        boolean backward = controller != null
                && (controller.isDecelerating() || controller.isAcceleratingBackwards());

        // 命令优先级：前进 > 后退/减速 > 待机。
        if (accelerating) {
            // 命令开始时从“当前显示值”接管，避免从待机跳变值切命令时出现突变。
            smoothedAlpha = approach(currentAlpha, FORWARD_TARGET, RISE_SPEED * amount);
            currentAlpha = smoothedAlpha;
        } else if (backward) {
            smoothedAlpha = approach(currentAlpha, BACKWARD_TARGET, RISE_SPEED * amount);
            currentAlpha = smoothedAlpha;
        } else {
            // 无命令时，统一平滑回落到待机最低值。
            smoothedAlpha = approach(smoothedAlpha, IDLE_MIN, FALL_SPEED * amount);
            if (Math.abs(smoothedAlpha - IDLE_MIN) <= 0.0001f) {
                smoothedAlpha = IDLE_MIN;

                // 到达最低值后进入待机随机跳变（故意不平滑）。
                idleRandomTimer.advance(amount);
                if (idleRandomTimer.intervalElapsed()) {
                    idleJumpAlpha = randomIdleAlpha();
                }
                currentAlpha = idleJumpAlpha;
            } else {
                currentAlpha = smoothedAlpha;
            }
        }

        // 强制边界，避免异常值导致过亮/过暗。
        currentAlpha = clamp(currentAlpha, IDLE_MIN, FORWARD_TARGET);
        updateWeaponAlpha(weapon, currentAlpha);
        updateLight(weapon, currentAlpha);
    }

    /**
     * 初始化：读取发光颜色、设置初始亮度、注册永久光源。
     */
    private void initialize(WeaponAPI weapon) {
        glowColor = weapon.getSpec() != null ? weapon.getSpec().getGlowColor() : null;
        idleJumpAlpha = randomIdleAlpha();
        currentAlpha = idleJumpAlpha;
        smoothedAlpha = IDLE_MIN;
        configureLightSizeFromTags(weapon);
        createLights(weapon);
    }

    /**
     * 根据武器tag选择光源尺寸档位；默认LARGE。
     */
    private void configureLightSizeFromTags(WeaponAPI weapon) {
        lightSizeBase = LIGHT_SIZE_BASE_LARGE;
        lightSizeMult = LIGHT_SIZE_MULT_LARGE;

        if (weapon == null || weapon.getSpec() == null) {
            return;
        }

        if (weapon.getSpec().hasTag(TAG_ENGINE_SMALL)) {
            lightSizeBase = LIGHT_SIZE_BASE_SMALL;
            lightSizeMult = LIGHT_SIZE_MULT_SMALL;
            return;
        }

        if (weapon.getSpec().hasTag(TAG_ENGINE_MEDIUM) || weapon.getSpec().hasTag(TAG_ENGINE_MEDIUM_ALT)) {
            lightSizeBase = LIGHT_SIZE_BASE_MEDIUM;
            lightSizeMult = LIGHT_SIZE_MULT_MEDIUM;
            return;
        }

        if (weapon.getSpec().hasTag(TAG_ENGINE_LARGE)) {
            lightSizeBase = LIGHT_SIZE_BASE_LARGE;
            lightSizeMult = LIGHT_SIZE_MULT_LARGE;
        }
    }

    /**
     * 创建动态光源：炮塔按每个开火点创建一个，否则创建一个默认光源。
     */
    private void createLights(WeaponAPI weapon) {
        cleanupLight();
        int count = getLightCount(weapon);

        try {
            for (int i = 0; i < count; i++) {
                Vector2f location = getLightLocation(weapon, i);
                // 注意：attach 到 ship 后 setLocation() 会被忽略，位置会锁到舰体中心 + offset。
                // 这里必须使用未附着光源，才能每帧精确跟随炮口世界坐标。
                StandardLight light = new StandardLight(location, new Vector2f(), new Vector2f(), null);
                light.makePermanent();
                LightShader.addLight(light);
                lights.add(light);
            }
        } catch (Exception ex) {
            cleanupLight();
        }
    }

    /**
     * 将 alpha（0~1）写入武器精灵透明度，保留原始 RGB。
     */
    private void updateWeaponAlpha(WeaponAPI weapon, float alpha01) {
        if (weapon.getSprite() == null) {
            return;
        }

        Color current = weapon.getSprite().getColor();
        if (current == null) {
            current = Color.WHITE;
        }

        int alpha = (int) (clamp(alpha01, 0f, 1f) * 255f);
        weapon.getSprite().setColor(new Color(
                current.getRed(),
                current.getGreen(),
                current.getBlue(),
                alpha));
    }

    /**
     * 同步更新动态光源（颜色、强度、大小、位置）。
     */
    private void updateLight(WeaponAPI weapon, float alpha01) {
        if (lights.isEmpty()) {
            return;
        }

        float intensity = LIGHT_INTENSITY_BASE + alpha01 * LIGHT_INTENSITY_MULT;
        float size = lightSizeBase + alpha01 * lightSizeMult;

        for (int i = 0; i < lights.size(); i++) {
            StandardLight light = lights.get(i);
            if (glowColor != null) {
                light.setColor(glowColor);
            }
            light.setIntensity(intensity);
            light.setSize(size);
            light.setLocation(getLightLocation(weapon, i));
        }
    }

    /**
     * 清理动态光源，防止舰船销毁后仍保留光照对象。
     */
    private void cleanupLight() {
        for (StandardLight light : lights) {
            LightShader.removeLight(light);
        }
        lights.clear();
    }

    /**
     * 计算光源数量：炮塔按开火点数量创建，否则1个。
     */
    private int getLightCount(WeaponAPI weapon) {
        if (weapon == null || weapon.getSlot() == null || !weapon.getSlot().isTurret()
                || weapon.getSpec() == null || weapon.getSpec().getTurretFireOffsets() == null
                || weapon.getSpec().getTurretFireOffsets().isEmpty()) {
            return 1;
        }
        return weapon.getSpec().getTurretFireOffsets().size();
    }

    /**
     * 获取指定索引光源的位置：炮塔使用对应开火点；否则使用默认开火点/武器位置兜底。
     */
    private Vector2f getLightLocation(WeaponAPI weapon, int index) {
        if (weapon == null) {
            return new Vector2f();
        }

        int firePointIndex = 0;
        if (weapon.getSlot() != null && weapon.getSlot().isTurret()) {
            int count = getLightCount(weapon);
            firePointIndex = Math.max(0, Math.min(index, count - 1));
        }

        Vector2f calculated = calculateFirePointLocation(weapon, firePointIndex);
        if (calculated != null) {
            return calculated;
        }

        Vector2f fallbackFirePoint = weapon.getFirePoint(firePointIndex);
        if (fallbackFirePoint != null) {
            return new Vector2f(fallbackFirePoint);
        }
        if (weapon.getLocation() != null) {
            return new Vector2f(weapon.getLocation());
        }
        return new Vector2f();
    }

    /**
     * 参考 Moci_MagicAnimationGun：根据开火偏移计算指定索引的世界坐标。
     */
    private Vector2f calculateFirePointLocation(WeaponAPI weapon, int firePointIndex) {
        if (weapon == null || weapon.getSlot() == null || weapon.getSpec() == null || weapon.getLocation() == null) {
            return null;
        }

        Vector2f firePointLocation = new Vector2f(0f, 0f);
        Vector2f offset = getFireOffsetForIndex(weapon, firePointIndex);
        if (offset == null) {
            return null;
        }

        firePointLocation.x += offset.x;
        firePointLocation.y += offset.y;
        firePointLocation = VectorUtils.rotate(firePointLocation, weapon.getCurrAngle(), new Vector2f(0f, 0f));
        firePointLocation.x += weapon.getLocation().x;
        firePointLocation.y += weapon.getLocation().y;
        return firePointLocation;
    }

    /**
     * 读取指定槽位类型的开火偏移；索引越界时回退到0号偏移。
     */
    private Vector2f getFireOffsetForIndex(WeaponAPI weapon, int firePointIndex) {
        if (weapon == null || weapon.getSpec() == null || weapon.getSlot() == null) {
            return null;
        }

        List<Vector2f> offsets;
        if (weapon.getSlot().isHardpoint()) {
            offsets = weapon.getSpec().getHardpointFireOffsets();
        } else if (weapon.getSlot().isTurret()) {
            offsets = weapon.getSpec().getTurretFireOffsets();
        } else {
            offsets = weapon.getSpec().getHiddenFireOffsets();
        }

        if (offsets == null || offsets.isEmpty()) {
            return null;
        }
        int safeIndex = Math.max(0, Math.min(firePointIndex, offsets.size() - 1));
        Vector2f offset = offsets.get(safeIndex);
        return offset == null ? null : new Vector2f(offset);
    }

    /**
     * 生成待机随机亮度，范围 [IDLE_MIN, IDLE_MAX]。
     */
    private float randomIdleAlpha() {
        return IDLE_MIN + (float) Math.random() * (IDLE_MAX - IDLE_MIN);
    }

    /**
     * 线性逼近函数：每帧最多变化 maxDelta，避免瞬间跳到目标。
     */
    private float approach(float current, float target, float maxDelta) {
        if (current < target) {
            return Math.min(current + maxDelta, target);
        }
        return Math.max(current - maxDelta, target);
    }

    /**
     * 数值钳制到 [min, max]。
     */
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
