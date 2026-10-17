package data.scripts.weapons;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ProximityExplosionEffect;
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual;
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual.NEParams;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 重力地雷爆炸效果
 * 
 * 基于RiftCascadeMineExplosion修改，使用白核心橙色边缘的配色方案
 * 
 * 颜色特点：
 * - 边缘颜色：橙色 (255, 140, 0)
 * - 核心颜色：白色 (255, 255, 255)
 * - 底层发光：暖橙色 (255, 100, 0, 120)
 */
public class Moci_GravityMineExplosion implements ProximityExplosionEffect {

    public static String SIZE_MULT_KEY = "gravity_sizeMult";

    // 重力地雷爆炸颜色定义
    public static Color GRAVITY_EXPLOSION_BORDER = new Color(255, 188, 109, 255); // 橙色边缘
    public static Color GRAVITY_EXPLOSION_CORE = new Color(255, 255, 255, 255); // 白色核心
    public static Color GRAVITY_EXPLOSION_UNDERGLOW = new Color(255, 100, 0, 120); // 暖橙色底层发光

    @Override
    public void onExplosion(DamagingProjectileAPI explosion, DamagingProjectileAPI originalProjectile) {
        Float sizeMult = null;
        if (originalProjectile.getCustomData() != null) {
            sizeMult = (Float) originalProjectile.getCustomData().get(SIZE_MULT_KEY);
        }
        if (sizeMult == null)
            sizeMult = 1f;

        // 创建重力地雷专用的爆炸参数
        NEParams p = createGravityMineExplosionParams(25f * sizeMult);
        p.fadeOut = 1f;
        spawnGravityMineExplosion(explosion, p);
    }

    /**
     * 生成重力地雷爆炸效果
     */
    public static void spawnGravityMineExplosion(DamagingProjectileAPI explosion, NEParams params) {
        CombatEngineAPI engine = Global.getCombatEngine();
        explosion.addDamagedAlready(explosion.getSource());

        CombatEntityAPI prev = null;

        // 生成2层爆炸效果，营造深度感
        for (int i = 0; i < 2; i++) {
            NEParams p = copyNEParams(params);
            p.radius *= 0.75f + 0.5f * (float) Math.random();

            // 第一层显示击中发光效果（白色核心）
            p.withHitGlow = prev == null;

            Vector2f loc = new Vector2f(explosion.getLocation());
            // 第二层稍微偏移位置，增加视觉层次
            loc = Misc.getPointAtRadius(loc, p.radius * 0.4f);

            CombatEntityAPI e = engine.addLayeredRenderingPlugin(new NegativeExplosionVisual(p));
            e.getLocation().set(loc);

            // 为第二层添加向外扩散的速度
            if (prev != null) {
                float dist = Misc.getDistance(prev.getLocation(), loc);
                Vector2f vel = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(loc, prev.getLocation()));
                vel.scale(dist / (p.fadeIn + p.fadeOut) * 0.7f);
                e.getVelocity().set(vel);
            }

            prev = e;
        }
    }

    /**
     * 创建重力地雷爆炸参数
     * 使用白核心橙色边缘的配色方案
     */
    public static NEParams createGravityMineExplosionParams(float baseRadius) {
        NEParams p = new NEParams();

        // === 基础参数设置 ===
        p.radius = baseRadius;
        p.fadeIn = 0.1f; // 快速淡入
        p.fadeOut = 1.0f; // 较慢淡出，让爆炸效果更持久

        // === 发光效果设置 ===
        p.hitGlowSizeMult = 0.75f; // 击中发光尺寸倍数
        p.spawnHitGlowAt = 0f; // 立即生成击中发光
        p.withHitGlow = true; // 启用击中发光（白色核心效果）

        // === 噪声效果设置 ===
        p.noiseMag = 1f; // 噪声强度，让边缘更自然

        // === 颜色配置 ===
        p.color = GRAVITY_EXPLOSION_BORDER; // 橙色边缘
        p.underglow = GRAVITY_EXPLOSION_UNDERGLOW; // 暖橙色底层发光

        return p;
    }

    /**
     * 重载方法：根据地雷ID创建爆炸参数
     * 保持与原版RiftCascadeMineExplosion的兼容性
     */
    public static NEParams createGravityMineExplosionParams(String minelayerId, float baseRadius) {
        // 默认使用重力地雷的橙色配色
        Color borderColor = GRAVITY_EXPLOSION_BORDER;

        // 尝试从武器规格中获取颜色（如果需要的话）
        try {
            Object o = Global.getSettings().getWeaponSpec(minelayerId).getProjectileSpec();
            if (o instanceof MissileSpecAPI) {
                MissileSpecAPI spec = (MissileSpecAPI) o;
                Color specColor = spec.getGlowColor();
                if (specColor != null) {
                    // 可以选择使用规格中的颜色，或者保持重力地雷的橙色主题
                    // borderColor = specColor;
                }
            }
        } catch (Exception e) {
            // 如果获取失败，使用默认的橙色
        }

        return createGravityMineExplosionParams(baseRadius);
    }

    /**
     * 复制NEParams对象（因为clone方法是protected的）
     */
    private static NEParams copyNEParams(NEParams original) {
        NEParams copy = new NEParams();

        // 复制所有公共字段
        copy.radius = original.radius;
        copy.fadeIn = original.fadeIn;
        copy.fadeOut = original.fadeOut;
        copy.hitGlowSizeMult = original.hitGlowSizeMult;
        copy.spawnHitGlowAt = original.spawnHitGlowAt;
        copy.withHitGlow = original.withHitGlow;
        copy.noiseMag = original.noiseMag;
        copy.color = original.color;
        copy.underglow = original.underglow;

        return copy;
    }
}