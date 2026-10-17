package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ProximityExplosionEffect;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;

/**
 * 大和主炮高爆弹近炸爆炸特效
 * 
 * 这个插件在高爆弹（三式融合弹）触发近炸引信时添加自定义爆炸特效
 * 
 * 功能：
 * - 捕获近炸弹药的爆炸事件
 * - 添加多层烟雾效果（白色+灰色）
 * - 添加火花粒子飞溅效果
 * - 添加中心闪光
 * - 播放音效
 */
public class Moci_YamatoHEExplosionEffect implements ProximityExplosionEffect {

    // ========== 爆炸特效配置 ==========

    // --- 中心闪光配置 ---
    private static final Color FLASH_COLOR_WHITE = new Color(255, 255, 255, 155); // 白色闪光
    private static final Color FLASH_COLOR_ORANGE = new Color(255, 150, 50, 100); // 橙色闪光
    private static final float FLASH_SIZE = 150f; // 闪光大小
    private static final float FLASH_DURATION = 0.1f; // 闪光持续时间

    // --- 白色烟雾配置（内层）---
    private static final Color SMOKE_WHITE = new Color(255, 60, 0, 100); // 白色烟雾
    private static final float SMOKE_WHITE_SIZE_MIN = 80f; // 白色烟雾最小大小
    private static final float SMOKE_WHITE_SIZE_MAX = 120f; // 白色烟雾最大大小
    private static final float SMOKE_WHITE_DURATION = 0.5f; // 白色烟雾持续时间
    private static final int SMOKE_WHITE_COUNT = 10; // 白色烟雾数量

    // --- 灰色烟雾配置（外层）---
    private static final Color SMOKE_GRAY = new Color(45, 45, 45, 150); // 灰色烟雾
    private static final float SMOKE_GRAY_SIZE_MIN = 100f; // 灰色烟雾最小大小
    private static final float SMOKE_GRAY_SIZE_MAX = 150f; // 灰色烟雾最大大小
    private static final float SMOKE_GRAY_DURATION = 0.75f; // 灰色烟雾持续时间
    private static final int SMOKE_GRAY_COUNT = 10; // 灰色烟雾数量
//
//    // --- 黑色烟雾配置（负面粒子，虚空效果）---
//    private static final Color SMOKE_BLACK = new Color(100, 100, 100, 75); // 黑色烟雾
//    private static final float SMOKE_BLACK_SIZE_MIN = 120f; // 黑色烟雾最小大小
//    private static final float SMOKE_BLACK_SIZE_MAX = 155f; // 黑色烟雾最大大小
//    private static final float SMOKE_BLACK_DURATION = 1.0f; // 黑色烟雾持续时间（更持久）
//    private static final int SMOKE_BLACK_COUNT = 4; // 黑色烟雾数量

    // --- 火花粒子配置 ---
    private static final Color SPARK_COLOR = new Color(255, 200, 100, 255); // 金黄色火花
    private static final float SPARK_SIZE_MIN = 8f; // 火花最小大小
    private static final float SPARK_SIZE_MAX = 20f; // 火花最大大小
    private static final float SPARK_VELOCITY_MIN = 50; // 火花最小速度
    private static final float SPARK_VELOCITY_MAX = 100f; // 火花最大速度
    private static final float SPARK_DURATION_MIN = 0.3f; // 火花最短持续时间
    private static final float SPARK_DURATION_MAX = 0.8f; // 火花最长持续时间
    private static final int SPARK_COUNT = 100; // 火花数量


    /**
     * 近炸爆炸事件处理方法
     * 当高爆弹触发近炸引信时自动调用
     * 
     * @param explosion          爆炸伤害实体（用于造成范围伤害）
     * @param originalProjectile 原始弹丸实体（包含位置、速度等信息）
     */
    @Override
    public void onExplosion(DamagingProjectileAPI explosion, DamagingProjectileAPI originalProjectile) {
        // 获取战斗引擎
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }

        // 获取爆炸位置和弹丸速度
        Vector2f location = new Vector2f(originalProjectile.getLocation());
        Vector2f velocity = originalProjectile.getVelocity();

        // 零速度向量（用于静止粒子）
        Vector2f zeroVel = new Vector2f(0, 0);

