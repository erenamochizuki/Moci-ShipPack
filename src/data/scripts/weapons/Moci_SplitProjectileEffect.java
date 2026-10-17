package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static com.fs.starfarer.api.util.Misc.ZERO;

/**
 * 分裂弹丸效果
 * 
 * 工作原理：
 * 1. 武器发射主弹（普通弹丸）
 * 2. 主弹飞行到射程一半时自动分裂
 * 3. 分裂成多个子弹，每个子弹继承部分伤害
 * 4. 子弹带有散布角度，形成扇形覆盖
 * 
 * 使用方法：
 * 在 weapon_data.csv 中设置：
 * customAncillary: data.scripts.weapons.Moci_SplitProjectileEffect
 */
public class Moci_SplitProjectileEffect implements EveryFrameWeaponEffectPlugin {
    
    // ========== 可调参数 ==========
    
    // 分裂子弹数量
    private static final int SPLIT_COUNT = 5;
    
    // 子弹散布角度（度）
    private static final float SPLIT_INACCURACY = 10f;
    
    // 子弹速度随机变化范围（±10%）
    private static final float SPLIT_SPEED_VARIATION = 0.1f;
    
    // 伤害倍率（总伤害会乘以这个值再分配给子弹）
    // 例如：1.25 表示分裂后总伤害提升25%
    private static final float DAMAGE_MULTIPLIER = 1.25f;
    
    // 分裂时机（0.5 = 飞行到射程一半时分裂）
    private static final float SPLIT_TIMING = 0.5f;
    
    // 子弹武器ID（需要在 weapon_data.csv 中定义）
    // 这个武器应该是一个简单的弹丸，不需要特殊效果
    private static final String SPLIT_WEAPON_ID = "moci_split_submunition";
    
    // 视觉效果颜色
    private static final Color MUZZLE_COLOR = new Color(255, 180, 113);  // 橙黄色
    private static final Color SPLIT_COLOR = new Color(232, 221, 204);   // 米白色
    
    // ========== 内部变量 ==========
    
    // 追踪所有需要分裂的弹丸
    private List<DamagingProjectileAPI> trackedProjectiles = new ArrayList<DamagingProjectileAPI>();
    
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 游戏暂停时不处理
        if (engine.isPaused()) {
            return;
        }
        
        ShipAPI ship = weapon.getShip();
        if (ship == null) {
            return;
        }
        
        // ========== 第一步：检测新发射的弹丸 ==========
        // 扫描武器周围的弹丸，找到新发射的
        for (DamagingProjectileAPI proj : CombatUtils.getProjectilesWithinRange(
                weapon.getLocation(), 300f)) {
            
            // 过滤条件：
            // 1. 必须是本武器发射的
            // 2. 还没有被追踪
            // 3. 在游戏中有效
            if (proj.getWeapon() != weapon || 
                trackedProjectiles.contains(proj) || 
                !engine.isEntityInPlay(proj)) {
                continue;
            }
            
            // 找到新弹丸，加入追踪列表
            trackedProjectiles.add(proj);
        }
        
        // ========== 第二步：处理已追踪的弹丸 ==========
        List<DamagingProjectileAPI> toRemove = new ArrayList<DamagingProjectileAPI>();
        
        for (DamagingProjectileAPI proj : trackedProjectiles) {
            // 检查弹丸是否还有效
            if (!engine.isEntityInPlay(proj)) {
                toRemove.add(proj);
                continue;
            }
            
            // ========== 第三步：判断是否到达分裂时机 ==========
            // 计算分裂时间点：(射程 / 速度) * 分裂时机
            float splitDuration = (weapon.getRange() / weapon.getProjectileSpeed()) * SPLIT_TIMING;
            
            // 如果弹丸飞行时间超过分裂时间，执行分裂
            if (proj.getElapsed() > splitDuration) {
                performSplit(proj, weapon, ship, engine);
                toRemove.add(proj);
            }
        }
        
        // ========== 第四步：清理已处理的弹丸 ==========
        for (DamagingProjectileAPI proj : toRemove) {
            trackedProjectiles.remove(proj);
        }
    }
    
    /**
     * 执行弹丸分裂
     */
    private void performSplit(DamagingProjectileAPI proj, WeaponAPI weapon, 
                             ShipAPI ship, CombatEngineAPI engine) {
        
        Vector2f loc = proj.getLocation();
        float projAngle = proj.getFacing();
        float projDamage = proj.getDamageAmount();
        
        // ========== 视觉效果 ==========
        // 分裂时的爆炸效果
        engine.addSmoothParticle(loc, ZERO, 200f, 0.5f, 0.1f, MUZZLE_COLOR);
        engine.addSmoothParticle(loc, ZERO, 100f, 0.5f, 0.25f, SPLIT_COLOR);
        
        // 烟雾粒子效果
        for (int i = 0; i < 8; i++) {
            float angle = (360f / 8f) * i;
            Vector2f particleVel = MathUtils.getPointOnCircumference(ZERO, 50f, angle);
            engine.addSmokeParticle(loc, particleVel, 
                MathUtils.getRandomNumberInRange(15f, 25f), 
                0.6f, 
                0.5f, 
                SPLIT_COLOR);
        }
        
        // ========== 生成子弹 ==========
        // 计算每个子弹的伤害：(原伤害 * 倍率) / 子弹数量
        float subDamage = (projDamage * DAMAGE_MULTIPLIER) / (float)SPLIT_COUNT;
        
        for (int i = 0; i < SPLIT_COUNT; i++) {
            // 计算散布角度（双重随机实现正态分布）
            float angleOffset = 
                MathUtils.getRandomNumberInRange(-SPLIT_INACCURACY / 2, SPLIT_INACCURACY / 2) + 
                MathUtils.getRandomNumberInRange(-SPLIT_INACCURACY / 2, SPLIT_INACCURACY / 2);
            
            // 生成子弹
            DamagingProjectileAPI subProj = (DamagingProjectileAPI) engine.spawnProjectile(
                ship,                           // 发射者
                weapon,                         // 武器
                SPLIT_WEAPON_ID,               // 子弹武器ID
                loc,                           // 位置
                projAngle + angleOffset,       // 角度（带散布）
                new Vector2f(0, 0)            // 初始速度（会被武器数据覆盖）
            );
            
            // 随机化子弹速度（模拟不稳定性）
            float speedMod = MathUtils.getRandomNumberInRange(
                1 - SPLIT_SPEED_VARIATION, 
                1 + SPLIT_SPEED_VARIATION
            );
            subProj.getVelocity().x *= speedMod;
            subProj.getVelocity().y *= speedMod;
            
            // 设置子弹伤害
            subProj.setDamageAmount(subDamage);
            
            // 如果子弹有近炸引信AI，更新其伤害数据
            if (subProj.getAI() instanceof ProximityFuseAIAPI) {
                ProximityFuseAIAPI fuseAI = (ProximityFuseAIAPI) subProj.getAI();
                fuseAI.updateDamage();
            }
        }
        
        // ========== 移除原弹丸 ==========
        engine.removeEntity(proj);
    }
}
