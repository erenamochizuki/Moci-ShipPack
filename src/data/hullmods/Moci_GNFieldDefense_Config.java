package data.hullmods;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/**
 * GN力场防御系统配置
 * 参考光环src的HSIHaloV2配置
 */
public class Moci_GNFieldDefense_Config {
    
    // ========== 护盾容量配置 ==========
    // 护盾容量 = 船只最大幅能 * 倍率
    public static final Map<HullSize, Float> SHIELD_CAP = new HashMap<HullSize, Float>();
    static {
        SHIELD_CAP.put(HullSize.FIGHTER, 1.2f);      // 战机：120%幅能
        SHIELD_CAP.put(HullSize.FRIGATE, 1.1f);      // 护卫舰：110%幅能
        SHIELD_CAP.put(HullSize.DESTROYER, 1.0f);    // 驱逐舰：100%幅能
        SHIELD_CAP.put(HullSize.CRUISER, 0.9f);      // 巡洋舰：90%幅能
        SHIELD_CAP.put(HullSize.CAPITAL_SHIP, 0.8f); // 主力舰：80%幅能
        SHIELD_CAP.put(HullSize.DEFAULT, 1.0f);
    }
    
    // ========== 护盾回复配置 ==========
    // 护盾回复速度 = 船只散热速率 * 倍率
    public static final Map<HullSize, Float> REGEN_MAX_SPEED = new HashMap<HullSize, Float>();
    static {
        REGEN_MAX_SPEED.put(HullSize.FIGHTER, 1.2f);      // 战机：120%散热
        REGEN_MAX_SPEED.put(HullSize.FRIGATE, 1.1f);      // 护卫舰：110%散热
        REGEN_MAX_SPEED.put(HullSize.DESTROYER, 1.0f);    // 驱逐舰：100%散热
        REGEN_MAX_SPEED.put(HullSize.CRUISER, 0.9f);      // 巡洋舰：90%散热
        REGEN_MAX_SPEED.put(HullSize.CAPITAL_SHIP, 0.8f); // 主力舰：80%散热
        REGEN_MAX_SPEED.put(HullSize.DEFAULT, 1.0f);
    }
    
    // 护盾回复冷却时间（受到伤害后多久开始回复）
    public static final Map<HullSize, Float> REGEN_CD = new HashMap<HullSize, Float>();
    static {
        REGEN_CD.put(HullSize.FIGHTER, 2.0f);      // 战机：2秒
        REGEN_CD.put(HullSize.FRIGATE, 3.0f);      // 护卫舰：3秒
        REGEN_CD.put(HullSize.DESTROYER, 4.0f);    // 驱逐舰：4秒
        REGEN_CD.put(HullSize.CRUISER, 5.0f);      // 巡洋舰：5秒
        REGEN_CD.put(HullSize.CAPITAL_SHIP, 6.0f); // 主力舰：6秒
        REGEN_CD.put(HullSize.DEFAULT, 3.0f);
    }
    
    // 护盾重启时间（护盾被击破后的重启延迟）
    public static final Map<HullSize, Float> SHIELD_RESTART_TIME = new HashMap<HullSize, Float>();
    static {
        SHIELD_RESTART_TIME.put(HullSize.FIGHTER, 4.0f);      // 战机：4秒
        SHIELD_RESTART_TIME.put(HullSize.FRIGATE, 5.0f);      // 护卫舰：5秒
        SHIELD_RESTART_TIME.put(HullSize.DESTROYER, 6.0f);    // 驱逐舰：6秒
        SHIELD_RESTART_TIME.put(HullSize.CRUISER, 7.0f);      // 巡洋舰：7秒
        SHIELD_RESTART_TIME.put(HullSize.CAPITAL_SHIP, 8.0f); // 主力舰：8秒
        SHIELD_RESTART_TIME.put(HullSize.DEFAULT, 5.0f);
    }
    
    // 护盾重启所需的最低充能百分比
    public static final float SHIELD_RESTART_THRESHOLD = 0.5f; // 50%
    
    // ========== 护盾效率配置 ==========
    // 默认伤害吸收率（1.0 = 100%伤害转化为护盾消耗）
    public static final float DEFAULT_DAMAGE_TAKEN = 1.0f;
    
    // 护盾装甲值
    public static final float SHIELD_ARMOR_MAX = 500f; // 装甲值上限（防止过高）
    public static final float ARMOR_FRAC = 0.01f; // 护盾容量的1%转化为装甲值
    
    // ========== 回复机制配置 ==========
    // 过载时回复速度惩罚
    public static final float VENTING_REGEN_LOSS = 0.5f; // 过载时回复速度降低50%
    
    // 护盾回复消耗幅能的比率（0 = 不消耗幅能）
    public static final float REGEN_FLUX_RATE = 0.0f; // GN力场不消耗幅能回复
    
    // 额外护盾容量（超过基础容量的部分）
    public static final float BUFFER_CAP = 0.3f; // 30%额外容量作为缓冲
    
    // ========== 特殊机制配置 ==========
    // 过载时是否阻挡伤害
    public static final float SHIELD_BLOCK_WHEN_VENTING = 1.0f; // 1.0 = 可以阻挡
    
    // 低护盾值时停止回复的阈值（防止频繁开关）
    public static final float LOW_REGEN_STOP_LIMIT = 0.1f; // 10%以下停止回复
    
    // 护盾回复阻挡的最大伤害值
    public static final float SHIELD_BLOCK_MAX = 1000f;
}
