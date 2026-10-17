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
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 阿克西斯之光循环控制器（仅控制透明度与光源，不控制帧动画）
 *
 * 行为规则：
 * 1. 自动做周期循环，不依赖舰船命令。
 * 2. 透明度/发光在 0.2~1.0 之间往返变化。
 */
public class Moci_AxisLight implements EveryFrameWeaponEffectPlugin {

    // ===== 循环亮度范围 =====
    // 循环下限：最低可见亮度。
    private static final float MIN_ALPHA = 0f;
    // 循环上限：最高亮度。
    private static final float MAX_ALPHA = 1f;
    // 完成一个完整周期（亮->暗->亮）所需秒数。
    // 数值越小，闪烁越快；越大，呼吸越慢。
    private static final float FULL_CYCLE_SECONDS = 10f;

    // ===== 光源映射参数 =====
    // 光强 = LIGHT_INTENSITY_BASE + alpha * LIGHT_INTENSITY_MULT
    private static final float LIGHT_INTENSITY_BASE = 0.3f;
    private static final float LIGHT_INTENSITY_MULT = 0.75f;
    // 光源半径 = LIGHT_SIZE_BASE + alpha * LIGHT_SIZE_MULT
    private static final float LIGHT_SIZE_BASE = 50f;
    private static final float LIGHT_SIZE_MULT = 125f;

    // 是否完成一次性初始化（随机初相位、创建光源）。
    private boolean initialized = false;
    // 当前正弦相位（弧度），决定本帧循环位置。
    private float phase = 0f;
    // ShaderLib 动态光源对象（按开火点一一对应）。
    private final List<StandardLight> lights = new ArrayList<StandardLight>();
    // 从武器 spec 获取的 glowColor，用作光源颜色。
    private Color glowColor;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 引擎暂停或对象无效时直接跳过。
        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        // 舰船失效时移除光源，避免残留。
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            cleanupLight();
            return;
        }

        // 首帧初始化。
        if (!initialized) {
            initialize(weapon);
            initialized = true;
        }

        // 角速度：2π / 周期秒数。
        float omega = (float) ((Math.PI * 2.0) / FULL_CYCLE_SECONDS);
        phase += amount * omega;
        if (phase > Math.PI * 2.0f) {
            phase -= (float) (Math.PI * 2.0);
        }

        // 通过正弦把相位映射为 [0,1]，再映射到 [MIN_ALPHA, MAX_ALPHA]。
        float t = 0.5f + 0.5f * (float) Math.sin(phase);
        float alpha = MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * t;

        updateWeaponAlpha(weapon, alpha);
        updateLight(weapon, alpha);
    }

    /**
     * 初始化：读取颜色、随机循环起点、注册永久光源。
     */
    private void initialize(WeaponAPI weapon) {
        glowColor = weapon.getSpec() != null ? weapon.getSpec().getGlowColor() : null;
        // 给每个实例随机起始相位，避免同舰多个灯完全同频。
        phase = (float) (Math.random() * Math.PI * 2.0);
        createLights(weapon);
    }

    /**
     * 创建动态光源：按开火点数量创建，且不附着到 ship（确保 setLocation 生效）。
     */
    private void createLights(WeaponAPI weapon) {
        cleanupLight();
        int count = getLightCount(weapon);
        try {
            for (int i = 0; i < count; i++) {
                Vector2f location = getLightLocation(weapon, i);
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
     * 写入武器透明度（只改 alpha，不改 RGB）。
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
     * 根据 alpha 同步动态光源的强度、尺寸与位置。
     */
    private void updateLight(WeaponAPI weapon, float alpha01) {
        if (lights.isEmpty()) {
            return;
        }

        float intensity = LIGHT_INTENSITY_BASE + alpha01 * LIGHT_INTENSITY_MULT;
        float size = LIGHT_SIZE_BASE + alpha01 * LIGHT_SIZE_MULT;

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
     * 卸载光源对象。
     */
    private void cleanupLight() {
        for (StandardLight light : lights) {
            LightShader.removeLight(light);
        }
        lights.clear();
    }

    /**
     * 计算光源数量：默认按当前槽位对应的开火点数量创建。
     */
    private int getLightCount(WeaponAPI weapon) {
        if (weapon == null || weapon.getSlot() == null || weapon.getSpec() == null) {
            return 1;
        }

        List<Vector2f> offsets;
        if (weapon.getSlot().isHardpoint()) {
            offsets = weapon.getSpec().getHardpointFireOffsets();
        } else if (weapon.getSlot().isTurret()) {
            offsets = weapon.getSpec().getTurretFireOffsets();
        } else {
            offsets = weapon.getSpec().getHiddenFireOffsets();
        }

        return (offsets == null || offsets.isEmpty()) ? 1 : offsets.size();
    }

    /**
     * 获取指定索引光源的位置：优先按 offset 计算开火点世界坐标。
     */
    private Vector2f getLightLocation(WeaponAPI weapon, int index) {
        if (weapon == null) {
            return new Vector2f();
        }

        int count = getLightCount(weapon);
        int firePointIndex = Math.max(0, Math.min(index, count - 1));

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
     * 数值钳制到 [min, max] 区间。
     */
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
