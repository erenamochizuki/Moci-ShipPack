package data.scripts.weapons;

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.util.IntervalUtil;
import org.lwjgl.util.vector.Vector2f;

/**
 * 共晶脉冲光束特效
 * 
 * 支持两种触发模式：
 * - 延迟触发模式：持续照射0.3秒后触发一次，每次射击只触发一次
 * - 间隔触发模式：每隔0.2-0.3秒重复触发，无照射时间限制
 * 
 * 配置方式：修改下方 USE_INTERVAL_MODE 常量
 * 
 * 伤害值从武器配置的 customPrimaryHL 第一个参数读取
 */
public class Moci_eutecticpulse_Effect implements BeamEffectPlugin {

    // ========== 配置区域 ==========
    private static final boolean USE_INTERVAL_MODE = true; // true = 间隔触发模式，false = 延迟触发模式
    private static final float TRIGGER_TIME = 0.3f; // 延迟触发模式的照射时间（秒）
    private static final float DEFAULT_DAMAGE = 300f; // 默认伤害值（如果无法从配置读取）
    // ==============================

    private float elapsedTime = 0f; // 照射累计时间
    private final IntervalUtil fireInterval = new IntervalUtil(0.2f, 0.3f); // 触发间隔计时器
    private boolean done = false; // 延迟模式的触发标记
    private CombatEntityAPI lastTarget = null; // 上一帧的目标
    private boolean wasZero = true; // 用于处理时间加速/减速
    private Float cachedDamage = null; // 缓存的伤害值

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        // 首次运行时从武器配置读取伤害值
        if (cachedDamage == null) {
            cachedDamage = getDamageFromWeaponSpec(beam);
        }
        
        if (USE_INTERVAL_MODE) {
            advanceIntervalMode(engine, beam);
        } else {
            advanceDelayMode(engine, beam);
        }
    }
    
    /**
     * 从武器配置的 customPrimaryHL 参数读取伤害值
     * customPrimaryHL 格式：250 | 动能 | 硬幅能
     * 返回第一个参数作为伤害值
     */
    private float getDamageFromWeaponSpec(BeamAPI beam) {
        try {
            if (beam.getWeapon() != null && beam.getWeapon().getSpec() != null) {
                String customPrimaryHL = beam.getWeapon().getSpec().getCustomPrimaryHL();
                if (customPrimaryHL != null && !customPrimaryHL.isEmpty()) {
                    // 按 | 分割字符串
                    String[] parts = customPrimaryHL.split("\\|");
                    if (parts.length > 0) {
                        // 获取第一个参数并去除空格
                        String damageStr = parts[0].trim();
                        return Float.parseFloat(damageStr);
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败时使用默认值
        }
        return DEFAULT_DAMAGE;
    }
    
    // 延迟触发模式：持续照射0.3秒后触发一次
    private void advanceDelayMode(CombatEngineAPI engine, BeamAPI beam) {
        if (done) return;
        
        CombatEntityAPI target = beam.getDamageTarget();
        
        if (target != null && beam.getBrightness() >= 1f) {
            if (lastTarget != target) {
                elapsedTime = 0f;
                lastTarget = target;
            }
            
            float dur = beam.getDamage().getDpsDuration();
            if (dur > 0) {
                elapsedTime += dur;
            }
            
            if (elapsedTime >= TRIGGER_TIME) {
                applyDamage(engine, beam, target);
                done = true;
            }
        } else {
            elapsedTime = 0f;
            lastTarget = null;
        }
    }
    
    // 间隔触发模式：每隔一定时间重复触发
    private void advanceIntervalMode(CombatEngineAPI engine, BeamAPI beam) {
        CombatEntityAPI target = beam.getDamageTarget();
        
        if (target != null && beam.getBrightness() >= 1f) {
            float dur = beam.getDamage().getDpsDuration();
            
            if (!wasZero) dur = 0;
            wasZero = beam.getDamage().getDpsDuration() <= 0;
            
            fireInterval.advance(dur);
            
            if (fireInterval.intervalElapsed()) {
                applyDamage(engine, beam, target);
            }
        }
    }
    
    // 对目标造成伤害
    private void applyDamage(CombatEngineAPI engine, BeamAPI beam, CombatEntityAPI target) {
        Vector2f point = beam.getTo();
        
        engine.applyDamage(
            target,
            point,
            cachedDamage,
            DamageType.KINETIC,
            0f,
            false,
            false,
            beam.getSource()
        );
    }
}