        // ==================== 生成爆炸特效 ====================

        // 1. 中心白色闪光（最亮）
        engine.addSmoothParticle(location, zeroVel, FLASH_SIZE * 0.8f, 2.5f, FLASH_DURATION * 0.6f, FLASH_COLOR_WHITE);

        // 2. 橙色火球闪光
        engine.addSmoothParticle(location, zeroVel, FLASH_SIZE, 1.8f, FLASH_DURATION, FLASH_COLOR_ORANGE);

        // 3. 外圈橙色光晕
        engine.addSmoothParticle(location, zeroVel, FLASH_SIZE * 1.3f, 1.2f, FLASH_DURATION * 1.2f, FLASH_COLOR_ORANGE);

        // 4. 白色烟雾层（内层，使用星云烟雾）
        for (int i = 0; i < SMOKE_WHITE_COUNT; i++) {
            float size = MathUtils.getRandomNumberInRange(SMOKE_WHITE_SIZE_MIN, SMOKE_WHITE_SIZE_MAX);
            float duration = SMOKE_WHITE_DURATION + (float) Math.random() * 0.3f;
            float angle = (float) Math.random() * 360f;
            float speed = MathUtils.getRandomNumberInRange(50f, 150f);

            Vector2f smokeVel = MathUtils.getPointOnCircumference(zeroVel, speed, angle);

            engine.addNebulaParticle(location, smokeVel, size, 2.0f, 0.1f, 0.3f, duration, SMOKE_WHITE);
        }

        // 5. 灰色烟雾层（外层，使用星云烟雾）
        for (int i = 0; i < SMOKE_GRAY_COUNT; i++) {
            float size = MathUtils.getRandomNumberInRange(SMOKE_GRAY_SIZE_MIN, SMOKE_GRAY_SIZE_MAX);
            float duration = SMOKE_GRAY_DURATION + (float) Math.random() * 0.5f;
            float angle = (float) Math.random() * 360f;
            float speed = MathUtils.getRandomNumberInRange(30f, 100f);

            Vector2f smokeVel = MathUtils.getPointOnCircumference(zeroVel, speed, angle);

            engine.addNebulaParticle(location, smokeVel, size, 1.5f, 0.1f, 0.4f, duration, SMOKE_GRAY);
        }
//
//        // 5.5. 黑色烟雾层（负面星云粒子，吸光效果）
//        for (int i = 0; i < SMOKE_BLACK_COUNT; i++) {
//            float size = MathUtils.getRandomNumberInRange(SMOKE_BLACK_SIZE_MIN, SMOKE_BLACK_SIZE_MAX);
//            float duration = SMOKE_BLACK_DURATION + (float) Math.random() * 0.3f;
//            float angle = (float) Math.random() * 360f;
//            float speed = MathUtils.getRandomNumberInRange(50f, 75f); // 较慢的速度，让黑烟更集中
//
//            Vector2f smokeVel = MathUtils.getPointOnCircumference(zeroVel, speed, angle);
//
//            // 使用负面星云粒子创建黑色烟雾效果
//            engine.addNegativeNebulaParticle(location, smokeVel, size, 1.2f, 0.2f, 0.5f, duration, SMOKE_BLACK);
//        }

        // 6. 火花粒子（360度飞溅）
        for (int i = 0; i < SPARK_COUNT; i++) {
            float size = MathUtils.getRandomNumberInRange(SPARK_SIZE_MIN, SPARK_SIZE_MAX);
            float speed = MathUtils.getRandomNumberInRange(SPARK_VELOCITY_MIN, SPARK_VELOCITY_MAX);
            float duration = MathUtils.getRandomNumberInRange(SPARK_DURATION_MIN, SPARK_DURATION_MAX);
            float angle = (float) Math.random() * 360f;

            Vector2f sparkVel = MathUtils.getPointOnCircumference(zeroVel, speed, angle);

            // 使用addSmoothParticle创建明亮的火花效果
            engine.addSmoothParticle(location, sparkVel, size, 1.5f, duration, SPARK_COLOR);
        }

        // ==================== 播放音效 ====================
        // 使用重型能量武器命中音效
        Global.getSoundPlayer().playSound("hit_heavy_energy", 1.0f, 1.0f, location, zeroVel);

    }
}
