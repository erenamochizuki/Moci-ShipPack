package data.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.WaveDistortion;
import org.dark.shaders.util.ShaderLib;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Moci弹丸扭曲效果插件
 * 
 * 功能：为特定弹丸添加持续的波动扭曲效果（类似GraphicsLib的mjolnir_shot效果）
 * 
 * 特点：
 * - 只处理弹丸扭曲效果，不干涉护盾涟漪（由GraphicsLib自动处理）
 * - 扭曲效果跟随弹丸移动
 * - 弹丸击中目标或消失时自动淡出
 * - 支持自定义弹丸ID和效果参数
 * 
 * 使用方法：
 * 1. 在 addProjectileDistortion() 方法中添加你的弹丸ID
 * 2. 根据需要调整扭曲参数（强度、大小、方向）
 * 3. 在 Moci_bounty_plugin 中注册此插件
 * 
 * 依赖：
 * - GraphicsLib（提供扭曲效果API）
 * - LazyLib（提供数学工具）
 * 
 * @author Moci
 * @version 1.0
 */
public class Moci_ProjectileDistortionPlugin extends BaseEveryFrameCombatPlugin {

    // 数据存储键，用于在战斗引擎中存储弹丸追踪数据
    private static final String DATA_KEY = "Moci_ProjectileDistortions";
    
    // 零向量常量，用于创建静止的扭曲效果
    private static final Vector2f ZERO = new Vector2f();

    // 战斗引擎引用
    private CombatEngineAPI engine;

    /**
     * 插件初始化
     * 在战斗开始时调用，创建数据存储容器
     */
    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        // 创建弹丸追踪Map，初始容量500（根据实际弹丸数量调整）
        engine.getCustomData().put(DATA_KEY, new HashMap<DamagingProjectileAPI, WaveDistortion>(500));
    }

    /**
     * 每帧更新逻辑
     * 
     * 处理流程：
     * 1. 检查前置条件（引擎状态、Shader支持）
     * 2. 扫描新弹丸，为符合条件的弹丸创建扭曲效果
     * 3. 更新现有扭曲效果的位置
     * 4. 清理已消失或击中目标的弹丸
     */
    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        // 基础检查：引擎是否存在、游戏是否暂停
        if (engine == null || engine.isPaused()) {
            return;
        }

        // Shader检查：确保显卡支持扭曲效果
        if (!ShaderLib.areShadersAllowed() || !ShaderLib.areBuffersAllowed()) {
            return;
        }

        // 获取弹丸追踪数据
        @SuppressWarnings("unchecked")
        Map<DamagingProjectileAPI, WaveDistortion> trackedProjectiles = 
            (Map<DamagingProjectileAPI, WaveDistortion>) engine.getCustomData().get(DATA_KEY);

        // ========================================
        // 第一阶段：扫描新弹丸并创建扭曲效果
        // ========================================
        List<DamagingProjectileAPI> projectiles = engine.getProjectiles();
        for (DamagingProjectileAPI proj : projectiles) {
            // 跳过已经造成伤害的弹丸（避免重复处理）
            if (proj.didDamage()) {
                continue;
            }
            
            // 跳过存在时间过长的弹丸（0.2秒后不再创建新效果，优化性能）
            if (proj.getElapsed() > 0.2f) {
                continue;
            }

            // 获取弹丸ID
            String projId = proj.getProjectileSpecId();
            if (projId == null) {
                continue;
            }

            // 如果弹丸尚未被追踪，尝试为其创建扭曲效果
            if (!trackedProjectiles.containsKey(proj)) {
                WaveDistortion wave = addProjectileDistortion(proj, projId);
                if (wave != null) {
                    // 将扭曲效果添加到渲染器
                    DistortionShader.addDistortion(wave);
                    // 记录弹丸和对应的扭曲效果
                    trackedProjectiles.put(proj, wave);
                }
            }
        }

        // ========================================
        // 第二阶段：更新和清理扭曲效果
        // ========================================
        Iterator<Map.Entry<DamagingProjectileAPI, WaveDistortion>> iter = 
            trackedProjectiles.entrySet().iterator();
        
        while (iter.hasNext()) {
            Map.Entry<DamagingProjectileAPI, WaveDistortion> entry = iter.next();
            DamagingProjectileAPI proj = entry.getKey();
            WaveDistortion wave = entry.getValue();

            // 情况1：弹丸击中目标或已从游戏中移除
            if (proj.didDamage() || !engine.isEntityInPlay(proj)) {
                // 开始淡出效果（0.2秒内淡出）
                if (!wave.isFading()) {
                    wave.fadeOutIntensity(0.2f);
                }
                // 从追踪列表中移除
                iter.remove();
            } 
            // 情况2：弹丸正在消失（超出范围或生命周期结束）
            else if (proj.isFading()) {
                // 开始淡出效果
                if (!wave.isFading()) {
                    wave.fadeOutIntensity(0.2f);
                }
            } 
            // 情况3：弹丸正常飞行，更新扭曲效果位置
            else {
                wave.setLocation(proj.getLocation());
            }
        }
    }

    /**
     * 为弹丸创建扭曲效果
     * 
     * 使用与 GraphicsLib 的 mjolnir_shot 相同的参数
     * 保持视觉效果的一致性
     * 
     * @param proj 弹丸对象
     * @param projId 弹丸ID（来自weapon_data.csv的proj spec id列）
     * @return 创建的扭曲效果，如果不支持该弹丸则返回null
     */
    private WaveDistortion addProjectileDistortion(DamagingProjectileAPI proj, String projId) {
        // ========================================
        // Moci 模组弹丸扭曲效果配置
        // 使用与 GraphicsLib mjolnir_shot 相同的参数
        // ========================================
        
        // 检查是否是支持的弹丸
        if (projId != null && 
            (projId.equals("Moci_mjolnir_shot") || 
             projId.equals("Moci_Yamato_maingun_shot") || 
             projId.equals("Moci_Yamato_ET_maingun_shot"))) {
            
            // 创建扭曲效果（与 GraphicsLib 的 mjolnir_shot 相同参数）
            WaveDistortion wave = new WaveDistortion(proj.getLocation(), ZERO);
            wave.setIntensity(5f);   // 强度：5（与 mjolnir_shot 相同）
            wave.setSize(50f);       // 范围：50像素（与 mjolnir_shot 相同）
            wave.flip(true);         // 向内扭曲（与 mjolnir_shot 相同）
            
            return wave;
        }
        
        // 不支持的弹丸类型，返回null（不创建扭曲效果）
        return null;
    }

    /**
     * 扭曲效果参数说明：
     * 
     * setIntensity(float intensity)
     * - 扭曲强度，控制扭曲的明显程度
     * - 推荐范围：3-10
     * - 过高会导致画面过度扭曲，影响视觉体验
     * 
     * setSize(float size)
     * - 扭曲范围（像素）
     * - 推荐范围：30-100
     * - 应根据弹丸大小和视觉效果需求调整
     * 
     * flip(boolean flip)
     * - 扭曲方向
     * - true：向内扭曲（收缩效果）
     * - false：向外扭曲（膨胀效果）
     * 
     * fadeOutIntensity(float duration)
     * - 淡出时间（秒）
     * - 推荐：0.2-0.5秒
     * - 过短会显得突兀，过长会影响性能
     */
}
