package data.scripts.weapons;

import java.awt.Color;

import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import static com.fs.starfarer.api.util.Misc.ZERO;

/**
 * 武器特效插件：充能武器视觉效果
 * 
 * 功能说明：
 * 1. 充能阶段：播放充能音效
 * 2. 开火阶段：在武器开火点生成爆炸、粒子和闪光效果
 * 
 * 基于泰兰船包的RyzaScript修改
 */
public class Moci_RyzaScript_Annotated implements EveryFrameWeaponEffectPlugin {

    // ==================== 视觉效果颜色配置 ====================
    /** 粒子颜色：蓝色 (RGB: 19, 206, 255) */
    private static final Color PARTICLE_COLOR = new Color(19, 206, 255);
    
    /** 光晕颜色：半透明蓝色 (RGB: 75, 159, 255, Alpha: 50) */
    private static final Color GLOW_COLOR = new Color(75, 159, 255, 50);
    
    /** 闪光颜色：白蓝色 (RGB: 227, 255, 253) */
    private static final Color FLASH_COLOR = new Color(227, 255, 253);
    
    /** 开火时生成的粒子数量 */
    private static final int NUM_PARTICLES = 30;

    // ==================== 音效配置 ====================
    /** 充能音效ID */
    private final String CHARGE_SOUND_ID = "tahlan_ryza_charge";

    // ==================== 状态控制变量 ====================
    /** 标记本次充能是否已经开火过（防止重复触发开火效果） */
    private boolean hasFiredThisCharge = false;

    /** 标记是否需要运行一次性逻辑（用于控制音效播放） */
    private boolean runOnce = true;

    /**
     * 武器特效主循环方法
     * 每帧都会被调用
     * 
     * @param amount 时间增量（秒）
     * @param engine 战斗引擎API
     * @param weapon 武器API
     */
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {

        // ==================== 基础检查 ====================
        // 如果游戏暂停或武器不存在，直接返回
        if (engine.isPaused() || weapon == null) {
            return;
        }

        // ==================== 获取武器充能状态 ====================
        // 充能等级：0.0（未充能） 到 1.0（完全充能）
        float chargelevel = weapon.getChargeLevel();

        // ==================== 状态重置逻辑 ====================
        // 如果已经开火过，但现在停止充能或停止开火，则重置状态
        if (hasFiredThisCharge && (chargelevel <= 0f || !weapon.isFiring())) {
            hasFiredThisCharge = false;  // 重置开火标记
            runOnce = true;              // 允许下次播放音效
        }

        // 如果充能等级归零，允许下次播放音效
        if (chargelevel <= 0f) {
            runOnce = true;
        }

        // ==================== 计算武器开火点位置 ====================
        Vector2f point = new Vector2f();

        // 根据武器槽位类型获取开火点偏移量
        if (weapon.getSlot().isHardpoint()) {
            // 硬点武器：使用硬点开火偏移
            point.x = weapon.getSpec().getHardpointFireOffsets().get(0).x;
            point.y = weapon.getSpec().getHardpointFireOffsets().get(0).y;
        } else if (weapon.getSlot().isTurret()) {
            // 炮塔武器：使用炮塔开火偏移
            point.x = weapon.getSpec().getTurretFireOffsets().get(0).x;
            point.y = weapon.getSpec().getTurretFireOffsets().get(0).y;
        } else {
            // 隐藏武器：使用隐藏开火偏移
            point.x = weapon.getSpec().getHiddenFireOffsets().get(0).x;
            point.y = weapon.getSpec().getHiddenFireOffsets().get(0).y;
        }

        // 将相对偏移转换为世界坐标
        // 1. 根据武器当前角度旋转偏移向量
        point = VectorUtils.rotate(point, weapon.getCurrAngle(), new Vector2f(0f, 0f));
        // 2. 加上武器的世界位置
        point.x += weapon.getLocation().x;
        point.y += weapon.getLocation().y;

        // ==================== 充能阶段视觉效果 ====================
        // 如果正在充能且还没有开火过
        if (chargelevel > 0f && !hasFiredThisCharge) {
            // 播放充能音效（只播放一次）
            if (runOnce) {
                Global.getSoundPlayer().playSound(
                    CHARGE_SOUND_ID,           // 音效ID
                    1f,                        // 音量
                    1f,                        // 音调
                    weapon.getLocation(),      // 播放位置
                    weapon.getShip().getVelocity() // 速度（用于多普勒效果）
                );
                runOnce = false; // 标记已播放，防止重复播放
            }
            
            // 注释掉的代码：原本是循环播放音效，音量和音调随充能等级变化
            //Global.getSoundPlayer().playLoop(CHARGE_SOUND_ID, weapon, (0.85f + weapon.getChargeLevel()*2f), (0.6f + (weapon.getChargeLevel() * 0.4f)), weapon.getLocation(), new Vector2f(0f, 0f));
        }

        // ==================== 开火阶段视觉效果 ====================
        // 如果充能完成且还没有触发开火效果
        if (chargelevel >= 1f && !hasFiredThisCharge) {
            hasFiredThisCharge = true; // 标记已开火，防止重复触发

            // 1. 生成主要爆炸效果（蓝色，大范围）
            engine.spawnExplosion(
                point,                    // 位置
                new Vector2f(0f, 0f),    // 速度（静止）
                PARTICLE_COLOR,          // 颜色
                90f,                     // 大小
                0.2f                     // 持续时间
            );
            
            // 2. 生成闪光爆炸效果（白蓝色，中等范围）
            engine.spawnExplosion(
                point,                    // 位置
                new Vector2f(0f, 0f),    // 速度（静止）
                FLASH_COLOR,             // 颜色
                60f,                     // 大小
                0.2f                     // 持续时间
            );
            
            // 3. 添加平滑粒子效果（蓝色光点）
            engine.addSmoothParticle(
                point,                    // 位置
                ZERO,                    // 速度（静止）
                100f,                    // 大小
                0.7f,                    // 亮度
                0.1f,                    // 持续时间
                PARTICLE_COLOR           // 颜色
            );
            
            // 4. 添加大范围光晕效果（半透明蓝色）
            engine.addSmoothParticle(
                point,                    // 位置
                ZERO,                    // 速度（静止）
                200f,                    // 大小
                0.7f,                    // 亮度
                1f,                      // 持续时间
                GLOW_COLOR               // 颜色
            );
            
            // 5. 添加中心闪光效果（白蓝色）
            engine.addSmoothParticle(
                point,                    // 位置
                ZERO,                    // 速度（静止）
                320f,                    // 大小
                1f,                      // 亮度
                0.05f,                   // 持续时间
                FLASH_COLOR              // 颜色
            );
            
            // 6. 生成散射粒子效果（30个小粒子向四周飞散）
            for (int x = 0; x < NUM_PARTICLES; x++) {
                engine.addSmoothParticle(
                    point,                // 起始位置
                    MathUtils.getPointOnCircumference(
                        null,             // 中心点（使用null表示原点）
                        MathUtils.getRandomNumberInRange(50f, 150f), // 随机速度（50-150）
                        (float) Math.random() * 360f                 // 随机方向（0-360度）
                    ),
                    5f,                   // 粒子大小
                    1f,                   // 亮度
                    MathUtils.getRandomNumberInRange(0.3f, 0.6f),    // 随机持续时间（0.3-0.6秒）
                    PARTICLE_COLOR        // 颜色
                );
            }
        }
    }
}
